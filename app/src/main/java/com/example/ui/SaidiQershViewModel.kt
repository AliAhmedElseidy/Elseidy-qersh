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
    val primaryCurrency: String = "SAR",
    val todayExpenseMinor: Long = 0L,
    val monthExpenseMinor: Long = 0L,
    val prevMonthExpenseMinor: Long = 0L,
    val monthIncomeMinor: Long = 0L,
    val netAccountsBalanceMinor: Long = 0L,
    val monthlyBudgetLimitMinor: Long = 8000_00L,
    val remainingBudgetMinor: Long = 8000_00L,
    val categoryBreakdown: List<CategorySpendingSlice> = emptyList(),
    val budgetAlerts: List<BudgetAlertItem> = emptyList(),
    val dueObligations: List<ObligationEntity> = emptyList(),
    val pendingSmsCount: Int = 0,
    val unclearSmsCount: Int = 0
)

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

    val accounts: StateFlow<List<AccountEntity>> = repository.accountsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val categories: StateFlow<List<CategoryEntity>> = repository.categoriesFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allConfirmedTransactions: StateFlow<List<TransactionEntity>> = repository.confirmedTransactionsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val suggestedTransactions: StateFlow<List<SuggestedTransactionEntity>> = repository.suggestedTransactionsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

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

    val filteredTransactions: StateFlow<List<TransactionEntity>> = combine(
        allConfirmedTransactions,
        _filterState
    ) { list, filter ->
        val minMinor = MoneyUtils.parseToMinorUnits(filter.minAmountText)
        val maxMinor = MoneyUtils.parseToMinorUnits(filter.maxAmountText)
        val q = filter.searchQuery.trim().lowercase()

        list.filter { tx ->
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
            matchesQuery && matchesType && matchesAccount && matchesCategory && matchesMin && matchesMax
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val dashboardSummary: StateFlow<DashboardSummary> = combine(
        allConfirmedTransactions,
        accounts,
        categories,
        budgets,
        obligations,
        suggestedTransactions,
        _selectedCurrency
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
        val suggested = values[5] as List<SuggestedTransactionEntity>
        val cur = values[6] as String

        val now = System.currentTimeMillis()
        val (todayStart, todayEnd) = MoneyUtils.todayRangeMillis(now)
        val currentYm = MoneyUtils.currentYearMonth(now)
        val prevYm = MoneyUtils.previousYearMonth(currentYm)
        val (monthStart, monthEnd) = MoneyUtils.monthRangeMillis(currentYm)
        val (prevStart, prevEnd) = MoneyUtils.monthRangeMillis(prevYm)

        val todayTxs = txs.filter { it.timestamp in todayStart..todayEnd }
        val monthTxs = txs.filter { it.timestamp in monthStart..monthEnd }
        val prevMonthTxs = txs.filter { it.timestamp in prevStart..prevEnd }

        val todayExpense = SaidiQershRepository.computeNetConfirmedExpensesMinor(todayTxs)
        val monthExpense = SaidiQershRepository.computeNetConfirmedExpensesMinor(monthTxs)
        val prevMonthExpense = SaidiQershRepository.computeNetConfirmedExpensesMinor(prevMonthTxs)
        val monthIncome = SaidiQershRepository.computeConfirmedIncomeMinor(monthTxs)

        val netBalance = accs.sumOf { it.currentBalanceMinor }

        val currentMonthBudgets = bds.filter { it.yearMonth == currentYm }
        val totalBudgetObj = currentMonthBudgets.firstOrNull { it.categoryKey == "TOTAL" }
        val budgetLimit = totalBudgetObj?.limitMinor ?: 8000_00L
        val remainingBudget = budgetLimit - monthExpense

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

        val pendingSms = suggested.count { it.status == ReviewStatus.PENDING && !it.isTestSandbox }
        val unclearSms = suggested.count { it.status == ReviewStatus.UNCLEAR && !it.isTestSandbox }

        DashboardSummary(
            primaryCurrency = cur,
            todayExpenseMinor = todayExpense,
            monthExpenseMinor = monthExpense,
            prevMonthExpenseMinor = prevMonthExpense,
            monthIncomeMinor = monthIncome,
            netAccountsBalanceMinor = netBalance,
            monthlyBudgetLimitMinor = budgetLimit,
            remainingBudgetMinor = remainingBudget,
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
                merchantName = merchantName,
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
                showBanner("تم حفظ العملية وتحديث الأرصدة مباشرة")
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

    fun toggleBankSms(bank: BankEntity, enabled: Boolean) {
        viewModelScope.launch {
            repository.toggleBankSmsEnabled(bank, enabled)
            showBanner(if (enabled) "تم تفعيل قراءة رسائل ${bank.nameAr}" else "تم إيقاف قراءة رسائل ${bank.nameAr}")
        }
    }

    fun addCustomBank(nameAr: String, code: String, currency: String, senderIds: String) {
        if (nameAr.isBlank() || senderIds.isBlank()) return
        viewModelScope.launch {
            repository.addCustomBank(nameAr, nameAr, code.ifBlank { "BANK_${System.currentTimeMillis() % 1000}" }, currency, senderIds)
            showBanner("تمت إضافة البنك $nameAr إلى قائمة البنوك المدعومة")
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
                showBanner("تم تحليل الرسالة البنكية وربطها بالحساب تلقائياً للمراجعة")
            } else {
                showBanner("تم استبعاد الرسالة تلقائياً (رسالة غير مالية / OTP / تفعيل مستفيد أو مكررة)")
            }
        }
    }

    fun loadRealisticBankSamplesIntoSandbox() {
        viewModelScope.launch {
            val samples = listOf(
                "Alinma" to "شراء عبر Samsung Wallet مبلغ: ريال سعودى 10 بطاقة مدى: *5650 حساب: *2000 من: dani est",
                "AlRajhiBank" to "شراء عبر نقاط البيع\nبطاقة: *4821;مدى-أثير\nبمبلغ: SAR 145.50\nلدى: أسواق التميمي\nفي: 2026/10/18 19:20\nمرجع: RJ8849201",
                "SNB AlAhli" to "عملية شراء مدى\nبمبلغ 68.00 ر.س\nلدى محطة الدريس للوقود\nتاريخ 2026/10/18\nرقم المرجع: SNB441092",
                "Alinma" to "حوالة صادرة مقبولة\nالمبلغ: 1,250.00 SAR\nحساب: *2000\nالمستفيد: مؤسسة السكن العقارية\nمرجع رقم: INM773910",
                "AlRajhiBank" to "إشعار استرداد مبلغ\nبمبلغ: SAR 45.00\nلدى: صيدلية النهدي\nمرجع: RJ9910234",
                "RiyadBank" to "سداد فاتورة سداد\nمفوتر: الشركة السعودية للكهرباء\nالمبلغ: 390.25 ريال\nمرجع: SADAD55201",
                "Alinma" to "تم تفعيل مستفيد بنجاح في مصرف الإنماء، رمز التحقق OTP هو 8821 لا تشاركه مع أحد",
                "AlRajhiBank" to "تسجيل دخول لحسابكم في مباشر الأفراد، كلمة المرور المؤقتة: 4419",
                "AlRajhiBank" to "عزيزي العميل، الرصيد المتاح في حسابكم الجاري هو 12,450.00 ر.س"
            )
            var count = 0
            for ((sender, text) in samples) {
                val id = repository.ingestBankSms(sender, text, isTestSandbox = true)
                if (id != null && id > 0L) count++
            }
            showBanner("تم تحليل الرسائل (تم استبعاد رسائل التفعيل وOTP تلقائياً وإضافة $count عملية مالية للمراجعة)")
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
                val count = BankSmsBroadcastReceiver.scanDeviceInboxForBankSms(context)
                showBanner(
                    if (count > 0) "تم استيراد $count رسالة بنكية جديدة إلى قائمة المراجعة"
                    else "تم فحص صندوق الوارد؛ لا توجد رسائل بنكية جديدة غير مراجعة"
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
                showBanner("تمت إضافة العملية وتسجيلها وتحديث الأرصدة والميزانية بنجاح")
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
            showBanner("تم تحديث بيانات العملية المقترحة، يمكنك الآن الضغط على «أضف»")
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
            val ym = MoneyUtils.currentYearMonth()
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
            showBanner("تم تحديث $name لشهر $ym")
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
                paymentKey = paymentKey.ifBlank { MoneyUtils.currentYearMonth() },
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
