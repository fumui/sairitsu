package my.fumui.sairitsu.library

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Build
import android.os.IBinder
import android.util.Log
import my.fumui.sairitsu.MainActivity
import my.fumui.sairitsu.R
import my.fumui.sairitsu.app.AppServices
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Keeps the process in the foreground while levels download, so switching apps doesn't freeze the downloads, and shows
 * their progress. The downloads themselves run in [AppServices.scope]; this only watches [LibraryDownloader.states] and
 * stops itself once nothing is queued or running.
 */
class DownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val downloader by lazy { AppServices.get(this).downloader }
    private var collector: Job? = null
    private var lastStartId = 0

    override fun onCreate() {
        super.onCreate()
        val channel = NotificationChannel(CHANNEL, "Downloads", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        if (intent?.action == ACTION_CANCEL_ALL) {
            downloader.cancelAll()
        } else {
            // First, always: Android stops the app if a started foreground service doesn't call this in time.
            if (!enterForeground()) {
                stopSelf(startId)
                return START_NOT_STICKY
            }
            if (collector == null) {
                collector = scope.launch { watch() }
                return START_NOT_STICKY
            }
        }
        // The watcher only wakes on a change. A start that arrives after the last change (the last level finished or
        // failed while it waited), or a Cancel all that changed nothing, must check for itself.
        if (downloader.states.value.values.none { it !is DownloadState.Failed }) stop()
        return START_NOT_STICKY
    }

    /** False when Android refuses (e.g. Android 15's daily `dataSync` time is used up): downloads then run only while the app is open. */
    private fun enterForeground(): Boolean = try {
        val notification = notification(null)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        true
    } catch (e: IllegalStateException) {
        Log.w(TAG, "Couldn't enter the foreground: $e")
        false
    } catch (e: SecurityException) {
        Log.w(TAG, "Couldn't enter the foreground: $e")
        false
    }

    private suspend fun watch() {
        val batch = DownloadBatch()
        val manager = getSystemService(NotificationManager::class.java)
        // A StateFlow: after the delay the collector gets the latest states, not every change in between.
        downloader.states.collect { states ->
            val progress = batch.update(states)
            if (progress == null) {
                stop()
            } else {
                manager.notify(NOTIFICATION_ID, notification(progress))
                delay(UPDATE_MS)
            }
        }
    }

    /** Stops unless a newer start (a new download) arrived meanwhile, which keeps the service in the foreground. */
    private fun stop() {
        if (stopSelfResult(lastStartId)) leave()
    }

    /** Android 15's daily `dataSync` limit: downloads go on only while the app is open. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.w(TAG, "Foreground time limit reached; downloads continue only while the app is open")
        leave()
        stopSelf()
    }

    /** Ends the watcher first, so no update can post a notification that no running service owns. */
    private fun leave() {
        collector?.cancel()
        collector = null
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun notification(progress: BatchProgress?): Notification {
        // The launcher's intent, so the tap brings the app's task back as it was instead of starting a new menu.
        val launch = Intent.makeMainActivity(ComponentName(this, MainActivity::class.java))
        val open = PendingIntent.getActivity(this, 0, launch, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val cancel = PendingIntent.getService(
            this, 0, Intent(this, DownloadService::class.java).setAction(ACTION_CANCEL_ALL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_download)
            .setContentTitle("Downloading levels")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_download), "Cancel all", cancel).build())
        if (progress == null) return builder.setProgress(0, 0, true).build()
        val count = "${progress.done} of ${progress.total}"
        val text = progress.current?.let { "$count · ${downloader.titleOf(it) ?: it.level}" } ?: count
        // The bar is the batch; the running level's bytes fill its share.
        val filled = progress.done * 100 + ((progress.fraction ?: 0f) * 100).toInt()
        return builder.setContentText(text).setProgress(progress.total * 100, filled, false).build()
    }

    companion object {
        private const val TAG = "DownloadService"
        private const val CHANNEL = "downloads"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_CANCEL_ALL = "my.fumui.sairitsu.CANCEL_ALL_DOWNLOADS"
        private const val UPDATE_MS = 1_000L

        /** Starts the service; logs and gives up when Android refuses (the downloads still run while the app is open). */
        fun start(context: Context) {
            try {
                context.startForegroundService(Intent(context, DownloadService::class.java))
            } catch (e: IllegalStateException) {
                Log.w(TAG, "Couldn't start the download service: $e")
            }
        }
    }
}
