package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.data.local.*
import com.example.data.repository.SaidiQershRepository
import com.example.domain.MoneyUtils
import com.example.ui.DashboardSummary
import com.example.ui.components.CategorySpendingDonutChart
import com.example.ui.components.MonthlyComparisonBarChart
import com.example.ui.theme.*
import kotlinx.coroutines.launch

/**
 * شاشة الميزانية والتقارير وتصدير CSV والنسخ الاحتياطي المشفر.
 */
@Composable
fun BudgetsAndReportsScreen(
    summary: DashboardSummary,
    budgets: List<BudgetEntity>,
    confirmedTransactions: List<TransactionEntity>,
    accounts: List<AccountEntity>,
    categories: List<CategoryEntity>,
    onSaveBudget: (Long?, String, String) -> Unit,
    onGenerateCsv: suspend () -> String,
    onGenerateBackup: suspend (String?) -> String,
    onRestoreBackup: (String, String?) -> Unit,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    var showBudgetDialog by remember { mutableStateOf(false) }
    var exportedTextPreview by remember { mutableStateOf<String?>(null) }
    var showRestoreDialog by remember { mutableStateOf(false) }
    var backupPassphrase by remember { mutableStateOf("") }

    // حساب تقارير يومية وأسبوعية وشهرية (تستبعد تلقائيًا جميع العمليات المعلقة والمرفوضة)
    val now = System.currentTimeMillis()
    val sevenDaysAgo = now - 7 * 24L * 60L * 60L * 1000L
    val weeklyTransactions = remember(confirmedTransactions) {
        confirmedTransactions.filter { it.timestamp >= sevenDaysAgo }
    }
    val weeklyExpenseMinor = remember(weeklyTransactions) {
        SaidiQershRepository.computeNetConfirmedExpensesMinor(weeklyTransactions)
    }

    // المصروفات حسب الحساب والبنك
    val accMap = remember(accounts) { accounts.associateBy { it.id } }
    val spendingByAccount = remember(confirmedTransactions, accounts) {
        confirmedTransactions
            .filter { it.type == TransactionType.EXPENSE || it.type == TransactionType.OBLIGATION_PAYMENT }
            .groupBy { it.accountId }
            .map { (accId, list) ->
                val accName = accMap[accId]?.name ?: "حساب #$accId"
                val bankName = accMap[accId]?.bankName ?: ""
                Triple(accName, bankName, list.sumOf { it.amountMinor })
            }
            .sortedByDescending { it.third }
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(top = 12.dp, bottom = 96.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "الميزانية والتقارير المالية",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "تقارير دقيقة للعمليات المؤكدة فقط مع تنبيهات 80% و100%",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Button(
                    onClick = { showBudgetDialog = true },
                    modifier = Modifier.testTag("set_budget_btn")
                ) {
                    Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("تحديد ميزانية")
                }
            }
        }

        // 1. بطاقة الميزانية الشهرية الإجمالية وميزانيات التصنيفات
        item {
            val totalRatio = if (summary.monthlyBudgetLimitMinor > 0) {
                (summary.monthExpenseMinor.toFloat() / summary.monthlyBudgetLimitMinor.toFloat()).coerceIn(0f, 1f)
            } else 0f
            val pctInt = if (summary.monthlyBudgetLimitMinor > 0) {
                ((summary.monthExpenseMinor * 100L) / summary.monthlyBudgetLimitMinor).toInt()
            } else 0

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "الميزانية الشهرية الإجمالية (${MoneyUtils.currentYearMonth()})",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "$pctInt%",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = when {
                                pctInt >= 100 -> ExpenseRed
                                pctInt >= 80 -> WarningAmber
                                else -> IncomeGreen
                            }
                        )
                    }

                    LinearProgressIndicator(
                        progress = { totalRatio },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(10.dp)
                            .clip(RoundedCornerShape(5.dp)),
                        color = when {
                            pctInt >= 100 -> ExpenseRed
                            pctInt >= 80 -> WarningAmber
                            else -> SaidiGreenPrimary
                        }
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "المنفق المؤكد: ${MoneyUtils.formatMinor(summary.monthExpenseMinor, summary.primaryCurrency)}",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            text = "الحد: ${MoneyUtils.formatMinor(summary.monthlyBudgetLimitMinor, summary.primaryCurrency)}",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }

                    // ميزانيات التصنيفات المستقلة
                    val catBudgets = budgets.filter { it.categoryKey != "TOTAL" }
                    if (catBudgets.isNotEmpty()) {
                        HorizontalDivider()
                        Text(
                            text = "ميزانيات التصنيفات المستقلة:",
                            style = MaterialTheme.typography.labelLarge
                        )
                        catBudgets.forEach { cb ->
                            val spent = summary.categoryBreakdown
                                .firstOrNull { it.categoryId == cb.categoryId }?.amountMinor ?: 0L
                            val cbPct = if (cb.limitMinor > 0) ((spent * 100L) / cb.limitMinor).toInt() else 0
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(cb.categoryName, style = MaterialTheme.typography.bodySmall)
                                Text(
                                    text = "${MoneyUtils.formatMinor(spent, cb.currency)} / ${MoneyUtils.formatMinor(cb.limitMinor, cb.currency)} ($cbPct%)",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (cbPct >= 80) ExpenseRed else MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                }
            }
        }

        // 2. ملخص التقارير اليومية والأسبوعية والشهرية
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "ملخص الفترات (يومي / أسبوعي / شهري)",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("مصروفات اليوم:", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            MoneyUtils.formatMinor(summary.todayExpenseMinor, summary.primaryCurrency),
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("مصروفات آخر ٧ أيام (أسبوعي):", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            MoneyUtils.formatMinor(weeklyExpenseMinor, summary.primaryCurrency),
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("صافي مصروفات الشهر الحالي:", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            MoneyUtils.formatMinor(summary.monthExpenseMinor, summary.primaryCurrency),
                            fontWeight = FontWeight.Bold,
                            color = ExpenseRed
                        )
                    }
                }
            }
        }

        // 3. أعلى التصنيفات إنفاقًا ومقارنة الفترات
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "أعلى التصنيفات إنفاقًا",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    CategorySpendingDonutChart(
                        slices = summary.categoryBreakdown,
                        currency = summary.primaryCurrency
                    )
                }
            }
        }

        item {
            MonthlyComparisonBarChart(
                currentMonthExpenseMinor = summary.monthExpenseMinor,
                prevMonthExpenseMinor = summary.prevMonthExpenseMinor,
                currentMonthIncomeMinor = summary.monthIncomeMinor,
                currency = summary.primaryCurrency
            )
        }

        // 4. المصروفات حسب الحساب والبنك
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "المصروفات حسب الحساب والبنك",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    if (spendingByAccount.isEmpty()) {
                        Text(
                            text = "لا توجد مصروفات مسجلة على الحسابات بعد.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        spendingByAccount.forEach { (accName, bankName, totalMinor) ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "$accName ${if (bankName.isNotBlank()) "($bankName)" else ""}",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Text(
                                    text = MoneyUtils.formatMinor(totalMinor, summary.primaryCurrency),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }
        }

        // 5. تصدير CSV والنسخ الاحتياطي المشفر محليًا
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "تصدير البيانات والنسخ الاحتياطي المحلي (دون إرسال لأي جهة)",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    OutlinedTextField(
                        value = backupPassphrase,
                        onValueChange = { backupPassphrase = it },
                        label = { Text("كلمة مرور تشفير النسخة الاحتياطية (اختياري AES-256)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                scope.launch {
                                    val csv = onGenerateCsv()
                                    exportedTextPreview = csv
                                    clipboard.setText(AnnotatedString(csv))
                                }
                            },
                            modifier = Modifier.weight(1f).testTag("export_csv_btn")
                        ) {
                            Text("تصدير CSV")
                        }
                        FilledTonalButton(
                            onClick = {
                                scope.launch {
                                    val backup = onGenerateBackup(backupPassphrase.ifBlank { null })
                                    exportedTextPreview = backup
                                    clipboard.setText(AnnotatedString(backup))
                                }
                            },
                            modifier = Modifier.weight(1f).testTag("export_backup_btn")
                        ) {
                            Text("نسخة احتياطية")
                        }
                    }
                    OutlinedButton(
                        onClick = { showRestoreDialog = true },
                        modifier = Modifier.fillMaxWidth().testTag("open_restore_backup_btn")
                    ) {
                        Text("استيراد نسخة احتياطية بعد التحقق منها")
                    }

                    if (exportedTextPreview != null) {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Text(
                                    text = "تم النسخ إلى الحافظة المحلية بنجاح (معاينة):",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = exportedTextPreview!!.take(350),
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showBudgetDialog) {
        var selectedCatId by remember { mutableStateOf<Long?>(null) }
        var limitText by remember {
            mutableStateOf(MoneyUtils.formatPlainDecimal(summary.monthlyBudgetLimitMinor))
        }
        AlertDialog(
            onDismissRequest = { showBudgetDialog = false },
            title = { Text("تحديد الميزانية الشهرية") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("نطاق الميزانية:", style = MaterialTheme.typography.labelLarge)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        item {
                            FilterChip(
                                selected = selectedCatId == null,
                                onClick = { selectedCatId = null },
                                label = { Text("الميزانية الشهرية الإجمالية") }
                            )
                        }
                        items(categories.filter { it.type == CategoryType.EXPENSE }) { cat ->
                            FilterChip(
                                selected = selectedCatId == cat.id,
                                onClick = { selectedCatId = cat.id },
                                label = { Text(cat.nameAr) }
                            )
                        }
                    }
                    OutlinedTextField(
                        value = limitText,
                        onValueChange = { limitText = it },
                        label = { Text("الحد الأعلى الشهري (${MoneyUtils.currencySymbol(summary.primaryCurrency)})") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("budget_limit_input")
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        onSaveBudget(selectedCatId, limitText, summary.primaryCurrency)
                        showBudgetDialog = false
                    },
                    modifier = Modifier.testTag("save_budget_btn")
                ) {
                    Text("حفظ الميزانية")
                }
            },
            dismissButton = {
                TextButton(onClick = { showBudgetDialog = false }) { Text("إلغاء") }
            }
        )
    }

    if (showRestoreDialog) {
        var backupInput by remember { mutableStateOf(exportedTextPreview ?: "") }
        var passInput by remember { mutableStateOf(backupPassphrase) }
        AlertDialog(
            onDismissRequest = { showRestoreDialog = false },
            title = { Text("استيراد نسخة احتياطية") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = backupInput,
                        onValueChange = { backupInput = it },
                        label = { Text("الصق محتوى النسخة الاحتياطية (JSON أو المشفرة SQENC1)") },
                        minLines = 3,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = passInput,
                        onValueChange = { passInput = it },
                        label = { Text("كلمة مرور فك التشفير (إن كانت مشفرة)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        onRestoreBackup(backupInput, passInput.ifBlank { null })
                        showRestoreDialog = false
                    }
                ) {
                    Text("التحقق والاستيراد")
                }
            },
            dismissButton = {
                TextButton(onClick = { showRestoreDialog = false }) { Text("إلغاء") }
            }
        )
    }
}

/**
 * الشاشة الشاملة للالتزامات والأقساط والجمعيات + صناديق التوفير ومشتريات البيت + الأمان والسجل.
 */
@Composable
fun PlanningAndMoreScreen(
    obligations: List<ObligationEntity>,
    savingsGoals: List<SavingsGoalEntity>,
    shoppingLists: List<ShoppingListEntity>,
    shoppingItems: List<ShoppingItemEntity>,
    auditLogs: List<ImportAuditLogEntity>,
    accounts: List<AccountEntity>,
    categories: List<CategoryEntity>,
    currentPin: String?,
    isDarkModePreferred: Boolean?,
    onAddObligation: (String, ObligationKind, String, String, String, Int, Boolean, String, Long?, Long?) -> Unit,
    onPayObligation: (ObligationEntity, String, String, Long?, Boolean) -> Unit,
    onAddSavingsGoal: (String, String, String, Int) -> Unit,
    onRecordSavingsMovement: (Long, Boolean, String, String) -> Unit,
    onCreateShoppingList: (String, String) -> Unit,
    onAddShoppingItem: (Long, String, Int, String) -> Unit,
    onToggleShoppingItem: (ShoppingItemEntity) -> Unit,
    onConvertShoppingListToExpenseOnce: (Long, Long, Long?) -> Unit,
    onSetPin: (String?) -> Unit,
    onLockNow: () -> Unit,
    onToggleDarkMode: (Boolean?) -> Unit,
    onWipeAllDataConfirmed: () -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedSection by remember { mutableIntStateOf(0) } // 0 = Obligations/Installments/ROSCAs, 1 = Savings & Shopping, 2 = Security & Audit
    var showAddObligationDialog by remember { mutableStateOf(false) }
    var payingObligation by remember { mutableStateOf<ObligationEntity?>(null) }
    var showWipeConfirmDialog by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(top = 12.dp, bottom = 96.dp)
    ) {
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    FilterChip(
                        selected = selectedSection == 0,
                        onClick = { selectedSection = 0 },
                        label = { Text("الالتزامات والأقساط والجمعيات") },
                        modifier = Modifier.testTag("subtab_obligations")
                    )
                }
                item {
                    FilterChip(
                        selected = selectedSection == 1,
                        onClick = { selectedSection = 1 },
                        label = { Text("التوفير ومشتريات البيت") },
                        modifier = Modifier.testTag("subtab_savings_shopping")
                    )
                }
                item {
                    FilterChip(
                        selected = selectedSection == 2,
                        onClick = { selectedSection = 2 },
                        label = { Text("الأمان وسجل التعديلات") },
                        modifier = Modifier.testTag("subtab_security_audit")
                    )
                }
            }
        }

        when (selectedSection) {
            0 -> {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "الالتزامات والأقساط والجمعيات",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Button(
                            onClick = { showAddObligationDialog = true },
                            modifier = Modifier.testTag("add_obligation_btn")
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null)
                            Spacer(Modifier.width(4.dp))
                            Text("إضافة التزام/جمعية")
                        }
                    }
                }

                if (obligations.isEmpty()) {
                    item {
                        Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                            Text(
                                text = "لا توجد التزامات أو أقساط أو جمعيات مسجلة. أضف التزامًا لتتبع المدفوع والمتبقي والتذكيرات المحلية.",
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(20.dp)
                            )
                        }
                    }
                } else {
                    items(obligations, key = { it.id }) { obl ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Column(
                                modifier = Modifier.padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = "${obl.title} (${obl.kind.titleAr})",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = if (obl.isCompleted) "مكتمل" else "قائم",
                                        color = if (obl.isCompleted) IncomeGreen else WarningAmber,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                Text(
                                    text = "الإجمالي: ${MoneyUtils.formatMinor(obl.totalAmountMinor, obl.currency)} • القسط: ${MoneyUtils.formatMinor(obl.installmentAmountMinor, obl.currency)}",
                                    style = MaterialTheme.typography.bodySmall
                                )
                                Text(
                                    text = "المدفوع: ${MoneyUtils.formatMinor(obl.paidAmountMinor, obl.currency)} • المتبقي: ${MoneyUtils.formatMinor(obl.remainingAmountMinor, obl.currency)}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                if (obl.roscaPayoutMonth.isNotBlank()) {
                                    Text(
                                        text = "موعد قبض الجمعية: ${obl.roscaPayoutMonth}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = SaidiGoldDark
                                    )
                                }
                                if (!obl.isCompleted) {
                                    Button(
                                        onClick = { payingObligation = obl },
                                        modifier = Modifier.align(Alignment.End).testTag("pay_obligation_btn_${obl.id}")
                                    ) {
                                        Text("تسجيل دفعة / قسط")
                                    }
                                }
                            }
                        }
                    }
                }
            }

            1 -> {
                item {
                    SavingsAndShoppingSection(
                        savingsGoals = savingsGoals,
                        shoppingLists = shoppingLists,
                        shoppingItems = shoppingItems,
                        accounts = accounts,
                        categories = categories,
                        onAddSavingsGoal = onAddSavingsGoal,
                        onRecordSavingsMovement = onRecordSavingsMovement,
                        onCreateShoppingList = onCreateShoppingList,
                        onAddShoppingItem = onAddShoppingItem,
                        onToggleShoppingItem = onToggleShoppingItem,
                        onConvertShoppingListToExpenseOnce = onConvertShoppingListToExpenseOnce
                    )
                }
            }

            2 -> {
                item {
                    SecurityAndAuditSection(
                        currentPin = currentPin,
                        isDarkModePreferred = isDarkModePreferred,
                        auditLogs = auditLogs,
                        onSetPin = onSetPin,
                        onLockNow = onLockNow,
                        onToggleDarkMode = onToggleDarkMode,
                        onRequestWipe = { showWipeConfirmDialog = true }
                    )
                }
            }
        }
    }

    if (showAddObligationDialog) {
        var title by remember { mutableStateOf("") }
        var kind by remember { mutableStateOf(ObligationKind.INSTALLMENT_PLAN) }
        var totalText by remember { mutableStateOf("") }
        var instText by remember { mutableStateOf("") }
        var roscaMonth by remember { mutableStateOf("2026-12") }
        var isRecurring by remember { mutableStateOf(true) }

        AlertDialog(
            onDismissRequest = { showAddObligationDialog = false },
            title = { Text("إضافة التزام / قسط / جمعية") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(ObligationKind.entries) { k ->
                            FilterChip(
                                selected = kind == k,
                                onClick = { kind = k },
                                label = { Text(k.titleAr) }
                            )
                        }
                    }
                    OutlinedTextField(
                        value = title,
                        onValueChange = { title = it },
                        label = { Text("العنوان (مثل: قسط السيارة أو جمعية العائلة)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("obligation_title_input")
                    )
                    OutlinedTextField(
                        value = totalText,
                        onValueChange = { totalText = it },
                        label = { Text("إجمالي المبلغ الكلي") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("obligation_total_input")
                    )
                    OutlinedTextField(
                        value = instText,
                        onValueChange = { instText = it },
                        label = { Text("قيمة الدفعة / القسط الدوري") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("obligation_inst_input")
                    )
                    if (kind == ObligationKind.GAM3EYA_ROSCA) {
                        OutlinedTextField(
                            value = roscaMonth,
                            onValueChange = { roscaMonth = it },
                            label = { Text("موعد القبض (مثل: 2026-12)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        onAddObligation(
                            title,
                            kind,
                            totalText,
                            instText.ifBlank { totalText },
                            "SAR",
                            5,
                            isRecurring,
                            if (kind == ObligationKind.GAM3EYA_ROSCA) roscaMonth else "",
                            accounts.firstOrNull()?.id,
                            categories.firstOrNull()?.id
                        )
                        showAddObligationDialog = false
                    },
                    modifier = Modifier.testTag("save_obligation_btn")
                ) {
                    Text("حفظ")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddObligationDialog = false }) { Text("إلغاء") }
            }
        )
    }

    if (payingObligation != null) {
        val obl = payingObligation!!
        var paymentKey by remember { mutableStateOf(MoneyUtils.currentYearMonth()) }
        var payAmount by remember { mutableStateOf(MoneyUtils.formatPlainDecimal(obl.installmentAmountMinor)) }
        var alreadyExpense by remember { mutableStateOf(false) }

        AlertDialog(
            onDismissRequest = { payingObligation = null },
            title = { Text("تسجيل دفعة: ${obl.title}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = paymentKey,
                        onValueChange = { paymentKey = it },
                        label = { Text("مفتاح/شهر الدفعة (لمنع تكرار نفس الدفعة)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = payAmount,
                        onValueChange = { payAmount = it },
                        label = { Text("مبلغ الدفعة") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = alreadyExpense,
                            onCheckedChange = { alreadyExpense = it }
                        )
                        Text(
                            text = "هذه الدفعة سُجلت مسبقًا كمصروف (لا تخصمها مرة أخرى من الحساب)",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        onPayObligation(obl, paymentKey, payAmount, accounts.firstOrNull()?.id, alreadyExpense)
                        payingObligation = null
                    }
                ) {
                    Text("تأكيد الدفعة")
                }
            },
            dismissButton = {
                TextButton(onClick = { payingObligation = null }) { Text("إلغاء") }
            }
        )
    }

    if (showWipeConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showWipeConfirmDialog = false },
            title = { Text("تأكيد حذف كل البيانات نهائيًا") },
            text = {
                Text("هل أنت متأكد تمامًا من حذف جميع العمليات والحسابات والرسائل والميزانيات من الجهاز؟ لا يمكن التراجع عن هذا الإجراء.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        onWipeAllDataConfirmed()
                        showWipeConfirmDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = ExpenseRed),
                    modifier = Modifier.testTag("confirm_wipe_all_btn")
                ) {
                    Text("نعم، احذف كل البيانات")
                }
            },
            dismissButton = {
                TextButton(onClick = { showWipeConfirmDialog = false }) { Text("إلغاء") }
            }
        )
    }
}

@Composable
private fun SavingsAndShoppingSection(
    savingsGoals: List<SavingsGoalEntity>,
    shoppingLists: List<ShoppingListEntity>,
    shoppingItems: List<ShoppingItemEntity>,
    accounts: List<AccountEntity>,
    categories: List<CategoryEntity>,
    onAddSavingsGoal: (String, String, String, Int) -> Unit,
    onRecordSavingsMovement: (Long, Boolean, String, String) -> Unit,
    onCreateShoppingList: (String, String) -> Unit,
    onAddShoppingItem: (Long, String, Int, String) -> Unit,
    onToggleShoppingItem: (ShoppingItemEntity) -> Unit,
    onConvertShoppingListToExpenseOnce: (Long, Long, Long?) -> Unit
) {
    var goalTitle by remember { mutableStateOf("") }
    var goalTarget by remember { mutableStateOf("") }
    var movementAmount by remember { mutableStateOf("") }

    var newListTitle by remember { mutableStateOf("") }
    var newItemName by remember { mutableStateOf("") }
    var newItemQty by remember { mutableStateOf("1") }
    var newItemPrice by remember { mutableStateOf("") }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        // 1. صناديق التوفير
        Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("صناديق وأهداف التوفير", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = goalTitle,
                        onValueChange = { goalTitle = it },
                        label = { Text("اسم هدف التوفير") },
                        singleLine = true,
                        modifier = Modifier.weight(1.2f).testTag("savings_title_input")
                    )
                    OutlinedTextField(
                        value = goalTarget,
                        onValueChange = { goalTarget = it },
                        label = { Text("القيمة المستهدفة") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier.weight(1f).testTag("savings_target_input")
                    )
                }
                Button(
                    onClick = {
                        if (goalTitle.isNotBlank() && goalTarget.isNotBlank()) {
                            onAddSavingsGoal(goalTitle, goalTarget, "SAR", 90)
                            goalTitle = ""
                            goalTarget = ""
                        }
                    },
                    modifier = Modifier.fillMaxWidth().testTag("add_savings_goal_btn")
                ) {
                    Text("إنشاء صندوق توفير")
                }

                savingsGoals.forEach { goal ->
                    val pct = if (goal.targetAmountMinor > 0) {
                        ((goal.currentAmountMinor * 100L) / goal.targetAmountMinor).toInt().coerceIn(0, 100)
                    } else 0
                    HorizontalDivider()
                    Text(
                        text = "${goal.title} • نسبة الإنجاز: $pct% (${MoneyUtils.formatMinor(goal.currentAmountMinor, goal.currency)} من ${MoneyUtils.formatMinor(goal.targetAmountMinor, goal.currency)})",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    LinearProgressIndicator(
                        progress = { pct / 100f },
                        modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp))
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = movementAmount,
                            onValueChange = { movementAmount = it },
                            label = { Text("مبلغ إيداع/سحب") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        FilledTonalButton(
                            onClick = {
                                onRecordSavingsMovement(goal.id, true, movementAmount, "إيداع توفير")
                                movementAmount = ""
                            }
                        ) {
                            Text("إيداع")
                        }
                        OutlinedButton(
                            onClick = {
                                onRecordSavingsMovement(goal.id, false, movementAmount, "سحب توفير")
                                movementAmount = ""
                            }
                        ) {
                            Text("سحب")
                        }
                    }
                }
            }
        }

        // 2. قوائم مشتريات البيت
        Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("قوائم مشتريات البيت", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = newListTitle,
                        onValueChange = { newListTitle = it },
                        label = { Text("اسم القائمة (مثل: مقاضي الأسبوع)") },
                        singleLine = true,
                        modifier = Modifier.weight(1f).testTag("shopping_list_title_input")
                    )
                    Button(
                        onClick = {
                            if (newListTitle.isNotBlank()) {
                                onCreateShoppingList(newListTitle, "SAR")
                                newListTitle = ""
                            }
                        },
                        modifier = Modifier.testTag("create_shopping_list_btn")
                    ) {
                        Text("إنشاء")
                    }
                }

                shoppingLists.forEach { list ->
                    val itemsForList = shoppingItems.filter { it.listId == list.id }
                    val totalEst = itemsForList.sumOf { it.totalEstimatedMinor }
                    HorizontalDivider()
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${list.title} (${MoneyUtils.formatMinor(totalEst, list.currency)})",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        if (list.recordedAsExpense) {
                            Text("تم تسجيلها كمصروف ✓", color = IncomeGreen, style = MaterialTheme.typography.labelMedium)
                        } else {
                            FilledTonalButton(
                                onClick = {
                                    val accId = accounts.firstOrNull()?.id ?: 1L
                                    val catId = categories.firstOrNull()?.id
                                    onConvertShoppingListToExpenseOnce(list.id, accId, catId)
                                },
                                modifier = Modifier.testTag("convert_shopping_btn_${list.id}")
                            ) {
                                Text("تسجيل كمصروف مرة واحدة")
                            }
                        }
                    }

                    if (!list.recordedAsExpense) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            OutlinedTextField(
                                value = newItemName,
                                onValueChange = { newItemName = it },
                                label = { Text("المنتج") },
                                singleLine = true,
                                modifier = Modifier.weight(1.2f)
                            )
                            OutlinedTextField(
                                value = newItemQty,
                                onValueChange = { newItemQty = it },
                                label = { Text("الكمية") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true,
                                modifier = Modifier.weight(0.6f)
                            )
                            OutlinedTextField(
                                value = newItemPrice,
                                onValueChange = { newItemPrice = it },
                                label = { Text("السعر") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                singleLine = true,
                                modifier = Modifier.weight(0.8f)
                            )
                            IconButton(
                                onClick = {
                                    if (newItemName.isNotBlank()) {
                                        onAddShoppingItem(
                                            list.id,
                                            newItemName,
                                            newItemQty.toIntOrNull() ?: 1,
                                            newItemPrice
                                        )
                                        newItemName = ""
                                        newItemPrice = ""
                                    }
                                }
                            ) {
                                Icon(Icons.Default.AddCircle, contentDescription = "إضافة منتج")
                            }
                        }
                    }

                    itemsForList.forEach { item ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(
                                    checked = item.isPurchased,
                                    onCheckedChange = { onToggleShoppingItem(item) }
                                )
                                Text("${item.name} × ${item.quantity}", style = MaterialTheme.typography.bodySmall)
                            }
                            Text(
                                MoneyUtils.formatMinor(item.totalEstimatedMinor, list.currency),
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SecurityAndAuditSection(
    currentPin: String?,
    isDarkModePreferred: Boolean?,
    auditLogs: List<ImportAuditLogEntity>,
    onSetPin: (String?) -> Unit,
    onLockNow: () -> Unit,
    onToggleDarkMode: (Boolean?) -> Unit,
    onRequestWipe: () -> Unit
) {
    var pinInput by remember { mutableStateOf(currentPin ?: "") }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("الأمان والخصوصية والمظهر", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    text = "جميع البيانات المالية والرسائل محفوظة محليًا على جهازك فقط دون أي اتصال بالإنترنت أو تتبع.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text("المظهر (غامق / فاتح / زي الموبايل):", fontWeight = FontWeight.SemiBold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = isDarkModePreferred == true,
                            onClick = { onToggleDarkMode(true) },
                            label = { Text("غامق") },
                            modifier = Modifier.testTag("theme_mode_dark")
                        )
                        FilterChip(
                            selected = isDarkModePreferred == false,
                            onClick = { onToggleDarkMode(false) },
                            label = { Text("فاتح") },
                            modifier = Modifier.testTag("theme_mode_light")
                        )
                        FilterChip(
                            selected = isDarkModePreferred == null,
                            onClick = { onToggleDarkMode(null) },
                            label = { Text("زي الموبايل") },
                            modifier = Modifier.testTag("theme_mode_system")
                        )
                    }
                }

                HorizontalDivider()
                Text("قفل التطبيق برمز سري (PIN):", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = pinInput,
                        onValueChange = { if (it.length <= 6) pinInput = it },
                        label = { Text("رمز PIN (4-6 أرقام)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        singleLine = true,
                        modifier = Modifier.weight(1f).testTag("pin_setup_input")
                    )
                    Button(
                        onClick = { onSetPin(pinInput.ifBlank { null }) },
                        modifier = Modifier.testTag("save_pin_btn")
                    ) {
                        Text(if (pinInput.isBlank()) "إلغاء القفل" else "حفظ الرمز")
                    }
                }
                if (!currentPin.isNullOrBlank()) {
                    OutlinedButton(onClick = onLockNow, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Lock, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("قفل التطبيق الآن")
                    }
                }

                HorizontalDivider()
                Button(
                    onClick = onRequestWipe,
                    colors = ButtonDefaults.buttonColors(containerColor = ExpenseRed),
                    modifier = Modifier.fillMaxWidth().testTag("wipe_all_data_btn")
                ) {
                    Icon(Icons.Default.DeleteForever, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("حذف كل البيانات نهائيًا من الجهاز")
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "سجل التعديلات والاستيراد والتسويات (${auditLogs.size})",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                if (auditLogs.isEmpty()) {
                    Text("لا توجد سجلات بعد.", style = MaterialTheme.typography.bodySmall)
                } else {
                    auditLogs.take(25).forEach { log ->
                        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            Text(
                                text = "${log.sourceDescription} • ${MoneyUtils.formatArabicDate(log.timestamp)}",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = log.details,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            HorizontalDivider(modifier = Modifier.padding(top = 4.dp))
                        }
                    }
                }
            }
        }
    }
}
