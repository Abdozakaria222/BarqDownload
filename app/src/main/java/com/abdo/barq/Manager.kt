package com.abdo.barq

import android.content.Context
import android.media.MediaScannerConnection
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import com.abdo.barq.core.Categories
import com.abdo.barq.core.Category
import com.abdo.barq.core.Checksum
import com.abdo.barq.core.DownloadItem
import com.abdo.barq.core.GDrive
import com.abdo.barq.core.Kind
import com.abdo.barq.core.Names
import com.abdo.barq.core.NotResumableException
import com.abdo.barq.core.ProbeInfo
import com.abdo.barq.core.QueueInfo
import com.abdo.barq.core.Quota
import com.abdo.barq.core.RateLimiter
import com.abdo.barq.core.SegmentedDownloader
import com.abdo.barq.core.St
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Something that moves bytes for one download. */
interface Runner {
    val item: DownloadItem
    fun start()
    fun stop()
    fun applyLimit() {}
}

sealed class UiEvent {
    class Completed(val item: DownloadItem) : UiEvent()
    class Failed(val item: DownloadItem) : UiEvent()
    class Message(val text: String) : UiEvent()
    class Countdown(val action: Int) : UiEvent()
    class TorrentFile(val path: String) : UiEvent()
    object Exit : UiEvent()
}

/** The heart of the app: the list, the queues, starting/stopping and what happens at the end. */
object Manager {
    lateinit var ctx: Context
    val items = CopyOnWriteArrayList<DownloadItem>()
    val queues = CopyOnWriteArrayList<QueueInfo>()
    val categories = CopyOnWriteArrayList<Category>()
    val tick = MutableStateFlow(0L)
    val events = MutableSharedFlow<UiEvent>(extraBufferCapacity = 32)
    val runners = ConcurrentHashMap<Long, Runner>()
    val global = RateLimiter(0)
    val quota = Quota()
    val main = Handler(Looper.getMainLooper())
    val bg = Executors.newScheduledThreadPool(2)
    private val lock = Any()
    private val lastBytes = ConcurrentHashMap<Long, Long>()
    private var lastSave = 0L
    @Volatile private var hadActivity = false
    private val visibleCount = java.util.concurrent.atomic.AtomicInteger(0)
    val appVisible: Boolean get() = visibleCount.get() > 0
    fun activityStarted() { visibleCount.incrementAndGet() }
    fun activityStopped() { if (visibleCount.decrementAndGet() < 0) visibleCount.set(0) }
    private var initialized = false

    const val MAIN_QUEUE = "main"

    fun init(c: Context) {
        if (initialized) return
        initialized = true
        ctx = c.applicationContext
        items.addAll(Store.loadItems())
        queues.addAll(Store.loadQueues())
        categories.addAll(Store.loadCategories())
        if (queues.none { it.id == MAIN_QUEUE }) queues.add(0, QueueInfo(MAIN_QUEUE, "Main queue"))
        if (categories.isEmpty()) categories.addAll(Categories.defaults())
        items.maxOfOrNull { it.id }?.let { if (Prefs.nextId <= it) Prefs.nextId = it + 1 }
        if (Prefs.limiterAlwaysOn) Prefs.limiterOn = true
        applyLimits()
        // downloads that were running when the app was closed
        val resume = ArrayList<DownloadItem>()
        for (it in items) {
            if (it.status == St.RUNNING || it.status == St.WAITING) {
                if (Prefs.autoResume) resume.add(it)
                it.status = St.PAUSED
            }
        }
        val runningQueues = queues.filter { it.running }.map { it.id }
        queues.forEach { it.running = false }
        bg.scheduleWithFixedDelay({ safe { onTick() } }, 1, 1, TimeUnit.SECONDS)
        main.postDelayed({
            resume.forEach { start(it.id, manual = false) }
            queues.filter { it.startOnAppStart || it.id in runningQueues }.forEach { startQueue(it.id) }
            Scheduler.rescheduleAll(ctx)
        }, 800)
    }

    private inline fun safe(block: () -> Unit) {
        try { block() } catch (e: Throwable) { android.util.Log.e("Barq", "error", e) }
    }

    fun find(id: Long): DownloadItem? = items.firstOrNull { it.id == id }

    fun changed(save: Boolean = true) {
        tick.value = tick.value + 1
        if (save) Store.saveSoon()
    }

    fun category(id: String): Category? = categories.firstOrNull { it.id == id }

    fun categoryName(id: String): String = when (id) {
        Categories.GENERAL, "" -> tr("General", "عام")
        "compressed" -> tr("Compressed", "مضغوطة")
        "documents" -> tr("Documents", "مستندات")
        "music" -> tr("Music", "موسيقى")
        "programs" -> tr("Programs", "برامج")
        "video" -> tr("Video", "فيديو")
        "images" -> tr("Images", "صور")
        "torrents" -> tr("Torrents", "تورنت")
        else -> category(id)?.name ?: id
    }

    fun queueName(id: String?): String = when (id) {
        null -> ""
        MAIN_QUEUE -> tr("Main queue", "قائمة الانتظار الرئيسية")
        else -> queues.firstOrNull { it.id == id }?.name ?: id
    }

    /** Folder for a category: its own folder, or main/Category when sorting is on. */
    fun dirFor(catId: String): String {
        category(catId)?.dir?.takeIf { it.isNotBlank() }?.let { return it }
        val main = Prefs.mainDir.ifBlank { Prefs.defaultMainDir() }
        if (!Prefs.sortIntoCategories) return main
        val folder = when (catId) {
            Categories.GENERAL, "" -> "General"
            "compressed" -> "Compressed"
            "documents" -> "Documents"
            "music" -> "Music"
            "programs" -> "Programs"
            "video" -> "Video"
            "images" -> "Images"
            "torrents" -> "Torrents"
            else -> Names.sanitize(category(catId)?.name ?: catId)
        }
        return File(main, folder).absolutePath
    }

    fun partFileFor(it: DownloadItem): File {
        val tmp = Prefs.tempDir
        val dir = if (tmp.isNotBlank()) File(tmp) else File(it.dir)
        return File(dir, ".${it.id}.barqpart")
    }

    fun finalFile(it: DownloadItem): File = File(it.dir, it.fileName)

    // ------------------------------------------------------------------ adding

    /** A new HTTP/video/torrent item with folder and category filled in. */
    fun newItem(kind: Kind, url: String, fileName: String? = null): DownloadItem {
        val fixedUrl = if (kind == Kind.HTTP) GDrive.direct(url.trim()) else url.trim()
        val it = DownloadItem(Prefs.newId(), kind, fixedUrl)
        it.fileName = Names.sanitize(fileName?.takeIf { n -> n.isNotBlank() } ?: Names.fromUrl(fixedUrl))
        it.category = if (kind == Kind.TORRENT) "torrents" else Categories.detect(categories, it.fileName, fixedUrl)
        it.dir = dirFor(it.category)
        it.connections = Prefs.connectionsFor(fixedUrl, Prefs.defaultConnections)
        return it
    }

    fun findDuplicate(url: String): DownloadItem? {
        val u = url.trim()
        return items.firstOrNull { it.url == u || it.url == GDrive.direct(u) }
    }

    fun add(it: DownloadItem, start: Boolean, queueId: String? = null) {
        if (queueId != null) {
            it.queueId = queueId
            it.queuePos = (items.filter { x -> x.queueId == queueId }.maxOfOrNull { x -> x.queuePos } ?: 0) + 1
        }
        it.status = if (start) St.PAUSED else if (queueId != null) St.QUEUED else St.PAUSED
        items.add(0, it)
        changed()
        if (start) start(it.id)
        else if (queueId != null && queues.firstOrNull { q -> q.id == queueId }?.running == true) pump()
    }

    // ------------------------------------------------------------------ start / stop

    private fun httpCount() = runners.values.count { it.item.kind != Kind.TORRENT }

    private fun atCapacity(it: DownloadItem): Boolean {
        if (it.kind == Kind.TORRENT) return false
        val max = Prefs.maxParallel
        return max > 0 && httpCount() >= max
    }

    fun onWifi(): Boolean {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ||
            !caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
    }

    fun start(id: Long, manual: Boolean = true) = synchronized(lock) {
        val it = find(id) ?: return@synchronized
        if (it.status == St.DONE || runners.containsKey(id)) return@synchronized
        if (manual) it.retryCount = 0
        if (atCapacity(it)) {
            it.status = St.WAITING
            it.phase = null
            changed()
            return@synchronized
        }
        launch(it)
    }

    private fun launch(it: DownloadItem) {
        if (Prefs.wifiOnly && !onWifi()) {
            it.status = St.WAITING
            it.phase = tr("Waiting for Wi-Fi", "في انتظار الواي فاي")
            changed()
            return
        }
        val r: Runner = when (it.kind) {
            Kind.HTTP -> HttpRunner(it)
            Kind.VIDEO -> VideoRunner(it)
            Kind.TORRENT -> TorrentRunner(it)
        }
        runners[it.id] = r
        it.status = St.RUNNING
        it.error = null
        it.phase = tr("Connecting…", "جارٍ الاتصال…")
        it.lastTry = System.currentTimeMillis()
        it.speed = 0
        lastBytes[it.id] = it.downloaded
        hadActivity = true
        changed()
        DownloadService.ensure(ctx)
        try {
            r.start()
        } catch (e: Throwable) {
            onFailed(it, e)
        }
    }

    /** Stops a download; [to] is the state it gets (paused, or queued when a queue stops). */
    fun pause(id: Long, to: St = St.PAUSED) {
        val it = find(id) ?: return
        val r = runners[id]
        if (r == null) {
            if (it.status == St.WAITING || it.status == St.QUEUED && to == St.PAUSED) { it.status = to; it.phase = null; changed() }
            if (it.seeding) { TorrentEngine.stopSeeding(it); changed() }
            return
        }
        it.status = to
        it.phase = tr("Stopping…", "جارٍ الإيقاف…")
        pendingState[id] = to
        r.stop()
        changed()
    }

    private val pendingState = ConcurrentHashMap<Long, St>()

    fun pauseAll() {
        items.forEach { if (it.status == St.WAITING) it.status = St.PAUSED }
        queues.forEach { it.running = false }
        runners.keys.toList().forEach { pause(it) }
        changed()
    }

    fun resumeAll() {
        items.filter { it.status == St.PAUSED || it.status == St.ERROR }.sortedBy { it.dateAdded }.forEach { start(it.id) }
    }

    fun redownload(id: Long) {
        val it = find(id) ?: return
        if (runners.containsKey(id)) return
        if (it.kind == Kind.TORRENT) TorrentEngine.remove(it, deleteFiles = true)
        File(it.dir, it.fileName).takeIf { f -> f.exists() && it.status == St.DONE }?.delete()
        partFileFor(it).delete()
        it.segments.clear(); it.downloaded = 0; it.size = -1; it.resumable = null
        it.status = St.PAUSED; it.dateDone = 0; it.error = null; it.seeding = false
        start(id)
    }

    fun delete(ids: Collection<Long>, deleteFiles: Boolean) {
        for (id in ids) {
            val it = find(id) ?: continue
            runners.remove(id)?.stop()
            val inSession = it.kind == Kind.TORRENT && TorrentEngine.handle(it) != null
            if (it.kind == Kind.TORRENT) TorrentEngine.remove(it, deleteFiles)
            if (deleteFiles && it.kind == Kind.TORRENT && !inSession && it.fileName.isNotBlank()) {
                // the torrent is not loaded any more: delete what it downloaded ourselves
                val f = File(it.dir, it.fileName)
                if (f.exists() && f.canonicalPath != File(it.dir).canonicalPath) f.deleteRecursively()
            }
            if (deleteFiles) {
                partFileFor(it).delete()
                if (it.status == St.DONE && it.kind != Kind.TORRENT) File(it.dir, it.fileName).delete()
                if (it.kind == Kind.VIDEO) VideoEngine.deleteTemp(it)
            }
            items.remove(it)
        }
        changed()
        pump()
    }

    fun deleteCompleted() {
        val ids = items.filter { it.status == St.DONE && !it.seeding }.map { it.id }
        delete(ids, false)
    }

    // ------------------------------------------------------------------ queues

    fun startQueue(qid: String) {
        val q = queues.firstOrNull { it.id == qid } ?: return
        if (q.running) return
        q.running = true
        items.filter { it.queueId == qid && (it.status == St.PAUSED || it.status == St.ERROR) }.forEach {
            it.status = St.QUEUED; it.retryCount = 0
        }
        if (Prefs.soundQueueStart) Notifier.sound(ctx)
        changed()
        pump()
    }

    fun stopQueue(qid: String) {
        val q = queues.firstOrNull { it.id == qid } ?: return
        q.running = false
        items.filter { it.queueId == qid && runners.containsKey(it.id) }.forEach { pause(it.id, St.QUEUED) }
        changed()
    }

    fun moveInQueue(id: Long, up: Boolean) {
        val it = find(id) ?: return
        val list = items.filter { x -> x.queueId == it.queueId && it.queueId != null }.sortedBy { x -> x.queuePos }
        val i = list.indexOf(it)
        val j = if (up) i - 1 else i + 1
        if (i < 0 || j !in list.indices) return
        val other = list[j]
        val p = it.queuePos; it.queuePos = other.queuePos; other.queuePos = p
        if (it.queuePos == other.queuePos) it.queuePos += if (up) -1 else 1
        changed()
    }

    fun setQueue(ids: Collection<Long>, qid: String?) {
        var pos = (items.filter { it.queueId == qid }.maxOfOrNull { it.queuePos } ?: 0)
        for (id in ids) {
            val it = find(id) ?: continue
            it.queueId = qid
            it.queuePos = ++pos
            if (qid != null && it.status == St.PAUSED) it.status = St.QUEUED
            if (qid == null && it.status == St.QUEUED) it.status = St.PAUSED
        }
        changed()
        pump()
    }

    /** Starts what may start: waiting downloads first, then the files of running queues. */
    fun pump() {
        main.post { synchronized(lock) { pumpLocked() } }
    }

    private fun pumpLocked() {
        for (it in items.filter { x -> x.status == St.WAITING }.sortedBy { x -> x.lastTry }) {
            if (runners.containsKey(it.id)) continue
            if (atCapacity(it)) break
            if (Prefs.wifiOnly && !onWifi()) break
            launch(it)
        }
        for (q in queues.filter { x -> x.running }) {
            val running = items.count { x -> x.queueId == q.id && runners.containsKey(x.id) }
            var free = q.maxParallel.coerceAtLeast(1) - running
            val pending = items.filter { x -> x.queueId == q.id && x.status == St.QUEUED && !runners.containsKey(x.id) }.sortedBy { x -> x.queuePos }
            for (it in pending) {
                if (free <= 0 || atCapacity(it)) break
                if (q.saveDir.isNotBlank() && !it.dirLocked) it.dir = q.saveDir
                launch(it)
                free--
            }
            val left = items.count { x -> x.queueId == q.id && (x.status == St.QUEUED || runners.containsKey(x.id)) }
            if (left == 0) {
                q.running = false
                onQueueDone(q)
            }
        }
        if (runners.isEmpty() && items.none { it.status == St.WAITING } && queues.none { it.running } && hadActivity) {
            hadActivity = false
            onAllDone()
        }
    }

    private fun onQueueDone(q: QueueInfo) {
        changed()
        Notifier.message(ctx, tr("Queue finished", "انتهت قائمة الانتظار"), queueName(q.id))
        if (Prefs.soundQueueEnd) Notifier.sound(ctx)
        if (q.whenDone == 1) countdownExit()
    }

    private fun onAllDone() {
        events.tryEmit(UiEvent.Message(tr("All downloads are finished.", "انتهت كل التحميلات.")))
        if (Prefs.whenAllDone == 1) countdownExit()
    }

    private fun countdownExit() {
        if (appVisible) events.tryEmit(UiEvent.Countdown(1)) else exitApp()
    }

    fun exitApp() {
        Store.saveNow()
        runners.keys.toList().forEach { pause(it) }
        DownloadService.stop(ctx)
        events.tryEmit(UiEvent.Exit)
        main.postDelayed({ android.os.Process.killProcess(android.os.Process.myPid()) }, 1500)
    }

    // ------------------------------------------------------------------ engine callbacks

    fun onProbe(it: DownloadItem, info: ProbeInfo) {
        it.phase = null
        if (info.lastModified > 0) it.serverTime = info.lastModified
        if (!it.nameLocked) {
            var name = info.fileName?.let { n -> Names.sanitize(n) }
                ?: if (info.finalUrl != it.url) Names.fromUrl(info.finalUrl) else it.fileName
            name = Names.withExtFromMime(name, info.contentType)
            if (name.isNotBlank() && name != it.fileName) {
                it.fileName = name
                val cat = Categories.detect(categories, name, it.url, it.referer)
                if (cat != it.category) {
                    it.category = cat
                    if (!it.dirLocked) {
                        val oldPart = partFileFor(it)
                        it.dir = dirFor(cat)
                        val newPart = partFileFor(it)
                        if (oldPart.exists() && oldPart != newPart) { newPart.parentFile?.mkdirs(); oldPart.renameTo(newPart) }
                    }
                }
            }
        }
        changed()
    }

    fun onStopped(it: DownloadItem) {
        runners.remove(it.id)
        val to = pendingState.remove(it.id) ?: St.PAUSED
        it.status = to
        it.speed = 0; it.phase = null
        changed()
        pump()
    }

    fun onFailed(it: DownloadItem, e: Throwable) {
        runners.remove(it.id)
        pendingState.remove(it.id)
        it.speed = 0; it.phase = null
        if (e is NotResumableException && it.autoRestarts < 2) {
            // the server stopped accepting ranges: start again with one connection
            it.autoRestarts++
            it.segments.clear(); it.downloaded = 0; it.resumable = false
            partFileFor(it).delete()
            main.postDelayed({ start(it.id, manual = false) }, 500)
            return
        }
        it.error = (e.message ?: e.javaClass.simpleName).take(400)
        val q = queues.firstOrNull { x -> x.id == it.queueId && x.running }
        if (q != null && it.retryCount < q.retries) {
            it.retryCount++
            it.status = St.QUEUED
            changed()
            main.postDelayed({ pump() }, 5000)
            return
        }
        it.status = St.ERROR
        changed()
        Notifier.failed(ctx, it)
        if (Prefs.soundFailed) Notifier.sound(ctx)
        events.tryEmit(UiEvent.Failed(it))
        pump()
    }

    fun finishHttp(it: DownloadItem) {
        val part = partFileFor(it)
        val dir = File(it.dir).apply { mkdirs() }
        var target = File(dir, it.fileName)
        if (target.exists()) {
            if (it.overwrite) target.delete()
            else {
                it.fileName = Names.unique(dir, it.fileName) { n -> items.any { x -> x !== it && x.dir == it.dir && x.fileName == n && x.status != St.DONE } }
                target = File(dir, it.fileName)
            }
        }
        val ok = part.renameTo(target) || run {
            try { part.copyTo(target, overwrite = true); part.delete(); true } catch (e: Exception) { false }
        }
        if (!ok) { onFailed(it, java.io.IOException(tr("Could not save the file to ", "تعذّر حفظ الملف في ") + it.dir)); return }
        if (Prefs.serverDate && it.serverTime > 0) target.setLastModified(it.serverTime)
        completed(it, target)
    }

    fun completed(it: DownloadItem, file: File?) {
        runners.remove(it.id)
        it.status = St.DONE
        it.dateDone = System.currentTimeMillis()
        it.speed = 0; it.eta = 0; it.phase = null; it.error = null
        if (it.size <= 0 && file != null) it.size = file.length()
        it.downloaded = maxOf(it.downloaded, it.size)
        changed()
        Store.saveSoon(100)
        file?.let { f -> runCatching { MediaScannerConnection.scanFile(ctx, arrayOf(f.absolutePath), null, null) } }
        Notifier.completed(ctx, it)
        if (Prefs.soundDone) Notifier.sound(ctx)
        val isTorrentFile = file != null && it.kind == Kind.HTTP && Names.ext(file.name) == "torrent"
        if (isTorrentFile && Prefs.torrentDefault) events.tryEmit(UiEvent.TorrentFile(file!!.absolutePath))
        else if (appVisible && Prefs.showCompleteDialog) events.tryEmit(UiEvent.Completed(it))
        val sum = it.checksum?.trim()
        if (!sum.isNullOrEmpty() && file != null) verifyChecksum(it, file, sum)
        val q = queues.firstOrNull { x -> x.id == it.queueId }
        if (q?.openWhenDone == true && file != null) main.post { Sys.openFile(ctx, file) }
        if (it.onComplete == 1) countdownExit()
        pump()
    }

    private fun verifyChecksum(it: DownloadItem, f: File, expected: String) {
        bg.execute {
            it.phase = tr("Checking the file…", "جارٍ فحص الملف…")
            changed(false)
            val got = runCatching { Checksum.of(f, Checksum.algoFor(expected)) }.getOrNull()
            it.phase = null
            it.error = if (got != null && got.equals(expected, ignoreCase = true)) null
            else tr("Checksum does not match!", "رمز التحقق غير مطابق!")
            changed()
        }
    }

    // ------------------------------------------------------------------ limiter and ticks

    fun applyLimits() {
        val mobileOnly = Prefs.limiterMobileOnly && runCatching { onWifi() }.getOrDefault(false)
        val on = Prefs.limiterOn && !mobileOnly
        global.bytesPerSecond = if (on) Prefs.limiterKBps.toLong() * 1024 else 0
        quota.limitBytes = Prefs.quotaMb.toLong() * 1024 * 1024
        quota.periodMs = Prefs.quotaHours.toLong() * 3600_000
        runners.values.forEach { it.applyLimit() }
        TorrentEngine.applyLimits()
    }

    private var tickCount = 0

    private fun onTick() {
        tickCount++
        val now = System.currentTimeMillis()
        for (r in runners.values) {
            val it = r.item
            when (it.kind) {
                Kind.TORRENT -> TorrentEngine.update(it)
                Kind.HTTP -> {
                    val d = it.downloaded
                    val prev = lastBytes[it.id] ?: d
                    lastBytes[it.id] = d
                    val inst = (d - prev).coerceAtLeast(0)
                    it.speed = if (it.speed == 0L) inst else (it.speed * 6 + inst * 4) / 10
                    it.eta = if (it.size > 0 && it.speed > 0) (it.size - d) / it.speed else -1
                }
                Kind.VIDEO -> {}
            }
        }
        TorrentEngine.tickSeeding()
        if (tickCount % 10 == 0) applyLimits()
        if (runners.isNotEmpty() || items.any { it.seeding }) {
            tick.value = tick.value + 1
            if (now - lastSave > 3000) { lastSave = now; Store.saveSoon(10) }
        }
        // also when nothing runs any more: the service then stops itself and frees the wake lock
        DownloadService.refresh()
        if (Prefs.wifiOnly && tickCount % 15 == 0 && items.any { it.status == St.WAITING } && runCatching { onWifi() }.getOrDefault(false)) pump()
    }

    // ------------------------------------------------------------------ misc

    fun refreshAddress(id: Long, newUrl: String) {
        val it = find(id) ?: return
        val wasRunning = runners.containsKey(id)
        if (wasRunning) pause(id)
        it.url = GDrive.direct(newUrl.trim())
        it.error = null
        changed()
        if (wasRunning) main.postDelayed({ start(id) }, 1200)
    }

    fun moveRename(id: Long, newName: String, newDir: String): String? {
        val it = find(id) ?: return null
        if (runners.containsKey(id)) return tr("Stop the download first.", "أوقف التحميل أولاً.")
        val name = Names.sanitize(newName)
        if (it.status == St.DONE) {
            val src = File(it.dir, it.fileName)
            val dst = File(newDir, name)
            if (src.exists() && src != dst) {
                if (dst.exists()) return tr("A file with this name is already there.", "يوجد ملف بهذا الاسم بالفعل.")
                dst.parentFile?.mkdirs()
                val ok = src.renameTo(dst) || runCatching { src.copyTo(dst); src.delete(); true }.getOrDefault(false)
                if (!ok) return tr("Could not move the file.", "تعذّر نقل الملف.")
            }
        } else if (newDir != it.dir) {
            val oldPart = partFileFor(it)
            val oldDir = it.dir
            it.dir = newDir
            val newPart = partFileFor(it)
            if (oldPart.exists() && oldPart != newPart) {
                newPart.parentFile?.mkdirs()
                val ok = oldPart.renameTo(newPart) || runCatching { oldPart.copyTo(newPart); oldPart.delete(); true }.getOrDefault(false)
                if (!ok) { it.dir = oldDir; return tr("Could not move the file.", "تعذّر نقل الملف.") }
            }
        }
        it.fileName = name
        it.dir = newDir
        it.nameLocked = true
        it.dirLocked = true
        changed()
        return null
    }
}

class HttpRunner(override val item: DownloadItem) : Runner {
    private val limiter = RateLimiter(0)
    private var dl: SegmentedDownloader? = null

    override fun start() {
        applyLimit()
        val d = SegmentedDownloader(
            item, { Manager.partFileFor(item) }, Prefs.netConfig(), Prefs.retries, limiter, Manager.global, Manager.quota,
            object : SegmentedDownloader.Listener {
                override fun onProbe(info: ProbeInfo) {
                    Manager.onProbe(item, info)
                }
                override fun onSegmentsChanged() {
                    Manager.changed(false)
                }
                override fun onFinished() {
                    Manager.bg.execute { Manager.finishHttp(item) }
                }
                override fun onFailed(e: Throwable) {
                    Manager.main.post { Manager.onFailed(item, e) }
                }
                override fun onStopped() {
                    Manager.main.post { Manager.onStopped(item) }
                }
            })
        dl = d
        d.start()
    }

    override fun stop() { dl?.stop() }

    override fun applyLimit() {
        limiter.bytesPerSecond = if (item.limiterOn && item.speedLimit > 0) item.speedLimit * 1024L else 0
    }
}
