package com.abdo.barq.core

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference

/**
 * IDM-style downloader: the file is cut into parts that download at the same time over several
 * connections. When a connection finishes its part early it takes half of the biggest part that is
 * still left, so all connections stay busy until the end. Progress lives in [item].segments, so a
 * download continues from where it stopped, even after the app is closed.
 */
class SegmentedDownloader(
    private val item: DownloadItem,
    private val partFileOf: () -> File,
    private val cfg: NetConfig,
    private val retries: Int,
    private val limiter: RateLimiter,
    private val global: RateLimiter,
    private val quota: Quota?,
    private val listener: Listener,
) {
    interface Listener {
        /** Called once the server answered; may rename the item before data is written. */
        fun onProbe(info: ProbeInfo)
        fun onSegmentsChanged() {}
        fun onFinished()
        fun onFailed(e: Throwable)
        fun onStopped() {}
    }

    companion object {
        const val MIN_SEGMENT = 256L * 1024        // never cut parts smaller than this
        const val MIN_SPLIT = 1024L * 1024         // a part must have this much left to be split
        private const val BUF = 64 * 1024
    }

    @Volatile private var stopped = false
    @Volatile private var streamEnded = false
    private val conns = Collections.synchronizedSet(HashSet<HttpURLConnection>())
    private val segLock = Object()
    private var thread: Thread? = null
    private val failure = AtomicReference<Throwable?>(null)
    private var channel: FileChannel? = null
    private var url: String = item.url
    private lateinit var partFile: File

    fun start() {
        val t = Thread({ runSafely() }, "dl-${item.id}")
        thread = t
        t.start()
    }

    fun stop() {
        stopped = true
        synchronized(conns) { conns.toList() }.forEach { runCatching { it.disconnect() } }
    }

    fun join(ms: Long = 0) { thread?.join(ms) }

    private fun req(u: String = url) = Req(u, item.referer, item.cookies, item.userAgent, item.authUser, item.authPass)

    private fun runSafely() {
        try {
            run()
        } catch (e: Throwable) {
            if (stopped) listener.onStopped() else listener.onFailed(e)
        } finally {
            runCatching { channel?.close() }
        }
    }

    private fun run() {
        // 1. Ask the server about the file.
        var info = HttpUtil.probe(req(), cfg)
        if (info.contentType == "text/html" && GDrive.fileId(item.url) != null) {
            // Big Drive files answer with a warning page: take the real link from its form.
            val (_, html) = HttpUtil.getText(info.finalUrl, cfg)
            val link = GDrive.fromWarningPage(html)
            if (link != null) {
                url = link
                info = HttpUtil.probe(req(link), cfg, link)
            }
        }
        if (stopped) { listener.onStopped(); return }
        val hadProgress = item.segments.any { it.downloaded > 0 }
        if (hadProgress && item.size > 0 && info.size > 0 && info.size != item.size) {
            throw IOException("The file on the server changed (size ${item.size} → ${info.size}). Use Redownload.")
        }
        item.resumable = info.resumable
        if (info.size > 0) item.size = info.size
        listener.onProbe(info)
        if (stopped) { listener.onStopped(); return }

        // 2. Plan the parts.
        partFile = partFileOf()
        val size = item.size
        val canSplit = info.resumable && size > 0
        synchronized(segLock) {
            val valid = item.segments.isNotEmpty() &&
                item.segments.maxOf { it.end } == size - 1 &&
                item.segments.all { it.start >= 0 && it.downloaded >= 0 && (it.end < 0 || it.downloaded <= it.end - it.start + 1) }
            if (!canSplit || !partFile.exists() || !valid) item.segments.clear()
            if (item.segments.isEmpty()) {
                if (canSplit) {
                    val n = maxOf(1, minOf(item.connections.toLong(), size / MIN_SEGMENT).toInt())
                    val part = size / n
                    for (i in 0 until n) {
                        val s = i * part
                        val e = if (i == n - 1) size - 1 else (i + 1) * part - 1
                        item.segments.add(Segment(s, e, 0))
                    }
                } else {
                    item.segments.add(Segment(0, if (size > 0) size - 1 else -1, 0))
                }
            }
            item.segments.forEach { it.active = false }
            item.downloaded = item.segments.sumOf { it.downloaded }
        }
        listener.onSegmentsChanged()

        partFile.parentFile?.mkdirs()
        val raf = RandomAccessFile(partFile, "rw")
        if (!canSplit && item.downloaded == 0L) raf.setLength(0)
        if (canSplit && raf.length() < size) raf.setLength(size)
        channel = raf.channel

        // 3. Start the connections.
        val workers = if (canSplit) maxOf(1, item.connections) else 1
        val latch = CountDownLatch(workers)
        repeat(workers) { idx ->
            Thread({
                try {
                    workerLoop(canSplit)
                } catch (e: Throwable) {
                    if (!stopped) {
                        failure.compareAndSet(null, e)
                        stop()
                    }
                } finally {
                    latch.countDown()
                }
            }, "dl-${item.id}-$idx").start()
        }
        latch.await()
        runCatching { channel?.force(false) }
        runCatching { channel?.close() }
        channel = null

        val err = failure.get()
        when {
            err != null -> listener.onFailed(err)
            stopped -> listener.onStopped()
            streamEnded || item.segments.all { it.done } -> {
                if (size > 0 && partFile.length() > size) RandomAccessFile(partFile, "rw").use { it.setLength(size) }
                listener.onFinished()
            }
            else -> listener.onFailed(IOException("The download stopped before the end"))
        }
    }

    private fun workerLoop(canSplit: Boolean) {
        while (!stopped) {
            val seg = takeSegment(canSplit) ?: return
            try {
                download(seg, canSplit)
            } finally {
                seg.active = false
            }
        }
    }

    /** Gives a connection the next part: a free one, or half of the biggest part still left. */
    private fun takeSegment(canSplit: Boolean): Segment? = synchronized(segLock) {
        if (streamEnded) return@synchronized null
        item.segments.firstOrNull { !it.done && !it.active }?.let {
            it.active = true
            return@synchronized it
        }
        if (!canSplit) return@synchronized null
        val biggest = item.segments.filter { it.active && !it.done }.maxByOrNull { it.remaining } ?: return@synchronized null
        synchronized(biggest) {
            val rem = biggest.remaining
            if (rem < MIN_SPLIT * 2) return@synchronized null
            val mid = biggest.pos + rem / 2
            val tail = Segment(mid, biggest.end, 0)
            biggest.end = mid - 1
            tail.active = true
            item.segments.add(item.segments.indexOf(biggest) + 1, tail)
            listener.onSegmentsChanged()
            tail
        }
    }

    private fun download(seg: Segment, canSplit: Boolean) {
        var attempt = 0
        val buf = ByteArray(BUF)
        while (!stopped) {
            if (seg.done) return
            val from = seg.pos
            var conn: HttpURLConnection? = null
            try {
                val range = if (canSplit) "bytes=$from-${seg.end}" else if (from > 0) "bytes=$from-" else null
                conn = HttpUtil.open(req(), cfg, range)
                conns.add(conn)
                val code = conn.responseCode
                if (code >= 400) throw HttpStatusException(code, "HTTP $code ${conn.responseMessage ?: ""}".trim())
                if (range != null && code != 206) {
                    if (from == 0L && !canSplit) {
                        // fine: whole file from the start
                    } else throw NotResumableException()
                }
                val input: InputStream = conn.inputStream
                var eof = false
                while (!stopped) {
                    val want = if (seg.end >= 0) minOf(BUF.toLong(), seg.end - seg.pos + 1).toInt() else BUF
                    if (want <= 0) break
                    val n = input.read(buf, 0, want)
                    if (n < 0) { eof = true; break }
                    if (n == 0) continue
                    limiter.acquire(n) { stopped }
                    global.acquire(n) { stopped }
                    quota?.waitIfNeeded(n) { stopped }
                    var finished = false
                    synchronized(seg) {
                        val p = seg.pos
                        val allowed = if (seg.end >= 0) minOf(n.toLong(), seg.end - p + 1).toInt() else n
                        if (allowed > 0) {
                            writeFully(ByteBuffer.wrap(buf, 0, allowed), p)
                            seg.downloaded += allowed
                            addProgress(allowed.toLong())
                        }
                        if (seg.end >= 0 && seg.pos > seg.end) finished = true
                    }
                    if (finished) break
                    attempt = 0
                }
                runCatching { input.close() }
                if (stopped) return
                if (seg.done) return
                if (eof && seg.end < 0) {
                    // unknown size: the stream ended, so the file is complete
                    synchronized(seg) { if (seg.downloaded > 0) seg.end = seg.pos - 1 }
                    item.size = item.downloaded
                    streamEnded = true
                    return
                }
                if (eof) throw IOException("The connection closed early")
            } catch (e: NotResumableException) {
                throw e
            } catch (e: HttpStatusException) {
                if (stopped) return
                if (e.code in listOf(401, 403, 404, 410) || ++attempt > retries) throw e
                sleepRetry(attempt)
            } catch (e: IOException) {
                if (stopped) return
                if (++attempt > retries) throw e
                if (!canSplit && seg.pos > 0) {
                    // the server cannot continue a broken transfer: start this file again
                    synchronized(seg) { seg.downloaded = 0 }
                    synchronized(item) { item.downloaded = 0 }
                    runCatching { channel?.truncate(0) }
                }
                sleepRetry(attempt)
            } finally {
                conn?.let { conns.remove(it); runCatching { it.disconnect() } }
            }
        }
    }

    private fun addProgress(n: Long) {
        synchronized(item) { item.downloaded += n }
    }

    private fun writeFully(bb: ByteBuffer, pos: Long) {
        var p = pos
        val ch = channel ?: throw IOException("File closed")
        while (bb.hasRemaining()) {
            val w = ch.write(bb, p)
            p += w
        }
    }

    private fun sleepRetry(attempt: Int) {
        val ms = minOf(30_000L, 1500L * attempt)
        var waited = 0L
        while (waited < ms && !stopped) {
            Thread.sleep(250); waited += 250
        }
    }
}
