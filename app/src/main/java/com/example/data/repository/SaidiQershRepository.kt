package com.example.data.repository

import com.example.data.local.*
import com.example.domain.BankSmsParserEngine
import com.example.domain.MoneyUtils
import kotlinx.coroutines.flow.Flow
import org.json.JSONArray
import org.json.JSONObject

class SaidiQershRepository(private val dao: SaidiQershDao) {

    val banksFlow: Flow<List<BankEntity>> = dao.observeBanks()
    val smsRulesFlow: Flow<List<SmsRuleEntity>> = dao.observeSmsRules()
    val accountsFlow: Flow<List<AccountEntity>> = dao.observeAccounts()
    val categoriesFlow: Flow<List<CategoryEntity>> = dao.observeCategories()
    val confirmedTransactionsFlow: Flow<List<TransactionEntity>> = dao.observeConfirmedTransactions()
    val suggestedTransactionsFlow: Flow<List<SuggestedTransactionEntity>> = dao.observeAllSuggestedTransactions()
    val budgetsFlow: Flow<List<BudgetEntity>> = dao.observeAllBudgets()
    val obligationsFlow: Flow<List<ObligationEntity>> = dao.observeObligations()
    val savingsGoalsFlow: Flow<List<SavingsGoalEntity>> = dao.observeSavingsGoals()
    val shoppingListsFlow: Flow<List<ShoppingListEntity>> = dao.observeShoppingLists()
    val shoppingItemsFlow: Flow<List<ShoppingItemEntity>> = dao.observeAllShoppingItems()
    val auditLogsFlow: Flow<List<ImportAuditLogEntity>> = dao.observeAuditLogs()

    suspend fun ensureSeeded() {
        SaidiQershDatabase.seedInitialDataIfEmpty(dao)
    }

    // ==================== العمليات المالية المؤكدة ====================
    suspend fun addConfirmedTransaction(tx: TransactionEntity): Long {
        return dao.addConfirmedTransaction(tx)
    }

    suspend fun updateConfirmedTransaction(tx: TransactionEntity) {
        dao.updateConfirmedTransaction(tx)
    }

    suspend fun deleteConfirmedTransaction(txId: Long) {
        dao.deleteConfirmedTransaction(txId)
    }

    // ==================== الحسابات والبنوك ====================
    suspend fun saveAccount(account: AccountEntity): Long {
        val id = dao.insertAccount(account)
        dao.insertAuditLog(
            ImportAuditLogEntity(
                eventType = "ACCOUNT_SAVE",
                entityType = "Account",
                entityId = id,
                sourceDescription = account.name,
                details = "حفظ حساب (${account.type.titleAr}) بعملة ${account.currency} ورصيد ${MoneyUtils.formatMinor(account.currentBalanceMinor, account.currency)}"
            )
        )
        return id
    }

    suspend fun updateAccount(account: AccountEntity) {
        dao.updateAccount(account)
    }

    suspend fun reconcileAccountBalance(accountId: Long, newBalanceMinor: Long, reason: String) {
        dao.reconcileAccountBalance(accountId, newBalanceMinor, reason)
    }

    suspend fun toggleBankSmsEnabled(bank: BankEntity, enabled: Boolean) {
        dao.updateBank(bank.copy(smsEnabled = enabled))
    }

    suspend fun toggleBankAutoApprove(bank: BankEntity, autoApprove: Boolean) {
        dao.updateBank(bank.copy(autoApproveEnabled = autoApprove))
    }

    suspend fun addCustomBank(
        nameAr: String,
        nameEn: String,
        code: String,
        currency: String,
        senderIds: String,
        autoApproveEnabled: Boolean = false
    ): Long {
        return dao.insertBank(
            BankEntity(
                nameAr = nameAr,
                nameEn = nameEn.ifBlank { nameAr },
                code = code.uppercase(),
                defaultCurrency = currency,
                senderIdsCsv = senderIds,
                smsEnabled = true,
                autoApproveEnabled = autoApproveEnabled
            )
        )
    }

    suspend fun addCustomSmsRule(bankId: Long, ruleName: String, keywordOrRegex: String, type: SmsSuggestedType): Long {
        return dao.insertSmsRule(
            SmsRuleEntity(
                bankId = bankId,
                ruleName = ruleName,
                keywordOrRegex = keywordOrRegex,
                suggestedType = type,
                isActive = true,
                priority = 25
            )
        )
    }

    suspend fun deleteSmsRule(rule: SmsRuleEntity) {
        dao.deleteSmsRule(rule)
    }

    // ==================== التصنيفات ====================
    suspend fun saveCategory(category: CategoryEntity): Long {
        return if (category.id == 0L) {
            dao.insertCategory(category)
        } else {
            dao.updateCategory(category)
            category.id
        }
    }

    // ==================== معالجة ومراجعة رسائل البنوك ====================
    suspend fun ingestBankSms(
        senderAddress: String,
        rawBody: String,
        smsTimestamp: Long = System.currentTimeMillis(),
        isTestSandbox: Boolean = false
    ): Long? {
        val banks = dao.getAllBanks()
        val rules = dao.getActiveSmsRules()
        val categories = dao.getAllCategories()
        val accounts = dao.getAllAccounts()

        val parsed = BankSmsParserEngine.parseSms(
            senderAddress = senderAddress,
            rawBody = rawBody,
            smsTimestamp = smsTimestamp,
            knownBanks = banks,
            customRules = rules,
            categories = categories,
            accounts = accounts
        )

        // الفلترة التلقائية للرسائل غير المالية (تفعيل مستفيد، OTP، تسجيل دخول، إشعار رصيد...)
        if (parsed.suggestedType == SmsSuggestedType.BALANCE_NOTICE_ONLY || parsed.reviewStatus == ReviewStatus.IGNORED) {
            dao.insertAuditLog(
                ImportAuditLogEntity(
                    eventType = "SMS_BALANCE_NOTICE",
                    entityType = "SMS",
                    sourceDescription = parsed.bankName,
                    details = "رسالة غير مالية / إشعار رصيد تم استبعاده تلقائياً: ${parsed.sanitizedSnippet}",
                    reviewStatus = ReviewStatus.IGNORED.name
                )
            )
            return null
        }

        // تسجيل جهة المرسل ديناميكياً في قائمة الجهات المسموحة إذا كانت جهة جديدة
        val senderBank = com.example.receivers.BankSmsBroadcastReceiver.ensureDynamicSenderRegistered(
            dao = dao,
            senderAddress = parsed.senderAddress,
            resolvedBankName = parsed.bankName,
            currency = parsed.currency,
            matchedBankId = parsed.matchedBankId
        )

        val isSenderEnabled = senderBank?.smsEnabled ?: parsed.isBankSmsEnabled
        val isAutoApprove = senderBank?.autoApproveEnabled ?: parsed.isAutoApproveEnabled

        // Toggle 1: إذا عطل المستخدم قراءة رسائل هذه الجهة، نتجاهلها تماماً
        if (!isSenderEnabled && !isTestSandbox) {
            return null
        }

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

        val status = if (existingByRef != null) ReviewStatus.DUPLICATE else parsed.reviewStatus

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
            status = status,
            isTestSandbox = isTestSandbox,
            smsTimestamp = parsed.smsTimestamp
        )

        val id = dao.insertSuggestedTransaction(entity)
        if (id > 0L) {
            // Toggle 2: اعتماد وتسجيل تلقائي فوري (Auto-Approve) إذا كان مفعلاً لهذه الجهة
            if (isAutoApprove &&
                status == ReviewStatus.PENDING &&
                parsed.extractedAmountMinor != null &&
                parsed.extractedAmountMinor > 0L
            ) {
                dao.confirmSuggestedTransaction(id)
                return id
            }

            dao.insertAuditLog(
                ImportAuditLogEntity(
                    eventType = "SMS_IMPORT",
                    entityType = "SuggestedTransaction",
                    entityId = id,
                    sourceDescription = "${parsed.bankName} (${parsed.senderAddress})",
                    details = "${parsed.suggestedType.titleAr} - ${parsed.merchantName}",
                    reviewStatus = status.name
                )
            )
            return id
        }
        return null
    }

    suspend fun confirmSuggestedTransaction(
        suggestedId: Long,
        overrideAmountMinor: Long? = null,
        overrideMerchant: String? = null,
        overrideCategoryId: Long? = null,
        overrideAccountId: Long? = null,
        overrideToAccountId: Long? = null,
        overrideNotes: String? = null
    ): Long? {
        return dao.confirmSuggestedTransaction(
            suggestedId = suggestedId,
            overrideAmountMinor = overrideAmountMinor,
            overrideMerchant = overrideMerchant,
            overrideCategoryId = overrideCategoryId,
            overrideAccountId = overrideAccountId,
            overrideToAccountId = overrideToAccountId,
            overrideNotes = overrideNotes
        )
    }

    suspend fun updateSuggestedBeforeConfirm(
        suggested: SuggestedTransactionEntity,
        newAmountMinor: Long,
        newMerchant: String,
        newCategoryId: Long?,
        newCategoryName: String,
        newAccountId: Long?,
        newType: SmsSuggestedType
    ) {
        dao.updateSuggestedTransaction(
            suggested.copy(
                extractedAmountMinor = newAmountMinor,
                merchantName = newMerchant,
                suggestedCategoryId = newCategoryId,
                suggestedCategoryName = newCategoryName,
                suggestedAccountId = newAccountId,
                suggestedType = newType,
                status = if (newAmountMinor > 0L) ReviewStatus.PENDING else ReviewStatus.UNCLEAR
            )
        )
    }

    suspend fun ignoreSuggestedTransaction(suggested: SuggestedTransactionEntity) {
        val now = System.currentTimeMillis()
        dao.updateSuggestedTransaction(
            suggested.copy(status = ReviewStatus.IGNORED, reviewedAt = now)
        )
        dao.insertAuditLog(
            ImportAuditLogEntity(
                eventType = "SMS_IGNORED",
                entityType = "SuggestedTransaction",
                entityId = suggested.id,
                sourceDescription = suggested.bankName,
                details = "تم تجاهل الرسالة المقترحة من قبل المستخدم: ${suggested.merchantName}",
                reviewStatus = ReviewStatus.IGNORED.name,
                timestamp = now
            )
        )
    }

    suspend fun markSuggestedAsDuplicate(suggested: SuggestedTransactionEntity) {
        val now = System.currentTimeMillis()
        dao.updateSuggestedTransaction(
            suggested.copy(status = ReviewStatus.DUPLICATE, reviewedAt = now)
        )
        dao.insertAuditLog(
            ImportAuditLogEntity(
                eventType = "SMS_DUPLICATE",
                entityType = "SuggestedTransaction",
                entityId = suggested.id,
                sourceDescription = suggested.bankName,
                details = "تم تعليم الرسالة كمكررة: ${suggested.merchantName}",
                reviewStatus = ReviewStatus.DUPLICATE.name,
                timestamp = now
            )
        )
    }

    suspend fun clearTestSandboxSuggestions() {
        dao.clearTestSandboxSuggestions()
    }

    // ==================== الميزانية والالتزامات والتوفير ومشتريات البيت ====================
    suspend fun saveBudget(budget: BudgetEntity): Long = dao.upsertBudget(budget)

    suspend fun deleteBudget(budget: BudgetEntity) = dao.deleteBudget(budget)

    suspend fun saveObligation(obligation: ObligationEntity): Long = dao.insertObligation(obligation)

    suspend fun deleteObligation(obligation: ObligationEntity) = dao.deleteObligation(obligation)

    suspend fun recordObligationPayment(
        obligationId: Long,
        paymentKey: String,
        amountMinor: Long,
        accountId: Long?,
        alreadyRecordedAsExpense: Boolean,
        notes: String = ""
    ): Boolean {
        return dao.recordObligationPayment(
            obligationId = obligationId,
            paymentKey = paymentKey,
            amountMinor = amountMinor,
            accountId = accountId,
            alreadyRecordedAsExpense = alreadyRecordedAsExpense,
            notes = notes
        )
    }

    suspend fun saveSavingsGoal(goal: SavingsGoalEntity): Long = dao.insertSavingsGoal(goal)

    suspend fun deleteSavingsGoal(goal: SavingsGoalEntity) = dao.deleteSavingsGoal(goal)

    suspend fun recordSavingsMovement(goalId: Long, isDeposit: Boolean, amountMinor: Long, note: String): Boolean {
        return dao.recordSavingsMovement(goalId, isDeposit, amountMinor, note)
    }

    suspend fun createShoppingList(title: String, currency: String = "SAR"): Long {
        return dao.insertShoppingList(ShoppingListEntity(title = title, currency = currency))
    }

    suspend fun addShoppingItem(item: ShoppingItemEntity): Long = dao.insertShoppingItem(item)

    suspend fun toggleShoppingItemPurchased(item: ShoppingItemEntity) {
        dao.updateShoppingItem(item.copy(isPurchased = !item.isPurchased))
    }

    suspend fun deleteShoppingItem(item: ShoppingItemEntity) = dao.deleteShoppingItem(item)

    suspend fun convertShoppingListToExpenseOnce(
        listId: Long,
        accountId: Long,
        categoryId: Long?,
        overrideActualTotalMinor: Long? = null
    ): Long? {
        return dao.recordShoppingListAsExpenseOnce(listId, accountId, categoryId, overrideActualTotalMinor)
    }

    // ==================== تصدير CSV والنسخ الاحتياطي المحلي المشفر ====================
    suspend fun exportConfirmedTransactionsCsv(): String {
        val txs = dao.getAllConfirmedTransactions()
        val categories = dao.getAllCategories().associateBy { it.id }
        val accounts = dao.getAllAccounts().associateBy { it.id }

        val sb = StringBuilder()
        sb.appendLine("ID,التاريخ,النوع,المبلغ,العملة,الوصف,التصنيف,الحساب,البنك,التاجر,رقم المرجع,المصدر,ملاحظات")
        for (tx in txs) {
            val dateStr = MoneyUtils.formatArabicDate(tx.timestamp)
            val amountStr = MoneyUtils.formatPlainDecimal(tx.amountMinor)
            val catName = tx.categoryId?.let { categories[it]?.nameAr } ?: "بدون تصنيف"
            val accName = accounts[tx.accountId]?.name ?: ""
            val escDesc = tx.description.replace("\"", "\"\"")
            val escNotes = tx.notes.replace("\"", "\"\"")
            sb.appendLine("${tx.id},\"$dateStr\",\"${tx.type.titleAr}\",$amountStr,${tx.currency},\"$escDesc\",\"$catName\",\"$accName\",\"${tx.bankName}\",\"${tx.merchantName}\",\"${tx.referenceNumber ?: ""}\",\"${tx.source.titleAr}\",\"$escNotes\"")
        }
        dao.insertAuditLog(
            ImportAuditLogEntity(
                eventType = "CSV_EXPORT",
                entityType = "Export",
                sourceDescription = "تصدير CSV محلي",
                details = "تم تصدير ${txs.size} عملية مؤكدة بصيغة CSV محليًا."
            )
        )
        return sb.toString()
    }

    suspend fun createLocalBackupJson(passphrase: String? = null): String {
        val accounts = dao.getAllAccounts()
        val txs = dao.getAllConfirmedTransactions()
        val categories = dao.getAllCategories()

        val root = JSONObject()
        root.put("app", "SaidiQersh")
        root.put("schemaVersion", 2)
        root.put("exportedAt", System.currentTimeMillis())

        val accArr = JSONArray()
        for (a in accounts) {
            accArr.put(
                JSONObject().apply {
                    put("id", a.id)
                    put("name", a.name)
                    put("type", a.type.name)
                    put("bankName", a.bankName)
                    put("currency", a.currency)
                    put("openingBalanceMinor", a.openingBalanceMinor)
                    put("currentBalanceMinor", a.currentBalanceMinor)
                    put("accountLast4", a.accountLast4)
                    put("cardLast4", a.cardLast4)
                }
            )
        }
        root.put("accounts", accArr)

        val catArr = JSONArray()
        for (c in categories) {
            catArr.put(
                JSONObject().apply {
                    put("id", c.id)
                    put("nameAr", c.nameAr)
                    put("type", c.type.name)
                    put("iconName", c.iconName)
                    put("colorHex", c.colorHex)
                }
            )
        }
        root.put("categories", catArr)

        val txArr = JSONArray()
        for (t in txs) {
            txArr.put(
                JSONObject().apply {
                    put("id", t.id)
                    put("type", t.type.name)
                    put("amountMinor", t.amountMinor)
                    put("currency", t.currency)
                    put("description", t.description)
                    put("notes", t.notes)
                    put("timestamp", t.timestamp)
                    put("accountId", t.accountId)
                    put("toAccountId", t.toAccountId ?: JSONObject.NULL)
                    put("categoryId", t.categoryId ?: JSONObject.NULL)
                    put("merchantName", t.merchantName)
                    put("referenceNumber", t.referenceNumber ?: "")
                    put("source", t.source.name)
                }
            )
        }
        root.put("transactions", txArr)

        val plainJson = root.toString()
        return if (!passphrase.isNullOrBlank()) {
            MoneyUtils.encryptBackupPayload(plainJson, passphrase)
        } else {
            plainJson
        }
    }

    suspend fun restoreLocalBackupJson(payload: String, passphrase: String? = null): Result<Int> {
        return try {
            val plainJson = if (payload.trim().startsWith("SQENC1:")) {
                if (passphrase.isNullOrBlank()) {
                    return Result.failure(IllegalArgumentException("هذه النسخة الاحتياطية مشفرة وتحتاج إلى كلمة المرور لفك التشفير."))
                }
                MoneyUtils.decryptBackupPayload(payload.trim(), passphrase)
            } else {
                payload.trim()
            }

            val root = JSONObject(plainJson)
            if (root.optString("app") != "SaidiQersh" || !root.has("accounts") || !root.has("transactions")) {
                return Result.failure(IllegalArgumentException("ملف النسخة الاحتياطية غير صالح أو لا ينتمي لتطبيق الصعيدي قرش."))
            }

            val accArr = root.getJSONArray("accounts")
            val txArr = root.getJSONArray("transactions")

            var restoredCount = 0
            for (i in 0 until accArr.length()) {
                val obj = accArr.getJSONObject(i)
                dao.insertAccount(
                    AccountEntity(
                        id = obj.optLong("id", 0L),
                        name = obj.getString("name"),
                        type = AccountType.valueOf(obj.optString("type", AccountType.BANK.name)),
                        bankName = obj.optString("bankName", ""),
                        currency = obj.optString("currency", "SAR"),
                        openingBalanceMinor = obj.optLong("openingBalanceMinor", 0L),
                        currentBalanceMinor = obj.optLong("currentBalanceMinor", 0L),
                        accountLast4 = obj.optString("accountLast4", ""),
                        cardLast4 = obj.optString("cardLast4", "")
                    )
                )
            }

            for (i in 0 until txArr.length()) {
                val obj = txArr.getJSONObject(i)
                val amountMinor = obj.getLong("amountMinor")
                if (amountMinor > 0L) {
                    dao.insertTransactionInternal(
                        TransactionEntity(
                            id = obj.optLong("id", 0L),
                            type = TransactionType.valueOf(obj.optString("type", TransactionType.EXPENSE.name)),
                            amountMinor = amountMinor,
                            currency = obj.optString("currency", "SAR"),
                            description = obj.getString("description"),
                            notes = obj.optString("notes", ""),
                            timestamp = obj.optLong("timestamp", System.currentTimeMillis()),
                            accountId = obj.getLong("accountId"),
                            toAccountId = if (obj.isNull("toAccountId")) null else obj.optLong("toAccountId"),
                            categoryId = if (obj.isNull("categoryId")) null else obj.optLong("categoryId"),
                            merchantName = obj.optString("merchantName", ""),
                            referenceNumber = obj.optString("referenceNumber", "").ifBlank { null },
                            source = TransactionSource.valueOf(obj.optString("source", TransactionSource.MANUAL.name))
                        )
                    )
                    restoredCount++
                }
            }

            dao.insertAuditLog(
                ImportAuditLogEntity(
                    eventType = "BACKUP_IMPORT",
                    entityType = "Backup",
                    sourceDescription = "استعادة نسخة احتياطية محلية",
                    details = "تم التحقق واستعادة $restoredCount عملية مالية بنجاح."
                )
            )
            Result.success(restoredCount)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun wipeAllDataAfterConfirmation() {
        dao.wipeAllUserData()
        SaidiQershDatabase.seedInitialDataIfEmpty(dao)
    }

    companion object {
        fun computeNetConfirmedExpensesMinor(transactions: List<TransactionEntity>): Long {
            val grossExpenses = transactions
                .filter { it.type == TransactionType.EXPENSE || it.type == TransactionType.OBLIGATION_PAYMENT }
                .sumOf { it.amountMinor }
            val refunds = transactions
                .filter { it.type == TransactionType.REFUND }
                .sumOf { it.amountMinor }
            return (grossExpenses - refunds).coerceAtLeast(0L)
        }

        fun computeConfirmedIncomeMinor(transactions: List<TransactionEntity>): Long {
            return transactions
                .filter { it.type == TransactionType.INCOME }
                .sumOf { it.amountMinor }
        }
    }
}
