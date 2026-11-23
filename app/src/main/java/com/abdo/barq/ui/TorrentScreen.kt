@file:OptIn(ExperimentalMaterial3Api::class)

package com.abdo.barq.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.abdo.barq.Manager
import com.abdo.barq.R
import com.abdo.barq.Sys
import com.abdo.barq.TorrentEngine
import com.abdo.barq.TorrentPreview
import com.abdo.barq.core.Fmt
import com.abdo.barq.core.Kind
import com.abdo.barq.core.Magnet
import com.abdo.barq.core.Names
import com.abdo.barq.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun TorrentScreen(s: Screen.Torrent) {
    val ctx = LocalContext.current
    var magnet by remember { mutableStateOf(s.magnet) }
    var torrentPath by remember { mutableStateOf(s.file) }
    var preview by remember { mutableStateOf<TorrentPreview?>(null) }
    val wanted = remember { mutableStateListOf<Boolean>() }
    var error by remember { mutableStateOf<String?>(null) }
    var dir by remember { mutableStateOf(Manager.dirFor("torrents")) }
    var queue by remember { mutableStateOf(Manager.MAIN_QUEUE) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            runCatching {
                val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: throw IllegalStateException("empty")
                torrentPath = TorrentEngine.storeTorrent(ctx, bytes).absolutePath
            }.onFailure { e -> error = e.message }
        }
    }

    LaunchedEffect(torrentPath) {
        val p = torrentPath ?: return@LaunchedEffect
        error = null
        val r = withContext(Dispatchers.IO) { runCatching { TorrentEngine.preview(File(p)) } }
        r.onSuccess { pv -> preview = pv; wanted.clear(); repeat(pv.files.size) { wanted.add(true) } }
            .onFailure { e -> error = tr("This is not a valid .torrent file", "هذا ليس ملف تورنت صالحاً") + " (${e.message})" }
    }

    fun add(start: Boolean) {
        val pv = preview
        val it = if (pv != null && torrentPath != null) {
            Manager.newItem(Kind.TORRENT, "torrent:" + pv.infoHash, pv.name).apply {
                torrentFile = torrentPath
                infoHash = pv.infoHash
                size = pv.total
                if (wanted.any { w -> !w }) filePriorities = IntArray(wanted.size) { i -> if (wanted[i]) 4 else 0 }
            }
        } else {
            val m = magnet.trim()
            if (!m.startsWith("magnet:")) { error = tr("Paste a magnet link or open a .torrent file.", "الصق رابط magnet أو افتح ملف torrent."); return }
            Manager.newItem(Kind.TORRENT, m, Magnet.displayName(m) ?: Magnet.infoHash(m) ?: "magnet").apply { infoHash = Magnet.infoHash(m) }
        }
        val dup = Manager.items.firstOrNull { x -> x.kind == Kind.TORRENT && x.infoHash != null && x.infoHash == it.infoHash }
        if (dup != null) { Sys.toast(ctx, tr("This torrent is already in the list.", "هذا التورنت موجود في القائمة بالفعل.")); Ui.back(); Ui.go(Screen.Details(dup.id)); return }
        it.category = "torrents"
        it.dir = dir.trim().ifBlank { Manager.dirFor("torrents") }
        it.fileName = Names.sanitize(it.fileName)
        Manager.add(it, start, if (start) null else queue)
        Ui.back()
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(tr("Add a torrent", "إضافة تورنت")) },
            navigationIcon = { IconBtn(R.drawable.ic_arrow_back, tr("Back", "رجوع")) { Ui.back() } },
        )
    }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(16.dp).verticalScroll(rememberScrollState())) {
            if (!TorrentEngine.available) Text(tr("Torrents are not supported on this device: ", "التورنت غير مدعوم على هذا الجهاز: ") + (TorrentEngine.error ?: ""),
                color = MaterialTheme.colorScheme.error)
            Text(tr("A magnet link, or a .torrent file:", "رابط magnet، أو ملف torrent.:"), style = MaterialTheme.typography.titleSmall)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Field(tr("Magnet link", "رابط magnet"), magnet, { magnet = it; torrentPath = null; preview = null }, Modifier.weight(1f), placeholder = "magnet:?xt=urn:btih:…")
                IconBtn(R.drawable.ic_content_copy, tr("Paste from clipboard", "لصق من الحافظة")) { Sys.clipboardText(ctx)?.let { c -> magnet = c.trim(); torrentPath = null; preview = null } }
            }
            OutlinedButton(onClick = { picker.launch(arrayOf("application/x-bittorrent", "application/octet-stream", "*/*")) }) {
                Ico(R.drawable.ic_folder_open); Spacer(Modifier.width(6.dp)); Text(tr("Open .torrent file…", "فتح ملف torrent.…"))
            }
            error?.let { e -> Text(e, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 6.dp)) }
            val pv = preview
            if (pv != null) {
                SectionTitle(pv.name + " • " + Fmt.size(pv.total))
                for ((i, f) in pv.files.withIndex()) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Checkbox(checked = wanted.getOrElse(i) { true }, onCheckedChange = { v -> if (i < wanted.size) wanted[i] = v })
                        Text(f.first, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Text(Fmt.size(f.second), style = MaterialTheme.typography.bodySmall)
                    }
                }
            } else {
                Hint(tr("Paste a magnet link or open a .torrent file: what it shares shows up here.", "الصق رابط magnet أو افتح ملف torrent: ما يشاركه يظهر هنا."))
                Hint(tr("A magnet link brings its file list from the other computers when it starts: all its files are downloaded (you can uncheck files later in Files).",
                    "رابط magnet يجلب قائمة ملفاته من الأجهزة الأخرى عند بدئه: تُحمَّل كل ملفاته (يمكنك إلغاء ملفات لاحقاً من تبويب الملفات)."))
            }
            FolderField(tr("Save to", "الحفظ في"), dir, { dir = it })
            Dropdown(tr("Queue", "قائمة الانتظار"), queueOptions(), queue) { q -> if (q != null) queue = q }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 10.dp)) {
                FilledTonalButton(onClick = { add(false) }) { Text(tr("Download Later", "تحميل لاحقاً")) }
                Button(onClick = { add(true) }) { Text(tr("Start Download", "بدء التحميل"), fontWeight = FontWeight.Bold) }
            }
        }
    }
}
