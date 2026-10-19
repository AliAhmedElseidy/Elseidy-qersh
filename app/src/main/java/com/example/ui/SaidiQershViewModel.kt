package com.example.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.local.*
import com.example.data.repository.SaidiQershRepository
import com.example.domain.ArabicVoiceExpenseParser
import com.example.domain.MoneyUtils
import com.example.domain.VoiceExpenseProposal
import com.example.receivers.BankSmsBroadcastReceiver
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.Calendar

data class CategorySpendingSlice(
    val categoryId: Long?,
    val categoryName: String,
    val colorHex: String,
    val amountMinor: Long,
    val percentage: Float
)

data class BudgetAlertItem(
    val title: String,
    val spentMinor: Long,
    val limitMinor: Long,
    val currency: String,
    val percentUsed: Int,
    val isExceeded: Boolean
)

data class DashboardSummary(
    val selectedYearMonth: String = MoneyUtils.currentYearMonth(),
    val selectedMonthLabelAr: String = MoneyUtils.formatYearMonthArabic(MoneyUtils.currentYearMonth()),
    val primaryCurrency: String = "SAR",
    val todayExpenseMinor: Long = 0L,
    val monthExpenseMinor: Long = 0L,
    val prevMonthExpenseMinor: Long = 0L,
    val monthIncomeMinor: Long = 0L,
    val netAccountsBalanceMinor: Long = 0L,
    val monthlyBudgetLimitMinor: Long = 8000_00L,
    val remainingBudgetMinor: Long = 8000_00L,
    val dailySafeSpendLimitMinor: Long = 266_00L,
    val daysRemainingInMonth: Int = 15,
    val smartInsights: List<String> = emptyList(),
    val categoryBreakdown: List<CategorySpendingSlice> = emptyList(),
    val budgetAlerts: List<BudgetAlertItem> = emptyList(),
    val dueObligations: List<ObligationEntity> = emptyList(),
    val pendingSmsCount: Int = 0,
    val unclearSmsCount: Int = 0
) {
    val safeDailySpendMinor: Long
        get() = dailySafeSpendLimitMinor

    val remainingDaysInMonth: Int
        get() = daysRemainingInMonth

    val aiFinancialInsights: List<String>
        get() = smartInsights
}

data class TransactionFilterState(
    val searchQuery: String = "",
    val selectedType: TransactionType? = null,
    val selectedAccountId: Long? = null,
    val selectedCategoryId: Long? = null,
    val minAmountText: String = "",
    val maxAmountText: String = ""
)

class SaidiQershViewModel(
    private val repository: SaidiQershRepository
) : ViewModel() {

    private val _filterState = MutableStateFlow(TransactionFilterState())
    val filterState: StateFlow<TransactionFilterState> = _filterState.asStateFlow()

    // الشهر المستقل المختار في التطبيق (افتراضياً الشهر الحالي من يوم 1 حتى اليوم)
    private val _selectedYearMonth = MutableStateFlow(MoneyUtils.currentYearMonth())
    val selectedYearMonth: StateFlow<String> = _selectedYearMonth.asStateFlow()

    private val _selectedCurrency = MutableStateFlow("SAR")
    val selectedCurrency: StateFlow<String> = _selectedCurrency.asStateFlow()

    private val _statusBannerMessage = MutableStateFlow<String?>(null)
    val statusBannerMessage: StateFlow<String?> = _statusBannerMessage.asStateFlow()

    private val _voiceProposal = MutableStateFlow<VoiceExpenseProposal?>(null)
    val voiceProposal: StateFlow<VoiceExpenseProposal?> = _voiceProposal.asStateFlow()

    private val _pinCode = MutableStateFlow<String?>(null)
    val pinCode: StateFlow<String?> = _pinCode.asStateFlow()

    private val _isAppUnlocked = MutableStateFlow(true)
    val isAppUnlocked: StateFlow<Boolean> = _isAppUnlocked.asStateFlow()

    // null = زي الموبايل (تلقائي حسب النظام)، false = فاتح، true = غامق
    private val _isDarkModePreferred = MutableStateFlow<Boolean?>(null)
    val isDarkModePreferred: StateFlow<Boolean?> = _isDarkModePreferred.asStateFlow()

    val banks: StateFlow<List<BankEntity>> = repository.banksFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val smsRules: StateFlow<List<SmsRuleEntity>> = repository.smsRulesFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val ignoredKeywords: StateFlow<List<IgnoredKeywordEntity>> = repository.ignoredKeywordsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val merchantMemories: StateFlow<List<MerchantMemoryEntity>> = repository.merchantMemoriesFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val accounts: StateFlow<List<AccountEntity>> = repository.accountsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val categories: StateFlow<List<CategoryEntity>> = repository.categoriesFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allConfirmedTransactions: StateFlow<List<TransactionEntity>> = repository.confirmedTransactionsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val suggestedTransactions: StateFlow<List<SuggestedTransactionEntity>> = repository.suggestedTransactionsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * رسائل الشهر المختار فقط (تحديد نطاق قراءة وعرض الرسائل بالشهر المختار/الحالي فقط لمنع تراكم الرسائل القديمة)
     */
    val monthlySuggestedTransactions: StateFlow<List<SuggestedTransactionEntity>> = combine(
        suggestedTransactions,
        _selectedYearMonth
    ) { list, ym ->
        val (monthStart, monthEnd) = MoneyUtils.monthRangeMillis(ym)
        val currentYm = MoneyUtils.currentYearMonth()
        list.filter { item ->
            val inSelectedMonth = item.smsTimestamp in monthStart..monthEnd
            inSelectedMonth || (item.isTestSandbox && ym == currentYm)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val budgets: StateFlow<List<BudgetEntity>> = repository.budgetsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val obligations: StateFlow<List<ObligationEntity>> = repository.obligationsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val savingsGoals: StateFlow<List<SavingsGoalEntity>> = repository.savingsGoalsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val shoppingLists: StateFlow<List<ShoppingListEntity>> = repository.shoppingListsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val shoppingItems: StateFlow<List<ShoppingItemEntity>> = repository.shoppingItemsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val auditLogs: StateFlow<List<ImportAuditLogEntity>> = repository.auditLogsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        viewModelScope.launch {
            repository.ensureSeeded()
        }
    }

    fun selectPreviousMonth() {
        _selectedYearMonth.update { MoneyUtils.previousYearMonth(it) }
    }

    fun selectNextMonth() {
        _selectedYearMonth.update { MoneyUtils.nextYearMonth(it) }
    }

    fun resetToCurrentMonth() {
        _selectedYearMonth.value = MoneyUtils.currentYearMonth()
    }

    fun setSelectedYearMonth(yearMonth: String) {
        _selectedYearMonth.value = yearMonth
    }

    val filteredTransactions: StateFlow<List<TransactionEntity>> = combine(
        allConfirmedTransactions,
        _filterState,
        _selectedYearMonth
    ) { list, filter, ym ->
        val (monthStart, monthEnd) = MoneyUtils.monthRangeMillis(ym)
        val minMinor = MoneyUtils.parseToMinorUnits(filter.minAmountText)
        val maxMinor = MoneyUtils.parseToMinorUnits(filter.maxAmountText)
        val q = filter.searchQuery.trim().lowercase()

        list.filter { tx ->
            val inMonth = tx.timestamp in monthStart..monthEnd
            val matchesQuery = q.isEmpty() ||
                tx.description.lowercase().contains(q) ||
                tx.merchantName.lowercase().contains(q) ||
                tx.notes.lowercase().contains(q) ||
                tx.bankName.lowercase().contains(q) ||
                (tx.referenceNumber?.lowercase()?.contains(q) == true)
            val matchesType = filter.selectedType == null || tx.type == filter.selectedType
            val matchesAccount = filter.selectedAccountId == null ||
                tx.accountId == filter.selectedAccountId ||
                tx.toAccountId == filter.selectedAccountId
            val matchesCategory = filter.selectedCategoryId == null || tx.categoryId == filter.selectedCategoryId
            val matchesMin = minMinor == null || tx.amountMinor >= minMinor
            val matchesMax = maxMinor == null || tx.amountMinor <= maxMinor
            inMonth && matchesQuery && matchesType && matchesAccount && matchesMax && matchesMin
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val dashboardSummary: StateFlow<DashboardSummary> = combine(
        allConfirmedTransactions,
        accounts,
        categories,
        budgets,
        obligations,
        monthlySuggestedTransactions,
        _selectedCurrency,
        _selectedYearMonth,
        merchantMemories
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        val txs = values[0] as List<TransactionEntity>
        @Suppress("UNCHECKED_CAST")
        val accs = values[1] as List<AccountEntity>
        @Suppress("UNCHECKED_CAST")
        val cats = values[2] as List<CategoryEntity>
        @Suppress("UNCHECKED_CAST")
        val bds = values[3] as List<BudgetEntity>
        @Suppress("UNCHECKED_CAST")
        val obls = values[4] as List<ObligationEntity>
        @Suppress("UNCHECKED_CAST")
        val suggestedInMonth = values[5] as List<SuggestedTransactionEntity>
        val cur = values[6] as String
        val selectedYm = values[7] as String
        @Suppress("UNCHECKED_CAST")
        val memories = values[8] as List<MerchantMemoryEntity>

        val now = System.currentTimeMillis()
        val (todayStart, todayEnd) = MoneyUtils.todayRangeMillis(now)
        val prevYm = MoneyUtils.previousYearMonth(selectedYm)
        val (monthStart, monthEnd) = MoneyUtils.monthRangeMillis(selectedYm)
        val (prevStart, prevEnd) = MoneyUtils.monthRangeMillis(prevYm)

        val todayTxs = txs.filter { it.timestamp in todayStart..todayEnd }
        val monthTxs = txs.filter { it.timestamp in monthStart..monthEnd }
        val prevMonthTxs = txs.filter { it.timestamp in prevStart..prevEnd }

        val todayExpense = SaidiQershRepository.computeNetConfirmedExpensesMinor(todayTxs)
        val monthExpense = SaidiQershRepository.computeNetConfirmedExpensesMinor(monthTxs)
        val prevMonthExpense = SaidiQershRepository.computeNetConfirmedExpensesMinor(prevMonthTxs)
        val monthIncome = SaidiQershRepository.computeConfirmedIncomeMinor(monthTxs)

        val netBalance = accs.sumOf { it.currentBalanceMinor }

        val currentMonthBudgets = bds.filter { it.yearMonth == selectedYm }
        val totalBudgetObj = currentMonthBudgets.firstOrNull { it.categoryKey == "TOTAL" }
            ?: bds.firstOrNull { it.categoryKey == "TOTAL" }
        val budgetLimit = totalBudgetObj?.limitMinor ?: 8000_00L
        val remainingBudget = budgetLimit - monthExpense

        val cal = Calendar.getInstance()
        val currentDay = cal.get(Calendar.DAY_OF_MONTH).coerceAtLeast(1)
        val maxDay = cal.getActualMaximum(Calendar.DAY_OF_MONTH).coerceAtLeast(28)
        val daysLeft = (maxDay - currentDay + 1).coerceAtLeast(1)
        val dailySafeSpend = (remainingBudget.coerceAtLeast(0L)) / daysLeft

        val catMap = cats.associateBy { it.id }
        val expenseTxsThisMonth = monthTxs.filter {
            it.type == TransactionType.EXPENSE || it.type == TransactionType.OBLIGATION_PAYMENT
        }
        val totalGrossCatExpense = expenseTxsThisMonth.sumOf { it.amountMinor }.coerceAtLeast(1L)
        val breakdown = expenseTxsThisMonth
            .groupBy { it.categoryId }
            .map { (catId, group) ->
                val cat = catId?.let { catMap[it] }
                val sum = group.sumOf { it.amountMinor }
                CategorySpendingSlice(
                    categoryId = catId,
                    categoryName = cat?.nameAr ?: "أخرى",
                    colorHex = cat?.colorHex ?: "#0D4732",
                    amountMinor = sum,
                    percentage = (sum.toFloat() / totalGrossCatExpense.toFloat()).coerceIn(0f, 1f)
                )
            }
            .sortedByDescending { it.amountMinor }

        val insights = mutableListOf<String>()
        insights.add(
            "الحد اليومي الآمن للصرف: ${MoneyUtils.formatMinor(dailySafeSpend, cur)} يومياً خلال الـ $daysLeft يوماً المتبقية من الشهر."
        )
        breakdown.firstOrNull()?.let { topSlice ->
            val pctTop = (topSlice.percentage * 100).toInt()
            insights.add(
                "أعلى تصنيف استهلاكاً هذا الشهر هو «${topSlice.categoryName}» بنسبة $pctTop% (${MoneyUtils.formatMinor(topSlice.amountMinor, cur)})."
            )
        }
        if (memories.isNotEmpty()) {
            insights.add(
                "ذاكرة التاجر الذكية تحفظ حالياً ${memories.size} تاجر (منها: ${memories.take(3).joinToString("، ") { it.merchantDisplayName }}) للربط التلقائي الفوري."
            )
        }

        val alerts = mutableListOf<BudgetAlertItem>()
        if (budgetLimit > 0L) {
            val pct = ((monthExpense * 100L) / budgetLimit).toInt()
            if (pct >= 80) {
                alerts.add(
                    BudgetAlertItem(
                        title = "الميزانية الشهرية الإجمالية",
                        spentMinor = monthExpense,
                        limitMinor = budgetLimit,
                        currency = cur,
                        percentUsed = pct,
                        isExceeded = pct >= 100
                    )
                )
            }
        }
        for (catBudget in currentMonthBudgets.filter { it.categoryKey != "TOTAL" }) {
            val spentForCat = expenseTxsThisMonth
                .filter { it.categoryId == catBudget.categoryId }
                .sumOf { it.amountMinor }
            if (catBudget.limitMinor > 0L) {
                val pct = ((spentForCat * 100L) / catBudget.limitMinor).toInt()
                if (pct >= 80) {
                    alerts.add(
                        BudgetAlertItem(
                            title = "ميزانية ${catBudget.categoryName}",
                            spentMinor = spentForCat,
                            limitMinor = catBudget.limitMinor,
                            currency = catBudget.currency,
                            percentUsed = pct,
                            isExceeded = pct >= 100
                        )
                    )
                }
            }
        }

        val pendingSms = suggestedInMonth.count { it.status == ReviewStatus.PENDING && !it.isTestSandbox }
        val unclearSms = suggestedInMonth.count { it.status == ReviewStatus.UNCLEAR && !it.isTestSandbox }

        DashboardSummary(
            selectedYearMonth = selectedYm,
            selectedMonthLabelAr = MoneyUtils.formatYearMonthArabic(selectedYm),
            primaryCurrency = cur,
            todayExpenseMinor = todayExpense,
            monthExpenseMinor = monthExpense,
            prevMonthExpenseMinor = prevMonthExpense,
            monthIncomeMinor = monthIncome,
            netAccountsBalanceMinor = netBalance,
            monthlyBudgetLimitMinor = budgetLimit,
            remainingBudgetMinor = remainingBudget,
            dailySafeSpendLimitMinor = dailySafeSpend,
            daysRemainingInMonth = daysLeft,
            smartInsights = insights,
            categoryBreakdown = breakdown,
            budgetAlerts = alerts,
            dueObligations = obls.filter { !it.isCompleted }.take(5),
            pendingSmsCount = pendingSms,
            unclearSmsCount = unclearSms
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), DashboardSummary())

    fun dismissBanner() {
        _statusBannerMessage.value = null
    }

    fun showBanner(message: String) {
        _statusBannerMessage.value = message
    }

    fun updateFilter(transform: (TransactionFilterState) -> TransactionFilterState) {
        _filterState.update(transform)
    }

    fun clearFilters() {
        _filterState.value = TransactionFilterState()
    }

    fun toggleDarkMode(dark: Boolean?) {
        _isDarkModePreferred.value = dark
    }

    fun setPinProtection(newPin: String?) {
        _pinCode.value = newPin?.takeIf { it.length >= 4 }
        _isAppUnlocked.value = true
        showBanner(if (newPin.isNullOrBlank()) "تم إلغاء قفل الرمز السري (PIN)" else "تم تفعيل حماية التطبيق برمز PIN")
    }

    fun lockAppNow() {
        if (!_pinCode.value.isNullOrBlank()) {
            _isAppUnlocked.value = false
        }
    }

    fun tryUnlockWithPin(enteredPin: String): Boolean {
        val current = _pinCode.value
        if (current == null || current == enteredPin) {
            _isAppUnlocked.value = true
            return true
        }
        return false
    }

    // ==================== نظام كلمات التجاهل المخصصة (Ignored Keywords Manager) ====================
    fun addIgnoredKeyword(keyword: String) {
        val clean = keyword.trim()
        if (clean.isEmpty()) return
        viewModelScope.launch {
            repository.addIgnoredKeyword(clean)
            showBanner("تمت إضافة الكلمة «$clean» لقائمة كلمات التجاهل التلقائي")
        }
    }

    fun toggleIgnoredKeyword(item: IgnoredKeywordEntity, isActive: Boolean) {
        viewModelScope.launch {
            repository.toggleIgnoredKeyword(item, isActive)
            showBanner(
                if (isActive) "تم تفعيل تجاهل الرسائل المحتوية على «${item.keyword}»"
                else "تم إيقاف تجاهل الكلمة «${item.keyword}»"
            )
        }
    }

    fun deleteIgnoredKeyword(item: IgnoredKeywordEntity) {
        viewModelScope.launch {
            repository.deleteIgnoredKeyword(item)
            showBanner("تم حذف الكلمة «${item.keyword}» من قائمة التجاهل")
        }
    }

    // ==================== ذاكرة التاجر الذكية (Smart Merchant Memory) ====================
    fun saveMerchantMemory(merchantName: String, categoryId: Long?, accountId: Long?) {
        val clean = merchantName.trim()
        if (clean.isEmpty()) return
        viewModelScope.launch {
            repository.saveMerchantMemory(clean, categoryId, accountId)
            showBanner("تم حفظ التاجر «$clean» في ذاكرة التاجر الذكية")
        }
    }

    fun deleteMerchantMemory(item: MerchantMemoryEntity) {
        viewModelScope.launch {
            repository.deleteMerchantMemory(item)
            showBanner("تم حذف «${item.merchantDisplayName}» من ذاكرة التاجر الذكية")
        }
    }

    // ==================== العمليات المالية ====================
    fun saveTransaction(
        existingId: Long = 0L,
        type: TransactionType,
        amountText: String,
        currency: String,
        description: String,
        notes: String,
        accountId: Long,
        toAccountId: Long? = null,
        categoryId: Long? = null,
        merchantName: String = "",
        isMonthlyObligation: Boolean = false,
        linkedObligationId: Long? = null,
        source: TransactionSource = TransactionSource.MANUAL,
        timestamp: Long = System.currentTimeMillis()
    ) {
        val amountMinor = MoneyUtils.parseToMinorUnits(amountText)
        if (amountMinor == null || amountMinor <= 0L) {
            showBanner("يرجى إدخال مبلغ مالي صحيح أكبر من الصفر")
            return
        }
        if (type == TransactionType.TRANSFER && (toAccountId == null || toAccountId == accountId)) {
            showBanner("يرجى اختيار حساب مستلم مختلف لإتمام التحويل")
            return
        }

        viewModelScope.launch {
            val acc = accounts.value.firstOrNull { it.id == accountId }
            val catName = categories.value.firstOrNull { it.id == categoryId }?.nameAr ?: type.titleAr
            val finalDescription = description.ifBlank {
                if (merchantName.isNotBlank()) merchantName else catName
            }
            val tx = TransactionEntity(
                id = existingId,
                type = if (isMonthlyObligation && type == TransactionType.EXPENSE) TransactionType.OBLIGATION_PAYMENT else type,
                amountMinor = amountMinor,
                currency = currency,
                description = finalDescription,
                notes = notes,
                timestamp = timestamp,
                accountId = accountId,
                toAccountId = toAccountId,
                categoryId = if (type == TransactionType.TRANSFER) null else categoryId,
                bankId = acc?.bankId,
                bankName = acc?.bankName ?: "",
                merchantName = merchantName.ifBlank { description.trim() },
                source = source,
                obligationId = linkedObligationId,
                isMonthlyObligationPayment = isMonthlyObligation
            )
            if (existingId == 0L) {
                repository.addConfirmedTransaction(tx)
                if (isMonthlyObligation && linkedObligationId != null) {
                    repository.recordObligationPayment(
                        obligationId = linkedObligationId,
                        paymentKey = "${MoneyUtils.currentYearMonth()}-${System.currentTimeMillis() % 10000}",
                        amountMinor = amountMinor,
                        accountId = accountId,
                        alreadyRecordedAsExpense = true,
                        notes = finalDescription
                    )
                }
                showBanner("تم حفظ العملية وتحديث الأرصدة وذاكرة التاجر الذكية مباشرة")
            } else {
                repository.updateConfirmedTransaction(tx)
                showBanner("تم تعديل العملية وتحديث سجل التعديلات والأرصدة")
            }
        }
    }

    fun deleteTransactionConfirmed(txId: Long) {
        viewModelScope.launch {
            repository.deleteConfirmedTransaction(txId)
            showBanner("تم حذف العملية واستعادة الرصيد في الحساب")
        }
    }

    // ==================== الحسابات والبنوك والتصنيفات ====================
    fun addOrUpdateAccount(
        existingId: Long = 0L,
        name: String,
        type: AccountType,
        bankId: Long?,
        currency: String,
        openingBalanceText: String,
        smsEnabled: Boolean,
        accountLast4: String = "",
        cardLast4: String = ""
    ) {
        val openingMinor = MoneyUtils.parseToMinorUnits(openingBalanceText) ?: 0L
        viewModelScope.launch {
            val bank = banks.value.firstOrNull { it.id == bankId }
            val acc = AccountEntity(
                id = existingId,
                name = name.ifBlank { type.titleAr },
                type = type,
                bankId = bankId,
                bankName = bank?.nameAr ?: if (type == AccountType.CASH) "نقدي" else "",
                currency = currency,
                openingBalanceMinor = openingMinor,
                currentBalanceMinor = openingMinor,
                accountLast4 = accountLast4.trim(),
                cardLast4 = cardLast4.trim(),
                smsParsingEnabled = smsEnabled
            )
            repository.saveAccount(acc)
            showBanner("تم حفظ الحساب بنجاح")
        }
    }

    fun reconcileAccount(accountId: Long, newBalanceText: String, reason: String) {
        val newMinor = MoneyUtils.parseToMinorUnits(newBalanceText) ?: return
        viewModelScope.launch {
            repository.reconcileAccountBalance(accountId, newMinor, reason)
            showBanner("تمت تسوية رصيد الحساب وحفظ سجل التسوية")
        }
    }

    fun toggleAccountSms(account: AccountEntity, enabled: Boolean) {
        viewModelScope.launch {
            repository.updateAccount(account.copy(smsParsingEnabled = enabled))
        }
    }

    /**
     * Toggle 1: قراءة رسائل هذه الجهة (مفعّل / معطّل)
     */
    fun toggleBankSms(bank: BankEntity, enabled: Boolean) {
        viewModelScope.launch {
            repository.toggleBankSmsEnabled(bank, enabled)
            showBanner(if (enabled) "تم تفعيل قراءة رسائل ${bank.nameAr}" else "تم إيقاف وتجاهل رسائل ${bank.nameAr}")
        }
    }

    /**
     * Toggle 2 (ميزة مستقلة): اعتماد وتسجيل تلقائي فوري (Auto-Approve)
     */
    fun toggleBankAutoApprove(bank: BankEntity, autoApprove: Boolean) {
        viewModelScope.launch {
            repository.toggleBankAutoApprove(bank, autoApprove)
            showBanner(
                if (autoApprove) "تم تفعيل الاعتماد والتسجيل التلقائي الفوري لرسائل ${bank.nameAr}"
                else "تم إيقاف الاعتماد التلقائي لرسائل ${bank.nameAr} (ستحتاج مراجعة يدوية)"
            )
        }
    }

    fun addCustomBank(nameAr: String, code: String, currency: String, senderIds: String) {
        if (nameAr.isBlank() || senderIds.isBlank()) return
        viewModelScope.launch {
            repository.addCustomBank(nameAr, nameAr, code.ifBlank { "BANK_${System.currentTimeMillis() % 1000}" }, currency, senderIds)
            showBanner("تمت إضافة جهة الرسائل «$nameAr» إلى قائمة الجهات المسموحة")
        }
    }

    fun addCustomSmsRule(bankId: Long, ruleName: String, pattern: String, type: SmsSuggestedType) {
        if (ruleName.isBlank() || pattern.isBlank()) return
        viewModelScope.launch {
            repository.addCustomSmsRule(bankId, ruleName, pattern, type)
            showBanner("تمت إضافة قاعدة تحليل الرسائل بنجاح")
        }
    }

    fun saveCategory(existingId: Long = 0L, nameAr: String, type: CategoryType, colorHex: String, iconName: String = "📦") {
        if (nameAr.isBlank()) return
        viewModelScope.launch {
            repository.saveCategory(
                CategoryEntity(
                    id = existingId,
                    nameAr = nameAr.trim(),
                    type = type,
                    iconName = iconName,
                    colorHex = colorHex
                )
            )
            showBanner("تم حفظ التصنيف «$nameAr»")
        }
    }

    // ==================== معالجة ومراجعة رسائل البنوك ====================
    fun ingestSmsMessage(sender: String, body: String, isTestSandbox: Boolean = false) {
        if (body.isBlank()) return
        viewModelScope.launch {
            val id = repository.ingestBankSms(
                senderAddress = sender.ifBlank { "Alinma" },
                rawBody = body,
                isTestSandbox = isTestSandbox
            )
            if (id != null && id > 0L) {
                showBanner("تم تحليل الرسالة من المرسل «${sender.ifBlank { "Alinma" }}» بنجاح")
            } else {
                showBanner("تم استبعاد الرسالة تلقائياً (الجهة معطّلة / كلمة متجاهلة / OTP / مكررة)")
            }
        }
    }

    fun loadRealisticBankSamplesIntoSandbox() {
        viewModelScope.launch {
            val samples = listOf(
                "Alinma" to "شراء عبر Samsung Wallet مبلغ: ريال سعودى 10 بطاقة مدى: *5650 حساب: *2000 من: dani est",
                "Alinma" to "شراء عبر نقاط البيع مبلغ: ريال سعودي 250 بطاقة مدى: *5650 حساب: *2000 من: Tamara مرجع: TMR402",
                "Enjaz" to "حوالة صادرة عبر إنجاز بمبلغ: 550.00 ريال سعودي المستفيد: محمد أحمد مرجع: ENJ99120",
                "Urpay" to "شراء عبر محفظة Urpay بمبلغ: SAR 35.00 لدى: كافيه القهوة المختصة مرجع: URP4410",
                "STC Pay" to "عملية شراء بطاقة STC Pay بمبلغ 120.00 ر.س لدى: صيدلية النهدي مرجع: STC8812",
                "VF-Cash" to "تم تحويل مبلغ 450.00 ج.م من محفظة فودافون كاش رقم المرجع: VF77201",
                "AlRajhiBank" to "شراء عبر نقاط البيع\nبطاقة: *4821;مدى-أثير\nبمبلغ: SAR 145.50\nلدى: أسواق التميمي\nمرجع: RJ8849201",
                "SNB AlAhli" to "عملية شراء مدى\nبمبلغ 68.00 ر.س\nلدى محطة الدريس للوقود\nرقم المرجع: SNB441092",
                "Alinma" to "تم تفعيل مستفيد بنجاح في مصرف الإنماء، رمز التحقق OTP هو 8821 لا تشاركه مع أحد",
                "AlRajhiBank" to "تسجيل دخول لحسابكم في مباشر الأفراد، كلمة المرور المؤقتة: 4419"
            )
            var count = 0
            for ((sender, text) in samples) {
                val id = repository.ingestBankSms(sender, text, isTestSandbox = true)
                if (id != null && id > 0L) count++
            }
            showBanner("تم تحليل الرسائل بالمرسل الحقيقي (Alinma, Enjaz, Urpay, STC Pay, VF-Cash) وإضافة $count عملية")
        }
    }

    fun clearSandboxSuggestions() {
        viewModelScope.launch {
            repository.clearTestSandboxSuggestions()
            showBanner("تم مسح بيانات الاختبار التجريبية بالكامل مع الحفاظ على بياناتك الحقيقية")
        }
    }

    fun scanPhoneSmsInbox(context: Context) {
        viewModelScope.launch {
            try {
                val targetYm = _selectedYearMonth.value
                val count = BankSmsBroadcastReceiver.scanDeviceInboxForBankSms(context, targetYm)
                val monthLabel = MoneyUtils.formatYearMonthArabic(targetYm)
                showBanner(
                    if (count > 0) "تم استيراد $count رسالة مالية جديدة لشهر $monthLabel واكتشاف جهات الإرسال"
                    else "تم فحص رسائل شهر $monthLabel؛ لا توجد رسائل مالية جديدة غير مراجعة"
                )
            } catch (e: SecurityException) {
                showBanner("يرجى منح صلاحية قراءة الرسائل أولًا لفحص صندوق الوارد")
            } catch (e: Exception) {
                showBanner("تعذر فحص صندوق الوارد: ${e.localizedMessage ?: ""}")
            }
        }
    }

    fun confirmSuggested(
        suggestedId: Long,
        overrideAmountMinor: Long? = null,
        overrideMerchant: String? = null,
        overrideCategoryId: Long? = null,
        overrideAccountId: Long? = null,
        overrideToAccountId: Long? = null,
        overrideNotes: String? = null
    ) {
        viewModelScope.launch {
            val txId = repository.confirmSuggestedTransaction(
                suggestedId = suggestedId,
                overrideAmountMinor = overrideAmountMinor,
                overrideMerchant = overrideMerchant,
                overrideCategoryId = overrideCategoryId,
                overrideAccountId = overrideAccountId,
                overrideToAccountId = overrideToAccountId,
                overrideNotes = overrideNotes
            )
            if (txId != null) {
                showBanner("تمت إضافة العملية وتسجيلها وتحديث الأرصدة وذاكرة التاجر الذكية بنجاح")
            } else {
                showBanner("يرجى تحديد المبلغ أولاً قبل إضافة العملية")
            }
        }
    }

    fun editSuggestedBeforeConfirm(
        suggested: SuggestedTransactionEntity,
        amountText: String,
        merchant: String,
        categoryId: Long?,
        accountId: Long?,
        type: SmsSuggestedType
    ) {
        val minor = MoneyUtils.parseToMinorUnits(amountText) ?: 0L
        val catName = categories.value.firstOrNull { it.id == categoryId }?.nameAr ?: suggested.suggestedCategoryName
        viewModelScope.launch {
            repository.updateSuggestedBeforeConfirm(
                suggested = suggested,
                newAmountMinor = minor,
                newMerchant = merchant,
                newCategoryId = categoryId,
                newCategoryName = catName,
                newAccountId = accountId,
                newType = type
            )
            showBanner("تم تحديث بيانات العملية وحفظ ربط التاجر «$merchant» في الذاكرة الذكية")
        }
    }

    fun ignoreSuggested(suggested: SuggestedTransactionEntity) {
        viewModelScope.launch {
            repository.ignoreSuggestedTransaction(suggested)
            showBanner("تم تجاهل الرسالة واستبعادها من السجل المالي")
        }
    }

    fun markSuggestedDuplicate(suggested: SuggestedTransactionEntity) {
        viewModelScope.launch {
            repository.markSuggestedAsDuplicate(suggested)
            showBanner("تم تعليم الرسالة كمكررة")
        }
    }

    // ==================== الميزانيات والالتزامات والتوفير ومشتريات البيت ====================
    fun saveMonthlyBudget(categoryId: Long?, limitText: String, currency: String) {
        val limitMinor = MoneyUtils.parseToMinorUnits(limitText) ?: return
        if (limitMinor <= 0L) return
        viewModelScope.launch {
            val ym = _selectedYearMonth.value
            val cat = categories.value.firstOrNull { it.id == categoryId }
            val key = categoryId?.toString() ?: "TOTAL"
            val name = cat?.nameAr ?: "الميزانية الشهرية الإجمالية"
            repository.saveBudget(
                BudgetEntity(
                    yearMonth = ym,
                    categoryKey = key,
                    categoryId = categoryId,
                    categoryName = name,
                    limitMinor = limitMinor,
                    currency = currency
                )
            )
            showBanner("تم تحديث $name لشهر ${MoneyUtils.formatYearMonthArabic(ym)}")
        }
    }

    fun addObligation(
        title: String,
        kind: ObligationKind,
        totalText: String,
        installmentText: String,
        currency: String,
        dueDaysFromNow: Int,
        isRecurring: Boolean,
        roscaPayoutMonth: String,
        accountId: Long?,
        categoryId: Long?
    ) {
        val totalMinor = MoneyUtils.parseToMinorUnits(totalText) ?: return
        val instMinor = MoneyUtils.parseToMinorUnits(installmentText) ?: totalMinor
        if (title.isBlank() || totalMinor <= 0L) return
        viewModelScope.launch {
            val dueMillis = System.currentTimeMillis() + (dueDaysFromNow * 24L * 60L * 60L * 1000L)
            repository.saveObligation(
                ObligationEntity(
                    title = title.trim(),
                    kind = kind,
                    totalAmountMinor = totalMinor,
                    installmentAmountMinor = instMinor,
                    currency = currency,
                    dueDateMillis = dueMillis,
                    isRecurring = isRecurring,
                    roscaPayoutMonth = roscaPayoutMonth,
                    accountId = accountId,
                    categoryId = categoryId
                )
            )
            showBanner("تمت إضافة «$title» إلى قائمة الالتزامات والأقساط")
        }
    }

    fun payObligationInstallment(
        obligation: ObligationEntity,
        paymentKey: String,
        amountText: String,
        accountId: Long?,
        alreadyRecordedAsExpense: Boolean
    ) {
        val amountMinor = MoneyUtils.parseToMinorUnits(amountText) ?: obligation.installmentAmountMinor
        viewModelScope.launch {
            val ok = repository.recordObligationPayment(
                obligationId = obligation.id,
                paymentKey = paymentKey.ifBlank { _selectedYearMonth.value },
                amountMinor = amountMinor,
                accountId = accountId ?: obligation.accountId ?: accounts.value.firstOrNull()?.id,
                alreadyRecordedAsExpense = alreadyRecordedAsExpense
            )
            if (ok) {
                showBanner(
                    if (alreadyRecordedAsExpense) "تم خصم الدفعة من المتبقي دون تكرار احتسابها كمصروف"
                    else "تم تسجيل سداد الدفعة وخصمها من الحساب والميزانية"
                )
            } else {
                showBanner("هذه الدفعة ($paymentKey) مسجلة مسبقًا! تم منع التكرار تلقائيًا")
            }
        }
    }

    fun addSavingsGoal(title: String, targetText: String, currency: String, daysToTarget: Int) {
        val targetMinor = MoneyUtils.parseToMinorUnits(targetText) ?: return
        if (title.isBlank() || targetMinor <= 0L) return
        viewModelScope.launch {
            val targetMillis = System.currentTimeMillis() + (daysToTarget.coerceAtLeast(1) * 24L * 60L * 60L * 1000L)
            repository.saveSavingsGoal(
                SavingsGoalEntity(
                    title = title.trim(),
                    targetAmountMinor = targetMinor,
                    currency = currency,
                    targetDateMillis = targetMillis
                )
            )
            showBanner("تم إنشاء صندوق التوفير «$title»")
        }
    }

    fun recordSavingsMovement(goalId: Long, isDeposit: Boolean, amountText: String, note: String) {
        val minor = MoneyUtils.parseToMinorUnits(amountText) ?: return
        viewModelScope.launch {
            val ok = repository.recordSavingsMovement(goalId, isDeposit, minor, note)
            if (ok) {
                showBanner(if (isDeposit) "تم تسجيل الإيداع في صندوق التوفير" else "تم تسجيل السحب من صندوق التوفير")
            }
        }
    }

    fun createShoppingList(title: String, currency: String) {
        if (title.isBlank()) return
        viewModelScope.launch {
            repository.createShoppingList(title.trim(), currency)
            showBanner("تم إنشاء قائمة المشتريات «$title»")
        }
    }

    fun addShoppingItem(listId: Long, name: String, quantity: Int, priceText: String) {
        if (name.isBlank()) return
        val unitPriceMinor = MoneyUtils.parseToMinorUnits(priceText) ?: 0L
        viewModelScope.launch {
            repository.addShoppingItem(
                ShoppingItemEntity(
                    listId = listId,
                    name = name.trim(),
                    quantity = quantity.coerceAtLeast(1),
                    estimatedUnitPriceMinor = unitPriceMinor,
                    isPurchased = true
                )
            )
        }
    }

    fun toggleShoppingItem(item: ShoppingItemEntity) {
        viewModelScope.launch {
            repository.toggleShoppingItemPurchased(item)
        }
    }

    fun convertShoppingListToExpenseOnce(listId: Long, accountId: Long, categoryId: Long?) {
        viewModelScope.launch {
            val txId = repository.convertShoppingListToExpenseOnce(listId, accountId, categoryId)
            if (txId != null) {
                showBanner("تم تحويل قائمة المشتريات إلى مصروف فعلي مرة واحدة فقط")
            } else {
                showBanner("تم تسجيل هذه القائمة كمصروف مسبقًا أو أن إجماليها صفر")
            }
        }
    }

    // ==================== الإملاء الصوتي بالعربية ====================
    fun analyzeSpokenExpense(phrase: String) {
        if (phrase.isBlank()) return
        val proposal = ArabicVoiceExpenseParser.parseSpokenPhrase(
            phrase = phrase,
            defaultCurrency = _selectedCurrency.value,
            categories = categories.value
        )
        _voiceProposal.value = proposal
    }

    fun clearVoiceProposal() {
        _voiceProposal.value = null
    }

    fun confirmVoiceProposal(
        proposal: VoiceExpenseProposal,
        accountId: Long,
        overrideAmountText: String? = null,
        overrideDescription: String? = null,
        overrideCategoryId: Long? = null
    ) {
        val minor = overrideAmountText?.let { MoneyUtils.parseToMinorUnits(it) } ?: proposal.amountMinor
        if (minor == null || minor <= 0L) {
            showBanner("يرجى التأكد من المبلغ قبل تأكيد الإملاء الصوتي")
            return
        }
        viewModelScope.launch {
            val acc = accounts.value.firstOrNull { it.id == accountId }
            repository.addConfirmedTransaction(
                TransactionEntity(
                    type = TransactionType.EXPENSE,
                    amountMinor = minor,
                    currency = proposal.currency,
                    description = (overrideDescription ?: proposal.description).ifBlank { "مصروف صوتي" },
                    notes = "إملاء صوتي: «${proposal.rawTranscript}»",
                    accountId = accountId,
                    categoryId = overrideCategoryId ?: proposal.suggestedCategoryId,
                    bankId = acc?.bankId,
                    bankName = acc?.bankName ?: "",
                    source = TransactionSource.VOICE
                )
            )
            _voiceProposal.value = null
            showBanner("تم تأكيد وتسجيل المصروف الصوتي بنجاح")
        }
    }

    // ==================== التصدير والنسخ الاحتياطي والحذف النهائي ====================
    suspend fun generateCsvExport(): String = repository.exportConfirmedTransactionsCsv()

    suspend fun generateBackupPayload(passphrase: String?): String = repository.createLocalBackupJson(passphrase)

    fun restoreFromBackupPayload(payload: String, passphrase: String?) {
        viewModelScope.launch {
            val result = repository.restoreLocalBackupJson(payload, passphrase)
            result.onSuccess { count ->
                showBanner("تم التحقق من النسخة الاحتياطية واستعادة $count عملية بنجاح")
            }.onFailure { err ->
                showBanner("فشل استيراد النسخة الاحتياطية: ${err.message}")
            }
        }
    }

    fun wipeAllDataConfirmed() {
        viewModelScope.launch {
            repository.wipeAllDataAfterConfirmation()
            showBanner("تم حذف جميع البيانات نهائيًا وإعادة تهيئة التطبيق")
        }
    }

    class Factory(private val repository: SaidiQershRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return SaidiQershViewModel(repository) as T
        }
    }
}
