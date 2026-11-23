@file:OptIn(ExperimentalMaterial3Api::class)

package com.abdo.barq.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.abdo.barq.Manager
import com.abdo.barq.Prefs
import com.abdo.barq.R
import com.abdo.barq.Sys
import com.abdo.barq.VideoEngine
import com.abdo.barq.core.FileTypes
import com.abdo.barq.core.Fmt
import com.abdo.barq.core.GrabbedLink
import com.abdo.barq.core.HttpUtil
import com.abdo.barq.core.Names
import com.abdo.barq.core.Req
import com.abdo.barq.tr
import org.json.JSONArray
import java.util.concurrent.ConcurrentHashMap

class Media(val url: String, val kind: String, @Volatile var size: Long = -1)

/** Built-in browser: catches downloads, finds the videos a page plays, grabs the links of a page. */
class BrowserActivity : AppCompatActivity() {
    companion object {
        fun open(ctx: Context, url: String?) {
            ctx.startActivity(Intent(ctx, BrowserActivity::class.java).putExtra("url", url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        /** links grabbed from a page, picked up by the main window */
        @Volatile var pendingGrab: List<GrabbedLink>? = null
    }

    private var web: WebView? = null
    private val media = mutableStateListOf<Media>()
    private val seen = ConcurrentHashMap.newKeySet<String>()
    private var pageUrl by mutableStateOf("")
    private var loadProgress by mutableIntStateOf(0)
    private var add by mutableStateOf<AddRequest?>(null)
    private var linkMenu by mutableStateOf<String?>(null)
    private var address by mutableStateOf<String?>(null)

    override fun onStart() { super.onStart(); Manager.activityStarted() }
    override fun onStop() { super.onStop(); Manager.activityStopped() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val start = intent.getStringExtra("url") ?: Prefs.homePage
        setContent { BarqTheme { BrowserUi(start) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra("url")?.let { u -> web?.loadUrl(fix(u)) }
    }

    private fun goMain(s: com.abdo.barq.ui.Screen) {
        Ui.go(s)
        toMain(Intent())
    }

    override fun onDestroy() {
        web?.let { (it.parent as? ViewGroup)?.removeView(it); it.destroy() }
        web = null
        super.onDestroy()
    }

    private fun toMain(i: Intent) {
        startActivity(i.setClass(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
    }

    private fun excluded(url: String, list: String): Boolean {
        val host = url.substringAfter("://").substringBefore('/').lowercase()
        return list.lowercase().split(' ', ',', '\n').map { it.trim() }.filter { it.isNotEmpty() }.any { p ->
            if (p.contains('/')) Regex(p.replace(".", "\\.").replace("*", ".*")).containsMatchIn(url.lowercase())
            else if (p.startsWith("*.")) host.endsWith(p.substring(1)) else host == p || host.endsWith(".$p")
        }
    }

    private fun wantedType(url: String, mime: String?): Boolean {
        val types = Prefs.browserTypes.lowercase().split(' ', ',').map { it.trim() }.toSet()
        val ext = FileTypes.extOfUrl(url)
        return ext.isNotEmpty() && ext in types || (mime != null && !mime.startsWith("text/") && mime != "application/xhtml+xml")
    }

    private fun onDownload(url: String, ua: String?, disposition: String?, mime: String?, length: Long) {
        if (url.startsWith("blob:") || url.startsWith("data:")) {
            Sys.toast(this, tr("This page makes the file itself (blob); it can't be sent to the download manager.", "هذه الصفحة تصنع الملف بنفسها (blob)؛ لا يمكن إرساله لمدير التحميل."))
            return
        }
        if (excluded(url, Prefs.browserExcludedSites) || excluded(url, Prefs.browserExcludedAddrs)) return
        if (url.substringBefore('?').endsWith(".torrent", true) || mime == "application/x-bittorrent") {
            add = AddRequest(url, pageUrl, CookieManager.getInstance().getCookie(url), ua, pageUrl)
            return
        }
        val name = runCatching { URLUtil.guessFileName(url, disposition, mime) }.getOrNull()
        add = AddRequest(url, pageUrl, CookieManager.getInstance().getCookie(url), ua, pageUrl, name?.let { Names.sanitize(it) }, length)
    }

    private fun noteMedia(url: String, accept: String?) {
        val u = url.lowercase()
        val ext = FileTypes.extOfUrl(u)
        val kind = when {
            ext == "m3u8" || u.contains(".m3u8") -> "HLS"
            ext == "mpd" || u.contains(".mpd") -> "DASH"
            ext in FileTypes.VIDEO && ext != "ts" -> ext.uppercase()
            ext in FileTypes.AUDIO -> ext.uppercase()
            else -> return
        }
        val key = url.substringBefore('?')
        if (!seen.add(key)) return
        val m = Media(url, kind)
        Manager.main.post { if (media.size < 40) media.add(m) }
        if (kind != "HLS" && kind != "DASH") Manager.bg.execute {
            val size = runCatching { HttpUtil.probe(Req(url, pageUrl, CookieManager.getInstance().getCookie(url)), Prefs.netConfig()).size }.getOrDefault(-1)
            m.size = size
            val min = if (FileTypes.group(ext) == 3) Prefs.minAudioKB else Prefs.minVideoKB
            Manager.main.post { if (size in 0 until min * 1024L) media.remove(m) else { val i = media.indexOf(m); if (i >= 0) { media[i] = m } } }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun makeWebView(ctx: Context, start: String): WebView {
        val w = WebView(ctx)
        w.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = true
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = true
            displayZoomControls = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            if (Prefs.desktopMode) userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(w, true)
        w.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) { loadProgress = newProgress }
        }
        w.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                if (url != null) {
                    if (url != pageUrl) { media.clear(); seen.clear() }
                    pageUrl = url
                    address = null
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                if (url != null) pageUrl = url
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val u = request?.url?.toString() ?: return false
                val scheme = request.url.scheme?.lowercase() ?: ""
                if (scheme == "magnet") {
                    toMain(Intent(Intent.ACTION_VIEW, request.url)); return true
                }
                if (scheme != "http" && scheme != "https") {
                    runCatching {
                        val i = if (scheme == "intent") Intent.parseUri(u, Intent.URI_INTENT_SCHEME) else Intent(Intent.ACTION_VIEW, request.url)
                        startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                    return true
                }
                val ext = FileTypes.extOfUrl(u)
                if (ext.isNotEmpty() && FileTypes.group(ext) != 0 && wantedType(u, null) && !excluded(u, Prefs.browserExcludedSites)) {
                    onDownload(u, view?.settings?.userAgentString, null, null, -1); return true
                }
                return false
            }

            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                request?.url?.toString()?.let { u -> runCatching { noteMedia(u, request.requestHeaders?.get("Accept")) } }
                return null
            }
        }
        w.setDownloadListener { url, ua, cd, mime, len -> onDownload(url, ua, cd, mime, len) }
        w.setOnLongClickListener { v ->
            val r = (v as WebView).hitTestResult
            when (r.type) {
                WebView.HitTestResult.SRC_ANCHOR_TYPE, WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE, WebView.HitTestResult.IMAGE_TYPE -> {
                    val link = r.extra
                    if (!link.isNullOrBlank() && link.startsWith("http")) { linkMenu = link; true } else false
                }
                else -> false
            }
        }
        w.loadUrl(fix(start))
        web = w
        return w
    }

    private fun fix(input: String): String {
        val t = input.trim()
        if (t.startsWith("http://") || t.startsWith("https://")) return t
        if (t.contains('.') && !t.contains(' ')) return "https://$t"
        return "https://www.google.com/search?q=" + Uri.encode(t)
    }

    private fun grabPage() {
        val js = "(function(){var a=[];document.querySelectorAll('a[href]').forEach(function(e){a.push([e.href,(e.innerText||e.title||'').trim().slice(0,150)])});" +
            "document.querySelectorAll('img[src],video[src],audio[src],source[src],img[data-src]').forEach(function(e){a.push([e.currentSrc||e.src||e.getAttribute('data-src'),e.alt||''])});return JSON.stringify(a);})()"
        web?.evaluateJavascript(js) { res ->
            runCatching {
                val raw = if (res.startsWith("\"")) JSONArray("[$res]").getString(0) else res
                val arr = JSONArray(raw)
                val out = LinkedHashMap<String, GrabbedLink>()
                for (i in 0 until arr.length()) {
                    val p = arr.getJSONArray(i)
                    val u = p.optString(0)
                    if (!u.startsWith("http")) continue
                    val clean = u.substringBefore('#')
                    if (!out.containsKey(clean)) out[clean] = GrabbedLink(clean, FileTypes.group(FileTypes.extOfUrl(clean)), p.optString(1), pageUrl)
                }
                pendingGrab = out.values.filter { it.group != 0 }.ifEmpty { out.values.toList() }
                toMain(Intent().putExtra("grab", true))
            }.onFailure { Sys.toast(this, tr("Could not read this page.", "تعذّرت قراءة هذه الصفحة.")) }
        }
    }

    @Composable
    private fun BrowserUi(start: String) {
        val ctx = LocalContext.current
        var menu by remember { mutableStateOf(false) }
        var sheet by remember { mutableStateOf(false) }
        BackHandler { if (web?.canGoBack() == true) web?.goBack() else finish() }
        val videoSite = VideoEngine.looksLikeVideoSite(pageUrl)
        val panel = Prefs.videoPanel && (media.isNotEmpty() || videoSite) && !excluded(pageUrl, Prefs.panelExcludedSites)
        Scaffold(
            topBar = {
                Column {
                    TopAppBar(
                        title = {
                            OutlinedTextField(
                                value = address ?: pageUrl, onValueChange = { address = it }, singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                                keyboardActions = KeyboardActions(onGo = { val a = address; address = null; if (!a.isNullOrBlank()) web?.loadUrl(fix(a)) }),
                                textStyle = MaterialTheme.typography.bodyMedium,
                            )
                        },
                        navigationIcon = { IconBtn(R.drawable.ic_close, tr("Close", "إغلاق")) { finish() } },
                        actions = {
                            IconBtn(R.drawable.ic_refresh, tr("Reload", "إعادة تحميل")) { web?.reload() }
                            Box {
                                IconBtn(R.drawable.ic_more_vert, tr("More", "المزيد")) { menu = true }
                                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                    DropdownMenuItem(text = { Text(tr("Back", "رجوع")) }, leadingIcon = { Ico(R.drawable.ic_arrow_back) }, onClick = { menu = false; web?.goBack() })
                                    DropdownMenuItem(text = { Text(tr("Forward", "تقدّم")) }, leadingIcon = { Ico(R.drawable.ic_arrow_forward) }, onClick = { menu = false; web?.goForward() })
                                    DropdownMenuItem(text = { Text(tr("Start page", "صفحة البداية")) }, leadingIcon = { Ico(R.drawable.ic_home) }, onClick = { menu = false; web?.loadUrl(fix(Prefs.homePage)) })
                                    DropdownMenuItem(text = { Text(tr("Grab links of this page", "جمع روابط هذه الصفحة")) }, leadingIcon = { Ico(R.drawable.ic_travel_explore) }, onClick = { menu = false; grabPage() })
                                    DropdownMenuItem(text = { Text(tr("Get the video of this page", "جلب فيديو هذه الصفحة")) }, leadingIcon = { Ico(R.drawable.ic_smart_display) }, onClick = {
                                        menu = false; openVideo(pageUrl)
                                    })
                                    DropdownMenuItem(text = { Text(tr("Copy address", "نسخ الرابط")) }, leadingIcon = { Ico(R.drawable.ic_content_copy) }, onClick = { menu = false; Sys.copy(ctx, pageUrl) })
                                    DropdownMenuItem(text = { Text(if (Prefs.desktopMode) tr("Mobile mode", "وضع الهاتف") else tr("Desktop mode", "وضع سطح المكتب")) },
                                        leadingIcon = { Ico(R.drawable.ic_web) }, onClick = { menu = false; Prefs.desktopMode = !Prefs.desktopMode; intent.putExtra("url", pageUrl); recreate() })
                                    DropdownMenuItem(text = { Text(tr("Downloads", "التحميلات")) }, leadingIcon = { Ico(R.drawable.ic_download) }, onClick = { menu = false; toMain(Intent()) })
                                }
                            }
                        },
                    )
                    if (loadProgress in 1..99) LinearProgressIndicator(progress = { loadProgress / 100f }, modifier = Modifier.fillMaxWidth().height(2.dp))
                }
            },
            floatingActionButton = {
                if (panel) ExtendedFloatingActionButton(
                    text = { Text(tr("Download this video", "تحميل هذا الفيديو") + if (media.isNotEmpty()) " (${media.size})" else "") },
                    icon = { Ico(R.drawable.ic_download) },
                    onClick = { if (media.isEmpty()) openVideo(pageUrl) else sheet = true },
                )
            },
        ) { pad ->
            AndroidView(factory = { c -> web ?: makeWebView(c, start) }, modifier = Modifier.fillMaxSize().padding(pad))
        }
        if (sheet) {
            ModalBottomSheet(onDismissRequest = { sheet = false }) {
                Column(Modifier.padding(bottom = 24.dp)) {
                    Text(tr("Videos and sounds of this page", "فيديوهات وأصوات هذه الصفحة"), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(16.dp))
                    Row(Modifier.fillMaxWidth().clickable { sheet = false; openVideo(pageUrl) }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Ico(R.drawable.ic_smart_display)
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(tr("This page — choose the quality", "هذه الصفحة — اختر الجودة"))
                            Text(tr("Best for YouTube, Facebook, Instagram, TikTok…", "الأفضل ليوتيوب وفيسبوك وإنستجرام وتيك توك…"), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    for (m in media.toList()) {
                        Row(Modifier.fillMaxWidth().clickable {
                            sheet = false
                            if (m.kind == "HLS" || m.kind == "DASH") openVideo(m.url)
                            else add = AddRequest(m.url, pageUrl, CookieManager.getInstance().getCookie(m.url), web?.settings?.userAgentString, pageUrl, null, m.size)
                        }.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Ico(if (m.kind in listOf("MP3", "M4A", "AAC", "OGG", "OPUS", "WAV", "FLAC")) R.drawable.ic_music_note else R.drawable.ic_movie)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(m.kind + if (m.size > 0) "  •  " + Fmt.size(m.size) else "", style = MaterialTheme.typography.titleSmall)
                                Text(m.url, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }
        add?.let { r -> AddUrlDialog(r, onNavigate = { s -> goMain(s) }) { add = null } }
        linkMenu?.let { link ->
            AlertDialog(
                onDismissRequest = { linkMenu = null },
                title = { Text(link, maxLines = 3, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium) },
                text = {
                    Column {
                        TextButton(onClick = { linkMenu = null; add = AddRequest(link, pageUrl, CookieManager.getInstance().getCookie(link), web?.settings?.userAgentString, pageUrl) }) {
                            Text(tr("Download with the download manager", "التحميل بمدير التحميل"))
                        }
                        TextButton(onClick = { linkMenu = null; openVideo(link) }) { Text(tr("Get the video", "جلب الفيديو")) }
                        TextButton(onClick = { linkMenu = null; web?.loadUrl(link) }) { Text(tr("Open", "فتح")) }
                        TextButton(onClick = { linkMenu = null; Sys.copy(ctx, link) }) { Text(tr("Copy address", "نسخ الرابط")) }
                    }
                },
                confirmButton = { TextButton(onClick = { linkMenu = null }) { Text(tr("Close", "إغلاق")) } },
            )
        }
    }

    private fun openVideo(url: String) {
        if (url.isBlank()) return
        val cookies = VideoEngine.cookieFileFor(this, pageUrl.ifBlank { url })
        toMain(Intent().putExtra("video", url).putExtra("referer", pageUrl).putExtra("cookies", cookies))
    }
}
