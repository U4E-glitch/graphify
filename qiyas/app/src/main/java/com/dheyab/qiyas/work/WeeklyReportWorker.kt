package com.dheyab.qiyas.work

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.dheyab.qiyas.MainActivity
import com.dheyab.qiyas.R
import com.dheyab.qiyas.data.db.DEFAULT_PROFILE_ID
import com.dheyab.qiyas.data.repo.ReportRepository
import com.dheyab.qiyas.data.settings.SettingsRepository
import com.dheyab.qiyas.domain.report.WeekMath
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.TemporalAdjusters
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first

const val OPEN_REPORT_EXTRA = "open_report"

/**
 * Weekly job (spec §10): fires at week start 09:00 local, generates the
 * just-finished week's report and posts a LOW-importance notification.
 */
@HiltWorker
class WeeklyReportWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val reportRepository: ReportRepository,
    private val settingsRepository: SettingsRepository,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val settings = settingsRepository.settings.first()
        val currentWeekStart = WeekMath.weekStartFor(LocalDate.now(), settings.weekStart)
        reportRepository.generate(DEFAULT_PROFILE_ID, currentWeekStart.minusWeeks(1))
        if (settings.reportNotificationEnabled) {
            postNotification()
        }
        return Result.success()
    }

    private fun postNotification() {
        val context = applicationContext
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.notif_channel_weekly),
                NotificationManager.IMPORTANCE_LOW, // LOW importance, no sound (spec §10)
            )
        )
        val intent = Intent(context, MainActivity::class.java).apply {
            putExtra(OPEN_REPORT_EXTRA, true)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_agenda)
            .setContentTitle(context.getString(R.string.notif_report_ready))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
    }

    companion object {
        private const val CHANNEL_ID = "weekly_report"
        private const val NOTIFICATION_ID = 100
        private const val WORK_NAME = "weekly_report_generation"

        /** (Re)schedules the periodic job for the next week-start 09:00 local. */
        fun schedule(context: Context, weekStartDay: DayOfWeek) {
            val now = LocalDateTime.now()
            var next = LocalDate.now()
                .with(TemporalAdjusters.nextOrSame(weekStartDay))
                .atTime(9, 0)
            if (!next.isAfter(now)) next = next.plusWeeks(1)
            val request = PeriodicWorkRequestBuilder<WeeklyReportWorker>(7, TimeUnit.DAYS)
                .setInitialDelay(Duration.between(now, next).toMillis(), TimeUnit.MILLISECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
        }
    }
}
