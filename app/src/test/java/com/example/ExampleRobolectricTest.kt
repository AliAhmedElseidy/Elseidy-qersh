package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.*
import com.example.data.repository.SaidiQershRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * اختبارات Robolectric التكاملية لقاعدة بيانات Room ومستودع البيانات (SaidiQershRepository):
 * - التحقق من تأسيس الـ 16 تصنيفاً وجهات الرسائل المتنوعة (Alinma, AlRajhi, Enjaz, Urpay, STC Pay, VF-Cash, Fawry).
 * - التحقق من استبعاد رسائل التفعيل وOTP تلقائياً عند الاستقبال.
 * - التحقق من قراءة رسائل Samsung Wallet وربطها التلقائي بحساب الإنماء (*2000) وبطاقة (*5650).
 * - التحقق من إعدادات التحكم في جهات الرسائل (إيقاف قراءة جهة معينة + الاعتماد والتسجيل التلقائي الفوري Auto-Approve).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExampleRobolectricTest {

    private lateinit var db: SaidiQershDatabase
    private lateinit var repository: SaidiQershRepository

    @Before
    fun setup() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, SaidiQershDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = SaidiQershRepository(db.dao())
        repository.ensureSeeded()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun repository_seedsAll16CategoriesAndIndependentSenderEntities() = runBlocking {
        val categories = db.dao().getAllCategories()
        val expenseNames = categories.filter { it.type == CategoryType.EXPENSE }.map { it.nameAr }

        val required16 = listOf(
            "بقالة", "أكل بره", "مواصلات", "تسوق",
            "فواتير", "اشتراكات", "سفر", "صحة",
            "عناية شخصية", "إيجار", "تعليم", "بنزين",
            "أقساط", "جمعيات", "أولاد", "أخرى"
        )
        for (name in required16) {
            assertTrue("Missing required category: $name", expenseNames.contains(name))
        }

        val banks = db.dao().getAllBanks()
        val bankCodes = banks.map { it.code }
        assertTrue(bankCodes.contains("ALINMA"))
        assertTrue(bankCodes.contains("ALRAJHI"))
        assertTrue(bankCodes.contains("ENJAZ"))
        assertTrue(bankCodes.contains("URPAY"))
        assertTrue(bankCodes.contains("STCPAY"))
        assertTrue(bankCodes.contains("VFCASH"))

        val accounts = db.dao().getAllAccounts()
        val alinmaAcc = accounts.firstOrNull { it.accountLast4 == "2000" && it.cardLast4 == "5650" }
        assertNotNull("Alinma account with *2000 and *5650 should be seeded", alinmaAcc)
    }

    @Test
    fun samsungWalletSms_ingestsAndMatchesAlinmaAccount_whileBeneficiaryOtpSmsIsIgnored() = runBlocking {
        // 1. رسالة غير مالية (تفعيل مستفيد / OTP) -> يجب استبعادها تلقائياً وإرجاع null
        val otpId = repository.ingestBankSms(
            senderAddress = "Alinma",
            rawBody = "تم تفعيل مستفيد بنجاح، رمز التحقق OTP هو 9182"
        )
        assertNull("Non-financial beneficiary/OTP SMS must be auto-ignored", otpId)

        // 2. رسالة Samsung Wallet ومصرف الإنماء -> يجب قبولها واستخراج 10 ر.س والتاجر dani est وربطها بحساب الإنماء (*2000)
        val validId = repository.ingestBankSms(
            senderAddress = "Alinma",
            rawBody = "شراء عبر Samsung Wallet مبلغ: ريال سعودى 10 بطاقة مدى: *5650 حساب: *2000 من: dani est"
        )
        assertNotNull("Valid Samsung Wallet SMS should be ingested", validId)

        val suggested = db.dao().getSuggestedById(validId!!)
        assertNotNull(suggested)
        assertEquals(10_00L, suggested!!.extractedAmountMinor)
        assertEquals("dani est", suggested.merchantName)
        assertEquals("2000", suggested.extractedAccountLast4)
        assertEquals("5650", suggested.extractedCardLast4)

        val alinmaAccount = db.dao().getAllAccounts().first { it.accountLast4 == "2000" }
        assertEquals(alinmaAccount.id, suggested.suggestedAccountId)

        // 3. عند الضغط على "أضف" (تأكيد العملية المقترحة)، يتم خصم 10 ر.س من رصيد حساب الإنماء مباشرة
        val balanceBefore = alinmaAccount.currentBalanceMinor
        val confirmedTxId = repository.confirmSuggestedTransaction(validId)
        assertNotNull(confirmedTxId)

        val balanceAfter = db.dao().getAccountById(alinmaAccount.id)!!.currentBalanceMinor
        assertEquals(balanceBefore - 10_00L, balanceAfter)
    }

    @Test
    fun senderRulesAndControl_toggle1IgnoresDisabledSender_andToggle2AutoApprovesImmediately() = runBlocking {
        // 1. التحقق من أن رسالة Enjaz تُنسب إلى "إنجاز (Enjaz)" وليس الراجحي أو الإنماء
        val enjazId = repository.ingestBankSms(
            senderAddress = "Enjaz",
            rawBody = "حوالة صادرة عبر إنجاز بمبلغ: 300.00 ريال سعودي المستفيد: أحمد علي مرجع: ENJ100"
        )
        assertNotNull(enjazId)
        val enjazSuggested = db.dao().getSuggestedById(enjazId!!)!!
        assertEquals("إنجاز (Enjaz)", enjazSuggested.bankName)
        assertEquals("Enjaz", enjazSuggested.senderAddress)
        assertEquals(ReviewStatus.PENDING, enjazSuggested.status)

        // 2. مفتاح التفعيل 1 (Toggle 1): تعطيل قراءة رسائل Enjaz -> يتم تجاهل أي رسالة جديدة منها تماماً
        val enjazBank = db.dao().getAllBanks().first { it.code == "ENJAZ" }
        repository.toggleBankSmsEnabled(enjazBank, false)
        val ignoredWhenDisabledId = repository.ingestBankSms(
            senderAddress = "Enjaz",
            rawBody = "حوالة صادرة عبر إنجاز بمبلغ: 500.00 ريال سعودي المستفيد: خالد مرجع: ENJ200"
        )
        assertNull("Disabled sender SMS must be completely ignored", ignoredWhenDisabledId)

        // 3. مفتاح التفعيل 2 (Toggle 2 - Auto-Approve): تفعيل الاعتماد التلقائي لمصرف الإنماء
        val alinmaBank = db.dao().getAllBanks().first { it.code == "ALINMA" }
        repository.toggleBankAutoApprove(alinmaBank, true)

        val alinmaAccBefore = db.dao().getAllAccounts().first { it.accountLast4 == "2000" }
        val txCountBefore = db.dao().getAllConfirmedTransactions().size

        val autoApprovedSuggestedId = repository.ingestBankSms(
            senderAddress = "Alinma",
            rawBody = "شراء عبر نقاط البيع مبلغ: ريال سعودي 75 بطاقة مدى: *5650 حساب: *2000 من: بقالة الوفاء مرجع: AUTO75",
            isTestSandbox = false
        )
        assertNotNull(autoApprovedSuggestedId)

        // يجب أن تتحول الحالة فوراً إلى CONFIRMED ويُخصم المبلغ 75 ر.س من حساب الإنماء تلقائياً دون انتظار مراجعة يدوية
        val autoSuggested = db.dao().getSuggestedById(autoApprovedSuggestedId!!)!!
        assertEquals(ReviewStatus.CONFIRMED, autoSuggested.status)

        val alinmaAccAfter = db.dao().getAccountById(alinmaAccBefore.id)!!
        assertEquals(alinmaAccBefore.currentBalanceMinor - 75_00L, alinmaAccAfter.currentBalanceMinor)
        assertEquals(txCountBefore + 1, db.dao().getAllConfirmedTransactions().size)
    }
}
