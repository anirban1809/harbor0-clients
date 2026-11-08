package app.harbor0.android

import android.app.ActivityManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.work.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeUnit

/** A backup or sync on this phone with work under way, for the "Backing up" / "Syncing" notification. */
data class BackgroundWork(val rootId: String, val name: String, val done: Int, val total: Int, val photos: Boolean, val sync: Boolean = false) {
    val title get() = if (sync) "Syncing $name" else if (photos) "Backing up photos" else "Backing up $name"
    val detail get() = when {
        sync -> "${"%,d".format(total)} ${if (total == 1) "file" else "files"} left"
        total > 0 -> "Saved ${"%,d".format(done)} of ${"%,d".format(total)} files"
        else -> "Getting ready…"
    }
}

/** A sync this big keeps the service going when the app is left; smaller ones finish in the app's own time or the next pass. */
const val LARGE_SYNC_FILES = 20
const val LARGE_SYNC_BYTES = 20L * 1024 * 1024

/**
 * What this phone is working on, or null when nothing would move. A backup comes first: its run is open, and nothing
 * makes it wait (paused, locked, on mobile data, or out of storage). Otherwise a sync folder with at least [minSync]
 * files to transfer (or one large file under way) that is actually syncing, not paused, offline or waiting on the
 * person. The foreground service only runs while this is not null.
 */
fun backgroundWork(view: SyncView?, minSync: Int = 1): BackgroundWork? {
    val runtime = view?.runtime ?: return null
    if (runtime.locked) return null
    if (!runtime.paused && !storageFullNow(runtime.storageFull, runtime.storage)) view.roots.asSequence()
        .filter { it.mode == "backup" && !it.paused && it.archive == null && it.id !in runtime.waitingForWifi }
        .mapNotNull { root -> runtime.backupRuns[root.id]?.takeIf { it.done < it.total }?.let { BackgroundWork(root.id, root.localName, it.done, it.total, root.isPhotos) } }
        .firstOrNull()?.let { return it }
    return view.roots.asSequence()
        .filter { it.mode == "sync" && folderState(it, runtime, view.jobs) == "Syncing" }
        .mapNotNull { root ->
            val left = view.jobs.count { it.rootId == root.id }
            val big = runtime.active?.takeIf { it.rootId == root.id }?.let { it.total >= LARGE_SYNC_BYTES } == true
            if (left >= minSync || big) BackgroundWork(root.id, root.localName, 0, maxOf(left, 1), photos = false, sync = true) else null
        }
        .firstOrNull()
}

/**
 * Keeps a backup or a large sync going after the app is left, as a foreground service with a "Backing up" or "Syncing"
 * notification. The engine does the work in this process; the service only keeps the process running (Samsung and
 * others freeze it otherwise) and shows progress. It ends as soon as nothing is left to move, or everything that's left
 * is waiting.
 */
class BackupWorker(context: Context, params: WorkerParameters): CoroutineWorker(context, params) {
    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo(applicationContext, backgroundWork(Harbor.sync(applicationContext).view.value))

    override suspend fun doWork(): Result {
        val api = Harbor.api(applicationContext)
        val controller = Harbor.sync(applicationContext)
        return try {
            if (!api.signedIn.value) api.restore()
            if (!api.signedIn.value) return Result.success()
            if (controller.vault.locked) controller.vault.restore(api.e2eeApi())
            val engine = controller.connect(api.get<Account>("/v1/users/me").user.id)
            // Allowed while the app is on screen; from the background Android may refuse, and then this is an ordinary
            // background pass, limited to about ten minutes.
            val foreground = try { setForeground(getForegroundInfo()); true } catch (e: CancellationException) { throw e } catch (_: Exception) { false }
            val until = System.currentTimeMillis() + when {
                !foreground -> BACKGROUND_PASS_MS
                Build.VERSION.SDK_INT >= 35 -> MAX_FOREGROUND_MS
                // Only Android 15 caps data transfer services; earlier versions keep going until the backup is done.
                else -> Long.MAX_VALUE / 2
            }
            var idleSince = 0L
            while (System.currentTimeMillis() < until) {
                val work = backgroundWork(controller.view.value)
                if (work == null) {
                    // A run can close and the next open a moment later (Back up now, new photos): wait briefly first.
                    if (idleSince == 0L) idleSince = System.currentTimeMillis()
                    if (System.currentTimeMillis() - idleSince > IDLE_MS) break
                } else {
                    idleSince = 0L
                    if (foreground) runCatching { setForeground(foregroundInfo(applicationContext, work)) }
                }
                delay(2_000)
            }
            // The process may end soon after; what it reported goes now.
            withTimeoutOrNull(10_000) { engine.flushReports() }
            Result.success()
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            if (e is ApiException && (e.status == 401 || e.code == "DEVICE_REVOKED")) Result.success() else Result.retry()
        }
    }

    companion object {
        const val WORK = "backup-foreground"
        private const val CHANNEL = "backups"
        private const val NOTIFICATION = 7
        // Android 15 stops data transfer services after six hours a day; the next start carries on from the journal.
        private const val MAX_FOREGROUND_MS = 6 * 3600_000L - 5 * 60_000L
        private const val BACKGROUND_PASS_MS = 9 * 60_000L
        private const val IDLE_MS = 30_000L

        /** Starts the service if a backup or large sync is under way and it isn't running yet. Best done while the app is on screen. */
        fun start(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(WORK, ExistingWorkPolicy.KEEP, OneTimeWorkRequestBuilder<BackupWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES).build())
        }
        fun stop(context: Context) { WorkManager.getInstance(context).cancelUniqueWork(WORK) }

        fun foregroundInfo(context: Context, work: BackgroundWork?): ForegroundInfo {
            val manager = context.getSystemService(NotificationManager::class.java)
            // Created, or renamed back from "Sync and backups" now that phones only back up photos.
            if (Build.VERSION.SDK_INT >= 26)
                manager.createNotificationChannel(NotificationChannel(CHANNEL, "Backups", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shown while photos are backed up in the background."; setShowBadge(false)
                })
            val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val notification = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(if (work?.sync == true) android.R.drawable.stat_notify_sync else android.R.drawable.stat_sys_upload).setContentTitle(work?.title ?: "Backing up")
                .setContentText(work?.detail ?: "Getting ready…").setContentIntent(open)
                .setOngoing(true).setOnlyAlertOnce(true).setSilent(true).setCategory(NotificationCompat.CATEGORY_PROGRESS)
                .apply { if (work != null && work.total > 0 && !work.sync) setProgress(work.total, work.done, false) else setProgress(0, 0, true) }
                .build()
            return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else ForegroundInfo(NOTIFICATION, notification)
        }

        /**
         * Android's battery optimization still applies to harbor0. Many phones then stop its background work soon after
         * the app is closed, and Android won't let a background pass start the "Backing up" service.
         */
        fun batteryOptimized(context: Context) =
            context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == false

        /** Android's own "Let app always run in background?" request, or its battery list where a phone doesn't offer it. */
        fun allowBackgroundIntents(context: Context): List<Intent> {
            val app = Uri.fromParts("package", context.packageName, null)
            return listOf(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, app), Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, app)).filter { it.resolveActivity(context.packageManager) != null }
        }

        // Pages some phones add for apps allowed to start on their own. Without it, closing the app from recent apps
        // stops its scheduled work until it is opened again. Listed in <queries> in the manifest.
        private val AUTO_START = listOf(
            "com.miui.securitycenter" to "com.miui.permcenter.autostart.AutoStartManagementActivity",
            "com.coloros.safecenter" to "com.coloros.safecenter.permission.startup.StartupAppListActivity",
            "com.coloros.safecenter" to "com.coloros.safecenter.startupapp.StartupAppListActivity",
            "com.oplus.safecenter" to "com.oplus.safecenter.permission.startup.StartupAppListActivity",
            "com.vivo.permissionmanager" to "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
            "com.iqoo.secure" to "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager",
            "com.huawei.systemmanager" to "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
            "com.hihonor.systemmanager" to "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
            "com.asus.mobilemanager" to "com.asus.mobilemanager.autostart.AutoStartActivity",
        )
        /** This phone's page for apps that may start on their own, or null on phones without one. */
        fun autoStartPage(context: Context): Intent? = AUTO_START.asSequence()
            .map { (pkg, page) -> Intent().setComponent(ComponentName(pkg, page)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            .firstOrNull { runCatching { it.resolveActivity(context.packageManager)?.let { found -> context.packageManager.getActivityInfo(found, 0).exported } }.getOrNull() == true }

        /** The person (or a battery saver) stopped harbor0 from working in the background. */
        fun backgroundRestricted(context: Context): Boolean {
            if (Build.VERSION.SDK_INT >= 28 && context.getSystemService(ActivityManager::class.java)?.isBackgroundRestricted == true) return true
            // Samsung's "sleeping apps" and Android's restricted bucket: background work hardly ever runs.
            return Build.VERSION.SDK_INT >= 30 && context.getSystemService(android.app.usage.UsageStatsManager::class.java)
                ?.appStandbyBucket == android.app.usage.UsageStatsManager.STANDBY_BUCKET_RESTRICTED
        }
    }
}
