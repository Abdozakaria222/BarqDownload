package com.abdo.barq

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.RingtoneManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.abdo.barq.core.DownloadItem
import com.abdo.barq.core.Fmt
import com.abdo.barq.core.St
import com.abdo.barq.ui.MainActivity
import java.io.File

object Notifier {
    const val CH_ACTIVE = "active"
    const val CH_DONE = "done"
    const val ID_SERVICE = 1

    fun channels(ctx: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_ACTIVE, tr("Running downloads", "التحميلات الجارية"), NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CH_DONE, tr("Finished downloads", "التحميلات المكتملة"), NotificationManager.IMPORTANCE_DEFAULT).apply {
            setSound(null, null)
        })
    }

    fun openApp(ctx: Context, id: Long = -1): PendingIntent {
        val i = Intent(ctx, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            if (id >= 0) putExtra("open_id", id)
        }
        return PendingIntent.getActivity(ctx, (id % 100000).toInt() + 10, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun action(ctx: Context, act: String, code: Int): PendingIntent =
        PendingIntent.getBroadcast(ctx, code, Intent(ctx, ActionReceiver::class.java).setAction(act), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    fun serviceNotification(ctx: Context): Notification {
        val running = Manager.runners.values.map { it.item }
        val seeding = Manager.items.count { it.seeding }
        val speed = running.sumOf { it.speed }
        val total = running.sumOf { maxOf(0, it.size) }
        val done = running.sumOf { it.downloaded }
        val title = when {
            running.isEmpty() && seeding > 0 -> tr("Sharing $seeding torrent(s)", "مشاركة $seeding تورنت")
            running.size == 1 -> running[0].fileName
            else -> tr("${running.size} downloads", "${running.size} تحميلات")
        }
        val text = buildString {
            if (total > 0) append(Fmt.size(done)).append(" / ").append(Fmt.size(total))
            if (speed > 0) { if (isNotEmpty()) append("  •  "); append(Fmt.speed(speed)) }
        }
        val b = NotificationCompat.Builder(ctx, CH_ACTIVE)
            .setSmallIcon(R.drawable.ic_download)
            .setContentTitle(title)
            .setContentText(text.ifEmpty { tr("Preparing…", "جارٍ التجهيز…") })
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(openApp(ctx))
            .addAction(R.drawable.ic_pause, tr("Pause all", "إيقاف الكل"), action(ctx, ActionReceiver.PAUSE_ALL, 2))
        if (total > 0) b.setProgress(1000, (done * 1000 / total).toInt().coerceIn(0, 1000), false)
        else if (running.isNotEmpty()) b.setProgress(0, 0, true)
        return b.build()
    }

    @SuppressLint("MissingPermission")
    private fun post(ctx: Context, id: Int, n: Notification) {
        runCatching { NotificationManagerCompat.from(ctx).notify(id, n) }
    }

    fun completed(ctx: Context, it: DownloadItem) {
        val file = File(it.dir, it.fileName)
        val open = if (file.exists()) PendingIntent.getActivity(
            ctx, (it.id % 100000).toInt() + 200000,
            Intent(ctx, MainActivity::class.java).putExtra("open_file", file.absolutePath).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        ) else openApp(ctx, it.id)
        val n = NotificationCompat.Builder(ctx, CH_DONE)
            .setSmallIcon(R.drawable.ic_download_done)
            .setContentTitle(tr("Download complete", "اكتمل التحميل"))
            .setContentText(it.fileName)
            .setSubText(Fmt.size(it.size))
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        post(ctx, (it.id % 1_000_000).toInt() + 100, n)
    }

    fun failed(ctx: Context, it: DownloadItem) {
        val n = NotificationCompat.Builder(ctx, CH_DONE)
            .setSmallIcon(R.drawable.ic_error)
            .setContentTitle(tr("Download failed", "فشل التحميل"))
            .setContentText(it.fileName)
            .setStyle(NotificationCompat.BigTextStyle().bigText(it.fileName + "\n" + (it.error ?: "")))
            .setAutoCancel(true)
            .setContentIntent(openApp(ctx, it.id))
            .build()
        post(ctx, (it.id % 1_000_000).toInt() + 100, n)
    }

    fun message(ctx: Context, title: String, text: String) {
        val n = NotificationCompat.Builder(ctx, CH_DONE)
            .setSmallIcon(R.drawable.ic_bolt)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(openApp(ctx))
            .build()
        post(ctx, 50, n)
    }

    fun sound(ctx: Context) {
        runCatching {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            RingtoneManager.getRingtone(ctx, uri)?.play()
        }
        if (Prefs.vibrate) runCatching {
            val v = ctx.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            if (Build.VERSION.SDK_INT >= 26) {
                v.vibrate(VibrationEffect.createOneShot(120, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(120)
            }
        }
    }
}

/** Keeps downloads going when the app is in the background or the screen is off. */
class DownloadService : Service() {
    private var wake: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null

    companion object {
        @Volatile var instance: DownloadService? = null

        fun ensure(ctx: Context) {
            if (instance != null) return
            runCatching { ContextCompat.startForegroundService(ctx, Intent(ctx, DownloadService::class.java)) }
        }

        fun refresh() {
            val s = instance ?: return
            Manager.main.post { s.update() }
        }

        fun stop(ctx: Context) {
            runCatching { ctx.stopService(Intent(ctx, DownloadService::class.java)) }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        runCatching { ServiceCompat.startForeground(this, Notifier.ID_SERVICE, Notifier.serviceNotification(this), type) }
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Barq:downloads").apply { setReferenceCounted(false); acquire(6 * 3600_000L) }
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        @Suppress("DEPRECATION")
        wifi = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "Barq:wifi").apply { setReferenceCounted(false); acquire() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        update()
        return START_STICKY
    }

    fun update() {
        val busy = Manager.runners.isNotEmpty() || Manager.items.any { it.seeding || it.status == St.WAITING }
        if (!busy) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        runCatching { NotificationManagerCompat.from(this).notify(Notifier.ID_SERVICE, Notifier.serviceNotification(this)) }
        if (wake?.isHeld == false) wake?.acquire(6 * 3600_000L)
    }

    override fun onDestroy() {
        instance = null
        runCatching { wake?.release() }
        runCatching { wifi?.release() }
        super.onDestroy()
    }
}

class ActionReceiver : BroadcastReceiver() {
    companion object {
        const val PAUSE_ALL = "com.abdo.barq.PAUSE_ALL"
    }

    override fun onReceive(context: Context, intent: Intent) {
        Manager.init(context)
        when (intent.action) {
            PAUSE_ALL -> Manager.pauseAll()
        }
    }
}
