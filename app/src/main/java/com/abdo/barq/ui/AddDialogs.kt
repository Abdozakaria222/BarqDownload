@file:OptIn(ExperimentalMaterial3Api::class)

package com.abdo.barq.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.abdo.barq.Manager
import com.abdo.barq.Prefs
import com.abdo.barq.R
import com.abdo.barq.Sys
import com.abdo.barq.VideoEngine
import com.abdo.barq.core.Batch
import com.abdo.barq.core.Categories
import com.abdo.barq.core.Category
import com.abdo.barq.core.DownloadItem
import com.abdo.barq.core.Fmt
import com.abdo.barq.core.GDrive
import com.abdo.barq.core.HttpUtil
import com.abdo.barq.core.Kind
import com.abdo.barq.core.Names
import com.abdo.barq.core.Req
import com.abdo.barq.core.St
import com.abdo.barq.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

/** A wide window with a scrolling body and a row of buttons. */
@Composable
fun BigDialog(title: String, onDismiss: () -> Unit, buttons: @Composable RowScope.() -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(shape = MaterialTheme.shapes.extraLarge, tonalElevation = 6.dp, modifier = Modifier.fillMaxWidth(0.94f).heightIn(max = 720.dp)) {
            Column(Modifier.padding(20.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Spacer(Modifier.size(8.dp))
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) { content() }
                Spacer(Modifier.size(12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                    buttons()
                }
            }
        }
    }
}

fun categoryOptions(): List<Pair<String, String>> =
    (listOf(Categories.GENERAL) + Manager.categories.map { it.id }).distinct().map { it to Manager.categoryName(it) }

fun queueOptions(withNone: Boolean = false): List<Pair<String?, String>> =
    (if (withNone) listOf<Pair<String?, String>>(null to tr("None", "بدون")) else emptyList()) + Manager.queues.map { it.id to Manager.queueName(it.id) }

/** "Download File Info": the window shown for every new link. */
@Composable
fun AddUrlDialog(req: AddRequest, onNavigate: (Screen) -> Unit = { Ui.go(it) }, onClose: () -> Unit) {
    val ctx = LocalContext.current
    var url by remember { mutableStateOf(req.url) }
    var name by remember { mutableStateOf(req.name?.let { Names.sanitize(it) } ?: Names.fromUrl(GDrive.direct(req.url))) }
    var nameTouched by remember { mutableStateOf(req.name != null) }
    var cat by remember { mutableStateOf(Categories.detect(Manager.categories, name, req.url, req.referer)) }
    var dir by remember { mutableStateOf(Manager.dirFor(cat)) }
    var dirTouched by remember { mutableStateOf(false) }
    var rememberPath by remember { mutableStateOf(false) }
    var desc by remember { mutableStateOf(req.description ?: "") }
    var sum by remember { mutableStateOf("") }
    var useAuth by remember { mutableStateOf(false) }
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var conns by remember { mutableIntStateOf(Prefs.connectionsFor(req.url, Prefs.defaultConnections)) }
    var queue by remember { mutableStateOf(Manager.MAIN_QUEUE) }
    var checking by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf<String?>(if (req.size > 0) Fmt.size(req.size) else null) }
    var probeError by remember { mutableStateOf<String?>(null) }
    var dupItem by remember { mutableStateOf<DownloadItem?>(null) }
    var fileExists by remember { mutableStateOf(false) }
    var pendingStart by remember { mutableStateOf(true) }

    LaunchedEffect(url) {
        val u = url.trim()
        if (!u.startsWith("http")) return@LaunchedEffect
        delay(350)
        checking = true; probeError = null
        val r = withContext(Dispatchers.IO) {
            runCatching { HttpUtil.probe(Req(GDrive.direct(u), req.referer, req.cookies, req.userAgent), Prefs.netConfig()) }
        }
        checking = false
        r.onSuccess { p ->
            if (!nameTouched) {
                val n = Names.withExtFromMime(p.fileName?.let { Names.sanitize(it) } ?: Names.fromUrl(p.finalUrl), p.contentType)
                name = n
                val c = Categories.detect(Manager.categories, n, u, req.referer)
                if (c != cat) { cat = c; if (!dirTouched) dir = Manager.dirFor(c) }
            }
            info = (if (p.size > 0) Fmt.size(p.size) else tr("Unknown size", "حجم غير معروف")) + "  •  " +
                (if (p.resumable) tr("Can be resumed", "يمكن استئنافه") else tr("Cannot be resumed", "لا يمكن استئنافه"))
        }.onFailure { e -> probeError = e.message ?: e.javaClass.simpleName }
    }

    fun create(numbered: Boolean, replace: Boolean): DownloadItem {
        val it = Manager.newItem(Kind.HTTP, url.trim(), name)
        it.category = cat
        it.dir = dir.trim().ifBlank { Manager.dirFor(cat) }
        if (numbered) it.fileName = Names.unique(File(it.dir), it.fileName) { n -> Manager.items.any { x -> x.dir == it.dir && x.fileName == n } }
        it.overwrite = replace
        it.nameLocked = nameTouched || numbered
        it.dirLocked = dirTouched
        it.referer = req.referer; it.cookies = req.cookies; it.userAgent = req.userAgent; it.parentPage = req.page
        it.description = desc.ifBlank { null }
        it.checksum = sum.trim().ifBlank { null }
        if (useAuth && user.isNotBlank()) { it.authUser = user; it.authPass = pass }
        it.connections = conns
        if (rememberPath) Manager.category(cat)?.let { c -> c.dir = it.dir; Manager.changed() }
        return it
    }

    fun commit(start: Boolean, numbered: Boolean = false, replace: Boolean = false) {
        val u = url.trim()
        if (u.startsWith("magnet:", true)) { onClose(); onNavigate(Screen.Torrent(magnet = u)); return }
        val it = create(numbered, replace)
        Manager.add(it, start, if (start) null else queue)
        if (start && Prefs.showProgress == 0) onNavigate(Screen.Details(it.id))
        onClose()
    }

    fun tryCommit(start: Boolean) {
        pendingStart = start
        val dup = Manager.findDuplicate(url)
        if (dup != null) {
            when (Prefs.duplicatePolicy) {
                0 -> { dupItem = dup; return }
                2 -> { Manager.delete(listOf(dup.id), false); commit(start, replace = true); return }
                3 -> { onClose(); if (dup.status == St.DONE) onNavigate(Screen.Details(dup.id)) else Manager.start(dup.id); return }
                else -> { commit(start, numbered = true); return }
            }
        }
        if (File(dir.trim(), name.trim()).exists()) { fileExists = true; return }
        commit(start)
    }

    BigDialog(
        title = tr("Download File Info", "معلومات ملف التحميل"),
        onDismiss = onClose,
        buttons = {
            TextButton(onClick = onClose) { Text(tr("Cancel", "إلغاء")) }
            FilledTonalButton(onClick = { tryCommit(false) }) { Text(tr("Download Later", "تحميل لاحقاً")) }
            Button(onClick = { tryCommit(true) }) { Text(tr("Start Download", "بدء التحميل")) }
        },
    ) {
        Field(tr("URL", "الرابط"), url, { url = it })
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
            if (checking) {
                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text(tr("Checking the address…", "جارٍ فحص الرابط…"), style = MaterialTheme.typography.bodySmall)
            } else if (probeError != null) {
                Text(probeError ?: "", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            } else if (info != null) {
                Text(info ?: "", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
        }
        if (VideoEngine.looksLikeVideoSite(url)) {
            TextButton(onClick = { onClose(); onNavigate(Screen.Video(url.trim(), 0, req.referer)) }) {
                Ico(R.drawable.ic_smart_display); Spacer(Modifier.width(6.dp)); Text(tr("This is a video page: get the video", "هذه صفحة فيديو: جلب الفيديو"))
            }
        }
        Dropdown(tr("Category", "التصنيف"), categoryOptions(), cat) { c -> cat = c; if (!dirTouched) dir = Manager.dirFor(c) }
        Field(tr("File name", "اسم الملف"), name, { name = it; nameTouched = true })
        FolderField(tr("Save As", "حفظ في"), dir, { dir = it; dirTouched = true })
        CheckRow(tr("Remember this path for \"${Manager.categoryName(cat)}\" category", "تذكّر هذا المسار لتصنيف \"${Manager.categoryName(cat)}\""), rememberPath) { rememberPath = it }
        Field(tr("Description", "الوصف"), desc, { desc = it }, placeholder = tr("optional note", "ملاحظة اختيارية"))
        Field(tr("Checksum", "رمز التحقق"), sum, { sum = it }, placeholder = tr("MD5 / SHA-1 / SHA-256 (optional)", "MD5 / SHA-1 / SHA-256 (اختياري)"))
        SliderRow(tr("Connections", "الاتصالات"), conns, 1..32) { conns = it }
        Dropdown(tr("Queue", "قائمة الانتظار"), queueOptions(), queue) { q -> if (q != null) queue = q }
        CheckRow(tr("Use authorization", "استخدام تسجيل الدخول"), useAuth) { useAuth = it }
        if (useAuth) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Field(tr("Login", "اسم المستخدم"), user, { user = it }, Modifier.weight(1f))
                Field(tr("Password", "كلمة المرور"), pass, { pass = it }, Modifier.weight(1f))
            }
        }
        if (req.referer != null) Hint(tr("Referer: ", "المُحيل: ") + req.referer)
    }

    dupItem?.let { d ->
        DuplicateLinkDialog(d, onDismiss = { dupItem = null }) { choice ->
            dupItem = null
            when (choice) {
                1 -> commit(pendingStart, numbered = true)
                2 -> { Manager.delete(listOf(d.id), false); commit(pendingStart, replace = true) }
                3 -> { onClose(); if (d.status == St.DONE) onNavigate(Screen.Details(d.id)) else Manager.start(d.id) }
            }
        }
    }
    if (fileExists) {
        AlertDialog(
            onDismissRequest = { fileExists = false },
            title = { Text(tr("The file is already there", "الملف موجود بالفعل")) },
            text = { Text(tr("A file named \"$name\" is already in this folder. Replace it, save the new one with a numbered name, or change the name above.",
                "يوجد ملف باسم \"$name\" في هذا المجلد بالفعل. استبدله، أو احفظ الجديد باسم برقم، أو غيّر الاسم بالأعلى.")) },
            confirmButton = { TextButton(onClick = { fileExists = false; commit(pendingStart, numbered = true) }) { Text(tr("New name (numbered)", "اسم جديد (برقم)")) } },
            dismissButton = { TextButton(onClick = { fileExists = false; commit(pendingStart, replace = true) }) { Text(tr("Replace it", "استبداله")) } },
        )
    }
}

@Composable
fun DuplicateLinkDialog(old: DownloadItem, onDismiss: () -> Unit, onChoice: (Int) -> Unit) {
    var keep by remember { mutableStateOf(false) }
    var choice by remember { mutableIntStateOf(1) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Duplicate download link", "رابط تحميل مكرر")) },
        text = {
            Column {
                Text(tr("This address is already in the download list:", "هذا الرابط موجود بالفعل في قائمة التحميل:"))
                Text(old.fileName, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 6.dp))
                RadioRow(tr("Add it with a new name (numbered)", "إضافته باسم جديد (برقم)"), choice == 1) { choice = 1 }
                RadioRow(tr("Add it and replace the old download (overwrite the file)", "إضافته واستبدال التحميل القديم (الكتابة فوق الملف)"), choice == 2) { choice = 2 }
                RadioRow(tr("If it is finished show it, if not resume it", "إن كان مكتملاً اعرضه، وإن لم يكن استأنفه"), choice == 3) { choice = 3 }
                CheckRow(tr("Remember my choice (Options > Downloads)", "تذكّر اختياري (الخيارات > التحميلات)"), keep) { keep = it }
            }
        },
        confirmButton = { TextButton(onClick = { if (keep) Prefs.duplicatePolicy = choice; onChoice(choice) }) { Text(tr("OK", "موافق")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Cancel", "إلغاء")) } },
    )
}

/** "Add batch download": many links, or numbered files from one address. */
@Composable
fun BatchDialog(initial: String, onClose: () -> Unit) {
    val ctx = LocalContext.current
    var tab by remember { mutableIntStateOf(0) }
    var text by remember { mutableStateOf(initial) }
    var pattern by remember { mutableStateOf("") }
    var from by remember { mutableStateOf("1") }
    var to by remember { mutableStateOf("10") }
    var width by remember { mutableIntStateOf(1) }
    var letters by remember { mutableStateOf(false) }
    var autoDir by remember { mutableStateOf(true) }
    var dir by remember { mutableStateOf(Prefs.mainDir) }
    var queue by remember { mutableStateOf(Manager.MAIN_QUEUE) }
    var startNow by remember { mutableStateOf(true) }

    val links: List<String> = if (tab == 0) Batch.linksFromText(text) else Batch.expand(pattern.trim(), from, to, width, letters)

    BigDialog(
        title = tr("Add batch download", "إضافة مجموعة تحميلات"),
        onDismiss = onClose,
        buttons = {
            TextButton(onClick = onClose) { Text(tr("Cancel", "إلغاء")) }
            Button(enabled = links.isNotEmpty(), onClick = {
                val added = ArrayList<Long>()
                for (l in links) {
                    if (l.startsWith("magnet:")) {
                        val it = Manager.newItem(Kind.TORRENT, l, com.abdo.barq.core.Magnet.displayName(l) ?: "magnet")
                        Manager.add(it, false, queue); added.add(it.id); continue
                    }
                    val it = Manager.newItem(Kind.HTTP, l)
                    if (!autoDir && dir.isNotBlank()) { it.dir = dir; it.dirLocked = true }
                    Manager.add(it, false, queue)
                    added.add(it.id)
                }
                if (startNow) Manager.startQueue(queue)
                Sys.toast(ctx, tr("${added.size} links added", "تمت إضافة ${added.size} رابط"))
                onClose()
            }) { Text(tr("Add", "إضافة") + " (${links.size})") }
        },
    ) {
        TabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text(tr("List of links", "قائمة روابط")) })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text(tr("Address with a pattern (*)", "رابط بنمط (*)")) })
        }
        Spacer(Modifier.size(8.dp))
        if (tab == 0) {
            Hint(tr("Paste the links, one per line. They download one after another in the queue you choose.",
                "الصق الروابط، رابط في كل سطر. ستُحمَّل واحداً تلو الآخر في القائمة التي تختارها."))
            Field(tr("Links", "الروابط"), text, { text = it }, singleLine = false, minLines = 5)
            TextButton(onClick = { Sys.clipboardText(ctx)?.let { c -> text = if (text.isBlank()) c else text + "\n" + c } }) {
                Ico(R.drawable.ic_content_copy); Spacer(Modifier.width(6.dp)); Text(tr("Paste from clipboard", "لصق من الحافظة"))
            }
        } else {
            Hint(tr("Type an address with * where the number (or letter) goes, for example:", "اكتب رابطاً فيه * مكان الرقم (أو الحرف)، مثلاً:") +
                "\nhttps://www.site.com/pictures/img*.jpg")
            Field(tr("Address", "الرابط"), pattern, { pattern = it }, placeholder = "https://www.site.com/pictures/img*.jpg")
            CheckRow(tr("Letters (a-z) instead of numbers", "حروف (a-z) بدلاً من الأرقام"), letters) { letters = it; if (it) { from = "a"; to = "z" } else { from = "1"; to = "10" } }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Field(tr("From", "من"), from, { from = it }, Modifier.weight(1f))
                Field(tr("To", "إلى"), to, { to = it }, Modifier.weight(1f))
            }
            if (!letters) {
                SliderRow(tr("Wildcard size", "عدد الخانات"), width, 1..6) { width = it }
                Hint(tr("Wildcard size 3 makes 001, 002 … 010.", "عدد الخانات 3 يعطي 001، 002 … 010."))
            }
            if (links.isNotEmpty()) Hint(links.first() + "\n…\n" + links.last())
        }
        SectionTitle(tr("Save all to", "حفظ الكل في"))
        RadioRow(tr("automatic (by file type)", "تلقائي (حسب نوع الملف)"), autoDir) { autoDir = true }
        RadioRow(tr("this folder:", "هذا المجلد:"), !autoDir) { autoDir = false }
        if (!autoDir) FolderField(tr("Folder", "المجلد"), dir, { dir = it })
        Dropdown(tr("Queue", "قائمة الانتظار"), queueOptions(), queue) { q -> if (q != null) queue = q }
        CheckRow(tr("Start the queue now", "بدء القائمة الآن"), startNow) { startNow = it }
    }
}

@Composable
fun TextInputDialog(title: String, label: String, initial: String, hint: String? = null, ok: String = tr("OK", "موافق"), onDismiss: () -> Unit, onOk: (String) -> Unit) {
    var v by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Column { if (hint != null) Hint(hint); Field(label, v, { v = it }) } },
        confirmButton = { TextButton(onClick = { onOk(v) }) { Text(ok) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Cancel", "إلغاء")) } },
    )
}

@Composable
fun MoveRenameDialog(it: DownloadItem, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var name by remember { mutableStateOf(it.fileName) }
    var dir by remember { mutableStateOf(it.dir) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Move / Rename", "نقل / إعادة تسمية")) },
        text = { Column { Field(tr("File name", "اسم الملف"), name, { name = it }); FolderField(tr("Folder", "المجلد"), dir, { dir = it }) } },
        confirmButton = {
            TextButton(onClick = {
                val err = Manager.moveRename(it.id, name, dir.trim())
                if (err != null) Sys.toast(ctx, err) else onDismiss()
            }) { Text(tr("OK", "موافق")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Cancel", "إلغاء")) } },
    )
}

@Composable
fun DeleteDialog(ids: List<Long>, onDismiss: () -> Unit) {
    var files by remember { mutableStateOf(false) }
    var dontAsk by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Remove ${ids.size} download(s) from the list?", "حذف ${ids.size} تحميل من القائمة؟")) },
        text = {
            Column {
                Text(tr("The files stay on the phone unless you choose below.", "تبقى الملفات على الهاتف إلا إذا اخترت ذلك بالأسفل."))
                CheckRow(tr("Delete the downloaded files from the disk too (be careful)", "حذف الملفات المحمَّلة من القرص أيضًا (انتبه)"), files) { files = it }
                CheckRow(tr("Don't show this question again", "عدم إظهار هذا السؤال مرة أخرى"), dontAsk) { dontAsk = it }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (dontAsk) Prefs.askBeforeRemove = false
                Manager.delete(ids, files)
                onDismiss()
            }) { Text(tr("Delete", "حذف"), color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Cancel", "إلغاء")) } },
    )
}

/** Add / edit a category (name, file types, sites, folder). */
@Composable
fun CategoryDialog(cat: Category?, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(cat?.let { Manager.categoryName(it.id) } ?: "") }
    var exts by remember { mutableStateOf(cat?.exts ?: "") }
    var sites by remember { mutableStateOf(cat?.sites ?: "") }
    var dir by remember { mutableStateOf(cat?.dir ?: "") }
    BigDialog(
        title = if (cat == null) tr("Add category", "إضافة تصنيف") else tr("Category properties", "خصائص التصنيف"),
        onDismiss = onDismiss,
        buttons = {
            if (cat != null && !cat.builtIn) TextButton(onClick = {
                Manager.categories.remove(cat)
                Manager.items.filter { x -> x.category == cat.id }.forEach { x -> x.category = Categories.GENERAL }
                Manager.changed(); onDismiss()
            }) { Text(tr("Delete category", "حذف التصنيف"), color = MaterialTheme.colorScheme.error) }
            TextButton(onClick = onDismiss) { Text(tr("Cancel", "إلغاء")) }
            Button(enabled = name.isNotBlank(), onClick = {
                val c = cat ?: Category("c" + System.currentTimeMillis(), name.trim()).also { Manager.categories.add(it) }
                if (!c.builtIn || name.trim() != Manager.categoryName(c.id)) c.name = name.trim()
                c.exts = exts.trim(); c.sites = sites.trim(); c.dir = dir.trim()
                Manager.changed(); onDismiss()
            }) { Text(tr("OK", "موافق")) }
        },
    ) {
        Field(tr("Category name", "اسم التصنيف"), name, { name = it }, placeholder = tr("e.g. Courses", "مثلاً: كورسات"))
        Field(tr("File types", "أنواع الملفات"), exts, { exts = it }, placeholder = tr("mp4 mkv avi (separated by spaces)", "mp4 mkv avi (بينها مسافات)"))
        Field(tr("Sites", "المواقع"), sites, { sites = it }, placeholder = tr("udemy.com *.coursera.org (optional)", "udemy.com *.coursera.org (اختياري)"))
        FolderField(tr("Save files to", "حفظ الملفات في"), dir, { dir = it })
        Hint(tr("Empty = automatic (Downloads/Barq/<category>).", "فارغ = تلقائي (Downloads/Barq/<التصنيف>)."))
        Hint(tr("Files with these extensions, or from these sites, are put into this category and saved to its folder.",
            "الملفات بهذه الامتدادات، أو من هذه المواقع، توضع في هذا التصنيف وتُحفظ في مجلده."))
    }
}
