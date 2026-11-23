@file:OptIn(ExperimentalMaterial3Api::class)

package com.abdo.barq.ui

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.abdo.barq.Manager
import com.abdo.barq.R
import com.abdo.barq.Scheduler
import com.abdo.barq.core.QueueInfo
import com.abdo.barq.core.St
import com.abdo.barq.tr

/** The Scheduler: queues, their files and the times they start and stop. */
@Composable
fun QueuesScreen() {
    val ctx = LocalContext.current
    val tick by Manager.tick.collectAsState()
    var sel by remember { mutableStateOf(Manager.MAIN_QUEUE) }
    var newQueue by remember { mutableStateOf(false) }
    val q = Manager.queues.firstOrNull { it.id == sel } ?: Manager.queues.first()

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(tr("Scheduler", "الجدولة")) },
            navigationIcon = { IconBtn(R.drawable.ic_arrow_back, tr("Back", "رجوع")) { Ui.back() } },
            actions = { IconBtn(R.drawable.ic_add, tr("New queue…", "قائمة جديدة…")) { newQueue = true } },
        )
    }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(horizontal = 16.dp).verticalScroll(rememberScrollState())) {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (x in Manager.queues) FilterChip(selected = x.id == q.id, onClick = { sel = x.id },
                    label = { Text(Manager.queueName(x.id) + if (x.running) " ▶" else "") })
            }
            QueueEditor(ctx, q, tick)
        }
    }
    if (newQueue) TextInputDialog(tr("New queue", "قائمة جديدة"), tr("Queue name", "اسم القائمة"), "", onDismiss = { newQueue = false }) { name ->
        newQueue = false
        if (name.isNotBlank()) {
            val nq = QueueInfo("q" + System.currentTimeMillis(), name.trim())
            Manager.queues.add(nq); Manager.changed(); sel = nq.id
        }
    }
}

@Composable
private fun QueueEditor(ctx: Context, q: QueueInfo, tick: Long) {
    // local copies, saved with "Apply"
    var name by remember(q.id) { mutableStateOf(Manager.queueName(q.id)) }
    var startOn by remember(q.id) { mutableStateOf(q.startEnabled) }
    var startTime by remember(q.id) { mutableStateOf(q.startTime) }
    var daily by remember(q.id) { mutableStateOf(q.daily) }
    var days by remember(q.id) { mutableIntStateOf(q.days) }
    var once by remember(q.id) { mutableStateOf(q.onceDate) }
    var appStart by remember(q.id) { mutableStateOf(q.startOnAppStart) }
    var stopOn by remember(q.id) { mutableStateOf(q.stopEnabled) }
    var stopTime by remember(q.id) { mutableStateOf(q.stopTime) }
    var whenDone by remember(q.id) { mutableIntStateOf(q.whenDone) }
    var retries by remember(q.id) { mutableIntStateOf(q.retries) }
    var parallel by remember(q.id) { mutableIntStateOf(q.maxParallel) }
    var dir by remember(q.id) { mutableStateOf(q.saveDir) }
    var openDone by remember(q.id) { mutableStateOf(q.openWhenDone) }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (q.running) Button(onClick = { Manager.stopQueue(q.id) }) { Text(tr("Stop", "إيقاف")) }
        else Button(onClick = { Manager.startQueue(q.id) }) { Text(tr("Start now", "البدء الآن")) }
        if (q.id != Manager.MAIN_QUEUE) OutlinedButton(onClick = {
            Manager.stopQueue(q.id)
            Manager.items.filter { it.queueId == q.id }.forEach { it.queueId = null; if (it.status == St.QUEUED) it.status = St.PAUSED }
            Scheduler.cancel(ctx, q)
            Manager.queues.remove(q); Manager.changed()
        }) { Text(tr("Delete queue", "حذف القائمة"), color = MaterialTheme.colorScheme.error) }
    }
    SectionTitle(tr("Schedule", "الجدولة"))
    if (q.id != Manager.MAIN_QUEUE) Field(tr("Queue name", "اسم القائمة"), name, { name = it })
    SwitchRow(tr("Start the queue automatically", "بدء القائمة تلقائياً"), startOn) { startOn = it }
    if (startOn) {
        Field(tr("Start time (HH:MM)", "وقت البدء (HH:MM)"), startTime, { startTime = it })
        RadioRow(tr("Daily", "يومياً"), daily) { daily = true }
        if (daily) {
            val names = listOf(tr("Mon", "إث"), tr("Tue", "ث"), tr("Wed", "أر"), tr("Thu", "خ"), tr("Fri", "ج"), tr("Sat", "س"), tr("Sun", "أح"))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (i in 0..6) FilterChip(selected = (days and (1 shl i)) != 0, onClick = { days = days xor (1 shl i) }, label = { Text(names[i]) })
            }
        }
        RadioRow(tr("Once at (YYYY-MM-DD)", "مرة واحدة في (YYYY-MM-DD)"), !daily) { daily = false }
        if (!daily) Field(tr("Date", "التاريخ"), once, { once = it }, placeholder = "2026-12-31")
    }
    SwitchRow(tr("Start the queue when the app starts", "بدء القائمة عند فتح التطبيق"), appStart) { appStart = it }
    SwitchRow(tr("Stop the queue at", "إيقاف القائمة في"), stopOn) { stopOn = it }
    if (stopOn) Field(tr("Stop time (HH:MM)", "وقت الإيقاف (HH:MM)"), stopTime, { stopTime = it })
    Text(tr("When the queue is done", "عند انتهاء القائمة"), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
    RadioRow(tr("Do nothing", "لا شيء"), whenDone == 0) { whenDone = 0 }
    RadioRow(tr("Exit the app", "الخروج من التطبيق"), whenDone == 1) { whenDone = 1 }
    if (Build.VERSION.SDK_INT >= 31) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        if (!am.canScheduleExactAlarms()) {
            Hint(tr("For exact start times allow \"Alarms & reminders\" for the app.", "لمواعيد بدء دقيقة اسمح للتطبيق بـ \"المنبهات والتذكيرات\"."))
            TextButton(onClick = { runCatching { ctx.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }) {
                Text(tr("Allow", "السماح"))
            }
        }
    }
    SectionTitle(tr("Files in the queue", "الملفات في القائمة"))
    SliderRow(tr("Files at the same time", "ملفات في نفس الوقت"), parallel, 1..10) { parallel = it }
    SliderRow(tr("Number of retries for each file if downloading failed", "عدد المحاولات لكل ملف إذا فشل التحميل"), retries, 0..10) { retries = it }
    FolderField(tr("Save all files of this queue to", "حفظ كل ملفات هذه القائمة في"), dir, { dir = it })
    Hint(tr("Empty = each file's own folder.", "فارغ = مجلد كل ملف."))
    CheckRow(tr("Open each file when it has finished downloading", "فتح كل ملف عند انتهاء تحميله"), openDone) { openDone = it }
    Button(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), onClick = {
        if (q.id != Manager.MAIN_QUEUE && name.isNotBlank()) q.name = name.trim()
        q.startEnabled = startOn; q.startTime = startTime.trim(); q.daily = daily; q.days = days; q.onceDate = once.trim()
        q.startOnAppStart = appStart; q.stopEnabled = stopOn; q.stopTime = stopTime.trim(); q.whenDone = whenDone
        q.retries = retries; q.maxParallel = parallel; q.saveDir = dir.trim(); q.openWhenDone = openDone
        Scheduler.schedule(ctx, q)
        Manager.changed()
        val next = if (startOn) Scheduler.nextTime(q, q.startTime, true) else null
        com.abdo.barq.Sys.toast(ctx, if (next != null) tr("Saved. Next start: ", "تم الحفظ. البدء القادم: ") +
            java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date(next)) else tr("Saved", "تم الحفظ"))
        Manager.pump()
    }) { Text(tr("Apply", "تطبيق")) }

    Hint(tr("Files download in this order. Add files with ⋮ > Move to queue, Add Batch, or \"Download Later\".",
        "تُحمَّل الملفات بهذا الترتيب. أضف ملفات عبر ⋮ > نقل إلى قائمة الانتظار، أو إضافة مجموعة، أو \"تحميل لاحقاً\"."))
    val files = Manager.items.filter { it.queueId == q.id }.sortedBy { it.queuePos }
    if (files.isEmpty()) Text(tr("No files in this queue.", "لا توجد ملفات في هذه القائمة."), modifier = Modifier.padding(vertical = 8.dp))
    for (f in files) {
        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Ico(iconFor(f), null)
            Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                Text(f.fileName, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                Text(statusText(f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconBtn(R.drawable.ic_arrow_upward, tr("Move up", "تحريك لأعلى")) { Manager.moveInQueue(f.id, true) }
            IconBtn(R.drawable.ic_arrow_downward, tr("Move down", "تحريك لأسفل")) { Manager.moveInQueue(f.id, false) }
            IconBtn(R.drawable.ic_clear, tr("Delete from queue", "حذف من قائمة الانتظار")) { Manager.setQueue(listOf(f.id), null) }
        }
    }
}
