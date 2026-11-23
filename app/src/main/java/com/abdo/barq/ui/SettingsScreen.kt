@file:OptIn(ExperimentalMaterial3Api::class)

package com.abdo.barq.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.abdo.barq.Lang
import com.abdo.barq.Manager
import com.abdo.barq.Prefs
import com.abdo.barq.R
import com.abdo.barq.Sys
import com.abdo.barq.VideoEngine
import com.abdo.barq.core.NetConfig
import com.abdo.barq.tr

/** "Options": every setting of the program, in tabs like the Windows version. */
@Composable
fun SettingsScreen() {
    Prefs.version.intValue
    var tab by remember { mutableIntStateOf(0) }
    val tabs = listOf(
        tr("General", "عام"), tr("Save to", "الحفظ"), tr("Downloads", "التحميلات"), tr("Connection", "الاتصال"),
        tr("Proxy / Server", "البروكسي / الخادم"), tr("Site Logins", "حسابات المواقع"), tr("Speed Limiter", "محدد السرعة"),
        tr("Torrents", "التورنت"), tr("Browser", "المتصفح"), tr("Video downloader", "محمّل الفيديو"), tr("Sounds", "الأصوات"),
    )
    Scaffold(topBar = {
        TopAppBar(
            title = { Text(tr("Options", "الخيارات")) },
            navigationIcon = { IconBtn(R.drawable.ic_arrow_back, tr("Back", "رجوع")) { Ui.back() } },
        )
    }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            ScrollableTabRow(selectedTabIndex = tab, edgePadding = 8.dp) {
                tabs.forEachIndexed { i, t -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) }) }
            }
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                when (tab) {
                    0 -> GeneralTab()
                    1 -> SaveTab()
                    2 -> DownloadsTab()
                    3 -> ConnectionTab()
                    4 -> ProxyTab()
                    5 -> LoginsTab()
                    6 -> LimiterOptions()
                    7 -> TorrentOptions()
                    8 -> BrowserOptions()
                    9 -> VideoOptions()
                    else -> SoundOptions()
                }
            }
        }
    }
}

@Composable
private fun GeneralTab() {
    val ctx = LocalContext.current
    Dropdown(tr("Language", "اللغة"), listOf("" to tr("Like the phone", "مثل الهاتف"), "ar" to "العربية", "en" to "English"), Prefs.language) { v ->
        Prefs.language = v; Lang.init()
    }
    Dropdown(tr("Theme", "المظهر"), Themes.names().mapIndexed { i, n -> i to n }, Prefs.theme) { v -> Prefs.theme = v }
    Dropdown(tr("Text size", "حجم النص"), listOf(0.85f to tr("Small", "صغير"), 1f to tr("Normal", "عادي"), 1.15f to tr("Large", "كبير"), 1.3f to tr("Very large", "كبير جداً")),
        listOf(0.85f, 1f, 1.15f, 1.3f).minByOrNull { kotlin.math.abs(it - Prefs.textScale) } ?: 1f) { v -> Prefs.textScale = v }
    SwitchRow(tr("Watch the clipboard for download links", "مراقبة الحافظة لروابط التحميل"), Prefs.watchClipboard,
        tr("Checked when you open the app (Android does not let apps read it in the background).", "يُفحص عند فتح التطبيق (أندرويد لا يسمح بقراءتها في الخلفية).")) { Prefs.watchClipboard = it }
    SwitchRow(tr("Automatically start downloading links placed in the clipboard", "بدء تحميل الروابط الموضوعة في الحافظة تلقائياً"), Prefs.autoStartClipboard, enabled = Prefs.watchClipboard) { Prefs.autoStartClipboard = it }
    SwitchRow(tr("Resume unfinished downloads when the app starts", "استئناف التحميلات غير المكتملة عند فتح التطبيق"), Prefs.autoResume) { Prefs.autoResume = it }
    SwitchRow(tr("Show \"Tip of the day\" at startup", "إظهار \"نصيحة اليوم\" عند البدء"), Prefs.showTips) { Prefs.showTips = it }
    Text(tr("On tap on a download", "عند الضغط على تحميل"), modifier = Modifier.padding(top = 8.dp))
    RadioRow(tr("Open the file / progress window", "فتح الملف / نافذة التقدّم"), Prefs.tapAction == 0) { Prefs.tapAction = 0 }
    RadioRow(tr("Show properties", "عرض الخصائص"), Prefs.tapAction == 1) { Prefs.tapAction = 1 }
    SectionTitle(tr("Keep downloads going", "استمرار التحميل"))
    Hint(tr("Some phones stop apps in the background. Allow the app to run without battery limits.", "بعض الهواتف توقف التطبيقات في الخلفية. اسمح للتطبيق بالعمل بلا قيود البطارية."))
    OutlinedButton(onClick = {
        runCatching {
            ctx.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + ctx.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { runCatching { ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
    }) { Text(tr("Battery: no limits", "البطارية: بلا قيود")) }
}

@Composable
private fun SaveTab() {
    val ctx = LocalContext.current
    var main by remember { mutableStateOf(Prefs.mainDir) }
    var temp by remember { mutableStateOf(Prefs.tempDir) }
    FolderField(tr("Main download folder", "مجلد التحميل الرئيسي"), main, { v -> main = v; Prefs.mainDir = v })
    SwitchRow(tr("Sort files into folders by category (Video, Music, Programs, Compressed, Documents, Images…)", "ترتيب الملفات في مجلدات حسب التصنيف (فيديو، موسيقى، برامج، مضغوطة، مستندات، صور…)"),
        Prefs.sortIntoCategories) { Prefs.sortIntoCategories = it }
    Hint(tr("Each category's own folder and file types: open the menu, then the pencil next to the category.", "مجلد كل تصنيف وأنواع ملفاته: افتح القائمة ثم القلم بجانب التصنيف."))
    OutlinedButton(onClick = { Manager.categories.forEach { it.dir = "" }; Manager.changed(); Sys.toast(ctx, tr("Done", "تم")) }) {
        Text(tr("Forget remembered category paths", "نسيان مسارات التصنيفات المحفوظة"))
    }
    SectionTitle(tr("Temporary folder (parts while downloading)", "المجلد المؤقت (الأجزاء أثناء التحميل)"))
    RadioRow(tr("next to the file (default)", "بجانب الملف (افتراضي)"), temp.isBlank()) { temp = ""; Prefs.tempDir = "" }
    RadioRow(tr("inside the app", "داخل التطبيق"), temp.isNotBlank()) {
        temp = ctx.getExternalFilesDir("parts")?.absolutePath ?: ctx.filesDir.absolutePath; Prefs.tempDir = temp
    }
    if (temp.isNotBlank()) FolderField(tr("Temporary folder", "المجلد المؤقت"), temp, { v -> temp = v; Prefs.tempDir = v })
    SectionTitle(tr("Access to storage", "الوصول إلى التخزين"))
    if (Build.VERSION.SDK_INT >= 30) {
        val all = Sys.hasAllFilesAccess()
        Hint(if (all) tr("The app can save to any folder.", "التطبيق يستطيع الحفظ في أي مجلد.")
            else tr("Saving inside Download works as is. To save to any other folder (or an SD card), allow \"All files access\".",
                "الحفظ داخل مجلد Download يعمل كما هو. للحفظ في أي مجلد آخر (أو كارت ميموري)، اسمح بـ \"الوصول إلى كل الملفات\"."))
        if (!all) OutlinedButton(onClick = {
            runCatching { ctx.startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + ctx.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                .onFailure { runCatching { ctx.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
        }) { Text(tr("Allow all files access", "السماح بالوصول إلى كل الملفات")) }
    }
    Hint(if (Sys.canWrite(main)) tr("✓ The main folder is writable.", "✓ يمكن الكتابة في المجلد الرئيسي.") else tr("✗ The app cannot write to this folder.", "✗ لا يستطيع التطبيق الكتابة في هذا المجلد."))
}

@Composable
private fun DownloadsTab() {
    SwitchRow(tr("Show the \"Download File Info\" window for shared links", "إظهار نافذة \"معلومات ملف التحميل\" للروابط المشاركة"), Prefs.showAddDialog) { Prefs.showAddDialog = it }
    SwitchRow(tr("Ask before removing downloads from the list", "السؤال قبل حذف التحميلات من القائمة"), Prefs.askBeforeRemove) { Prefs.askBeforeRemove = it }
    SwitchRow(tr("Show the \"Download complete\" window", "إظهار نافذة \"اكتمل التحميل\""), Prefs.showCompleteDialog) { Prefs.showCompleteDialog = it }
    SwitchRow(tr("Give finished files the date from the server", "إعطاء الملفات المكتملة تاريخ الخادم"), Prefs.serverDate) { Prefs.serverDate = it }
    Text(tr("Show the download progress window", "إظهار نافذة تقدّم التحميل"), modifier = Modifier.padding(top = 8.dp))
    RadioRow(tr("Normally (open it)", "عادياً (فتحها)"), Prefs.showProgress == 0) { Prefs.showProgress = 0 }
    RadioRow(tr("Minimized (stay on the list)", "مصغّرة (البقاء في القائمة)"), Prefs.showProgress == 1) { Prefs.showProgress = 1 }
    RadioRow(tr("Don't show", "لا تظهر"), Prefs.showProgress == 2) { Prefs.showProgress = 2 }
    Dropdown(tr("If a duplicate link is added", "عند إضافة رابط مكرر"), listOf(
        0 to tr("Ask me", "اسألني"), 1 to tr("Add it with a numbered file name", "إضافته باسم برقم"),
        2 to tr("Add it and replace the old download", "إضافته واستبدال القديم"), 3 to tr("Show the finished one / resume the unfinished one", "عرض المكتمل / استئناف غير المكتمل"),
    ), Prefs.duplicatePolicy) { Prefs.duplicatePolicy = it }
    var ua by remember { mutableStateOf(Prefs.userAgent) }
    Field(tr("User-Agent for downloads added by hand", "User-Agent للتحميلات المضافة يدوياً"), ua, { v -> ua = v; Prefs.userAgent = v })
    OutlinedButton(onClick = { ua = NetConfig.DEFAULT_UA; Prefs.userAgent = ua }) { Text(tr("Reset", "استعادة الافتراضي")) }
}

@Composable
private fun ConnectionTab() {
    Dropdown(tr("Connection type / speed", "نوع / سرعة الاتصال"), listOf(
        0 to tr("Low speed (mobile data)", "سرعة منخفضة (بيانات الهاتف)"), 1 to tr("Medium speed (DSL)", "سرعة متوسطة (DSL)"),
        2 to tr("High speed (fiber, cable, LAN)", "سرعة عالية (فايبر، كابل، شبكة)"),
    ), Prefs.connPreset) { v -> Prefs.connPreset = v; Prefs.defaultConnections = when (v) { 0 -> 2; 1 -> 8; else -> 16 } }
    SliderRow(tr("Default max. connections number", "أقصى عدد اتصالات افتراضي"), Prefs.defaultConnections, 1..32) { Prefs.defaultConnections = it }
    SliderRow(tr("Downloads at the same time (0 = no limit)", "تحميلات في نفس الوقت (0 = بلا حد)"), Prefs.maxParallel, 0..10) { Prefs.maxParallel = it }
    SliderRow(tr("Retries on error", "المحاولات عند الخطأ"), Prefs.retries, 0..30) { Prefs.retries = it }
    SwitchRow(tr("Download only on Wi-Fi", "التحميل عبر الواي فاي فقط"), Prefs.wifiOnly) { Prefs.wifiOnly = it }
    var ex by remember { mutableStateOf(Prefs.exceptions) }
    Field(tr("Exceptions: servers with their own number of connections", "استثناءات: خوادم لها عدد اتصالات خاص"), ex, { v -> ex = v; Prefs.exceptions = v },
        singleLine = false, minLines = 2, placeholder = "example.com=2")
    SectionTitle(tr("Download limits", "حدود التحميل"))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        IntField(tr("Download no more than (MB, 0 = off)", "لا تحمّل أكثر من (ميجا، 0 = إيقاف)"), Prefs.quotaMb, { v -> Prefs.quotaMb = v; Manager.applyLimits() }, Modifier.weight(1f))
        IntField(tr("every (hours)", "كل (ساعات)"), Prefs.quotaHours, { v -> Prefs.quotaHours = v; Manager.applyLimits() }, Modifier.weight(1f), min = 1)
    }
}

@Composable
private fun ProxyTab() {
    var host by remember { mutableStateOf(Prefs.proxyHost) }
    var bypass by remember { mutableStateOf(Prefs.proxyBypass) }
    RadioRow(tr("No proxy", "بدون بروكسي"), Prefs.proxyMode == 0) { Prefs.proxyMode = 0 }
    RadioRow(tr("Use the system settings (like your browser)", "استخدام إعدادات النظام (مثل المتصفح)"), Prefs.proxyMode == 1) { Prefs.proxyMode = 1 }
    RadioRow(tr("Manual proxy configuration (HTTP / HTTPS)", "إعداد بروكسي يدوي (HTTP / HTTPS)"), Prefs.proxyMode == 2) { Prefs.proxyMode = 2 }
    RadioRow(tr("Manual SOCKS5 proxy", "بروكسي SOCKS5 يدوي"), Prefs.proxyMode == 3) { Prefs.proxyMode = 3 }
    if (Prefs.proxyMode >= 2) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Field(tr("Proxy address", "عنوان البروكسي"), host, { v -> host = v; Prefs.proxyHost = v.trim() }, Modifier.weight(2f), placeholder = "proxy.example.com")
            IntField(tr("Port", "المنفذ"), Prefs.proxyPort, { v -> Prefs.proxyPort = v }, Modifier.weight(1f), min = 1, max = 65535)
        }
        Field(tr("Don't use the proxy for", "عدم استخدام البروكسي لـ"), bypass, { v -> bypass = v; Prefs.proxyBypass = v })
        Hint(tr("Addresses separated by spaces.", "عناوين بينها مسافات."))
    }
}

@Composable
private fun LoginsTab() {
    var text by remember { mutableStateOf(Prefs.siteLogins) }
    var site by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    Hint(tr("User names and passwords for sites that ask for them:", "أسماء المستخدمين وكلمات المرور للمواقع التي تطلبها:"))
    Field(tr("Site / path", "الموقع / المسار"), site, { site = it }, placeholder = "example.com/members")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Field(tr("Login", "اسم المستخدم"), user, { user = it }, Modifier.weight(1f))
        Field(tr("Password", "كلمة المرور"), pass, { pass = it }, Modifier.weight(1f))
    }
    OutlinedButton(onClick = {
        if (site.isNotBlank()) {
            val lines = text.lines().filter { it.isNotBlank() && it.substringBefore('\t') != site.trim() } + "${site.trim()}\t$user\t$pass"
            text = lines.joinToString("\n"); Prefs.siteLogins = text
            site = ""; user = ""; pass = ""
        }
    }) { Text(tr("Add / Update", "إضافة / تحديث")) }
    for (l in text.lines().filter { it.isNotBlank() }) {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text(l.substringBefore('\t') + "  —  " + l.split('\t').getOrElse(1) { "" }, modifier = Modifier.weight(1f))
            IconBtn(R.drawable.ic_delete, tr("Delete", "حذف")) {
                text = text.lines().filter { x -> x != l }.joinToString("\n"); Prefs.siteLogins = text
            }
        }
    }
    Hint(tr("Saved only inside the app on this phone.", "تُحفظ داخل التطبيق على هذا الهاتف فقط."))
}

@Composable
private fun LimiterOptions() {
    SwitchRow(tr("Turn the speed limiter on", "تشغيل محدد السرعة"), Prefs.limiterOn) { Prefs.limiterOn = it; Manager.applyLimits() }
    IntField(tr("Maximum download speed (KB/s)", "أقصى سرعة تحميل (كيلوبايت/ث)"), Prefs.limiterKBps, { v -> Prefs.limiterKBps = v; Manager.applyLimits() }, min = 1)
    SwitchRow(tr("Always turn on the speed limiter when the app starts", "تشغيل محدد السرعة دائماً عند فتح التطبيق"), Prefs.limiterAlwaysOn) { Prefs.limiterAlwaysOn = it }
    SwitchRow(tr("Limit only on mobile data (full speed on Wi-Fi)", "التحديد على بيانات الهاتف فقط (سرعة كاملة على الواي فاي)"), Prefs.limiterMobileOnly) { Prefs.limiterMobileOnly = it; Manager.applyLimits() }
    Hint(tr("Limit the download speed so the internet stays usable for other things. Every download can also have its own limit: its progress page > Speed Limiter.",
        "حدّد سرعة التحميل ليبقى الإنترنت صالحاً لأشياء أخرى. يمكن لكل تحميل أن يكون له حد خاص: صفحة التقدّم > محدد السرعة."))
}

@Composable
private fun TorrentOptions() {
    SwitchRow(tr("Open magnet links and .torrent files with this app", "فتح روابط magnet وملفات torrent بهذا التطبيق"), Prefs.torrentDefault) { Prefs.torrentDefault = it }
    IntField(tr("Keep sharing a finished torrent for (minutes)", "الاستمرار في مشاركة التورنت المكتمل لمدة (دقائق)"), Prefs.seedMinutes, { Prefs.seedMinutes = it })
    Hint(tr("0 = stop as soon as all files are here. Sharing longer helps the others who download the same torrent.",
        "0 = التوقف بمجرد وصول كل الملفات. المشاركة لمدة أطول تساعد الآخرين الذين يحمّلون نفس التورنت."))
    Dropdown(tr("Stop sharing once it sent (times what it received)", "إيقاف المشاركة بعد إرسال (أضعاف ما استقبله)"),
        listOf(0f to tr("No limit", "بلا حد"), 0.5f to "0.5", 1f to "1", 2f to "2", 3f to "3"), Prefs.seedRatio) { Prefs.seedRatio = it }
    IntField(tr("Maximum upload speed (KB/s, 0 = no limit)", "أقصى سرعة رفع (كيلوبايت/ث، 0 = بلا حد)"), Prefs.uploadKBps, { Prefs.uploadKBps = it; Manager.applyLimits() })
    Hint(tr("While a torrent downloads it also sends pieces to others (that is how torrents work). A limit keeps your internet free for other things.",
        "أثناء تحميل التورنت يرسل أيضاً أجزاءً للآخرين (هكذا يعمل التورنت). الحد يبقي الإنترنت متاحاً لأشياء أخرى."))
}

@Composable
private fun BrowserOptions() {
    var home by remember { mutableStateOf(Prefs.homePage) }
    var types by remember { mutableStateOf(Prefs.browserTypes) }
    var exSites by remember { mutableStateOf(Prefs.browserExcludedSites) }
    var exAddrs by remember { mutableStateOf(Prefs.browserExcludedAddrs) }
    var panelEx by remember { mutableStateOf(Prefs.panelExcludedSites) }
    Field(tr("Start page", "صفحة البداية"), home, { v -> home = v; Prefs.homePage = v })
    Field(tr("Automatically take these file types (from the browser)", "التقاط أنواع الملفات هذه تلقائياً (من المتصفح)"), types, { v -> types = v; Prefs.browserTypes = v }, singleLine = false)
    Field(tr("Don't take downloads from these sites", "عدم التقاط التحميلات من هذه المواقع"), exSites, { v -> exSites = v; Prefs.browserExcludedSites = v }, placeholder = "*.update.microsoft.com")
    Field(tr("Don't take these addresses (one per line, * allowed)", "عدم التقاط هذه العناوين (واحد في كل سطر، * مسموح)"), exAddrs, { v -> exAddrs = v; Prefs.browserExcludedAddrs = v }, singleLine = false)
    SwitchRow(tr("Show the \"Download this video\" button on web players", "إظهار زر \"تحميل هذا الفيديو\" على مشغلات الويب"), Prefs.videoPanel) { Prefs.videoPanel = it }
    Field(tr("Don't show it on these sites", "عدم إظهاره في هذه المواقع"), panelEx, { v -> panelEx = v; Prefs.panelExcludedSites = v })
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        IntField(tr("Smallest video (KB)", "أصغر فيديو (كيلوبايت)"), Prefs.minVideoKB, { Prefs.minVideoKB = it }, Modifier.weight(1f))
        IntField(tr("Smallest audio (KB)", "أصغر صوت (كيلوبايت)"), Prefs.minAudioKB, { Prefs.minAudioKB = it }, Modifier.weight(1f))
    }
    SwitchRow(tr("Open sites in desktop mode", "فتح المواقع بوضع سطح المكتب"), Prefs.desktopMode) { Prefs.desktopMode = it }
    Hint(tr("Other browsers: use Share > $APP on any link or page to send it to the app.", "المتصفحات الأخرى: استخدم مشاركة > $APP_AR على أي رابط أو صفحة لإرساله للتطبيق."))
}

private val APP get() = com.abdo.barq.APP_NAME
private val APP_AR get() = com.abdo.barq.APP_NAME_AR

@Composable
private fun VideoOptions() {
    val ctx = LocalContext.current
    var subs by remember { mutableStateOf(Prefs.subLangs) }
    var status by remember { mutableStateOf<String?>(null) }
    Dropdown(tr("Quality of MP3 files", "جودة ملفات MP3"), listOf(128 to "128 kbps", 192 to "192 kbps", 256 to "256 kbps", 320 to "320 kbps"), Prefs.mp3Quality) { Prefs.mp3Quality = it }
    Field(tr("Download the subtitles of videos too, in these languages:", "تحميل ترجمات الفيديوهات أيضاً بهذه اللغات:"), subs, { v -> subs = v; Prefs.subLangs = v }, placeholder = "ar,en")
    Dropdown(tr("Default quality", "الجودة الافتراضية"), VideoEngine.qualities.map { it to VideoEngine.qualityLabel(it) }, Prefs.defaultQuality) { Prefs.defaultQuality = it }
    SwitchRow(tr("Update the video downloader by itself", "تحديث محمّل الفيديو تلقائياً"), Prefs.ytdlpAutoUpdate) { Prefs.ytdlpAutoUpdate = it }
    Text(tr("Version: ", "الإصدار: ") + if (VideoEngine.ready) VideoEngine.version(ctx) else tr("preparing…", "جارٍ التجهيز…"))
    VideoEngine.error?.let { e -> Text(e, color = androidx.compose.material3.MaterialTheme.colorScheme.error) }
    OutlinedButton(onClick = {
        status = tr("Checking…", "جارٍ التحقق…")
        Manager.bg.execute { status = runCatching { VideoEngine.update(ctx) }.getOrElse { e -> tr("Update failed: ", "فشل التحديث: ") + e.message } }
    }) { Text(tr("Check for updates (video downloader)", "التحقق من التحديثات (محمّل الفيديو)")) }
    status?.let { Hint(it) }
    Hint(tr("Videos are saved with yt-dlp, FFmpeg and QuickJS, which work with YouTube and 1000+ sites. Only download what you have the right to.",
        "تُحفظ الفيديوهات بـ yt-dlp و FFmpeg و QuickJS التي تعمل مع يوتيوب وأكثر من 1000 موقع. لا تحمّل إلا ما لك الحق فيه."))
}

@Composable
private fun SoundOptions() {
    SwitchRow(tr("Download complete", "اكتمل التحميل"), Prefs.soundDone) { Prefs.soundDone = it }
    SwitchRow(tr("Download failed", "فشل التحميل"), Prefs.soundFailed) { Prefs.soundFailed = it }
    SwitchRow(tr("Queue started", "بدأت القائمة"), Prefs.soundQueueStart) { Prefs.soundQueueStart = it }
    SwitchRow(tr("Queue finished", "انتهت القائمة"), Prefs.soundQueueEnd) { Prefs.soundQueueEnd = it }
    SwitchRow(tr("Vibrate", "اهتزاز"), Prefs.vibrate) { Prefs.vibrate = it }
    Hint(tr("The sound is the phone's notification sound.", "الصوت هو صوت الإشعارات في الهاتف."))
}
