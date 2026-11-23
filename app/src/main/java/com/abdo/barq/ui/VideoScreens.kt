@file:OptIn(ExperimentalMaterial3Api::class)

package com.abdo.barq.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import com.abdo.barq.Prefs
import com.abdo.barq.R
import com.abdo.barq.Sys
import com.abdo.barq.VideoEngine
import com.abdo.barq.VideoEntry
import com.abdo.barq.VideoMeta
import com.abdo.barq.core.Fmt
import com.abdo.barq.core.Kind
import com.abdo.barq.core.Names
import com.abdo.barq.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private fun videoCategory(q: String) = if (q == "mp3" || q == "m4a") "music" else "video"

/** Adds one video (or a part of it) to the list. */
fun addVideo(
    url: String, title: String, quality: String, subs: Boolean, start: Boolean, queue: String?, referer: String?, cookieFile: String?,
    number: Int = 0, partFrom: Double = -1.0, partTo: Double = -1.0, exact: Boolean = false, dir: String? = null,
): Long {
    val ext = when (quality) { "mp3" -> "mp3"; "m4a" -> "m4a"; else -> "mp4" }
    var base = Names.sanitize(title.ifBlank { "video" }).take(150)
    if (number > 0) base = "%03d - %s".format(number, base)
    if (partFrom >= 0 && partTo > partFrom) base += " (${Fmt.hms(partFrom).replace(':', '.')}-${Fmt.hms(partTo).replace(':', '.')})"
    val it = Manager.newItem(Kind.VIDEO, url, "$base.$ext")
    it.vTitle = title
    it.vQuality = quality
    it.vSubs = subs
    it.vNumber = number
    it.vStart = partFrom; it.vEnd = partTo; it.vExact = exact
    it.referer = referer
    it.vCookieFile = cookieFile
    it.category = videoCategory(quality)
    it.dir = dir ?: Manager.dirFor(it.category)
    it.nameLocked = true
    it.connections = 4
    Manager.add(it, start, if (start) null else queue)
    return it.id
}

@Composable
fun VideoScreen(s: Screen.Video) {
    val ctx = LocalContext.current
    var url by remember { mutableStateOf(s.url) }
    var mode by remember { mutableIntStateOf(s.mode) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var meta by remember { mutableStateOf<VideoMeta?>(null) }
    var go by remember { mutableIntStateOf(if (s.url.startsWith("http")) 1 else 0) }

    LaunchedEffect(go) {
        if (go == 0 || !url.trim().startsWith("http")) return@LaunchedEffect
        loading = true; error = null; meta = null
        val r = withContext(Dispatchers.IO) { runCatching { VideoEngine.info(ctx, url.trim(), mode == 1, s.referer, s.cookieFile) } }
        loading = false
        r.onSuccess { m -> meta = m; if (mode == 0 && m.entries.isNotEmpty()) mode = 1 }
            .onFailure { e -> error = (e.message ?: e.javaClass.simpleName).lines().lastOrNull { l -> l.isNotBlank() }?.take(300) }
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(when (mode) { 1 -> tr("Download a playlist", "تحميل قائمة تشغيل"); 2 -> tr("Download a part of a video", "تحميل جزء من فيديو"); else -> tr("Download a video", "تحميل فيديو") }) },
            navigationIcon = { IconBtn(R.drawable.ic_arrow_back, tr("Back", "رجوع")) { Ui.back() } },
        )
    }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(horizontal = 16.dp)) {
            Text(when (mode) {
                1 -> tr("Paste the address of a playlist:", "الصق عنوان قائمة التشغيل:")
                2 -> tr("Paste the address of the video (its page, or a link to the video or stream):", "الصق عنوان الفيديو (صفحته، أو رابط الفيديو أو البث):")
                else -> tr("Paste the address of the video page:", "الصق عنوان صفحة الفيديو:")
            }, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Field(tr("Address", "العنوان"), url, { url = it }, Modifier.weight(1f), placeholder = if (mode == 1) "https://…/playlist?list=…" else "https://…")
                IconBtn(R.drawable.ic_content_copy, tr("Paste from clipboard", "لصق من الحافظة")) { Sys.clipboardText(ctx)?.let { c -> url = c.trim() } }
            }
            Button(onClick = { go++ }, enabled = !loading && url.isNotBlank()) {
                Text(if (mode == 1) tr("Get videos", "جلب الفيديوهات") else tr("Get the video", "جلب الفيديو"))
            }
            if (VideoEngine.preparing && !VideoEngine.ready) Hint(tr("Preparing the video downloader (first time only)…", "جارٍ تجهيز محمّل الفيديو (أول مرة فقط)…"))
            if (loading) Row(Modifier.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
                Text(if (mode == 1) tr("Getting the videos of the playlist…", "جارٍ جلب فيديوهات القائمة…") else tr("Looking…", "جارٍ البحث…"))
            }
            error?.let { e -> Text(e, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 8.dp)) }
            val m = meta
            if (m != null) {
                when (mode) {
                    1 -> PlaylistBody(m, s)
                    2 -> PartBody(m, s)
                    else -> SingleBody(m, s)
                }
            } else if (mode == 1 && !loading) Hint(tr("The videos of the playlist show up here.", "تظهر فيديوهات القائمة هنا."))
        }
    }
}

@Composable
private fun QualityPicker(m: VideoMeta?, q: String, onChange: (String) -> Unit) {
    val heights = m?.heights ?: emptyList()
    val opts = VideoEngine.qualities.filter { x ->
        val h = x.toIntOrNull()
        h == null || heights.isEmpty() || heights.any { it >= h * 0.9 } || h == 360
    }.map { x ->
        val est = m?.estimate(x) ?: 0
        x to (VideoEngine.qualityLabel(x) + if (est > 0) "  (~${Fmt.size(est)})" else "")
    }
    Dropdown(tr("Quality:", "الجودة:"), opts, if (opts.any { it.first == q }) q else "best", onSelect = onChange)
}

@Composable
private fun SingleBody(m: VideoMeta, s: Screen.Video) {
    val ctx = LocalContext.current
    var q by remember { mutableStateOf(Prefs.defaultQuality) }
    var subs by remember { mutableStateOf(false) }
    var title by remember { mutableStateOf(m.title) }
    Card(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        Column(Modifier.padding(14.dp)) {
            Text(m.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 3, overflow = TextOverflow.Ellipsis)
            if (m.duration > 0) Text(tr("Length: ", "المدة: ") + Fmt.duration(m.duration.toLong()), style = MaterialTheme.typography.bodySmall)
            Text(m.extractor, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    QualityPicker(m, q) { q = it }
    Field(tr("File name", "اسم الملف"), title, { title = it })
    CheckRow(tr("Subtitles too", "الترجمة أيضاً") + " (${Prefs.subLangs})", subs) { subs = it }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
        FilledTonalButton(onClick = {
            addVideo(m.url, title, q, subs, false, Manager.MAIN_QUEUE, s.referer, s.cookieFile)
            Sys.toast(ctx, tr("Added to the main queue", "أضيف إلى القائمة الرئيسية")); Ui.back()
        }) { Text(tr("Download Later", "تحميل لاحقاً")) }
        Button(onClick = {
            val id = addVideo(m.url, title, q, subs, true, null, s.referer, s.cookieFile)
            Prefs.defaultQuality = q
            Ui.back()
            if (Prefs.showProgress == 0) Ui.go(Screen.Details(id))
        }) { Text(tr("Download", "تحميل")) }
    }
    TextButton(onClick = { Ui.back(); Ui.go(Screen.Video(m.url, 2, s.referer, s.cookieFile)) }) {
        Ico(R.drawable.ic_content_cut); Spacer(Modifier.width(6.dp)); Text(tr("Download a part of it…", "تحميل جزء منه…"))
    }
}

@Composable
private fun PlaylistBody(m: VideoMeta, s: Screen.Video) {
    val ctx = LocalContext.current
    val entries = remember(m) { mutableStateListOf<VideoEntry>().apply { addAll(m.entries) } }
    val checked = remember(m) { mutableStateListOf<Boolean>().apply { repeat(m.entries.size) { add(true) } } }
    var oldestFirst by remember { mutableStateOf(false) }
    var q by remember { mutableStateOf(Prefs.defaultQuality) }
    var parallel by remember { mutableIntStateOf(2) }
    var number by remember { mutableStateOf(true) }
    var subs by remember { mutableStateOf(false) }
    var startNow by remember { mutableStateOf(true) }
    if (m.entries.isEmpty()) {
        Hint(tr("This address is a single video.", "هذا العنوان فيديو واحد."))
        SingleBody(m, s); return
    }
    Text(m.title + " • " + tr("${m.entries.size} videos", "${m.entries.size} فيديو"), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(vertical = 6.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { for (i in checked.indices) checked[i] = true }) { Text(tr("Check all", "تحديد الكل")) }
        TextButton(onClick = { for (i in checked.indices) checked[i] = false }) { Text(tr("Uncheck all", "إلغاء تحديد الكل")) }
        CheckRow(tr("Oldest first", "الأقدم أولاً"), oldestFirst) { oldestFirst = it }
    }
    LazyColumn(Modifier.fillMaxWidth().height(260.dp)) {
        items(entries.size) { i ->
            val e = entries[if (oldestFirst) entries.size - 1 - i else i]
            val idx = entries.indexOf(e)
            Row(Modifier.fillMaxWidth().clickable { checked[idx] = !checked[idx] }.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = checked[idx], onCheckedChange = { v -> checked[idx] = v })
                Text("${e.index}. ", style = MaterialTheme.typography.bodySmall)
                Text(e.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (e.duration > 0) Text(Fmt.duration(e.duration), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    QualityPicker(null, q) { q = it }
    SliderRow(tr("Files at the same time", "ملفات في نفس الوقت"), parallel, 1..6) { parallel = it }
    CheckRow(tr("Number the files", "ترقيم الملفات"), number) { number = it }
    CheckRow(tr("Subtitles too", "الترجمة أيضاً"), subs) { subs = it }
    CheckRow(tr("Start now", "البدء الآن"), startNow) { startNow = it }
    Button(onClick = {
        // a queue just for this playlist, so N files download at the same time
        val qid = "pl" + System.currentTimeMillis()
        val qi = com.abdo.barq.core.QueueInfo(qid, m.title.take(40)).apply { maxParallel = parallel; retries = 2 }
        Manager.queues.add(qi)
        val dir = java.io.File(Manager.dirFor(videoCategory(q)), Names.sanitize(m.title).take(80)).absolutePath
        val order = if (oldestFirst) entries.indices.reversed() else entries.indices
        var n = 0
        for (i in order) {
            if (!checked[i]) continue
            n++
            val e = entries[i]
            addVideo(e.url, e.title, q, subs, false, qid, s.referer, s.cookieFile, number = if (number) (if (oldestFirst) n else e.index) else 0, dir = dir)
        }
        Prefs.defaultQuality = q
        if (startNow) Manager.startQueue(qid)
        Sys.toast(ctx, tr("$n videos added", "تمت إضافة $n فيديو"))
        Ui.back()
    }, modifier = Modifier.padding(vertical = 8.dp)) { Text(tr("Download", "تحميل") + " (${checked.count { it }})") }
}

@Composable
private fun PartBody(m: VideoMeta, s: Screen.Video) {
    val ctx = LocalContext.current
    val len = m.duration.coerceAtLeast(1.0)
    var range by remember(m) { mutableStateOf(0f..len.toFloat().coerceAtMost(60f).coerceAtLeast(1f)) }
    var fromText by remember(m) { mutableStateOf(Fmt.hms(0.0)) }
    var toText by remember(m) { mutableStateOf(Fmt.hms(range.endInclusive.toDouble())) }
    var exact by remember { mutableStateOf(false) }
    var q by remember { mutableStateOf(Prefs.defaultQuality) }
    Text(m.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 6.dp))
    if (m.duration > 0) {
        Text(tr("Length: ", "المدة: ") + Fmt.hms(m.duration), style = MaterialTheme.typography.bodySmall)
        RangeSlider(value = range, onValueChange = { r -> range = r; fromText = Fmt.hms(r.start.toDouble()); toText = Fmt.hms(r.endInclusive.toDouble()) },
            valueRange = 0f..len.toFloat())
    } else Hint(tr("This is a live stream or its length is unknown: type the times.", "هذا بث مباشر أو مدته غير معروفة: اكتب الأوقات."))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Field(tr("From", "من"), fromText, { v -> fromText = v; Fmt.parseHms(v)?.let { x -> if (x < range.endInclusive) range = x.toFloat()..range.endInclusive } }, Modifier.weight(1f))
        Field(tr("To", "إلى"), toText, { v -> toText = v; Fmt.parseHms(v)?.let { x -> if (x > range.start) range = range.start..x.toFloat() } }, Modifier.weight(1f))
    }
    QualityPicker(m, q) { q = it }
    Text(tr("Cut:", "القص:"), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 6.dp))
    RadioRow(tr("Quick cut (recommended)", "قص سريع (مستحسن)"), !exact) { exact = false }
    Hint(tr("Takes seconds and keeps the quality exactly as it is (nothing is made again). A video can only start on a whole picture, so the part may start a second or two before the time you chose.",
        "يستغرق ثوانيَ ويحافظ على الجودة كما هي تمامًا (لا يُعاد إنشاء أي شيء). لا يمكن للفيديو أن يبدأ إلا عند صورة كاملة، لذلك قد يبدأ الجزء قبل الوقت الذي اخترته بثانية أو ثانيتين."))
    RadioRow(tr("Exact cut", "قص دقيق"), exact) { exact = true }
    Hint(tr("Starts and ends on the very second you chose. The picture of the part is made again, so it takes longer and works the processor. Sound alone is always cut exactly.",
        "يبدأ وينتهي في الثانية التي اخترتها بالضبط. تُعاد صناعة صورة الجزء، لذلك يستغرق وقتًا أطول ويُشغّل المعالج. الصوت وحده يُقص دائمًا بدقة."))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
        OutlinedButton(onClick = {
            val p = m.previewUrl()
            if (p == null) Sys.toast(ctx, tr("No preview for this video.", "لا توجد معاينة لهذا الفيديو."))
            else runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(p), "video/*").putExtra("position", (range.start * 1000).toInt()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                .onFailure { Sys.toast(ctx, tr("No video player found.", "لا يوجد مشغل فيديو.")) }
        }) { Text(tr("Preview the part", "معاينة الجزء")) }
        Button(onClick = {
            val a = Fmt.parseHms(fromText) ?: range.start.toDouble()
            val b = Fmt.parseHms(toText) ?: range.endInclusive.toDouble()
            if (b <= a) { Sys.toast(ctx, tr("The end must be after the start.", "يجب أن تكون النهاية بعد البداية.")); return@Button }
            addVideo(m.url, m.title, q, false, true, null, s.referer, s.cookieFile, partFrom = a, partTo = b, exact = exact)
            Ui.back()
        }) { Text(tr("Download the part", "تحميل الجزء")) }
    }
}
