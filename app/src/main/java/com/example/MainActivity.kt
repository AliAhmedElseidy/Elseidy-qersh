package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.data.local.SaidiQershDatabase
import com.example.data.local.TransactionEntity
import com.example.data.local.TransactionType
import com.example.data.repository.SaidiQershRepository
import com.example.ui.SaidiQershViewModel
import com.example.ui.components.SaidiQershWalletLogo
import com.example.ui.screens.*
import com.example.ui.theme.ExpenseRed
import com.example.ui.theme.SaidiQershTheme
import com.example.workers.ObligationReminderWorker

enum class MainDestination(
    val titleAr: String,
    val icon: ImageVector,
    val testTag: String
) {
    DASHBOARD("الرئيسية", Icons.Default.Dashboard, "nav_dashboard"),
    TRANSACTIONS("العمليات", Icons.Default.ReceiptLong, "nav_transactions"),
    SMS_REVIEW("رسائل البنوك", Icons.Default.Sms, "nav_sms_review"),
    ACCOUNTS("الحسابات", Icons.Default.AccountBalanceWallet, "nav_accounts"),
    BUDGETS_REPORTS("الميزانية والتقارير", Icons.Default.PieChart, "nav_budgets"),
    PLANNING_MORE("الالتزامات والتوفير", Icons.Default.Savings, "nav_planning")
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val db = SaidiQershDatabase.getInstance(applicationContext)
        val repository = SaidiQershRepository(db.dao())
        ObligationReminderWorker.schedulePeriodicReminders(applicationContext)

        setContent {
            val vm: SaidiQershViewModel = viewModel(factory = SaidiQershViewModel.Factory(repository))
            val darkPref by vm.isDarkModePreferred.collectAsStateWithLifecycle()
            val isDark = darkPref ?: isSystemInDarkTheme()

            SaidiQershTheme(darkTheme = isDark) {
                SaidiQershAppRoot(viewModel = vm)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SaidiQershAppRoot(
    viewModel: SaidiQershViewModel
) {
    val context = LocalContext.current
    val summary by viewModel.dashboardSummary.collectAsStateWithLifecycle()
    val allTransactions by viewModel.allConfirmedTransactions.collectAsStateWithLifecycle()
    val filteredTransactions by viewModel.filteredTransactions.collectAsStateWithLifecycle()
    val filterState by viewModel.filterState.collectAsStateWithLifecycle()
    val accounts by viewModel.accounts.collectAsStateWithLifecycle()
    val banks by viewModel.banks.collectAsStateWithLifecycle()
    val smsRules by viewModel.smsRules.collectAsStateWithLifecycle()
    val categories by viewModel.categories.collectAsStateWithLifecycle()
    val suggestedTransactions by viewModel.suggestedTransactions.collectAsStateWithLifecycle()
    val budgets by viewModel.budgets.collectAsStateWithLifecycle()
    val obligations by viewModel.obligations.collectAsStateWithLifecycle()
    val savingsGoals by viewModel.savingsGoals.collectAsStateWithLifecycle()
    val shoppingLists by viewModel.shoppingLists.collectAsStateWithLifecycle()
    val shoppingItems by viewModel.shoppingItems.collectAsStateWithLifecycle()
    val auditLogs by viewModel.auditLogs.collectAsStateWithLifecycle()
    val statusBanner by viewModel.statusBannerMessage.collectAsStateWithLifecycle()
    val voiceProposal by viewModel.voiceProposal.collectAsStateWithLifecycle()
    val pinCode by viewModel.pinCode.collectAsStateWithLifecycle()
    val isUnlocked by viewModel.isAppUnlocked.collectAsStateWithLifecycle()
    val isDarkModePreferred by viewModel.isDarkModePreferred.collectAsStateWithLifecycle()

    var currentTab by remember { mutableStateOf(MainDestination.DASHBOARD) }
    var showTransactionDialog by remember { mutableStateOf(false) }
    var dialogInitialTxType by remember { mutableStateOf(TransactionType.EXPENSE) }
    var editingTransaction by remember { mutableStateOf<TransactionEntity?>(null) }
    var showVoiceDialog by remember { mutableStateOf(false) }

    // الرجوع إلى الشاشة الرئيسية عند الضغط على زر الرجوع من أي تبويب فرعي
    BackHandler(enabled = currentTab != MainDestination.DASHBOARD) {
        currentTab = MainDestination.DASHBOARD
    }

    if (!isUnlocked && !pinCode.isNullOrBlank()) {
        PinUnlockScreen(
            onUnlockAttempt = { viewModel.tryUnlockWithPin(it) }
        )
        return
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val isExpandedScreen = maxWidth >= 700.dp

        Scaffold(
            modifier = Modifier.fillMaxSize(),
            topBar = {
                TopAppBar(
                    title = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            SaidiQershWalletLogo(size = 38.dp)
                            Column {
                                Text(
                                    text = "الصعيدي قرش",
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.testTag("app_title_header")
                                )
                                Text(
                                    text = "Saidi Qersh • إدارة المصروفات والميزانية",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    },
                    actions = {
                        IconButton(
                            onClick = { showVoiceDialog = true },
                            modifier = Modifier.testTag("top_voice_btn")
                        ) {
                            Icon(Icons.Default.Mic, contentDescription = "إملاء صوتي")
                        }
                        BadgedBox(
                            badge = {
                                val totalPending = summary.pendingSmsCount + summary.unclearSmsCount
                                if (totalPending > 0) {
                                    Badge { Text("$totalPending") }
                                }
                            }
                        ) {
                            IconButton(
                                onClick = { currentTab = MainDestination.SMS_REVIEW },
                                modifier = Modifier.testTag("top_sms_badge_btn")
                            ) {
                                Icon(Icons.Default.Sms, contentDescription = "رسائل البنوك للمراجعة")
                            }
                        }
                    }
                )
            },
            bottomBar = {
                if (!isExpandedScreen) {
                    NavigationBar {
                        MainDestination.entries.forEach { dest ->
                            NavigationBarItem(
                                selected = currentTab == dest,
                                onClick = { currentTab = dest },
                                icon = {
                                    if (dest == MainDestination.SMS_REVIEW && (summary.pendingSmsCount + summary.unclearSmsCount) > 0) {
                                        BadgedBox(
                                            badge = {
                                                Badge { Text("${summary.pendingSmsCount + summary.unclearSmsCount}") }
                                            }
                                        ) {
                                            Icon(dest.icon, contentDescription = dest.titleAr)
                                        }
                                    } else {
                                        Icon(dest.icon, contentDescription = dest.titleAr)
                                    }
                                },
                                label = {
                                    Text(
                                        text = dest.titleAr,
                                        style = MaterialTheme.typography.labelMedium,
                                        maxLines = 1
                                    )
                                },
                                modifier = Modifier.testTag(dest.testTag)
                            )
                        }
                    }
                }
            },
            floatingActionButton = {
                FloatingActionButton(
                    onClick = {
                        editingTransaction = null
                        dialogInitialTxType = TransactionType.EXPENSE
                        showTransactionDialog = true
                    },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.testTag("main_fab_add_tx")
                ) {
                    Icon(Icons.Default.Add, contentDescription = "إضافة عملية مالية")
                }
            }
        ) { innerPadding ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                if (isExpandedScreen) {
                    NavigationRail {
                        MainDestination.entries.forEach { dest ->
                            NavigationRailItem(
                                selected = currentTab == dest,
                                onClick = { currentTab = dest },
                                icon = { Icon(dest.icon, contentDescription = dest.titleAr) },
                                label = { Text(dest.titleAr) },
                                modifier = Modifier.testTag(dest.testTag)
                            )
                        }
                    }
                }

                Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    AnimatedVisibility(visible = statusBanner != null) {
                        statusBanner?.let { msg ->
                            Surface(
                                color = MaterialTheme.colorScheme.secondaryContainer,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 6.dp),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 8.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = msg,
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                                        modifier = Modifier.weight(1f).testTag("status_banner_text")
                                    )
                                    IconButton(
                                        onClick = { viewModel.dismissBanner() },
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Icon(Icons.Default.Close, contentDescription = "إغلاق التنبيه", modifier = Modifier.size(16.dp))
                                    }
                                }
                            }
                        }
                    }

                    when (currentTab) {
                        MainDestination.DASHBOARD -> {
                            DashboardScreen(
                                summary = summary,
                                recentTransactions = allTransactions,
                                accounts = accounts,
                                categories = categories,
                                onQuickAddClick = { txType ->
                                    editingTransaction = null
                                    dialogInitialTxType = txType
                                    showTransactionDialog = true
                                },
                                onVoiceDictationClick = { showVoiceDialog = true },
                                onOpenSmsReviewClick = { currentTab = MainDestination.SMS_REVIEW },
                                onEditTransaction = { tx ->
                                    editingTransaction = tx
                                    dialogInitialTxType = tx.type
                                    showTransactionDialog = true
                                },
                                onDeleteTransactionConfirmed = { viewModel.deleteTransactionConfirmed(it) }
                            )
                        }
                        MainDestination.TRANSACTIONS -> {
                            TransactionsManagementScreen(
                                transactions = filteredTransactions,
                                filterState = filterState,
                                accounts = accounts,
                                categories = categories,
                                onUpdateFilter = { viewModel.updateFilter(it) },
                                onClearFilters = { viewModel.clearFilters() },
                                onAddTransactionClick = { txType ->
                                    editingTransaction = null
                                    dialogInitialTxType = txType
                                    showTransactionDialog = true
                                },
                                onEditTransaction = { tx ->
                                    editingTransaction = tx
                                    dialogInitialTxType = tx.type
                                    showTransactionDialog = true
                                },
                                onDeleteTransactionConfirmed = { viewModel.deleteTransactionConfirmed(it) },
                                onSaveCategory = { id, name, type, hex ->
                                    viewModel.saveCategory(id, name, type, hex)
                                }
                            )
                        }
                        MainDestination.SMS_REVIEW -> {
                            SmsReviewScreen(
                                suggestedTransactions = suggestedTransactions,
                                banks = banks,
                                smsRules = smsRules,
                                accounts = accounts,
                                categories = categories,
                                onConfirmSuggested = { viewModel.confirmSuggested(it) },
                                onEditSuggestedBeforeConfirm = { item, amt, merch, catId, accId, sType ->
                                    viewModel.editSuggestedBeforeConfirm(item, amt, merch, catId, accId, sType)
                                },
                                onIgnoreSuggested = { viewModel.ignoreSuggested(it) },
                                onMarkDuplicate = { viewModel.markSuggestedDuplicate(it) },
                                onToggleBankSms = { bank, en -> viewModel.toggleBankSms(bank, en) },
                                onAddCustomBank = { nAr, code, cur, senders ->
                                    viewModel.addCustomBank(nAr, code, cur, senders)
                                },
                                onAddCustomRule = { bId, rName, pat, sType ->
                                    viewModel.addCustomSmsRule(bId, rName, pat, sType)
                                },
                                onIngestManualOrTestSms = { sender, body, sandbox ->
                                    viewModel.ingestSmsMessage(sender, body, sandbox)
                                },
                                onLoadSandboxSamples = { viewModel.loadRealisticBankSamplesIntoSandbox() },
                                onClearSandbox = { viewModel.clearSandboxSuggestions() },
                                onScanDeviceInbox = { viewModel.scanPhoneSmsInbox(context) }
                            )
                        }
                        MainDestination.ACCOUNTS -> {
                            AccountsManagementScreen(
                                accounts = accounts,
                                banks = banks,
                                onAddOrUpdateAccount = { id, name, type, bId, cur, openBal, smsEn ->
                                    viewModel.addOrUpdateAccount(id, name, type, bId, cur, openBal, smsEn)
                                },
                                onReconcileAccount = { accId, newBal, reason ->
                                    viewModel.reconcileAccount(accId, newBal, reason)
                                },
                                onToggleAccountSms = { acc, en -> viewModel.toggleAccountSms(acc, en) }
                            )
                        }
                        MainDestination.BUDGETS_REPORTS -> {
                            BudgetsAndReportsScreen(
                                summary = summary,
                                budgets = budgets,
                                confirmedTransactions = allTransactions,
                                accounts = accounts,
                                categories = categories,
                                onSaveBudget = { catId, limit, cur ->
                                    viewModel.saveMonthlyBudget(catId, limit, cur)
                                },
                                onGenerateCsv = { viewModel.generateCsvExport() },
                                onGenerateBackup = { pass -> viewModel.generateBackupPayload(pass) },
                                onRestoreBackup = { payload, pass ->
                                    viewModel.restoreFromBackupPayload(payload, pass)
                                }
                            )
                        }
                        MainDestination.PLANNING_MORE -> {
                            PlanningAndMoreScreen(
                                obligations = obligations,
                                savingsGoals = savingsGoals,
                                shoppingLists = shoppingLists,
                                shoppingItems = shoppingItems,
                                auditLogs = auditLogs,
                                accounts = accounts,
                                categories = categories,
                                currentPin = pinCode,
                                isDarkModePreferred = isDarkModePreferred,
                                onAddObligation = { title, kind, total, inst, cur, days, rec, rosca, accId, catId ->
                                    viewModel.addObligation(title, kind, total, inst, cur, days, rec, rosca, accId, catId)
                                },
                                onPayObligation = { obl, key, amt, accId, alreadyExp ->
                                    viewModel.payObligationInstallment(obl, key, amt, accId, alreadyExp)
                                },
                                onAddSavingsGoal = { title, target, cur, days ->
                                    viewModel.addSavingsGoal(title, target, cur, days)
                                },
                                onRecordSavingsMovement = { gId, isDep, amt, note ->
                                    viewModel.recordSavingsMovement(gId, isDep, amt, note)
                                },
                                onCreateShoppingList = { title, cur ->
                                    viewModel.createShoppingList(title, cur)
                                },
                                onAddShoppingItem = { lId, name, qty, price ->
                                    viewModel.addShoppingItem(lId, name, qty, price)
                                },
                                onToggleShoppingItem = { viewModel.toggleShoppingItem(it) },
                                onConvertShoppingListToExpenseOnce = { lId, accId, catId ->
                                    viewModel.convertShoppingListToExpenseOnce(lId, accId, catId)
                                },
                                onSetPin = { viewModel.setPinProtection(it) },
                                onLockNow = { viewModel.lockAppNow() },
                                onToggleDarkMode = { viewModel.toggleDarkMode(it) },
                                onWipeAllDataConfirmed = { viewModel.wipeAllDataConfirmed() }
                            )
                        }
                    }
                }
            }
        }
    }

    if (showTransactionDialog) {
        TransactionFormDialog(
            initialType = dialogInitialTxType,
            editingTransaction = editingTransaction,
            accounts = accounts,
            categories = categories,
            onDismiss = {
                showTransactionDialog = false
                editingTransaction = null
            },
            onSave = { id, type, amt, cur, desc, notes, accId, toAccId, catId, merch ->
                viewModel.saveTransaction(
                    existingId = id,
                    type = type,
                    amountText = amt,
                    currency = cur,
                    description = desc,
                    notes = notes,
                    accountId = accId,
                    toAccountId = toAccId,
                    categoryId = catId,
                    merchantName = merch
                )
            }
        )
    }

    if (showVoiceDialog) {
        VoiceExpenseDialog(
            proposal = voiceProposal,
            accounts = accounts,
            categories = categories,
            onAnalyzePhrase = { viewModel.analyzeSpokenExpense(it) },
            onConfirmProposal = { prop, accId, amt, desc, catId ->
                viewModel.confirmVoiceProposal(prop, accId, amt, desc, catId)
            },
            onDismiss = {
                viewModel.clearVoiceProposal()
                showVoiceDialog = false
            }
        )
    }
}

@Composable
private fun PinUnlockScreen(
    onUnlockAttempt: (String) -> Boolean
) {
    var entered by remember { mutableStateOf("") }
    var hasError by remember { mutableStateOf(false) }

    Scaffold { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            SaidiQershWalletLogo(size = 72.dp)
            Spacer(Modifier.height(16.dp))
            Text(
                text = "الصعيدي قرش محمي برمز سري",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = entered,
                onValueChange = {
                    entered = it
                    hasError = false
                },
                label = { Text("أدخل رمز PIN") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("pin_unlock_input")
            )
            if (hasError) {
                Text(
                    text = "الرمز السري غير صحيح، حاول مرة أخرى",
                    color = ExpenseRed,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = {
                    if (!onUnlockAttempt(entered)) {
                        hasError = true
                    }
                },
                modifier = Modifier.fillMaxWidth().testTag("pin_unlock_btn")
            ) {
                Text("فتح التطبيق")
            }
        }
    }
}
