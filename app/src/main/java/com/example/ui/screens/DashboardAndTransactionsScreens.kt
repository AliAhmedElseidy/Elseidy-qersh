package com.example.ui.screens

import android.app.Activity
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CompareArrows
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.data.local.*
import com.example.domain.MoneyUtils
import com.example.domain.VoiceExpenseProposal
import com.example.ui.DashboardSummary
import com.example.ui.TransactionFilterState
import com.example.ui.components.CategorySpendingDonutChart
import com.example.ui.components.MonthlyComparisonBarChart
import com.example.ui.theme.*

@Composable
fun DashboardScreen(
    summary: DashboardSummary,
    recentTransactions: List<TransactionEntity>,
    accounts: List<AccountEntity>,
    categories: List<CategoryEntity>,
    onQuickAddClick: (TransactionType) -> Unit,
    onVoiceDictationClick: () -> Unit,
    onOpenSmsReviewClick: () -> Unit,
    onEditTransaction: (TransactionEntity) -> Unit,
    onDeleteTransactionConfirmed: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    var txToDelete by remember { mutableStateOf<TransactionEntity?>(null) }
    val accMap = remember(accounts) { accounts.associateBy { it.id } }
    val catMap = remember(categories) { categories.associateBy { it.id } }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(top = 12.dp, bottom = 96.dp)
    ) {
        // 1. بطاقة صافي الرصيد والميزانية المتبقية
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("dashboard_hero_card"),
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(containerColor = SaidiGreenDark)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            Brush.linearGradient(
                                listOf(SaidiGreenDark, SaidiGreenPrimary)
                            )
                        )
                        .padding(20.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = "صافي الرصيد في الحسابات المسجلة",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Color.White.copy(alpha = 0.85f)
                                )
                                Text(
                                    text = MoneyUtils.formatMinor(summary.netAccountsBalanceMinor, summary.primaryCurrency),
                                    style = MaterialTheme.typography.headlineLarge,
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.testTag("dashboard_net_balance")
                                )
                            }
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = SaidiGoldPrimary.copy(alpha = 0.22f)
                            ) {
                                Text(
                                    text = "محلي ١٠٠٪",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = SaidiGoldLight,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                )
                            }
                        }

                        HorizontalDivider(color = Color.White.copy(alpha = 0.15f))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            MetricMiniColumn(
                                label = "مصروفات اليوم",
                                value = MoneyUtils.formatMinor(summary.todayExpenseMinor, summary.primaryCurrency),
                                valueColor = Color(0xFFFFB4AB),
                                tag = "dashboard_today_expense"
                            )
                            MetricMiniColumn(
                                label = "مصروفات الشهر",
                                value = MoneyUtils.formatMinor(summary.monthExpenseMinor, summary.primaryCurrency),
                                valueColor = Color(0xFFFFD97D),
                                tag = "dashboard_month_expense"
                            )
                            MetricMiniColumn(
                                label = "إجمالي الدخل",
                                value = MoneyUtils.formatMinor(summary.monthIncomeMinor, summary.primaryCurrency),
                                valueColor = Color(0xFFA6F4C5),
                                tag = "dashboard_total_income"
                            )
                        }

                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = Color.Black.copy(alpha = 0.20f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "الميزانية المتبقية للشهر:",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color.White.copy(alpha = 0.9f)
                                )
                                Text(
                                    text = MoneyUtils.formatMinor(summary.remainingBudgetMinor, summary.primaryCurrency, showSign = false),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = if (summary.remainingBudgetMinor >= 0) SaidiGoldLight else Color(0xFFFF8A80),
                                    modifier = Modifier.testTag("dashboard_remaining_budget")
                                )
                            }
                        }
                    }
                }
            }
        }

        // 2. أزرار سريعة لإضافة مصروف أو دخل أو تحويل أو إملاء صوتي
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                QuickActionButton(
                    title = "إضافة مصروف",
                    icon = Icons.Default.RemoveCircleOutline,
                    containerColor = ExpenseRed.copy(alpha = 0.12f),
                    contentColor = ExpenseRed,
                    onClick = { onQuickAddClick(TransactionType.EXPENSE) },
                    modifier = Modifier.weight(1f).testTag("quick_add_expense_btn")
                )
                QuickActionButton(
                    title = "إضافة دخل",
                    icon = Icons.Default.AddCircleOutline,
                    containerColor = IncomeGreen.copy(alpha = 0.12f),
                    contentColor = IncomeGreen,
                    onClick = { onQuickAddClick(TransactionType.INCOME) },
                    modifier = Modifier.weight(1f).testTag("quick_add_income_btn")
                )
                QuickActionButton(
                    title = "تحويل حساب",
                    icon = Icons.AutoMirrored.Filled.CompareArrows,
                    containerColor = SaidiGoldPrimary.copy(alpha = 0.18f),
                    contentColor = SaidiGoldDark,
                    onClick = { onQuickAddClick(TransactionType.TRANSFER) },
                    modifier = Modifier.weight(1f).testTag("quick_add_transfer_btn")
                )
                QuickActionButton(
                    title = "إملاء صوتي",
                    icon = Icons.Default.Mic,
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    onClick = onVoiceDictationClick,
                    modifier = Modifier.weight(1f).testTag("quick_voice_btn")
                )
            }
        }

        // 3. إشعار رسائل البنوك المعلقة للمراجعة إن وجدت
        if (summary.pendingSmsCount > 0 || summary.unclearSmsCount > 0) {
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenSmsReviewClick() }
                        .testTag("pending_sms_alert_card"),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Sms,
                                contentDescription = "رسائل تحتاج مراجعة",
                                tint = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                            Column {
                                Text(
                                    text = "لديك ${summary.pendingSmsCount} عملية مقترحة و ${summary.unclearSmsCount} غير واضحة",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                                Text(
                                    text = "لا تدخل في المصروفات المؤكدة إلا بعد مراجعتك وتأكيدك",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f)
                                )
                            }
                        }
                        TextButton(onClick = onOpenSmsReviewClick) {
                            Text("مراجعة الآن")
                        }
                    }
                }
            }
        }

        // 4. تنبيهات الميزانية (80% و100%) والالتزامات المستحقة
        if (summary.budgetAlerts.isNotEmpty() || summary.dueObligations.isNotEmpty()) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = WarningAmber.copy(alpha = 0.12f)
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.NotificationsActive,
                                contentDescription = "تنبيهات الميزانية والالتزامات",
                                tint = WarningAmber
                            )
                            Text(
                                text = "تنبيهات الميزانية والالتزامات المستحقة",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        summary.budgetAlerts.forEach { alert ->
                            Text(
                                text = "• ${alert.title}: استهلكت ${alert.percentUsed}% (${MoneyUtils.formatMinor(alert.spentMinor, alert.currency)} من ${MoneyUtils.formatMinor(alert.limitMinor, alert.currency)})",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (alert.isExceeded) ExpenseRed else MaterialTheme.colorScheme.onSurface,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        summary.dueObligations.forEach { obl ->
                            Text(
                                text = "• مستحق (${obl.kind.titleAr}): ${obl.title} - المتبقي ${MoneyUtils.formatMinor(obl.remainingAmountMinor, obl.currency)}",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }
        }

        // 5. رسم بياني للمصروفات حسب التصنيف
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "توزيع مصروفات الشهر حسب التصنيف",
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

        // 6. مقارنة الشهر الحالي بالشهر السابق
        item {
            MonthlyComparisonBarChart(
                currentMonthExpenseMinor = summary.monthExpenseMinor,
                prevMonthExpenseMinor = summary.prevMonthExpenseMinor,
                currentMonthIncomeMinor = summary.monthIncomeMinor,
                currency = summary.primaryCurrency
            )
        }

        // 7. أحدث العمليات المؤكدة
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "أحدث العمليات المؤكدة",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "${recentTransactions.size} عملية",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (recentTransactions.isEmpty()) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.ReceiptLong,
                            contentDescription = "لا توجد عمليات",
                            modifier = Modifier.size(40.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "لا توجد عمليات مالية مؤكدة حتى الآن",
                            style = MaterialTheme.typography.titleSmall
                        )
                        Text(
                            text = "استخدم الأزرار السريعة أعلاه لإضافة مصروف أو دخل أو راجع رسائل البنوك المقترحة.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        } else {
            items(recentTransactions.take(8), key = { it.id }) { tx ->
                TransactionRowCard(
                    tx = tx,
                    accountName = accMap[tx.accountId]?.name ?: "حساب رئيسي",
                    categoryName = tx.categoryId?.let { catMap[it]?.nameAr } ?: tx.type.titleAr,
                    onEdit = { onEditTransaction(tx) },
                    onDeleteRequest = { txToDelete = tx }
                )
            }
        }
    }

    if (txToDelete != null) {
        val target = txToDelete!!
        AlertDialog(
            onDismissRequest = { txToDelete = null },
            title = { Text("تأكيد حذف العملية المالية") },
            text = {
                Text("هل أنت متأكد من حذف «${target.description}» بقيمة ${MoneyUtils.formatMinor(target.amountMinor, target.currency)}؟ سيتم عكس أثرها على رصيد الحساب فورًا.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        onDeleteTransactionConfirmed(target.id)
                        txToDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = ExpenseRed),
                    modifier = Modifier.testTag("confirm_delete_tx_btn")
                ) {
                    Text("تأكيد الحذف")
                }
            },
            dismissButton = {
                TextButton(onClick = { txToDelete = null }) {
                    Text("إلغاء")
                }
            }
        )
    }
}

@Composable
private fun MetricMiniColumn(
    label: String,
    value: String,
    valueColor: Color,
    tag: String
) {
    Column(horizontalAlignment = Alignment.Start) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = Color.White.copy(alpha = 0.80f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            color = valueColor,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.testTag(tag)
        )
    }
}

@Composable
private fun QuickActionButton(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    containerColor: Color,
    contentColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = containerColor,
        modifier = modifier.heightIn(min = 64.dp)
    ) {
        Column(
            modifier = Modifier.padding(vertical = 10.dp, horizontal = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(imageVector = icon, contentDescription = title, tint = contentColor)
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                color = contentColor,
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
        }
    }
}

@Composable
fun TransactionRowCard(
    tx: TransactionEntity,
    accountName: String,
    categoryName: String,
    onEdit: () -> Unit,
    onDeleteRequest: () -> Unit,
    modifier: Modifier = Modifier
) {
    val amountColor = when (tx.type) {
        TransactionType.EXPENSE, TransactionType.OBLIGATION_PAYMENT -> ExpenseRed
        TransactionType.INCOME -> IncomeGreen
        TransactionType.REFUND -> RefundTeal
        TransactionType.TRANSFER, TransactionType.CREDIT_CARD_PAYMENT -> SaidiGoldDark
    }
    val prefix = when (tx.type) {
        TransactionType.EXPENSE, TransactionType.OBLIGATION_PAYMENT -> "-"
        TransactionType.INCOME, TransactionType.REFUND -> "+"
        TransactionType.TRANSFER, TransactionType.CREDIT_CARD_PAYMENT -> "⇄ "
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag("tx_item_${tx.id}"),
        shape = RoundedCornerShape(14.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = tx.description,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Text(
                            text = categoryName,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
                Text(
                    text = "$accountName • ${tx.source.titleAr} • ${MoneyUtils.formatArabicDate(tx.timestamp)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (tx.notes.isNotBlank()) {
                    Text(
                        text = "ملاحظة: ${tx.notes}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = "$prefix${MoneyUtils.formatMinor(tx.amountMinor, tx.currency)}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = amountColor
                )
                Row {
                    IconButton(
                        onClick = onEdit,
                        modifier = Modifier.size(36.dp).testTag("edit_tx_${tx.id}")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = "تعديل العملية",
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    IconButton(
                        onClick = onDeleteRequest,
                        modifier = Modifier.size(36.dp).testTag("delete_tx_${tx.id}")
                    ) {
                        Icon(
                            imageVector = Icons.Default.DeleteOutline,
                            contentDescription = "حذف العملية",
                            tint = ExpenseRed,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
    }
}

/**
 * شاشة سجل العمليات المالية الكاملة مع البحث، التصفية، إدارة التصنيفات، والاسترداد، والتحويلات.
 */
@Composable
fun TransactionsManagementScreen(
    transactions: List<TransactionEntity>,
    filterState: TransactionFilterState,
    accounts: List<AccountEntity>,
    categories: List<CategoryEntity>,
    onUpdateFilter: ((TransactionFilterState) -> TransactionFilterState) -> Unit,
    onClearFilters: () -> Unit,
    onAddTransactionClick: (TransactionType) -> Unit,
    onEditTransaction: (TransactionEntity) -> Unit,
    onDeleteTransactionConfirmed: (Long) -> Unit,
    onSaveCategory: (Long, String, CategoryType, String) -> Unit,
    modifier: Modifier = Modifier
) {
    var txToDelete by remember { mutableStateOf<TransactionEntity?>(null) }
    var showCategoryManager by remember { mutableStateOf(false) }
    val accMap = remember(accounts) { accounts.associateBy { it.id } }
    val catMap = remember(categories) { categories.associateBy { it.id } }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(top = 12.dp, bottom = 96.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "إدارة العمليات المالية",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )
                OutlinedButton(
                    onClick = { showCategoryManager = true },
                    modifier = Modifier.testTag("manage_categories_btn")
                ) {
                    Icon(Icons.Default.Category, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("التصنيفات")
                }
            }
        }

        // أزرار إضافة العمليات (مصروف، دخل، تحويل، استرداد مالي)
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    FilledTonalButton(
                        onClick = { onAddTransactionClick(TransactionType.EXPENSE) },
                        modifier = Modifier.testTag("tx_screen_add_expense")
                    ) {
                        Icon(Icons.Default.Remove, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text("مصروف جديد")
                    }
                }
                item {
                    FilledTonalButton(
                        onClick = { onAddTransactionClick(TransactionType.INCOME) },
                        modifier = Modifier.testTag("tx_screen_add_income")
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text("دخل جديد")
                    }
                }
                item {
                    FilledTonalButton(
                        onClick = { onAddTransactionClick(TransactionType.REFUND) },
                        modifier = Modifier.testTag("tx_screen_add_refund")
                    ) {
                        Icon(Icons.Default.Replay, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text("استرداد مالي")
                    }
                }
                item {
                    FilledTonalButton(
                        onClick = { onAddTransactionClick(TransactionType.TRANSFER) },
                        modifier = Modifier.testTag("tx_screen_add_transfer")
                    ) {
                        Icon(Icons.AutoMirrored.Filled.CompareArrows, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text("تحويل بين حسابين")
                    }
                }
            }
        }

        // شريط البحث والتصفية
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = filterState.searchQuery,
                        onValueChange = { q -> onUpdateFilter { it.copy(searchQuery = q) } },
                        label = { Text("بحث بالوصف، التاجر، البنك، الملاحظات، أو المرجع") },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("tx_search_input")
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = filterState.minAmountText,
                            onValueChange = { v -> onUpdateFilter { it.copy(minAmountText = v) } },
                            label = { Text("أقل مبلغ") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = filterState.maxAmountText,
                            onValueChange = { v -> onUpdateFilter { it.copy(maxAmountText = v) } },
                            label = { Text("أعلى مبلغ") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        item {
                            FilterChip(
                                selected = filterState.selectedType == null,
                                onClick = { onUpdateFilter { it.copy(selectedType = null) } },
                                label = { Text("الكل") }
                            )
                        }
                        items(TransactionType.entries) { tType ->
                            FilterChip(
                                selected = filterState.selectedType == tType,
                                onClick = {
                                    onUpdateFilter {
                                        it.copy(selectedType = if (it.selectedType == tType) null else tType)
                                    }
                                },
                                label = { Text(tType.titleAr) }
                            )
                        }
                    }

                    if (filterState.searchQuery.isNotBlank() ||
                        filterState.selectedType != null ||
                        filterState.minAmountText.isNotBlank() ||
                        filterState.maxAmountText.isNotBlank()
                    ) {
                        TextButton(
                            onClick = onClearFilters,
                            modifier = Modifier.align(Alignment.End)
                        ) {
                            Text("مسح الفلاتر")
                        }
                    }
                }
            }
        }

        if (transactions.isEmpty()) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "لا توجد عمليات مطابقة لمعايير البحث الحالية",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            items(transactions, key = { it.id }) { tx ->
                TransactionRowCard(
                    tx = tx,
                    accountName = accMap[tx.accountId]?.name ?: "حساب",
                    categoryName = tx.categoryId?.let { catMap[it]?.nameAr } ?: tx.type.titleAr,
                    onEdit = { onEditTransaction(tx) },
                    onDeleteRequest = { txToDelete = tx }
                )
            }
        }
    }

    if (txToDelete != null) {
        val target = txToDelete!!
        AlertDialog(
            onDismissRequest = { txToDelete = null },
            title = { Text("تأكيد حذف العملية") },
            text = {
                Text("هل تريد بالتأكيد حذف «${target.description}» (${MoneyUtils.formatMinor(target.amountMinor, target.currency)})؟")
            },
            confirmButton = {
                Button(
                    onClick = {
                        onDeleteTransactionConfirmed(target.id)
                        txToDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = ExpenseRed)
                ) {
                    Text("تأكيد الحذف")
                }
            },
            dismissButton = {
                TextButton(onClick = { txToDelete = null }) { Text("إلغاء") }
            }
        )
    }

    if (showCategoryManager) {
        CategoryManagerDialog(
            categories = categories,
            onDismiss = { showCategoryManager = false },
            onSaveCategory = onSaveCategory
        )
    }
}

@Composable
fun TransactionFormDialog(
    initialType: TransactionType,
    editingTransaction: TransactionEntity?,
    accounts: List<AccountEntity>,
    categories: List<CategoryEntity>,
    onDismiss: () -> Unit,
    onSave: (
        existingId: Long,
        type: TransactionType,
        amountText: String,
        currency: String,
        description: String,
        notes: String,
        accountId: Long,
        toAccountId: Long?,
        categoryId: Long?,
        merchantName: String
    ) -> Unit
) {
    var selectedType by remember { mutableStateOf(editingTransaction?.type ?: initialType) }
    var amountText by remember {
        mutableStateOf(editingTransaction?.let { MoneyUtils.formatPlainDecimal(it.amountMinor) } ?: "")
    }
    var currency by remember { mutableStateOf(editingTransaction?.currency ?: "SAR") }
    var description by remember { mutableStateOf(editingTransaction?.description ?: "") }
    var merchantName by remember { mutableStateOf(editingTransaction?.merchantName ?: "") }
    var notes by remember { mutableStateOf(editingTransaction?.notes ?: "") }
    var selectedAccountId by remember {
        mutableStateOf(editingTransaction?.accountId ?: accounts.firstOrNull()?.id ?: 1L)
    }
    var selectedToAccountId by remember {
        mutableStateOf(editingTransaction?.toAccountId ?: accounts.drop(1).firstOrNull()?.id)
    }
    var selectedCategoryId by remember {
        mutableStateOf(editingTransaction?.categoryId ?: categories.firstOrNull()?.id)
    }

    val currencies = listOf("SAR", "EGP", "AED", "KWD", "USD")

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (editingTransaction == null) "إضافة ${selectedType.titleAr}"
                else "تعديل عملية مالية"
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(
                        listOf(
                            TransactionType.EXPENSE,
                            TransactionType.INCOME,
                            TransactionType.REFUND,
                            TransactionType.TRANSFER
                        )
                    ) { t ->
                        FilterChip(
                            selected = selectedType == t,
                            onClick = { selectedType = t },
                            label = { Text(t.titleAr) }
                        )
                    }
                }

                if (selectedType == TransactionType.REFUND) {
                    Text(
                        text = "ملاحظة: يُخصم الاسترداد المالي من إجمالي المصروفات ويعيد المبلغ للحساب دون احتسابه دخلًا عاديًا.",
                        style = MaterialTheme.typography.bodySmall,
                        color = RefundTeal
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = amountText,
                        onValueChange = { amountText = it },
                        label = { Text("المبلغ") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier.weight(1.4f).testTag("tx_form_amount_input")
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text("العملة", style = MaterialTheme.typography.labelMedium)
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            items(currencies) { cur ->
                                FilterChip(
                                    selected = currency == cur,
                                    onClick = { currency = cur },
                                    label = { Text(MoneyUtils.currencySymbol(cur)) }
                                )
                            }
                        }
                    }
                }

                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("وصف العملية") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("tx_form_desc_input")
                )

                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text("ملاحظات إضافية (اختياري)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                if (accounts.isNotEmpty()) {
                    Text(
                        text = if (selectedType == TransactionType.TRANSFER) "من الحساب:" else "الحساب:",
                        style = MaterialTheme.typography.labelLarge
                    )
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(accounts) { acc ->
                            FilterChip(
                                selected = selectedAccountId == acc.id,
                                onClick = {
                                    selectedAccountId = acc.id
                                    currency = acc.currency
                                },
                                label = { Text(acc.name) }
                            )
                        }
                    }
                }

                if (selectedType == TransactionType.TRANSFER && accounts.size > 1) {
                    Text(text = "إلى الحساب المستلم:", style = MaterialTheme.typography.labelLarge)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(accounts.filter { it.id != selectedAccountId }) { acc ->
                            FilterChip(
                                selected = selectedToAccountId == acc.id,
                                onClick = { selectedToAccountId = acc.id },
                                label = { Text(acc.name) }
                            )
                        }
                    }
                }

                if (selectedType != TransactionType.TRANSFER && categories.isNotEmpty()) {
                    Text(text = "التصنيف:", style = MaterialTheme.typography.labelLarge)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(categories) { cat ->
                            FilterChip(
                                selected = selectedCategoryId == cat.id,
                                onClick = { selectedCategoryId = cat.id },
                                label = { Text(cat.nameAr) }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(
                        editingTransaction?.id ?: 0L,
                        selectedType,
                        amountText,
                        currency,
                        description,
                        notes,
                        selectedAccountId,
                        if (selectedType == TransactionType.TRANSFER) selectedToAccountId else null,
                        selectedCategoryId,
                        merchantName
                    )
                    onDismiss()
                },
                modifier = Modifier.testTag("tx_form_save_btn")
            ) {
                Text("حفظ العملية")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("إلغاء") }
        }
    )
}

@Composable
private fun CategoryManagerDialog(
    categories: List<CategoryEntity>,
    onDismiss: () -> Unit,
    onSaveCategory: (Long, String, CategoryType, String) -> Unit
) {
    var nameAr by remember { mutableStateOf("") }
    var selectedType by remember { mutableStateOf(CategoryType.EXPENSE) }
    var editingId by remember { mutableStateOf(0L) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("إدارة وتعديل التصنيفات") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = nameAr,
                    onValueChange = { nameAr = it },
                    label = { Text(if (editingId == 0L) "اسم التصنيف الجديد" else "تعديل اسم التصنيف") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = selectedType == CategoryType.EXPENSE,
                        onClick = { selectedType = CategoryType.EXPENSE },
                        label = { Text("مصروفات") }
                    )
                    FilterChip(
                        selected = selectedType == CategoryType.INCOME,
                        onClick = { selectedType = CategoryType.INCOME },
                        label = { Text("دخل") }
                    )
                }
                Button(
                    onClick = {
                        if (nameAr.isNotBlank()) {
                            onSaveCategory(editingId, nameAr, selectedType, "#0D4732")
                            nameAr = ""
                            editingId = 0L
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (editingId == 0L) "إضافة تصنيف" else "حفظ تعديل التصنيف")
                }

                HorizontalDivider()
                Text("التصنيفات الحالية (اضغط للتعديل):", style = MaterialTheme.typography.labelLarge)
                LazyColumn(modifier = Modifier.heightIn(max = 180.dp)) {
                    items(categories) { cat ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    editingId = cat.id
                                    nameAr = cat.nameAr
                                    selectedType = cat.type
                                }
                                .padding(vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(cat.nameAr, style = MaterialTheme.typography.bodyMedium)
                            Text(cat.type.titleAr, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("إغلاق") }
        }
    )
}

/**
 * نافذة الإملاء الصوتي بالعربية مع شاشة المراجعة قبل التسجيل وتوضيح متطلبات تحويل الصوت إلى نص.
 */
@Composable
fun VoiceExpenseDialog(
    proposal: VoiceExpenseProposal?,
    accounts: List<AccountEntity>,
    categories: List<CategoryEntity>,
    onAnalyzePhrase: (String) -> Unit,
    onConfirmProposal: (VoiceExpenseProposal, Long, String?, String?, Long?) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var phraseInput by remember { mutableStateOf("دفعت خمسة وثلاثين ريال بنزين") }
    var speechErrorNote by remember { mutableStateOf<String?>(null) }

    var selectedAccountId by remember { mutableStateOf(accounts.firstOrNull()?.id ?: 1L) }
    var editableAmount by remember(proposal) {
        mutableStateOf(proposal?.amountMinor?.let { MoneyUtils.formatPlainDecimal(it) } ?: "")
    }
    var editableDesc by remember(proposal) {
        mutableStateOf(proposal?.description ?: "")
    }
    var editableCategoryId by remember(proposal) {
        mutableStateOf(proposal?.suggestedCategoryId)
    }

    val speechLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val matches = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            val spoken = matches?.firstOrNull()
            if (!spoken.isNullOrBlank()) {
                phraseInput = spoken
                onAnalyzePhrase(spoken)
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("الإملاء الصوتي للمصروفات بالعربية") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Text(
                        text = "تنويه شفافية: يعتمد تحويل الصوت المنطوق إلى نص (Speech-to-Text) على محرك التعرف الصوتي في نظام Android (والذي قد يتطلب اتصالًا بالإنترنت في بعض الهواتف إذا لم تُحمّل حزمة اللغة العربية دون اتصال). أما استخراج المبلغ والوصف والتصنيف فيتم محليًا ١٠٠٪ داخل التطبيق.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(10.dp)
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = phraseInput,
                        onValueChange = { phraseInput = it },
                        label = { Text("عبارة المصروف بالعربية") },
                        modifier = Modifier.weight(1f).testTag("voice_phrase_input")
                    )
                    FilledIconButton(
                        onClick = {
                            try {
                                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ar-SA")
                                    putExtra(RecognizerIntent.EXTRA_PROMPT, "انطق المصروف مثل: دفعت خمسة وثلاثين ريال بنزين")
                                }
                                speechLauncher.launch(intent)
                            } catch (e: Exception) {
                                speechErrorNote = "محرك التعرف الصوتي غير متاح حاليًا على هذا الجهاز؛ يمكنك كتابة أو تعديل الجملة مباشرة ثم الضغط على «تحليل الجملة»."
                            }
                        }
                    ) {
                        Icon(Icons.Default.Mic, contentDescription = "بدء التحدث")
                    }
                }

                if (speechErrorNote != null) {
                    Text(
                        text = speechErrorNote!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = WarningAmber
                    )
                }

                Button(
                    onClick = { onAnalyzePhrase(phraseInput) },
                    modifier = Modifier.fillMaxWidth().testTag("analyze_voice_btn")
                ) {
                    Text("تحليل الجملة وعرض شاشة المراجعة")
                }

                if (proposal != null) {
                    HorizontalDivider()
                    Text(
                        text = "شاشة مراجعة المصروف المستخرج قبل التسجيل:",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    OutlinedTextField(
                        value = editableAmount,
                        onValueChange = { editableAmount = it },
                        label = { Text("المبلغ المستخرج (${MoneyUtils.currencySymbol(proposal.currency)})") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("voice_review_amount")
                    )
                    OutlinedTextField(
                        value = editableDesc,
                        onValueChange = { editableDesc = it },
                        label = { Text("الوصف المستخرج") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("voice_review_desc")
                    )
                    Text(
                        text = "التصنيف المقترح: ${proposal.suggestedCategoryName}",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        },
        confirmButton = {
            if (proposal != null) {
                Button(
                    onClick = {
                        onConfirmProposal(
                            proposal,
                            selectedAccountId,
                            editableAmount,
                            editableDesc,
                            editableCategoryId
                        )
                        onDismiss()
                    },
                    modifier = Modifier.testTag("confirm_voice_expense_btn")
                ) {
                    Text("تأكيد وتسجيل المصروف")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("إغلاق") }
        }
    )
}
