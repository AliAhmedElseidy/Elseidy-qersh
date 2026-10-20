package com.example.workers

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.MainActivity
import com.example.R
import com.example.data.local.SaidiQershDatabase
import com.example.data.local.TransactionType
import com.example.data.repository.SaidiQershRepository
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

            // 1. فحص الالتزامات والأقساط والجمعيات المستحقة خلال 3 أيام
            val obligations = dao.observeObligations().first()
            val dueSoon = obligations.filter {
                !it.isCompleted && it.reminderEnabled && (it.dueDateMillis - now) <= threeDaysMillis
            }

            if (dueSoon.isNotEmpty()) {
                val firstDue = dueSoon.first()
                sendLocalNotification(
                    notificationId = 1001,
                    title = "تذكير التزام مستحق - الصعيدي قرش",
                    body = "لديك ${dueSoon.size} التزام/قسط مستحق قريبًا، أبرزها: ${firstDue.title} (${MoneyUtils.formatMinor(firstDue.installmentAmountMinor, firstDue.currency)})",
                    openTab = "planning"
                )
            }

            // 2. فحص تنبيهات الميزانية الشهرية (80% و 100%) للشهر الحالي
            val currentYm = MoneyUtils.currentYearMonth(now)
            val (monthStart, monthEnd) = MoneyUtils.monthRangeMillis(currentYm)
            val confirmedTxs = dao.observeConfirmedTransactions().first()
            val monthTxs = confirmedTxs.filter { it.timestamp in monthStart..monthEnd }
            val netMonthExpense = SaidiQershRepository.computeNetConfirmedExpensesMinor(monthTxs)

            val budgets = dao.getAllBudgets().filter { it.yearMonth == currentYm }
            val totalBudget = budgets.firstOrNull { it.categoryKey == "TOTAL" }
            if (totalBudget != null && totalBudget.limitMinor > 0L) {
                val pct = ((netMonthExpense * 100L) / totalBudget.limitMinor).toInt()
                if (pct >= 100 && totalBudget.alertAt100) {
                    sendLocalNotification(
                        notificationId = 1002,
                        title = "تنبيه تجاوز الميزانية الشهرية (100%)",
                        body = "تجاوزت مصروفات الشهر الميزانية المحددة بنسبة $pct% (${MoneyUtils.formatMinor(netMonthExpense, totalBudget.currency)})",
                        openTab = "planning"
                    )
                } else if (pct >= 80 && totalBudget.alertAt80) {
                    sendLocalNotification(
                        notificationId = 1002,
                        title = "تنبيه اقتراب استهلاك الميزانية (80%)",
                        body = "وصل استهلاك الميزانية الشهرية إلى $pct% (${MoneyUtils.formatMinor(netMonthExpense, totalBudget.currency)} من ${MoneyUtils.formatMinor(totalBudget.limitMinor, totalBudget.currency)})",
                        openTab = "planning"
                    )
                }
            }

            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }

    private fun sendLocalNotification(
        notificationId: Int,
        title: String,
        body: String,
        openTab: String = "planning"
    ) {
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
        val openIntent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("open_tab", openTab)
        }
        val pendingOpen = PendingIntent.getActivity(
            applicationContext,
            notificationId,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(applicationContext, channelId)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingOpen)
            .setAutoCancel(true)
            .build()
        manager.notify(notificationId, notification)
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
