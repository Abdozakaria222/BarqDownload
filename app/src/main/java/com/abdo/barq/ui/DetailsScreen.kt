@file:OptIn(ExperimentalMaterial3Api::class)

package com.abdo.barq.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.abdo.barq.Manager
import com.abdo.barq.Prefs
import com.abdo.barq.R
import com.abdo.barq.Sys
import com.abdo.barq.TorrentEngine
import com.abdo.barq.core.Checksum
import com.abdo.barq.core.DownloadItem
import com.abdo.barq.core.Fmt
import com.abdo.barq.core.Kind
import com.abdo.barq.core.St
import com.abdo.barq.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun DetailsScreen(id: Long) {
    val ctx = LocalContext.current
    val tick by Manager.tick.collectAsState()
    val it = Manager.find(id)
    if (it == null) { LaunchedEffect(Unit) { Ui.back() }; return }
    var tab by remember { mutableIntStateOf(0) }
    var deleteAsk by remember { mutableStateOf(false) }
    var move by remember { mutableStateOf(false) }
    val tabs = buildList {
        add(tr("Download status", "حالة التحميل"))
        if (it.kind == Kind.TORRENT) { add(tr("Files", "الملفات")); add(tr("People", "الأشخاص")); add(tr("Trackers", "المتتبعات")) }
        add(tr("Speed Limiter", "محدد السرعة"))
        add(tr("Options on completion", "خيارات عند الاكتمال"))
        add(tr("Properties", "الخصائص"))
    }
    Scaffold(topBar = {
        TopAppBar(
            title = { Text(it.fileName.ifBlank { it.url }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            navigationIcon = { IconBtn(R.drawable.ic_arrow_back, tr("Back", "رجوع")) { Ui.back() } },
            actions = {
                IconBtn(R.drawable.ic_drive_file_rename_outline, tr("Move / Rename…", "نقل / إعادة تسمية…")) { move = true }
                IconBtn(R.drawable.ic_delete, tr("Delete", "حذف")) { deleteAsk = true }
            },
        )
    }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            ScrollableTabRow(selectedTabIndex = tab.coerceAtMost(tabs.size - 1), edgePadding = 8.dp) {
                tabs.forEachIndexed { i, t -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) }) }
            }
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                when (tabs.getOrNull(tab)) {
                    tr("Download status", "حالة التحميل") -> StatusTab(it, tick)
                    tr("Files", "الملفات") -> TorrentFilesTab(it, tick)
                    tr("People", "الأشخاص") -> PeersTab(it, tick)
                    tr("Trackers", "المتتبعات") -> TrackersTab(it, tick)
                    tr("Speed Limiter", "محدد السرعة") -> LimiterTab(it)
                    tr("Options on completion", "خيارات عند الاكتمال") -> CompletionTab(it)
                    else -> PropertiesTab(it)
                }
            }
        }
    }
    if (deleteAsk) DeleteDialog(listOf(id)) { deleteAsk = false; if (Manager.find(id) == null) Ui.back() }
    if (move) MoveRenameDialog(it) { move = false }
}

@Composable
private fun StatusTab(it: DownloadItem, tick: Long) {
    val ctx = LocalContext.current
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(statusText(it), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                color = if (it.status == St.ERROR) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(10.dp))
            if (it.status != St.DONE) {
                if (it.size > 0) LinearProgressIndicator(progress = { it.progress }, modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)))
                else if (it.status == St.RUNNING) LinearProgressIndicator(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)))
                Spacer(Modifier.height(10.dp))
            }
            KeyValue(tr("URL", "الرابط"), it.url)
            KeyValue(tr("Status", "الحالة"), statusText(it) + (it.phase?.let { p -> " — $p" } ?: ""))
            KeyValue(tr("File size", "حجم الملف"), if (it.size > 0) Fmt.size(it.size) else tr("Unknown", "غير معروف"))
            KeyValue(tr("Downloaded", "تم تحميل"), Fmt.size(it.downloaded) + if (it.size > 0) "  (${"%.1f".format(Locale.US, it.progress * 100)}%)" else "")
            if (it.status == St.RUNNING) {
                KeyValue(tr("Transfer rate", "سرعة النقل"), Fmt.speed(it.speed).ifEmpty { "—" })
                KeyValue(tr("Time left", "الوقت المتبقي"), if (it.eta >= 0 && it.speed > 0) Fmt.duration(it.eta) else "—")
            }
            if (it.kind == Kind.HTTP) KeyValue(tr("Resume capability", "إمكانية الاستئناف"), when (it.resumable) {
                true -> tr("Yes", "نعم"); false -> tr("No", "لا"); else -> tr("Not checked yet", "لم يُفحص بعد")
            })
            if (it.kind == Kind.TORRENT) {
                KeyValue(tr("People", "الأشخاص"), "${it.peers} (${tr("seeds", "مصادر")} ${it.seeds})")
                KeyValue(tr("Sent", "تم الإرسال"), Fmt.size(it.uploaded) + if (it.upSpeed > 0) "  ↑ ${Fmt.speed(it.upSpeed)}" else "")
            }
            KeyValue(tr("Save To", "الحفظ في"), it.dir)
            it.error?.let { e -> KeyValue(tr("Error", "خطأ"), e) }
        }
    }
    Spacer(Modifier.height(12.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        when {
            it.status == St.RUNNING || it.status == St.WAITING -> Button(onClick = { Manager.pause(it.id) }) { Ico(R.drawable.ic_pause); Spacer(Modifier.width(6.dp)); Text(tr("Pause", "إيقاف مؤقت")) }
            it.status == St.DONE -> {
                if (it.kind != Kind.TORRENT) Button(onClick = { Sys.openFile(ctx, File(it.dir, it.fileName)) }) { Ico(R.drawable.ic_open_in_new); Spacer(Modifier.width(6.dp)); Text(tr("Open", "فتح")) }
                OutlinedButton(onClick = { Sys.openFolder(ctx, it.dir) }) { Text(tr("Open folder", "فتح المجلد")) }
                if (it.seeding) OutlinedButton(onClick = { Manager.pause(it.id) }) { Text(tr("Stop sharing", "إيقاف المشاركة")) }
            }
            else -> Button(onClick = { Manager.start(it.id) }) { Ico(R.drawable.ic_play_arrow); Spacer(Modifier.width(6.dp)); Text(tr("Resume", "استئناف")) }
        }
    }
    if (it.kind == Kind.HTTP && it.segments.isNotEmpty() && it.status != St.DONE) {
        SectionTitle(tr("Start positions and download progress by connections", "مواضع البدء وتقدّم التحميل حسب الاتصالات"))
        SegmentsBar(it, tick)
        Spacer(Modifier.height(8.dp))
        val active = it.segments.count { s -> s.active }
        Hint(tr("${it.segments.size} parts • $active connections working", "${it.segments.size} أجزاء • $active اتصالات تعمل"))
    }
    if (it.kind == Kind.TORRENT) {
        SectionTitle(tr("Torrent", "تورنت"))
        CheckRow(tr("First and last pieces first", "أول وآخر الأجزاء أولاً"), it.firstLast) { v -> TorrentEngine.setFirstLast(it, v) }
        CheckRow(tr("Download in order (to watch while downloading)", "التحميل بالترتيب (للمشاهدة أثناء التحميل)"), it.seqDownload) { v -> TorrentEngine.setSequential(it, v) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { TorrentEngine.recheck(it) }) { Text(tr("Check the files again", "فحص الملفات مرة أخرى")) }
            OutlinedButton(onClick = { Sys.copy(ctx, TorrentEngine.magnetUri(it)); Sys.toast(ctx, tr("Copied", "تم النسخ")) }) { Text(tr("Copy magnet link", "نسخ رابط magnet")) }
        }
    }
    if (it.status == St.DONE && it.kind != Kind.TORRENT) ChecksumBox(it)
}

@Composable
private fun SegmentsBar(it: DownloadItem, tick: Long) {
    val total = it.size.coerceAtLeast(1)
    val done = MaterialTheme.colorScheme.primary
    val active = MaterialTheme.colorScheme.tertiary
    val rest = MaterialTheme.colorScheme.surfaceVariant
    val segs = it.segments.toList()
    Canvas(Modifier.fillMaxWidth().height(26.dp).clip(RoundedCornerShape(6.dp))) {
        drawRect(rest)
        if (it.size <= 0) {
            drawRect(done, size = Size(size.width * 0.02f, size.height))
            return@Canvas
        }
        for (s in segs) {
            val x0 = size.width * (s.start.toFloat() / total)
            val w = size.width * (s.downloaded.toFloat() / total)
            drawRect(if (s.active) active else done, topLeft = Offset(x0, 0f), size = Size(w.coerceAtLeast(0f), size.height))
            drawRect(rest.copy(alpha = 0.0f), topLeft = Offset(x0, 0f), size = Size(1f, size.height))
            drawLine(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.25f), Offset(x0, 0f), Offset(x0, size.height), strokeWidth = 1f)
        }
    }
}

@Composable
private fun ChecksumBox(it: DownloadItem) {
    var running by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var result by remember { mutableStateOf<String?>(null) }
    var expected by remember { mutableStateOf(it.checksum ?: "") }
    var algo by remember { mutableStateOf("SHA-256") }
    var go by remember { mutableIntStateOf(0) }
    SectionTitle(tr("Check file", "فحص الملف"))
    Dropdown(tr("Algorithm", "الخوارزمية"), listOf("MD5" to "MD5", "SHA-1" to "SHA-1", "SHA-256" to "SHA-256"), algo) { a -> algo = a }
    Field(tr("Expected value (optional)", "القيمة المتوقعة (اختياري)"), expected, { v -> expected = v; if (v.length == 32) algo = "MD5"; if (v.length == 40) algo = "SHA-1"; if (v.length == 64) algo = "SHA-256" })
    Button(enabled = !running, onClick = { go++ }) { Ico(R.drawable.ic_fingerprint); Spacer(Modifier.width(6.dp)); Text(tr("Check file", "فحص الملف") + " ($algo)") }
    if (running) LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
    result?.let { r ->
        val match = expected.isNotBlank() && r.equals(expected.trim(), ignoreCase = true)
        Text(r, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
        if (expected.isNotBlank()) Text(
            if (match) tr("✓ The file is correct (it matches).", "✓ الملف سليم (مطابق).") else tr("✗ It does not match!", "✗ غير مطابق!"),
            color = if (match) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold,
        )
    }
    LaunchedEffect(go) {
        if (go == 0) return@LaunchedEffect
        running = true; result = null
        val f = File(it.dir, it.fileName)
        result = withContext(Dispatchers.IO) {
            runCatching { Checksum.of(f, algo) { p -> progress = p } }.getOrElse { e -> tr("Error: ", "خطأ: ") + e.message }
        }
        running = false
    }
}

@Composable
private fun TorrentFilesTab(it: DownloadItem, tick: Long) {
    val files = remember(tick) { TorrentEngine.files(it) }
    if (files.isEmpty()) { Hint(tr("The file list shows up when the torrent starts.", "تظهر قائمة الملفات عند بدء التورنت.")); return }
    for (f in files) {
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = f.wanted, onCheckedChange = { v -> TorrentEngine.setFileWanted(it, f.index, v) })
            Column(Modifier.weight(1f)) {
                Text(f.path, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                LinearProgressIndicator(progress = { if (f.size > 0) (f.done.toFloat() / f.size).coerceIn(0f, 1f) else 0f }, modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp))
                Text("${Fmt.size(f.done)} / ${Fmt.size(f.size)}", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun PeersTab(it: DownloadItem, tick: Long) {
    val peers = remember(tick / 3) { TorrentEngine.peers(it) }
    if (peers.isEmpty()) { Hint(tr("No one is connected right now.", "لا يوجد أحد متصل الآن.")); return }
    for (p in peers.sortedByDescending { x -> x.down }) {
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Column(Modifier.weight(1f)) {
                Text(p.ip, style = MaterialTheme.typography.bodyMedium)
                Text(p.client, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("↓ " + Fmt.speed(p.down.toLong()).ifEmpty { "0" }, style = MaterialTheme.typography.bodySmall)
                Text("↑ " + Fmt.speed(p.up.toLong()).ifEmpty { "0" }, style = MaterialTheme.typography.bodySmall)
                Text("${(p.progress * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun TrackersTab(it: DownloadItem, tick: Long) {
    var add by remember { mutableStateOf("") }
    val list = remember(tick / 5) { TorrentEngine.trackers(it) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Field(tr("Add tracker", "إضافة متتبع"), add, { v -> add = v }, Modifier.weight(1f), placeholder = "udp://tracker…:1337/announce")
        IconBtn(R.drawable.ic_add, tr("Add tracker", "إضافة متتبع")) { TorrentEngine.addTracker(it, add); add = "" }
    }
    for (t in list) Text(t, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 4.dp))
}

@Composable
private fun LimiterTab(it: DownloadItem) {
    var on by remember { mutableStateOf(it.limiterOn) }
    var kb by remember { mutableIntStateOf(if (it.speedLimit > 0) it.speedLimit else 500) }
    SwitchRow(tr("Use speed limiter for this download", "استخدام محدد السرعة لهذا التحميل"), on) { v ->
        on = v; it.limiterOn = v; it.speedLimit = kb; Manager.runners[it.id]?.applyLimit(); Manager.changed()
    }
    IntField(tr("Maximum download speed (KB/s)", "أقصى سرعة تحميل (كيلوبايت/ث)"), kb, { v ->
        kb = v; it.speedLimit = v; Manager.runners[it.id]?.applyLimit(); Manager.changed()
    }, min = 1)
    Hint(tr("Changes apply right away, even while downloading.", "التغييرات تُطبَّق فوراً حتى أثناء التحميل."))
    if (it.kind == Kind.VIDEO) Hint(tr("For videos the new limit is used the next time the download starts.", "في الفيديوهات يُطبَّق الحد الجديد عند بدء التحميل في المرة القادمة."))
    SectionTitle(tr("Connections", "الاتصالات"))
    var c by remember { mutableIntStateOf(it.connections) }
    SliderRow(tr("Connections", "الاتصالات"), c, 1..32) { v -> c = v; it.connections = v; Manager.changed() }
    Hint(tr("Used the next time the download starts.", "تُستخدم عند بدء التحميل في المرة القادمة."))
}

@Composable
private fun CompletionTab(it: DownloadItem) {
    var v by remember { mutableIntStateOf(it.onComplete) }
    Text(tr("When this download completes:", "عند اكتمال هذا التحميل:"), style = MaterialTheme.typography.titleSmall)
    RadioRow(tr("Do nothing", "لا شيء"), v == 0) { v = 0; it.onComplete = 0; Manager.changed() }
    RadioRow(tr("Exit the app", "الخروج من التطبيق"), v == 1) { v = 1; it.onComplete = 1; Manager.changed() }
    SwitchRow(tr("Show the \"Download complete\" window", "إظهار نافذة \"اكتمل التحميل\""), Prefs.showCompleteDialog) { x -> Prefs.showCompleteDialog = x }
}

@Composable
private fun PropertiesTab(it: DownloadItem) {
    var url by remember { mutableStateOf(it.url) }
    var desc by remember { mutableStateOf(it.description ?: "") }
    var ref by remember { mutableStateOf(it.referer ?: "") }
    var ua by remember { mutableStateOf(it.userAgent ?: "") }
    var user by remember { mutableStateOf(it.authUser ?: "") }
    var pass by remember { mutableStateOf(it.authPass ?: "") }
    val fmt = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US) }
    KeyValue(tr("File name", "اسم الملف"), it.fileName)
    KeyValue(tr("Category", "التصنيف"), Manager.categoryName(it.category))
    KeyValue(tr("Queue", "قائمة الانتظار"), Manager.queueName(it.queueId).ifEmpty { "—" })
    KeyValue(tr("Date Added", "تاريخ الإضافة"), fmt.format(Date(it.dateAdded)))
    if (it.lastTry > 0) KeyValue(tr("Last Try Date", "آخر محاولة"), fmt.format(Date(it.lastTry)))
    if (it.dateDone > 0) KeyValue(tr("Finished", "اكتمل"), fmt.format(Date(it.dateDone)))
    it.parentPage?.let { p -> KeyValue(tr("Parent Web Page", "صفحة الويب الأصلية"), p) }
    Spacer(Modifier.size(8.dp))
    Field(tr("URL", "الرابط"), url, { v -> url = v })
    Field(tr("Description", "الوصف"), desc, { v -> desc = v })
    Field(tr("Referer", "المُحيل (Referer)"), ref, { v -> ref = v })
    Field("User-Agent", ua, { v -> ua = v })
    Text(tr("Use authorization (server login)", "استخدام تسجيل الدخول (حساب الخادم)"), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Field(tr("Login", "اسم المستخدم"), user, { v -> user = v }, Modifier.weight(1f))
        Field(tr("Password", "كلمة المرور"), pass, { v -> pass = v }, Modifier.weight(1f))
    }
    Button(onClick = {
        if (url.trim() != it.url) Manager.refreshAddress(it.id, url.trim())
        it.description = desc.ifBlank { null }; it.referer = ref.ifBlank { null }; it.userAgent = ua.ifBlank { null }
        it.authUser = user.ifBlank { null }; it.authPass = pass.ifBlank { null }
        Manager.changed()
    }, modifier = Modifier.padding(top = 8.dp)) { Text(tr("Apply", "تطبيق")) }
}
