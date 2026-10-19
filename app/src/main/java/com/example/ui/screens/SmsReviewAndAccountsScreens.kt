package com.example.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.data.local.*
import com.example.domain.MoneyUtils
import com.example.ui.theme.*

@Composable
fun SmsReviewScreen(
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
    onAddCustomBank: (String, String, String, String) -> Unit,
    onAddCustomRule: (Long, String, String, SmsSuggestedType) -> Unit,
    onIngestManualOrTestSms: (String, String, Boolean) -> Unit,
    onLoadSandboxSamples: () -> Unit,
    onClearSandbox: () -> Unit,
    onScanDeviceInbox: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var selectedSection by remember { mutableIntStateOf(0) } // 0 = Pending, 1 = Unclear, 2 = Banks & Rules & Sandbox, 3 = History
    var editingSuggested by remember { mutableStateOf<SuggestedTransactionEntity?>(null) }
    var showPermissionExplanationDialog by remember { mutableStateOf(false) }

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

    val pendingItems = remember(suggestedTransactions) {
        suggestedTransactions.filter { it.status == ReviewStatus.PENDING }
    }
    val unclearItems = remember(suggestedTransactions) {
        suggestedTransactions.filter { it.status == ReviewStatus.UNCLEAR }
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
                text = "قراءة رسائل البنوك والمراجعة",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "المعالجة محلية ١٠٠٪ على جهازك دون أي خادم أو ذكاء اصطناعي خارجي. لا تُسجل أي عملية إلا بعد ضغطك على «تأكيد وتسجيل».",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // بطاقة الصلاحيات وفحص صندوق الرسائل الفعلي
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
                            text = if (hasSmsPermission) "الرصد التلقائي للرسائل البنكية مفعل"
                            else "تفعيل قراءة رسائل البنوك من الهاتف",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "نطلب صلاحية الرسائل فقط عند رغبتك في استيراد إشعارات البنوك تلقائيًا، ولا نحتفظ إلا بالبيانات اللازمة للمراجعة ومنع التكرار.",
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
                        Text(if (hasSmsPermission) "فحص الوارد" else "منح الصلاحية")
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
                        label = { Text("عمليات تحتاج إلى مراجعة (${pendingItems.size})") },
                        modifier = Modifier.testTag("sms_pending_tab")
                    )
                }
                item {
                    FilterChip(
                        selected = selectedSection == 1,
                        onClick = { selectedSection = 1 },
                        label = { Text("رسائل غير واضحة (${unclearItems.size})") },
                        modifier = Modifier.testTag("sms_unclear_tab")
                    )
                }
                item {
                    FilterChip(
                        selected = selectedSection == 2,
                        onClick = { selectedSection = 2 },
                        label = { Text("البنوك والقواعد ومختبر الرسائل") },
                        modifier = Modifier.testTag("sms_rules_sandbox_tab")
                    )
                }
                item {
                    FilterChip(
                        selected = selectedSection == 3,
                        onClick = { selectedSection = 3 },
                        label = { Text("سجل المراجعة (${reviewedItems.size})") },
                        modifier = Modifier.testTag("sms_history_tab")
                    )
                }
            }
        }

        when (selectedSection) {
            0 -> {
                if (pendingItems.isEmpty()) {
                    item {
                        EmptySmsQueueCard(
                            title = "لا توجد عمليات معلقة تحتاج إلى مراجعة حاليًا",
                            subtitle = "يمكنك فحص صندوق الوارد أو تجربة محلل رسائل البنوك من تبويب «البنوك والقواعد ومختبر الرسائل».",
                            onOpenSandbox = { selectedSection = 2 }
                        )
                    }
                } else {
                    items(pendingItems, key = { it.id }) { item ->
                        SuggestedTransactionReviewCard(
                            item = item,
                            onConfirm = { onConfirmSuggested(item.id) },
                            onEdit = { editingSuggested = item },
                            onIgnore = { onIgnoreSuggested(item) },
                            onMarkDuplicate = { onMarkDuplicate(item) }
                        )
                    }
                }
            }
            1 -> {
                item {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = WarningAmber.copy(alpha = 0.14f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "الرسائل غير الواضحة هي رسائل بنكية لم يتمكن المحرك من استخراج مبلغها أو نوعها بثقة ١٠٠٪؛ نعرضها هنا لتعديلها يدويًا أو تجاهلها بدلًا من اختراع بيانات.",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }
                if (unclearItems.isEmpty()) {
                    item {
                        EmptySmsQueueCard(
                            title = "لا توجد رسائل غير واضحة",
                            subtitle = "جميع الرسائل المستوردة تم تصنيفها بوضوح.",
                            onOpenSandbox = { selectedSection = 2 }
                        )
                    }
                } else {
                    items(unclearItems, key = { it.id }) { item ->
                        SuggestedTransactionReviewCard(
                            item = item,
                            onConfirm = { onConfirmSuggested(item.id) },
                            onEdit = { editingSuggested = item },
                            onIgnore = { onIgnoreSuggested(item) },
                            onMarkDuplicate = { onMarkDuplicate(item) }
                        )
                    }
                }
            }
            2 -> {
                item {
                    SmsSandboxAndBanksSection(
                        banks = banks,
                        smsRules = smsRules,
                        onToggleBankSms = onToggleBankSms,
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
            3 -> {
                if (reviewedItems.isEmpty()) {
                    item {
                        Text(
                            text = "لا توجد رسائل تمت مراجعتها بعد.",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(24.dp)
                        )
                    }
                } else {
                    items(reviewedItems, key = { it.id }) { item ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "${item.bankName} • ${item.suggestedType.titleAr}",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = "التاجر: ${item.merchantName.ifBlank { "غير محدد" }} | المرجع: ${item.referenceNumber ?: "بدون"}",
                                        style = MaterialTheme.typography.bodySmall
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
            title = { Text("لماذا نطلب صلاحية الرسائل النصية؟") },
            text = {
                Text(
                    "يحتاج تطبيق «الصعيدي قرش» إلى صلاحية استقبال/قراءة الرسائل (SMS) حصريًا للتعرف على إشعارات البنوك (مثل الراجحي والأهلي والإنماء) وتحويلها محليًا على هاتفك إلى عمليات مقترحة تراجعها بنفسك.\n\n" +
                        "• لا يملك التطبيق صلاحية الاتصال بالإنترنت أصلًا.\n" +
                        "• لا نطلب أرقام بطاقات كاملة أو كلمات مرور أو رموز OTP."
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showPermissionExplanationDialog = false
                        smsPermissionLauncher.launch(
                            arrayOf(
                                Manifest.permission.RECEIVE_SMS,
                                Manifest.permission.READ_SMS
                            )
                        )
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

@Composable
fun SuggestedTransactionReviewCard(
    item: SuggestedTransactionEntity,
    onConfirm: () -> Unit,
    onEdit: () -> Unit,
    onIgnore: () -> Unit,
    onMarkDuplicate: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("suggested_card_${item.id}"),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Text(
                            text = item.bankName,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                    if (item.isTestSandbox) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = SaidiGoldLight
                        ) {
                            Text(
                                text = "بيئة اختبار",
                                style = MaterialTheme.typography.labelMedium,
                                color = SaidiGoldDark,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                            )
                        }
                    }
                }
                Text(
                    text = item.extractedAmountMinor?.let { MoneyUtils.formatMinor(it, item.currency) }
                        ?: "المبلغ غير واضح",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = if (item.extractedAmountMinor != null) MaterialTheme.colorScheme.primary else WarningAmber
                )
            }

            Text(
                text = "نوع العملية: ${item.suggestedType.titleAr} • التاجر/الجهة: ${item.merchantName.ifBlank { "غير محدد" }}",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold
            )

            Text(
                text = "التصنيف المقترح: ${item.suggestedCategoryName} • التاريخ: ${MoneyUtils.formatArabicDate(item.smsTimestamp)}" +
                    (item.referenceNumber?.let { " • مرجع: $it" } ?: ""),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = item.sanitizedSnippet,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(8.dp)
                )
            }

            // الأزرار الأربعة الإلزامية لكل عملية مقترحة
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Button(
                    onClick = onConfirm,
                    enabled = item.extractedAmountMinor != null && item.extractedAmountMinor > 0L,
                    modifier = Modifier
                        .weight(1.3f)
                        .testTag("confirm_sms_btn_${item.id}")
                ) {
                    Text("تأكيد وتسجيل", style = MaterialTheme.typography.labelMedium)
                }
                OutlinedButton(
                    onClick = onEdit,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("edit_sms_btn_${item.id}")
                ) {
                    Text("تعديل البيانات", style = MaterialTheme.typography.labelMedium)
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                TextButton(
                    onClick = onIgnore,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("ignore_sms_btn_${item.id}")
                ) {
                    Text("تجاهل")
                }
                TextButton(
                    onClick = onMarkDuplicate,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("duplicate_sms_btn_${item.id}")
                ) {
                    Text("تعليم كرسالة مكررة")
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
                Text("فتح مختبر الرسائل البنكية وإعدادات البنوك")
            }
        }
    }
}

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
        title = { Text("تعديل بيانات العملية المقترحة قبل التأكيد") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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

                if (categories.isNotEmpty()) {
                    Text("التصنيف:", style = MaterialTheme.typography.labelLarge)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(categories) { cat ->
                            FilterChip(
                                selected = selectedCatId == cat.id,
                                onClick = { selectedCatId = cat.id },
                                label = { Text(cat.nameAr) }
                            )
                        }
                    }
                }

                if (accounts.isNotEmpty()) {
                    Text("الحساب المرتبط:", style = MaterialTheme.typography.labelLarge)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(accounts) { acc ->
                            FilterChip(
                                selected = selectedAccId == acc.id,
                                onClick = { selectedAccId = acc.id },
                                label = { Text(acc.name) }
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

@Composable
private fun SmsSandboxAndBanksSection(
    banks: List<BankEntity>,
    smsRules: List<SmsRuleEntity>,
    onToggleBankSms: (BankEntity, Boolean) -> Unit,
    onAddCustomBank: (String, String, String, String) -> Unit,
    onAddCustomRule: (Long, String, String, SmsSuggestedType) -> Unit,
    onIngestSms: (String, String, Boolean) -> Unit,
    onLoadSandboxSamples: () -> Unit,
    onClearSandbox: () -> Unit
) {
    var testSender by remember { mutableStateOf("AlRajhiBank") }
    var testSmsBody by remember {
        mutableStateOf("شراء عبر نقاط البيع\nبمبلغ: SAR 185.75\nلدى: بندة للتجزئة\nمرجع: RJ551209")
    }
    var newBankName by remember { mutableStateOf("") }
    var newBankSender by remember { mutableStateOf("") }
    var newBankCurrency by remember { mutableStateOf("SAR") }

    var ruleName by remember { mutableStateOf("") }
    var rulePattern by remember { mutableStateOf("") }
    var ruleBankId by remember { mutableStateOf(banks.firstOrNull()?.id ?: 1L) }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        // 1. مختبر فحص الرسائل البنكية (منفصل عن بيانات المستخدم الحقيقية)
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "مختبر تحليل رسائل البنوك (بيئة اختبار منفصلة)",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "جرّب لصق أي رسالة بنكية عربية أو إنجليزية لاختبار المحلل الفعلي، أو حمّل حزمة رسائل تجريبية متنوعة (شراء، حوالة، استرداد، فاتورة، إشعار رصيد، وعرض خصم تسويقي).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = testSender,
                    onValueChange = { testSender = it },
                    label = { Text("اسم المرسل (مثل AlRajhiBank أو SNB أو CIB)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("sandbox_sender_input")
                )
                OutlinedTextField(
                    value = testSmsBody,
                    onValueChange = { testSmsBody = it },
                    label = { Text("نص الرسالة البنكية") },
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

        // 2. تفعيل أو تعطيل قراءة الرسائل لكل بنك
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "البنوك المدعومة (تفعيل / إيقاف قراءة الرسائل لكل بنك)",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                banks.forEach { bank ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(bank.nameAr, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            Text(
                                text = "معرفات المرسل: ${bank.senderIdsCsv} • العملة: ${bank.defaultCurrency}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = bank.smsEnabled,
                            onCheckedChange = { enabled -> onToggleBankSms(bank, enabled) },
                            modifier = Modifier.testTag("toggle_bank_${bank.code}")
                        )
                    }
                    HorizontalDivider()
                }

                Text("إضافة بنك جديد قابل للتوسعة:", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = newBankName,
                        onValueChange = { newBankName = it },
                        label = { Text("اسم البنك") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = newBankSender,
                        onValueChange = { newBankSender = it },
                        label = { Text("اسم المرسل SMS") },
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
                    Text("إضافة البنك")
                }
            }
        }

        // 3. قواعد الرسائل القابلة للتوسعة
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "قواعد التعرف على رسائل البنوك (${smsRules.size} قاعدة نشطة)",
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
 * شاشة إدارة الحسابات والبنوك والتسوية اليدوية للأرصدة (نقدي، بنكي، بطاقة ائتمان، محفظة إلكترونية).
 */
@Composable
fun AccountsManagementScreen(
    accounts: List<AccountEntity>,
    banks: List<BankEntity>,
    onAddOrUpdateAccount: (Long, String, AccountType, Long?, String, String, Boolean) -> Unit,
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
                Column {
                    Text(
                        text = "الحسابات والبطاقات والمحافظ",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "إدارة محلية متعددة الحسابات دون أي ربط بنكي خارجي (Open Banking)",
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
                        Column {
                            Text(
                                text = account.name,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "${account.type.titleAr} • ${account.bankName.ifBlank { "بدون بنك" }} • العملة: ${account.currency}",
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
                            Text("ربط مع رسائل البنك", style = MaterialTheme.typography.bodySmall)
                        }
                        OutlinedButton(
                            onClick = { reconcilingAccount = account },
                            modifier = Modifier.testTag("reconcile_acc_btn_${account.id}")
                        ) {
                            Icon(Icons.Default.Balance, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("تسوية الرصيد يدويًا")
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
        var smsEnabled by remember { mutableStateOf(true) }

        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text("إضافة حساب مالي جديد") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("اسم الحساب المستعار (مثل: جاري الراجحي أو محفظة STC Pay)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("new_account_name_input")
                    )

                    Text("نوع الحساب:", style = MaterialTheme.typography.labelLarge)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(AccountType.entries) { aType ->
                            FilterChip(
                                selected = selectedType == aType,
                                onClick = { selectedType = aType },
                                label = { Text(aType.titleAr) }
                            )
                        }
                    }

                    if (selectedType != AccountType.CASH && banks.isNotEmpty()) {
                        Text("البنك المرتبط:", style = MaterialTheme.typography.labelLarge)
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
                            value = openingBalance,
                            onValueChange = { openingBalance = it },
                            label = { Text("الرصيد الافتتاحي") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true,
                            modifier = Modifier.weight(1.3f).testTag("new_account_balance_input")
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text("العملة", style = MaterialTheme.typography.labelMedium)
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                items(listOf("SAR", "EGP", "AED", "USD")) { cur ->
                                    FilterChip(
                                        selected = currency == cur,
                                        onClick = { currency = cur },
                                        label = { Text(MoneyUtils.currencySymbol(cur)) }
                                    )
                                }
                            }
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
                            smsEnabled
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
