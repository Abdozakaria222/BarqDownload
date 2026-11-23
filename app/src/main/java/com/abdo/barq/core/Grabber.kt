package com.abdo.barq.core

import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class GrabbedLink(val url: String, val group: Int, val text: String, val page: String) {
    @Volatile var checked: Boolean = true
}

class GrabOptions(
    /** groups to keep (see [FileTypes.group]); empty = every file that is not a web page */
    val groups: Set<Int> = emptySet(),
    val customExts: Set<String> = emptySet(),
    val levels: Int = 1,
    val pagesAtOnce: Int = 4,
    val otherSites: Boolean = false,
    val noParent: Boolean = true,
    val includePages: Boolean = false,
    val maxPages: Int = 400,
)

/**
 * "Site Grabber": walks the pages of a site and collects the files linked from them
 * (pictures, videos, archives, documents…).
 */
class Grabber(
    private val cfg: NetConfig,
    private val opts: GrabOptions,
    private val onLink: (GrabbedLink) -> Unit,
    private val onPage: (url: String, pagesDone: Int) -> Unit,
) {
    @Volatile var cancelled = false
    private val seenPages = ConcurrentHashMap.newKeySet<String>()
    private val seenLinks = ConcurrentHashMap.newKeySet<String>()
    private val pagesDone = AtomicInteger(0)

    companion object {
        private val aRx = Regex("<a\\b([^>]*)>(.*?)</a\\s*>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        private val hrefRx = Regex("\\bhref\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s>]+))", RegexOption.IGNORE_CASE)
        private val srcRx = Regex("\\b(?:src|data-src|data-original|data-url|poster|data-href)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s>]+))", RegexOption.IGNORE_CASE)
        private val srcsetRx = Regex("\\bsrcset\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)')", RegexOption.IGNORE_CASE)
        private val plainRx = Regex("https?://[^\\s\"'<>()\\\\]+", RegexOption.IGNORE_CASE)
        private val tagRx = Regex("<[^>]+>")
        private val wsRx = Regex("\\s+")

        fun decodeEntities(s: String): String = s.replace("&amp;", "&").replace("&#38;", "&").replace("&quot;", "\"")
            .replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ")

        /** Links of one HTML text: (url, link text). Used by the grabber and by the browser page grab. */
        fun extract(baseUrl: String, html: String): List<Pair<String, String>> {
            val base = try { URI(baseUrl.replace(" ", "%20")) } catch (e: Exception) { return emptyList() }
            val out = LinkedHashMap<String, String>()
            fun add(raw: String?, text: String) {
                if (raw.isNullOrBlank()) return
                val v = decodeEntities(raw.trim())
                if (v.startsWith("#") || v.startsWith("javascript:", true) || v.startsWith("mailto:", true) ||
                    v.startsWith("data:", true) || v.startsWith("tel:", true) || v.startsWith("blob:", true)) return
                val abs = try { base.resolve(v.replace(" ", "%20")).toString() } catch (e: Exception) { return }
                if (!abs.startsWith("http://") && !abs.startsWith("https://") && !abs.startsWith("ftp://")) return
                val clean = abs.substringBefore('#')
                if (!out.containsKey(clean) || (out[clean].isNullOrBlank() && text.isNotBlank())) out[clean] = text
            }
            for (m in aRx.findAll(html)) {
                val h = hrefRx.find(m.groupValues[1]) ?: continue
                val text = wsRx.replace(decodeEntities(tagRx.replace(m.groupValues[2], " ")), " ").trim().take(200)
                add(h.groupValues[1].ifEmpty { h.groupValues[2] }.ifEmpty { h.groupValues[3] }, text)
            }
            for (m in hrefRx.findAll(html)) add(m.groupValues[1].ifEmpty { m.groupValues[2] }.ifEmpty { m.groupValues[3] }, "")
            for (m in srcRx.findAll(html)) add(m.groupValues[1].ifEmpty { m.groupValues[2] }.ifEmpty { m.groupValues[3] }, "")
            for (m in srcsetRx.findAll(html)) {
                m.groupValues[1].ifEmpty { m.groupValues[2] }.split(',').forEach { add(it.trim().substringBefore(' '), "") }
            }
            for (m in plainRx.findAll(html)) add(m.value.trimEnd('.', ',', ';'), "")
            return out.map { it.key to it.value }
        }
    }

    private var host = ""
    private var dirPath = "/"

    private fun allowedPage(u: String): Boolean {
        val uri = try { URI(u) } catch (e: Exception) { return false }
        val h = uri.host?.lowercase()?.removePrefix("www.") ?: return false
        if (!opts.otherSites && h != host) return false
        if (opts.noParent && h == host && !(uri.path ?: "/").startsWith(dirPath)) return false
        return true
    }

    fun run(startUrl: String) {
        val start = startUrl.trim()
        val startUri = try { URI(start.replace(" ", "%20")) } catch (e: Exception) { return }
        host = startUri.host?.lowercase()?.removePrefix("www.") ?: return
        dirPath = (startUri.path ?: "/").let { if (it.endsWith("/")) it else it.substringBeforeLast('/') + "/" }
        val pool = Executors.newFixedThreadPool(opts.pagesAtOnce.coerceIn(1, 8))
        val pending = AtomicInteger(0)
        val lock = Object()

        fun submit(url: String, depth: Int) {
            if (cancelled || seenPages.size >= opts.maxPages || !seenPages.add(url)) return
            pending.incrementAndGet()
            pool.execute {
                try {
                    if (!cancelled) visit(url, depth) { u, d -> submit(u, d) }
                } catch (e: Exception) {
                    // a page that fails is skipped
                } finally {
                    onPage(url, pagesDone.incrementAndGet())
                    if (pending.decrementAndGet() == 0) synchronized(lock) { lock.notifyAll() }
                }
            }
        }

        submit(start, 0)
        synchronized(lock) {
            while (pending.get() > 0 && !cancelled) lock.wait(500)
        }
        pool.shutdownNow()
        pool.awaitTermination(2, TimeUnit.SECONDS)
    }

    private fun visit(url: String, depth: Int, enqueue: (String, Int) -> Unit) {
        val c = HttpUtil.open(Req(url), cfg)
        val type: String
        val finalUrl: String
        val html: String
        try {
            if (c.responseCode >= 400) return
            type = c.getHeaderField("Content-Type")?.lowercase() ?: ""
            finalUrl = c.url.toString()
            if (!type.contains("html") && !type.contains("xml") && !type.startsWith("text/")) {
                // the address is a file, not a page
                addLink(url, "", url)
                return
            }
            html = HttpUtil.readText(c, 3 * 1024 * 1024)
        } finally {
            c.disconnect()
        }
        for ((link, text) in extract(finalUrl, html)) {
            if (cancelled) return
            val ext = FileTypes.extOfUrl(link)
            val group = FileTypes.group(ext)
            if (group == 0) {
                if (opts.includePages) addLink(link, text, finalUrl)
                if (depth < opts.levels && allowedPage(link)) enqueue(link, depth + 1)
            } else {
                addLink(link, text, finalUrl)
            }
        }
    }

    private fun addLink(url: String, text: String, page: String) {
        val ext = FileTypes.extOfUrl(url)
        val group = FileTypes.group(ext)
        val wanted = when {
            group == 0 -> opts.includePages
            opts.customExts.isNotEmpty() -> ext in opts.customExts
            opts.groups.isEmpty() -> true
            else -> group in opts.groups
        }
        if (!wanted || !seenLinks.add(url)) return
        onLink(GrabbedLink(url, group, text, page))
    }
}
