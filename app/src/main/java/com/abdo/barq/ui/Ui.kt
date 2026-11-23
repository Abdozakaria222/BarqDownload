package com.abdo.barq.ui

import android.content.Context
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.abdo.barq.Manager
import com.abdo.barq.Prefs
import com.abdo.barq.VideoEngine
import com.abdo.barq.core.Batch
import com.abdo.barq.core.DownloadItem
import com.abdo.barq.core.GrabbedLink
import com.abdo.barq.core.Kind

sealed class Screen {
    object Main : Screen()
    class Details(val id: Long) : Screen()
    object Settings : Screen()
    object Queues : Screen()
    class Grabber(val url: String = "", val preset: List<GrabbedLink>? = null) : Screen()
    /** mode 0 one video, 1 playlist, 2 a part of a video */
    class Video(val url: String, val mode: Int, val referer: String? = null, val cookieFile: String? = null) : Screen()
    class Torrent(val magnet: String = "", val file: String? = null) : Screen()
    object About : Screen()
}

/** A link on its way into the "Download File Info" window. */
class AddRequest(
    val url: String,
    val referer: String? = null,
    val cookies: String? = null,
    val userAgent: String? = null,
    val page: String? = null,
    val name: String? = null,
    val size: Long = -1,
    val description: String? = null,
)

/** App-wide screen state: which page is open and which windows are shown. */
object Ui {
    val stack = mutableStateListOf<Screen>(Screen.Main)
    val screen: Screen get() = stack.last()

    fun go(s: Screen) { stack.add(s) }
    fun back(): Boolean {
        if (stack.size > 1) { stack.removeAt(stack.size - 1); return true }
        return false
    }
    fun home() { while (stack.size > 1) stack.removeAt(stack.size - 1) }

    var addRequest by mutableStateOf<AddRequest?>(null)
    var batchText by mutableStateOf<String?>(null)
    var completed by mutableStateOf<DownloadItem?>(null)
    var countdown by mutableStateOf<Int?>(null)
    var showTips by mutableStateOf(false)
    var clipLink by mutableStateOf<String?>(null)
    val snack = SnackbarHostState()

    /** Sends a link to the right place: torrent window, video window or "Download File Info". */
    fun route(ctx: Context, url: String, referer: String? = null, page: String? = null, cookies: String? = null, ua: String? = null) {
        val u = url.trim()
        when {
            u.startsWith("magnet:", true) -> go(Screen.Torrent(magnet = u))
            u.substringBefore('?').endsWith(".torrent", true) -> openAdd(ctx, AddRequest(u, referer, cookies, ua, page))
            VideoEngine.looksLikeVideoSite(u) -> go(Screen.Video(u, if (u.contains("list=") && !u.contains("watch?v=")) 1 else 0, referer))
            else -> openAdd(ctx, AddRequest(u, referer, cookies, ua, page))
        }
    }

    fun routeText(ctx: Context, text: String) {
        val links = Batch.linksFromText(text)
        when {
            links.isEmpty() -> return
            links.size == 1 -> route(ctx, links[0])
            else -> batchText = links.joinToString("\n")
        }
    }

    fun openAdd(ctx: Context, req: AddRequest) {
        if (Prefs.showAddDialog) { addRequest = req; return }
        // "Don't show the window": add with the automatic name and folder and start
        val it = Manager.newItem(Kind.HTTP, req.url, req.name)
        it.referer = req.referer; it.cookies = req.cookies; it.userAgent = req.userAgent; it.parentPage = req.page
        Manager.add(it, start = true)
    }
}
