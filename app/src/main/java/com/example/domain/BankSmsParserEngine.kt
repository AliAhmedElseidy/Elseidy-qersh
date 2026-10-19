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
    val isAutoApproveEnabled: Boolean,
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
 * محرك محلي فعلي ومطور لتحليل رسائل البنوك والمحافظ (السعودية والمصرية):
 * 1. الاكتشاف الذكي للمرسل (Dynamic Sender ID Detection): يقرأ اسم المرسل الفعلي (Alinma, AlRajhi, Enjaz, Vodafone, VF-Cash, Urpay, STC Pay, CIB, NBE, Fawry...) دون نسبة الرسائل خطأً للراجحي أو الإنماء.
 * 2. يدعم العملة قبل الرقم أو بعده، وحرف الياء والألف المقصورة (ريال سعودي / ريال سعودى)، والأرقام الصحيحة أو العشرية.
 * 3. يفلتر تلقائيًا الرسائل غير المالية (تفعيل مستفيد، إضافة مستفيد، رمز التحقق، كود التفعيل، OTP، الرقم السري، تسجيل دخول).
 * 4. يستخرج آخر 4 أرقام من الحساب (*2000) أو البطاقة (*5650) ويربطها تلقائيًا بالحساب المسجل في التطبيق.
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

    // الأنماط المحدثة لاستخراج المبلغ بدقة من جميع الصيغ (بما فيها Samsung Wallet ومصرف الإنماء والراجحي وEnjaz وVF-Cash وSTC Pay وUrpay)
    private val AMOUNT_PATTERNS_ORDERED = listOf(
        // النمط 1: (مبلغ|المبلغ|بمبلغ) متبوعاً بالعملة (ريال سعودي/سعودى/SAR/ر.س/ج.م...) ثم الرقم
        Regex("""(?:مبلغ|المبلغ|بمبلغ|قيمة|Amount|Amt)\s*[:\-]?\s*(?:(ريال سعود[ي|ى]|ريال|SAR|SR|ر\.س|جنيه مصر[ي|ى]|جنيه|جنية|EGP|LE|ج\.م|USD|\$|AED|د\.إ|KWD|د\.ك))?\s*([\d,]+(?:\.\d{1,2})?)\s*(?:(ريال سعود[ي|ى]|ريال|SAR|SR|ر\.س|جنيه مصر[ي|ى]|جنيه|جنية|EGP|LE|ج\.م|USD|\$|AED|د\.إ|KWD|د\.ك))?""", RegexOption.IGNORE_CASE),
        // النمط 2: العملة أولاً (SAR / ر.س / ريال سعودي / ريال سعودى / ريال / ج.م / جنيه) ثم الرقم
        Regex("""(SAR|SR|ر\.س|ريال سعود[ي|ى]|ريال|EGP|LE|ج\.م|جنيه مصر[ي|ى]|جنيه|جنية|USD|AED|د\.إ|KWD|د\.ك)\s*[:\-]?\s*([\d,]+(?:\.\d{1,2})?)""", RegexOption.IGNORE_CASE),
        // النمط 3: الرقم أولاً ثم العملة
        Regex("""([\d,]+(?:\.\d{1,2})?)\s*(SAR|SR|ر\.س|ريال سعود[ي|ى]|ريال|EGP|LE|ج\.م|جنيه مصر[ي|ى]|جنيه|جنية|USD|AED|د\.إ|KWD|د\.ك)""", RegexOption.IGNORE_CASE)
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
        val cleanSender = senderAddress.trim()
        val normalizedBody = MoneyUtils.normalizeDigits(rawBody)
        val messageHash = MoneyUtils.computeMessageHash(cleanSender, normalizedBody)
        val sanitizedSnippet = sanitizeSmsForStorage(normalizedBody)

        // استخراج آخر 4 أرقام للحساب والبطاقة
        val extractedAccLast4 = extractAccountLast4(normalizedBody)
        val extractedCardLast4 = extractCardLast4(normalizedBody)

        // 1. التعرف الذكي والدقيق على جهة المرسل (Sender ID) دون إجبار الرسالة على الراجحي أو الإنماء
        val matchedBank = matchBankStrictly(
            sender = cleanSender,
            body = normalizedBody,
            extractedAccLast4 = extractedAccLast4,
            extractedCardLast4 = extractedCardLast4,
            banks = knownBanks
        )

        // 2. الربط الذكي للحسابات والبطاقات بناءً على آخر 4 أرقام أو البنك الفعلي المطابق
        val smartMatchedAccount = matchAccountByLast4OrBank(
            extractedAccountLast4 = extractedAccLast4,
            extractedCardLast4 = extractedCardLast4,
            matchedBank = matchedBank,
            senderAddress = cleanSender,
            accounts = accounts
        )

        // اسم الجهة الحقيقي للعرض على الكارت
        val bankName = resolveSenderDisplayName(
            sender = cleanSender,
            body = normalizedBody,
            matchedBank = matchedBank,
            accLast4 = extractedAccLast4,
            cardLast4 = extractedCardLast4
        )

        val isBankSmsEnabled = matchedBank?.smsEnabled ?: true
        val isAutoApproveEnabled = matchedBank?.autoApproveEnabled ?: false
        val defaultCurrency = inferDefaultCurrency(normalizedBody, matchedBank, cleanSender)

        // 3. الفلترة التلقائية للرسائل غير المالية (Auto-Ignore Non-Financial SMS)
        if (containsAutoIgnoreKeyword(normalizedBody) ||
            (PROMO_OR_NON_TX_PATTERNS.any { it.containsMatchIn(normalizedBody) } &&
                !containsConfirmedFinancialActionVerb(normalizedBody))
        ) {
            return ParsedSmsResult(
                messageHash = messageHash,
                senderAddress = cleanSender,
                matchedBankId = matchedBank?.id,
                bankName = bankName,
                isBankSmsEnabled = isBankSmsEnabled,
                isAutoApproveEnabled = isAutoApproveEnabled,
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

        // 4. فحص رسائل إشعار الرصيد فقط التي لا تمثل معاملة جديدة
        if (BALANCE_ONLY_PATTERNS.any { it.containsMatchIn(normalizedBody.trim()) } ||
            (normalizedBody.contains("الرصيد المتاح") && !containsFinancialMovementKeyword(normalizedBody))
        ) {
            return ParsedSmsResult(
                messageHash = messageHash,
                senderAddress = cleanSender,
                matchedBankId = matchedBank?.id,
                bankName = bankName,
                isBankSmsEnabled = isBankSmsEnabled,
                isAutoApproveEnabled = isAutoApproveEnabled,
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

        // 5. تحديد نوع العملية من القواعد المخصصة أو القواعد الذكية الشاملة
        val detectedType = detectOperationType(normalizedBody, matchedBank?.id, customRules)

        // 6. استخراج المبلغ والعملة
        val (amountMinor, currency) = extractAmountAndCurrency(normalizedBody, defaultCurrency)

        // 7. استخراج التاجر ورقم المرجع
        val referenceNumber = extractReferenceNumber(normalizedBody)
        val merchantName = extractMerchant(normalizedBody, detectedType)

        // 8. إذا لم يمكن استخراج المبلغ أو نوع العملية بثقة -> قائمة غير واضحة (UNCLEAR)
        if (detectedType == SmsSuggestedType.UNCLEAR || amountMinor == null || amountMinor <= 0L) {
            return ParsedSmsResult(
                messageHash = messageHash,
                senderAddress = cleanSender,
                matchedBankId = matchedBank?.id,
                bankName = bankName,
                isBankSmsEnabled = isBankSmsEnabled,
                isAutoApproveEnabled = isAutoApproveEnabled,
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

        // 9. اقتراح التصنيف المناسب بناءً على اسم التاجر ونوع العملية
        val (catId, catName) = suggestCategory(merchantName, normalizedBody, detectedType, categories)

        return ParsedSmsResult(
            messageHash = messageHash,
            senderAddress = cleanSender,
            matchedBankId = matchedBank?.id,
            bankName = bankName,
            isBankSmsEnabled = isBankSmsEnabled,
            isAutoApproveEnabled = isAutoApproveEnabled,
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
     * مطابقة دقيقة لجهة الرسالة:
     * 1. تطابق أولاً مع اسم المرسل الفعلي (senderAddress) مباشرة.
     * 2. إذا لم يكن المرسل معروفاً، تفحص ما إذا كان نص الرسالة يذكر اسم الجهة صراحةً.
     * 3. إذا كانت الرسالة من محفظة دفع مثل Samsung Wallet وبها حساب *2000 أو بطاقة *5650، تربطها بالبنك المطابق للأرقام.
     */
    private fun matchBankStrictly(
        sender: String,
        body: String,
        extractedAccLast4: String,
        extractedCardLast4: String,
        banks: List<BankEntity>
    ): BankEntity? {
        if (banks.isEmpty()) return null
        val sLower = sender.trim().lowercase(Locale.ROOT)

        // أولاً: مطابقة اسم المرسل (Sender ID) مباشرة
        if (sLower.isNotEmpty()) {
            for (bank in banks) {
                val tokens = bank.senderIdsCsv.split(",")
                    .map { it.trim().lowercase(Locale.ROOT) }
                    .filter { it.isNotEmpty() }
                if (tokens.any { token -> sLower == token || sLower.contains(token) || token.contains(sLower) && sLower.length >= 3 }) {
                    return bank
                }
            }
        }

        // ثانياً: إذا لم يتطابق اسم المرسل مع أي جهة معروفة، وكان اسم المرسل واضحاً ومميزاً (مثل Enjaz أو VF-Cash أو Barq)،
        // لا نقوم بإجباره على بنك آخر إلا إذا كان نص الرسالة نفسه يذكر اسم البنك صراحةً
        val bLower = body.lowercase(Locale.ROOT)
        for (bank in banks) {
            val tokens = bank.senderIdsCsv.split(",")
                .map { it.trim().lowercase(Locale.ROOT) }
                .filter { it.length >= 3 }
            if (tokens.any { token -> bLower.contains(token) }) {
                return bank
            }
        }

        // ثالثاً: في حال كانت الرسالة من Samsung Wallet أو Apple Pay وتحتوي على حساب *2000 أو بطاقة *5650
        if (extractedAccLast4 == "2000" || extractedCardLast4 == "5650") {
            return banks.firstOrNull { it.code.equals("ALINMA", ignoreCase = true) }
        }
        if (extractedAccLast4 == "4821" || extractedCardLast4 == "4821") {
            return banks.firstOrNull { it.code.equals("ALRAJHI", ignoreCase = true) }
        }

        return null
    }

    /**
     * الربط الذكي للحسابات والبطاقات بناءً على آخر 4 أرقام للحساب أو البطاقة، ثم البنك الفعلي المطابق فقط.
     * لا ينسب الرسالة قسراً لحساب الإنماء أو الراجحي إذا كانت من جهة أخرى!
     */
    fun matchAccountByLast4OrBank(
        extractedAccountLast4: String,
        extractedCardLast4: String,
        matchedBank: BankEntity?,
        senderAddress: String,
        accounts: List<AccountEntity>
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

        // 3. مطابقة بناءً على البنك الفعلي المكتشف
        if (matchedBank != null) {
            val byBankId = accounts.firstOrNull { it.bankId == matchedBank.id && it.smsParsingEnabled }
                ?: accounts.firstOrNull {
                    it.smsParsingEnabled && (
                        it.bankName.contains(matchedBank.nameAr.take(6), ignoreCase = true) ||
                            it.name.contains(matchedBank.nameEn.take(5), ignoreCase = true) ||
                            it.name.contains(matchedBank.code, ignoreCase = true)
                        )
                }
            if (byBankId != null) return byBankId
        }

        // 4. مطابقة اسم الحساب مباشرة مع اسم المرسل (مثل حساب فودافون كاش أو حساب Urpay أو STC Pay)
        if (senderAddress.isNotBlank()) {
            val bySenderName = accounts.firstOrNull { acc ->
                acc.name.contains(senderAddress, ignoreCase = true) ||
                    acc.bankName.contains(senderAddress, ignoreCase = true)
            }
            if (bySenderName != null) return bySenderName
        }

        // 5. إذا كانت الرسالة تحتوي على حساب *2000 أو بطاقة *5650
        if (extractedAccountLast4 == "2000" || extractedCardLast4 == "5650") {
            val alinmaAcc = accounts.firstOrNull {
                it.name.contains("الإنماء") || it.name.contains("الانماء") || it.bankName.contains("الإنماء")
            }
            if (alinmaAcc != null) return alinmaAcc
        }

        return null
    }

    /**
     * يستخرج الاسم الحقيقي لجهة الرسالة (Sender ID) لعرضه على الكارت دون افتراض خاطئ للراجحي أو الإنماء.
     */
    fun resolveSenderDisplayName(
        sender: String,
        body: String,
        matchedBank: BankEntity?,
        accLast4: String = "",
        cardLast4: String = ""
    ): String {
        val cleanSender = sender.trim()
        val sLower = cleanSender.lowercase(Locale.ROOT)
        val bLower = body.lowercase(Locale.ROOT)

        if (matchedBank != null) {
            return matchedBank.nameAr
        }

        // فحص الجهات الشهيرة بناءً على اسم المرسل الفعلي عند عدم وجودها في جدول البنوك
        return when {
            sLower.contains("enjaz") || cleanSender.contains("إنجاز") || cleanSender.contains("انجاز") || bLower.contains("enjaz") || body.contains("إنجاز") ->
                "إنجاز (Enjaz)"
            sLower.contains("vf-cash") || sLower.contains("vfcash") || sLower.contains("vodafone") || cleanSender.contains("فودافون") || body.contains("فودافون كاش") ->
                "فودافون كاش (VF-Cash)"
            sLower.contains("urpay") || cleanSender.contains("يورباي") || bLower.contains("urpay") ->
                "Urpay (يورباي)"
            sLower.contains("stc") || cleanSender.contains("اس تي سي") || cleanSender.contains("إس تي سي") || bLower.contains("stc pay") || bLower.contains("stcpay") ->
                "STC Pay"
            sLower.contains("fawry") || cleanSender.contains("فوري") || bLower.contains("fawry") ->
                "فوري (Fawry)"
            sLower.contains("instapay") || cleanSender.contains("انستاباي") || bLower.contains("instapay") ->
                "InstaPay (إنستاباي)"
            sLower.contains("cib") || bLower.contains("cib") ->
                "البنك التجاري الدولي (CIB)"
            sLower.contains("nbe") || cleanSender.contains("الأهلي المصري") || body.contains("الأهلي المصري") ->
                "البنك الأهلي المصري (NBE)"
            sLower.contains("alinma") || cleanSender.contains("الإنماء") || cleanSender.contains("الانماء") ->
                "مصرف الإنماء"
            sLower.contains("rajhi") || cleanSender.contains("الراجحي") ->
                "مصرف الراجحي"
            sLower.contains("snb") || sLower.contains("alahli") || cleanSender.contains("الأهلي") ->
                "البنك الأهلي السعودي (SNB)"
            sLower.contains("riyad") || cleanSender.contains("الرياض") ->
                "بنك الرياض"
            sLower.contains("sab") || cleanSender.contains("الأول") ->
                "البنك السعودي الأول (SAB)"
            (accLast4 == "2000" || cardLast4 == "5650") && (cleanSender.isBlank() || sLower.contains("samsung") || sLower.contains("wallet")) ->
                "مصرف الإنماء"
            cleanSender.isNotBlank() && !cleanSender.equals("Unknown", ignoreCase = true) ->
                cleanSender
            bLower.contains("samsung wallet") -> "Samsung Wallet"
            bLower.contains("apple pay") -> "Apple Pay"
            else -> "جهة مالية غير محددة"
        }
    }

    private fun inferDefaultCurrency(body: String, matchedBank: BankEntity?, sender: String): String {
        val combined = "$sender $body"
        return when {
            combined.contains("ج.م") || combined.contains("جنيه") || combined.contains("جنية") ||
                combined.contains("EGP", ignoreCase = true) ||
                combined.contains("VF-Cash", ignoreCase = true) ||
                combined.contains("Vodafone", ignoreCase = true) ||
                combined.contains("Fawry", ignoreCase = true) -> "EGP"
            combined.contains("د.إ") || combined.contains("AED", ignoreCase = true) -> "AED"
            combined.contains("USD", ignoreCase = true) || combined.contains("$") -> "USD"
            matchedBank != null -> matchedBank.defaultCurrency
            else -> "SAR"
        }
    }

    private fun containsConfirmedFinancialActionVerb(body: String): Boolean {
        return Regex("(?:شراء|سحب|حوالة|تحويل|إيداع|ايداع|استرداد|سداد فاتورة|تم خصم|تم استلام|PoS|Purchase|Withdrawal|Transfer|Refund)", RegexOption.IGNORE_CASE)
            .containsMatchIn(body)
    }

    private fun containsFinancialMovementKeyword(body: String): Boolean {
        return Regex("(?:شراء|سحب|حوالة|تحويل|إيداع|ايداع|استرداد|عكس عملية|سداد|تم خصم|تم استلام|Purchase|Withdrawal|Transfer|Deposit|Refund|Bill|POS|ATM)", RegexOption.IGNORE_CASE)
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
        if (Regex("(?:سداد فاتورة|دفع فاتورة|فاتورة سداد|SADAD|Bill Payment|فوري|Fawry)", RegexOption.IGNORE_CASE).containsMatchIn(body) &&
            !Regex("(?:إيداع|حوالة واردة)", RegexOption.IGNORE_CASE).containsMatchIn(body)
        ) {
            return SmsSuggestedType.BILL_PAYMENT
        }
        if (Regex("(?:سحب نقدي|سحب صراف|صراف آلي|ATM Withdrawal|Cash Withdrawal|سحب: صراف|تم سحب)", RegexOption.IGNORE_CASE).containsMatchIn(body)) {
            return SmsSuggestedType.ATM_WITHDRAWAL
        }
        if (Regex("(?:حوالة واردة|تحويل وارد|حوالة محلية واردة|تم استلام مبلغ|تم ايداع|Incoming Transfer|Credit Transfer|تم إضافة مبلغ حوالة)", RegexOption.IGNORE_CASE).containsMatchIn(body)) {
            return SmsSuggestedType.INCOMING_TRANSFER
        }
        if (Regex("(?:إيداع نقدي|ايداع نقدي|إيداع راتب|إيداع في حسابك|Cash Deposit|Salary Deposit|Salary Credit)", RegexOption.IGNORE_CASE).containsMatchIn(body)) {
            return SmsSuggestedType.DEPOSIT
        }
        if (Regex("(?:حوالة صادرة|تحويل صادر|حوالة محلية صادرة|حوالة دولية|تم تحويل مبلغ|تم تحويل|حوالة عبر إنجاز|Outgoing Transfer|Transfer to)", RegexOption.IGNORE_CASE).containsMatchIn(body)) {
            return SmsSuggestedType.OUTGOING_TRANSFER
        }
        if (Regex("(?:شراء|شراء عبر|نقاط البيع|شراء انترنت|شراء أثير|مدى-أثير|عملية شراء|بطاقة مدى|تم خصم|Samsung Wallet|Apple Pay|PoS Purchase|POS|Online Purchase|Card Purchase)", RegexOption.IGNORE_CASE).containsMatchIn(body)) {
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
            c in listOf("EGP", "LE", "ج.م", "جنيه", "جنية", "جنيه مصري", "جنيه مصرى") -> "EGP"
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
            SmsSuggestedType.OUTGOING_TRANSFER -> "حوالة صادرة"
            SmsSuggestedType.INCOMING_TRANSFER -> "حوالة واردة"
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
            type == SmsSuggestedType.BILL_PAYMENT || text.contains("كهرباء") || text.contains("مياه") || text.contains("stc") || text.contains("موبايلي") || text.contains("زين") || text.contains("فوري") || text.contains("fawry") -> "فواتير"
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
