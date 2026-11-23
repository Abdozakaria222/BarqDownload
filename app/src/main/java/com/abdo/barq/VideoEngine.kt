package com.abdo.barq

import android.content.Context
import android.webkit.CookieManager
import com.abdo.barq.core.DownloadItem
import com.abdo.barq.core.Fmt
import com.abdo.barq.core.Names
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import org.json.JSONObject
import java.io.File
import java.util.Locale

class VideoFormat(val id: String, val ext: String, val height: Int, val fps: Int, val vcodec: String, val acodec: String,
                  val size: Long, val note: String, val url: String?)

class VideoEntry(val url: String, val title: String, val duration: Long, val index: Int) {
    @Volatile var checked: Boolean = true
}

class VideoMeta(
    val url: String,
    val title: String,
    val duration: Double,
    val thumbnail: String?,
    val formats: List<VideoFormat>,
    val entries: List<VideoEntry>,
    val extractor: String,
) {
    val heights: List<Int> get() = formats.filter { it.height > 0 && it.vcodec != "none" }.map { it.height }.distinct().sortedDescending()

    /** Size of the best video ≤ height plus the best audio, if the site told it. */
    fun estimate(quality: String): Long {
        val audio = formats.filter { it.vcodec == "none" && it.acodec != "none" }.maxByOrNull { it.size }?.size ?: 0
        return when (quality) {
            "m4a", "mp3" -> audio
            "best" -> (formats.filter { it.vcodec != "none" }.maxByOrNull { it.height * 10_000L + it.size }?.size ?: 0) + audio
            else -> {
                val h = quality.toIntOrNull() ?: return 0
                (formats.filter { it.vcodec != "none" && it.height in 1..h }.maxByOrNull { it.height * 10_000L + it.size }?.size ?: 0) + audio
            }
        }
    }

    /** A link a video player can open for the "preview". */
    fun previewUrl(): String? = formats.filter { it.vcodec != "none" && it.acodec != "none" && it.url != null }
        .maxByOrNull { if (it.height <= 480) it.height else 0 }?.url
}

/** yt-dlp (+ FFmpeg + QuickJS) through youtubedl-android: videos, playlists and parts of videos. */
object VideoEngine {
    @Volatile var ready = false
    @Volatile var preparing = false
    @Volatile var error: String? = null
    private val initLock = Any()

    val qualities = listOf("best", "2160", "1440", "1080", "720", "480", "360", "m4a", "mp3")

    fun qualityLabel(q: String): String = when (q) {
        "best" -> tr("Best quality (MP4)", "أفضل جودة (MP4)")
        "2160" -> "2160p 4K"
        "1440" -> "1440p 2K"
        "1080" -> "1080p Full HD"
        "720" -> "720p HD"
        "480" -> "480p SD"
        "360" -> "360p SD"
        "m4a" -> tr("Audio only (M4A)", "صوت فقط (M4A)")
        "mp3" -> tr("Audio only (MP3)", "صوت فقط (MP3)")
        else -> q
    }

    fun initAsync(ctx: Context) {
        Manager.bg.execute { runCatching { ensureReady(ctx) } }
    }

    fun ensureReady(ctx: Context) {
        if (ready) return
        synchronized(initLock) {
            if (ready) return
            preparing = true
            try {
                YoutubeDL.getInstance().init(ctx)
                FFmpeg.getInstance().init(ctx)
                ready = true
                error = null
            } catch (e: Throwable) {
                error = e.message ?: e.javaClass.simpleName
                throw e
            } finally {
                preparing = false
                Manager.changed(false)
            }
        }
        if (Prefs.ytdlpAutoUpdate && System.currentTimeMillis() - Prefs.ytdlpLastUpdate > 3L * 24 * 3600_000) {
            Manager.bg.execute { runCatching { update(ctx) } }
        }
    }

    fun version(ctx: Context): String = runCatching { YoutubeDL.getInstance().versionName(ctx) ?: YoutubeDL.getInstance().version(ctx) }.getOrNull() ?: "?"

    /** Returns a message for the user. */
    fun update(ctx: Context): String {
        ensureReady(ctx)
        val st = YoutubeDL.getInstance().updateYoutubeDL(ctx, YoutubeDL.UpdateChannel.STABLE)
        Prefs.ytdlpLastUpdate = System.currentTimeMillis()
        return if (st == YoutubeDL.UpdateStatus.DONE) tr("The video downloader was updated to ", "تم تحديث محمّل الفيديو إلى ") + version(ctx)
        else tr("The video downloader is up to date: ", "محمّل الفيديو محدَّث: ") + version(ctx)
    }

    private fun baseRequest(url: String, referer: String? = null, cookieFile: String? = null): YoutubeDLRequest {
        val r = YoutubeDLRequest(url)
        when (Prefs.proxyMode) {
            0 -> r.addOption("--proxy", "")
            2 -> if (Prefs.proxyHost.isNotBlank()) r.addOption("--proxy", "http://${Prefs.proxyHost}:${Prefs.proxyPort}")
            3 -> if (Prefs.proxyHost.isNotBlank()) r.addOption("--proxy", "socks5://${Prefs.proxyHost}:${Prefs.proxyPort}")
        }
        if (!referer.isNullOrBlank()) r.addOption("--referer", referer)
        if (!cookieFile.isNullOrBlank() && File(cookieFile).exists()) r.addOption("--cookies", cookieFile)
        r.addOption("--socket-timeout", "30")
        return r
    }

    /** Title, length and qualities of a video; [playlist] lists the videos of a playlist instead. */
    fun info(ctx: Context, url: String, playlist: Boolean = false, referer: String? = null, cookieFile: String? = null): VideoMeta {
        ensureReady(ctx)
        val r = baseRequest(url, referer, cookieFile)
        r.addOption("-J")
        if (playlist) r.addOption("--flat-playlist") else r.addOption("--no-playlist")
        val resp = YoutubeDL.getInstance().execute(r, null, null)
        val start = resp.out.indexOf('{')
        if (start < 0) throw IllegalStateException(resp.err.lines().lastOrNull { it.contains("ERROR") } ?: tr("No video found", "لم يتم العثور على فيديو"))
        return parse(JSONObject(resp.out.substring(start)), url)
    }

    private fun parse(o: JSONObject, url: String): VideoMeta {
        val formats = ArrayList<VideoFormat>()
        o.optJSONArray("formats")?.let { a ->
            for (i in 0 until a.length()) {
                val f = a.optJSONObject(i) ?: continue
                formats.add(VideoFormat(
                    f.optString("format_id"), f.optString("ext"), f.optInt("height", 0), f.optInt("fps", 0),
                    f.optString("vcodec", "none"), f.optString("acodec", "none"),
                    if (f.has("filesize") && !f.isNull("filesize")) f.optLong("filesize") else f.optLong("filesize_approx", 0),
                    f.optString("format_note"), f.optString("url").takeIf { u -> u.startsWith("http") },
                ))
            }
        }
        val entries = ArrayList<VideoEntry>()
        o.optJSONArray("entries")?.let { a ->
            for (i in 0 until a.length()) {
                val e = a.optJSONObject(i) ?: continue
                var u = e.optString("url")
                if (!u.startsWith("http")) u = e.optString("webpage_url")
                if (!u.startsWith("http")) {
                    val id = e.optString("id")
                    if (id.isNotBlank() && (o.optString("extractor_key").contains("Youtube", true) || e.optString("ie_key").contains("Youtube", true)))
                        u = "https://www.youtube.com/watch?v=$id"
                }
                if (!u.startsWith("http")) continue
                entries.add(VideoEntry(u, e.optString("title", u), e.optDouble("duration", 0.0).toLong(), i + 1))
            }
        }
        return VideoMeta(
            o.optString("webpage_url", url).ifBlank { url },
            o.optString("title", "video"),
            o.optDouble("duration", 0.0),
            o.optString("thumbnail").takeIf { it.isNotBlank() },
            formats, entries, o.optString("extractor_key"),
        )
    }

    fun outputBase(it: DownloadItem): String = Names.sanitize(Names.base(it.fileName).ifBlank { it.vTitle ?: "video" })

    fun buildRequest(it: DownloadItem): YoutubeDLRequest {
        val dir = File(it.dir).apply { mkdirs() }
        val r = baseRequest(it.url, it.referer, it.vCookieFile)
        // a literal % in a title would be read as a yt-dlp template field
        r.addOption("-o", File(dir, outputBase(it).replace("%", "%%") + ".%(ext)s").absolutePath)
        r.addOption("--no-playlist")
        r.addOption("--newline")
        r.addOption("--no-mtime")
        r.addOption("--retries", Prefs.retries.coerceAtLeast(1))
        r.addOption("--fragment-retries", Prefs.retries.coerceAtLeast(1))
        r.addOption("--concurrent-fragments", it.connections.coerceIn(1, 16))
        when (val q = it.vQuality ?: "best") {
            "mp3" -> {
                r.addOption("-f", "ba/b")
                r.addOption("-x"); r.addOption("--audio-format", "mp3"); r.addOption("--audio-quality", "${Prefs.mp3Quality}K")
            }
            "m4a" -> {
                r.addOption("-f", "ba[ext=m4a]/ba/b")
                r.addOption("-x"); r.addOption("--audio-format", "m4a")
            }
            "best" -> {
                r.addOption("-f", "bv*[ext=mp4]+ba[ext=m4a]/bv*+ba/b")
                r.addOption("--merge-output-format", "mp4")
            }
            else -> {
                val h = q.toIntOrNull()
                if (h != null) {
                    r.addOption("-f", "bv*[height<=$h][ext=mp4]+ba[ext=m4a]/bv*[height<=$h]+ba/b[height<=$h]/b")
                } else {
                    r.addOption("-f", "$q+ba/$q")   // a format id chosen from the list
                }
                r.addOption("--merge-output-format", "mp4")
            }
        }
        if (it.vSubs) {
            r.addOption("--write-subs")
            r.addOption("--sub-langs", Prefs.subLangs.ifBlank { "ar,en" })
            r.addOption("--convert-subs", "srt")
        }
        if (it.vStart >= 0 && it.vEnd > it.vStart) {
            r.addOption("--download-sections", "*${Fmt.hms(it.vStart)}-${Fmt.hms(it.vEnd)}")
            if (it.vExact) r.addOption("--force-keyframes-at-cuts")
        }
        val perItem = if (it.limiterOn && it.speedLimit > 0) it.speedLimit else 0
        val glob = if (Manager.global.bytesPerSecond > 0) (Manager.global.bytesPerSecond / 1024).toInt() else 0
        val lim = listOf(perItem, glob).filter { x -> x > 0 }.minOrNull()
        if (lim != null) r.addOption("--limit-rate", "${lim}K")
        it.userAgent?.takeIf { u -> u.isNotBlank() }?.let { u -> r.addOption("--user-agent", u) }
        return r
    }

    private val ofRx = Regex("of\\s+~?\\s*([\\d.]+)\\s*([KMGT]?i?B)")
    private val atRx = Regex("at\\s+([\\d.]+)\\s*([KMGT]?i?B)/s")

    private fun bytes(v: String, unit: String): Long {
        val d = v.toDoubleOrNull() ?: return 0
        val m = when (unit.uppercase(Locale.US).replace("I", "")) {
            "KB" -> 1024.0; "MB" -> 1024.0 * 1024; "GB" -> 1024.0 * 1024 * 1024; "TB" -> 1024.0 * 1024 * 1024 * 1024
            else -> 1.0
        }
        return (d * m).toLong()
    }

    fun onProgress(it: DownloadItem, progress: Float, eta: Long, line: String) {
        when {
            line.startsWith("[download]") -> {
                ofRx.find(line)?.let { m -> it.size = bytes(m.groupValues[1], m.groupValues[2]) }
                atRx.find(line)?.let { m -> it.speed = bytes(m.groupValues[1], m.groupValues[2]) }
                if (progress >= 0 && it.size > 0) it.downloaded = (it.size * (progress / 100.0)).toLong()
                it.eta = eta
                if (line.contains("Destination:")) {
                    val f = line.substringAfter("Destination:").trim()
                    val audioOnly = it.vQuality == "m4a" || it.vQuality == "mp3"
                    it.phase = if (!audioOnly && Regex("\\.f\\d+\\.").containsMatchIn(f)) {
                        if (it.phase == tr("Video", "الفيديو")) tr("Sound", "الصوت") else tr("Video", "الفيديو")
                    } else null
                } else if (it.phase == tr("Getting the video…", "جارٍ جلب الفيديو…") || it.phase == tr("Connecting…", "جارٍ الاتصال…")) {
                    it.phase = null
                }
            }
            line.startsWith("[Merger]") || line.startsWith("[VideoConvertor]") -> { it.phase = tr("Joining video and sound…", "جارٍ دمج الصورة والصوت…"); it.speed = 0 }
            line.startsWith("[ExtractAudio]") -> { it.phase = tr("Making the audio file…", "جارٍ تجهيز ملف الصوت…"); it.speed = 0 }
            line.startsWith("[youtube]") || line.startsWith("[info]") || line.startsWith("[generic]") -> it.phase = tr("Getting the video…", "جارٍ جلب الفيديو…")
        }
        Manager.changed(false)
    }

    private val outExts = setOf("mp4", "mkv", "webm", "m4a", "mp3", "opus", "ogg", "flac", "wav", "mov", "avi", "3gp", "aac", "flv", "ts")

    fun findOutput(it: DownloadItem): File? {
        val dir = File(it.dir)
        val base = outputBase(it)
        return dir.listFiles()?.filter { f ->
            f.isFile && f.name.startsWith(base) && Names.ext(f.name) in outExts &&
                !Regex("\\.f\\d+\\.[a-z0-9]+$").containsMatchIn(f.name) && !f.name.endsWith(".part")
        }?.maxByOrNull { f -> f.lastModified() }
    }

    fun deleteTemp(it: DownloadItem) {
        val base = outputBase(it)
        File(it.dir).listFiles()?.filter { f -> f.name.startsWith(base) && (f.name.endsWith(".part") || f.name.endsWith(".ytdl") || f.name.contains(".part-Frag")) }
            ?.forEach { f -> f.delete() }
    }

    /** Cookies of the built-in browser for this site, for sites that need you to be logged in. */
    fun cookieFileFor(ctx: Context, pageUrl: String): String? {
        val cookies = runCatching { CookieManager.getInstance().getCookie(pageUrl) }.getOrNull() ?: return null
        if (cookies.isBlank()) return null
        val host = pageUrl.substringAfter("://").substringBefore('/').substringBefore(':')
        val domain = "." + host.removePrefix("www.").removePrefix("m.")
        val sb = StringBuilder("# Netscape HTTP Cookie File\n")
        val expiry = System.currentTimeMillis() / 1000 + 30L * 24 * 3600
        for (kv in cookies.split(';')) {
            val k = kv.substringBefore('=').trim()
            val v = kv.substringAfter('=', "").trim()
            if (k.isEmpty()) continue
            sb.append(domain).append("\tTRUE\t/\tTRUE\t").append(expiry).append('\t').append(k).append('\t').append(v).append('\n')
        }
        val f = File(ctx.cacheDir, "cookies-" + host.replace(Regex("[^a-zA-Z0-9.]"), "_") + ".txt")
        f.writeText(sb.toString())
        return f.absolutePath
    }

    /** Sites the video downloader knows well: links from them open the video window. */
    fun looksLikeVideoSite(url: String): Boolean {
        val h = url.substringAfter("://").substringBefore('/').lowercase()
        return listOf("youtube.com", "youtu.be", "facebook.com", "fb.watch", "instagram.com", "tiktok.com", "twitter.com", "x.com",
            "vimeo.com", "dailymotion.com", "twitch.tv", "soundcloud.com", "reddit.com", "bilibili.com", "streamable.com", "ok.ru",
            "vk.com", "rumble.com", "pinterest.com", "threads.net", "snapchat.com", "linkedin.com", "telegram.org", "t.me")
            .any { h == it || h.endsWith(".$it") } || url.contains(".m3u8") || url.contains(".mpd")
    }
}

class VideoRunner(override val item: DownloadItem) : Runner {
    @Volatile private var stopped = false
    private val pid = "v${item.id}"

    override fun start() {
        Thread({ run() }, "video-${item.id}").start()
    }

    override fun stop() {
        stopped = true
        runCatching { YoutubeDL.getInstance().destroyProcessById(pid) }
    }

    private fun run() {
        try {
            VideoEngine.ensureReady(Manager.ctx)
            if (stopped) { Manager.main.post { Manager.onStopped(item) }; return }
            item.phase = tr("Getting the video…", "جارٍ جلب الفيديو…")
            Manager.changed(false)
            val req = VideoEngine.buildRequest(item)
            YoutubeDL.getInstance().execute(req, pid) { progress, eta, line ->
                VideoEngine.onProgress(item, progress, eta, line)
            }
            if (stopped) { Manager.main.post { Manager.onStopped(item) }; return }
            val f = VideoEngine.findOutput(item)
            if (f != null) {
                item.fileName = f.name
                item.size = f.length()
                item.downloaded = item.size
            }
            Manager.main.post { Manager.completed(item, f) }
        } catch (e: YoutubeDL.CanceledException) {
            Manager.main.post { Manager.onStopped(item) }
        } catch (e: InterruptedException) {
            Manager.main.post { Manager.onStopped(item) }
        } catch (e: Throwable) {
            val msg = (e.message ?: e.javaClass.simpleName).lines().lastOrNull { it.contains("ERROR") } ?: (e.message ?: e.javaClass.simpleName)
            if (stopped) Manager.main.post { Manager.onStopped(item) }
            else Manager.main.post { Manager.onFailed(item, Exception(msg.take(400))) }
        }
    }
}
