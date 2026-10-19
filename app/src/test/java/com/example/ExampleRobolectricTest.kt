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
 * - التعامل مع قاعدة بيانات فارغة.
 * - إضافة وتعديل وحذف المصروفات وتحديث الأرصدة.
 * - التحويل بين الحسابات ومعالجة الاستردادات.
 * - منع التكرار في رسائل البنوك.
 * - عدم ظهور العمليات المعلقة (Pending) ضمن إجمالي المصروفات المؤكدة.
 * - تعديل العملية المقترحة قبل التأكيد، وتأكيدها مرة واحدة فقط في معاملة قاعدة بيانات واحدة.
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
    fun `empty database handles queries gracefully and reads Arabic app_name`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertEquals("الصعيدي قرش", context.getString(R.string.app_name))

        val initialTxs = repository.confirmedTransactionsFlow.first()
        assertTrue(initialTxs.isEmpty())
        assertEquals(0L, SaidiQershRepository.computeNetConfirmedExpensesMinor(initialTxs))
        assertEquals(0L, dao.observeTotalNetBalanceMinor().first())

        // استعادة نسخة احتياطية تالفة يعيد خطأ محكومًا دون انهيار
        val badRestore = repository.restoreLocalBackupJson("{invalid_json}")
        assertTrue(badRestore.isFailure)
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

        // 1. إضافة مصروف بقيمة 200 ريال
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

        // 2. تعديل المصروف إلى 350 ريال
        val savedTx = dao.getTransactionById(txId)!!
        repository.updateConfirmedTransaction(savedTx.copy(amountMinor = 350_00L, description = "مقاضي الشهر"))
        assertEquals(4650_00L, dao.getAccountById(bankAccId)!!.currentBalanceMinor)

        // 3. حذف المصروف يستعيد الرصيد إلى 5000 ريال
        repository.deleteConfirmedTransaction(txId)
        assertEquals(5000_00L, dao.getAccountById(bankAccId)!!.currentBalanceMinor)
        assertNull(dao.getTransactionById(txId))

        val logs = repository.auditLogsFlow.first()
        assertTrue(logs.any { it.eventType == "TRANSACTION_EDIT" })
        assertTrue(logs.any { it.eventType == "TRANSACTION_DELETE" })
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

        // تحويل 400 ريال من البنك إلى الكاش
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

        // تسجيل مصروف 300 ريال ثم استرداد 100 ريال على حساب البنك
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

        // الرصيد النهائي للبنك: 2600 - 300 + 100 = 2400 ريال
        assertEquals(2400_00L, dao.getAccountById(bankId)!!.currentBalanceMinor)

        val allTxs = repository.confirmedTransactionsFlow.first()
        // صافي المصروفات المؤكدة = 300 - 100 = 200 ريال (لا يشمل التحويل)
        assertEquals(200_00L, SaidiQershRepository.computeNetConfirmedExpensesMinor(allTxs))
        // إجمالي الدخل = 0 (الاسترداد لا يُحتسب دخلًا عاديًا)
        assertEquals(0L, SaidiQershRepository.computeConfirmedIncomeMinor(allTxs))
    }

    @Test
    fun `pending SMS does not affect confirmed expenses, prevents duplicates, allows edit before confirm, and confirms once only`() = runBlocking {
        repository.ensureSeeded()
        val acc = repository.accountsFlow.first().first()
        val initialBalance = acc.currentBalanceMinor

        val smsBody = """
            شراء عبر نقاط البيع
            بمبلغ: SAR 120.00
            لدى: محطة الدريس
            مرجع: REF990011
        """.trimIndent()

        // 1. استيراد الرسالة البنكية -> تنتج عملية مقترحة بحالة PENDING
        val suggestedId = repository.ingestBankSms("AlRajhiBank", smsBody)
        assertNotNull(suggestedId)
        assertTrue(suggestedId!! > 0L)

        // 2. منع التكرار: إرسال نفس الرسالة مرة أخرى يُرفض
        val duplicateAttempt = repository.ingestBankSms("AlRajhiBank", smsBody)
        assertNull(duplicateAttempt)

        // 3. عدم ظهور العملية المعلقة ضمن إجمالي المصروفات المؤكدة أو تغيير رصيد الحساب
        val confirmedBefore = repository.confirmedTransactionsFlow.first()
        assertTrue(confirmedBefore.isEmpty())
        assertEquals(0L, SaidiQershRepository.computeNetConfirmedExpensesMinor(confirmedBefore))
        assertEquals(initialBalance, dao.getAccountById(acc.id)!!.currentBalanceMinor)

        // 4. تعديل العملية المقترحة قبل التأكيد (تعديل المبلغ إلى 150 ريال والتاجر إلى محطة وقود ساسكو)
        val pendingEntity = dao.getSuggestedById(suggestedId)!!
        repository.updateSuggestedBeforeConfirm(
            suggested = pendingEntity,
            newAmountMinor = 150_00L,
            newMerchant = "محطة وقود ساسكو",
            newCategoryId = pendingEntity.suggestedCategoryId,
            newCategoryName = pendingEntity.suggestedCategoryName,
            newAccountId = acc.id,
            newType = SmsSuggestedType.CARD_PURCHASE
        )

        // 5. تأكيد العملية مرة واحدة فقط
        val confirmedTxId1 = repository.confirmSuggestedTransaction(suggestedId)
        assertNotNull(confirmedTxId1)

        // محاولة تأكيد نفس العملية المقترحة مرة ثانية -> تُرفض لمنع المضاعفة!
        val confirmedTxId2 = repository.confirmSuggestedTransaction(suggestedId)
        assertNull(confirmedTxId2)

        // 6. التحقق من تحديث الرصيد والمصروفات المؤكدة مرة واحدة فقط بالقيمة المعدلة (150 ريال)
        val confirmedAfter = repository.confirmedTransactionsFlow.first()
        assertEquals(1, confirmedAfter.size)
        assertEquals(150_00L, SaidiQershRepository.computeNetConfirmedExpensesMinor(confirmedAfter))
        assertEquals(initialBalance - 150_00L, dao.getAccountById(acc.id)!!.currentBalanceMinor)
    }
}
