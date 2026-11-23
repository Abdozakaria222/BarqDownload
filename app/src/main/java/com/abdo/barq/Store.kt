package com.abdo.barq

import android.content.Context
import com.abdo.barq.core.Category
import com.abdo.barq.core.DownloadItem
import com.abdo.barq.core.Kind
import com.abdo.barq.core.QueueInfo
import com.abdo.barq.core.Segment
import com.abdo.barq.core.St
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** Saves the download list, queues and categories as JSON files in the app's private folder. */
object Store {
    private lateinit var dir: File
    private val io = Executors.newSingleThreadScheduledExecutor()
    private var pending: ScheduledFuture<*>? = null
    private val lock = Any()

    fun init(ctx: Context) { dir = ctx.filesDir }

    fun saveSoon(delayMs: Long = 1200) = synchronized(lock) {
        if (pending?.isDone == false) return@synchronized
        pending = io.schedule({ saveNow() }, delayMs, TimeUnit.MILLISECONDS)
    }

    fun saveNow() {
        try {
            writeAtomic(File(dir, "downloads.json"), JSONArray().apply { Manager.items.forEach { put(itemToJson(it)) } }.toString())
            writeAtomic(File(dir, "queues.json"), JSONArray().apply { Manager.queues.forEach { put(queueToJson(it)) } }.toString())
            writeAtomic(File(dir, "categories.json"), JSONArray().apply { Manager.categories.forEach { put(catToJson(it)) } }.toString())
        } catch (e: Exception) {
            android.util.Log.e("Barq", "save failed", e)
        }
    }

    private fun writeAtomic(f: File, text: String) {
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
    }

    fun loadItems(): List<DownloadItem> = readArray("downloads.json").mapNotNull { runCatching { itemFromJson(it) }.getOrNull() }
    fun loadQueues(): List<QueueInfo> = readArray("queues.json").mapNotNull { runCatching { queueFromJson(it) }.getOrNull() }
    fun loadCategories(): List<Category> = readArray("categories.json").mapNotNull { runCatching { catFromJson(it) }.getOrNull() }

    private fun readArray(name: String): List<JSONObject> {
        val f = File(dir, name)
        if (!f.exists()) return emptyList()
        return try {
            val a = JSONArray(f.readText())
            (0 until a.length()).map { a.getJSONObject(it) }
        } catch (e: Exception) { emptyList() }
    }

    private fun JSONObject.optStr(k: String): String? = if (has(k) && !isNull(k)) optString(k) else null

    fun itemToJson(it: DownloadItem) = JSONObject().apply {
        put("id", it.id); put("kind", it.kind.name); put("url", it.url)
        put("name", it.fileName); put("dir", it.dir); put("cat", it.category)
        put("size", it.size); put("dl", it.downloaded); put("st", it.status.name)
        it.error?.let { e -> put("err", e) }
        it.resumable?.let { r -> put("res", r) }
        put("conn", it.connections)
        put("segs", JSONArray().apply { it.segments.forEach { s -> put(JSONArray().put(s.start).put(s.end).put(s.downloaded)) } })
        it.referer?.let { v -> put("ref", v) }; it.parentPage?.let { v -> put("page", v) }
        it.cookies?.let { v -> put("cookies", v) }; it.userAgent?.let { v -> put("ua", v) }
        it.description?.let { v -> put("desc", v) }; it.checksum?.let { v -> put("sum", v) }
        it.authUser?.let { v -> put("user", v) }; it.authPass?.let { v -> put("pass", v) }
        it.queueId?.let { v -> put("q", v) }; put("qpos", it.queuePos)
        put("added", it.dateAdded); put("try", it.lastTry); put("done", it.dateDone); put("stime", it.serverTime)
        put("lim", it.speedLimit); put("limOn", it.limiterOn); put("onDone", it.onComplete)
        put("nameLock", it.nameLocked); put("dirLock", it.dirLocked); put("over", it.overwrite)
        put("retry", it.retryCount)
        it.vQuality?.let { v -> put("vq", v) }; it.vTitle?.let { v -> put("vt", v) }
        put("vsubs", it.vSubs); put("vs", it.vStart); put("ve", it.vEnd); put("vx", it.vExact); put("vn", it.vNumber)
        it.vCookieFile?.let { v -> put("vck", v) }
        it.infoHash?.let { v -> put("ih", v) }; it.torrentFile?.let { v -> put("tf", v) }
        put("seq", it.seqDownload); put("fl", it.firstLast)
        it.filePriorities?.let { p -> put("prio", JSONArray().apply { p.forEach { x -> put(x) } }) }
        if (it.extraTrackers.isNotEmpty()) put("trk", JSONArray(it.extraTrackers))
    }

    fun itemFromJson(o: JSONObject): DownloadItem {
        val it = DownloadItem(o.getLong("id"), Kind.valueOf(o.optString("kind", "HTTP")), o.getString("url"))
        it.fileName = o.optString("name"); it.dir = o.optString("dir"); it.category = o.optString("cat")
        it.size = o.optLong("size", -1); it.downloaded = o.optLong("dl", 0)
        it.status = runCatching { St.valueOf(o.optString("st", "PAUSED")) }.getOrDefault(St.PAUSED)
        it.error = o.optStr("err")
        it.resumable = if (o.has("res")) o.optBoolean("res") else null
        it.connections = o.optInt("conn", 8)
        o.optJSONArray("segs")?.let { a ->
            for (i in 0 until a.length()) {
                val s = a.getJSONArray(i)
                it.segments.add(Segment(s.getLong(0), s.getLong(1), s.getLong(2)))
            }
        }
        it.referer = o.optStr("ref"); it.parentPage = o.optStr("page"); it.cookies = o.optStr("cookies"); it.userAgent = o.optStr("ua")
        it.description = o.optStr("desc"); it.checksum = o.optStr("sum"); it.authUser = o.optStr("user"); it.authPass = o.optStr("pass")
        it.queueId = o.optStr("q"); it.queuePos = o.optInt("qpos", 0)
        it.dateAdded = o.optLong("added", System.currentTimeMillis()); it.lastTry = o.optLong("try", 0)
        it.dateDone = o.optLong("done", 0); it.serverTime = o.optLong("stime", 0)
        it.speedLimit = o.optInt("lim", 0); it.limiterOn = o.optBoolean("limOn", false); it.onComplete = o.optInt("onDone", 0)
        it.nameLocked = o.optBoolean("nameLock", false); it.dirLocked = o.optBoolean("dirLock", false); it.overwrite = o.optBoolean("over", false)
        it.retryCount = o.optInt("retry", 0)
        it.vQuality = o.optStr("vq"); it.vTitle = o.optStr("vt"); it.vSubs = o.optBoolean("vsubs", false)
        it.vStart = o.optDouble("vs", -1.0); it.vEnd = o.optDouble("ve", -1.0); it.vExact = o.optBoolean("vx", false)
        it.vNumber = o.optInt("vn", 0); it.vCookieFile = o.optStr("vck")
        it.infoHash = o.optStr("ih"); it.torrentFile = o.optStr("tf")
        it.seqDownload = o.optBoolean("seq", false); it.firstLast = o.optBoolean("fl", false)
        o.optJSONArray("prio")?.let { a -> it.filePriorities = IntArray(a.length()) { i -> a.getInt(i) } }
        o.optJSONArray("trk")?.let { a -> for (i in 0 until a.length()) it.extraTrackers.add(a.getString(i)) }
        return it
    }

    private fun queueToJson(q: QueueInfo) = JSONObject().apply {
        put("id", q.id); put("name", q.name); put("par", q.maxParallel); put("retries", q.retries); put("dir", q.saveDir)
        put("open", q.openWhenDone); put("sOn", q.startEnabled); put("sT", q.startTime); put("daily", q.daily); put("days", q.days)
        put("once", q.onceDate); put("appStart", q.startOnAppStart); put("eOn", q.stopEnabled); put("eT", q.stopTime)
        put("done", q.whenDone); put("running", q.running)
    }

    private fun queueFromJson(o: JSONObject) = QueueInfo(o.getString("id"), o.optString("name")).apply {
        maxParallel = o.optInt("par", 1); retries = o.optInt("retries", 3); saveDir = o.optString("dir")
        openWhenDone = o.optBoolean("open"); startEnabled = o.optBoolean("sOn"); startTime = o.optString("sT", "02:00")
        daily = o.optBoolean("daily", true); days = o.optInt("days", 0x7F); onceDate = o.optString("once")
        startOnAppStart = o.optBoolean("appStart"); stopEnabled = o.optBoolean("eOn"); stopTime = o.optString("eT", "07:00")
        whenDone = o.optInt("done", 0); running = o.optBoolean("running", false)
    }

    private fun catToJson(c: Category) = JSONObject().apply {
        put("id", c.id); put("name", c.name); put("exts", c.exts); put("sites", c.sites); put("dir", c.dir); put("builtIn", c.builtIn)
    }

    private fun catFromJson(o: JSONObject) = Category(o.getString("id"), o.optString("name")).apply {
        exts = o.optString("exts"); sites = o.optString("sites"); dir = o.optString("dir"); builtIn = o.optBoolean("builtIn")
    }
}
