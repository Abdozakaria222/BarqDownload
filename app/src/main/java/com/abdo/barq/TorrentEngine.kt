package com.abdo.barq

import android.content.Context
import com.abdo.barq.core.DownloadItem
import com.abdo.barq.core.Magnet
import org.libtorrent4j.AnnounceEntry
import org.libtorrent4j.Priority
import org.libtorrent4j.SessionHandle
import org.libtorrent4j.SessionManager
import org.libtorrent4j.SessionParams
import org.libtorrent4j.SettingsPack
import org.libtorrent4j.Sha1Hash
import org.libtorrent4j.TorrentFlags
import org.libtorrent4j.TorrentHandle
import org.libtorrent4j.TorrentInfo
import org.libtorrent4j.TorrentStatus
import org.libtorrent4j.swig.torrent_flags_t
import java.io.File
import java.io.IOException

class TorrentFileRow(val index: Int, val path: String, val size: Long, val done: Long, val wanted: Boolean)
class PeerRow(val ip: String, val client: String, val down: Int, val up: Int, val progress: Float)

/** Files of a .torrent before it is added (to choose what to download). */
class TorrentPreview(val name: String, val infoHash: String, val total: Long, val files: List<Pair<String, Long>>)

/** BitTorrent through libtorrent (libtorrent4j): magnet links and .torrent files. */
object TorrentEngine {
    @Volatile var available = true
    @Volatile var error: String? = null
    private var sm: SessionManager? = null

    @Synchronized
    fun session(): SessionManager? {
        sm?.let { return it }
        if (!available) return null
        return try {
            val s = SessionManager(false)
            val sp = SettingsPack()
                .activeDownloads(8)
                .activeSeeds(8)
                .connectionsLimit(300)
                .uploadRateLimit(Prefs.uploadKBps * 1024)
                .downloadRateLimit(if (Manager.global.bytesPerSecond > 0) Manager.global.bytesPerSecond.toInt() else 0)
            s.start(SessionParams(sp))
            sm = s
            s
        } catch (t: Throwable) {
            available = false
            error = t.message ?: t.javaClass.simpleName
            null
        }
    }

    fun applyLimits() {
        val s = sm ?: return
        runCatching {
            s.downloadRateLimit(if (Manager.global.bytesPerSecond > 0) Manager.global.bytesPerSecond.toInt() else 0)
            s.uploadRateLimit(Prefs.uploadKBps * 1024)
        }
    }

    fun preview(file: File): TorrentPreview {
        val ti = TorrentInfo(file)
        val fs = ti.files()
        val files = (0 until fs.numFiles()).map { fs.filePath(it) to fs.fileSize(it) }
        return TorrentPreview(ti.name(), ti.infoHashes().best.toHex(), ti.totalSize(), files)
    }

    /** Copies a .torrent file into the app so it can be added again later. */
    fun storeTorrent(ctx: Context, bytes: ByteArray): File {
        val dir = File(ctx.filesDir, "torrents").apply { mkdirs() }
        val tmp = File(dir, "tmp-${System.nanoTime()}.torrent")
        tmp.writeBytes(bytes)
        val hash = runCatching { TorrentInfo(tmp).infoHashes().best.toHex() }.getOrNull()
        if (hash == null) { tmp.delete(); throw IOException(tr("This is not a valid .torrent file", "هذا ليس ملف تورنت صالحاً")) }
        val f = File(dir, "$hash.torrent")
        if (f.exists()) f.delete()
        tmp.renameTo(f)
        return f
    }

    fun handle(it: DownloadItem): TorrentHandle? {
        val s = sm ?: return null
        val h = it.infoHash ?: return null
        return runCatching { s.find(Sha1Hash.parseHex(h)) }.getOrNull()?.takeIf { x -> x.isValid }
    }

    fun start(it: DownloadItem) {
        val s = session() ?: throw IOException(tr("Torrents are not supported on this device: ", "التورنت غير مدعوم على هذا الجهاز: ") + (error ?: ""))
        handle(it)?.let { h ->
            h.setFlags(TorrentFlags.AUTO_MANAGED)
            h.resume()
            return
        }
        val dir = File(it.dir).apply { mkdirs() }
        val tf = it.torrentFile
        if (tf != null && File(tf).exists()) {
            val ti = TorrentInfo(File(tf))
            it.infoHash = ti.infoHashes().best.toHex()
            if (it.fileName.isBlank() || it.fileName.endsWith(".torrent")) it.fileName = ti.name()
            val pr = it.filePriorities?.takeIf { p -> p.size == ti.numFiles() }?.map { p -> if (p == 0) Priority.IGNORE else Priority.DEFAULT }?.toTypedArray()
            s.download(ti, dir, null, pr, null, torrent_flags_t())
        } else {
            val magnet = Magnet.withTrackers(it.url, Magnet.PUBLIC_TRACKERS + it.extraTrackers)
            if (it.infoHash == null) it.infoHash = Magnet.infoHash(magnet)
            s.download(magnet, dir, torrent_flags_t())
        }
    }

    fun pause(it: DownloadItem) {
        handle(it)?.let { h ->
            runCatching { h.unsetFlags(TorrentFlags.AUTO_MANAGED); h.pause() }
        }
    }

    fun remove(it: DownloadItem, deleteFiles: Boolean) {
        val s = sm ?: return
        val h = handle(it) ?: return
        runCatching { if (deleteFiles) s.remove(h, SessionHandle.DELETE_FILES) else s.remove(h) }
        it.seeding = false
    }

    fun stopSeeding(it: DownloadItem) {
        remove(it, false)
        it.seeding = false
        it.upSpeed = 0
    }

    /** Called every second for running torrents. */
    fun update(it: DownloadItem) {
        val h = handle(it) ?: return
        val st = runCatching { h.status() }.getOrNull() ?: return
        it.size = st.totalWanted()
        it.downloaded = st.totalWantedDone()
        it.speed = st.downloadPayloadRate().toLong()
        it.upSpeed = st.uploadPayloadRate().toLong()
        it.peers = st.numPeers()
        it.seeds = st.numSeeds()
        it.uploaded = st.allTimeUpload()
        it.eta = if (it.speed > 0 && it.size > 0) (it.size - it.downloaded) / it.speed else -1
        if (st.hasMetadata()) {
            val name = runCatching { h.torrentFile()?.name() }.getOrNull() ?: st.name()
            if (!name.isNullOrBlank() && (it.fileName.isBlank() || it.fileName.startsWith("magnet") || it.fileName == it.infoHash)) it.fileName = name
        }
        it.phase = when (st.state()) {
            TorrentStatus.State.CHECKING_FILES -> tr("Checking the files…", "جارٍ فحص الملفات…")
            TorrentStatus.State.DOWNLOADING_METADATA -> tr("Getting the file list from other computers…", "جارٍ جلب قائمة الملفات من الأجهزة الأخرى…")
            else -> if (it.speed == 0L && it.peers == 0) tr("Looking for people who share it…", "جارٍ البحث عمّن يشاركه…") else null
        }
        val finished = st.hasMetadata() && st.totalWanted() > 0 && st.totalWantedDone() >= st.totalWanted() &&
            (st.isFinished || st.isSeeding || st.state() == TorrentStatus.State.SEEDING || st.state() == TorrentStatus.State.FINISHED)
        if (finished && it.status != com.abdo.barq.core.St.DONE) {
            val keep = Prefs.seedMinutes > 0 || Prefs.seedRatio > 0f
            it.seeding = keep
            if (!keep) stopSeeding(it)
            val file = File(it.dir, it.fileName)
            Manager.main.post { Manager.completed(it, if (file.exists()) file else null) }
        }
    }

    /** "Keep sharing a finished torrent for N minutes / until it sent N times what it received". */
    fun tickSeeding() {
        for (it in Manager.items) {
            if (!it.seeding) continue
            val h = handle(it)
            if (h == null) { it.seeding = false; continue }
            runCatching {
                val st = h.status()
                it.upSpeed = st.uploadPayloadRate().toLong()
                it.uploaded = st.allTimeUpload()
                it.peers = st.numPeers()
            }
            val minutes = Prefs.seedMinutes
            val ratio = Prefs.seedRatio
            val timeUp = minutes > 0 && System.currentTimeMillis() - it.dateDone > minutes * 60_000L
            val ratioUp = ratio > 0f && it.size > 0 && it.uploaded.toDouble() / it.size >= ratio
            val stop = when {
                minutes <= 0 && ratio <= 0f -> true
                minutes > 0 && ratio > 0f -> timeUp || ratioUp
                minutes > 0 -> timeUp
                else -> ratioUp
            }
            if (stop) {
                stopSeeding(it)
                Manager.changed()
            }
        }
    }

    fun files(it: DownloadItem): List<TorrentFileRow> {
        val h = handle(it) ?: return emptyList()
        val ti = h.torrentFile() ?: return emptyList()
        val fs = ti.files()
        val prog = runCatching { h.fileProgress() }.getOrNull()
        val pr = runCatching { h.filePriorities() }.getOrNull()
        return (0 until fs.numFiles()).map { i ->
            TorrentFileRow(i, fs.filePath(i), fs.fileSize(i), prog?.getOrNull(i) ?: 0L, pr?.getOrNull(i)?.let { p -> p != Priority.IGNORE } ?: true)
        }
    }

    fun setFileWanted(it: DownloadItem, index: Int, wanted: Boolean) {
        val h = handle(it) ?: return
        h.filePriority(index, if (wanted) Priority.DEFAULT else Priority.IGNORE)
        val pr = runCatching { h.filePriorities() }.getOrNull() ?: return
        it.filePriorities = IntArray(pr.size) { i -> if (pr[i] == Priority.IGNORE) 0 else 4 }
        Manager.changed()
    }

    fun peers(it: DownloadItem): List<PeerRow> {
        val h = handle(it) ?: return emptyList()
        return runCatching { h.peerInfo().map { p -> PeerRow(p.ip(), p.client(), p.downSpeed(), p.upSpeed(), p.progress()) } }.getOrDefault(emptyList())
    }

    fun trackers(it: DownloadItem): List<String> {
        val h = handle(it) ?: return it.extraTrackers
        return runCatching { h.trackers().map { t -> t.url() } }.getOrDefault(it.extraTrackers)
    }

    fun addTracker(it: DownloadItem, url: String) {
        val u = url.trim()
        if (u.isEmpty()) return
        if (u !in it.extraTrackers) it.extraTrackers.add(u)
        handle(it)?.let { h -> runCatching { h.addTracker(AnnounceEntry(u)); h.forceReannounce() } }
        Manager.changed()
    }

    fun recheck(it: DownloadItem) { handle(it)?.let { h -> runCatching { h.forceRecheck() } } }

    fun magnetUri(it: DownloadItem): String = handle(it)?.let { h -> runCatching { h.makeMagnetUri() }.getOrNull() }
        ?: if (it.url.startsWith("magnet:")) it.url else "magnet:?xt=urn:btih:${it.infoHash ?: ""}"

    fun setSequential(it: DownloadItem, on: Boolean) {
        it.seqDownload = on
        handle(it)?.let { h -> runCatching { if (on) h.setFlags(TorrentFlags.SEQUENTIAL_DOWNLOAD) else h.unsetFlags(TorrentFlags.SEQUENTIAL_DOWNLOAD) } }
        Manager.changed()
    }

    /** "First and last pieces first": lets players open a video before it is complete. */
    fun setFirstLast(it: DownloadItem, on: Boolean) {
        it.firstLast = on
        val h = handle(it) ?: return
        runCatching {
            val ti = h.torrentFile() ?: return@runCatching
            val n = ti.numPieces()
            if (n <= 0) return@runCatching
            val p = if (on) Priority.TOP_PRIORITY else Priority.DEFAULT
            h.piecePriority(0, p)
            h.piecePriority(n - 1, p)
        }
        Manager.changed()
    }

    fun applyItemLimit(it: DownloadItem) {
        handle(it)?.let { h -> runCatching { h.setDownloadLimit(if (it.limiterOn && it.speedLimit > 0) it.speedLimit * 1024 else 0) } }
    }
}

class TorrentRunner(override val item: DownloadItem) : Runner {
    override fun start() {
        Manager.bg.execute {
            try {
                TorrentEngine.start(item)
                item.phase = tr("Starting…", "جارٍ البدء…")
                Manager.changed()
                Manager.main.postDelayed({
                    TorrentEngine.applyItemLimit(item)
                    if (item.seqDownload) TorrentEngine.setSequential(item, true)
                    if (item.firstLast) TorrentEngine.setFirstLast(item, true)
                }, 3000)
            } catch (e: Throwable) {
                Manager.main.post { Manager.onFailed(item, e) }
            }
        }
    }

    override fun stop() {
        Manager.bg.execute {
            TorrentEngine.pause(item)
            Manager.main.post { Manager.onStopped(item) }
        }
    }

    override fun applyLimit() = TorrentEngine.applyItemLimit(item)
}
