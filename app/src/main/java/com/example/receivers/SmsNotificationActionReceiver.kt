package com.example.receivers

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.data.local.ImportAuditLogEntity
import com.example.data.local.ReviewStatus
import com.example.data.local.SaidiQershDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * مستقبل داخلي مستقل غير مُصدّر (exported = false) لمعالجة أزرار الإشعارات التفاعلية (أضف | تجاهل)
 * دون تعارض مع صلاحية النظام android.permission.BROADCAST_SMS الخاصة بمستقبل الرسائل النصية.
 */
class SmsNotificationActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        val suggestedId = intent.getLongExtra(BankSmsBroadcastReceiver.EXTRA_SUGGESTED_ID, -1L)
        val notificationId = intent.getIntExtra(BankSmsBroadcastReceiver.EXTRA_NOTIFICATION_ID, -1)
        if (suggestedId <= 0L) return

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                handleNotificationAction(
                    context = context.applicationContext,
                    action = action,
                    suggestedId = suggestedId,
                    notificationId = notificationId
                )
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        suspend fun handleNotificationAction(
            context: Context,
            action: String,
            suggestedId: Long,
            notificationId: Int
        ) {
            val db = SaidiQershDatabase.getInstance(context)
            val dao = db.dao()
            val item = dao.getSuggestedById(suggestedId) ?: return

            // عزل قاطع لعينات الاختبار (Sandbox Isolation)
            if (item.isTestSandbox) {
                dismissNotification(context, notificationId)
                return
            }

            when (action) {
                BankSmsBroadcastReceiver.ACTION_APPROVE_SMS_NOTIFICATION -> {
                    // لا يتم الاعتماد التلقائي من الإشعار إلا إذا كان الحساب مطابقاً ومحدداً
                    if (item.suggestedAccountId != null) {
                        dao.confirmSuggestedTransaction(suggestedId)
                        dismissNotification(context, notificationId)
                    }
                }

                BankSmsBroadcastReceiver.ACTION_IGNORE_SMS_NOTIFICATION -> {
                    if (item.status != ReviewStatus.CONFIRMED) {
                        val now = System.currentTimeMillis()
                        dao.updateSuggestedTransaction(
                            item.copy(status = ReviewStatus.IGNORED, reviewedAt = now)
                        )
                        dao.insertAuditLog(
                            ImportAuditLogEntity(
                                eventType = "SMS_IGNORED_FROM_NOTIFICATION",
                                entityType = "SuggestedTransaction",
                                entityId = item.id,
                                sourceDescription = item.bankName,
                                details = "تم تجاهل الرسالة من إشعار النظام مباشرة: ${item.merchantName}",
                                reviewStatus = ReviewStatus.IGNORED.name,
                                timestamp = now
                            )
                        )
                    }
                    dismissNotification(context, notificationId)
                }
            }
        }

        private fun dismissNotification(context: Context, notificationId: Int) {
            if (notificationId <= 0) return
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            nm?.cancel(notificationId)
        }
    }
}
