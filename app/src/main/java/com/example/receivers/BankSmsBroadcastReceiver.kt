package com.example.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import com.example.data.local.ImportAuditLogEntity
import com.example.data.local.ReviewStatus
import com.example.data.local.SaidiQershDatabase
import com.example.data.local.SmsSuggestedType
import com.example.data.local.SuggestedTransactionEntity
import com.example.domain.BankSmsParserEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * مستقبل الرسائل البنكية الفعلي (Android SMS BroadcastReceiver)
 * يعمل محليًا بالكامل عند وصول رسالة جديدة، ويحولها إلى مقترح بحالة Pending للمراجعة
 * دون المساس بالأرصدة المؤكدة حتى يضغط المستخدم على «تأكيد وتسجيل».
 */
class BankSmsBroadcastReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (messages.isNullOrEmpty()) return

        val sender = messages.firstOrNull()?.displayOriginatingAddress ?: "Unknown"
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
                    isTestSandbox = false
                )
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        /**
         * يعالج رسالة واحدة ويخزنها في قائمة المراجعة إذا كانت من بنك مفعل وغير مكررة.
         */
        suspend fun processIncomingSmsLocally(
            context: Context,
            senderAddress: String,
            body: String,
            smsTimestamp: Long = System.currentTimeMillis(),
            isTestSandbox: Boolean = false
        ): Long? {
            val db = SaidiQershDatabase.getInstance(context)
            val dao = db.dao()
            val banks = dao.getAllBanks()
            val rules = dao.getActiveSmsRules()
            val categories = dao.getAllCategories()

            val parsed = BankSmsParserEngine.parseSms(
                senderAddress = senderAddress,
                rawBody = body,
                smsTimestamp = smsTimestamp,
                knownBanks = banks,
                customRules = rules,
                categories = categories
            )

            // احترام إعداد المستخدم في تفعيل أو تعطيل قراءة الرسائل لكل بنك
            if (!parsed.isBankSmsEnabled && !isTestSandbox) {
                return null
            }

            // تجاهل الرسائل غير المالية أو إشعارات الرصيد البحتة مع تسجيلها في سجل الاستيراد للشفافية
            if (parsed.suggestedType == SmsSuggestedType.BALANCE_NOTICE_ONLY) {
                dao.insertAuditLog(
                    ImportAuditLogEntity(
                        eventType = "SMS_IGNORED_BALANCE",
                        entityType = "SMS",
                        sourceDescription = parsed.bankName,
                        details = "تم تجاهل رسالة إشعار رصيد / غير معاملة تلقائيًا: ${parsed.sanitizedSnippet}",
                        reviewStatus = ReviewStatus.IGNORED.name
                    )
                )
                return null
            }

            // منع التكرار عبر بصمة الرسالة أو رقم المرجع لنفس البنك
            val existingByHash = dao.getSuggestedByHash(parsed.messageHash)
            if (existingByHash != null) {
                return null
            }
            val existingByRef = parsed.referenceNumber?.let { ref ->
                dao.getSuggestedByRefAndBank(ref, parsed.bankName)
            }

            val accounts = dao.getAllAccounts()
            val matchedAccount = accounts.firstOrNull {
                it.smsParsingEnabled && (parsed.matchedBankId != null && it.bankId == parsed.matchedBankId)
            } ?: accounts.firstOrNull { it.smsParsingEnabled }

            // إذا كان الحساب البنكي المرتبط معطلًا لمعالجة الرسائل
            if (matchedAccount == null && !isTestSandbox && accounts.isNotEmpty()) {
                return null
            }

            val finalStatus = if (existingByRef != null) {
                ReviewStatus.DUPLICATE
            } else {
                parsed.reviewStatus
            }

            val entity = SuggestedTransactionEntity(
                messageHash = parsed.messageHash,
                referenceNumber = parsed.referenceNumber,
                senderAddress = parsed.senderAddress,
                bankId = parsed.matchedBankId,
                bankName = parsed.bankName,
                sanitizedSnippet = parsed.sanitizedSnippet,
                extractedAmountMinor = parsed.extractedAmountMinor,
                currency = parsed.currency,
                merchantName = parsed.merchantName,
                suggestedType = parsed.suggestedType,
                suggestedCategoryId = parsed.suggestedCategoryId,
                suggestedCategoryName = parsed.suggestedCategoryName,
                suggestedAccountId = matchedAccount?.id,
                status = finalStatus,
                isTestSandbox = isTestSandbox,
                smsTimestamp = parsed.smsTimestamp,
                importedAt = System.currentTimeMillis()
            )

            val insertedId = dao.insertSuggestedTransaction(entity)
            if (insertedId > 0L) {
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
                return insertedId
            }
            return null
        }

        /**
         * يفحص صندوق رسائل الهاتف الفعلي (عند منح صلاحية READ_SMS) لاستيراد رسائل البنوك المفعلة.
         */
        suspend fun scanDeviceInboxForBankSms(context: Context, maxMessages: Int = 60): Int {
            var importedCount = 0
            val resolver = context.contentResolver
            val cursor = resolver.query(
                Telephony.Sms.Inbox.CONTENT_URI,
                arrayOf(
                    Telephony.Sms.Inbox.ADDRESS,
                    Telephony.Sms.Inbox.BODY,
                    Telephony.Sms.Inbox.DATE
                ),
                null,
                null,
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
                    if (body.isNotBlank()) {
                        val id = processIncomingSmsLocally(
                            context = context,
                            senderAddress = address,
                            body = body,
                            smsTimestamp = date,
                            isTestSandbox = false
                        )
                        if (id != null && id > 0L) {
                            importedCount++
                        }
                    }
                }
            }
            return importedCount
        }
    }
}
