package app.harbor0.android

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Keeps files picked for upload going after the app is left, as a foreground service with an "Uploading" notification,
 * the way [BackupWorker] keeps backups going. The queue (Harbor.uploads) does the work in this process; the service
 * keeps the process running and shows progress. When Android ended the app, WorkManager runs this again and the saved
 * queue carries on from its next file.
 */
class UploadWorker(context: Context, params: WorkerParameters): CoroutineWorker(context, params) {
    override suspend fun getForegroundInfo(): ForegroundInfo =
        foregroundInfo(applicationContext, withContext(Dispatchers.Main) { uploadNotice(Harbor.uploads(applicationContext).entries) })

    override suspend fun doWork(): Result {
        val api = Harbor.api(applicationContext)
        val queue = withContext(Dispatchers.Main) { Harbor.uploads(applicationContext) }
        return try {
            if (!api.signedIn.value) api.restore()
            if (!api.signedIn.value) { withContext(Dispatchers.Main) { queue.clear() }; return Result.success() }
            // An encrypted account's files are sealed on this phone: without its keys nothing can upload until the app is unlocked.
            val vault = Harbor.vault(applicationContext)
            if (vault.locked) vault.restore(api.e2eeApi())
            if (vault.locked) return Result.success()
            withContext(Dispatchers.Main) { queue.start() }
            // Allowed while the app is on screen; from the background Android may refuse, and then this is an ordinary
            // background pass, limited to about ten minutes.
            val foreground = try { setForeground(getForegroundInfo()); true } catch (e: CancellationException) { throw e } catch (_: Exception) { false }
            val until = System.currentTimeMillis() + when {
                !foreground -> BACKGROUND_PASS_MS
                Build.VERSION.SDK_INT >= 35 -> MAX_FOREGROUND_MS
                else -> Long.MAX_VALUE / 2
            }
            var shown: UploadNotice? = null
            while (System.currentTimeMillis() < until) {
                val notice = withContext(Dispatchers.Main) { if (queue.active) uploadNotice(queue.entries) else null } ?: break
                if (foreground && notice != shown) { shown = notice; runCatching { setForeground(foregroundInfo(applicationContext, notice)) } }
                delay(1_000)
            }
            // Out of time with files left: WorkManager runs this again shortly.
            if (withContext(Dispatchers.Main) { queue.active }) Result.retry() else Result.success()
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            if (e is ApiException && (e.status == 401 || e.code == "DEVICE_REVOKED")) Result.success() else Result.retry()
        }
    }

    companion object {
        const val WORK = "upload-foreground"
        private const val CHANNEL = "uploads"
        private const val NOTIFICATION = 8
        // Android 15 stops data transfer services after six hours a day.
        private const val MAX_FOREGROUND_MS = 6 * 3600_000L - 5 * 60_000L
        private const val BACKGROUND_PASS_MS = 9 * 60_000L

        /** Starts the service if it isn't running yet. Best done while the app is on screen, when Android allows it. */
        fun start(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(WORK, ExistingWorkPolicy.KEEP, OneTimeWorkRequestBuilder<UploadWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES).build())
        }

        fun foregroundInfo(context: Context, notice: UploadNotice?): ForegroundInfo {
            val manager = context.getSystemService(NotificationManager::class.java)
            if (Build.VERSION.SDK_INT >= 26)
                manager.createNotificationChannel(NotificationChannel(CHANNEL, "Uploads", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shown while files you chose upload in the background."; setShowBadge(false)
                })
            val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val notification = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_upload).setContentTitle(notice?.title ?: "Uploading")
                .setContentText(notice?.detail ?: "Getting ready…").setContentIntent(open)
                .setOngoing(true).setOnlyAlertOnce(true).setSilent(true).setCategory(NotificationCompat.CATEGORY_PROGRESS)
                .apply { if (notice != null && notice.total > 0) setProgress(notice.total, notice.loaded, false) else setProgress(0, 0, true) }
                .build()
            return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else ForegroundInfo(NOTIFICATION, notification)
        }
    }
}
