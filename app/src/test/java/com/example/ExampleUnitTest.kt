package com.example

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
 * اختبارات الوحدات الفعلية للحسابات المالية الدقيقة (BigDecimal / Minor Units)،
 * محلل رسائل البنوك (العربية والإنجليزية)، ومحلل الإملاء الصوتي بالعربية.
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
    }

    @Test
    fun `parse Arabic bank SMS for AlRajhi, SNB, Alinma, and Refund`() {
        // 1. شراء بالبطاقة - مصرف الراجحي
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

        // 2. استرداد مبلغ - لا يُصنف كشراء
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
    fun `parse English bank SMS for POS purchase, ATM withdrawal, and outgoing transfer`() {
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
    fun `ignore non-transaction balance notices and promotional discount messages with word خصم`() {
        // إشعار رصيد لا يمثل معاملة جديدة
        val balanceSms = "عزيزي العميل، الرصيد المتاح في حسابكم الجاري هو 12,450.00 ر.س"
        val parsedBal = BankSmsParserEngine.parseSms("AlRajhiBank", balanceSms)
        assertEquals(SmsSuggestedType.BALANCE_NOTICE_ONLY, parsedBal.suggestedType)
        assertEquals(ReviewStatus.IGNORED, parsedBal.reviewStatus)

        // رسالة عرض ترويجي تحتوي كلمة "خصم" (القاعدة 15)
        val promoSms = "عرض خاص! استمتع بـ خصم 30% بمناسبة نهاية الأسبوع لدى متجر الأزياء"
        val parsedPromo = BankSmsParserEngine.parseSms("StorePromo", promoSms)
        assertEquals(SmsSuggestedType.BALANCE_NOTICE_ONLY, parsedPromo.suggestedType)
        assertEquals(ReviewStatus.IGNORED, parsedPromo.reviewStatus)

        // رسالة غير واضحة بدون مبلغ لا تخترع بيانات وتذهب لقائمة UNCLEAR (القاعدة 16)
        val unclearSms = "تمت عملية شراء عبر نقاط البيع لدى متجر الإلكترونيات بنجاح"
        val parsedUnclear = BankSmsParserEngine.parseSms("Alinma", unclearSms)
        assertEquals(SmsSuggestedType.UNCLEAR, parsedUnclear.suggestedType)
        assertNull(parsedUnclear.extractedAmountMinor)
        assertEquals(ReviewStatus.UNCLEAR, parsedUnclear.reviewStatus)
    }

    @Test
    fun `refund reduces net expenses without inflating regular income and transfer is separated`() {
        val list = listOf(
            TransactionEntity(id = 1, type = TransactionType.EXPENSE, amountMinor = 500_00L, description = "مشتريات", accountId = 1),
            TransactionEntity(id = 2, type = TransactionType.REFUND, amountMinor = 120_00L, description = "استرداد", accountId = 1),
            TransactionEntity(id = 3, type = TransactionType.INCOME, amountMinor = 9000_00L, description = "راتب", accountId = 1),
            TransactionEntity(id = 4, type = TransactionType.TRANSFER, amountMinor = 1000_00L, description = "تحويل للكاش", accountId = 1, toAccountId = 2)
        )
        // صافي المصروفات = 500 - 120 = 380.00
        assertEquals(380_00L, SaidiQershRepository.computeNetConfirmedExpensesMinor(list))
        // إجمالي الدخل = 9000.00 فقط (لا يشمل الاسترداد ولا التحويل)
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
        assertEquals("الوقود والمواصلات", proposal.suggestedCategoryName)
    }
}
