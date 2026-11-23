package com.abdo.barq.core

import java.io.File
import java.io.FileInputStream
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.Locale

object Fmt {
    fun size(b: Long): String {
        if (b < 0) return "?"
        if (b < 1024) return "$b B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var v = b / 1024.0
        var i = 0
        while (v >= 1024 && i < units.size - 1) { v /= 1024; i++ }
        return String.format(Locale.US, if (v >= 100) "%.0f %s" else if (v >= 10) "%.1f %s" else "%.2f %s", v, units[i])
    }

    fun speed(bps: Long): String = if (bps <= 0) "" else size(bps) + "/s"

    fun duration(sec: Long): String {
        if (sec < 0) return "—"
        val h = sec / 3600
        val m = (sec % 3600) / 60
        val s = sec % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s) else String.format(Locale.US, "%d:%02d", m, s)
    }

    fun hms(sec: Double): String {
        val t = sec.toLong().coerceAtLeast(0)
        return String.format(Locale.US, "%02d:%02d:%02d", t / 3600, (t % 3600) / 60, t % 60)
    }

    fun parseHms(s: String): Double? {
        val p = s.trim().split(':').map { it.trim() }
        if (p.isEmpty() || p.size > 3 || p.any { it.isEmpty() }) return null
        return try {
            var total = 0.0
            for (x in p) total = total * 60 + x.toDouble()
            total
        } catch (e: NumberFormatException) { null }
    }
}

object Names {
    private val bad = Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]")

    fun sanitize(name: String, fallback: String = "download"): String {
        var n = bad.replace(name, "_").trim().trim('.')
        if (n.isEmpty()) n = fallback
        // keep the name under ~200 bytes (FAT/ext4 limit is 255)
        while (n.toByteArray(Charsets.UTF_8).size > 200) {
            val ext = ext(n)
            val base = n.removeSuffix(if (ext.isEmpty()) "" else ".$ext")
            n = base.dropLast(maxOf(1, base.length / 10)) + if (ext.isEmpty()) "" else ".$ext"
        }
        return n
    }

    fun ext(name: String): String {
        val i = name.lastIndexOf('.')
        return if (i <= 0 || i == name.length - 1) "" else name.substring(i + 1).lowercase(Locale.ROOT).take(10)
    }

    fun base(name: String): String {
        val e = ext(name)
        return if (e.isEmpty()) name else name.dropLast(e.length + 1)
    }

    fun fromUrl(url: String): String {
        val path = url.substringBefore('#').substringBefore('?').substringAfter("://").substringAfter('/', "")
        val last = path.trimEnd('/').substringAfterLast('/')
        val dec = try { URLDecoder.decode(last.replace("+", "%2B"), "UTF-8") } catch (e: Exception) { last }
        if (dec.isNotBlank()) return sanitize(dec)
        val host = url.substringAfter("://").substringBefore('/').substringBefore(':')
        return sanitize(if (host.isBlank()) "index.html" else "$host.html")
    }

    /** "name.ext" → "name (2).ext" until free. [taken] also counts names of other downloads. */
    fun unique(dir: File, name: String, taken: (String) -> Boolean = { false }): String {
        if (!File(dir, name).exists() && !taken(name)) return name
        val e = ext(name)
        val b = base(name)
        var i = 2
        while (true) {
            val n = if (e.isEmpty()) "$b ($i)" else "$b ($i).$e"
            if (!File(dir, n).exists() && !taken(n)) return n
            i++
        }
    }

    private val mimeExt = mapOf(
        "application/pdf" to "pdf", "application/zip" to "zip", "application/x-rar-compressed" to "rar",
        "application/vnd.rar" to "rar", "application/x-7z-compressed" to "7z", "application/vnd.android.package-archive" to "apk",
        "video/mp4" to "mp4", "video/webm" to "webm", "video/x-matroska" to "mkv", "audio/mpeg" to "mp3", "audio/mp4" to "m4a",
        "image/jpeg" to "jpg", "image/png" to "png", "image/gif" to "gif", "image/webp" to "webp", "text/html" to "html",
        "text/plain" to "txt", "application/json" to "json", "application/x-bittorrent" to "torrent",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document" to "docx",
        "application/msword" to "doc", "application/vnd.ms-excel" to "xls",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" to "xlsx",
        "application/vnd.openxmlformats-officedocument.presentationml.presentation" to "pptx",
        "application/x-msdownload" to "exe", "application/octet-stream" to "",
    )

    fun withExtFromMime(name: String, mime: String?): String {
        if (ext(name).isNotEmpty() || mime == null) return name
        val e = mimeExt[mime] ?: return name
        return if (e.isEmpty()) name else "$name.$e"
    }
}

/** File groups used by categories, the grabber and the browser panel. */
object FileTypes {
    val VIDEO = setOf("mp4", "mkv", "avi", "mov", "wmv", "flv", "webm", "m4v", "ts", "3gp", "mpg", "mpeg", "ogv", "m2ts", "vob")
    val AUDIO = setOf("mp3", "wav", "flac", "aac", "ogg", "m4a", "wma", "opus", "aif", "aiff", "mid", "amr")
    val IMAGE = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "svg", "tif", "tiff", "ico", "heic", "avif")
    val ARCHIVE = setOf("zip", "rar", "7z", "tar", "gz", "tgz", "bz2", "xz", "zst", "iso", "cab", "arj", "lzh", "lzma", "z")
    val PROGRAM = setOf("apk", "apks", "xapk", "exe", "msi", "dmg", "pkg", "deb", "rpm", "bin", "jar", "bat", "sh", "appimage")
    val DOCUMENT = setOf("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "pps", "odt", "ods", "odp", "txt", "rtf", "epub", "csv", "mobi", "djvu")
    val PAGE = setOf("", "html", "htm", "php", "asp", "aspx", "jsp", "shtml", "xhtml", "cfm", "cgi")
    val STREAM = setOf("m3u8", "mpd", "f4m")

    fun extOfUrl(url: String): String = Names.ext(url.substringBefore('#').substringBefore('?').trimEnd('/').substringAfterLast('/'))

    /** 0 page, 1 image, 2 video, 3 audio, 4 archive/program, 5 document, 6 other file */
    fun group(ext: String): Int = when (ext) {
        in PAGE -> 0
        in IMAGE -> 1
        in VIDEO, in STREAM -> 2
        in AUDIO -> 3
        in ARCHIVE, in PROGRAM -> 4
        in DOCUMENT -> 5
        else -> 6
    }
}

object Categories {
    const val GENERAL = "general"

    fun defaults(): List<Category> = listOf(
        Category("compressed", "Compressed").apply { exts = "zip rar 7z tar gz tgz bz2 xz zst iso cab arj lzh lzma z"; builtIn = true },
        Category("documents", "Documents").apply { exts = "pdf doc docx xls xlsx ppt pptx pps odt ods odp txt rtf epub csv mobi djvu"; builtIn = true },
        Category("music", "Music").apply { exts = "mp3 wav flac aac ogg m4a wma opus aif aiff mid amr"; builtIn = true },
        Category("programs", "Programs").apply { exts = "apk apks xapk exe msi dmg pkg deb rpm bin jar"; builtIn = true },
        Category("video", "Video").apply { exts = "mp4 mkv avi mov wmv flv webm m4v ts 3gp mpg mpeg ogv m2ts"; builtIn = true },
        Category("images", "Images").apply { exts = "jpg jpeg png gif webp bmp svg tif tiff ico heic avif"; builtIn = true },
        Category("torrents", "Torrents").apply { exts = ""; builtIn = true },
    )

    private fun siteMatches(host: String, pattern: String): Boolean {
        val h = host.lowercase().removePrefix("www.")
        val p = pattern.lowercase().removePrefix("http://").removePrefix("https://").substringBefore('/').removePrefix("www.")
        return when {
            p.startsWith("*.") -> h.endsWith(p.substring(1)) || h == p.substring(2)
            else -> h == p || h.endsWith(".$p")
        }
    }

    /** Sites of the user's own categories win, then file types. */
    fun detect(cats: List<Category>, fileName: String, url: String, referer: String? = null): String {
        val host = (referer ?: url).substringAfter("://").substringBefore('/').substringBefore(':')
        val host2 = url.substringAfter("://").substringBefore('/').substringBefore(':')
        cats.firstOrNull { c -> c.siteList().any { siteMatches(host, it) || siteMatches(host2, it) } }?.let { return it.id }
        val ext = Names.ext(fileName)
        if (ext.isEmpty()) return GENERAL
        cats.firstOrNull { !it.builtIn && ext in it.extList() }?.let { return it.id }
        cats.firstOrNull { ext in it.extList() }?.let { return it.id }
        return GENERAL
    }
}

object Batch {
    /**
     * "https://site.com/img*.jpg" with from/to → every address. Letters a–z work too.
     * [width] pads numbers with zeros: width 3 → 001, 002 … 010.
     */
    fun expand(pattern: String, from: String, to: String, width: Int, letters: Boolean, limit: Int = 10_000): List<String> {
        if (!pattern.contains('*')) return emptyList()
        val out = ArrayList<String>()
        if (letters) {
            val a = from.trim().ifEmpty { "a" }[0].lowercaseChar()
            val b = to.trim().ifEmpty { "z" }[0].lowercaseChar()
            if (a !in 'a'..'z' || b !in 'a'..'z') return emptyList()
            val range = if (a <= b) (a..b) else (b..a)
            for (ch in range) out.add(pattern.replace("*", ch.toString()))
        } else {
            val a = from.trim().toLongOrNull() ?: return emptyList()
            val b = to.trim().toLongOrNull() ?: return emptyList()
            val range = if (a <= b) (a..b) else (b..a)
            for (i in range) {
                if (out.size >= limit) break
                out.add(pattern.replace("*", i.toString().padStart(width.coerceIn(0, 12), '0')))
            }
        }
        return out
    }

    fun linksFromText(text: String): List<String> {
        val rx = Regex("(?i)\\b(?:https?|ftp|magnet):[^\\s<>\"']+")
        return rx.findAll(text).map { it.value.trimEnd(')', ']', ',', '.', ';') }.distinct().toList()
    }
}

/** The ".ef2" download list format (IDM compatible) and plain text lists. */
object Ef2 {
    fun export(items: List<DownloadItem>): String = buildString {
        for (it in items) {
            append("<\r\n").append(it.url).append("\r\n")
            it.referer?.takeIf { r -> r.isNotBlank() }?.let { r -> append("referer: ").append(r).append("\r\n") }
            it.userAgent?.takeIf { u -> u.isNotBlank() }?.let { u -> append("User-Agent: ").append(u).append("\r\n") }
            if (it.fileName.isNotBlank()) append("filename: ").append(it.fileName).append("\r\n")
            it.description?.takeIf { d -> d.isNotBlank() }?.let { d -> append("description: ").append(d.replace("\n", " ")).append("\r\n") }
            append(">\r\n")
        }
    }

    data class Entry(val url: String, val referer: String?, val userAgent: String?, val fileName: String?, val description: String?)

    fun parse(text: String): List<Entry> {
        val out = ArrayList<Entry>()
        val lines = text.lines().map { it.trim() }
        var i = 0
        while (i < lines.size) {
            if (lines[i] == "<") {
                var url: String? = null
                var ref: String? = null
                var ua: String? = null
                var fn: String? = null
                var desc: String? = null
                i++
                while (i < lines.size && lines[i] != ">") {
                    val l = lines[i]
                    val key = l.substringBefore(':').lowercase()
                    when {
                        url == null && l.contains("://") && (key == "http" || key == "https" || key == "ftp") -> url = l
                        key == "referer" -> ref = l.substringAfter(':').trim()
                        key == "user-agent" -> ua = l.substringAfter(':').trim()
                        key == "filename" -> fn = l.substringAfter(':').trim()
                        key == "description" -> desc = l.substringAfter(':').trim()
                        url == null && l.startsWith("magnet:") -> url = l
                    }
                    i++
                }
                if (url != null) out.add(Entry(url, ref, ua, fn, desc))
            }
            i++
        }
        if (out.isEmpty()) Batch.linksFromText(text).forEach { out.add(Entry(it, null, null, null, null)) }
        return out
    }
}

object Checksum {
    /** Picks the algorithm from the length of the expected value: 32 MD5, 40 SHA-1, 64 SHA-256. */
    fun algoFor(expected: String): String = when (expected.trim().length) {
        32 -> "MD5"
        40 -> "SHA-1"
        else -> "SHA-256"
    }

    fun of(file: File, algo: String = "SHA-256", progress: (Float) -> Unit = {}): String {
        val md = MessageDigest.getInstance(algo)
        val total = file.length().coerceAtLeast(1)
        var done = 0L
        FileInputStream(file).use { input ->
            val buf = ByteArray(256 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
                done += n
                progress(done.toFloat() / total)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}

object Magnet {
    val PUBLIC_TRACKERS = listOf(
        "udp://tracker.opentrackr.org:1337/announce",
        "udp://open.stealth.si:80/announce",
        "udp://tracker.torrent.eu.org:451/announce",
        "udp://exodus.desync.com:6969/announce",
        "udp://tracker.openbittorrent.com:6969/announce",
        "udp://open.demonii.com:1337/announce",
        "http://tracker.opentrackr.org:1337/announce",
    )

    fun infoHash(magnet: String): String? =
        Regex("xt=urn:btih:([0-9a-zA-Z]{32,40})").find(magnet)?.groupValues?.get(1)?.let { h ->
            if (h.length == 40) h.lowercase() else base32ToHex(h)
        }

    fun displayName(magnet: String): String? = Regex("[?&]dn=([^&]+)").find(magnet)?.groupValues?.get(1)?.let {
        try { URLDecoder.decode(it, "UTF-8") } catch (e: Exception) { it }
    }

    fun withTrackers(magnet: String, extra: List<String> = PUBLIC_TRACKERS): String {
        val sb = StringBuilder(magnet)
        for (t in extra) if (!magnet.contains(java.net.URLEncoder.encode(t, "UTF-8")) && !magnet.contains(t)) {
            sb.append("&tr=").append(java.net.URLEncoder.encode(t, "UTF-8"))
        }
        return sb.toString()
    }

    private fun base32ToHex(s: String): String? {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        var bits = 0
        var value = 0
        val out = StringBuilder()
        for (c in s.uppercase()) {
            val v = alphabet.indexOf(c)
            if (v < 0) return null
            value = (value shl 5) or v
            bits += 5
            if (bits >= 8) {
                out.append("%02x".format((value shr (bits - 8)) and 0xFF))
                bits -= 8
            }
        }
        return out.toString()
    }
}
