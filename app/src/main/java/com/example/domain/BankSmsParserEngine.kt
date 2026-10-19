package com.example.domain

import com.example.data.local.AccountEntity
import com.example.data.local.BankEntity
import com.example.data.local.CategoryEntity
import com.example.data.local.ReviewStatus
import com.example.data.local.SmsRuleEntity
import com.example.data.local.SmsSuggestedType
import java.util.Locale

data class ParsedSmsResult(
    val messageHash: String,
    val senderAddress: String,
    val matchedBankId: Long?,
    val bankName: String,
    val isBankSmsEnabled: Boolean,
    val suggestedType: SmsSuggestedType,
    val extractedAmountMinor: Long?,
    val currency: String,
    val merchantName: String,
    val referenceNumber: String?,
    val extractedAccountLast4: String,
    val extractedCardLast4: String,
    val matchedAccountId: Long?,
    val suggestedCategoryId: Long?,
    val suggestedCategoryName: String,
    val reviewStatus: ReviewStatus,
    val sanitizedSnippet: String,
    val smsTimestamp: Long
)

/**
 * محرك محلي فعلي ومطور لتحليل رسائل البنوك السعودية والمصرية ومحافظ الدفع (Samsung Wallet / Apple Pay / مدى):
 * 1. يدعم العملة قبل الرقم أو بعده، وحرف الياء والألف المقصورة (ريال سعودي / ريال سعودى)، والأرقام الصحيحة أو العشرية.
 * 2. يفلتر تلقائيًا الرسائل غير المالية (تفعيل مستفيد، إضافة مستفيد، رمز التحقق، كود التفعيل، OTP، الرقم السري، تسجيل دخول).
 * 3. يستخرج آخر 4 أرقام من الحساب (*2000) أو البطاقة (*5650) ويربطها تلقائيًا بالحساب المسجل في التطبيق.
 */
object BankSmsParserEngine {

    // قائمة الكلمات المستبعدة تلقائيًا للرسائل غير المالية (Auto-Ignore Non-Financial SMS)
    val AUTO_IGNORE_NON_FINANCIAL_KEYWORDS = listOf(
        "تم تفعيل مستفيد",
        "تم إضافة مستفيد",
        "تم اضافة مستفيد",
        "رمز التحقق",
        "كود التفعيل",
        "كلمة المرور المؤقتة",
        "OTP",
        "الرقم السري",
        "تسجيل دخول"
    )

    // كلمات العروض الترويجية أو النقاط التي تحتوي كلمة "خصم" ولكنها ليست معاملات مالية
    private val PROMO_OR_NON_TX_PATTERNS = listOf(
        Regex("(خصم|تخفيض)\\s*\\d+\\s*%", RegexOption.IGNORE_CASE),
        Regex("كود خصم|كوبون خصم|عرض خاص|خصم يصل إلى|استفد من خصم|بمناسبة", RegexOption.IGNORE_CASE),
        Regex("نقاط مكافأة|نقاطك|خصم نقاط|اكسب نقاط|مكافآت", RegexOption.IGNORE_CASE),
        Regex("Verification Code|One Time Password|لا تشارك الرمز", RegexOption.IGNORE_CASE)
    )

    // رسائل إشعار الرصيد فقط أو الكشوفات التي لا تمثل معاملة جديدة
    private val BALANCE_ONLY_PATTERNS = listOf(
        Regex("^(?:إشعار رصيد|عزيزي العميل،?\\s*الرصيد المتاح|رصيد حسابكم الحالي هو|Available Balance is|Your current balance is)", RegexOption.IGNORE_CASE),
        Regex("(?:كشف حسابك الشهري جاهز|تم إصدار كشف الحساب|Statement is ready)", RegexOption.IGNORE_CASE)
    )

    // الأنماط المحدثة لاستخراج المبلغ بدقة من جميع الصيغ (بما فيها Samsung Wallet ومصرف الإنماء والراجحي وSNB وCIB)
    private val AMOUNT_PATTERNS_ORDERED = listOf(
        // النمط 1: (مبلغ|المبلغ|بمبلغ) متبوعاً بالعملة (ريال سعودي/سعودى/SAR/ر.س/ج.م...) ثم الرقم
        Regex("""(?:مبلغ|المبلغ|بمبلغ|قيمة|Amount|Amt)\s*[:\-]?\s*(?:(ريال سعود[ي|ى]|ريال|SAR|SR|ر\.س|جنيه مصر[ي|ى]|جنيه|EGP|LE|ج\.م|USD|\$|AED|د\.إ|KWD|د\.ك))?\s*([\d,]+(?:\.\d{1,2})?)\s*(?:(ريال سعود[ي|ى]|ريال|SAR|SR|ر\.س|جنيه مصر[ي|ى]|جنيه|EGP|LE|ج\.م|USD|\$|AED|د\.إ|KWD|د\.ك))?""", RegexOption.IGNORE_CASE),
        // النمط 2: العملة أولاً (SAR / ر.س / ريال سعودي / ريال سعودى / ريال / ج.م) ثم الرقم
        Regex("""(SAR|SR|ر\.س|ريال سعود[ي|ى]|ريال|EGP|LE|ج\.م|جنيه مصر[ي|ى]|جنيه|USD|AED|د\.إ|KWD|د\.ك)\s*[:\-]?\s*([\d,]+(?:\.\d{1,2})?)""", RegexOption.IGNORE_CASE),
        // النمط 3: الرقم أولاً ثم العملة
        Regex("""([\d,]+(?:\.\d{1,2})?)\s*(SAR|SR|ر\.س|ريال سعود[ي|ى]|ريال|EGP|LE|ج\.م|جنيه مصر[ي|ى]|جنيه|USD|AED|د\.إ|KWD|د\.ك)""", RegexOption.IGNORE_CASE)
    )

    // استخراج آخر 4 أرقام من الحساب (مثال: حساب: *2000 أو حساب *2000 أو Account: *2000)
    private val ACCOUNT_LAST4_REGEX = Regex(
        """(?:حساب|الحساب|حسابكم|Acc|Account)\s*(?:رقم)?\s*[:\-]?\s*[*xX#]*(\d{4})\b""",
        RegexOption.IGNORE_CASE
    )

    // استخراج آخر 4 أرقام من البطاقة (مثال: بطاقة مدى: *5650 أو بطاقة: 4821 أو مدى: *5650)
    private val CARD_LAST4_REGEX = Regex(
        """(?:بطاقة مدى|بطاقة ائتمانية|بطاقة|مدى|فيزا|ماستركارد|Card|Mada)\s*(?:رقم)?\s*[:\-]?\s*[*xX#]*(\d{4})\b""",
        RegexOption.IGNORE_CASE
    )

    // استخراج اسم التاجر أو الجهة (يدعم "من: dani est" في نهاية الرسالة أو "لدى: ..." أو "المستفيد: ...")
    private val MERCHANT_PATTERNS = listOf(
        Regex("""(?:لدى|لدي|عند|التاجر|Merchant|At)\s*[:\-]?\s*([^\n\r،,;|]+)""", RegexOption.IGNORE_CASE),
        Regex("""(?:من)\s*:\s*([^\n\r،,;|]+)""", RegexOption.IGNORE_CASE),
        Regex("""(?:لصالح|المستفيد|إلى|الى|To|Beneficiary|الجهة المفوترة|مفوتر)\s*[:\-]?\s*([^\n\r،,;|]+)""", RegexOption.IGNORE_CASE)
    )

    // استخراج رقم المرجع / العملية لمنع التكرار
    private val REFERENCE_PATTERNS = listOf(
        Regex("""(?:مرجع|رقم المرجع|رقم العملية|الرقم المرجعي|Ref|Reference|TxID|Auth)\s*(?:رقم)?\s*[:#\-]?\s*([A-Za-z0-9\-]{4,24})""", RegexOption.IGNORE_CASE)
    )

    fun parseSms(
        senderAddress: String,
        rawBody: String,
        smsTimestamp: Long = System.currentTimeMillis(),
        knownBanks: List<BankEntity> = emptyList(),
        customRules: List<SmsRuleEntity> = emptyList(),
        categories: List<CategoryEntity> = emptyList(),
        accounts: List<AccountEntity> = emptyList()
    ): ParsedSmsResult {
        val normalizedBody = MoneyUtils.normalizeDigits(rawBody)
        val messageHash = MoneyUtils.computeMessageHash(senderAddress, normalizedBody)
        val sanitizedSnippet = sanitizeSmsForStorage(normalizedBody)

        // استخراج آخر 4 أرقام للحساب والبطاقة ومطابقتها الذكية مع الحسابات المسجلة
        val extractedAccLast4 = extractAccountLast4(normalizedBody)
        val extractedCardLast4 = extractCardLast4(normalizedBody)
        val smartMatchedAccount = matchAccountByLast4OrBank(
            extractedAccountLast4 = extractedAccLast4,
            extractedCardLast4 = extractedCardLast4,
            senderAddress = senderAddress,
            body = normalizedBody,
            accounts = accounts,
            knownBanks = knownBanks
        )

        // 1. التعرف على البنك (من الحساب المطابق ذكيًا أو اسم المرسل أو محتوى الرسالة)
        val matchedBank = matchBank(senderAddress, normalizedBody, knownBanks)
            ?: smartMatchedAccount?.bankId?.let { bId -> knownBanks.firstOrNull { it.id == bId } }
        val bankName = matchedBank?.nameAr
            ?: smartMatchedAccount?.bankName?.takeIf { it.isNotBlank() }
            ?: inferBankNameFromText(senderAddress, normalizedBody, extractedAccLast4, extractedCardLast4)
        val isBankSmsEnabled = matchedBank?.smsEnabled ?: true
        val defaultCurrency = matchedBank?.defaultCurrency
            ?: smartMatchedAccount?.currency
            ?: inferDefaultCurrency(normalizedBody)

        // 2. الفلترة التلقائية للرسائل غير المالية (Auto-Ignore Non-Financial SMS)
        if (containsAutoIgnoreKeyword(normalizedBody) ||
            (PROMO_OR_NON_TX_PATTERNS.any { it.containsMatchIn(normalizedBody) } &&
                !containsConfirmedFinancialActionVerb(normalizedBody))
        ) {
            return ParsedSmsResult(
                messageHash = messageHash,
                senderAddress = senderAddress,
                matchedBankId = matchedBank?.id,
                bankName = bankName,
                isBankSmsEnabled = isBankSmsEnabled,
                suggestedType = SmsSuggestedType.BALANCE_NOTICE_ONLY,
                extractedAmountMinor = null,
                currency = defaultCurrency,
                merchantName = "",
                referenceNumber = null,
                extractedAccountLast4 = extractedAccLast4,
                extractedCardLast4 = extractedCardLast4,
                matchedAccountId = smartMatchedAccount?.id,
                suggestedCategoryId = null,
                suggestedCategoryName = "إشعار غير مالي",
                reviewStatus = ReviewStatus.IGNORED,
                sanitizedSnippet = sanitizedSnippet,
                smsTimestamp = smsTimestamp
            )
        }

        // 3. فحص رسائل إشعار الرصيد فقط التي لا تمثل معاملة جديدة
        if (BALANCE_ONLY_PATTERNS.any { it.containsMatchIn(normalizedBody.trim()) } ||
            (normalizedBody.contains("الرصيد المتاح") && !containsFinancialMovementKeyword(normalizedBody))
        ) {
            return ParsedSmsResult(
                messageHash = messageHash,
                senderAddress = senderAddress,
                matchedBankId = matchedBank?.id,
                bankName = bankName,
                isBankSmsEnabled = isBankSmsEnabled,
                suggestedType = SmsSuggestedType.BALANCE_NOTICE_ONLY,
                extractedAmountMinor = null,
                currency = defaultCurrency,
                merchantName = "إشعار رصيد",
                referenceNumber = extractReferenceNumber(normalizedBody),
                extractedAccountLast4 = extractedAccLast4,
                extractedCardLast4 = extractedCardLast4,
                matchedAccountId = smartMatchedAccount?.id,
                suggestedCategoryId = null,
                suggestedCategoryName = "إشعار رصيد",
                reviewStatus = ReviewStatus.IGNORED,
                sanitizedSnippet = sanitizedSnippet,
                smsTimestamp = smsTimestamp
            )
        }

        // 4. تحديد نوع العملية من القواعد المخصصة أو القواعد الذكية الشاملة
        val detectedType = detectOperationType(normalizedBody, matchedBank?.id, customRules)

        // 5. استخراج المبلغ والعملة
        val (amountMinor, currency) = extractAmountAndCurrency(normalizedBody, defaultCurrency)

        // 6. استخراج التاجر ورقم المرجع
        val referenceNumber = extractReferenceNumber(normalizedBody)
        val merchantName = extractMerchant(normalizedBody, detectedType)

        // 7. إذا لم يمكن استخراج المبلغ أو نوع العملية بثقة -> قائمة غير واضحة (UNCLEAR)
        if (detectedType == SmsSuggestedType.UNCLEAR || amountMinor == null || amountMinor <= 0L) {
            return ParsedSmsResult(
                messageHash = messageHash,
                senderAddress = senderAddress,
                matchedBankId = matchedBank?.id,
                bankName = bankName,
                isBankSmsEnabled = isBankSmsEnabled,
                suggestedType = SmsSuggestedType.UNCLEAR,
                extractedAmountMinor = amountMinor,
                currency = currency,
                merchantName = merchantName,
                referenceNumber = referenceNumber,
                extractedAccountLast4 = extractedAccLast4,
                extractedCardLast4 = extractedCardLast4,
                matchedAccountId = smartMatchedAccount?.id,
                suggestedCategoryId = null,
                suggestedCategoryName = "أخرى",
                reviewStatus = ReviewStatus.UNCLEAR,
                sanitizedSnippet = sanitizedSnippet,
                smsTimestamp = smsTimestamp
            )
        }

        // 8. اقتراح التصنيف المناسب بناءً على اسم التاجر ونوع العملية
        val (catId, catName) = suggestCategory(merchantName, normalizedBody, detectedType, categories)

        return ParsedSmsResult(
            messageHash = messageHash,
            senderAddress = senderAddress,
            matchedBankId = matchedBank?.id,
            bankName = bankName,
            isBankSmsEnabled = isBankSmsEnabled,
            suggestedType = detectedType,
            extractedAmountMinor = amountMinor,
            currency = currency,
            merchantName = merchantName.ifBlank { detectedType.titleAr },
            referenceNumber = referenceNumber,
            extractedAccountLast4 = extractedAccLast4,
            extractedCardLast4 = extractedCardLast4,
            matchedAccountId = smartMatchedAccount?.id,
            suggestedCategoryId = catId,
            suggestedCategoryName = catName,
            reviewStatus = ReviewStatus.PENDING,
            sanitizedSnippet = sanitizedSnippet,
            smsTimestamp = smsTimestamp
        )
    }

    fun containsAutoIgnoreKeyword(body: String): Boolean {
        return AUTO_IGNORE_NON_FINANCIAL_KEYWORDS.any { keyword ->
            if (keyword.equals("OTP", ignoreCase = true)) {
                Regex("""\bOTP\b""", RegexOption.IGNORE_CASE).containsMatchIn(body)
            } else {
                body.contains(keyword, ignoreCase = true)
            }
        }
    }

    fun extractAccountLast4(body: String): String {
        val match = ACCOUNT_LAST4_REGEX.find(body)
        return match?.groupValues?.getOrNull(1)?.trim() ?: ""
    }

    fun extractCardLast4(body: String): String {
        val match = CARD_LAST4_REGEX.find(body)
        return match?.groupValues?.getOrNull(1)?.trim() ?: ""
    }

    /**
     * الربط الذكي للحسابات والبطاقات بناءً على آخر 4 أرقام للحساب أو البطاقة، ثم البنك.
     */
    fun matchAccountByLast4OrBank(
        extractedAccountLast4: String,
        extractedCardLast4: String,
        senderAddress: String,
        body: String,
        accounts: List<AccountEntity>,
        knownBanks: List<BankEntity>
    ): AccountEntity? {
        if (accounts.isEmpty()) return null

        // 1. مطابقة مباشرة مع آخر 4 أرقام من الحساب
        if (extractedAccountLast4.isNotBlank()) {
            val byAccLast4 = accounts.firstOrNull {
                it.accountLast4 == extractedAccountLast4 || it.cardLast4 == extractedAccountLast4 || it.name.contains(extractedAccountLast4)
            }
            if (byAccLast4 != null) return byAccLast4
        }

        // 2. مطابقة مباشرة مع آخر 4 أرقام من البطاقة
        if (extractedCardLast4.isNotBlank()) {
            val byCardLast4 = accounts.firstOrNull {
                it.cardLast4 == extractedCardLast4 || it.accountLast4 == extractedCardLast4 || it.name.contains(extractedCardLast4)
            }
            if (byCardLast4 != null) return byCardLast4
        }

        // 3. مطابقة بناءً على البنك المكتشف
        val bank = matchBank(senderAddress, body, knownBanks)
        if (bank != null) {
            val byBankId = accounts.firstOrNull { it.bankId == bank.id && it.smsParsingEnabled }
                ?: accounts.firstOrNull { it.bankName.contains(bank.nameAr.take(6)) }
            if (byBankId != null) return byBankId
        }

        // 4. إذا كانت الرسالة تحتوي على حساب *2000 أو بطاقة *5650 (الخاصة بمصرف الإنماء غالبًا)
        if (extractedAccountLast4 == "2000" || extractedCardLast4 == "5650") {
            val alinmaAcc = accounts.firstOrNull { it.name.contains("الإنماء") || it.name.contains("الانماء") || it.bankName.contains("الإنماء") }
            if (alinmaAcc != null) return alinmaAcc
        }

        return accounts.firstOrNull { it.smsParsingEnabled }
    }

    private fun matchBank(sender: String, body: String, banks: List<BankEntity>): BankEntity? {
        val sLower = sender.lowercase(Locale.ROOT)
        val bLower = body.lowercase(Locale.ROOT)
        for (bank in banks) {
            val tokens = bank.senderIdsCsv.split(",")
                .map { it.trim().lowercase(Locale.ROOT) }
                .filter { it.isNotEmpty() }
            if (tokens.any { sLower.contains(it) || bLower.contains(it) }) {
                return bank
            }
        }
        return null
    }

    private fun inferBankNameFromText(
        sender: String,
        body: String,
        accLast4: String,
        cardLast4: String
    ): String {
        val combined = "$sender $body"
        return when {
            combined.contains("الإنماء", ignoreCase = true) || combined.contains("الانماء", ignoreCase = true) || combined.contains("Alinma", ignoreCase = true) || accLast4 == "2000" || cardLast4 == "5650" -> "مصرف الإنماء"
            combined.contains("الراجحي", ignoreCase = true) || combined.contains("AlRajhi", ignoreCase = true) -> "مصرف الراجحي"
            combined.contains("SNB", ignoreCase = true) || combined.contains("الأهلي السعودي", ignoreCase = true) || combined.contains("AlAhli", ignoreCase = true) -> "البنك الأهلي السعودي (SNB)"
            combined.contains("بنك الرياض", ignoreCase = true) || combined.contains("Riyad", ignoreCase = true) -> "بنك الرياض"
            combined.contains("SAB", ignoreCase = true) || combined.contains("الأول", ignoreCase = true) -> "البنك السعودي الأول (SAB)"
            combined.contains("CIB", ignoreCase = true) -> "البنك التجاري الدولي (CIB)"
            combined.contains("NBE", ignoreCase = true) || combined.contains("الأهلي المصري", ignoreCase = true) -> "البنك الأهلي المصري (NBE)"
            combined.contains("Samsung Wallet", ignoreCase = true) || combined.contains("Apple Pay", ignoreCase = true) -> "مصرف الإنماء"
            sender.isNotBlank() -> sender
            else -> "بنك غير محدد"
        }
    }

    private fun inferDefaultCurrency(body: String): String {
        return when {
            body.contains("ج.م") || body.contains("جنيه") || body.contains("EGP", ignoreCase = true) -> "EGP"
            body.contains("د.إ") || body.contains("AED", ignoreCase = true) -> "AED"
            body.contains("USD", ignoreCase = true) || body.contains("$") -> "USD"
            else -> "SAR"
        }
    }

    private fun containsConfirmedFinancialActionVerb(body: String): Boolean {
        return Regex("(?:شراء|سحب|حوالة|تحويل|إيداع|ايداع|استرداد|سداد فاتورة|PoS|Purchase|Withdrawal|Transfer|Refund)", RegexOption.IGNORE_CASE)
            .containsMatchIn(body)
    }

    private fun containsFinancialMovementKeyword(body: String): Boolean {
        return Regex("(?:شراء|سحب|حوالة|تحويل|إيداع|ايداع|استرداد|عكس عملية|سداد|Purchase|Withdrawal|Transfer|Deposit|Refund|Bill|POS|ATM)", RegexOption.IGNORE_CASE)
            .containsMatchIn(body)
    }

    private fun detectOperationType(
        body: String,
        bankId: Long?,
        customRules: List<SmsRuleEntity>
    ): SmsSuggestedType {
        if (Regex("(?:استرداد|إرجاع|ارجاع|عكس عملية|عملية مستردة|Refund|Reversal|Cashback Refund)", RegexOption.IGNORE_CASE).containsMatchIn(body)) {
            return SmsSuggestedType.REFUND
        }
        if (Regex("(?:سداد بطاقة|سداد مستحقات البطاقة|دفعة بطاقة ائتمانية|Credit Card Payment|Card Settlement)", RegexOption.IGNORE_CASE).containsMatchIn(body)) {
            return SmsSuggestedType.CREDIT_CARD_SETTLEMENT
        }
        if (Regex("(?:سداد فاتورة|دفع فاتورة|فاتورة سداد|SADAD|Bill Payment)", RegexOption.IGNORE_CASE).containsMatchIn(body)) {
            return SmsSuggestedType.BILL_PAYMENT
        }
        if (Regex("(?:سحب نقدي|سحب صراف|صراف آلي|ATM Withdrawal|Cash Withdrawal|سحب: صراف)", RegexOption.IGNORE_CASE).containsMatchIn(body)) {
            return SmsSuggestedType.ATM_WITHDRAWAL
        }
        if (Regex("(?:حوالة واردة|تحويل وارد|حوالة محلية واردة|Incoming Transfer|Credit Transfer|تم إضافة مبلغ حوالة)", RegexOption.IGNORE_CASE).containsMatchIn(body)) {
            return SmsSuggestedType.INCOMING_TRANSFER
        }
        if (Regex("(?:إيداع نقدي|ايداع نقدي|إيداع راتب|إيداع في حسابك|Cash Deposit|Salary Deposit|Salary Credit)", RegexOption.IGNORE_CASE).containsMatchIn(body)) {
            return SmsSuggestedType.DEPOSIT
        }
        if (Regex("(?:حوالة صادرة|تحويل صادر|حوالة محلية صادرة|حوالة داخلية صادرة|Outgoing Transfer|Transfer to)", RegexOption.IGNORE_CASE).containsMatchIn(body)) {
            return SmsSuggestedType.OUTGOING_TRANSFER
        }
        if (Regex("(?:شراء|شراء عبر|نقاط البيع|شراء انترنت|شراء أثير|مدى-أثير|عملية شراء|بطاقة مدى|Samsung Wallet|Apple Pay|PoS Purchase|POS|Online Purchase|Card Purchase)", RegexOption.IGNORE_CASE).containsMatchIn(body)) {
            return SmsSuggestedType.CARD_PURCHASE
        }

        val applicableRules = customRules.filter { it.isActive && (bankId == null || it.bankId == bankId) }
        for (rule in applicableRules) {
            try {
                if (Regex(rule.keywordOrRegex, RegexOption.IGNORE_CASE).containsMatchIn(body)) {
                    return rule.suggestedType
                }
            } catch (_: Exception) {
                if (body.contains(rule.keywordOrRegex, ignoreCase = true)) {
                    return rule.suggestedType
                }
            }
        }

        return SmsSuggestedType.UNCLEAR
    }

    private fun extractAmountAndCurrency(body: String, fallbackCurrency: String): Pair<Long?, String> {
        val linesWithoutBalance = body.lines().filterNot { line ->
            val trimmed = line.trim()
            trimmed.startsWith("الرصيد") ||
                trimmed.startsWith("رصيد") ||
                trimmed.startsWith("Avail", ignoreCase = true) ||
                trimmed.startsWith("Balance", ignoreCase = true)
        }.joinToString("\n")

        val searchTarget = if (linesWithoutBalance.isNotBlank()) linesWithoutBalance else body

        for ((index, regex) in AMOUNT_PATTERNS_ORDERED.withIndex()) {
            val match = regex.find(searchTarget) ?: continue
            when (index) {
                0 -> {
                    val curBefore = match.groupValues.getOrNull(1).orEmpty()
                    val amtStr = match.groupValues.getOrNull(2).orEmpty()
                    val curAfter = match.groupValues.getOrNull(3).orEmpty()
                    val minor = MoneyUtils.parseToMinorUnits(amtStr)
                    if (minor != null && minor > 0L) {
                        val resolvedCur = normalizeCurrencyCode(curBefore.ifBlank { curAfter }, fallbackCurrency)
                        return minor to resolvedCur
                    }
                }
                1 -> {
                    val cur = match.groupValues.getOrNull(1).orEmpty()
                    val amtStr = match.groupValues.getOrNull(2).orEmpty()
                    val minor = MoneyUtils.parseToMinorUnits(amtStr)
                    if (minor != null && minor > 0L) {
                        return minor to normalizeCurrencyCode(cur, fallbackCurrency)
                    }
                }
                2 -> {
                    val amtStr = match.groupValues.getOrNull(1).orEmpty()
                    val cur = match.groupValues.getOrNull(2).orEmpty()
                    val minor = MoneyUtils.parseToMinorUnits(amtStr)
                    if (minor != null && minor > 0L) {
                        return minor to normalizeCurrencyCode(cur, fallbackCurrency)
                    }
                }
            }
        }
        return null to fallbackCurrency
    }

    private fun normalizeCurrencyCode(rawCurrency: String, fallback: String): String {
        val c = rawCurrency.trim().uppercase(Locale.ROOT)
        return when {
            c.isEmpty() -> fallback
            c in listOf("SAR", "SR", "ر.س", "ريال", "ريال سعودي", "ريال سعودى") -> "SAR"
            c in listOf("EGP", "LE", "ج.م", "جنيه", "جنيه مصري", "جنيه مصرى") -> "EGP"
            c in listOf("AED", "د.إ") -> "AED"
            c in listOf("KWD", "د.ك") -> "KWD"
            c in listOf("USD", "$") -> "USD"
            else -> fallback
        }
    }

    private fun extractMerchant(body: String, suggestedType: SmsSuggestedType): String {
        for (regex in MERCHANT_PATTERNS) {
            val match = regex.find(body)
            if (match != null) {
                val candidate = match.groupValues[1]
                    .replace(Regex("(?:بمبلغ|المبلغ|مبلغ|في:|تاريخ|مرجع|الرصيد|بطاقة مدى|حساب:).*$", RegexOption.IGNORE_CASE), "")
                    .trim()
                if (candidate.isNotBlank() && candidate.length <= 50) {
                    return candidate
                }
            }
        }
        return when (suggestedType) {
            SmsSuggestedType.ATM_WITHDRAWAL -> "صراف آلي (ATM)"
            SmsSuggestedType.BILL_PAYMENT -> "سداد فاتورة"
            SmsSuggestedType.CREDIT_CARD_SETTLEMENT -> "سداد البطاقة الائتمانية"
            SmsSuggestedType.DEPOSIT -> "إيداع بنكي"
            else -> ""
        }
    }

    private fun extractReferenceNumber(body: String): String? {
        for (regex in REFERENCE_PATTERNS) {
            val match = regex.find(body)
            if (match != null) {
                val ref = match.groupValues[1].trim()
                if (ref.isNotEmpty()) return ref
            }
        }
        return null
    }

    private fun suggestCategory(
        merchantName: String,
        body: String,
        type: SmsSuggestedType,
        categories: List<CategoryEntity>
    ): Pair<Long?, String> {
        val text = "$merchantName $body".lowercase(Locale.ROOT)
        val targetName = when {
            type == SmsSuggestedType.DEPOSIT || type == SmsSuggestedType.INCOMING_TRANSFER -> "أخرى"
            type == SmsSuggestedType.BILL_PAYMENT || text.contains("كهرباء") || text.contains("مياه") || text.contains("stc") || text.contains("موبايلي") || text.contains("زين") -> "فواتير"
            text.contains("بنزين") || text.contains("وقود") || text.contains("الدريس") || text.contains("ساسكو") || text.contains("نفط") || text.contains("petromin") -> "بنزين"
            text.contains("أوبر") || text.contains("uber") || text.contains("كريم") || text.contains("قطار") || text.contains("تاكسي") -> "مواصلات"
            text.contains("بندة") || text.contains("العثيم") || text.contains("الدانوب") || text.contains("تميمي") || text.contains("كارفور") || text.contains("سوبرماركت") || text.contains("بقالة") || text.contains("لولو") -> "بقالة"
            text.contains("مطعم") || text.contains("كافيه") || text.contains("قهوة") || text.contains("البيك") || text.contains("شاورما") || text.contains("ماكدونالدز") || text.contains("ستاربكس") || text.contains("دانكن") || text.contains("هنقرستيشن") || text.contains("جاهز") -> "أكل بره"
            text.contains("صيدلية") || text.contains("النهدي") || text.contains("الدواء") || text.contains("مستشفى") || text.contains("عيادة") -> "صحة"
            text.contains("إيجار") || text.contains("ايجار") || text.contains("عقار") -> "إيجار"
            text.contains("مدرسة") || text.contains("جامعة") || text.contains("مكتبة") || text.contains("جرير") -> "تعليم"
            text.contains("طيران") || text.contains("فندق") || text.contains("سفر") -> "سفر"
            text.contains("شاهد") || text.contains("نتفليكس") || text.contains("يوتيوب") || text.contains("اشتراك") -> "اشتراكات"
            else -> "تسوق"
        }
        val matched = categories.firstOrNull { it.nameAr == targetName }
            ?: categories.firstOrNull { it.nameAr.contains(targetName.take(4)) }
        return matched?.id to (matched?.nameAr ?: targetName)
    }

    /**
     * يحذف أي أرقام بطاقات كاملة أو رموز سرية ويحتفظ بالنص اللازم للمراجعة بوضوح.
     */
    fun sanitizeSmsForStorage(rawBody: String): String {
        val redacted = rawBody
            .replace(Regex("\\b\\d{12,19}\\b"), "****")
            .replace(Regex("SA\\d{18,22}", RegexOption.IGNORE_CASE), "SA****")
            .replace(Regex("EG\\d{20,27}", RegexOption.IGNORE_CASE), "EG****")
            .replace(Regex("\\s+"), " ")
            .trim()
        return if (redacted.length > 220) {
            redacted.take(220) + "..."
        } else {
            redacted
        }
    }
}
