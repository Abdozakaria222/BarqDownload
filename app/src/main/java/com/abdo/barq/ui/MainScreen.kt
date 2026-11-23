@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package com.abdo.barq.ui

import android.content.Context
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.abdo.barq.Manager
import com.abdo.barq.Prefs
import com.abdo.barq.R
import com.abdo.barq.Store
import com.abdo.barq.Sys
import com.abdo.barq.VideoEngine
import com.abdo.barq.appName
import com.abdo.barq.core.Categories
import com.abdo.barq.core.DownloadItem
import com.abdo.barq.core.Ef2
import com.abdo.barq.core.Fmt
import com.abdo.barq.core.Kind
import com.abdo.barq.core.St
import com.abdo.barq.tr
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One line of the download list, rebuilt every tick so only changed lines redraw. */
data class RowData(
    val id: Long, val name: String, val icon: Int, val status: St, val progress: Float, val info: String,
    val error: Boolean, val seeding: Boolean, val kind: Kind, val indeterminate: Boolean,
)

fun statusText(it: DownloadItem): String = when (it.status) {
    St.RUNNING -> it.phase ?: if (it.size > 0) "${(it.progress * 100).toInt()}%" else tr("Downloading", "جارٍ التحميل")
    St.DONE -> if (it.seeding) tr("Sharing", "يشارك") else tr("Complete", "مكتمل")
    St.PAUSED -> tr("Stopped", "متوقف")
    St.QUEUED -> tr("Queued", "في الانتظار")
    St.WAITING -> it.phase ?: tr("Waiting…", "ينتظر…")
    St.ERROR -> tr("Error", "خطأ")
}

private val dateFmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)

fun rowOf(it: DownloadItem): RowData {
    val parts = ArrayList<String>()
    if (Prefs.colStatus) parts.add(statusText(it))
    when (it.status) {
        St.RUNNING -> {
            if (Prefs.colSize) parts.add(if (it.size > 0) "${Fmt.size(it.downloaded)} / ${Fmt.size(it.size)}" else Fmt.size(it.downloaded))
            if (Prefs.colSpeed && it.speed > 0) parts.add(Fmt.speed(it.speed))
            if (it.kind == Kind.TORRENT) parts.add("👥 ${it.peers}")
            if (Prefs.colTimeLeft && it.eta >= 0 && it.speed > 0) parts.add(Fmt.duration(it.eta) + tr(" left", " متبقٍ"))
        }
        St.DONE -> {
            if (Prefs.colSize) parts.add(Fmt.size(it.size))
            if (it.seeding && it.upSpeed > 0) parts.add("↑ " + Fmt.speed(it.upSpeed))
        }
        St.ERROR -> it.error?.let { e -> parts.add(e.lines().first().take(90)) }
        else -> if (Prefs.colSize && (it.size > 0 || it.downloaded > 0)) {
            parts.add(if (it.size > 0) "${(it.progress * 100).toInt()}% / ${Fmt.size(it.size)}" else Fmt.size(it.downloaded))
        }
    }
    if (Prefs.colQueue && it.queueId != null && it.status != St.DONE) parts.add(tr("Q", "ق") + it.queuePos)
    if (Prefs.colDate) parts.add(dateFmt.format(Date(it.dateAdded)))
    if (Prefs.colSaveTo) parts.add(it.dir.substringAfterLast("/Download/", it.dir))
    if (it.status == St.DONE && it.error != null) parts.add("⚠ " + it.error)
    return RowData(it.id, it.fileName.ifBlank { it.url }, iconFor(it), it.status, it.progress, parts.joinToString("  •  "),
        it.status == St.ERROR || (it.status == St.DONE && it.error != null), it.seeding, it.kind,
        it.status == St.RUNNING && it.size <= 0)
}

fun filterTitle(f: String): String = when {
    f == "all" -> tr("All Downloads", "كل التحميلات")
    f == "unfinished" -> tr("Unfinished", "غير المكتملة")
    f == "finished" -> tr("Finished", "المكتملة")
    f.startsWith("q:") -> Manager.queueName(f.removePrefix("q:"))
    f.startsWith("c:") -> Manager.categoryName(f.removePrefix("c:"))
    else -> appName()
}

fun matches(it: DownloadItem, f: String): Boolean = when {
    f == "unfinished" -> it.status != St.DONE
    f == "finished" -> it.status == St.DONE
    f.startsWith("q:") -> it.queueId == f.removePrefix("q:")
    f.startsWith("c:") -> it.category == f.removePrefix("c:") || (f == "c:${Categories.GENERAL}" && it.category.isBlank())
    else -> true
}

@Composable
fun MainScreen() {
    val ctx = LocalContext.current
    val tick by Manager.tick.collectAsState()
    Prefs.version.intValue
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var filter by remember { mutableStateOf("all") }
    var search by remember { mutableStateOf<String?>(null) }
    val selected = remember { mutableStateListOf<Long>() }
    var addSheet by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var deleteIds by remember { mutableStateOf<List<Long>?>(null) }
    var catEdit by remember { mutableStateOf<com.abdo.barq.core.Category?>(null) }
    var catNew by remember { mutableStateOf(false) }
    var columnsDialog by remember { mutableStateOf(false) }
    var sortDialog by remember { mutableStateOf(false) }
    var allDoneDialog by remember { mutableStateOf(false) }
    var queuePick by remember { mutableStateOf<List<Long>?>(null) }
    var exportKind by remember { mutableStateOf(0) }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri: Uri? ->
        if (uri != null) exportTo(ctx, uri, exportKind, selected.toList())
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) importFrom(ctx, uri)
    }

    val rows: List<RowData> = remember(tick, filter, search, Prefs.version.intValue) {
        var list = Manager.items.filter { matches(it, filter) }
        search?.takeIf { s -> s.isNotBlank() }?.let { s ->
            list = list.filter { x -> x.fileName.contains(s, true) || x.url.contains(s, true) || (x.description ?: "").contains(s, true) || (x.referer ?: "").contains(s, true) }
        }
        list = when (Prefs.sortOrder) {
            1 -> list.sortedBy { x -> x.fileName.lowercase() }
            2 -> list.sortedByDescending { x -> x.size }
            3 -> list.sortedByDescending { x -> x.lastTry }
            else -> if (filter.startsWith("q:")) list.sortedBy { x -> x.queuePos } else list.sortedByDescending { x -> x.dateAdded }
        }
        list.map { rowOf(it) }
    }

    BackHandler(enabled = drawer.isOpen || selected.isNotEmpty() || search != null) {
        when {
            drawer.isOpen -> scope.launch { drawer.close() }
            selected.isNotEmpty() -> selected.clear()
            else -> search = null
        }
    }

    fun openItem(id: Long) {
        val it = Manager.find(id) ?: return
        if (it.status == St.DONE && Prefs.tapAction == 0 && it.kind != Kind.TORRENT) {
            Sys.openFile(ctx, File(it.dir, it.fileName))
        } else Ui.go(Screen.Details(id))
    }

    fun del(ids: List<Long>) {
        if (ids.isEmpty()) return
        if (Prefs.askBeforeRemove) deleteIds = ids else Manager.delete(ids, false)
    }

    ModalNavigationDrawer(drawerState = drawer, drawerContent = {
        ModalDrawerSheet {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
                Row(Modifier.padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(42.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
                        Ico(R.drawable.ic_bolt, null, tint = MaterialTheme.colorScheme.onPrimary)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(appName(), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        val sp = Manager.runners.values.sumOf { r -> r.item.speed }
                        Text(if (sp > 0) Fmt.speed(sp) else tr("Download manager", "مدير التحميل"), style = MaterialTheme.typography.bodySmall)
                    }
                }
                fun go(f: String) { filter = f; selected.clear(); scope.launch { drawer.close() } }
                @Composable
                fun item(f: String, icon: Int, label: String, count: Int, onLong: (() -> Unit)? = null) {
                    NavigationDrawerItem(
                        label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        selected = filter == f,
                        onClick = { go(f) },
                        icon = { Ico(icon) },
                        badge = { if (count > 0) Text(count.toString()) },
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                }
                item("all", R.drawable.ic_list, tr("All Downloads", "كل التحميلات"), Manager.items.size)
                item("unfinished", R.drawable.ic_download, tr("Unfinished", "غير المكتملة"), Manager.items.count { it.status != St.DONE })
                item("finished", R.drawable.ic_download_done, tr("Finished", "المكتملة"), Manager.items.count { it.status == St.DONE })
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Text(tr("Queues", "قوائم الانتظار"), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 28.dp, vertical = 6.dp))
                for (q in Manager.queues) {
                    item("q:${q.id}", if (q.running) R.drawable.ic_play_circle else R.drawable.ic_queue,
                        Manager.queueName(q.id) + if (q.running) tr(" (running)", " (تعمل)") else "", Manager.items.count { it.queueId == q.id && it.status != St.DONE })
                }
                NavigationDrawerItem(label = { Text(tr("Edit queue / schedule…", "تعديل القائمة / الجدولة…")) }, selected = false,
                    onClick = { scope.launch { drawer.close() }; Ui.go(Screen.Queues) }, icon = { Ico(R.drawable.ic_schedule) }, modifier = Modifier.padding(horizontal = 12.dp))
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Text(tr("Categories", "التصنيفات"), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 28.dp, vertical = 6.dp))
                val catIds = (Manager.categories.map { it.id } + Categories.GENERAL).distinct()
                for (cid in catIds) {
                    val c = Manager.category(cid)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) {
                            item("c:$cid", when (cid) {
                                "video" -> R.drawable.ic_movie; "music" -> R.drawable.ic_music_note; "documents" -> R.drawable.ic_description
                                "compressed" -> R.drawable.ic_folder_zip; "programs" -> R.drawable.ic_android; "images" -> R.drawable.ic_image
                                "torrents" -> R.drawable.ic_hub; else -> R.drawable.ic_label
                            }, Manager.categoryName(cid), Manager.items.count { it.category == cid })
                        }
                        if (c != null) IconBtn(R.drawable.ic_edit, tr("Properties…", "الخصائص…")) { catEdit = c }
                    }
                }
                NavigationDrawerItem(label = { Text(tr("Add category…", "إضافة تصنيف…")) }, selected = false,
                    onClick = { catNew = true }, icon = { Ico(R.drawable.ic_add) }, modifier = Modifier.padding(horizontal = 12.dp))
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                NavigationDrawerItem(label = { Text(tr("Browser", "المتصفح")) }, selected = false,
                    onClick = { scope.launch { drawer.close() }; BrowserActivity.open(ctx, null) }, icon = { Ico(R.drawable.ic_public) }, modifier = Modifier.padding(horizontal = 12.dp))
                NavigationDrawerItem(label = { Text(tr("Site Grabber", "جامع الملفات من المواقع")) }, selected = false,
                    onClick = { scope.launch { drawer.close() }; Ui.go(Screen.Grabber()) }, icon = { Ico(R.drawable.ic_travel_explore) }, modifier = Modifier.padding(horizontal = 12.dp))
                NavigationDrawerItem(label = { Text(tr("Options", "الخيارات")) }, selected = false,
                    onClick = { scope.launch { drawer.close() }; Ui.go(Screen.Settings) }, icon = { Ico(R.drawable.ic_settings) }, modifier = Modifier.padding(horizontal = 12.dp))
                NavigationDrawerItem(label = { Text(tr("About", "حول البرنامج")) }, selected = false,
                    onClick = { scope.launch { drawer.close() }; Ui.go(Screen.About) }, icon = { Ico(R.drawable.ic_info) }, modifier = Modifier.padding(horizontal = 12.dp))
            }
        }
    }) {
        Scaffold(
            topBar = {
                if (selected.isNotEmpty()) {
                    TopAppBar(
                        title = { Text(tr("${selected.size} selected", "تم تحديد ${selected.size}")) },
                        navigationIcon = { IconBtn(R.drawable.ic_close, tr("Close", "إغلاق")) { selected.clear() } },
                        actions = {
                            IconBtn(R.drawable.ic_play_arrow, tr("Resume", "استئناف")) { selected.forEach { Manager.start(it) }; selected.clear() }
                            IconBtn(R.drawable.ic_pause, tr("Stop", "إيقاف")) { selected.forEach { Manager.pause(it) }; selected.clear() }
                            IconBtn(R.drawable.ic_delete, tr("Delete", "حذف")) { del(selected.toList()); selected.clear() }
                            var more by remember { mutableStateOf(false) }
                            Box {
                                IconBtn(R.drawable.ic_more_vert, tr("More", "المزيد")) { more = true }
                                DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                                    DropdownMenuItem(text = { Text(tr("Select all", "تحديد الكل")) }, onClick = {
                                        more = false; selected.clear(); selected.addAll(rows.map { r -> r.id })
                                    })
                                    DropdownMenuItem(text = { Text(tr("Move to queue", "نقل إلى قائمة الانتظار")) }, onClick = { more = false; queuePick = selected.toList() })
                                    DropdownMenuItem(text = { Text(tr("Delete from queue", "حذف من قائمة الانتظار")) }, onClick = {
                                        more = false; Manager.setQueue(selected.toList(), null); selected.clear()
                                    })
                                    DropdownMenuItem(text = { Text(tr("Copy addresses", "نسخ الروابط")) }, onClick = {
                                        more = false; Sys.copy(ctx, selected.mapNotNull { Manager.find(it)?.url }.joinToString("\n")); selected.clear()
                                    })
                                    DropdownMenuItem(text = { Text(tr("Export…", "تصدير…")) }, onClick = {
                                        more = false; exportKind = 0; exportLauncher.launch("downloads.ef2")
                                    })
                                }
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    )
                } else if (search != null) {
                    TopAppBar(
                        title = {
                            OutlinedTextField(value = search ?: "", onValueChange = { search = it }, singleLine = true,
                                placeholder = { Text(tr("Filter the list…", "تصفية القائمة…")) }, modifier = Modifier.fillMaxWidth())
                        },
                        navigationIcon = { IconBtn(R.drawable.ic_arrow_back, tr("Close", "إغلاق")) { search = null } },
                    )
                } else {
                    TopAppBar(
                        title = { Text(filterTitle(filter), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        navigationIcon = { IconBtn(R.drawable.ic_menu, tr("Menu", "القائمة")) { scope.launch { drawer.open() } } },
                        actions = {
                            IconBtn(R.drawable.ic_search, tr("Find", "بحث")) { search = "" }
                            IconBtn(R.drawable.ic_speed, if (Prefs.limiterOn) tr("Limiter: On", "المحدد: يعمل") else tr("Limiter: Off", "المحدد: متوقف"),
                                tint = if (Prefs.limiterOn) MaterialTheme.colorScheme.primary else null) {
                                Prefs.limiterOn = !Prefs.limiterOn
                                Manager.applyLimits()
                                scope.launch { Ui.snack.showSnackbar(if (Prefs.limiterOn) tr("Limiter: On (${Prefs.limiterKBps} KB/s)", "المحدد: يعمل (${Prefs.limiterKBps} كيلو/ث)") else tr("Limiter: Off", "المحدد: متوقف")) }
                            }
                            Box {
                                IconBtn(R.drawable.ic_more_vert, tr("More", "المزيد")) { menu = true }
                                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                    @Composable
                                    fun mi(t: String, icon: Int, f: () -> Unit) {
                                        DropdownMenuItem(text = { Text(t) }, leadingIcon = { Ico(icon) }, onClick = { menu = false; f() })
                                    }
                                    mi(tr("Resume all", "استئناف الكل"), R.drawable.ic_play_arrow) { Manager.resumeAll() }
                                    mi(tr("Stop all", "إيقاف الكل"), R.drawable.ic_pause) { Manager.pauseAll() }
                                    mi(tr("Delete all completed", "حذف كل المكتملة"), R.drawable.ic_delete_sweep) { Manager.deleteCompleted() }
                                    HorizontalDivider()
                                    val qid = if (filter.startsWith("q:")) filter.removePrefix("q:") else Manager.MAIN_QUEUE
                                    val running = Manager.queues.firstOrNull { it.id == qid }?.running == true
                                    if (running) mi(tr("Stop Queue", "إيقاف القائمة"), R.drawable.ic_stop) { Manager.stopQueue(qid) }
                                    else mi(tr("Start Queue", "بدء القائمة"), R.drawable.ic_queue_play_next) { Manager.startQueue(qid) }
                                    mi(tr("Scheduler…", "الجدولة…"), R.drawable.ic_schedule) { Ui.go(Screen.Queues) }
                                    HorizontalDivider()
                                    mi(tr("Import from .ef2 / text file…", "استيراد من ملف ef2. / نصي…"), R.drawable.ic_file_upload) { importLauncher.launch(arrayOf("*/*")) }
                                    mi(tr("Export to .ef2 file…", "تصدير إلى ملف ef2.…"), R.drawable.ic_save) { exportKind = 0; selected.clear(); exportLauncher.launch("downloads.ef2") }
                                    mi(tr("Export to text file…", "تصدير إلى ملف نصي…"), R.drawable.ic_save) { exportKind = 1; selected.clear(); exportLauncher.launch("downloads.txt") }
                                    mi(tr("Open download folder", "فتح مجلد التحميل"), R.drawable.ic_folder_open) { Sys.openFolder(ctx, Prefs.mainDir) }
                                    HorizontalDivider()
                                    mi(tr("Arrange files…", "ترتيب الملفات…"), R.drawable.ic_sort) { sortDialog = true }
                                    mi(tr("Columns…", "الأعمدة…"), R.drawable.ic_tune) { columnsDialog = true }
                                    mi(tr("When all downloads finish…", "عند انتهاء كل التحميلات…"), R.drawable.ic_timer) { allDoneDialog = true }
                                    mi(tr("Options…", "الخيارات…"), R.drawable.ic_settings) { Ui.go(Screen.Settings) }
                                    mi(tr("Tip of the day…", "نصيحة اليوم…"), R.drawable.ic_lightbulb) { Ui.showTips = true }
                                    mi(tr("Check for updates (video downloader)", "التحقق من التحديثات (محمّل الفيديو)"), R.drawable.ic_refresh) {
                                        scope.launch { Ui.snack.showSnackbar(tr("Checking…", "جارٍ التحقق…")) }
                                        Manager.bg.execute {
                                            val msg = runCatching { VideoEngine.update(ctx) }.getOrElse { e -> tr("Update failed: ", "فشل التحديث: ") + e.message }
                                            Sys.toast(ctx, msg)
                                        }
                                    }
                                    mi(tr("Exit", "خروج"), R.drawable.ic_close) { Manager.exitApp() }
                                }
                            }
                        },
                    )
                }
            },
            floatingActionButton = {
                if (selected.isEmpty()) ExtendedFloatingActionButton(
                    text = { Text(tr("Add", "إضافة")) }, icon = { Ico(R.drawable.ic_add) }, onClick = { addSheet = true },
                )
            },
            snackbarHost = { SnackbarHost(Ui.snack) },
        ) { pad ->
            if (rows.isEmpty()) {
                Column(Modifier.fillMaxSize().padding(pad).padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    Ico(R.drawable.ic_cloud_download, null, Modifier.size(72.dp), MaterialTheme.colorScheme.primary.copy(alpha = 0.5f))
                    Spacer(Modifier.height(12.dp))
                    Text(if (search.isNullOrBlank()) tr("Nothing here yet. Tap \"Add\" or share a link to the app.", "لا يوجد شيء بعد. اضغط \"إضافة\" أو شارك رابطاً مع التطبيق.")
                        else tr("Nothing matches “$search”.", "لا شيء يطابق “$search”."), style = MaterialTheme.typography.bodyLarge)
                }
            } else {
                LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(bottom = 96.dp, top = 4.dp)) {
                    items(rows, key = { it.id }) { r ->
                        ItemRow(r, selected = r.id in selected,
                            onClick = { if (selected.isNotEmpty()) { if (r.id in selected) selected.remove(r.id) else selected.add(r.id) } else openItem(r.id) },
                            onLong = { if (r.id in selected) selected.remove(r.id) else selected.add(r.id) },
                            onDelete = { del(listOf(r.id)) },
                            onQueue = { queuePick = listOf(r.id) },
                        )
                    }
                }
            }
        }
    }

    if (addSheet) AddSheet(onDismiss = { addSheet = false })
    deleteIds?.let { ids -> DeleteDialog(ids) { deleteIds = null } }
    catEdit?.let { c -> CategoryDialog(c) { catEdit = null } }
    if (catNew) CategoryDialog(null) { catNew = false }
    queuePick?.let { ids ->
        AlertDialog(
            onDismissRequest = { queuePick = null },
            title = { Text(tr("Move to queue", "نقل إلى قائمة الانتظار")) },
            text = {
                Column {
                    for (q in Manager.queues) RadioRow(Manager.queueName(q.id), false) { Manager.setQueue(ids, q.id); queuePick = null; selected.clear() }
                    RadioRow(tr("New queue…", "قائمة جديدة…"), false) { queuePick = null; Ui.go(Screen.Queues) }
                }
            },
            confirmButton = { TextButton(onClick = { queuePick = null }) { Text(tr("Cancel", "إلغاء")) } },
        )
    }
    if (columnsDialog) ColumnsDialog { columnsDialog = false }
    if (sortDialog) {
        AlertDialog(
            onDismissRequest = { sortDialog = false },
            title = { Text(tr("Arrange files", "ترتيب الملفات")) },
            text = {
                Column {
                    listOf(tr("By order of addition", "حسب ترتيب الإضافة"), tr("By name", "حسب الاسم"), tr("By size", "حسب الحجم"), tr("Last try date", "تاريخ آخر محاولة"))
                        .forEachIndexed { i, t -> RadioRow(t, Prefs.sortOrder == i) { Prefs.sortOrder = i; sortDialog = false } }
                }
            },
            confirmButton = { TextButton(onClick = { sortDialog = false }) { Text(tr("Close", "إغلاق")) } },
        )
    }
    if (allDoneDialog) {
        AlertDialog(
            onDismissRequest = { allDoneDialog = false },
            title = { Text(tr("When all downloads finish", "عند انتهاء كل التحميلات")) },
            text = {
                Column {
                    RadioRow(tr("Do nothing", "لا شيء"), Prefs.whenAllDone == 0) { Prefs.whenAllDone = 0 }
                    RadioRow(tr("Exit the app", "الخروج من التطبيق"), Prefs.whenAllDone == 1) { Prefs.whenAllDone = 1 }
                }
            },
            confirmButton = { TextButton(onClick = { allDoneDialog = false }) { Text(tr("OK", "موافق")) } },
        )
    }
}

@Composable
fun ItemRow(r: RowData, selected: Boolean, onClick: () -> Unit, onLong: () -> Unit, onDelete: () -> Unit, onQueue: () -> Unit) {
    val ctx = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    val bg = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
    Surface(
        color = bg, shape = RoundedCornerShape(14.dp), tonalElevation = if (selected) 0.dp else 1.dp,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp).clip(RoundedCornerShape(14.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLong),
    ) {
        Row(Modifier.padding(start = 12.dp, end = 4.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(42.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center) {
                Ico(r.icon, null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(r.name, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                if (r.status != St.DONE) {
                    Spacer(Modifier.height(5.dp))
                    if (r.indeterminate) LinearProgressIndicator(Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)))
                    else LinearProgressIndicator(progress = { r.progress }, modifier = Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)),
                        color = if (r.error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                }
                Spacer(Modifier.height(4.dp))
                Text(r.info, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    color = if (r.error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            when (r.status) {
                St.RUNNING, St.WAITING -> IconBtn(R.drawable.ic_pause, tr("Stop", "إيقاف")) { Manager.pause(r.id) }
                St.DONE -> if (r.seeding) IconBtn(R.drawable.ic_stop, tr("Stop sharing", "إيقاف المشاركة")) { Manager.pause(r.id) }
                    else IconBtn(R.drawable.ic_open_in_new, tr("Open", "فتح")) {
                        Manager.find(r.id)?.let { x -> if (x.kind == Kind.TORRENT) Sys.openFolder(ctx, x.dir) else Sys.openFile(ctx, File(x.dir, x.fileName)) }
                    }
                else -> IconBtn(R.drawable.ic_play_arrow, tr("Resume", "استئناف")) { Manager.start(r.id) }
            }
            Box {
                IconBtn(R.drawable.ic_more_vert, tr("More", "المزيد")) { menu = true }
                ItemMenu(r.id, menu, onDismiss = { menu = false }, onDelete = onDelete, onQueue = onQueue)
            }
        }
    }
}

@Composable
fun ItemMenu(id: Long, expanded: Boolean, onDismiss: () -> Unit, onDelete: () -> Unit, onQueue: () -> Unit) {
    val ctx = LocalContext.current
    val it = Manager.find(id) ?: return
    var refresh by remember { mutableStateOf(false) }
    var move by remember { mutableStateOf(false) }
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        val file = File(it.dir, it.fileName)
        @Composable
        fun mi(t: String, icon: Int, f: () -> Unit) {
            DropdownMenuItem(text = { Text(t) }, leadingIcon = { Ico(icon) }, onClick = { onDismiss(); f() })
        }
        if (it.status == St.DONE) {
            if (it.kind != Kind.TORRENT) {
                mi(tr("Open", "فتح"), R.drawable.ic_open_in_new) { Sys.openFile(ctx, file) }
                mi(tr("Open with…", "فتح باستخدام…"), R.drawable.ic_apps) { Sys.openFile(ctx, file, chooser = true) }
                mi(tr("Share", "مشاركة"), R.drawable.ic_share) { if (file.exists()) Sys.share(ctx, file) }
            }
            mi(tr("Open folder", "فتح المجلد"), R.drawable.ic_folder_open) { Sys.openFolder(ctx, it.dir) }
        } else {
            if (it.status == St.RUNNING) mi(tr("Stop download", "إيقاف التحميل"), R.drawable.ic_pause) { Manager.pause(id) }
            else mi(tr("Resume download", "استئناف التحميل"), R.drawable.ic_play_arrow) { Manager.start(id) }
            if (it.kind == Kind.HTTP && it.downloaded > 0) mi(tr("Play while downloading", "تشغيل أثناء التحميل"), R.drawable.ic_play_circle) {
                Sys.openFile(ctx, Manager.partFileFor(it), mime = Sys.mimeOf(file))
            }
        }
        mi(tr("Show progress / Properties", "عرض التقدّم / الخصائص"), R.drawable.ic_info) { Ui.go(Screen.Details(id)) }
        if (it.kind == Kind.VIDEO || com.abdo.barq.core.FileTypes.group(com.abdo.barq.core.Names.ext(it.fileName)) == 2)
            mi(tr("Download a part of it…", "تحميل جزء منه…"), R.drawable.ic_content_cut) { Ui.go(Screen.Video(if (it.kind == Kind.VIDEO) it.url else (it.parentPage ?: it.url), 2, it.referer)) }
        mi(tr("Redownload", "إعادة التحميل"), R.drawable.ic_replay) { Manager.redownload(id) }
        if (it.kind == Kind.HTTP && it.status != St.DONE) mi(tr("Refresh download address…", "تحديث رابط التحميل…"), R.drawable.ic_add_link) { refresh = true }
        mi(tr("Move / Rename…", "نقل / إعادة تسمية…"), R.drawable.ic_drive_file_rename_outline) { move = true }
        mi(tr("Copy address", "نسخ الرابط"), R.drawable.ic_content_copy) { Sys.copy(ctx, if (it.kind == Kind.TORRENT) com.abdo.barq.TorrentEngine.magnetUri(it) else it.url) }
        if (it.status == St.DONE && it.kind != Kind.TORRENT) mi(tr("Check file (SHA-256)", "فحص الملف (SHA-256)"), R.drawable.ic_fingerprint) { Ui.go(Screen.Details(id)) }
        mi(tr("Move to queue", "نقل إلى قائمة الانتظار"), R.drawable.ic_queue) { onQueue() }
        if (it.queueId != null) mi(tr("Delete from queue", "حذف من قائمة الانتظار"), R.drawable.ic_clear) { Manager.setQueue(listOf(id), null) }
        mi(tr("Delete", "حذف"), R.drawable.ic_delete) { onDelete() }
    }
    if (refresh) TextInputDialog(
        tr("Refresh download address", "تحديث رابط التحميل"), tr("New address", "الرابط الجديد"), Sys.clipboardText(ctx)?.takeIf { c -> c.startsWith("http") } ?: it.url,
        hint = tr("When a link expires, paste the new link of the same file. The download continues from where it stopped.",
            "عند انتهاء صلاحية الرابط، الصق الرابط الجديد لنفس الملف. يكمل التحميل من حيث توقف."),
        onDismiss = { refresh = false },
    ) { v -> refresh = false; if (v.isNotBlank()) Manager.refreshAddress(id, v) }
    if (move) MoveRenameDialog(it) { move = false }
}

/** The "Add" sheet: every way to start a download. */
@Composable
fun AddSheet(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state) {
        Column(Modifier.padding(bottom = 24.dp)) {
            @Composable
            fun entry(icon: Int, title: String, sub: String, f: () -> Unit) {
                Row(Modifier.fillMaxWidth().clickable { onDismiss(); f() }.padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                        Ico(icon, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text(title, style = MaterialTheme.typography.titleSmall)
                        Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            entry(R.drawable.ic_add_link, tr("Add URL", "إضافة رابط"), tr("Download a file from an address you type or paste.", "حمّل ملفاً من رابط تكتبه أو تلصقه.")) {
                val clip = Sys.clipboardText(ctx)?.trim()?.takeIf { c -> c.startsWith("http") || c.startsWith("magnet:") } ?: ""
                Ui.addRequest = AddRequest(clip)
            }
            entry(R.drawable.ic_playlist_add, tr("Add Batch", "إضافة مجموعة"), tr("Download many links at once, or numbered files from one address.", "حمّل روابط كثيرة مرة واحدة، أو ملفات مرقّمة من رابط واحد.")) { Ui.batchText = "" }
            entry(R.drawable.ic_content_copy, tr("Add batch download from clipboard", "إضافة مجموعة تحميلات من الحافظة"), tr("Every link copied in the clipboard.", "كل الروابط المنسوخة في الحافظة.")) {
                Ui.batchText = Sys.clipboardText(ctx) ?: ""
            }
            entry(R.drawable.ic_smart_display, tr("Video / audio", "فيديو / صوت"), tr("Save a video or its sound from YouTube and 1000+ sites, with quality choice.", "احفظ فيديو أو صوته من يوتيوب وأكثر من 1000 موقع مع اختيار الجودة.")) {
                Ui.go(Screen.Video(Sys.clipboardText(ctx)?.trim()?.takeIf { c -> c.startsWith("http") } ?: "", 0))
            }
            entry(R.drawable.ic_playlist_play, tr("Playlist", "قائمة تشغيل"), tr("Download all or some videos of a playlist.", "حمّل كل فيديوهات قائمة تشغيل أو بعضها.")) {
                Ui.go(Screen.Video(Sys.clipboardText(ctx)?.trim()?.takeIf { c -> c.startsWith("http") } ?: "", 1))
            }
            entry(R.drawable.ic_content_cut, tr("Download a part of a video…", "تحميل جزء من فيديو…"), tr("Only the minutes you choose.", "الدقائق التي تختارها فقط.")) {
                Ui.go(Screen.Video(Sys.clipboardText(ctx)?.trim()?.takeIf { c -> c.startsWith("http") } ?: "", 2))
            }
            entry(R.drawable.ic_hub, tr("Torrent", "تورنت"), tr("Download a .torrent file or a magnet link.", "حمّل ملف torrent. أو رابط magnet.")) {
                Ui.go(Screen.Torrent(magnet = Sys.clipboardText(ctx)?.trim()?.takeIf { c -> c.startsWith("magnet:") } ?: ""))
            }
            entry(R.drawable.ic_travel_explore, tr("Grabber", "جامع الملفات"), tr("Collect the files of a web site: pictures, videos, archives…", "اجمع ملفات موقع: صور، فيديوهات، ملفات مضغوطة…")) {
                Ui.go(Screen.Grabber(Sys.clipboardText(ctx)?.trim()?.takeIf { c -> c.startsWith("http") } ?: ""))
            }
            entry(R.drawable.ic_public, tr("Browser", "المتصفح"), tr("Browse and catch downloads and videos of web pages.", "تصفّح والتقط التحميلات والفيديوهات من صفحات الويب.")) {
                BrowserActivity.open(ctx, null)
            }
        }
    }
}

@Composable
fun ColumnsDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Columns", "الأعمدة")) },
        text = {
            Column {
                Hint(tr("Show these columns in the download list:", "إظهار هذه الأعمدة في قائمة التحميل:"))
                CheckRow(tr("File Name (always shown)", "اسم الملف (يظهر دائماً)"), true, enabled = false) {}
                CheckRow(tr("Q (queue number)", "ق (رقم قائمة الانتظار)"), Prefs.colQueue) { Prefs.colQueue = it }
                CheckRow(tr("Size", "الحجم"), Prefs.colSize) { Prefs.colSize = it }
                CheckRow(tr("Status", "الحالة"), Prefs.colStatus) { Prefs.colStatus = it }
                CheckRow(tr("Time left", "الوقت المتبقي"), Prefs.colTimeLeft) { Prefs.colTimeLeft = it }
                CheckRow(tr("Transfer rate", "سرعة النقل"), Prefs.colSpeed) { Prefs.colSpeed = it }
                CheckRow(tr("Date Added", "تاريخ الإضافة"), Prefs.colDate) { Prefs.colDate = it }
                CheckRow(tr("Save To", "الحفظ في"), Prefs.colSaveTo) { Prefs.colSaveTo = it }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(tr("OK", "موافق")) } },
    )
}

fun exportTo(ctx: Context, uri: Uri, kind: Int, ids: List<Long>) {
    val list = if (ids.isEmpty()) Manager.items.toList() else ids.mapNotNull { Manager.find(it) }
    val text = if (kind == 0) Ef2.export(list) else list.joinToString("\n") { it.url }
    runCatching {
        ctx.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
        Sys.toast(ctx, tr("${list.size} downloads exported", "تم تصدير ${list.size} تحميل"))
    }.onFailure { e -> Sys.toast(ctx, e.message ?: "error") }
}

fun importFrom(ctx: Context, uri: Uri) {
    runCatching {
        val text = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: return
        val entries = Ef2.parse(text)
        for (e in entries) {
            val kind = if (e.url.startsWith("magnet:")) Kind.TORRENT else Kind.HTTP
            val it = Manager.newItem(kind, e.url, e.fileName)
            it.referer = e.referer; it.userAgent = e.userAgent; it.description = e.description
            if (e.fileName != null) it.nameLocked = true
            Manager.add(it, false, Manager.MAIN_QUEUE)
        }
        Store.saveSoon()
        Sys.toast(ctx, tr("${entries.size} downloads imported into the main queue", "تم استيراد ${entries.size} تحميل إلى القائمة الرئيسية"))
    }.onFailure { e -> Sys.toast(ctx, e.message ?: "error") }
}

@Composable
fun BadgeCount(n: Int) { if (n > 0) Badge { Text(n.toString()) } }
