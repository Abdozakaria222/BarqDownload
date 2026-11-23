@file:OptIn(ExperimentalMaterial3Api::class)

package com.abdo.barq.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.abdo.barq.BuildConfig
import com.abdo.barq.Manager
import com.abdo.barq.Prefs
import com.abdo.barq.R
import com.abdo.barq.Sys
import com.abdo.barq.VideoEngine
import com.abdo.barq.appName
import com.abdo.barq.core.DownloadItem
import com.abdo.barq.core.Fmt
import com.abdo.barq.core.Kind
import com.abdo.barq.tr
import kotlinx.coroutines.delay
import java.io.File

@Composable
fun AboutScreen() {
    val ctx = LocalContext.current
    Scaffold(topBar = {
        TopAppBar(title = { Text(tr("About", "حول البرنامج")) }, navigationIcon = { IconBtn(R.drawable.ic_arrow_back, tr("Back", "رجوع")) { Ui.back() } })
    }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(20.dp).verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(84.dp).clip(RoundedCornerShape(24.dp)).background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
                Ico(R.drawable.ic_bolt, null, Modifier.size(52.dp), MaterialTheme.colorScheme.onPrimary)
            }
            Spacer(Modifier.height(12.dp))
            Text(appName(), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(tr("Version ", "الإصدار ") + BuildConfig.VERSION_NAME)
            Spacer(Modifier.height(16.dp))
            Text(tr("A download manager for Android: many connections at once, pause and resume, queues and a scheduler, a speed limiter, torrents, videos and playlists, a site grabber and a built-in browser that catches downloads.",
                "مدير تحميل لأندرويد: اتصالات متعددة في نفس الوقت، إيقاف واستئناف، قوائم انتظار وجدولة، محدد سرعة، تورنت، فيديوهات وقوائم تشغيل، جامع ملفات من المواقع، ومتصفح مدمج يلتقط التحميلات."),
                style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(12.dp))
            Text(tr("Its features follow the Windows program \"Ezoo Download\". This Android app is a separate program and is not made by Ezoo Tech.",
                "مميزاته مأخوذة من برنامج الويندوز \"Ezoo Download\". تطبيق الأندرويد هذا برنامج منفصل وليس من إنتاج Ezoo Tech."),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SectionTitle(tr("Open-source parts", "مكونات مفتوحة المصدر"))
            Text("yt-dlp (Unlicense) • FFmpeg (LGPL/GPL) • QuickJS (MIT) • youtubedl-android (GPL-3.0) • libtorrent / libtorrent4j (BSD / MIT) • Jetpack Compose (Apache-2.0) • Material icons (Apache-2.0)",
                style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(10.dp))
            Text(tr("Video downloader: ", "محمّل الفيديو: ") + if (VideoEngine.ready) VideoEngine.version(ctx) else tr("preparing…", "جارٍ التجهيز…"),
                style = MaterialTheme.typography.bodySmall)
            Text(tr("Main folder: ", "المجلد الرئيسي: ") + Prefs.mainDir, style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun tips(): List<String> = listOf(
    tr("Share any link from your browser or YouTube to the app: it opens the right window by itself.", "شارك أي رابط من متصفحك أو من يوتيوب مع التطبيق: يفتح النافذة المناسبة تلقائياً."),
    tr("Copy a link, then open the app: it offers to download it.", "انسخ رابطاً ثم افتح التطبيق: سيعرض تحميله."),
    tr("A broken link? ⋮ > Refresh download address, paste the new one: the download goes on from where it stopped.", "رابط انتهت صلاحيته؟ ⋮ > تحديث رابط التحميل والصق الجديد: يكمل التحميل من حيث توقف."),
    tr("The bolt-shaped speed icon at the top turns the speed limiter on and off.", "أيقونة السرعة في الأعلى تشغّل محدد السرعة وتوقفه."),
    tr("Put downloads in a queue and set a start time in the Scheduler, for example at night.", "ضع التحميلات في قائمة انتظار وحدد وقت البدء في الجدولة، مثلاً ليلاً."),
    tr("Add Batch downloads numbered files like img001.jpg … img100.jpg from one address with *.", "إضافة مجموعة تحمّل ملفات مرقمة مثل img001.jpg … img100.jpg من رابط واحد بعلامة *."),
    tr("The built-in browser shows a \"Download this video\" button when a page plays a video.", "المتصفح المدمج يُظهر زر \"تحميل هذا الفيديو\" عندما تشغّل الصفحة فيديو."),
    tr("Long-press a download to select several, then resume, stop or delete them together.", "اضغط مطولاً على تحميل لتحديد عدة تحميلات ثم استأنفها أو أوقفها أو احذفها معاً."),
    tr("Site Grabber collects all pictures, videos or archives of a site in one go.", "جامع الملفات يجمع كل صور أو فيديوهات أو ملفات موقع دفعة واحدة."),
    tr("Downloads continue with the screen off. If your phone stops them, Options > General > Battery: no limits.", "التحميل يستمر والشاشة مغلقة. إن أوقفه هاتفك: الخيارات > عام > البطارية: بلا قيود."),
)

@Composable
fun TipsDialog(onDismiss: () -> Unit) {
    var i by remember { mutableIntStateOf(Prefs.tipIndex % tips().size) }
    AlertDialog(
        onDismissRequest = { Prefs.tipIndex = i + 1; onDismiss() },
        icon = { Ico(R.drawable.ic_tips_and_updates) },
        title = { Text(tr("Did you know…", "هل تعلم…")) },
        text = {
            Column {
                Text(tips()[i])
                CheckRow(tr("Show tips at startup", "إظهار النصائح عند البدء"), Prefs.showTips) { Prefs.showTips = it }
            }
        },
        confirmButton = { TextButton(onClick = { Prefs.tipIndex = i + 1; onDismiss() }) { Text(tr("Close", "إغلاق")) } },
        dismissButton = { TextButton(onClick = { i = (i + 1) % tips().size }) { Text(tr("Next tip", "النصيحة التالية")) } },
    )
}

@Composable
fun CompleteDialog(it: DownloadItem, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val f = File(it.dir, it.fileName)
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Ico(R.drawable.ic_download_done) },
        title = { Text(tr("Download complete", "اكتمل التحميل")) },
        text = {
            Column {
                KeyValue(tr("File size", "حجم الملف"), Fmt.size(it.size))
                KeyValue(tr("Saved as", "تم الحفظ باسم"), f.absolutePath)
                CheckRow(tr("Don't show this again", "لا تظهر هذا مرة أخرى"), !Prefs.showCompleteDialog) { v -> Prefs.showCompleteDialog = !v }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { onDismiss(); Sys.openFolder(ctx, it.dir) }) { Text(tr("Open folder", "فتح المجلد")) }
                    if (it.kind != Kind.TORRENT) TextButton(onClick = { onDismiss(); Sys.openFile(ctx, f, chooser = true) }) { Text(tr("Open with…", "فتح باستخدام…")) }
                }
            }
        },
        confirmButton = {
            if (it.kind != Kind.TORRENT) TextButton(onClick = { onDismiss(); Sys.openFile(ctx, f) }) { Text(tr("Open", "فتح")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Close", "إغلاق")) } },
    )
}

/** "The app will exit in N seconds" with a way to cancel. */
@Composable
fun CountdownDialog(onDismiss: () -> Unit) {
    var left by remember { mutableIntStateOf(30) }
    LaunchedEffect(Unit) {
        while (left > 0) { delay(1000); left-- }
        onDismiss()
        Manager.exitApp()
    }
    AlertDialog(
        onDismissRequest = {},
        title = { Text(tr("All downloads are finished.", "انتهت كل التحميلات.")) },
        text = { Text(tr("${appName()} will exit in $left seconds.", "سيُغلق ${appName()} خلال $left ثانية.")) },
        confirmButton = { TextButton(onClick = { onDismiss(); Manager.exitApp() }) { Text(tr("Now", "الآن")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Cancel", "إلغاء")) } },
    )
}
