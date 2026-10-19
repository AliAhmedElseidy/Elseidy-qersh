package com.example.receivers

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Telephony
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.data.local.BankEntity
import com.example.data.local.ImportAuditLogEntity
import com.example.data.local.ReviewStatus
import com.example.data.local.SaidiQershDao
import com.example.data.local.SaidiQershDatabase
import com.example.data.local.SmsSuggestedType
import com.example.data.local.SuggestedTransactionEntity
import com.example.domain.BankSmsParserEngine
import com.example.domain.MoneyUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * مستقبل الرسائل البنكية الفعلي (Android SMS BroadcastReceiver) + معالج أزرار الإشعارات التفاعلية:
 * - يعمل تلقائياً في الخلفية حتى والتطبيق مغلق فور وصول أي رسالة SMS جديدة.
 * - يكتشف اسم المرسل الفعلي ديناميكياً ويضيفه لقائمة "إدارة جهات الرسائل المسموحة".
 * - يطبق قواعد المرسل:
 *   1) إذا كانت قراءة رسائل الجهة معطلة -> يتجاهلها تماماً.
 *   2) إذا كان "الاعتماد التلقائي الفوري (Auto-Approve)" مفعلاً -> يسجل العملية فوراً في الحساب ويرسل إشعار:
 *      "✅ تم تسجيل مصروف تلقائياً: [المبلغ] من [اسم الجهة] لدى [التاجر]"
 *   3) إذا كانت تحتاج مراجعة -> يضعها في قائمة المراجعة ويرسل إشعاراً تفاعلياً مع أزرار (أضف | تجاهل).
 */
class BankSmsBroadcastReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return

        when (action) {
            Telephony.Sms.Intents.SMS_RECEIVED_ACTION -> {
                val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
                if (messages.isNullOrEmpty()) return

                val sender = messages.firstOrNull()?.displayOriginatingAddress
                    ?: messages.firstOrNull()?.originatingAddress
                    ?: "Unknown"
                val timestamp = messages.firstOrNull()?.timestampMillis ?: System.currentTimeMillis()
                val combinedBody = messages.joinToString(separator = "") { it.displayMessageBody ?: "" }
                if (combinedBody.isBlank()) return

                val pendingResult = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        processIncomingSmsLocally(
                            context = context.applicationContext,
                            senderAddress = sender,
                            body = combinedBody,
                            smsTimestamp = timestamp,
                            isTestSandbox = false,
                            sendSystemNotification = true
                        )
                    } finally {
                        pendingResult.finish()
                    }
                }
            }

            ACTION_APPROVE_SMS_NOTIFICATION -> {
                val suggestedId = intent.getLongExtra(EXTRA_SUGGESTED_ID, -1L)
                val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, -1)
                if (suggestedId <= 0L) return

                val pendingResult = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val db = SaidiQershDatabase.getInstance(context.applicationContext)
                        val dao = db.dao()
                        dao.confirmSuggestedTransaction(suggestedId)
                        dismissNotification(context.applicationContext, notificationId)
                    } finally {
                        pendingResult.finish()
                    }
                }
            }

            ACTION_IGNORE_SMS_NOTIFICATION -> {
                val suggestedId = intent.getLongExtra(EXTRA_SUGGESTED_ID, -1L)
                val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, -1)
                if (suggestedId <= 0L) return

                val pendingResult = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val db = SaidiQershDatabase.getInstance(context.applicationContext)
                        val dao = db.dao()
                        val item = dao.getSuggestedById(suggestedId)
                        if (item != null && item.status != ReviewStatus.CONFIRMED) {
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
                        dismissNotification(context.applicationContext, notificationId)
                    } finally {
                        pendingResult.finish()
                    }
                }
            }
        }
    }

    companion object {
        const val CHANNEL_ID_BANK_SMS = "bank_sms_operations_channel"
        const val CHANNEL_NAME_BANK_SMS = "إشعارات العمليات البنكية"

        const val ACTION_APPROVE_SMS_NOTIFICATION = "com.example.action.APPROVE_SMS_NOTIFICATION"
        const val ACTION_IGNORE_SMS_NOTIFICATION = "com.example.action.IGNORE_SMS_NOTIFICATION"
        const val EXTRA_SUGGESTED_ID = "extra_suggested_id"
        const val EXTRA_NOTIFICATION_ID = "extra_notification_id"
        const val EXTRA_OPEN_SMS_REVIEW = "extra_open_sms_review"

        /**
         * يسجل جهة المرسل تلقائياً في جدول الجهات (banks) إذا كانت جهة مالية جديدة غير مسجلة مسبقاً،
         * حتى تظهر للمستخدم في شاشة "إدارة جهات الرسائل المسموحة" مع مفتاحي التحكم.
         */
        suspend fun ensureDynamicSenderRegistered(
            dao: SaidiQershDao,
            senderAddress: String,
            resolvedBankName: String,
            currency: String,
            matchedBankId: Long?
        ): BankEntity? {
            if (matchedBankId != null) {
                return dao.getBankById(matchedBankId)
            }
            val cleanSender = senderAddress.trim()
            if (cleanSender.isEmpty() || cleanSender.equals("Unknown", ignoreCase = true) || cleanSender.equals("Receipt", ignoreCase = true)) {
                return null
            }

            val allBanks = dao.getAllBanks()
            val existing = allBanks.firstOrNull { bank ->
                bank.code.equals(cleanSender, ignoreCase = true) ||
                    bank.nameAr.equals(resolvedBankName, ignoreCase = true) ||
                    bank.senderIdsCsv.split(",").any { it.trim().equals(cleanSender, ignoreCase = true) }
            }
            if (existing != null) return existing

            val safeCode = "SENDER_" + cleanSender.uppercase(Locale.ROOT)
                .replace(Regex("[^A-Z0-9_]"), "_")
                .take(18)
            val newBank = BankEntity(
                nameAr = if (resolvedBankName.isNotBlank() && resolvedBankName != cleanSender) {
                    resolvedBankName
                } else {
                    cleanSender
                },
                nameEn = cleanSender,
                code = safeCode,
                country = if (currency == "EGP") "EG" else "SA",
                defaultCurrency = currency,
                senderIdsCsv = cleanSender,
                smsEnabled = true,
                autoApproveEnabled = false
            )
            val newId = dao.insertBank(newBank)
            return if (newId > 0L) newBank.copy(id = newId) else dao.getAllBanks().firstOrNull { it.code == safeCode }
        }

        suspend fun processIncomingSmsLocally(
            context: Context,
            senderAddress: String,
            body: String,
            smsTimestamp: Long = System.currentTimeMillis(),
            isTestSandbox: Boolean = false,
            sendSystemNotification: Boolean = false
        ): Long? {
            val db = SaidiQershDatabase.getInstance(context)
            val dao = db.dao()
            SaidiQershDatabase.seedInitialDataIfEmpty(dao)

            val banks = dao.getAllBanks()
            val rules = dao.getActiveSmsRules()
            val categories = dao.getAllCategories()
            val accounts = dao.getAllAccounts()

            val parsed = BankSmsParserEngine.parseSms(
                senderAddress = senderAddress,
                rawBody = body,
                smsTimestamp = smsTimestamp,
                knownBanks = banks,
                customRules = rules,
                categories = categories,
                accounts = accounts
            )

            // استبعاد الرسائل غير المالية (تفعيل مستفيد، OTP، تسجيل دخول، إشعار رصيد) تلقائياً
            if (parsed.suggestedType == SmsSuggestedType.BALANCE_NOTICE_ONLY || parsed.reviewStatus == ReviewStatus.IGNORED) {
                dao.insertAuditLog(
                    ImportAuditLogEntity(
                        eventType = "SMS_AUTO_IGNORED",
                        entityType = "SMS",
                        sourceDescription = parsed.bankName,
                        details = "تم استبعاد رسالة غير مالية تلقائياً: ${parsed.sanitizedSnippet}",
                        reviewStatus = ReviewStatus.IGNORED.name
                    )
                )
                return null
            }

            // التأكد من تسجيل جهة المرسل ديناميكياً في قائمة الجهات المسموحة
            val senderBank = ensureDynamicSenderRegistered(
                dao = dao,
                senderAddress = parsed.senderAddress,
                resolvedBankName = parsed.bankName,
                currency = parsed.currency,
                matchedBankId = parsed.matchedBankId
            )

            val isSenderEnabled = senderBank?.smsEnabled ?: parsed.isBankSmsEnabled
            val isAutoApprove = senderBank?.autoApproveEnabled ?: parsed.isAutoApproveEnabled

            // Toggle 1: إذا عطل المستخدم "قراءة رسائل هذه الجهة"، يتجاهل التطبيق رسائلها تماماً
            if (!isSenderEnabled && !isTestSandbox) {
                return null
            }

            // منع التكرار عبر بصمة الرسالة أو رقم المرجع لنفس الجهة
            val existingByHash = dao.getSuggestedByHash(parsed.messageHash)
            if (existingByHash != null) {
                return null
            }
            val existingByRef = parsed.referenceNumber?.let { ref ->
                dao.getSuggestedByRefAndBank(ref, parsed.bankName)
            }

            val matchedAccount = parsed.matchedAccountId?.let { id -> accounts.firstOrNull { it.id == id } }
                ?: accounts.firstOrNull {
                    it.smsParsingEnabled && (senderBank != null && it.bankId == senderBank.id)
                }
                ?: accounts.firstOrNull { it.smsParsingEnabled }
                ?: accounts.firstOrNull()

            val finalStatus = if (existingByRef != null) {
                ReviewStatus.DUPLICATE
            } else {
                parsed.reviewStatus
            }

            val entity = SuggestedTransactionEntity(
                messageHash = parsed.messageHash,
                referenceNumber = parsed.referenceNumber,
                senderAddress = parsed.senderAddress,
                bankId = senderBank?.id ?: parsed.matchedBankId,
                bankName = parsed.bankName,
                sanitizedSnippet = parsed.sanitizedSnippet,
                extractedAmountMinor = parsed.extractedAmountMinor,
                currency = parsed.currency,
                merchantName = parsed.merchantName,
                suggestedType = parsed.suggestedType,
                suggestedCategoryId = parsed.suggestedCategoryId,
                suggestedCategoryName = parsed.suggestedCategoryName,
                suggestedAccountId = matchedAccount?.id,
                extractedAccountLast4 = parsed.extractedAccountLast4,
                extractedCardLast4 = parsed.extractedCardLast4,
                status = finalStatus,
                isTestSandbox = isTestSandbox,
                smsTimestamp = parsed.smsTimestamp,
                importedAt = System.currentTimeMillis()
            )

            val insertedId = dao.insertSuggestedTransaction(entity)
            if (insertedId <= 0L) return null

            // Toggle 2: إذا كان "اعتماد وتسجيل تلقائي فوري (Auto-Approve)" مفعلاً لهذه الجهة
            if (isAutoApprove &&
                finalStatus == ReviewStatus.PENDING &&
                parsed.extractedAmountMinor != null &&
                parsed.extractedAmountMinor > 0L
            ) {
                val confirmedTxId = dao.confirmSuggestedTransaction(insertedId)
                if (confirmedTxId != null && sendSystemNotification) {
                    postAutoApprovedNotification(
                        context = context,
                        suggestedId = insertedId,
                        bankName = parsed.bankName,
                        amountMinor = parsed.extractedAmountMinor,
                        currency = parsed.currency,
                        merchantName = parsed.merchantName.ifBlank { parsed.suggestedType.titleAr }
                    )
                }
                return insertedId
            }

            dao.insertAuditLog(
                ImportAuditLogEntity(
                    eventType = "SMS_IMPORT",
                    entityType = "SuggestedTransaction",
                    entityId = insertedId,
                    sourceDescription = "رسالة ${parsed.bankName} (${parsed.senderAddress})",
                    details = "نوع مقترح: ${parsed.suggestedType.titleAr} | حالة المراجعة: ${finalStatus.titleAr}",
                    reviewStatus = finalStatus.name
                )
            )

            if (sendSystemNotification && (finalStatus == ReviewStatus.PENDING || finalStatus == ReviewStatus.UNCLEAR)) {
                postReviewRequiredNotification(
                    context = context,
                    suggestedId = insertedId,
                    bankName = parsed.bankName,
                    amountMinor = parsed.extractedAmountMinor,
                    currency = parsed.currency,
                    merchantName = parsed.merchantName.ifBlank { parsed.suggestedType.titleAr }
                )
            }

            return insertedId
        }

        /**
         * يفحص صندوق الوارد للشهر المحدد فقط (Monthly SMS Scope)
         * ولا يقوم بتحميل رسائل الشهور القديمة إلا إذا غيّر المستخدم الشهر من الأعلى.
         */
        suspend fun scanDeviceInboxForBankSms(
            context: Context,
            yearMonth: String = MoneyUtils.currentYearMonth(),
            maxMessages: Int = 250
        ): Int {
            var importedCount = 0
            val (monthStart, monthEnd) = MoneyUtils.monthRangeMillis(yearMonth)
            val resolver = context.contentResolver
            val selection = "${Telephony.Sms.Inbox.DATE} >= ? AND ${Telephony.Sms.Inbox.DATE} <= ?"
            val selectionArgs = arrayOf(monthStart.toString(), monthEnd.toString())

            val cursor = resolver.query(
                Telephony.Sms.Inbox.CONTENT_URI,
                arrayOf(
                    Telephony.Sms.Inbox.ADDRESS,
                    Telephony.Sms.Inbox.BODY,
                    Telephony.Sms.Inbox.DATE
                ),
                selection,
                selectionArgs,
                "${Telephony.Sms.Inbox.DATE} DESC LIMIT $maxMessages"
            )
            cursor?.use { c ->
                val addrIdx = c.getColumnIndex(Telephony.Sms.Inbox.ADDRESS)
                val bodyIdx = c.getColumnIndex(Telephony.Sms.Inbox.BODY)
                val dateIdx = c.getColumnIndex(Telephony.Sms.Inbox.DATE)
                while (c.moveToNext()) {
                    val address = if (addrIdx >= 0) c.getString(addrIdx) ?: "" else ""
                    val body = if (bodyIdx >= 0) c.getString(bodyIdx) ?: "" else ""
                    val date = if (dateIdx >= 0) c.getLong(dateIdx) else System.currentTimeMillis()
                    if (body.isNotBlank() && date in monthStart..monthEnd) {
                        val id = processIncomingSmsLocally(
                            context = context,
                            senderAddress = address,
                            body = body,
                            smsTimestamp = date,
                            isTestSandbox = false,
                            sendSystemNotification = false
                        )
                        if (id != null && id > 0L) {
                            importedCount++
                        }
                    }
                }
            }
            return importedCount
        }

        fun ensureBankSmsNotificationChannel(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
                val channel = NotificationChannel(
                    CHANNEL_ID_BANK_SMS,
                    CHANNEL_NAME_BANK_SMS,
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "إشعارات فورية للعمليات البنكية والرسائل المالية المعتمدة أو التي تنتظر المراجعة"
                }
                nm.createNotificationChannel(channel)
            }
        }

        private fun canPostNotifications(context: Context): Boolean {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            } else {
                true
            }
        }

        /**
         * إشعار الاعتماد التلقائي الفوري:
         * "✅ تم تسجيل مصروف تلقائياً: [المبلغ] من [اسم الجهة] لدى [التاجر]"
         */
        fun postAutoApprovedNotification(
            context: Context,
            suggestedId: Long,
            bankName: String,
            amountMinor: Long,
            currency: String,
            merchantName: String
        ) {
            ensureBankSmsNotificationChannel(context)
            if (!canPostNotifications(context)) return

            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            val notificationId = (20000 + (suggestedId % 10000)).toInt()
            val formattedAmount = MoneyUtils.formatMinorTwoDecimals(amountMinor, currency)
            val messageText = "✅ تم تسجيل مصروف تلقائياً: $formattedAmount من $bankName لدى $merchantName"

            val openIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(EXTRA_OPEN_SMS_REVIEW, true)
            }
            val contentPendingIntent = PendingIntent.getActivity(
                context,
                notificationId,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notification = NotificationCompat.Builder(context, CHANNEL_ID_BANK_SMS)
                .setSmallIcon(android.R.drawable.stat_notify_more)
                .setContentTitle("الصعيدي قرش • اعتماد تلقائي فوري")
                .setContentText(messageText)
                .setStyle(NotificationCompat.BigTextStyle().bigText(messageText))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(contentPendingIntent)
                .build()

            nm.notify(notificationId, notification)
        }

        /**
         * إشعار الرسالة التي تحتاج مراجعة مع أزرار سريعة (أضف | تجاهل):
         * "📩 رسالة مالية جديدة من [اسم الجهة] بمبلغ [المبلغ] - اضغط للمراجعة أو الاعتماد"
         */
        fun postReviewRequiredNotification(
            context: Context,
            suggestedId: Long,
            bankName: String,
            amountMinor: Long?,
            currency: String,
            merchantName: String
        ) {
            ensureBankSmsNotificationChannel(context)
            if (!canPostNotifications(context)) return

            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            val notificationId = (10000 + (suggestedId % 10000)).toInt()
            val formattedAmount = amountMinor?.let { MoneyUtils.formatMinorTwoDecimals(it, currency) } ?: "غير محدد"
            val messageText = "📩 رسالة مالية جديدة من $bankName بمبلغ $formattedAmount - اضغط للمراجعة أو الاعتماد"

            val openIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(EXTRA_OPEN_SMS_REVIEW, true)
            }
            val contentPendingIntent = PendingIntent.getActivity(
                context,
                notificationId,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val approveIntent = Intent(context, BankSmsBroadcastReceiver::class.java).apply {
                action = ACTION_APPROVE_SMS_NOTIFICATION
                putExtra(EXTRA_SUGGESTED_ID, suggestedId)
                putExtra(EXTRA_NOTIFICATION_ID, notificationId)
            }
            val approvePendingIntent = PendingIntent.getBroadcast(
                context,
                notificationId + 1,
                approveIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val ignoreIntent = Intent(context, BankSmsBroadcastReceiver::class.java).apply {
                action = ACTION_IGNORE_SMS_NOTIFICATION
                putExtra(EXTRA_SUGGESTED_ID, suggestedId)
                putExtra(EXTRA_NOTIFICATION_ID, notificationId)
            }
            val ignorePendingIntent = PendingIntent.getBroadcast(
                context,
                notificationId + 2,
                ignoreIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val builder = NotificationCompat.Builder(context, CHANNEL_ID_BANK_SMS)
                .setSmallIcon(android.R.drawable.stat_notify_chat)
                .setContentTitle("الصعيدي قرش • $bankName ($merchantName)")
                .setContentText(messageText)
                .setStyle(NotificationCompat.BigTextStyle().bigText(messageText))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(contentPendingIntent)

            if (amountMinor != null && amountMinor > 0L) {
                builder.addAction(
                    android.R.drawable.ic_input_add,
                    "أضف",
                    approvePendingIntent
                )
            }
            builder.addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "تجاهل",
                ignorePendingIntent
            )

            nm.notify(notificationId, builder.build())
        }

        private fun dismissNotification(context: Context, notificationId: Int) {
            if (notificationId <= 0) return
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            nm?.cancel(notificationId)
        }
    }
}
