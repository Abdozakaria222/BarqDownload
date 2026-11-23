package com.abdo.barq.core

import java.util.concurrent.CopyOnWriteArrayList

/** What engine handles a download. */
enum class Kind { HTTP, VIDEO, TORRENT }

/** Download state as shown in the list. */
enum class St { QUEUED, WAITING, RUNNING, PAUSED, DONE, ERROR }

/**
 * One byte range of a segmented download. [end] is inclusive; -1 means "until the end of the stream"
 * (unknown size). [downloaded] counts bytes already written from [start].
 */
class Segment(start: Long, end: Long, downloaded: Long = 0) {
    @Volatile var start: Long = start
    @Volatile var end: Long = end
    @Volatile var downloaded: Long = downloaded
    @Volatile var active: Boolean = false

    val pos: Long get() = start + downloaded
    val done: Boolean get() = end >= 0 && start + downloaded > end
    val remaining: Long get() = if (end < 0) Long.MAX_VALUE else end - pos + 1
}

class DownloadItem(val id: Long, var kind: Kind, url: String) {
    @Volatile var url: String = url
    @Volatile var fileName: String = ""
    @Volatile var dir: String = ""
    @Volatile var category: String = ""
    @Volatile var size: Long = -1
    @Volatile var downloaded: Long = 0
    @Volatile var status: St = St.PAUSED
    @Volatile var error: String? = null
    /** null = not checked yet. */
    @Volatile var resumable: Boolean? = null
    @Volatile var connections: Int = 8
    val segments = CopyOnWriteArrayList<Segment>()

    var referer: String? = null
    var parentPage: String? = null
    var cookies: String? = null
    var userAgent: String? = null
    var description: String? = null
    var checksum: String? = null
    var authUser: String? = null
    var authPass: String? = null
    var queueId: String? = null
    var queuePos: Int = 0
    var dateAdded: Long = System.currentTimeMillis()
    var lastTry: Long = 0
    var dateDone: Long = 0
    var serverTime: Long = 0
    @Volatile var speedLimit: Int = 0          // KB/s
    @Volatile var limiterOn: Boolean = false
    var onComplete: Int = 0                    // 0 nothing, 1 exit the app
    var nameLocked: Boolean = false            // the user typed the name: keep it
    var dirLocked: Boolean = false             // the user chose the folder: keep it
    var overwrite: Boolean = false             // replace a file with the same name when done
    var retryCount: Int = 0
    var autoRestarts: Int = 0

    // video (yt-dlp)
    var vQuality: String? = null               // best | 2160 | 1440 | 1080 | 720 | 480 | 360 | m4a | mp3
    var vTitle: String? = null
    var vSubs: Boolean = false
    var vStart: Double = -1.0                  // seconds, for "download a part"
    var vEnd: Double = -1.0
    var vExact: Boolean = false
    var vNumber: Int = 0                       // number prefix for playlists, 0 = none
    var vCookieFile: String? = null

    // torrent
    var infoHash: String? = null
    var torrentFile: String? = null
    var seqDownload: Boolean = false
    var firstLast: Boolean = false
    var filePriorities: IntArray? = null
    var extraTrackers: MutableList<String> = mutableListOf()

    // runtime only (not saved)
    @Volatile var speed: Long = 0
    @Volatile var eta: Long = -1
    @Volatile var phase: String? = null
    @Volatile var peers: Int = 0
    @Volatile var seeds: Int = 0
    @Volatile var upSpeed: Long = 0
    @Volatile var uploaded: Long = 0
    @Volatile var seeding: Boolean = false

    val progress: Float
        get() = if (size > 0) (downloaded.toDouble() / size).toFloat().coerceIn(0f, 1f) else 0f

    val isActive: Boolean get() = status == St.RUNNING
}

/** A download queue with its own schedule (the "Scheduler" of the Windows program). */
class QueueInfo(val id: String, var name: String) {
    var maxParallel: Int = 1
    var retries: Int = 3
    var saveDir: String = ""
    var openWhenDone: Boolean = false
    var startEnabled: Boolean = false
    var startTime: String = "02:00"            // HH:MM
    var daily: Boolean = true
    var days: Int = 0x7F                       // bit 0 = Monday … bit 6 = Sunday
    var onceDate: String = ""                  // YYYY-MM-DD
    var startOnAppStart: Boolean = false
    var stopEnabled: Boolean = false
    var stopTime: String = "07:00"
    var whenDone: Int = 0                      // 0 nothing, 1 exit the app
    @Volatile var running: Boolean = false
}

/** A file category: file types and sites go to its folder. */
class Category(val id: String, var name: String) {
    var exts: String = ""                      // "mp4 mkv avi"
    var sites: String = ""                     // "udemy.com *.coursera.org"
    var dir: String = ""                       // empty = automatic (main folder / name)
    var builtIn: Boolean = false

    fun extList(): List<String> = exts.lowercase().split(' ', ',', ';').map { it.trim().trimStart('.') }.filter { it.isNotEmpty() }
    fun siteList(): List<String> = sites.lowercase().split(' ', ',', ';').map { it.trim() }.filter { it.isNotEmpty() }
}
