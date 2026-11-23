package com.abdo.barq.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.abdo.barq.Manager
import com.abdo.barq.Prefs
import com.abdo.barq.Sys
import com.abdo.barq.TorrentEngine
import com.abdo.barq.UiEvent
import com.abdo.barq.core.Batch
import com.abdo.barq.tr
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : AppCompatActivity() {
    private val askPermission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Manager.init(applicationContext)
        setContent { BarqTheme { Root() } }
        if (savedInstanceState == null) handle(intent)
        firstRun()
        lifecycleScope.launch {
            Manager.events.collect { e -> onEvent(e) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    override fun onStart() { super.onStart(); Manager.activityStarted() }
    override fun onStop() { super.onStop(); Manager.activityStopped(); com.abdo.barq.Store.saveSoon(10) }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) checkClipboard()
    }

    private fun firstRun() {
        val need = ArrayList<String>()
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            need.add(Manifest.permission.POST_NOTIFICATIONS)
        if (Build.VERSION.SDK_INT <= 29 && ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED)
            need.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        if (need.isNotEmpty() && (Prefs.firstRun || Build.VERSION.SDK_INT <= 29)) runCatching { askPermission.launch(need.toTypedArray()) }
        if (Prefs.firstRun) {
            Prefs.firstRun = false
        } else if (Prefs.showTips) Ui.showTips = true
    }

    private fun checkClipboard() {
        if (!Prefs.watchClipboard) return
        val text = Sys.clipboardText(this)?.trim() ?: return
        val link = Batch.linksFromText(text).firstOrNull() ?: return
        if (link == Prefs.lastClip) return
        Prefs.lastClip = link
        if (Manager.findDuplicate(link) != null) return
        if (Prefs.autoStartClipboard && !link.startsWith("magnet:")) {
            Ui.route(this, link)
        } else {
            Ui.clipLink = link
        }
    }

    private fun handle(i: Intent?) {
        if (i == null) return
        i.getStringExtra("open_file")?.let { Sys.openFile(this, File(it)); return }
        val openId = i.getLongExtra("open_id", -1)
        if (openId >= 0) { Ui.home(); Ui.go(Screen.Details(openId)); return }
        if (i.getBooleanExtra("grab", false)) {
            BrowserActivity.pendingGrab?.let { Ui.go(Screen.Grabber(preset = it)) }
            BrowserActivity.pendingGrab = null
            return
        }
        i.getStringExtra("video")?.let { v ->
            Ui.go(Screen.Video(v, if (v.contains("list=") && !v.contains("watch?v=")) 1 else 0, i.getStringExtra("referer"), i.getStringExtra("cookies")))
            return
        }
        when (i.action) {
            Intent.ACTION_SEND -> {
                val text = i.getStringExtra(Intent.EXTRA_TEXT) ?: i.getStringExtra(Intent.EXTRA_SUBJECT)
                @Suppress("DEPRECATION")
                val stream = i.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                when {
                    text != null -> Ui.routeText(this, text)
                    stream != null -> openUri(stream, i.type)
                }
            }
            Intent.ACTION_VIEW -> i.data?.let { openUri(it, i.type) }
        }
    }

    private fun openUri(uri: Uri, type: String?) {
        when (uri.scheme?.lowercase()) {
            "magnet" -> Ui.go(Screen.Torrent(magnet = uri.toString()))
            "http", "https" -> Ui.route(this, uri.toString())
            "content", "file" -> {
                val isTorrent = type == "application/x-bittorrent" || uri.toString().substringBefore('?').endsWith(".torrent", true) || type == "application/octet-stream"
                if (isTorrent) {
                    runCatching {
                        val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return
                        val f = TorrentEngine.storeTorrent(this, bytes)
                        Ui.go(Screen.Torrent(file = f.absolutePath))
                    }.onFailure { e -> Sys.toast(this, e.message ?: "error") }
                } else {
                    // a text/list file with links
                    runCatching {
                        val text = contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: return
                        Ui.batchText = Batch.linksFromText(text).joinToString("\n")
                    }
                }
            }
        }
    }

    private fun onEvent(e: UiEvent) {
        when (e) {
            is UiEvent.Completed -> Ui.completed = e.item
            is UiEvent.Failed -> lifecycleScope.launch {
                Ui.snack.showSnackbar(tr("Download failed: ", "فشل التحميل: ") + e.item.fileName, duration = SnackbarDuration.Short)
            }
            is UiEvent.Message -> lifecycleScope.launch { Ui.snack.showSnackbar(e.text, duration = SnackbarDuration.Short) }
            is UiEvent.Countdown -> Ui.countdown = e.action
            is UiEvent.TorrentFile -> runCatching {
                val f = TorrentEngine.storeTorrent(this, File(e.path).readBytes())
                Ui.go(Screen.Torrent(file = f.absolutePath))
            }
            is UiEvent.Exit -> finishAffinity()
        }
    }

    @Composable
    private fun Root() {
        BackHandler(enabled = Ui.stack.size > 1) { Ui.back() }
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            when (val s = Ui.screen) {
                is Screen.Main -> MainScreen()
                is Screen.Details -> DetailsScreen(s.id)
                is Screen.Settings -> SettingsScreen()
                is Screen.Queues -> QueuesScreen()
                is Screen.Grabber -> GrabberScreen(s)
                is Screen.Video -> VideoScreen(s)
                is Screen.Torrent -> TorrentScreen(s)
                is Screen.About -> AboutScreen()
            }
        }
        Ui.addRequest?.let { r -> AddUrlDialog(r) { Ui.addRequest = null } }
        Ui.batchText?.let { t -> BatchDialog(t) { Ui.batchText = null } }
        Ui.completed?.let { c -> CompleteDialog(c) { Ui.completed = null } }
        if (Ui.countdown != null) CountdownDialog { Ui.countdown = null }
        if (Ui.showTips) TipsDialog { Ui.showTips = false }
        val clip = Ui.clipLink
        LaunchedEffect(clip) {
            if (clip == null) return@LaunchedEffect
            val r = Ui.snack.showSnackbar(tr("Download the copied link?", "تحميل الرابط المنسوخ؟") + "\n" + clip.take(80), tr("Download", "تحميل"), duration = SnackbarDuration.Long)
            if (r == SnackbarResult.ActionPerformed) Ui.route(this@MainActivity, clip)
            Ui.clipLink = null
        }
    }
}
