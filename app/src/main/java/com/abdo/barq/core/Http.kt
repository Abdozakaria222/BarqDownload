package com.abdo.barq.core

import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
import java.net.URL
import java.net.URLDecoder
import java.nio.charset.Charset
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** Network options shared by every request (from Settings > Proxy / Connection). */
class NetConfig(
    var proxyMode: Int = 1,           // 0 none, 1 system, 2 manual HTTP, 3 manual SOCKS
    var proxyHost: String = "",
    var proxyPort: Int = 8080,
    var proxyBypass: String = "localhost 127.0.0.1 *.local",
    var userAgent: String = DEFAULT_UA,
    var connectTimeoutMs: Int = 20_000,
    var readTimeoutMs: Int = 30_000,
    /** "site/path" -> (user, password) */
    var siteLogins: List<Triple<String, String, String>> = emptyList(),
) {
    companion object {
        const val DEFAULT_UA =
            "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
    }

    fun proxyFor(host: String): Proxy? {
        if (proxyMode == 1) return null
        if (proxyMode == 0 || proxyHost.isBlank()) return Proxy.NO_PROXY
        if (bypassed(host)) return Proxy.NO_PROXY
        val type = if (proxyMode == 3) Proxy.Type.SOCKS else Proxy.Type.HTTP
        return Proxy(type, InetSocketAddress.createUnresolved(proxyHost.trim(), proxyPort))
    }

    private fun bypassed(host: String): Boolean {
        val h = host.lowercase()
        return proxyBypass.lowercase().split(' ', ',', ';').map { it.trim() }.filter { it.isNotEmpty() }.any { p ->
            if (p.startsWith("*.")) h.endsWith(p.substring(1)) || h == p.substring(2) else h == p
        }
    }

    fun loginFor(url: String): Pair<String, String>? {
        val u = url.lowercase().substringAfter("://")
        return siteLogins.firstOrNull { (site, _, _) ->
            val s = site.lowercase().removePrefix("http://").removePrefix("https://").trim().trimEnd('/')
            s.isNotEmpty() && (u.startsWith(s) || u.startsWith("www.$s"))
        }?.let { it.second to it.third }
    }
}

/** Per-download request data (headers that travel with a link). */
class Req(
    val url: String,
    val referer: String? = null,
    val cookies: String? = null,
    val userAgent: String? = null,
    val user: String? = null,
    val pass: String? = null,
)

class HttpStatusException(val code: Int, message: String) : IOException(message)
class NotResumableException : IOException("The server does not support resuming")

data class ProbeInfo(
    val finalUrl: String,
    val code: Int,
    val size: Long,
    val resumable: Boolean,
    val fileName: String?,
    val contentType: String?,
    val lastModified: Long,
)

/** java.util.Base64 needs Android 8; this works everywhere. */
fun base64(data: ByteArray): String {
    val t = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    val sb = StringBuilder((data.size + 2) / 3 * 4)
    var i = 0
    while (i < data.size) {
        val b0 = data[i].toInt() and 0xFF
        val b1 = if (i + 1 < data.size) data[i + 1].toInt() and 0xFF else -1
        val b2 = if (i + 2 < data.size) data[i + 2].toInt() and 0xFF else -1
        sb.append(t[b0 shr 2])
        sb.append(t[((b0 and 3) shl 4) or (if (b1 < 0) 0 else b1 shr 4)])
        sb.append(if (b1 < 0) '=' else t[((b1 and 15) shl 2) or (if (b2 < 0) 0 else b2 shr 6)])
        sb.append(if (b2 < 0) '=' else t[b2 and 63])
        i += 3
    }
    return sb.toString()
}

object HttpUtil {

    /**
     * Opens a GET connection, following redirects by hand (HttpURLConnection does not follow
     * http <-> https redirects) and carrying cookies set on the way.
     */
    @Throws(IOException::class)
    fun open(req: Req, cfg: NetConfig, range: String? = null, method: String = "GET", url: String = req.url): HttpURLConnection {
        var current = toUrl(url)
        val jar = LinkedHashMap<String, String>()
        req.cookies?.split(';')?.forEach { kv ->
            val k = kv.substringBefore('=').trim()
            if (k.isNotEmpty()) jar[k] = kv.substringAfter('=', "").trim()
        }
        var referer = req.referer
        for (hop in 0 until 12) {
            val proxy = cfg.proxyFor(current.host ?: "")
            val c = (if (proxy == null) current.openConnection() else current.openConnection(proxy)) as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = cfg.connectTimeoutMs
            c.readTimeout = cfg.readTimeoutMs
            c.requestMethod = method
            c.useCaches = false
            c.setRequestProperty("User-Agent", req.userAgent?.takeIf { it.isNotBlank() } ?: cfg.userAgent)
            c.setRequestProperty("Accept", "*/*")
            // Android adds gzip by itself and then hides the real length; ask for the raw bytes.
            c.setRequestProperty("Accept-Encoding", "identity")
            c.setRequestProperty("Accept-Language", "ar,en;q=0.8")
            if (!referer.isNullOrBlank()) c.setRequestProperty("Referer", referer)
            if (jar.isNotEmpty()) c.setRequestProperty("Cookie", jar.entries.joinToString("; ") { "${it.key}=${it.value}" })
            val login = if (!req.user.isNullOrEmpty()) req.user to (req.pass ?: "") else cfg.loginFor(current.toString())
            if (login != null) {
                val token = base64("${login.first}:${login.second}".toByteArray(Charsets.UTF_8))
                c.setRequestProperty("Authorization", "Basic $token")
            }
            if (range != null) c.setRequestProperty("Range", range)
            val code = try {
                c.responseCode
            } catch (e: IOException) {
                c.disconnect(); throw e
            }
            if (code in 300..399 && code != 304) {
                val loc = c.getHeaderField("Location")
                if (loc.isNullOrBlank()) return c
                c.headerFields["Set-Cookie"]?.forEach { sc ->
                    val kv = sc.substringBefore(';')
                    val k = kv.substringBefore('=').trim()
                    if (k.isNotEmpty()) jar[k] = kv.substringAfter('=', "").trim()
                }
                c.disconnect()
                referer = current.toString()
                current = resolve(current, loc)
                continue
            }
            return c
        }
        throw IOException("Too many redirects")
    }

    fun toUrl(s: String): URL {
        val fixed = s.trim().replace(" ", "%20").replace("|", "%7C").replace("\"", "%22")
        return try {
            URI(fixed).toURL()
        } catch (e: Exception) {
            URL(fixed)
        }
    }

    fun resolve(base: URL, loc: String): URL = try {
        base.toURI().resolve(loc.trim().replace(" ", "%20")).toURL()
    } catch (e: Exception) {
        URL(base, loc.trim())
    }

    /** Asks the server about the file without downloading it. */
    @Throws(IOException::class)
    fun probe(req: Req, cfg: NetConfig, url: String = req.url): ProbeInfo {
        val c = open(req, cfg, range = "bytes=0-", url = url)
        try {
            val code = c.responseCode
            if (code == 416) {
                // Some servers refuse "bytes=0-" for empty files: ask again without a range.
                c.disconnect()
                val c2 = open(req, cfg, null, url = url)
                try { return info(c2, c2.responseCode) } finally { c2.disconnect() }
            }
            if (code >= 400) throw HttpStatusException(code, "HTTP $code ${c.responseMessage ?: ""}".trim())
            return info(c, code)
        } finally {
            c.disconnect()
        }
    }

    private fun info(c: HttpURLConnection, code: Int): ProbeInfo {
        var size = -1L
        val cr = c.getHeaderField("Content-Range")
        if (code == 206 && cr != null) size = cr.substringAfterLast('/').trim().toLongOrNull() ?: -1
        if (size < 0 && code == 200) size = c.getHeaderField("Content-Length")?.trim()?.toLongOrNull() ?: -1
        val resumable = code == 206 && size > 0
        val type = c.getHeaderField("Content-Type")?.substringBefore(';')?.trim()?.lowercase()
        val name = c.getHeaderField("Content-Disposition")?.let { parseDispositionName(it) }
        val lm = c.getHeaderField("Last-Modified")?.let { parseHttpDate(it) } ?: 0L
        return ProbeInfo(c.url.toString(), code, size, resumable, name, type, lm)
    }

    fun parseHttpDate(s: String): Long = try {
        SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).apply { timeZone = TimeZone.getTimeZone("GMT") }.parse(s)?.time ?: 0L
    } catch (e: Exception) { 0L }

    /** filename*=UTF-8''… wins over filename="…". */
    fun parseDispositionName(cd: String): String? {
        Regex("filename\\*\\s*=\\s*([^']*)'[^']*'([^;]+)", RegexOption.IGNORE_CASE).find(cd)?.let { m ->
            val cs = m.groupValues[1].ifBlank { "UTF-8" }
            val raw = m.groupValues[2].trim().trim('"')
            return try { URLDecoder.decode(raw.replace("+", "%2B"), cs) } catch (e: Exception) { raw }
        }
        Regex("filename\\s*=\\s*(\"([^\"]*)\"|([^;]+))", RegexOption.IGNORE_CASE).find(cd)?.let { m ->
            val v = (m.groupValues[2].ifEmpty { m.groupValues[3] }).trim()
            if (v.isEmpty()) return null
            val fixed = fixMojibake(v)
            return if (fixed.contains('%')) (try { URLDecoder.decode(fixed.replace("+", "%2B"), "UTF-8") } catch (e: Exception) { fixed }) else fixed
        }
        return null
    }

    /** Header bytes arrive as ISO-8859-1; many servers really send UTF-8 (Arabic names). */
    fun fixMojibake(s: String): String {
        if (s.all { it.code < 0x80 } || s.any { it.code > 0xFF }) return s
        val bytes = s.toByteArray(Charsets.ISO_8859_1)
        val dec = Charsets.UTF_8.newDecoder()
        return try { dec.decode(java.nio.ByteBuffer.wrap(bytes)).toString() } catch (e: Exception) { s }
    }

    fun readText(c: HttpURLConnection, limit: Int = 4 * 1024 * 1024): String {
        val input = if (c.responseCode >= 400) (c.errorStream ?: return "") else c.inputStream
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(32 * 1024)
        input.use {
            while (out.size() < limit) {
                val n = it.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
            }
        }
        val bytes = out.toByteArray()
        val ct = c.getHeaderField("Content-Type") ?: ""
        val cs = Regex("charset=([\\w-]+)", RegexOption.IGNORE_CASE).find(ct)?.groupValues?.get(1)
            ?: Regex("<meta[^>]+charset=[\"']?([\\w-]+)", RegexOption.IGNORE_CASE).find(String(bytes, 0, minOf(bytes.size, 4096), Charsets.ISO_8859_1))?.groupValues?.get(1)
        return try { String(bytes, Charset.forName(cs ?: "UTF-8")) } catch (e: Exception) { String(bytes, Charsets.UTF_8) }
    }

    @Throws(IOException::class)
    fun getText(url: String, cfg: NetConfig, referer: String? = null): Pair<String, String> {
        val c = open(Req(url, referer = referer), cfg)
        try {
            if (c.responseCode >= 400) throw HttpStatusException(c.responseCode, "HTTP ${c.responseCode}")
            return c.url.toString() to readText(c)
        } finally { c.disconnect() }
    }
}

/** Google Drive share links → direct download links. */
object GDrive {
    private val idRx = listOf(
        Regex("drive\\.google\\.com/file/d/([\\w-]{10,})"),
        Regex("drive\\.google\\.com/(?:open|uc)\\?(?:.*&)?id=([\\w-]{10,})"),
        Regex("docs\\.google\\.com/uc\\?(?:.*&)?id=([\\w-]{10,})"),
        Regex("drive\\.usercontent\\.google\\.com/download\\?(?:.*&)?id=([\\w-]{10,})"),
    )

    fun fileId(url: String): String? = idRx.firstNotNullOfOrNull { it.find(url)?.groupValues?.get(1) }

    fun direct(url: String): String {
        val id = fileId(url) ?: return url
        if (url.contains("drive.usercontent.google.com") && url.contains("uuid=")) return url
        return "https://drive.usercontent.google.com/download?id=$id&export=download&confirm=t"
    }

    /** When Drive answers with its "can't scan for viruses" page, build the link from its form. */
    fun fromWarningPage(html: String): String? {
        val form = Regex("<form[^>]+id=\"download-form\"[^>]*action=\"([^\"]+)\"(.*?)</form>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)).find(html)
            ?: return null
        val action = form.groupValues[1].replace("&amp;", "&")
        val params = Regex("<input[^>]+type=\"hidden\"[^>]+name=\"([^\"]+)\"[^>]+value=\"([^\"]*)\"", RegexOption.IGNORE_CASE)
            .findAll(form.groupValues[2]).joinToString("&") { "${it.groupValues[1]}=${java.net.URLEncoder.encode(it.groupValues[2].replace("&amp;", "&"), "UTF-8")}" }
        return if (params.isEmpty()) action else "$action?$params"
    }
}
