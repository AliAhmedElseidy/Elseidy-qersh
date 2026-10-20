package com.example.domain

import java.math.BigDecimal
import java.math.RoundingMode
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Calendar
import java.util.Date
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * أدوات الحسابات المالية الدقيقة باستخدام BigDecimal ووحدات العملة الصغرى (الهللة / القرش = 1/100).
 * يمنع استخدام Float أو Double كمصدر وحيد للمبالغ المالية لضمان الدقة التامة.
 */
object MoneyUtils {
    private val HUNDRED = BigDecimal("100")
    private val secureRandom = SecureRandom()

    private val ARABIC_MONTH_NAMES = arrayOf(
        "يناير",
        "فبراير",
        "مارس",
        "أبريل",
        "مايو",
        "يونيو",
        "يوليو",
        "أغسطس",
        "سبتمبر",
        "أكتوبر",
        "نوفمبر",
        "ديسمبر"
    )

    fun normalizeDigits(input: String): String {
        val sb = StringBuilder(input.length)
        for (ch in input) {
            when (ch) {
                '٠', '۰' -> sb.append('0')
                '١', '۱' -> sb.append('1')
                '٢', '۲' -> sb.append('2')
                '٣', '۳' -> sb.append('3')
                '٤', '۴' -> sb.append('4')
                '٥', '۵' -> sb.append('5')
                '٦', '۶' -> sb.append('6')
                '٧', '۷' -> sb.append('7')
                '٨', '۸' -> sb.append('8')
                '٩', '۹' -> sb.append('9')
                '٫' -> sb.append('.')
                '٬', ',' -> {
                    // Ignore thousands separator commas
                }
                else -> sb.append(ch)
            }
        }
        return sb.toString().trim()
    }

    /**
     * يحول نص المبلغ (مثل "1,250.75" أو "١٢٥٠٫٥٠" أو "10") إلى BigDecimal بدقة خانتين عشريتين.
     */
    fun parseToBigDecimal(rawText: String): BigDecimal? {
        val cleaned = normalizeDigits(rawText)
            .replace(Regex("[^0-9.-]"), "")
            .trim()
        if (cleaned.isEmpty() || cleaned == "-" || cleaned == ".") return null
        return try {
            BigDecimal(cleaned).setScale(2, RoundingMode.HALF_UP)
        } catch (e: NumberFormatException) {
            null
        }
    }

    /**
     * يحول BigDecimal إلى وحدات صغرى (قرش / هللة) كعدد صحيح Long.
     */
    fun toMinorUnits(amount: BigDecimal): Long {
        return amount.setScale(2, RoundingMode.HALF_UP)
            .multiply(HUNDRED)
            .longValueExact()
    }

    /**
     * يحول نص المبلغ مباشرة إلى وحدات صغرى (قرش / هللة).
     */
    fun parseToMinorUnits(rawText: String): Long? {
        val bd = parseToBigDecimal(rawText) ?: return null
        return toMinorUnits(bd)
    }

    /**
     * يحول الوحدات الصغرى (Long) إلى BigDecimal بدقة خانتين عشريتين.
     */
    fun fromMinorUnits(minorUnits: Long): BigDecimal {
        return BigDecimal.valueOf(minorUnits).divide(HUNDRED, 2, RoundingMode.HALF_UP)
    }

    /**
     * يعيد رمز العملة العربي المناسب للعرض.
     */
    fun currencySymbol(currencyCode: String): String {
        return when (currencyCode.uppercase(Locale.ROOT)) {
            "SAR", "SR", "ر.س", "ريال", "ريال سعودي", "ريال سعودى" -> "ر.س"
            "EGP", "LE", "ج.م", "جنيه", "جنيه مصري", "جنيه مصرى" -> "ج.م"
            "AED", "د.إ" -> "د.إ"
            "KWD", "د.ك" -> "د.ك"
            "USD", "$" -> "$"
            "EUR", "€" -> "€"
            else -> currencyCode
        }
    }

    /**
     * تنسيق المبلغ المالي للعرض بوضوح دون أخطاء الفاصلة العائمة.
     */
    fun formatMinor(minorUnits: Long, currencyCode: String = "SAR", showSign: Boolean = false): String {
        val bd = fromMinorUnits(minorUnits)
        val symbols = DecimalFormatSymbols(Locale.US)
        val formatter = if (bd.remainder(BigDecimal.ONE).compareTo(BigDecimal.ZERO) == 0) {
            DecimalFormat("#,##0", symbols)
        } else {
            DecimalFormat("#,##0.00", symbols)
        }
        val absFormatted = formatter.format(bd.abs())
        val signPrefix = when {
            minorUnits < 0 -> "-"
            showSign && minorUnits > 0 -> "+"
            else -> ""
        }
        return "$signPrefix$absFormatted ${currencySymbol(currencyCode)}"
    }

    /**
     * تنسيق المبلغ المالي بخانتين عشريتين دائماً (مثال: "10.00 ر.س") لعرضه في هيدر كارت رسائل البنك.
     */
    fun formatMinorTwoDecimals(minorUnits: Long, currencyCode: String = "SAR"): String {
        val bd = fromMinorUnits(minorUnits)
        val symbols = DecimalFormatSymbols(Locale.US)
        val formatter = DecimalFormat("#,##0.00", symbols)
        return "${formatter.format(bd.abs())} ${currencySymbol(currencyCode)}"
    }

    fun formatPlainDecimal(minorUnits: Long): String {
        val bd = fromMinorUnits(minorUnits)
        return if (bd.remainder(BigDecimal.ONE).compareTo(BigDecimal.ZERO) == 0) {
            bd.toBigInteger().toString()
        } else {
            bd.toPlainString()
        }
    }

    /**
     * إنشاء بصمة ثابتة SHA-256 للرسالة النصية بعد تطبيع المسافات والأرقام لمنع التكرار.
     */
    fun computeMessageHash(sender: String, body: String): String {
        val normalizedSender = sender.trim().uppercase(Locale.ROOT)
        val normalizedBody = normalizeDigits(body)
            .replace(Regex("\\s+"), " ")
            .trim()
            .lowercase(Locale.ROOT)
        val payload = "$normalizedSender|$normalizedBody"
        val digest = MessageDigest.getInstance("SHA-256").digest(payload.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun currentYearMonth(timeMillis: Long = System.currentTimeMillis()): String {
        val cal = Calendar.getInstance().apply { this.timeInMillis = timeMillis }
        val year = cal.get(Calendar.YEAR)
        val month = cal.get(Calendar.MONTH) + 1
        return String.format(Locale.US, "%04d-%02d", year, month)
    }

    fun previousYearMonth(yearMonth: String): String {
        val parts = yearMonth.split("-")
        if (parts.size != 2) return currentYearMonth()
        var year = parts[0].toIntOrNull() ?: 2026
        var month = parts[1].toIntOrNull() ?: 1
        month -= 1
        if (month < 1) {
            month = 12
            year -= 1
        }
        return String.format(Locale.US, "%04d-%02d", year, month)
    }

    fun nextYearMonth(yearMonth: String): String {
        val parts = yearMonth.split("-")
        if (parts.size != 2) return currentYearMonth()
        var year = parts[0].toIntOrNull() ?: 2026
        var month = parts[1].toIntOrNull() ?: 1
        month += 1
        if (month > 12) {
            month = 1
            year += 1
        }
        return String.format(Locale.US, "%04d-%02d", year, month)
    }

    /**
     * يحول صيغة الشهر "2026-10" إلى اسم الشهر العربي والسنة مثل "أكتوبر 2026".
     */
    fun formatArabicMonthYear(yearMonth: String): String {
        val parts = yearMonth.split("-")
        if (parts.size != 2) return yearMonth
        val year = parts[0].toIntOrNull() ?: 2026
        val monthIndex = (parts[1].toIntOrNull() ?: 1).coerceIn(1, 12) - 1
        return "${ARABIC_MONTH_NAMES[monthIndex]} $year"
    }

    fun formatYearMonthArabic(yearMonth: String): String = formatArabicMonthYear(yearMonth)

    fun monthRangeMillis(yearMonth: String): Pair<Long, Long> {
        val parts = yearMonth.split("-")
        val year = parts.getOrNull(0)?.toIntOrNull() ?: 2026
        val month = (parts.getOrNull(1)?.toIntOrNull() ?: 1) - 1
        val startCal = Calendar.getInstance().apply {
            set(Calendar.YEAR, year)
            set(Calendar.MONTH, month)
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val endCal = (startCal.clone() as Calendar).apply {
            add(Calendar.MONTH, 1)
            add(Calendar.MILLISECOND, -1)
        }
        return startCal.timeInMillis to endCal.timeInMillis
    }

    fun isTimestampInYearMonth(timestamp: Long, yearMonth: String): Boolean {
        val (start, end) = monthRangeMillis(yearMonth)
        return timestamp in start..end
    }

    fun todayRangeMillis(nowMillis: Long = System.currentTimeMillis()): Pair<Long, Long> {
        val startCal = Calendar.getInstance().apply {
            timeInMillis = nowMillis
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val endCal = (startCal.clone() as Calendar).apply {
            add(Calendar.DAY_OF_MONTH, 1)
            add(Calendar.MILLISECOND, -1)
        }
        return startCal.timeInMillis to endCal.timeInMillis
    }

    fun formatArabicDate(timeMillis: Long): String {
        val sdf = SimpleDateFormat("yyyy/MM/dd - hh:mm a", Locale.forLanguageTag("ar"))
        return sdf.format(Date(timeMillis))
    }

    fun formatShortDate(timeMillis: Long): String {
        val sdf = SimpleDateFormat("yyyy/MM/dd", Locale.US)
        return sdf.format(Date(timeMillis))
    }

    fun formatIsoDate(timeMillis: Long = System.currentTimeMillis()): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        return sdf.format(Date(timeMillis))
    }

    /**
     * يتحقق مما إذا كان محتوى النسخة الاحتياطية مشفراً (سواء بالترويسة الحديثة JSON/SQENC2 أو القديمة SQENC1).
     */
    fun isEncryptedBackupPayload(payload: String): Boolean {
        val trimmed = payload.trim()
        if (trimmed.startsWith("SQENC2:") || trimmed.startsWith("SQENC1:")) return true
        if (trimmed.startsWith("{")) {
            return Regex(""""encrypted"\s*:\s*true""").containsMatchIn(trimmed) &&
                Regex(""""ciphertext"\s*:\s*"[^"]+"""").containsMatchIn(trimmed)
        }
        return false
    }

    private fun extractJsonStringField(json: String, field: String): String? {
        val m = Regex(""""$field"\s*:\s*"([^"]+)"""").find(json)
        return m?.groupValues?.getOrNull(1)
    }

    private fun extractJsonIntField(json: String, field: String): Int? {
        val m = Regex(""""$field"\s*:\s*(\d+)""").find(json)
        return m?.groupValues?.getOrNull(1)?.toIntOrNull()
    }

    /**
     * تشفير النسخة الاحتياطية محليًا باستخدام AES-GCM مع توليد Salt (16 بايت) و IV (12 بايت)
     * عشوائيين عبر SecureRandom لكل عملية تصدير وحفظهما داخل ترويسة JSON آمنة.
     */
    fun encryptBackupPayload(plainJson: String, passphrase: String): String {
        val salt = ByteArray(16).also { secureRandom.nextBytes(it) }
        val iv = ByteArray(12).also { secureRandom.nextBytes(it) }

        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = PBEKeySpec(passphrase.toCharArray(), salt, 15000, 256)
        val secretKey = SecretKeySpec(factory.generateSecret(spec).encoded, "AES")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, GCMParameterSpec(128, iv))
        val encrypted = cipher.doFinal(plainJson.toByteArray(Charsets.UTF_8))

        val encoder = Base64.getEncoder()
        val saltB64 = encoder.encodeToString(salt)
        val ivB64 = encoder.encodeToString(iv)
        val cipherB64 = encoder.encodeToString(encrypted)

        return """{"app":"SaidiQersh","encrypted":true,"kdf":"PBKDF2WithHmacSHA256","iterations":15000,"cipher":"AES/GCM/NoPadding","version":2,"salt":"$saltB64","iv":"$ivB64","ciphertext":"$cipherB64"}"""
    }

    /**
     * فك تشفير النسخة الاحتياطية مع دعم:
     * 1) ترويسة JSON الحديثة التي تحتوي على SecureRandom salt & iv.
     * 2) صيغة SQENC2:<salt>:<iv>:<ciphertext>.
     * 3) التوافق العكسي مع صيغة SQENC1 القديمة.
     */
    fun decryptBackupPayload(encryptedPayload: String, passphrase: String): String {
        val trimmed = encryptedPayload.trim()
        val decoder = Base64.getMimeDecoder()

        // 1. صيغة ترويسة JSON الحديثة (Version 2)
        if (trimmed.startsWith("{") && isEncryptedBackupPayload(trimmed)) {
            val saltB64 = extractJsonStringField(trimmed, "salt") ?: error("Salt مفقود في ترويسة النسخة")
            val ivB64 = extractJsonStringField(trimmed, "iv") ?: error("IV مفقود في ترويسة النسخة")
            val cipherB64 = extractJsonStringField(trimmed, "ciphertext") ?: error("النص المشفر مفقود")
            val iterations = extractJsonIntField(trimmed, "iterations") ?: 15000
            val salt = decoder.decode(saltB64)
            val iv = decoder.decode(ivB64)
            val encryptedBytes = decoder.decode(cipherB64)
            return decryptAesGcmWithParams(encryptedBytes, passphrase, salt, iv, iterations)
        }

        // 2. صيغة SQENC2:<saltB64>:<ivB64>:<cipherB64>
        if (trimmed.startsWith("SQENC2:")) {
            val parts = trimmed.removePrefix("SQENC2:").split(":")
            require(parts.size == 3) { "ترويسة النسخة المشفرة غير صالحة" }
            val salt = decoder.decode(parts[0])
            val iv = decoder.decode(parts[1])
            val encryptedBytes = decoder.decode(parts[2])
            return decryptAesGcmWithParams(encryptedBytes, passphrase, salt, iv, 15000)
        }

        // 3. التوافق العكسي مع النسخ القديمة SQENC1:
        val rawBase64 = trimmed.removePrefix("SQENC1:").trim()
        val encryptedBytes = decoder.decode(rawBase64)
        val legacySalt = ByteArray(16) { (it * 7 + 13).toByte() }
        val legacyIv = ByteArray(12) { (it * 11 + 5).toByte() }
        return decryptAesGcmWithParams(encryptedBytes, passphrase, legacySalt, legacyIv, 10000)
    }

    fun daysRemainingInYearMonth(yearMonth: String, nowMillis: Long = System.currentTimeMillis()): Int {
        val currentYm = currentYearMonth(nowMillis)
        val parts = yearMonth.split("-")
        val targetYear = parts.getOrNull(0)?.toIntOrNull() ?: 2026
        val targetMonth = (parts.getOrNull(1)?.toIntOrNull() ?: 1).coerceIn(1, 12) - 1

        val targetCal = Calendar.getInstance().apply {
            set(Calendar.YEAR, targetYear)
            set(Calendar.MONTH, targetMonth)
            set(Calendar.DAY_OF_MONTH, 1)
        }
        val maxDay = targetCal.getActualMaximum(Calendar.DAY_OF_MONTH).coerceAtLeast(28)

        return when {
            yearMonth == currentYm -> {
                val nowCal = Calendar.getInstance().apply { timeInMillis = nowMillis }
                val currentDay = nowCal.get(Calendar.DAY_OF_MONTH).coerceAtLeast(1)
                (maxDay - currentDay + 1).coerceAtLeast(1)
            }
            yearMonth < currentYm -> 1
            else -> maxDay
        }
    }

    /**
     * تشفير رمز القفل السري (PIN) باستخدام PBKDF2WithHmacSHA256 مع Salt عشوائي (SecureRandom)
     * لمنع حفظ الرمز السري كنص صريح.
     */
    fun hashPinCode(pin: String): String {
        val clean = normalizeDigits(pin).trim()
        val salt = ByteArray(16).also { secureRandom.nextBytes(it) }
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = PBEKeySpec(clean.toCharArray(), salt, 12000, 256)
        val hash = factory.generateSecret(spec).encoded
        val saltHex = salt.joinToString("") { "%02x".format(it) }
        val hashHex = hash.joinToString("") { "%02x".format(it) }
        return "PINH1:$saltHex:$hashHex"
    }

    fun verifyPinCode(enteredPin: String, storedValue: String?): Boolean {
        if (storedValue.isNullOrBlank()) return true
        val cleanEntered = normalizeDigits(enteredPin).trim()
        if (!storedValue.startsWith("PINH1:")) {
            return storedValue == cleanEntered || storedValue == enteredPin
        }
        val parts = storedValue.removePrefix("PINH1:").split(":")
        if (parts.size != 2) return false
        return try {
            val salt = parts[0].chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            val expectedHash = parts[1].chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            val spec = PBEKeySpec(cleanEntered.toCharArray(), salt, 12000, 256)
            val actualHash = factory.generateSecret(spec).encoded
            MessageDigest.isEqual(expectedHash, actualHash)
        } catch (_: Exception) {
            false
        }
    }

    private fun decryptAesGcmWithParams(
        encryptedBytes: ByteArray,
        passphrase: String,
        salt: ByteArray,
        iv: ByteArray,
        iterations: Int
    ): String {
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = PBEKeySpec(passphrase.toCharArray(), salt, iterations, 256)
        val secretKey = SecretKeySpec(factory.generateSecret(spec).encoded, "AES")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(128, iv))
        val plainBytes = cipher.doFinal(encryptedBytes)
        return String(plainBytes, Charsets.UTF_8)
    }
}

