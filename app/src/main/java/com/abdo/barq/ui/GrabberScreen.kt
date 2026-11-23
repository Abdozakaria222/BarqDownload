@file:OptIn(ExperimentalMaterial3Api::class)

package com.abdo.barq.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.abdo.barq.Manager
import com.abdo.barq.Prefs
import com.abdo.barq.R
import com.abdo.barq.Sys
import com.abdo.barq.core.FileTypes
import com.abdo.barq.core.GrabOptions
import com.abdo.barq.core.GrabbedLink
import com.abdo.barq.core.Grabber
import com.abdo.barq.core.Kind
import com.abdo.barq.core.Names
import com.abdo.barq.tr

private fun groupName(g: Int): String = when (g) {
    0 -> tr("Web pages", "صفحات الويب")
    1 -> tr("Images", "صور")
    2 -> tr("Videos", "فيديوهات")
    3 -> tr("Music", "موسيقى")
    4 -> tr("Archives / programs", "مضغوطة / برامج")
    5 -> tr("Documents", "مستندات")
    else -> tr("Other files", "ملفات أخرى")
}

/** "Site Grabber": collect the files of a web site. */
@Composable
fun GrabberScreen(s: Screen.Grabber) {
    val ctx = LocalContext.current
    var url by remember { mutableStateOf(s.url) }
    var preset by remember { mutableIntStateOf(0) }   // 0 all files, 1 images, 2 videos, 3 music, 4 archives, 5 documents, 6 custom
    var custom by remember { mutableStateOf("jpg png mp4") }
    var levels by remember { mutableIntStateOf(1) }
    var pages by remember { mutableIntStateOf(4) }
    var otherSites by remember { mutableStateOf(false) }
    var noParent by remember { mutableStateOf(true) }
    var running by remember { mutableStateOf(false) }
    var pagesDone by remember { mutableIntStateOf(0) }
    val found = remember { mutableStateListOf<GrabbedLink>().apply { s.preset?.let { addAll(it) } } }
    var typeFilter by remember { mutableIntStateOf(-1) }
    var textFilter by remember { mutableStateOf("") }
    var useText by remember { mutableStateOf(true) }
    var grabber by remember { mutableStateOf<Grabber?>(null) }
    var showOptions by remember { mutableStateOf(s.preset == null) }
    val checkedSet = remember { mutableStateListOf<String>().apply { s.preset?.forEach { add(it.url) } } }

    DisposableEffect(Unit) { onDispose { grabber?.cancelled = true } }

    fun start() {
        found.clear(); checkedSet.clear(); pagesDone = 0
        val groups = when (preset) { 1 -> setOf(1); 2 -> setOf(2); 3 -> setOf(3); 4 -> setOf(4); 5 -> setOf(5); else -> emptySet() }
        val exts = if (preset == 6) custom.lowercase().split(' ', ',').map { it.trim().trimStart('.') }.filter { it.isNotEmpty() }.toSet() else emptySet()
        val g = Grabber(Prefs.netConfig(), GrabOptions(groups, exts, levels, pages, otherSites, noParent),
            onLink = { l -> Manager.main.post { found.add(l); checkedSet.add(l.url) } },
            onPage = { _, n -> Manager.main.post { pagesDone = n } })
        grabber = g
        running = true
        showOptions = false
        Thread {
            g.run(url.trim())
            Manager.main.post { running = false }
        }.start()
    }

    val shown = found.filter { l ->
        (typeFilter < 0 || l.group == typeFilter) &&
            (textFilter.isBlank() || l.url.contains(textFilter, true) || l.text.contains(textFilter, true))
    }.distinctBy { it.url }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(tr("Site Grabber", "جامع الملفات من المواقع")) },
            navigationIcon = { IconBtn(R.drawable.ic_arrow_back, tr("Back", "رجوع")) { grabber?.cancelled = true; Ui.back() } },
            actions = { IconBtn(R.drawable.ic_tune, tr("Options", "الخيارات")) { showOptions = !showOptions } },
        )
    }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(horizontal = 14.dp)) {
            if (showOptions) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Field(tr("Start page", "صفحة البداية"), url, { url = it }, Modifier.weight(1f), placeholder = "https://example.com/gallery/")
                    IconBtn(R.drawable.ic_content_copy, tr("Paste", "لصق")) { Sys.clipboardText(ctx)?.let { c -> url = c.trim() } }
                }
                Dropdown(tr("Files to collect", "الملفات المطلوبة"), listOf(
                    0 to tr("All files (not web pages)", "كل الملفات (ليست صفحات ويب)"), 1 to tr("All images", "كل الصور"),
                    2 to tr("All videos", "كل الفيديوهات"), 3 to tr("All music / audio", "كل الموسيقى / الصوت"),
                    4 to tr("Archives and programs", "الملفات المضغوطة والبرامج"), 5 to tr("Documents (PDF, Office)", "المستندات (PDF، أوفيس)"),
                    6 to tr("Custom file types", "أنواع ملفات مخصصة"),
                ), preset) { preset = it }
                if (preset == 6) Field(tr("Custom file types", "أنواع ملفات مخصصة"), custom, { custom = it }, placeholder = tr("jpg png mp4 (custom types)", "jpg png mp4 (أنواع مخصصة)"))
                SliderRow(tr("Levels to explore", "مستويات الاستكشاف"), levels, 0..5) { levels = it }
                SliderRow(tr("Pages at the same time", "صفحات في نفس الوقت"), pages, 1..8) { pages = it }
                CheckRow(tr("Other sites too", "مواقع أخرى أيضاً"), otherSites) { otherSites = it }
                CheckRow(tr("Don't go to parent folders", "عدم الذهاب للمجلدات الأعلى"), noParent) { noParent = it }
                Hint(tr("Some pages make their links only in the browser (with scripts). For them: open the page in the built-in browser and use \"Grab links of this page\".",
                    "بعض الصفحات تصنع روابطها داخل المتصفح فقط (بالسكربتات). لها: افتح الصفحة في المتصفح المدمج واستخدم \"جمع روابط هذه الصفحة\"."))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = !running && url.trim().startsWith("http"), onClick = { start() }) { Text(tr("Start exploring", "بدء الاستكشاف")) }
                    OutlinedButton(onClick = { BrowserActivity.open(ctx, url.trim().ifBlank { null }) }) { Text(tr("Open in browser", "فتح في المتصفح")) }
                }
            }
            if (running) Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text(tr("Exploring… $pagesDone pages, ${found.size} files", "جارٍ الاستكشاف… $pagesDone صفحة، ${found.size} ملف"), modifier = Modifier.weight(1f))
                TextButton(onClick = { grabber?.cancelled = true }) { Text(tr("Stop", "إيقاف")) }
            } else if (found.isEmpty() && !showOptions) {
                Text(if (pagesDone > 0) tr("No links on this page.", "لا توجد روابط في هذه الصفحة.") else tr("Type a start page and click \"Start exploring\".", "اكتب صفحة البداية واضغط \"بدء الاستكشاف\"."),
                    modifier = Modifier.padding(vertical = 12.dp))
            }
            if (found.isNotEmpty()) {
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = typeFilter < 0, onClick = { typeFilter = -1 }, label = { Text(tr("All types", "كل الأنواع") + " (${found.size})") })
                    for (g in listOf(1, 2, 3, 4, 5, 6, 0)) {
                        val n = found.count { it.group == g }
                        if (n > 0) FilterChip(selected = typeFilter == g, onClick = { typeFilter = g }, label = { Text(groupName(g) + " ($n)") })
                    }
                }
                Field(tr("Filter by name or address…", "تصفية بالاسم أو الرابط…"), textFilter, { textFilter = it })
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { shown.forEach { l -> if (l.url !in checkedSet) checkedSet.add(l.url) } }) { Text(tr("Check all", "تحديد الكل")) }
                    TextButton(onClick = { shown.forEach { l -> checkedSet.remove(l.url) } }) { Text(tr("Uncheck all", "إلغاء تحديد الكل")) }
                }
                LazyColumn(Modifier.weight(1f)) {
                    items(shown, key = { it.url }) { l ->
                        Row(Modifier.fillMaxWidth().clickable { if (l.url in checkedSet) checkedSet.remove(l.url) else checkedSet.add(l.url) }.padding(vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = l.url in checkedSet, onCheckedChange = { v -> if (v) checkedSet.add(l.url) else checkedSet.remove(l.url) })
                            Ico(iconForName(Names.fromUrl(l.url)), null, Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text(Names.fromUrl(l.url), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(if (l.text.isNotBlank()) l.text else l.url, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                CheckRow(tr("Use link texts as descriptions", "استخدام نصوص الروابط كأوصاف"), useText) { useText = it }
                Button(enabled = checkedSet.isNotEmpty(), modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp), onClick = {
                    var n = 0
                    for (l in found.filter { it.url in checkedSet }) {
                        val it = Manager.newItem(Kind.HTTP, l.url)
                        it.referer = l.page; it.parentPage = l.page
                        if (useText && l.text.isNotBlank()) it.description = l.text
                        Manager.add(it, false, Manager.MAIN_QUEUE); n++
                    }
                    Manager.startQueue(Manager.MAIN_QUEUE)
                    Sys.toast(ctx, tr("$n files added to the main queue", "تمت إضافة $n ملف إلى القائمة الرئيسية"))
                    Ui.back()
                }) { Text(tr("Download checked", "تحميل المحدد") + " (${checkedSet.size})") }
            }
        }
    }
}
