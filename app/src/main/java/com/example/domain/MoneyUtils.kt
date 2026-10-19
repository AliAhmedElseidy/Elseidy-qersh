package com.example.domain

import java.math.BigDecimal
import java.math.RoundingMode
import java.security.MessageDigest
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import android.util.Base64

/**
 * أدوات الحسابات المالية الدقيقة باستخدام BigDecimal ووحدات العملة الصغرى (الهللة / القرش = 1/100).
 * يمنع استخدام Float أو Double كمصدر وحيد للمبالغ المالية لضمان الدقة التامة.
 */
object MoneyUtils {
    private val HUNDRED = BigDecimal("100")

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
     * يحول نص المبلغ (مثل "1,250.75" أو "١٢٥٠٫٥٠") إلى BigDecimal بدقة خانتين عشريتين.
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
            "SAR", "ر.س", "ريال" -> "ر.س"
            "EGP", "ج.م", "جنيه" -> "ج.م"
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
        val sdf = SimpleDateFormat("yyyy/MM/dd - hh:mm a", Locale("ar"))
        return sdf.format(Date(timeMillis))
    }

    fun formatShortDate(timeMillis: Long): String {
        val sdf = SimpleDateFormat("yyyy/MM/dd", Locale.US)
        return sdf.format(Date(timeMillis))
    }

    /**
     * تشفير وفك تشفير النسخة الاحتياطية محليًا باستخدام AES-GCM ومفتاح مشتق من كلمة مرور المستخدم.
     */
    fun encryptBackupPayload(plainJson: String, passphrase: String): String {
        val salt = ByteArray(16) { (it * 7 + 13).toByte() }
        val iv = ByteArray(12) { (it * 11 + 5).toByte() }
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = PBEKeySpec(passphrase.toCharArray(), salt, 10000, 256)
        val secretKey = SecretKeySpec(factory.generateSecret(spec).encoded, "AES")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, GCMParameterSpec(128, iv))
        val encrypted = cipher.doFinal(plainJson.toByteArray(Charsets.UTF_8))
        return "SQENC1:" + Base64.encodeToString(encrypted, Base64.NO_WRAP)
    }

    fun decryptBackupPayload(encryptedPayload: String, passphrase: String): String {
        val rawBase64 = encryptedPayload.removePrefix("SQENC1:").trim()
        val encryptedBytes = Base64.decode(rawBase64, Base64.NO_WRAP)
        val salt = ByteArray(16) { (it * 7 + 13).toByte() }
        val iv = ByteArray(12) { (it * 11 + 5).toByte() }
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = PBEKeySpec(passphrase.toCharArray(), salt, 10000, 256)
        val secretKey = SecretKeySpec(factory.generateSecret(spec).encoded, "AES")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(128, iv))
        val plainBytes = cipher.doFinal(encryptedBytes)
        return String(plainBytes, Charsets.UTF_8)
    }
}
