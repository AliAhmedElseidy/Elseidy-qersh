package com.example.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.example.domain.MoneyUtils
import kotlinx.coroutines.flow.Flow
import java.util.Locale

@Dao
interface SaidiQershDao {

    // ==================== البنوك وقواعد الرسائل ====================
    @Query("SELECT * FROM banks ORDER BY id ASC")
    fun observeBanks(): Flow<List<BankEntity>>

    @Query("SELECT * FROM banks ORDER BY id ASC")
    suspend fun getAllBanks(): List<BankEntity>

    @Query("SELECT * FROM banks WHERE id = :id LIMIT 1")
    suspend fun getBankById(id: Long): BankEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertBank(bank: BankEntity): Long

    @Update
    suspend fun updateBank(bank: BankEntity)

    @Query("SELECT * FROM sms_rules ORDER BY priority DESC, id ASC")
    fun observeSmsRules(): Flow<List<SmsRuleEntity>>

    @Query("SELECT * FROM sms_rules WHERE isActive = 1 ORDER BY priority DESC, id ASC")
    suspend fun getActiveSmsRules(): List<SmsRuleEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSmsRule(rule: SmsRuleEntity): Long

    @Delete
    suspend fun deleteSmsRule(rule: SmsRuleEntity)

    // ==================== كلمات التجاهل المخصصة (Ignored Keywords Manager) ====================
    @Query("SELECT * FROM ignored_keywords ORDER BY isDefault DESC, id ASC")
    fun observeIgnoredKeywords(): Flow<List<IgnoredKeywordEntity>>

    @Query("SELECT * FROM ignored_keywords ORDER BY isDefault DESC, id ASC")
    suspend fun getAllIgnoredKeywords(): List<IgnoredKeywordEntity>

    @Query("SELECT * FROM ignored_keywords WHERE isActive = 1 ORDER BY id ASC")
    suspend fun getActiveIgnoredKeywords(): List<IgnoredKeywordEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnoredKeyword(item: IgnoredKeywordEntity): Long

    @Update
    suspend fun updateIgnoredKeyword(item: IgnoredKeywordEntity)

    @Delete
    suspend fun deleteIgnoredKeyword(item: IgnoredKeywordEntity)

    @Query("DELETE FROM ignored_keywords")
    suspend fun deleteAllIgnoredKeywords()

    // ==================== ذاكرة التاجر الذكية (Smart Merchant Memory) ====================
    @Query("SELECT * FROM merchant_memories ORDER BY updatedAt DESC, usageCount DESC")
    fun observeMerchantMemories(): Flow<List<MerchantMemoryEntity>>

    @Query("SELECT * FROM merchant_memories ORDER BY updatedAt DESC, usageCount DESC")
    suspend fun getAllMerchantMemories(): List<MerchantMemoryEntity>

    @Query("SELECT * FROM merchant_memories WHERE normalizedMerchant = :normalized LIMIT 1")
    suspend fun getMerchantMemoryByNormalized(normalized: String): MerchantMemoryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMerchantMemory(item: MerchantMemoryEntity): Long

    @Update
    suspend fun updateMerchantMemory(item: MerchantMemoryEntity)

    @Delete
    suspend fun deleteMerchantMemory(item: MerchantMemoryEntity)

    @Query("DELETE FROM merchant_memories")
    suspend fun deleteAllMerchantMemories()

    /**
     * يحفظ أو يحدث ذاكرة التاجر الذكية تلقائياً عند اختيار المستخدم للتصنيف والحساب لتاجر معين.
     */
    suspend fun upsertMerchantMemory(
        merchantRaw: String,
        categoryId: Long?,
        accountId: Long?
    ) {
        val cleaned = merchantRaw.trim()
        if (cleaned.length < 2) return
        val normalized = cleaned.lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")
        if (normalized in setOf("مصروف", "دخل", "شراء بالبطاقة", "سحب نقدي", "تحويل صادر", "تحويل وارد", "إيداع", "دفع فاتورة")) {
            return
        }

        val existing = getMerchantMemoryByNormalized(normalized)
        val catName = categoryId?.let { getCategoryById(it)?.nameAr } ?: existing?.categoryName ?: ""
        val accName = accountId?.let { getAccountById(it)?.name } ?: existing?.accountName ?: ""
        val now = System.currentTimeMillis()

        if (existing != null) {
            updateMerchantMemory(
                existing.copy(
                    merchantDisplayName = cleaned,
                    categoryId = categoryId ?: existing.categoryId,
                    categoryName = if (catName.isNotBlank()) catName else existing.categoryName,
                    accountId = accountId ?: existing.accountId,
                    accountName = if (accName.isNotBlank()) accName else existing.accountName,
                    usageCount = existing.usageCount + 1,
                    updatedAt = now
                )
            )
        } else {
            insertMerchantMemory(
                MerchantMemoryEntity(
                    normalizedMerchant = normalized,
                    merchantDisplayName = cleaned,
                    categoryId = categoryId,
                    categoryName = catName,
                    accountId = accountId,
                    accountName = accName,
                    usageCount = 1,
                    updatedAt = now
                )
            )
        }
    }

    // ==================== الحسابات المالية ====================
    @Query("SELECT * FROM accounts ORDER BY id ASC")
    fun observeAccounts(): Flow<List<AccountEntity>>

    @Query("SELECT * FROM accounts ORDER BY id ASC")
    suspend fun getAllAccounts(): List<AccountEntity>

    @Query("SELECT * FROM accounts WHERE id = :id LIMIT 1")
    suspend fun getAccountById(id: Long): AccountEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAccount(account: AccountEntity): Long

    @Update
    suspend fun updateAccount(account: AccountEntity)

    @Delete
    suspend fun deleteAccount(account: AccountEntity)

    @Query("SELECT COALESCE(SUM(currentBalanceMinor), 0) FROM accounts")
    fun observeTotalNetBalanceMinor(): Flow<Long>

    // ==================== التصنيفات ====================
    @Query("SELECT * FROM categories WHERE isArchived = 0 ORDER BY id ASC")
    fun observeCategories(): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM categories WHERE isArchived = 0 ORDER BY id ASC")
    suspend fun getAllCategories(): List<CategoryEntity>

    @Query("SELECT * FROM categories WHERE id = :id LIMIT 1")
    suspend fun getCategoryById(id: Long): CategoryEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCategory(category: CategoryEntity): Long

    @Update
    suspend fun updateCategory(category: CategoryEntity)

    // ==================== العمليات المالية المؤكدة ====================
    @Query("SELECT * FROM transactions ORDER BY timestamp DESC, id DESC")
    fun observeConfirmedTransactions(): Flow<List<TransactionEntity>>

    @Query("SELECT * FROM transactions ORDER BY timestamp DESC, id DESC")
    suspend fun getAllConfirmedTransactions(): List<TransactionEntity>

    @Query("SELECT * FROM transactions WHERE id = :id LIMIT 1")
    suspend fun getTransactionById(id: Long): TransactionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTransactionInternal(transaction: TransactionEntity): Long

    @Update
    suspend fun updateTransactionInternal(transaction: TransactionEntity)

    @Delete
    suspend fun deleteTransactionInternal(transaction: TransactionEntity)

    /**
     * يحسب أثر العملية المالية المؤكدة على الحسابات (مع دعم التحويل بين الحسابات، الاسترداد، وسداد البطاقة).
     */
    private suspend fun applyTransactionBalanceDelta(tx: TransactionEntity, reverse: Boolean) {
        val multiplier = if (reverse) -1L else 1L
        val primaryAccount = getAccountById(tx.accountId)
        if (primaryAccount != null) {
            val delta = when (tx.type) {
                TransactionType.EXPENSE,
                TransactionType.OBLIGATION_PAYMENT -> -tx.amountMinor * multiplier
                TransactionType.INCOME,
                TransactionType.REFUND -> tx.amountMinor * multiplier
                TransactionType.TRANSFER,
                TransactionType.CREDIT_CARD_PAYMENT -> -tx.amountMinor * multiplier
            }
            updateAccount(
                primaryAccount.copy(
                    currentBalanceMinor = primaryAccount.currentBalanceMinor + delta
                )
            )
        }
        val destId = tx.toAccountId
        if (destId != null && (tx.type == TransactionType.TRANSFER || tx.type == TransactionType.CREDIT_CARD_PAYMENT)) {
            val destAccount = getAccountById(destId)
            if (destAccount != null) {
                val destDelta = tx.amountMinor * multiplier
                updateAccount(
                    destAccount.copy(
                        currentBalanceMinor = destAccount.currentBalanceMinor + destDelta
                    )
                )
            }
        }
    }

    /**
     * إضافة عملية مؤكدة وتحديث أرصدة الحسابات وذاكرة التاجر الذكية وسجل التدقيق في معاملة قاعدة بيانات واحدة.
     */
    @Transaction
    suspend fun addConfirmedTransaction(tx: TransactionEntity): Long {
        val id = insertTransactionInternal(tx)
        applyTransactionBalanceDelta(tx.copy(id = id), reverse = false)
        val merchantCandidate = tx.merchantName.ifBlank { tx.description }
        if (tx.type != TransactionType.TRANSFER && merchantCandidate.isNotBlank()) {
            upsertMerchantMemory(
                merchantRaw = merchantCandidate,
                categoryId = tx.categoryId,
                accountId = tx.accountId
            )
        }
        insertAuditLog(
            ImportAuditLogEntity(
                eventType = "TRANSACTION_ADD",
                entityType = "Transaction",
                entityId = id,
                sourceDescription = tx.source.titleAr,
                details = "${tx.type.titleAr}: ${tx.description} (${MoneyUtils.formatMinor(tx.amountMinor, tx.currency)})",
                reviewStatus = "CONFIRMED",
                timestamp = System.currentTimeMillis()
            )
        )
        return id
    }

    /**
     * تعديل عملية مؤكدة مع عكس أثر الرصيد القديم وتطبيق الأثر الجديد وتحديث ذاكرة التاجر الذكية وحفظ سجل التعديل.
     */
    @Transaction
    suspend fun updateConfirmedTransaction(updatedTx: TransactionEntity) {
        val oldTx = getTransactionById(updatedTx.id) ?: return
        applyTransactionBalanceDelta(oldTx, reverse = true)
        val now = System.currentTimeMillis()
        val finalTx = updatedTx.copy(updatedAt = now)
        updateTransactionInternal(finalTx)
        applyTransactionBalanceDelta(finalTx, reverse = false)
        val merchantCandidate = finalTx.merchantName.ifBlank { finalTx.description }
        if (finalTx.type != TransactionType.TRANSFER && merchantCandidate.isNotBlank()) {
            upsertMerchantMemory(
                merchantRaw = merchantCandidate,
                categoryId = finalTx.categoryId,
                accountId = finalTx.accountId
            )
        }
        insertAuditLog(
            ImportAuditLogEntity(
                eventType = "TRANSACTION_EDIT",
                entityType = "Transaction",
                entityId = finalTx.id,
                sourceDescription = "تعديل عملية مالية",
                details = "قبل: ${oldTx.description} (${MoneyUtils.formatMinor(oldTx.amountMinor, oldTx.currency)}) -> بعد: ${finalTx.description} (${MoneyUtils.formatMinor(finalTx.amountMinor, finalTx.currency)})",
                reviewStatus = "CONFIRMED",
                timestamp = now
            )
        )
    }

    /**
     * حذف عملية مؤكدة بعد تأكيد المستخدم مع استعادة الرصيد وتوثيق الحذف في السجل.
     */
    @Transaction
    suspend fun deleteConfirmedTransaction(txId: Long) {
        val oldTx = getTransactionById(txId) ?: return
        applyTransactionBalanceDelta(oldTx, reverse = true)
        deleteTransactionInternal(oldTx)
        insertAuditLog(
            ImportAuditLogEntity(
                eventType = "TRANSACTION_DELETE",
                entityType = "Transaction",
                entityId = txId,
                sourceDescription = "حذف عملية مؤكدة",
                details = "حُذفت: ${oldTx.description} (${MoneyUtils.formatMinor(oldTx.amountMinor, oldTx.currency)})",
                reviewStatus = "DELETED",
                timestamp = System.currentTimeMillis()
            )
        )
    }

    /**
     * تسوية وتعديل رصيد الحساب يدويًا مع الاحتفاظ بسجل التسوية.
     */
    @Transaction
    suspend fun reconcileAccountBalance(accountId: Long, newBalanceMinor: Long, reason: String) {
        val account = getAccountById(accountId) ?: return
        val oldBalance = account.currentBalanceMinor
        val diff = newBalanceMinor - oldBalance
        if (diff == 0L) return
        updateAccount(account.copy(currentBalanceMinor = newBalanceMinor))
        insertAuditLog(
            ImportAuditLogEntity(
                eventType = "ACCOUNT_RECONCILE",
                entityType = "Account",
                entityId = accountId,
                sourceDescription = "تسوية رصيد يدوية (${account.name})",
                details = "الرصيد السابق: ${MoneyUtils.formatMinor(oldBalance, account.currency)} | الرصيد الجديد: ${MoneyUtils.formatMinor(newBalanceMinor, account.currency)} | الفرق: ${MoneyUtils.formatMinor(diff, account.currency, showSign = true)} | السبب: ${reason.ifBlank { "مطابقة رصيد فعلي" }}",
                reviewStatus = "RECONCILED",
                timestamp = System.currentTimeMillis()
            )
        )
    }

    // ==================== العمليات المقترحة (SMS Review Queue) ====================
    @Query("SELECT * FROM suggested_transactions WHERE isTestSandbox = :isSandbox ORDER BY smsTimestamp DESC, id DESC")
    fun observeSuggestedTransactions(isSandbox: Boolean = false): Flow<List<SuggestedTransactionEntity>>

    @Query("SELECT * FROM suggested_transactions ORDER BY smsTimestamp DESC, id DESC")
    fun observeAllSuggestedTransactions(): Flow<List<SuggestedTransactionEntity>>

    @Query("SELECT * FROM suggested_transactions WHERE id = :id LIMIT 1")
    suspend fun getSuggestedById(id: Long): SuggestedTransactionEntity?

    @Query("SELECT * FROM suggested_transactions WHERE messageHash = :hash LIMIT 1")
    suspend fun getSuggestedByHash(hash: String): SuggestedTransactionEntity?

    @Query("SELECT * FROM suggested_transactions WHERE referenceNumber = :refNum AND bankName = :bankName AND referenceNumber IS NOT NULL AND referenceNumber != '' LIMIT 1")
    suspend fun getSuggestedByRefAndBank(refNum: String, bankName: String): SuggestedTransactionEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSuggestedTransaction(suggested: SuggestedTransactionEntity): Long

    @Update
    suspend fun updateSuggestedTransaction(suggested: SuggestedTransactionEntity)

    @Query("DELETE FROM suggested_transactions WHERE isTestSandbox = 1")
    suspend fun clearTestSandboxSuggestions()

    /**
     * معاملة قاعدة بيانات واحدة (Atomic Transaction) عند ضغط المستخدم على «تأكيد وتسجيل»:
     * - تتحقق أن المقترح لم يُؤكد سابقًا لمنع المضاعفة.
     * - تنشئ العملية المؤكدة وتحدث رصيد الحساب مرة واحدة فقط.
     * - تحفظ ربط التاجر بالتصنيف والحساب في ذاكرة التاجر الذكية (Smart Merchant Memory).
     * - تحدث حالة المقترح إلى CONFIRMED وتوثق سجل الاستيراد.
     */
    @Transaction
    suspend fun confirmSuggestedTransaction(
        suggestedId: Long,
        overrideAmountMinor: Long? = null,
        overrideMerchant: String? = null,
        overrideCategoryId: Long? = null,
        overrideAccountId: Long? = null,
        overrideToAccountId: Long? = null,
        overrideNotes: String? = null
    ): Long? {
        val suggested = getSuggestedById(suggestedId) ?: return null
        // منع التأكيد المزدوج لنفس العملية المقترحة
        if (suggested.status == ReviewStatus.CONFIRMED || suggested.confirmedTransactionId != null) {
            return null
        }

        val amountMinor = overrideAmountMinor ?: suggested.extractedAmountMinor ?: return null
        if (amountMinor <= 0L) return null

        val merchant = (overrideMerchant ?: suggested.merchantName).ifBlank { suggested.suggestedType.titleAr }
        val normalizedMerchant = merchant.trim().lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")
        val memory = getMerchantMemoryByNormalized(normalizedMerchant)

        val accounts = getAllAccounts()
        val chosenAccountId = overrideAccountId
            ?: suggested.suggestedAccountId
            ?: memory?.accountId?.takeIf { memAccId -> accounts.any { it.id == memAccId } }
            ?: accounts.firstOrNull { it.bankId == suggested.bankId }?.id
            ?: accounts.firstOrNull()?.id
            ?: return null

        val txType = when (suggested.suggestedType) {
            SmsSuggestedType.CARD_PURCHASE,
            SmsSuggestedType.ATM_WITHDRAWAL,
            SmsSuggestedType.OUTGOING_TRANSFER,
            SmsSuggestedType.BILL_PAYMENT -> TransactionType.EXPENSE
            SmsSuggestedType.INCOMING_TRANSFER,
            SmsSuggestedType.DEPOSIT -> TransactionType.INCOME
            SmsSuggestedType.REFUND -> TransactionType.REFUND
            SmsSuggestedType.CREDIT_CARD_SETTLEMENT -> TransactionType.CREDIT_CARD_PAYMENT
            SmsSuggestedType.BALANCE_NOTICE_ONLY,
            SmsSuggestedType.UNCLEAR -> TransactionType.EXPENSE
        }

        val categoryId = overrideCategoryId ?: memory?.categoryId ?: suggested.suggestedCategoryId
        val now = System.currentTimeMillis()

        val confirmedTx = TransactionEntity(
            type = txType,
            amountMinor = amountMinor,
            currency = suggested.currency,
            description = "$merchant (${suggested.bankName})",
            notes = overrideNotes ?: "تم التأكيد من رسالة ${suggested.bankName} ${suggested.referenceNumber?.let { "- مرجع $it" } ?: ""}".trim(),
            timestamp = suggested.smsTimestamp,
            accountId = chosenAccountId,
            toAccountId = overrideToAccountId,
            categoryId = categoryId,
            bankId = suggested.bankId,
            bankName = suggested.bankName,
            merchantName = merchant,
            referenceNumber = suggested.referenceNumber,
            source = TransactionSource.SMS_CONFIRMED,
            createdAt = now,
            updatedAt = now
        )

        val confirmedId = insertTransactionInternal(confirmedTx)
        applyTransactionBalanceDelta(confirmedTx.copy(id = confirmedId), reverse = false)

        if (merchant.isNotBlank()) {
            upsertMerchantMemory(
                merchantRaw = merchant,
                categoryId = categoryId,
                accountId = chosenAccountId
            )
        }

        updateSuggestedTransaction(
            suggested.copy(
                extractedAmountMinor = amountMinor,
                merchantName = merchant,
                suggestedCategoryId = categoryId,
                suggestedAccountId = chosenAccountId,
                status = ReviewStatus.CONFIRMED,
                confirmedTransactionId = confirmedId,
                reviewedAt = now
            )
        )

        insertAuditLog(
            ImportAuditLogEntity(
                eventType = "SMS_CONFIRMED",
                entityType = "SuggestedTransaction",
                entityId = suggested.id,
                sourceDescription = "تأكيد رسالة بنكية (${suggested.bankName})",
                details = "تم تحويل الرسالة إلى عملية مؤكدة #$confirmedId بمبلغ ${MoneyUtils.formatMinor(amountMinor, suggested.currency)} لدى $merchant",
                reviewStatus = ReviewStatus.CONFIRMED.name,
                timestamp = now
            )
        )
        return confirmedId
    }

    // ==================== الميزانيات ====================
    @Query("SELECT * FROM budgets WHERE yearMonth = :yearMonth ORDER BY id ASC")
    fun observeBudgetsForMonth(yearMonth: String): Flow<List<BudgetEntity>>

    @Query("SELECT * FROM budgets ORDER BY yearMonth DESC, id ASC")
    fun observeAllBudgets(): Flow<List<BudgetEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertBudget(budget: BudgetEntity): Long

    @Delete
    suspend fun deleteBudget(budget: BudgetEntity)

    // ==================== الالتزامات والأقساط والجمعيات ====================
    @Query("SELECT * FROM obligations ORDER BY isCompleted ASC, dueDateMillis ASC")
    fun observeObligations(): Flow<List<ObligationEntity>>

    @Query("SELECT * FROM obligations WHERE id = :id LIMIT 1")
    suspend fun getObligationById(id: Long): ObligationEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertObligation(obligation: ObligationEntity): Long

    @Update
    suspend fun updateObligation(obligation: ObligationEntity)

    @Delete
    suspend fun deleteObligation(obligation: ObligationEntity)

    @Query("SELECT * FROM installment_payments WHERE obligationId = :obligationId ORDER BY paidAt DESC")
    fun observePaymentsForObligation(obligationId: Long): Flow<List<InstallmentPaymentEntity>>

    @Query("SELECT * FROM installment_payments WHERE obligationId = :obligationId AND paymentKey = :paymentKey LIMIT 1")
    suspend fun getInstallmentPaymentByKey(obligationId: Long, paymentKey: String): InstallmentPaymentEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertInstallmentPaymentInternal(payment: InstallmentPaymentEntity): Long

    /**
     * تسجيل قسط أو دفعة جزئية لالتزام معين:
     * - يمنع تكرار تسجيل نفس الدفعة (عبر paymentKey).
     * - إذا كانت الدفعة مسجلة مسبقًا كمصروف (alreadyRecordedAsExpense = true)، يحدث رصيد الالتزام فقط دون خصم مصروف مكرر!
     */
    @Transaction
    suspend fun recordObligationPayment(
        obligationId: Long,
        paymentKey: String,
        amountMinor: Long,
        accountId: Long?,
        alreadyRecordedAsExpense: Boolean,
        notes: String = ""
    ): Boolean {
        val obligation = getObligationById(obligationId) ?: return false
        val existing = getInstallmentPaymentByKey(obligationId, paymentKey)
        if (existing != null) {
            return false // منع تكرار تسجيل الدفعة نفسها
        }

        val now = System.currentTimeMillis()
        var linkedTxId: Long? = null

        if (!alreadyRecordedAsExpense && accountId != null) {
            val tx = TransactionEntity(
                type = TransactionType.OBLIGATION_PAYMENT,
                amountMinor = amountMinor,
                currency = obligation.currency,
                description = "سداد ${obligation.kind.titleAr}: ${obligation.title}",
                notes = notes.ifBlank { "دفعة رقم/مفتاح: $paymentKey" },
                timestamp = now,
                accountId = accountId,
                categoryId = obligation.categoryId,
                source = TransactionSource.OBLIGATION,
                obligationId = obligation.id,
                createdAt = now,
                updatedAt = now
            )
            linkedTxId = insertTransactionInternal(tx)
            applyTransactionBalanceDelta(tx.copy(id = linkedTxId), reverse = false)
        }

        insertInstallmentPaymentInternal(
            InstallmentPaymentEntity(
                obligationId = obligationId,
                paymentKey = paymentKey,
                amountMinor = amountMinor,
                paidAt = now,
                linkedTransactionId = linkedTxId,
                alreadyRecordedAsExpense = alreadyRecordedAsExpense,
                notes = notes
            )
        )

        val newPaid = obligation.paidAmountMinor + amountMinor
        val completed = newPaid >= obligation.totalAmountMinor
        updateObligation(
            obligation.copy(
                paidAmountMinor = newPaid,
                isCompleted = completed
            )
        )

        insertAuditLog(
            ImportAuditLogEntity(
                eventType = "OBLIGATION_PAYMENT",
                entityType = "Obligation",
                entityId = obligationId,
                sourceDescription = obligation.title,
                details = "سداد دفعة ($paymentKey) بمبلغ ${MoneyUtils.formatMinor(amountMinor, obligation.currency)} | سُجلت كمصروف جديد: ${if (alreadyRecordedAsExpense) "لا (مسجلة مسبقًا)" else "نعم"}",
                reviewStatus = "CONFIRMED",
                timestamp = now
            )
        )
        return true
    }

    // ==================== صناديق التوفير ومشتريات البيت ====================
    @Query("SELECT * FROM savings_goals ORDER BY isCompleted ASC, targetDateMillis ASC")
    fun observeSavingsGoals(): Flow<List<SavingsGoalEntity>>

    @Query("SELECT * FROM savings_goals WHERE id = :id LIMIT 1")
    suspend fun getSavingsGoalById(id: Long): SavingsGoalEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSavingsGoal(goal: SavingsGoalEntity): Long

    @Update
    suspend fun updateSavingsGoal(goal: SavingsGoalEntity)

    @Delete
    suspend fun deleteSavingsGoal(goal: SavingsGoalEntity)

    @Insert
    suspend fun insertSavingsMovement(movement: SavingsMovementEntity): Long

    @Transaction
    suspend fun recordSavingsMovement(goalId: Long, isDeposit: Boolean, amountMinor: Long, note: String): Boolean {
        val goal = getSavingsGoalById(goalId) ?: return false
        if (amountMinor <= 0L) return false
        val newAmount = if (isDeposit) {
            goal.currentAmountMinor + amountMinor
        } else {
            (goal.currentAmountMinor - amountMinor).coerceAtLeast(0L)
        }
        insertSavingsMovement(
            SavingsMovementEntity(
                goalId = goalId,
                isDeposit = isDeposit,
                amountMinor = amountMinor,
                note = note
            )
        )
        updateSavingsGoal(
            goal.copy(
                currentAmountMinor = newAmount,
                isCompleted = newAmount >= goal.targetAmountMinor
            )
        )
        return true
    }

    @Query("SELECT * FROM shopping_lists ORDER BY isArchived ASC, createdAt DESC")
    fun observeShoppingLists(): Flow<List<ShoppingListEntity>>

    @Query("SELECT * FROM shopping_lists WHERE id = :id LIMIT 1")
    suspend fun getShoppingListById(id: Long): ShoppingListEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertShoppingList(list: ShoppingListEntity): Long

    @Update
    suspend fun updateShoppingList(list: ShoppingListEntity)

    @Query("SELECT * FROM shopping_items WHERE listId = :listId ORDER BY id ASC")
    fun observeShoppingItems(listId: Long): Flow<List<ShoppingItemEntity>>

    @Query("SELECT * FROM shopping_items ORDER BY listId DESC, id ASC")
    fun observeAllShoppingItems(): Flow<List<ShoppingItemEntity>>

    @Query("SELECT * FROM shopping_items WHERE listId = :listId ORDER BY id ASC")
    suspend fun getShoppingItemsForList(listId: Long): List<ShoppingItemEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertShoppingItem(item: ShoppingItemEntity): Long

    @Update
    suspend fun updateShoppingItem(item: ShoppingItemEntity)

    @Delete
    suspend fun deleteShoppingItem(item: ShoppingItemEntity)

    /**
     * تحويل قائمة مشتريات البيت إلى عملية مصروف فعلية مرة واحدة فقط.
     */
    @Transaction
    suspend fun recordShoppingListAsExpenseOnce(
        listId: Long,
        accountId: Long,
        categoryId: Long?,
        overrideActualTotalMinor: Long? = null
    ): Long? {
        val list = getShoppingListById(listId) ?: return null
        // منع تسجيل نفس قائمة المشتريات كمصروف أكثر من مرة
        if (list.recordedAsExpense || list.linkedTransactionId != null) {
            return null
        }
        val items = getShoppingItemsForList(listId)
        val calculatedTotal = items.filter { it.isPurchased }.sumOf { it.totalEstimatedMinor }
            .let { if (it > 0L) it else items.sumOf { item -> item.totalEstimatedMinor } }
        val finalAmount = overrideActualTotalMinor ?: calculatedTotal
        if (finalAmount <= 0L) return null

        val now = System.currentTimeMillis()
        val tx = TransactionEntity(
            type = TransactionType.EXPENSE,
            amountMinor = finalAmount,
            currency = list.currency,
            description = "مشتريات البيت: ${list.title}",
            notes = "العناصر: ${items.joinToString("، ") { "${it.name} (${it.quantity})" }}",
            timestamp = now,
            accountId = accountId,
            categoryId = categoryId,
            source = TransactionSource.SHOPPING_LIST,
            createdAt = now,
            updatedAt = now
        )
        val txId = insertTransactionInternal(tx)
        applyTransactionBalanceDelta(tx.copy(id = txId), reverse = false)
        updateShoppingList(
            list.copy(
                recordedAsExpense = true,
                linkedTransactionId = txId,
                isArchived = true
            )
        )
        insertAuditLog(
            ImportAuditLogEntity(
                eventType = "SHOPPING_EXPENSE",
                entityType = "ShoppingList",
                entityId = listId,
                sourceDescription = "قائمة مشتريات: ${list.title}",
                details = "تم تسجيل قائمة المشتريات كمصروف مؤكد #$txId بمبلغ ${MoneyUtils.formatMinor(finalAmount, list.currency)}",
                reviewStatus = "CONFIRMED",
                timestamp = now
            )
        )
        return txId
    }

    // ==================== سجلات الاستيراد والتدقيق والحذف الكلي ====================
    @Query("SELECT * FROM import_audit_logs ORDER BY timestamp DESC LIMIT 200")
    fun observeAuditLogs(): Flow<List<ImportAuditLogEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAuditLog(log: ImportAuditLogEntity): Long

    @Query("DELETE FROM transactions")
    suspend fun deleteAllTransactions()

    @Query("DELETE FROM suggested_transactions")
    suspend fun deleteAllSuggestedTransactions()

    @Query("DELETE FROM accounts")
    suspend fun deleteAllAccounts()

    @Query("DELETE FROM budgets")
    suspend fun deleteAllBudgets()

    @Query("DELETE FROM obligations")
    suspend fun deleteAllObligations()

    @Query("DELETE FROM installment_payments")
    suspend fun deleteAllInstallmentPayments()

    @Query("DELETE FROM savings_goals")
    suspend fun deleteAllSavingsGoals()

    @Query("DELETE FROM savings_movements")
    suspend fun deleteAllSavingsMovements()

    @Query("DELETE FROM shopping_lists")
    suspend fun deleteAllShoppingLists()

    @Query("DELETE FROM shopping_items")
    suspend fun deleteAllShoppingItems()

    @Query("DELETE FROM import_audit_logs")
    suspend fun deleteAllAuditLogs()

    @Transaction
    suspend fun wipeAllUserData() {
        deleteAllTransactions()
        deleteAllSuggestedTransactions()
        deleteAllInstallmentPayments()
        deleteAllObligations()
        deleteAllBudgets()
        deleteAllSavingsMovements()
        deleteAllSavingsGoals()
        deleteAllShoppingItems()
        deleteAllShoppingLists()
        deleteAllAccounts()
        deleteAllMerchantMemories()
        deleteAllIgnoredKeywords()
        deleteAllAuditLogs()
    }
}
