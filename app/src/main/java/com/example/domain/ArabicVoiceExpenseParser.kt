package com.example.domain

import com.example.data.local.CategoryEntity
import java.util.Locale

data class VoiceExpenseProposal(
    val rawTranscript: String,
    val amountMinor: Long?,
    val currency: String,
    val description: String,
    val suggestedCategoryId: Long?,
    val suggestedCategoryName: String,
    val isReadyToConfirm: Boolean
)

/**
 * محلل محلي للإملاء الصوتي بالعربية، مثل:
 * «دفعت خمسة وثلاثين ريال بنزين» أو «صرفت مائة وعشرين جنيه فاكهة ومقاضي» أو «دفعت 45 ريال قهوة».
 */
object ArabicVoiceExpenseParser {

    private val WORD_NUMBERS = mapOf(
        "ريال" to 1L,
        "ريالين" to 2L,
        "جنيه" to 1L,
        "جنيهين" to 2L,
        "واحد" to 1L,
        "واحدة" to 1L,
        "اثنين" to 2L,
        "إثنين" to 2L,
        "اثنان" to 2L,
        "ثلاثة" to 3L,
        "ثلاث" to 3L,
        "تلاتة" to 3L,
        "أربعة" to 4L,
        "اربعة" to 4L,
        "أربع" to 4L,
        "اربع" to 4L,
        "خمسة" to 5L,
        "خمس" to 5L,
        "ستة" to 6L,
        "ست" to 6L,
        "سبعة" to 7L,
        "سبع" to 7L,
        "ثمانية" to 8L,
        "ثمان" to 8L,
        "تمانية" to 8L,
        "تسعة" to 9L,
        "تسع" to 9L,
        "عشرة" to 10L,
        "عشر" to 10L,
        "أحد عشر" to 11L,
        "احدعشر" to 11L,
        "حداشر" to 11L,
        "اثنا عشر" to 12L,
        "اتناشر" to 12L,
        "ثلاثة عشر" to 13L,
        "تلتاشر" to 13L,
        "أربعة عشر" to 14L,
        "اربعتاشر" to 14L,
        "خمسة عشر" to 15L,
        "خمستاشر" to 15L,
        "ستة عشر" to 16L,
        "ستاشر" to 16L,
        "سبعة عشر" to 17L,
        "سبعتاشر" to 17L,
        "ثمانية عشر" to 18L,
        "تمنتاشر" to 18L,
        "تسعة عشر" to 19L,
        "تسعتاشر" to 19L,
        "عشرين" to 20L,
        "عشرون" to 20L,
        "ثلاثين" to 30L,
        "ثلاثون" to 30L,
        "تلاتين" to 30L,
        "أربعين" to 40L,
        "اربعين" to 40L,
        "أربعون" to 40L,
        "خمسين" to 50L,
        "خمسون" to 50L,
        "ستين" to 60L,
        "ستون" to 60L,
        "سبعين" to 70L,
        "سبعون" to 70L,
        "ثمانين" to 80L,
        "ثمانون" to 80L,
        "تمانين" to 80L,
        "تسعين" to 90L,
        "تسعون" to 90L,
        "مائة" to 100L,
        "مئة" to 100L,
        "مية" to 100L,
        "مائتين" to 200L,
        "مئتين" to 200L,
        "ميتين" to 200L,
        "ثلاثمائة" to 300L,
        "تلتمية" to 300L,
        "أربعمائة" to 400L,
        "اربعمية" to 400L,
        "خمسمائة" to 500L,
        "خمسمية" to 500L,
        "ستمائة" to 600L,
        "ستمية" to 600L,
        "سبعمائة" to 700L,
        "سبعمية" to 700L,
        "ثمانمائة" to 800L,
        "تمنمية" to 800L,
        "تسعمائة" to 900L,
        "تسعمية" to 900L,
        "ألف" to 1000L,
        "الف" to 1000L,
        "ألفين" to 2000L,
        "الفين" to 2000L
    )

    fun parseSpokenPhrase(
        phrase: String,
        defaultCurrency: String = "SAR",
        categories: List<CategoryEntity> = emptyList()
    ): VoiceExpenseProposal {
        val normalized = MoneyUtils.normalizeDigits(phrase).trim()
        val currency = when {
            normalized.contains("جنيه") || normalized.contains("مصري") -> "EGP"
            normalized.contains("درهم") -> "AED"
            normalized.contains("دولار") -> "USD"
            normalized.contains("ريال") -> "SAR"
            else -> defaultCurrency
        }

        val numericMatch = Regex("([0-9]+(?:\\.[0-9]{1,2})?)").find(normalized)
        val amountMinorFromDigits = numericMatch?.groupValues?.get(1)?.let { MoneyUtils.parseToMinorUnits(it) }

        val amountMinor = amountMinorFromDigits ?: extractSpokenArabicNumberMinor(normalized)
        val description = extractCleanDescription(normalized)
        val (catId, catName) = matchCategory(description.ifBlank { normalized }, categories)

        return VoiceExpenseProposal(
            rawTranscript = phrase.trim(),
            amountMinor = amountMinor,
            currency = currency,
            description = description.ifBlank { "مصروف صوتي" },
            suggestedCategoryId = catId,
            suggestedCategoryName = catName,
            isReadyToConfirm = amountMinor != null && amountMinor > 0L
        )
    }

    private fun extractSpokenArabicNumberMinor(text: String): Long? {
        val tokens = text
            .replace("،", " ")
            .split(Regex("\\s+"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        var totalUnits = 0L
        var matchedAny = false

        for (rawToken in tokens) {
            val tokenWithoutWaw = if (rawToken.startsWith("و") && rawToken.length > 2) {
                rawToken.substring(1)
            } else {
                rawToken
            }
            val directVal = WORD_NUMBERS[rawToken] ?: WORD_NUMBERS[tokenWithoutWaw]
            if (directVal != null && rawToken != "ريال" && rawToken != "جنيه") {
                totalUnits += directVal
                matchedAny = true
            }
        }

        if (!matchedAny) {
            if (tokens.contains("ريالين") || tokens.contains("جنيهين")) {
                return 200L
            }
            return null
        }

        val hasHalf = tokens.any { it == "نصف" || it == "ونصف" || it == "نص" || it == "ونص" }
        return totalUnits * 100L + if (hasHalf) 50L else 0L
    }

    private fun extractCleanDescription(text: String): String {
        val stopWords = setOf(
            "دفعت", "صرفت", "اشتريت", "سددت", "حولت", "مبلغ",
            "ريال", "ريالات", "ريالين", "جنيه", "جنيهات", "جنيهين", "درهم", "دولار",
            "سعودي", "سعودى", "مصري", "مصرى", "نصف", "ونصف", "نص", "ونص", "في", "على", "من", "اليوم"
        )
        val tokens = text
            .replace(Regex("[0-9]+(?:\\.[0-9]+)?"), " ")
            .split(Regex("\\s+"))
            .map { it.trim() }
            .filter { token ->
                if (token.isEmpty()) return@filter false
                val withoutWaw = if (token.startsWith("و") && token.length > 2) token.substring(1) else token
                token !in stopWords &&
                    withoutWaw !in stopWords &&
                    !WORD_NUMBERS.containsKey(token) &&
                    !WORD_NUMBERS.containsKey(withoutWaw)
            }
        return tokens.joinToString(" ").trim()
    }

    private fun matchCategory(text: String, categories: List<CategoryEntity>): Pair<Long?, String> {
        val lower = text.lowercase(Locale.ROOT)
        val targetName = when {
            lower.contains("بنزين") || lower.contains("وقود") -> "بنزين"
            lower.contains("تاكسي") || lower.contains("أوبر") || lower.contains("مواصلات") || lower.contains("سيارة") || lower.contains("كريم") -> "مواصلات"
            lower.contains("خبز") || lower.contains("مقاضي") || lower.contains("خضار") || lower.contains("فاكهة") || lower.contains("سوبرماركت") || lower.contains("بقالة") || lower.contains("حليب") || lower.contains("كارفور") -> "بقالة"
            lower.contains("قهوة") || lower.contains("عشاء") || lower.contains("غداء") || lower.contains("فطور") || lower.contains("مطعم") || lower.contains("كافيه") || lower.contains("دانكن") || lower.contains("أكل") -> "أكل بره"
            lower.contains("فاتورة") || lower.contains("كهرباء") || lower.contains("نت") || lower.contains("شحن") || lower.contains("جوال") -> "فواتير"
            lower.contains("دواء") || lower.contains("صيدلية") || lower.contains("طبيب") || lower.contains("علاج") -> "صحة"
            lower.contains("إيجار") || lower.contains("ايجار") || lower.contains("صيانة") -> "إيجار"
            lower.contains("قسط") -> "أقساط"
            lower.contains("جمعية") -> "جمعيات"
            else -> "تسوق"
        }
        val cat = categories.firstOrNull { it.nameAr == targetName }
            ?: categories.firstOrNull { it.nameAr.contains(targetName.take(4)) }
        return cat?.id to (cat?.nameAr ?: targetName)
    }
}
