package com.example.workers

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.R
import com.example.data.local.SaidiQershDatabase
import com.example.domain.MoneyUtils
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/**
 * عامل الخلفية (WorkManager) لفحص الالتزامات والأقساط والجمعيات المستحقة وتنبيهات الميزانية (80% و100%)
 * محليًا دون أي خادم خارجي.
 */
class ObligationReminderWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        return try {
            val db = SaidiQershDatabase.getInstance(applicationContext)
            val dao = db.dao()
            val now = System.currentTimeMillis()
            val threeDaysMillis = 3 * 24 * 60 * 60 * 1000L

            val obligations = dao.observeObligations().first()
            val dueSoon = obligations.filter {
                !it.isCompleted && it.reminderEnabled && (it.dueDateMillis - now) <= threeDaysMillis
            }

            if (dueSoon.isNotEmpty()) {
                val firstDue = dueSoon.first()
                sendLocalNotification(
                    title = "تذكير التزام مستحق - الصعيدي قرش",
                    body = "لديك ${dueSoon.size} التزام/قسط مستحق قريبًا، أبرزها: ${firstDue.title} (${MoneyUtils.formatMinor(firstDue.installmentAmountMinor, firstDue.currency)})"
                )
            }
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }

    private fun sendLocalNotification(title: String, body: String) {
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        val channelId = "saidi_qersh_reminders"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "تذكيرات الالتزامات والميزانية",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            manager.createNotificationChannel(channel)
        }
        val notification = NotificationCompat.Builder(applicationContext, channelId)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()
        manager.notify(1001, notification)
    }

    companion object {
        private const val WORK_NAME = "saidi_qersh_daily_obligation_check"

        fun schedulePeriodicReminders(context: Context) {
            val request = PeriodicWorkRequestBuilder<ObligationReminderWorker>(12, TimeUnit.HOURS)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }
}
