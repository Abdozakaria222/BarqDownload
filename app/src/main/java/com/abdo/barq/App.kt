package com.abdo.barq

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.Application
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.core.content.FileProvider
import com.abdo.barq.core.Names
import com.abdo.barq.core.QueueInfo
import java.io.File
import java.util.Calendar

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        Lang.init()
        Store.init(this)
        Notifier.channels(this)
        Manager.init(this)
        VideoEngine.initAsync(this)
    }
}

/** Android helpers: open/share files, clipboard, folders. */
object Sys {
    fun uriFor(ctx: Context, f: File): Uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)

    fun mimeOf(f: File): String =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(Names.ext(f.name)) ?: when (Names.ext(f.name)) {
            "mkv" -> "video/x-matroska"
            "apk" -> "application/vnd.android.package-archive"
            else -> "*/*"
        }

    fun openFile(ctx: Context, f: File, chooser: Boolean = false, mime: String? = null) {
        if (!f.exists()) { toast(ctx, tr("The file is not there anymore.", "الملف لم يعد موجوداً.")); return }
        val i = Intent(Intent.ACTION_VIEW).setDataAndType(uriFor(ctx, f), mime ?: mimeOf(f))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            ctx.startActivity(if (chooser) Intent.createChooser(i, tr("Open with…", "فتح باستخدام…")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) else i)
        } catch (e: ActivityNotFoundException) {
            toast(ctx, tr("No app can open this file.", "لا يوجد تطبيق يفتح هذا الملف."))
        } catch (e: Exception) {
            toast(ctx, e.message ?: "error")
        }
    }

    fun share(ctx: Context, f: File) {
        val i = Intent(Intent.ACTION_SEND).setType(mimeOf(f)).putExtra(Intent.EXTRA_STREAM, uriFor(ctx, f))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ctx.startActivity(Intent.createChooser(i, tr("Share", "مشاركة")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Opens the folder in the Files app when the phone allows it. */
    fun openFolder(ctx: Context, dir: String) {
        val root = android.os.Environment.getExternalStorageDirectory().absolutePath
        if (dir.startsWith(root)) {
            val rel = dir.removePrefix(root).trimStart('/')
            val uri = DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:$rel")
            val tries = listOf(
                Intent(Intent.ACTION_VIEW).setDataAndType(uri, DocumentsContract.Document.MIME_TYPE_DIR),
                Intent(Intent.ACTION_VIEW).setDataAndType(uri, "vnd.android.document/root"),
                Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse("content://com.android.externalstorage.documents/document/primary%3A" + Uri.encode(rel)), "resource/folder"),
            )
            for (i in tries) {
                try {
                    ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION))
                    return
                } catch (e: Exception) { /* next */ }
            }
        }
        copy(ctx, dir)
        toast(ctx, tr("Folder path copied: ", "تم نسخ مسار المجلد: ") + dir)
    }

    fun copy(ctx: Context, text: String) {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("text", text))
    }

    fun clipboardText(ctx: Context): String? = runCatching {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(ctx)?.toString()
    }.getOrNull()

    fun toast(ctx: Context, s: String) {
        Manager.main.post { Toast.makeText(ctx, s, Toast.LENGTH_LONG).show() }
    }

    /** A folder picked with the system picker → a normal path (for files on the phone's own storage). */
    fun treeUriToPath(uri: Uri): String? {
        val id = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull() ?: return null
        val vol = id.substringBefore(':')
        val rel = id.substringAfter(':', "")
        val base = if (vol.equals("primary", true)) android.os.Environment.getExternalStorageDirectory().absolutePath else "/storage/$vol"
        return if (rel.isEmpty()) base else "$base/$rel"
    }

    fun canWrite(dir: String): Boolean = runCatching {
        val d = File(dir)
        d.mkdirs()
        val t = File(d, ".barq-test-${System.nanoTime()}")
        val ok = t.createNewFile()
        t.delete()
        ok
    }.getOrDefault(false)

    fun hasAllFilesAccess(): Boolean = Build.VERSION.SDK_INT >= 30 && android.os.Environment.isExternalStorageManager()
}

/** Starts and stops queues at the times chosen in the Scheduler. */
object Scheduler {
    private const val ACT_START = "com.abdo.barq.QUEUE_START"
    private const val ACT_STOP = "com.abdo.barq.QUEUE_STOP"

    fun rescheduleAll(ctx: Context) {
        for (q in Manager.queues) schedule(ctx, q)
    }

    fun schedule(ctx: Context, q: QueueInfo) {
        cancel(ctx, q)
        if (q.startEnabled) nextTime(q, q.startTime, true)?.let { set(ctx, q.id, ACT_START, it) }
        if (q.stopEnabled) nextTime(q, q.stopTime, q.startEnabled.not() || q.daily)?.let { set(ctx, q.id, ACT_STOP, it) }
    }

    fun cancel(ctx: Context, q: QueueInfo) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(pi(ctx, q.id, ACT_START))
        am.cancel(pi(ctx, q.id, ACT_STOP))
    }

    private fun pi(ctx: Context, qid: String, act: String): PendingIntent {
        val i = Intent(ctx, AlarmReceiver::class.java).setAction(act).putExtra("q", qid)
        return PendingIntent.getBroadcast(ctx, (qid + act).hashCode(), i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    @SuppressLint("MissingPermission")
    private fun set(ctx: Context, qid: String, act: String, at: Long) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val p = pi(ctx, qid, act)
        val exact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
        try {
            if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, p)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, p)
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, p)
        }
    }

    /** Next moment for "HH:MM" on the chosen days, or on the one date. */
    fun nextTime(q: QueueInfo, hhmm: String, allowDaily: Boolean): Long? {
        val h = hhmm.substringBefore(':').trim().toIntOrNull()?.coerceIn(0, 23) ?: return null
        val m = hhmm.substringAfter(':', "0").trim().toIntOrNull()?.coerceIn(0, 59) ?: 0
        val now = Calendar.getInstance()
        if (!q.daily || !allowDaily) {
            val d = q.onceDate.trim().split('-').mapNotNull { it.toIntOrNull() }
            val c = Calendar.getInstance()
            if (d.size == 3) {
                c.set(d[0], d[1] - 1, d[2], h, m, 0)
            } else {
                c.set(Calendar.HOUR_OF_DAY, h)
                c.set(Calendar.MINUTE, m)
                c.set(Calendar.SECOND, 0)
            }
            c.set(Calendar.MILLISECOND, 0)
            if (c.before(now)) {
                if (d.size == 3) return null
                c.add(Calendar.DAY_OF_YEAR, 1)
            }
            return c.timeInMillis
        }
        for (add in 0..7) {
            val c = Calendar.getInstance()
            c.add(Calendar.DAY_OF_YEAR, add)
            c.set(Calendar.HOUR_OF_DAY, h); c.set(Calendar.MINUTE, m); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
            if (!c.after(now)) continue
            // Calendar: Sunday = 1 … Saturday = 7. Our bits: Monday = bit 0 … Sunday = bit 6.
            val bit = (c.get(Calendar.DAY_OF_WEEK) + 5) % 7
            if ((q.days and (1 shl bit)) != 0) return c.timeInMillis
        }
        return null
    }

    fun handle(ctx: Context, intent: Intent) {
        val qid = intent.getStringExtra("q") ?: return
        val q = Manager.queues.firstOrNull { it.id == qid } ?: return
        when (intent.action) {
            ACT_START -> {
                Manager.startQueue(qid)
                if (!q.daily) { q.startEnabled = false; Manager.changed() }
            }
            ACT_STOP -> Manager.stopQueue(qid)
        }
        schedule(ctx, q)
    }
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Manager.init(context)
        Manager.main.post { Scheduler.handle(context, intent) }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Manager.init(context)
        Scheduler.rescheduleAll(context)
    }
}
