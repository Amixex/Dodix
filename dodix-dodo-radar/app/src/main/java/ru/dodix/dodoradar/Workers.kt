package ru.dodix.dodoradar

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.BatteryManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.*
import java.util.Calendar
import java.util.concurrent.TimeUnit

object Notifier {
    const val CH_DIGEST = "digest"
    const val CH_WORK = "work"

    fun createChannels(ctx: Context) {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CH_DIGEST, "Сводка Додо Радар", NotificationManager.IMPORTANCE_DEFAULT))
            nm.createNotificationChannel(NotificationChannel(CH_WORK, "Работа в фоне", NotificationManager.IMPORTANCE_LOW))
        }
    }

    private fun openApp(ctx: Context) = PendingIntent.getActivity(
        ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun digest(ctx: Context, picks: Int) {
        val nm = NotificationManagerCompat.from(ctx)
        if (!nm.areNotificationsEnabled()) return
        val text = if (picks > 0) "Сегодня идей для Додо: $picks. Откройте приложение." else "Сегодня ничего подходящего для маркетинга."
        val n = NotificationCompat.Builder(ctx, CH_DIGEST)
            .setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle("🍕 Додо Радар")
            .setContentText(text).setContentIntent(openApp(ctx)).setAutoCancel(true).build()
        runCatching { nm.notify(1, n) }
    }

    fun work(ctx: Context, text: String): Notification =
        NotificationCompat.Builder(ctx, CH_WORK).setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Додо Радар").setContentText(text).setOngoing(true).build()
}

private fun fgInfo(ctx: Context, id: Int, text: String): ForegroundInfo =
    if (Build.VERSION.SDK_INT >= 29)
        ForegroundInfo(id, Notifier.work(ctx, text), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    else ForegroundInfo(id, Notifier.work(ctx, text))

object Power {
    /** Условие автосводки: идёт зарядка или заряд не ниже 30 %. */
    fun ok(ctx: Context): Boolean {
        val i = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return true
        val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, 100) * 100 / i.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
        return charging || level >= 30
    }
}

class RunWorker(ctx: Context, p: WorkerParameters) : CoroutineWorker(ctx, p) {
    override suspend fun getForegroundInfo(): ForegroundInfo = fgInfo(applicationContext, 11, "Готовлю сводку…")

    override suspend fun doWork(): Result {
        val manual = inputData.getBoolean("manual", false)
        if (!manual && !Power.ok(applicationContext)) {
            Log.add(applicationContext, "warn", "Автосводка отложена: заряд ниже 30 % и нет зарядки")
            return Result.retry()
        }
        // Foreground из фона на новых Android может быть запрещён: тогда работаем без него.
        runCatching { setForeground(getForegroundInfo()) }
        val r = Radar.run(applicationContext)
        return if (r.ok || manual) Result.success() else Result.retry()
    }
}

class DownloadWorker(ctx: Context, p: WorkerParameters) : CoroutineWorker(ctx, p) {
    override suspend fun getForegroundInfo(): ForegroundInfo = fgInfo(applicationContext, 12, "Загружаю модель…")

    override suspend fun doWork(): Result {
        runCatching { setForeground(getForegroundInfo()) }
        return try {
            ModelDownloader.download(applicationContext, Prefs(applicationContext)) { isStopped.not() }
            Result.success()
        } catch (e: Exception) {
            if (DownloadState.state.value.status != "paused") {
                DownloadState.state.value = DlState("error", 0, 0, e.message ?: e.toString())
            }
            Log.add(applicationContext, "error", "Загрузка модели: ${e.message}")
            Result.failure()
        }
    }
}

object Scheduler {
    fun scheduleDaily(ctx: Context) {
        val wm = WorkManager.getInstance(ctx)
        val prefs = Prefs(ctx)
        if (!prefs.dailyEnabled) { wm.cancelUniqueWork("daily"); return }
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, prefs.hour); set(Calendar.MINUTE, prefs.minute); set(Calendar.SECOND, 0)
            if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR, 1)
        }
        val delay = cal.timeInMillis - System.currentTimeMillis()
        val req = PeriodicWorkRequestBuilder<RunWorker>(24, TimeUnit.HOURS)
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.LINEAR, 15, TimeUnit.MINUTES)
            .build()
        wm.enqueueUniquePeriodicWork("daily", ExistingPeriodicWorkPolicy.UPDATE, req)
    }

    fun runNow(ctx: Context) {
        val req = OneTimeWorkRequestBuilder<RunWorker>()
            .setInputData(workDataOf("manual" to true))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(ctx).enqueueUniqueWork("manual", ExistingWorkPolicy.KEEP, req)
    }

    fun download(ctx: Context) {
        DownloadState.state.value = DlState("running", 0, 0, "Запуск…")
        val req = OneTimeWorkRequestBuilder<DownloadWorker>().build()
        WorkManager.getInstance(ctx).enqueueUniqueWork("model", ExistingWorkPolicy.REPLACE, req)
    }

    fun cancelDownload(ctx: Context) = WorkManager.getInstance(ctx).cancelUniqueWork("model")
}
