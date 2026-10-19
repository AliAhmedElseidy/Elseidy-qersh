package com.example.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.data.local.*
import com.example.domain.MoneyUtils
import com.example.ui.theme.*

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SmsReviewScreen(
    selectedYearMonth: String = MoneyUtils.currentYearMonth(),
    suggestedTransactions: List<SuggestedTransactionEntity>,
    banks: List<BankEntity>,
    smsRules: List<SmsRuleEntity>,
    accounts: List<AccountEntity>,
    categories: List<CategoryEntity>,
    onConfirmSuggested: (Long) -> Unit,
    onEditSuggestedBeforeConfirm: (SuggestedTransactionEntity, String, String, Long?, Long?, SmsSuggestedType) -> Unit,
    onIgnoreSuggested: (SuggestedTransactionEntity) -> Unit,
    onMarkDuplicate: (SuggestedTransactionEntity) -> Unit,
    onToggleBankSms: (BankEntity, Boolean) -> Unit,
    onToggleBankAutoApprove: (BankEntity, Boolean) -> Unit = { _, _ -> },
    onAddCustomBank: (String, String, String, String) -> Unit,
    onAddCustomRule: (Long, String, String, SmsSuggestedType) -> Unit,
    onIngestManualOrTestSms: (String, String, Boolean) -> Unit,
    onLoadSandboxSamples: () -> Unit,
    onClearSandbox: () -> Unit,
    onScanDeviceInbox: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var selectedSection by remember { mutableIntStateOf(0) } // 0 = Review Queue, 1 = Sender Rules & Control + Sandbox, 2 = History
    var editingSuggested by remember { mutableStateOf<SuggestedTransactionEntity?>(null) }
    var showPermissionExplanationDialog by remember { mutableStateOf(false) }

    val accMap = remember(accounts) { accounts.associateBy { it.id } }
    val monthArabicLabel = remember(selectedYearMonth) { MoneyUtils.formatYearMonthArabic(selectedYearMonth) }

    var hasSmsPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED
        )
    }

    val smsPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val granted = result[Manifest.permission.READ_SMS] == true ||
            result[Manifest.permission.RECEIVE_SMS] == true
        hasSmsPermission = granted
        if (granted) {
            onScanDeviceInbox()
        }
    }

    val reviewQueueItems = remember(suggestedTransactions) {
        suggestedTransactions.filter {
            it.status == ReviewStatus.PENDING || it.status == ReviewStatus.UNCLEAR
        }
    }
    val reviewedItems = remember(suggestedTransactions) {
        suggestedTransactions.filter {
            it.status == ReviewStatus.CONFIRMED ||
                it.status == ReviewStatus.IGNORED ||
                it.status == ReviewStatus.DUPLICATE
        }
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(top = 12.dp, bottom = 96.dp)
    ) {
        item {
            Text(
                text = "رسائل البنوك والمراجعة الذكية ($monthArabicLabel)",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "قراءة رسائل شهر $monthArabicLabel فقط (من يوم 1 حتى اليوم)، مع التعرف الذكي على اسم المرسل الفعلي (Alinma, AlRajhi, Enjaz, Urpay, STC Pay, VF-Cash) واستبعاد رسائل التفعيل وOTP تلقائياً.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // بطاقة الصلاحيات وفحص صندوق الرسائل الفعلي والعمل في الخلفية
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = if (hasSmsPermission) "الرصد التلقائي في الخلفية والإشعارات مفعل"
                            else "تفعيل قراءة رسائل البنوك والإشعارات الفورية",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "يعمل حتى والتطبيق مغلق مع إشعار فوري بأزرار (أضف / تجاهل) أو اعتماد تلقائي حسب إعدادات كل جهة.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = {
                            if (hasSmsPermission) {
                                onScanDeviceInbox()
                            } else {
                                showPermissionExplanationDialog = true
                            }
                        },
                        modifier = Modifier.testTag("scan_or_request_sms_btn")
                    ) {
                        Icon(Icons.Default.Sms, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (hasSmsPermission) "فحص الشهر" else "منح الصلاحية")
                    }
                }
            }
        }

        // شريط التبويبات الفرعية
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    FilterChip(
                        selected = selectedSection == 0,
                        onClick = { selectedSection = 0 },
                        label = { Text("الرسائل التي تحتاج مراجعة (${reviewQueueItems.size})") },
                        modifier = Modifier.testTag("sms_pending_tab")
                    )
                }
                item {
                    FilterChip(
                        selected = selectedSection == 1,
                        onClick = { selectedSection = 1 },
                        label = { Text("إدارة جهات الرسائل المسموحة (${banks.size})") },
                        modifier = Modifier.testTag("sms_rules_sandbox_tab")
                    )
                }
                item {
                    FilterChip(
                        selected = selectedSection == 2,
                        onClick = { selectedSection = 2 },
                        label = { Text("سجل المراجعة (${reviewedItems.size})") },
                        modifier = Modifier.testTag("sms_history_tab")
                    )
                }
            }
        }

        when (selectedSection) {
            0 -> {
                if (reviewQueueItems.isEmpty()) {
                    item {
                        EmptySmsQueueCard(
                            title = "لا توجد رسائل بنكية تحتاج إلى مراجعة في شهر $monthArabicLabel",
                            subtitle = "يتم عرض رسائل شهر $monthArabicLabel فقط لمنع تراكم الرسائل القديمة. يمكنك فحص صندوق الوارد أو ضبط «إدارة جهات الرسائل المسموحة» والاعتماد التلقائي.",
                            onOpenSandbox = { selectedSection = 1 }
                        )
                    }
                } else {
                    items(reviewQueueItems, key = { it.id }) { item ->
                        val matchedAccountName = item.suggestedAccountId?.let { accMap[it]?.name }
                        SuggestedTransactionReviewCard(
                            item = item,
                            matchedAccountName = matchedAccountName,
                            onConfirm = {
                                if (item.extractedAmountMinor != null && item.extractedAmountMinor > 0L) {
                                    onConfirmSuggested(item.id)
                                } else {
                                    editingSuggested = item
                                }
                            },
                            onEdit = { editingSuggested = item },
                            onIgnore = { onIgnoreSuggested(item) }
                        )
                    }
                }
            }
            1 -> {
                item {
                    SmsSandboxAndBanksSection(
                        banks = banks,
                        smsRules = smsRules,
                        onToggleBankSms = onToggleBankSms,
                        onToggleBankAutoApprove = onToggleBankAutoApprove,
                        onAddCustomBank = onAddCustomBank,
                        onAddCustomRule = onAddCustomRule,
                        onIngestSms = onIngestManualOrTestSms,
                        onLoadSandboxSamples = {
                            onLoadSandboxSamples()
                            selectedSection = 0
                        },
                        onClearSandbox = onClearSandbox
                    )
                }
            }
            2 -> {
                if (reviewedItems.isEmpty()) {
                    item {
                        Text(
                            text = "لا توجد رسائل تمت مراجعتها في شهر $monthArabicLabel بعد.",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(24.dp)
                        )
                    }
                } else {
                    items(reviewedItems, key = { it.id }) { item ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(14.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    val senderLabel = if (item.senderAddress.isNotBlank() &&
                                        !item.bankName.equals(item.senderAddress, ignoreCase = true)
                                    ) {
                                        "${item.bankName} (${item.senderAddress})"
                                    } else {
                                        item.bankName
                                    }
                                    Text(
                                        text = "$senderLabel • ${item.merchantName.ifBlank { item.suggestedType.titleAr }}",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = MoneyUtils.formatArabicDate(item.smsTimestamp),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = when (item.status) {
                                        ReviewStatus.CONFIRMED -> IncomeGreen.copy(alpha = 0.15f)
                                        ReviewStatus.DUPLICATE -> WarningAmber.copy(alpha = 0.15f)
                                        else -> MaterialTheme.colorScheme.surfaceVariant
                                    }
                                ) {
                                    Text(
                                        text = item.status.titleAr,
                                        style = MaterialTheme.typography.labelMedium,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showPermissionExplanationDialog) {
        AlertDialog(
            onDismissRequest = { showPermissionExplanationDialog = false },
            title = { Text("لماذا نطلب صلاحية الرسائل والإشعارات؟") },
            text = {
                Text(
                    "يحتاج تطبيق «الصعيدي قرش» إلى صلاحية استقبال/قراءة الرسائل (SMS) للتعرف على إشعارات البنوك والمحافظ (مثل الإنماء، الراجحي، إنجاز، Urpay، STC Pay، فودافون كاش) حتى والتطبيق مغلق.\n\n" +
                        "• يعرض التطبيق اسم المرسل الحقيقي للرسالة دون افتراض خاطئ.\n" +
                        "• يرسل لك إشعاراً فورياً بأزرار (أضف / تجاهل) أو يعتمدها تلقائياً حسب اختيارك.\n" +
                        "• يقرأ رسائل الشهر الحالي فقط ولا يرسل أي بيانات للإنترنت."
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showPermissionExplanationDialog = false
                        val perms = buildList {
                            add(Manifest.permission.RECEIVE_SMS)
                            add(Manifest.permission.READ_SMS)
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                add(Manifest.permission.POST_NOTIFICATIONS)
                            }
                        }.toTypedArray()
                        smsPermissionLauncher.launch(perms)
                    }
                ) {
                    Text("موافق ومنح الصلاحية")
                }
            },
            dismissButton = {
                TextButton(onClick = { showPermissionExplanationDialog = false }) {
                    Text("ليس الآن")
                }
            }
        )
    }

    if (editingSuggested != null) {
        val target = editingSuggested!!
        EditSuggestedTransactionDialog(
            item = target,
            accounts = accounts,
            categories = categories,
            onDismiss = { editingSuggested = null },
            onSaveEdit = { amtText, merchant, catId, accId, sType ->
                onEditSuggestedBeforeConfirm(target, amtText, merchant, catId, accId, sType)
                editingSuggested = null
            }
        )
    }
}

/**
 * كارت مراجعة الرسالة البنكية مع إظهار الاسم الحقيقي للمرسل (Sender ID) والبنك/الجهة الفعلية
 */
@Composable
fun SuggestedTransactionReviewCard(
    item: SuggestedTransactionEntity,
    matchedAccountName: String? = null,
    onConfirm: () -> Unit,
    onEdit: () -> Unit,
    onIgnore: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onEdit() }
            .testTag("suggested_card_${item.id}"),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.22f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 1. الهيدر (أعلى الكارت)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                // على اليمين (Start في RTL): أيقونة دائرية + اسم الجهة الحقيقي + معرف المرسل + التاريخ والوقت + التاجر
                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.size(44.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.AccountBalance,
                                contentDescription = item.bankName,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = item.bankName.ifBlank { item.senderAddress },
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            if (item.senderAddress.isNotBlank() &&
                                !item.bankName.equals(item.senderAddress, ignoreCase = true)
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = MaterialTheme.colorScheme.secondaryContainer
                                ) {
                                    Text(
                                        text = item.senderAddress,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }
                        Text(
                            text = MoneyUtils.formatArabicDate(item.smsTimestamp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = item.merchantName.ifBlank { item.suggestedType.titleAr },
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        if (matchedAccountName != null || item.extractedAccountLast4.isNotBlank() || item.extractedCardLast4.isNotBlank()) {
                            val badgeText = buildString {
                                if (matchedAccountName != null) append(matchedAccountName)
                                if (item.extractedAccountLast4.isNotBlank()) append(" • حساب *${item.extractedAccountLast4}")
                                if (item.extractedCardLast4.isNotBlank()) append(" • بطاقة *${item.extractedCardLast4}")
                            }
                            Text(
                                text = badgeText,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                // على اليسار (End في RTL): المبلغ المستخرج بخط كبير وبارز جداً أو "تحديد المبلغ يدوياً"
                Column(horizontalAlignment = Alignment.End) {
                    if (item.extractedAmountMinor != null && item.extractedAmountMinor > 0L) {
                        Text(
                            text = MoneyUtils.formatMinorTwoDecimals(item.extractedAmountMinor, item.currency),
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    } else {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = WarningAmber.copy(alpha = 0.16f),
                            onClick = onEdit
                        ) {
                            Text(
                                text = "تحديد المبلغ يدوياً",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                color = WarningAmber,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                            )
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier
                            .padding(top = 4.dp)
                            .clickable { onEdit() }
                            .testTag("edit_sms_btn_${item.id}")
                    ) {
                        Text(
                            text = item.suggestedCategoryName,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = "تعديل البيانات",
                            modifier = Modifier.size(15.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // 2. جسم الكارت: صندوق أنيق بحواف دائرية ولون خلفية مميز يعرض النص الأصلي للرسالة البنكية بوضوح
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f))
                    .padding(12.dp)
            ) {
                Text(
                    text = item.sanitizedSnippet,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            // 3. الأزرار السفلية (تبسيط الإجراءات: زر رئيسي عريض "أضف" + زر ثانوي "تجاهل")
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = onConfirm,
                    modifier = Modifier
                        .weight(1.7f)
                        .height(48.dp)
                        .testTag("confirm_sms_btn_${item.id}"),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    )
                ) {
                    Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "أضف",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }

                OutlinedButton(
                    onClick = onIgnore,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("ignore_sms_btn_${item.id}"),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text(
                        text = "تجاهل",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptySmsQueueCard(
    title: String,
    subtitle: String,
    onOpenSandbox: () -> Unit
) {
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
                imageVector = Icons.Default.MarkChatRead,
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Text(text = title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            FilledTonalButton(
                onClick = onOpenSandbox,
                modifier = Modifier.testTag("open_sms_sandbox_btn")
            ) {
                Text("إدارة جهات الرسائل المسموحة ومختبر الرسائل")
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EditSuggestedTransactionDialog(
    item: SuggestedTransactionEntity,
    accounts: List<AccountEntity>,
    categories: List<CategoryEntity>,
    onDismiss: () -> Unit,
    onSaveEdit: (String, String, Long?, Long?, SmsSuggestedType) -> Unit
) {
    var amountText by remember {
        mutableStateOf(item.extractedAmountMinor?.let { MoneyUtils.formatPlainDecimal(it) } ?: "")
    }
    var merchant by remember { mutableStateOf(item.merchantName) }
    var selectedCatId by remember { mutableStateOf(item.suggestedCategoryId ?: categories.firstOrNull()?.id) }
    var selectedAccId by remember { mutableStateOf(item.suggestedAccountId ?: accounts.firstOrNull()?.id) }
    var selectedType by remember {
        mutableStateOf(
            if (item.suggestedType == SmsSuggestedType.UNCLEAR) SmsSuggestedType.CARD_PURCHASE else item.suggestedType
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("تعديل البيانات قبل الحفظ (${item.bankName})") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = amountText,
                    onValueChange = { amountText = it },
                    label = { Text("المبلغ (${MoneyUtils.currencySymbol(item.currency)})") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("edit_suggested_amount_input")
                )
                OutlinedTextField(
                    value = merchant,
                    onValueChange = { merchant = it },
                    label = { Text("اسم التاجر / الجهة") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("edit_suggested_merchant_input")
                )

                Text("نوع العملية:", style = MaterialTheme.typography.labelLarge)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(SmsSuggestedType.entries.filter { it.isFinancialTransaction }) { sType ->
                        FilterChip(
                            selected = selectedType == sType,
                            onClick = { selectedType = sType },
                            label = { Text(sType.titleAr) }
                        )
                    }
                }

                if (accounts.isNotEmpty()) {
                    Text("الحساب المرتبط:", style = MaterialTheme.typography.labelLarge)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        accounts.forEach { acc ->
                            FilterChip(
                                selected = selectedAccId == acc.id,
                                onClick = { selectedAccId = acc.id },
                                label = {
                                    Text(if (acc.accountLast4.isNotBlank()) "${acc.name} (*${acc.accountLast4})" else acc.name)
                                }
                            )
                        }
                    }
                }

                if (categories.isNotEmpty()) {
                    Text("التصنيف:", style = MaterialTheme.typography.labelLarge)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(categories) { cat ->
                            FilterChip(
                                selected = selectedCatId == cat.id,
                                onClick = { selectedCatId = cat.id },
                                label = { Text("${getCategoryEmoji(cat)} ${cat.nameAr}") }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSaveEdit(amountText, merchant, selectedCatId, selectedAccId, selectedType) },
                modifier = Modifier.testTag("save_suggested_edit_btn")
            ) {
                Text("حفظ التعديل")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("إلغاء") }
        }
    )
}

/**
 * شاشة "إدارة جهات الرسائل المسموحة" (Sender Rules & Control) + مختبر الرسائل:
 * - تعرض قائمة بالجهات المكتشفة في رسائل المستخدم (مثل: Alinma, AlRajhi, Enjaz, Vodafone, Urpay, STC Pay, CIB, NBE, Fawry).
 * - لكل جهة (مرسل) خياران مستقلان يتحكم بهما المستخدم:
 *   أ) مفتاح تفعيل (Toggle 1): "قراءة رسائل هذه الجهة" (مفعّل / معطّل) — إذا عطله المستخدم، يتجاهل التطبيق رسائل هذه الجهة تماماً.
 *   ب) مفتاح تفعيل منفصل (Toggle 2 - ميزة مستقلة): "اعتماد وتسجيل تلقائي فوري (Auto-Approve)" — إذا فعّله المستخدم، يقوم التطبيق عند وصول الرسالة باستخراج المبلغ وتسجيل العملية فوراً في الحساب المرتبط دون انتظار مراجعة يدوية.
 */
@Composable
private fun SmsSandboxAndBanksSection(
    banks: List<BankEntity>,
    smsRules: List<SmsRuleEntity>,
    onToggleBankSms: (BankEntity, Boolean) -> Unit,
    onToggleBankAutoApprove: (BankEntity, Boolean) -> Unit,
    onAddCustomBank: (String, String, String, String) -> Unit,
    onAddCustomRule: (Long, String, String, SmsSuggestedType) -> Unit,
    onIngestSms: (String, String, Boolean) -> Unit,
    onLoadSandboxSamples: () -> Unit,
    onClearSandbox: () -> Unit
) {
    var testSender by remember { mutableStateOf("Enjaz") }
    var testSmsBody by remember {
        mutableStateOf("حوالة صادرة عبر إنجاز بمبلغ: 550.00 ريال سعودي المستفيد: محمد أحمد مرجع: ENJ99120")
    }
    var newBankName by remember { mutableStateOf("") }
    var newBankSender by remember { mutableStateOf("") }
    var newBankCurrency by remember { mutableStateOf("SAR") }

    var ruleName by remember { mutableStateOf("") }
    var rulePattern by remember { mutableStateOf("") }
    var ruleBankId by remember { mutableStateOf(banks.firstOrNull()?.id ?: 1L) }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        // 1. شاشة إدارة جهات الرسائل المسموحة (Sender Rules & Control)
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("sender_rules_control_card"),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "إدارة جهات الرسائل المسموحة (Sender Rules & Control)",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "يتعرف التطبيق تلقائياً على اسم المرسل الفعلي (Alinma, AlRajhi, Enjaz, Urpay, STC Pay, VF-Cash...) ويمكنك التحكم في كل جهة بشكل مستقل:",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                banks.forEach { bank ->
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("sender_control_item_${bank.code}"),
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                        border = BorderStroke(
                            1.dp,
                            if (bank.smsEnabled) MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)
                            else MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
                        )
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = bank.nameAr,
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = "معرف المرسل (Sender ID): ${bank.senderIdsCsv} • ${bank.defaultCurrency}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = if (bank.smsEnabled) IncomeGreen.copy(alpha = 0.14f)
                                    else ExpenseRed.copy(alpha = 0.14f)
                                ) {
                                    Text(
                                        text = if (bank.smsEnabled) "مسموح" else "متجاهل",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = if (bank.smsEnabled) IncomeGreen else ExpenseRed,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                }
                            }

                            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))

                            // أ) مفتاح تفعيل (Toggle 1): قراءة رسائل هذه الجهة
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "قراءة رسائل هذه الجهة",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = if (bank.smsEnabled) "مفعّل: يستقبل ويحلل رسائل هذه الجهة"
                                        else "معطّل: يتجاهل التطبيق رسائل هذه الجهة تماماً",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Switch(
                                    checked = bank.smsEnabled,
                                    onCheckedChange = { enabled -> onToggleBankSms(bank, enabled) },
                                    modifier = Modifier.testTag("toggle_bank_${bank.code}")
                                )
                            }

                            // ب) مفتاح تفعيل منفصل (Toggle 2 - ميزة مستقلة): اعتماد وتسجيل تلقائي فوري (Auto-Approve)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "اعتماد وتسجيل تلقائي فوري (Auto-Approve)",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = if (bank.autoApproveEnabled) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = if (bank.autoApproveEnabled) "مفعّل: استخراج المبلغ وتسجيل العملية فوراً في الحساب المرتبط دون انتظار مراجعة"
                                        else "معطّل: إرسال إشعار وإضافة الرسالة لقائمة المراجعة اليدوية",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Switch(
                                    checked = bank.autoApproveEnabled,
                                    enabled = bank.smsEnabled,
                                    onCheckedChange = { autoApprove -> onToggleBankAutoApprove(bank, autoApprove) },
                                    modifier = Modifier.testTag("toggle_auto_approve_${bank.code}")
                                )
                            }
                        }
                    }
                }

                Text("إضافة جهة رسائل / بنك جديد يدوياً:", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = newBankName,
                        onValueChange = { newBankName = it },
                        label = { Text("اسم الجهة (مثل: باي موب)") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = newBankSender,
                        onValueChange = { newBankSender = it },
                        label = { Text("معرف المرسل SMS") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }
                Button(
                    onClick = {
                        if (newBankName.isNotBlank() && newBankSender.isNotBlank()) {
                            onAddCustomBank(newBankName, newBankSender, newBankCurrency, newBankSender)
                            newBankName = ""
                            newBankSender = ""
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("إضافة جهة الرسائل")
                }
            }
        }

        // 2. مختبر تحليل رسائل البنوك والمحافظ
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "مختبر تحليل رسائل الجهات (Alinma / Enjaz / Urpay / STC Pay / VF-Cash)",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "اختبر قراءة اسم المرسل الفعلي مباشرة دون نسبته بالخطأ للراجحي أو الإنماء، أو حمّل حزمة الرسائل المتنوعة لتجربة الاكتشاف الذكي للمرسل والاعتماد التلقائي.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = testSender,
                    onValueChange = { testSender = it },
                    label = { Text("اسم المرسل الفعلي (مثل Enjaz أو Urpay أو STC Pay أو VF-Cash أو Alinma)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("sandbox_sender_input")
                )
                OutlinedTextField(
                    value = testSmsBody,
                    onValueChange = { testSmsBody = it },
                    label = { Text("نص الرسالة المالية") },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth().testTag("sandbox_body_input")
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { onIngestSms(testSender, testSmsBody, true) },
                        modifier = Modifier.weight(1f).testTag("analyze_single_sms_btn")
                    ) {
                        Text("تحليل الرسالة")
                    }
                    FilledTonalButton(
                        onClick = onLoadSandboxSamples,
                        modifier = Modifier.weight(1f).testTag("load_sandbox_samples_btn")
                    ) {
                        Text("تحميل رسائل تجريبية")
                    }
                }
                TextButton(
                    onClick = onClearSandbox,
                    modifier = Modifier.align(Alignment.End).testTag("clear_sandbox_btn")
                ) {
                    Text("مسح جميع الرسائل التجريبية فقط", color = ExpenseRed)
                }
            }
        }

        // 3. قواعد التعرف المخصصة
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "قواعد التعرف على الرسائل (${smsRules.size} قاعدة نشطة)",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                OutlinedTextField(
                    value = ruleName,
                    onValueChange = { ruleName = it },
                    label = { Text("اسم القاعدة (مثل: شراء بطاقة بنك البلاد)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = rulePattern,
                    onValueChange = { rulePattern = it },
                    label = { Text("عبارة أو نمط المطابقة (Regex/Keywords)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Button(
                    onClick = {
                        if (ruleName.isNotBlank() && rulePattern.isNotBlank()) {
                            onAddCustomRule(ruleBankId, ruleName, rulePattern, SmsSuggestedType.CARD_PURCHASE)
                            ruleName = ""
                            rulePattern = ""
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("إضافة قاعدة رسائل جديدة")
                }
            }
        }
    }
}

/**
 * شاشة إدارة الحسابات والبنوك والتسوية اليدوية مع دعم آخر 4 أرقام للحساب والبطاقة للربط الذكي،
 * واستخدام FlowRow مرن لأزرار اختيار الحساب والعملة والبنك.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AccountsManagementScreen(
    accounts: List<AccountEntity>,
    banks: List<BankEntity>,
    onAddOrUpdateAccount: (Long, String, AccountType, Long?, String, String, Boolean, String, String) -> Unit,
    onReconcileAccount: (Long, String, String) -> Unit,
    onToggleAccountSms: (AccountEntity, Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    var showAddDialog by remember { mutableStateOf(false) }
    var reconcilingAccount by remember { mutableStateOf<AccountEntity?>(null) }

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
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "الحسابات والبطاقات والمحافظ",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "الربط الذكي التلقائي عبر آخر 4 أرقام من الحساب (*2000) أو البطاقة (*5650)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Button(
                    onClick = { showAddDialog = true },
                    modifier = Modifier.testTag("add_account_btn")
                ) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text("حساب جديد")
                }
            }
        }

        items(accounts, key = { it.id }) { account ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("account_card_${account.id}"),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = account.name,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            val digitsInfo = buildString {
                                append("${account.type.titleAr} • ${account.bankName.ifBlank { "نقدي" }}")
                                if (account.accountLast4.isNotBlank()) append(" • حساب *${account.accountLast4}")
                                if (account.cardLast4.isNotBlank()) append(" • بطاقة *${account.cardLast4}")
                            }
                            Text(
                                text = digitsInfo,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(
                            text = MoneyUtils.formatMinor(account.currentBalanceMinor, account.currency),
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            color = if (account.currentBalanceMinor >= 0) MaterialTheme.colorScheme.primary else ExpenseRed
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Switch(
                                checked = account.smsParsingEnabled,
                                onCheckedChange = { onToggleAccountSms(account, it) }
                            )
                            Text("ربط تلقائي برسائل البنك", style = MaterialTheme.typography.bodySmall)
                        }
                        OutlinedButton(
                            onClick = { reconcilingAccount = account },
                            modifier = Modifier.testTag("reconcile_acc_btn_${account.id}")
                        ) {
                            Icon(Icons.Default.Balance, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("تسوية الرصيد")
                        }
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        var name by remember { mutableStateOf("") }
        var selectedType by remember { mutableStateOf(AccountType.BANK) }
        var selectedBankId by remember { mutableStateOf<Long?>(banks.firstOrNull()?.id) }
        var currency by remember { mutableStateOf("SAR") }
        var openingBalance by remember { mutableStateOf("0") }
        var accountLast4 by remember { mutableStateOf("") }
        var cardLast4 by remember { mutableStateOf("") }
        var smsEnabled by remember { mutableStateOf(true) }

        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text("إضافة حساب مالي جديد") },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("اسم الحساب (مثل: حساب الإنماء أو محفظة Urpay)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("new_account_name_input")
                    )

                    Text("نوع الحساب:", style = MaterialTheme.typography.labelLarge)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        AccountType.entries.forEach { aType ->
                            FilterChip(
                                selected = selectedType == aType,
                                onClick = { selectedType = aType },
                                label = { Text(aType.titleAr) }
                            )
                        }
                    }

                    if (selectedType != AccountType.CASH && banks.isNotEmpty()) {
                        Text("البنك / الجهة المرتبطة:", style = MaterialTheme.typography.labelLarge)
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(banks) { b ->
                                FilterChip(
                                    selected = selectedBankId == b.id,
                                    onClick = {
                                        selectedBankId = b.id
                                        currency = b.defaultCurrency
                                    },
                                    label = { Text(b.nameAr) }
                                )
                            }
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = accountLast4,
                            onValueChange = { if (it.length <= 4) accountLast4 = it },
                            label = { Text("آخر 4 أرقام للحساب") },
                            placeholder = { Text("2000") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = cardLast4,
                            onValueChange = { if (it.length <= 4) cardLast4 = it },
                            label = { Text("آخر 4 أرقام للبطاقة") },
                            placeholder = { Text("5650") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    OutlinedTextField(
                        value = openingBalance,
                        onValueChange = { openingBalance = it },
                        label = { Text("الرصيد الافتتاحي") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("new_account_balance_input")
                    )

                    Text("العملة:", style = MaterialTheme.typography.labelLarge)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf("SAR", "EGP", "AED", "USD").forEach { cur ->
                            FilterChip(
                                selected = currency == cur,
                                onClick = { currency = cur },
                                label = { Text("${MoneyUtils.currencySymbol(cur)} ($cur)") }
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        onAddOrUpdateAccount(
                            0L,
                            name,
                            selectedType,
                            if (selectedType == AccountType.CASH) null else selectedBankId,
                            currency,
                            openingBalance,
                            smsEnabled,
                            accountLast4,
                            cardLast4
                        )
                        showAddDialog = false
                    },
                    modifier = Modifier.testTag("save_new_account_btn")
                ) {
                    Text("حفظ الحساب")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false }) { Text("إلغاء") }
            }
        )
    }

    if (reconcilingAccount != null) {
        val acc = reconcilingAccount!!
        var newBalText by remember { mutableStateOf(MoneyUtils.formatPlainDecimal(acc.currentBalanceMinor)) }
        var reason by remember { mutableStateOf("مطابقة الرصيد الفعلي في البنك/المحفظة") }

        AlertDialog(
            onDismissRequest = { reconcilingAccount = null },
            title = { Text("تسوية وتعديل رصيد «${acc.name}»") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "الرصيد المسجل حاليًا: ${MoneyUtils.formatMinor(acc.currentBalanceMinor, acc.currency)}",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    OutlinedTextField(
                        value = newBalText,
                        onValueChange = { newBalText = it },
                        label = { Text("الرصيد الفعلي الجديد") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("reconcile_amount_input")
                    )
                    OutlinedTextField(
                        value = reason,
                        onValueChange = { reason = it },
                        label = { Text("سبب التسوية (يُحفظ في سجل التسوية)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        onReconcileAccount(acc.id, newBalText, reason)
                        reconcilingAccount = null
                    },
                    modifier = Modifier.testTag("confirm_reconcile_btn")
                ) {
                    Text("حفظ التسوية")
                }
            },
            dismissButton = {
                TextButton(onClick = { reconcilingAccount = null }) { Text("إلغاء") }
            }
        )
    }
}
