package com.example

import com.example.data.local.*
import com.example.data.repository.SaidiQershRepository
import com.example.domain.ArabicVoiceExpenseParser
import com.example.domain.BankSmsParserEngine
import com.example.domain.MoneyUtils
import org.junit.Assert.*
import org.junit.Test

/**
 * اختبارات الوحدات الشاملة لتطبيق «الصعيدي قرش» (Saidi Qersh):
 * 1. قراءة رسائل Samsung Wallet ومصرف الإنماء بدقة (بما فيها "ريال سعودى 10" بالألف المقصورة والعملة قبل الرقم).
 * 2. الفلترة التلقائية للرسائل غير المالية (تم تفعيل مستفيد، OTP، كلمة المرور المؤقتة، تسجيل دخول).
 * 3. الربط الذكي للحسابات والبطاقات عبر آخر 4 أرقام (*2000 و *5650).
 * 4. الاكتشاف الذكي للمرسل (Enjaz, Urpay, STC Pay, VF-Cash, Fawry) وعدم نسبتها بالخطأ للراجحي أو الإنماء.
 * 5. حسابات المبالغ بالوحدات الصغرى وفصل الأشهر المستقلة.
 */
class ExampleUnitTest {

    private val sampleBanks = listOf(
        BankEntity(id = 1, nameAr = "مصرف الراجحي", nameEn = "Al Rajhi Bank", code = "ALRAJHI", defaultCurrency = "SAR", senderIdsCsv = "AlRajhi,AlRajhiBank,ALRAJHI"),
        BankEntity(id = 2, nameAr = "مصرف الإنماء", nameEn = "Alinma Bank", code = "ALINMA", defaultCurrency = "SAR", senderIdsCsv = "Alinma,AlinmaBank,ALINMA"),
        BankEntity(id = 3, nameAr = "البنك الأهلي السعودي (SNB)", nameEn = "SNB", code = "SNB", defaultCurrency = "SAR", senderIdsCsv = "SNB,AlAhli"),
        BankEntity(id = 4, nameAr = "إنجاز (Enjaz)", nameEn = "Enjaz", code = "ENJAZ", defaultCurrency = "SAR", senderIdsCsv = "Enjaz,ENJAZ,إنجاز"),
        BankEntity(id = 5, nameAr = "محفظة Urpay", nameEn = "Urpay", code = "URPAY", defaultCurrency = "SAR", senderIdsCsv = "Urpay,URPAY,يورباي"),
        BankEntity(id = 6, nameAr = "STC Pay", nameEn = "STC Pay", code = "STCPAY", defaultCurrency = "SAR", senderIdsCsv = "STC Pay,STCPAY,stcpay"),
        BankEntity(id = 7, nameAr = "فودافون كاش (VF-Cash)", nameEn = "Vodafone Cash", code = "VFCASH", defaultCurrency = "EGP", senderIdsCsv = "VF-Cash,Vodafone,VFCash")
    )

    private val sampleAccounts = listOf(
        AccountEntity(
            id = 10L,
            name = "حساب الإنماء",
            type = AccountType.BANK,
            bankId = 2L,
            bankName = "مصرف الإنماء",
            currency = "SAR",
            accountLast4 = "2000",
            cardLast4 = "5650",
            smsParsingEnabled = true
        ),
        AccountEntity(
            id = 20L,
            name = "حساب الراجحي",
            type = AccountType.BANK,
            bankId = 1L,
            bankName = "مصرف الراجحي",
            currency = "SAR",
            accountLast4 = "9911",
            cardLast4 = "4821",
            smsParsingEnabled = true
        )
    )

    @Test
    fun samsungWalletAlinmaSms_parsesAmountCurrencyBeforeNumberAndMatchesAccountLast4() {
        val sms = "شراء عبر Samsung Wallet مبلغ: ريال سعودى 10 بطاقة مدى: *5650 حساب: *2000 من: dani est"
        val parsed = BankSmsParserEngine.parseSms(
            senderAddress = "Alinma",
            rawBody = sms,
            knownBanks = sampleBanks,
            accounts = sampleAccounts
        )

        assertTrue(parsed.suggestedType.isFinancialTransaction)
        assertEquals(ReviewStatus.PENDING, parsed.reviewStatus)
        assertEquals(10_00L, parsed.extractedAmountMinor) // 10.00 ر.س
        assertEquals("SAR", parsed.currency)
        assertEquals("dani est", parsed.merchantName)
        assertEquals(SmsSuggestedType.CARD_PURCHASE, parsed.suggestedType)
        assertEquals("2000", parsed.extractedAccountLast4)
        assertEquals("5650", parsed.extractedCardLast4)
        assertEquals(10L, parsed.matchedAccountId) // حساب الإنماء *2000 / *5650
    }

    @Test
    fun dynamicSenderDetection_neverMisattributesEnjazUrpayStcPayOrVodafoneToAlRajhiOrAlinma() {
        // 1. رسالة من إنجاز (Enjaz)
        val enjazParsed = BankSmsParserEngine.parseSms(
            senderAddress = "Enjaz",
            rawBody = "حوالة صادرة عبر إنجاز بمبلغ: 550.00 ريال سعودي المستفيد: محمد أحمد مرجع: ENJ99120",
            knownBanks = sampleBanks,
            accounts = sampleAccounts
        )
        assertEquals("Enjaz", enjazParsed.senderAddress)
        assertEquals("إنجاز (Enjaz)", enjazParsed.bankName)
        assertNotEquals("مصرف الإنماء", enjazParsed.bankName)
        assertNotEquals("مصرف الراجحي", enjazParsed.bankName)
        assertNull("Enjaz SMS without *2000/*9911 must not be forced into Alinma/AlRajhi account", enjazParsed.matchedAccountId)

        // 2. رسالة من Urpay
        val urpayParsed = BankSmsParserEngine.parseSms(
            senderAddress = "Urpay",
            rawBody = "شراء عبر محفظة Urpay بمبلغ: SAR 35.00 لدى: كافيه القهوة المختصة",
            knownBanks = sampleBanks,
            accounts = sampleAccounts
        )
        assertEquals("Urpay", urpayParsed.senderAddress)
        assertEquals("محفظة Urpay", urpayParsed.bankName)
        assertNotEquals("مصرف الراجحي", urpayParsed.bankName)
        assertNotEquals("مصرف الإنماء", urpayParsed.bankName)

        // 3. رسالة من STC Pay
        val stcParsed = BankSmsParserEngine.parseSms(
            senderAddress = "STC Pay",
            rawBody = "عملية شراء بطاقة STC Pay بمبلغ 120.00 ر.س لدى: صيدلية النهدي",
            knownBanks = sampleBanks,
            accounts = sampleAccounts
        )
        assertEquals("STC Pay", stcParsed.senderAddress)
        assertEquals("STC Pay", stcParsed.bankName)

        // 4. رسالة من فودافون كاش (VF-Cash)
        val vfParsed = BankSmsParserEngine.parseSms(
            senderAddress = "VF-Cash",
            rawBody = "تم تحويل مبلغ 450.00 ج.م من محفظة فودافون كاش",
            knownBanks = sampleBanks,
            accounts = sampleAccounts
        )
        assertEquals("VF-Cash", vfParsed.senderAddress)
        assertEquals("فودافون كاش (VF-Cash)", vfParsed.bankName)
        assertEquals("EGP", vfParsed.currency)

        // 5. مرسل جديد تماماً غير مسجل مسبقاً (مثلاً PayMob) يعرض اسمه الحقيقي ولا يُنسب للراجحي أو الإنماء
        val customSenderParsed = BankSmsParserEngine.parseSms(
            senderAddress = "PayMob",
            rawBody = "عملية دفع ناجحة بمبلغ 200.00 ريال لدى متجر الإلكترونيات",
            knownBanks = sampleBanks,
            accounts = sampleAccounts
        )
        assertEquals("PayMob", customSenderParsed.senderAddress)
        assertEquals("PayMob", customSenderParsed.bankName)
    }

    @Test
    fun nonFinancialSms_beneficiaryActivationAndOtp_areAutoIgnored() {
        val ignoredSamples = listOf(
            "تم تفعيل مستفيد بنجاح في مصرف الإنماء، رمز التحقق هو 4412",
            "تم إضافة مستفيد جديد في مباشر الراجحي",
            "رمز التحقق: 8821 لا تشاركه مع أحد",
            "كود التفعيل الخاص بك هو 9910",
            "كلمة المرور المؤقتة لإتمام العملية: 1234",
            "Your OTP is 5529 for online banking",
            "الرقم السري المؤقت للبطاقة هو 3310",
            "تسجيل دخول جديد لحسابكم في تطبيق البنك"
        )

        for (body in ignoredSamples) {
            assertTrue("Should auto-ignore: $body", BankSmsParserEngine.containsAutoIgnoreKeyword(body))
            val parsed = BankSmsParserEngine.parseSms(
                senderAddress = "Alinma",
                rawBody = body,
                knownBanks = sampleBanks,
                accounts = sampleAccounts
            )
            assertFalse("Should not be financial transaction: $body", parsed.suggestedType.isFinancialTransaction)
            assertEquals(ReviewStatus.IGNORED, parsed.reviewStatus)
        }
    }

    @Test
    fun bankSmsParser_extractsAmountFromAllRequiredRegexFormats() {
        assertEquals(10_00L, BankSmsParserEngine.parseSms("Alinma", "شراء مبلغ: ريال سعودى 10").extractedAmountMinor)
        assertEquals(250_50L, BankSmsParserEngine.parseSms("Alinma", "شراء المبلغ: ريال سعودي 250.50").extractedAmountMinor)
        assertEquals(145_50L, BankSmsParserEngine.parseSms("AlRajhi", "شراء بمبلغ: SAR 145.50").extractedAmountMinor)
        assertEquals(75_00L, BankSmsParserEngine.parseSms("SNB", "شراء ر.س 75").extractedAmountMinor)
        assertEquals(1250_00L, BankSmsParserEngine.parseSms("Alinma", "حوالة صادرة 1,250.00 SAR").extractedAmountMinor)
        assertEquals(390_25L, BankSmsParserEngine.parseSms("RiyadBank", "سداد فاتورة 390.25 ريال سعودي").extractedAmountMinor)
    }

    @Test
    fun moneyUtils_monthNavigationAndFormatting_worksAccurately() {
        assertEquals("2026-09", MoneyUtils.previousYearMonth("2026-10"))
        assertEquals("2026-11", MoneyUtils.nextYearMonth("2026-10"))
        assertEquals("2025-12", MoneyUtils.previousYearMonth("2026-01"))
        assertEquals("2027-01", MoneyUtils.nextYearMonth("2026-12"))
        assertTrue(MoneyUtils.formatYearMonthArabic("2026-10").contains("أكتوبر"))
        assertTrue(MoneyUtils.formatYearMonthArabic("2026-10").contains("2026"))
    }

    @Test
    fun netExpenseCalculation_subtractsRefundsAndExcludesTransfers() {
        val txs = listOf(
            TransactionEntity(
                id = 1,
                type = TransactionType.EXPENSE,
                amountMinor = 200_00L,
                currency = "SAR",
                description = "بقالة",
                accountId = 1L
            ),
            TransactionEntity(
                id = 2,
                type = TransactionType.REFUND,
                amountMinor = 50_00L,
                currency = "SAR",
                description = "استرداد",
                accountId = 1L
            ),
            TransactionEntity(
                id = 3,
                type = TransactionType.TRANSFER,
                amountMinor = 500_00L,
                currency = "SAR",
                description = "تحويل داخلي",
                accountId = 1L,
                toAccountId = 2L
            )
        )
        val netExpense = SaidiQershRepository.computeNetConfirmedExpensesMinor(txs)
        assertEquals(150_00L, netExpense)
    }

    @Test
    fun arabicVoiceParser_extractsSpokenArabicNumberWordsAndCategory() {
        val categories = listOf(
            CategoryEntity(id = 12, nameAr = "بنزين", type = CategoryType.EXPENSE, iconName = "⛽", colorHex = "#455A64")
        )
        val proposal = ArabicVoiceExpenseParser.parseSpokenPhrase(
            phrase = "دفعت خمسة وثلاثين ريال بنزين",
            defaultCurrency = "SAR",
            categories = categories
        )
        assertEquals(35_00L, proposal.amountMinor)
        assertEquals("SAR", proposal.currency)
        assertEquals(12L, proposal.suggestedCategoryId)
    }
}
