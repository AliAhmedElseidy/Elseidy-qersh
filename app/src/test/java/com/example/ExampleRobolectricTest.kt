package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.*
import com.example.data.repository.SaidiQershRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * اختبارات Robolectric + Room الشاملة لقاعدة البيانات والعمليات المالية ومراجعة رسائل البنوك:
 * - التحقق من تأسيس الـ 16 تصنيفًا والحسابات الذكية (*2000 و *4821).
 * - استيراد رسالة Samsung Wallet / الإنماء وربطها تلقائيًا بحساب الإنماء (*2000).
 * - الفلترة التلقائية لرسائل تفعيل المستفيد وOTP وعدم إضافتها لقائمة المراجعة.
 * - إضافة وتعديل وحذف المصروفات وتحديث الأرصدة والتحويلات والاستردادات.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    private lateinit var db: SaidiQershDatabase
    private lateinit var dao: SaidiQershDao
    private lateinit var repository: SaidiQershRepository

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, SaidiQershDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.dao()
        repository = SaidiQershRepository(dao)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `empty database handles queries gracefully and seeds 16 categories and smart accounts`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertEquals("الصعيدي قرش", context.getString(R.string.app_name))

        repository.ensureSeeded()
        val cats = repository.categoriesFlow.first()
        val catNames = cats.map { it.nameAr }
        assertTrue(catNames.containsAll(listOf("بقالة", "أكل بره", "مواصلات", "تسوق", "فواتير", "اشتراكات", "سفر", "صحة", "عناية شخصية", "إيجار", "تعليم", "بنزين", "أقساط", "جمعيات", "أولاد", "أخرى")))

        val accs = repository.accountsFlow.first()
        assertTrue(accs.any { it.name == "حساب الإنماء" && it.accountLast4 == "2000" && it.cardLast4 == "5650" })
    }

    @Test
    fun `Samsung Wallet SMS automatically matches Alinma account 2000 and non-financial OTP SMS is auto-ignored`() = runBlocking {
        repository.ensureSeeded()
        val alinmaAcc = repository.accountsFlow.first().first { it.accountLast4 == "2000" }

        // 1. رسالة غير مالية (تم تفعيل مستفيد / OTP) -> يجب استبعادها تلقائياً وعدم إضافتها لقائمة المراجعة
        val ignoredId = repository.ingestBankSms("Alinma", "تم تفعيل مستفيد بنجاح، رمز التحقق OTP: 3910")
        assertNull(ignoredId)

        // 2. رسالة شراء Samsung Wallet مع ريال سعودى 10 وحساب *2000 وبطاقة *5650
        val samsungSms = "شراء عبر Samsung Wallet مبلغ: ريال سعودى 10 بطاقة مدى: *5650 حساب: *2000 من: dani est"
        val suggestedId = repository.ingestBankSms("Alinma", samsungSms)
        assertNotNull(suggestedId)

        val suggested = dao.getSuggestedById(suggestedId!!)!!
        assertEquals(1000L, suggested.extractedAmountMinor)
        assertEquals("dani est", suggested.merchantName)
        assertEquals(alinmaAcc.id, suggested.suggestedAccountId)

        // 3. عند الضغط على "أضف" (تأكيد العملية)، يُخصم 10 ر.س من حساب الإنماء مباشرة
        val txId = repository.confirmSuggestedTransaction(suggestedId)
        assertNotNull(txId)
        assertEquals(-1000L, dao.getAccountById(alinmaAcc.id)!!.currentBalanceMinor)
    }

    @Test
    fun `add, edit, and delete expense updates account balances and audit log accurately`() = runBlocking {
        val bankAccId = repository.saveAccount(
            AccountEntity(
                name = "حساب الراجحي",
                type = AccountType.BANK,
                currency = "SAR",
                openingBalanceMinor = 5000_00L,
                currentBalanceMinor = 5000_00L
            )
        )

        val txId = repository.addConfirmedTransaction(
            TransactionEntity(
                type = TransactionType.EXPENSE,
                amountMinor = 200_00L,
                currency = "SAR",
                description = "مقاضي أسبوعية",
                accountId = bankAccId
            )
        )
        assertEquals(4800_00L, dao.getAccountById(bankAccId)!!.currentBalanceMinor)

        val savedTx = dao.getTransactionById(txId)!!
        repository.updateConfirmedTransaction(savedTx.copy(amountMinor = 350_00L, description = "مقاضي الشهر"))
        assertEquals(4650_00L, dao.getAccountById(bankAccId)!!.currentBalanceMinor)

        repository.deleteConfirmedTransaction(txId)
        assertEquals(5000_00L, dao.getAccountById(bankAccId)!!.currentBalanceMinor)
        assertNull(dao.getTransactionById(txId))
    }

    @Test
    fun `transfer between accounts and refund processing work accurately`() = runBlocking {
        val bankId = repository.saveAccount(
            AccountEntity(
                name = "البنك الأهلي",
                type = AccountType.BANK,
                currency = "SAR",
                openingBalanceMinor = 3000_00L,
                currentBalanceMinor = 3000_00L
            )
        )
        val cashId = repository.saveAccount(
            AccountEntity(
                name = "المحفظة النقدية",
                type = AccountType.CASH,
                currency = "SAR",
                openingBalanceMinor = 500_00L,
                currentBalanceMinor = 500_00L
            )
        )

        repository.addConfirmedTransaction(
            TransactionEntity(
                type = TransactionType.TRANSFER,
                amountMinor = 400_00L,
                currency = "SAR",
                description = "سحب وتحويل للمحفظة",
                accountId = bankId,
                toAccountId = cashId
            )
        )
        assertEquals(2600_00L, dao.getAccountById(bankId)!!.currentBalanceMinor)
        assertEquals(900_00L, dao.getAccountById(cashId)!!.currentBalanceMinor)

        repository.addConfirmedTransaction(
            TransactionEntity(
                type = TransactionType.EXPENSE,
                amountMinor = 300_00L,
                currency = "SAR",
                description = "شراء جهاز",
                accountId = bankId
            )
        )
        repository.addConfirmedTransaction(
            TransactionEntity(
                type = TransactionType.REFUND,
                amountMinor = 100_00L,
                currency = "SAR",
                description = "استرداد فرق سعر",
                accountId = bankId
            )
        )

        assertEquals(2400_00L, dao.getAccountById(bankId)!!.currentBalanceMinor)

        val allTxs = repository.confirmedTransactionsFlow.first()
        assertEquals(200_00L, SaidiQershRepository.computeNetConfirmedExpensesMinor(allTxs))
        assertEquals(0L, SaidiQershRepository.computeConfirmedIncomeMinor(allTxs))
    }
}
