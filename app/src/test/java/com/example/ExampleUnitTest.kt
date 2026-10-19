package com.example

import com.example.data.local.AccountEntity
import com.example.data.local.AccountType
import com.example.data.local.ReviewStatus
import com.example.data.local.SmsSuggestedType
import com.example.data.local.TransactionEntity
import com.example.data.local.TransactionType
import com.example.data.repository.SaidiQershRepository
import com.example.domain.ArabicVoiceExpenseParser
import com.example.domain.BankSmsParserEngine
import com.example.domain.MoneyUtils
import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal

/**
 * اختبارات الوحدات الفعلية للحسابات المالية الدقيقة،
 * محلل رسائل البنوك المحدث (Samsung Wallet / مصرف الإنماء / الراجحي / SNB)،
 * الفلترة التلقائية للرسائل غير المالية (تفعيل مستفيد / OTP / تسجيل دخول)،
 * والربط الذكي للحسابات والبطاقات (*2000 / *5650).
 */
class ExampleUnitTest {

    @Test
    fun `money utils converts Arabic and English decimals accurately without floating point drift`() {
        val minor1 = MoneyUtils.parseToMinorUnits("١٬٢٥٠٫٧٥")
        assertEquals(125075L, minor1)
        assertEquals(BigDecimal("1250.75"), MoneyUtils.fromMinorUnits(minor1!!))

        val minor2 = MoneyUtils.parseToMinorUnits("35.50")
        assertEquals(3550L, minor2)
        assertEquals("35.50 ر.س", MoneyUtils.formatMinor(minor2!!, "SAR"))
        assertEquals("10.00 ر.س", MoneyUtils.formatMinorTwoDecimals(1000L, "SAR"))
    }

    @Test
    fun `parse Samsung Wallet and Alinma SMS with ريال سعودى before integer amount and match account 2000 and card 5650`() {
        val accounts = listOf(
            AccountEntity(
                id = 10L,
                name = "حساب الإنماء",
                type = AccountType.BANK,
                bankName = "مصرف الإنماء",
                currency = "SAR",
                accountLast4 = "2000",
                cardLast4 = "5650"
            ),
            AccountEntity(
                id = 20L,
                name = "حساب الراجحي",
                type = AccountType.BANK,
                bankName = "مصرف الراجحي",
                currency = "SAR",
                accountLast4 = "4821",
                cardLast4 = "4821"
            )
        )

        val samsungWalletSms = "شراء عبر Samsung Wallet مبلغ: ريال سعودى 10 بطاقة مدى: *5650 حساب: *2000 من: dani est"
        val parsed = BankSmsParserEngine.parseSms(
            senderAddress = "Alinma",
            rawBody = samsungWalletSms,
            accounts = accounts
        )

        assertEquals(SmsSuggestedType.CARD_PURCHASE, parsed.suggestedType)
        assertEquals(1000L, parsed.extractedAmountMinor) // 10.00 ر.س
        assertEquals("SAR", parsed.currency)
        assertEquals("dani est", parsed.merchantName)
        assertEquals("2000", parsed.extractedAccountLast4)
        assertEquals("5650", parsed.extractedCardLast4)
        assertEquals(10L, parsed.matchedAccountId) // ربط ذكي تلقائي مع حساب الإنماء
        assertEquals(ReviewStatus.PENDING, parsed.reviewStatus)
    }

    @Test
    fun `auto ignore non-financial SMS keywords like beneficiary activation, OTP, and login`() {
        val nonFinancialMessages = listOf(
            "مصرف الإنماء: تم تفعيل مستفيد بنجاح في حسابكم",
            "تم إضافة مستفيد جديد، يرجى عدم مشاركة الكود",
            "رمز التحقق الخاص بك هو 8829",
            "كود التفعيل لخدمة أبشر هو 1920",
            "كلمة المرور المؤقتة لعملية الشراء هي 4412",
            "Your OTP for online banking is 7731",
            "لا تشارك الرقم السري للبطاقة مع أي شخص",
            "إشعار تسجيل دخول إلى تطبيق البنك"
        )

        for (msg in nonFinancialMessages) {
            val result = BankSmsParserEngine.parseSms("Alinma", msg)
            assertEquals("Failed to auto-ignore: $msg", SmsSuggestedType.BALANCE_NOTICE_ONLY, result.suggestedType)
            assertEquals("Failed to set IGNORED status: $msg", ReviewStatus.IGNORED, result.reviewStatus)
        }
    }

    @Test
    fun `parse Arabic bank SMS for AlRajhi, SNB, Alinma, and Refund`() {
        val rajhiSms = """
            شراء عبر نقاط البيع
            بطاقة: 4821;مدى-أثير
            بمبلغ: SAR 145.50
            لدى: أسواق التميمي
            في: 2026/10/18 19:20
            مرجع: RJ8849201
        """.trimIndent()
        val parsedRajhi = BankSmsParserEngine.parseSms("AlRajhiBank", rajhiSms)
        assertEquals(SmsSuggestedType.CARD_PURCHASE, parsedRajhi.suggestedType)
        assertEquals(14550L, parsedRajhi.extractedAmountMinor)
        assertEquals("SAR", parsedRajhi.currency)
        assertEquals("أسواق التميمي", parsedRajhi.merchantName)
        assertEquals("RJ8849201", parsedRajhi.referenceNumber)
        assertEquals(ReviewStatus.PENDING, parsedRajhi.reviewStatus)

        val refundSms = """
            إشعار استرداد مبلغ
            بمبلغ: SAR 45.00
            لدى: صيدلية النهدي
            مرجع: RJ9910234
        """.trimIndent()
        val parsedRefund = BankSmsParserEngine.parseSms("AlRajhiBank", refundSms)
        assertEquals(SmsSuggestedType.REFUND, parsedRefund.suggestedType)
        assertEquals(4500L, parsedRefund.extractedAmountMinor)
        assertEquals("صيدلية النهدي", parsedRefund.merchantName)
    }

    @Test
    fun `parse English bank SMS for POS purchase and ATM withdrawal`() {
        val posEnglish = "PoS Purchase of SAR 210.25 at STARBUCKS CAFE on 2026-10-18 Ref: EN998811"
        val parsedPos = BankSmsParserEngine.parseSms("SNB", posEnglish)
        assertEquals(SmsSuggestedType.CARD_PURCHASE, parsedPos.suggestedType)
        assertEquals(21025L, parsedPos.extractedAmountMinor)
        assertEquals("SAR", parsedPos.currency)
        assertEquals("EN998811", parsedPos.referenceNumber)
        assertEquals(ReviewStatus.PENDING, parsedPos.reviewStatus)

        val atmEnglish = "ATM Cash Withdrawal Amount: EGP 1,500.00 Ref: ATM7712"
        val parsedAtm = BankSmsParserEngine.parseSms("CIB", atmEnglish)
        assertEquals(SmsSuggestedType.ATM_WITHDRAWAL, parsedAtm.suggestedType)
        assertEquals(150000L, parsedAtm.extractedAmountMinor)
        assertEquals("EGP", parsedAtm.currency)
    }

    @Test
    fun `refund reduces net expenses without inflating regular income and transfer is separated`() {
        val list = listOf(
            TransactionEntity(id = 1, type = TransactionType.EXPENSE, amountMinor = 500_00L, description = "مشتريات", accountId = 1),
            TransactionEntity(id = 2, type = TransactionType.REFUND, amountMinor = 120_00L, description = "استرداد", accountId = 1),
            TransactionEntity(id = 3, type = TransactionType.INCOME, amountMinor = 9000_00L, description = "راتب", accountId = 1),
            TransactionEntity(id = 4, type = TransactionType.TRANSFER, amountMinor = 1000_00L, description = "تحويل للكاش", accountId = 1, toAccountId = 2)
        )
        assertEquals(380_00L, SaidiQershRepository.computeNetConfirmedExpensesMinor(list))
        assertEquals(9000_00L, SaidiQershRepository.computeConfirmedIncomeMinor(list))
    }

    @Test
    fun `parse Arabic spoken voice expense phrase accurately`() {
        val spoken = "دفعت خمسة وثلاثين ريال بنزين"
        val proposal = ArabicVoiceExpenseParser.parseSpokenPhrase(spoken, defaultCurrency = "SAR")
        assertTrue(proposal.isReadyToConfirm)
        assertEquals(35_00L, proposal.amountMinor)
        assertEquals("SAR", proposal.currency)
        assertEquals("بنزين", proposal.description)
        assertEquals("بنزين", proposal.suggestedCategoryName)
    }
}
