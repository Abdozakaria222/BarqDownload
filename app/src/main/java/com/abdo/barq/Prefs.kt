package com.abdo.barq

import android.content.Context
import android.content.SharedPreferences
import android.os.Environment
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import com.abdo.barq.core.NetConfig
import java.io.File
import java.util.Locale
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

const val APP_NAME = "Barq Download"
const val APP_NAME_AR = "برق للتحميل"

/** Interface language: Arabic or English, switchable at once. */
object Lang {
    val ar = mutableStateOf(false)
    fun init() {
        ar.value = when (Prefs.language) {
            "ar" -> true
            "en" -> false
            else -> Locale.getDefault().language == "ar"
        }
    }
}

fun tr(en: String, ar: String): String = if (Lang.ar.value) ar else en
fun appName(): String = tr(APP_NAME, APP_NAME_AR)

/** Every option of the program, saved in SharedPreferences. */
object Prefs {
    lateinit var sp: SharedPreferences
    /** Bumped on every change so Compose screens that read it update. */
    val version = mutableIntStateOf(0)

    fun init(ctx: Context) {
        sp = ctx.getSharedPreferences("barq", Context.MODE_PRIVATE)
    }

    private fun changed() { version.intValue = version.intValue + 1 }

    private fun bool(key: String, def: Boolean) = object : ReadWriteProperty<Any?, Boolean> {
        override fun getValue(thisRef: Any?, property: KProperty<*>) = sp.getBoolean(key, def)
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: Boolean) { sp.edit().putBoolean(key, value).apply(); changed() }
    }

    private fun int(key: String, def: Int) = object : ReadWriteProperty<Any?, Int> {
        override fun getValue(thisRef: Any?, property: KProperty<*>) = sp.getInt(key, def)
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: Int) { sp.edit().putInt(key, value).apply(); changed() }
    }

    private fun long(key: String, def: Long) = object : ReadWriteProperty<Any?, Long> {
        override fun getValue(thisRef: Any?, property: KProperty<*>) = sp.getLong(key, def)
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: Long) { sp.edit().putLong(key, value).apply() }
    }

    private fun float(key: String, def: Float) = object : ReadWriteProperty<Any?, Float> {
        override fun getValue(thisRef: Any?, property: KProperty<*>) = sp.getFloat(key, def)
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: Float) { sp.edit().putFloat(key, value).apply(); changed() }
    }

    private fun str(key: String, def: () -> String) = object : ReadWriteProperty<Any?, String> {
        override fun getValue(thisRef: Any?, property: KProperty<*>) = sp.getString(key, null) ?: def()
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: String) { sp.edit().putString(key, value).apply(); changed() }
    }

    fun defaultMainDir(): String =
        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Barq").absolutePath

    // General / appearance
    var language by str("language") { "" }
    var theme by int("theme", 0)
    var textScale by float("textScale", 1f)
    var showTips by bool("showTips", true)
    var tipIndex by int("tipIndex", 0)
    var tapAction by int("tapAction", 0)               // 0 open the file / progress, 1 properties
    var sortOrder by int("sortOrder", 0)               // 0 added, 1 name, 2 size, 3 last try
    var colSize by bool("colSize", true)
    var colStatus by bool("colStatus", true)
    var colTimeLeft by bool("colTimeLeft", true)
    var colSpeed by bool("colSpeed", true)
    var colDate by bool("colDate", false)
    var colSaveTo by bool("colSaveTo", false)
    var colQueue by bool("colQueue", true)
    var watchClipboard by bool("watchClip", true)
    var autoStartClipboard by bool("autoClip", false)
    var lastClip by str("lastClip") { "" }
    var autoResume by bool("autoResume", true)

    // Save to
    var mainDir by str("mainDir") { defaultMainDir() }
    var sortIntoCategories by bool("sortCats", true)
    var tempDir by str("tempDir") { "" }

    // Downloads
    var showAddDialog by bool("showAddDialog", true)
    var startImmediately by bool("startNow", false)
    var askBeforeRemove by bool("askRemove", true)
    var mp3Quality by int("mp3Quality", 192)
    var subLangs by str("subLangs") { "ar,en" }
    var showProgress by int("showProgress", 1)         // 0 open the progress page, 1 stay on the list, 2 nothing
    var duplicatePolicy by int("dupPolicy", 0)         // 0 ask, 1 numbered name, 2 replace, 3 show / resume
    var serverDate by bool("serverDate", false)
    var showCompleteDialog by bool("showComplete", true)
    var whenAllDone by int("whenAllDone", 0)           // 0 nothing, 1 exit the app

    // Connection
    var connPreset by int("connPreset", 2)
    var defaultConnections by int("defConn", 8)
    var maxParallel by int("maxParallel", 3)
    var retries by int("retries", 10)
    var exceptions by str("exceptions") { "" }         // lines "host=connections"
    var quotaMb by int("quotaMb", 0)
    var quotaHours by int("quotaHours", 1)
    var wifiOnly by bool("wifiOnly", false)
    var userAgent by str("ua") { NetConfig.DEFAULT_UA }

    // Proxy / logins
    var proxyMode by int("proxyMode", 1)               // 0 none, 1 system, 2 HTTP, 3 SOCKS
    var proxyHost by str("proxyHost") { "" }
    var proxyPort by int("proxyPort", 8080)
    var proxyBypass by str("proxyBypass") { "localhost 127.0.0.1 *.local" }
    var siteLogins by str("siteLogins") { "" }         // lines "site\tuser\tpassword"

    // Sounds
    var soundDone by bool("soundDone", true)
    var soundFailed by bool("soundFailed", true)
    var soundQueueStart by bool("soundQStart", false)
    var soundQueueEnd by bool("soundQEnd", true)
    var vibrate by bool("vibrate", true)

    // Speed limiter
    var limiterOn by bool("limiterOn", false)
    var limiterKBps by int("limiterKBps", 500)
    var limiterAlwaysOn by bool("limiterAlways", false)
    var limiterMobileOnly by bool("limiterMobile", false)

    // Torrents
    var torrentDefault by bool("torrentDefault", true)
    var seedMinutes by int("seedMin", 0)
    var seedRatio by float("seedRatio", 0f)
    var uploadKBps by int("upKBps", 0)

    // Browser
    var homePage by str("homePage") { "https://www.google.com" }
    var videoPanel by bool("videoPanel", true)
    var panelExcludedSites by str("panelEx") { "" }
    var minVideoKB by int("minVideoKB", 300)
    var minAudioKB by int("minAudioKB", 100)
    var browserTypes by str("autoTypes") {
        "zip rar 7z gz tar iso apk xapk exe msi dmg pdf doc docx xls xlsx ppt pptx epub mp3 m4a flac wav mp4 mkv avi mov webm torrent"
    }
    var browserExcludedSites by str("bxSites") { "" }
    var browserExcludedAddrs by str("bxAddrs") { "" }
    var desktopMode by bool("desktopMode", false)

    // Video downloader
    var ytdlpAutoUpdate by bool("ytAuto", true)
    var ytdlpLastUpdate by long("ytLast", 0)
    var defaultQuality by str("defQuality") { "best" }

    var nextId by long("nextId", 1)
    var firstRun by bool("firstRun", true)

    fun netConfig(): NetConfig = NetConfig(
        proxyMode = proxyMode,
        proxyHost = proxyHost,
        proxyPort = proxyPort,
        proxyBypass = proxyBypass,
        userAgent = userAgent.ifBlank { NetConfig.DEFAULT_UA },
        siteLogins = siteLogins.lines().mapNotNull { l ->
            val p = l.split('\t')
            if (p.size >= 3 && p[0].isNotBlank()) Triple(p[0], p[1], p[2]) else null
        },
    )

    /** "Exceptions: servers with their own number of connections". */
    fun connectionsFor(url: String, wanted: Int): Int {
        val host = url.substringAfter("://").substringBefore('/').substringBefore(':').lowercase()
        for (line in exceptions.lines()) {
            val h = line.substringBefore('=').trim().lowercase().removePrefix("www.")
            val n = line.substringAfter('=', "").trim().toIntOrNull() ?: continue
            if (h.isNotEmpty() && (host == h || host.endsWith(".$h"))) return n.coerceIn(1, 32)
        }
        return wanted.coerceIn(1, 32)
    }

    @Synchronized
    fun newId(): Long {
        val id = nextId
        nextId = id + 1
        return id
    }
}
