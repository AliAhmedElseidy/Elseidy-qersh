package com.example.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import com.example.domain.MoneyUtils
import java.math.BigDecimal

enum class AccountType(val titleAr: String) {
    CASH("حساب نقدي (كاش)"),
    BANK("حساب بنكي"),
    CREDIT_CARD("بطاقة ائتمان"),
    E_WALLET("محفظة إلكترونية")
}

enum class CategoryType(val titleAr: String) {
    EXPENSE("مصروفات"),
    INCOME("دخل"),
    BOTH("مشترك")
}

enum class TransactionType(val titleAr: String) {
    EXPENSE("مصروف"),
    INCOME("دخل"),
    REFUND("استرداد مالي"),
    TRANSFER("تحويل بين الحسابات"),
    CREDIT_CARD_PAYMENT("سداد بطاقة ائتمان"),
    OBLIGATION_PAYMENT("سداد التزام/قسط")
}

enum class TransactionSource(val titleAr: String) {
    MANUAL("إدخال يدوي"),
    SMS_CONFIRMED("رسالة بنكية مؤكدة"),
    VOICE("إملاء صوتي"),
    RECEIPT_SCAN("تصوير إيصال"),
    SHOPPING_LIST("قائمة مشتريات البيت"),
    OBLIGATION("التزام/قسط"),
    RECONCILIATION("تسوية رصيد")
}

enum class SmsSuggestedType(val titleAr: String, val isFinancialTransaction: Boolean) {
    CARD_PURCHASE("شراء بالبطاقة", true),
    ATM_WITHDRAWAL("سحب نقدي", true),
    OUTGOING_TRANSFER("تحويل صادر", true),
    INCOMING_TRANSFER("تحويل وارد", true),
    DEPOSIT("إيداع", true),
    REFUND("استرداد مبلغ", true),
    CREDIT_CARD_SETTLEMENT("سداد بطاقة ائتمان", true),
    BILL_PAYMENT("دفع فاتورة", true),
    BALANCE_NOTICE_ONLY("إشعار غير مالي / رصيد فقط", false),
    UNCLEAR("رسالة غير واضحة تحتاج تدقيق", false)
}

enum class ReviewStatus(val titleAr: String) {
    PENDING("بانتظار المراجعة"),
    UNCLEAR("غير واضحة"),
    CONFIRMED("مؤكدة ومسجلة"),
    IGNORED("تم التجاهل"),
    DUPLICATE("رسالة مكررة")
}

enum class ObligationKind(val titleAr: String) {
    BILL_COMMITMENT("التزام / فاتورة دورية"),
    INSTALLMENT_PLAN("قسط شهري"),
    GAM3EYA_ROSCA("جمعية ادخارية")
}

class RoomTypeConverters {
    @TypeConverter
    fun fromBigDecimal(value: BigDecimal?): Long? = value?.let { MoneyUtils.toMinorUnits(it) }

    @TypeConverter
    fun toBigDecimal(value: Long?): BigDecimal? = value?.let { MoneyUtils.fromMinorUnits(it) }
}

/**
 * 1. كيان جهات الرسائل والبنوك المدعومة والمكتشفة ديناميكياً:
 * - smsEnabled (Toggle 1): قراءة رسائل هذه الجهة (مفعّل / معطّل)
 * - autoApproveEnabled (Toggle 2 - ميزة مستقلة): اعتماد وتسجيل تلقائي فوري (Auto-Approve)
 */
@Entity(
    tableName = "banks",
    indices = [Index(value = ["code"], unique = true)]
)
data class BankEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val nameAr: String,
    val nameEn: String,
    val code: String,
    val country: String = "SA",
    val defaultCurrency: String = "SAR",
    val senderIdsCsv: String,
    val smsEnabled: Boolean = true,
    val autoApproveEnabled: Boolean = false
)

/**
 * 2. كيان قواعد رسائل البنوك القابلة للتوسعة
 */
@Entity(
    tableName = "sms_rules",
    indices = [Index(value = ["bankId"])]
)
data class SmsRuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bankId: Long,
    val ruleName: String,
    val keywordOrRegex: String,
    val suggestedType: SmsSuggestedType,
    val isActive: Boolean = true,
    val priority: Int = 10
)

/**
 * 3. كيان الحسابات المالية مع دعم مطابقة آخر 4 أرقام من الحساب أو البطاقة تلقائيًا
 */
@Entity(
    tableName = "accounts",
    indices = [Index(value = ["bankId"])]
)
data class AccountEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val type: AccountType,
    val bankId: Long? = null,
    val bankName: String = "",
    val currency: String = "SAR",
    val openingBalanceMinor: Long = 0L,
    val currentBalanceMinor: Long = 0L,
    val creditLimitMinor: Long = 0L,
    val accountLast4: String = "",
    val cardLast4: String = "",
    val smsParsingEnabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis()
) {
    val currentBalanceDecimal: BigDecimal
        get() = MoneyUtils.fromMinorUnits(currentBalanceMinor)
}

/**
 * 4. كيان التصنيفات القابلة للتعديل مع أيقونة تعبيرية ملونة لشبكة التصنيفات (4 أعمدة)
 */
@Entity(
    tableName = "categories",
    indices = [Index(value = ["nameAr", "type"], unique = true)]
)
data class CategoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val nameAr: String,
    val type: CategoryType,
    val iconName: String = "🛒",
    val colorHex: String = "#0D4732",
    val isDefault: Boolean = false,
    val isArchived: Boolean = false
)

/**
 * 5. كيان العمليات المالية المؤكدة فقط
 */
@Entity(
    tableName = "transactions",
    indices = [
        Index(value = ["accountId"]),
        Index(value = ["toAccountId"]),
        Index(value = ["categoryId"]),
        Index(value = ["timestamp"]),
        Index(value = ["type"])
    ]
)
data class TransactionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: TransactionType,
    val amountMinor: Long,
    val currency: String = "SAR",
    val description: String,
    val notes: String = "",
    val timestamp: Long = System.currentTimeMillis(),
    val accountId: Long,
    val toAccountId: Long? = null,
    val categoryId: Long? = null,
    val bankId: Long? = null,
    val bankName: String = "",
    val merchantName: String = "",
    val referenceNumber: String? = null,
    val source: TransactionSource = TransactionSource.MANUAL,
    val relatedTransactionId: Long? = null,
    val obligationId: Long? = null,
    val isMonthlyObligationPayment: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    val amountDecimal: BigDecimal
        get() = MoneyUtils.fromMinorUnits(amountMinor)
}

/**
 * 6. كيان العمليات المقترحة المستخرجة من رسائل SMS البنكية
 */
@Entity(
    tableName = "suggested_transactions",
    indices = [
        Index(value = ["messageHash"], unique = true),
        Index(value = ["referenceNumber"]),
        Index(value = ["status"])
    ]
)
data class SuggestedTransactionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val messageHash: String,
    val referenceNumber: String? = null,
    val senderAddress: String,
    val bankId: Long? = null,
    val bankName: String,
    val sanitizedSnippet: String,
    val extractedAmountMinor: Long? = null,
    val currency: String = "SAR",
    val merchantName: String = "",
    val suggestedType: SmsSuggestedType,
    val suggestedCategoryId: Long? = null,
    val suggestedCategoryName: String = "أخرى",
    val suggestedAccountId: Long? = null,
    val extractedAccountLast4: String = "",
    val extractedCardLast4: String = "",
    val status: ReviewStatus = ReviewStatus.PENDING,
    val confirmedTransactionId: Long? = null,
    val isTestSandbox: Boolean = false,
    val smsTimestamp: Long = System.currentTimeMillis(),
    val importedAt: Long = System.currentTimeMillis(),
    val reviewedAt: Long? = null
)

/**
 * 7. كيان الميزانيات الشهرية
 */
@Entity(
    tableName = "budgets",
    indices = [Index(value = ["yearMonth", "categoryKey"], unique = true)]
)
data class BudgetEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val yearMonth: String,
    val categoryKey: String = "TOTAL",
    val categoryId: Long? = null,
    val categoryName: String = "الميزانية الشهرية الإجمالية",
    val limitMinor: Long,
    val currency: String = "SAR",
    val alertAt80: Boolean = true,
    val alertAt100: Boolean = true
)

/**
 * 8. كيان الالتزامات والأقساط والجمعيات
 */
@Entity(
    tableName = "obligations",
    indices = [Index(value = ["dueDateMillis"])]
)
data class ObligationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val kind: ObligationKind,
    val totalAmountMinor: Long,
    val installmentAmountMinor: Long,
    val paidAmountMinor: Long = 0L,
    val currency: String = "SAR",
    val dueDateMillis: Long,
    val isRecurring: Boolean = false,
    val recurrenceMonths: Int = 1,
    val roscaPayoutMonth: String = "",
    val roscaRole: String = "",
    val accountId: Long? = null,
    val categoryId: Long? = null,
    val reminderEnabled: Boolean = true,
    val isCompleted: Boolean = false,
    val notes: String = ""
) {
    val remainingAmountMinor: Long
        get() = (totalAmountMinor - paidAmountMinor).coerceAtLeast(0L)
}

/**
 * 9. كيان دفعات الأقساط والالتزامات الجزئية
 */
@Entity(
    tableName = "installment_payments",
    indices = [
        Index(value = ["obligationId", "paymentKey"], unique = true),
        Index(value = ["linkedTransactionId"])
    ]
)
data class InstallmentPaymentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val obligationId: Long,
    val paymentKey: String,
    val amountMinor: Long,
    val paidAt: Long = System.currentTimeMillis(),
    val linkedTransactionId: Long? = null,
    val alreadyRecordedAsExpense: Boolean = false,
    val notes: String = ""
)

/**
 * 10. كيان صناديق وأهداف التوفير
 */
@Entity(tableName = "savings_goals")
data class SavingsGoalEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val targetAmountMinor: Long,
    val currentAmountMinor: Long = 0L,
    val currency: String = "SAR",
    val targetDateMillis: Long,
    val colorHex: String = "#C89D42",
    val isCompleted: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "savings_movements",
    indices = [Index(value = ["goalId"])]
)
data class SavingsMovementEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val goalId: Long,
    val isDeposit: Boolean,
    val amountMinor: Long,
    val note: String = "",
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * 11. قوائم مشتريات البيت وعناصرها
 */
@Entity(tableName = "shopping_lists")
data class ShoppingListEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val currency: String = "SAR",
    val isArchived: Boolean = false,
    val recordedAsExpense: Boolean = false,
    val linkedTransactionId: Long? = null,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "shopping_items",
    indices = [Index(value = ["listId"])]
)
data class ShoppingItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val listId: Long,
    val name: String,
    val quantity: Int = 1,
    val unitLabel: String = "حبة",
    val estimatedUnitPriceMinor: Long = 0L,
    val isPurchased: Boolean = false
) {
    val totalEstimatedMinor: Long
        get() = estimatedUnitPriceMinor * quantity.coerceAtLeast(1)
}

/**
 * 12. سجل الاستيراد والتعديلات المهمة والتسويات
 */
@Entity(
    tableName = "import_audit_logs",
    indices = [Index(value = ["timestamp"]), Index(value = ["eventType"])]
)
data class ImportAuditLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val eventType: String,
    val entityType: String,
    val entityId: Long? = null,
    val sourceDescription: String,
    val details: String,
    val reviewStatus: String = "CONFIRMED",
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * 13. كيان كلمات التجاهل المخصصة للرسائل (Ignored Keywords Manager)
 * يتيح للمستخدم إضافة أو تفعيل/إيقاف أو حذف كلمات لتتجاهلها محركات قراءة الرسائل تلقائياً.
 */
@Entity(
    tableName = "ignored_keywords",
    indices = [Index(value = ["keyword"], unique = true)]
)
data class IgnoredKeywordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val keyword: String,
    val isActive: Boolean = true,
    val isDefault: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
) {
    val isEnabled: Boolean
        get() = isActive

    constructor(
        id: Long = 0,
        keyword: String,
        isEnabled: Boolean
    ) : this(
        id = id,
        keyword = keyword,
        isActive = isEnabled,
        isDefault = false
    )
}

/**
 * 14. كيان ذاكرة التاجر الذكية (Smart Merchant Memory)
 * يحفظ ربط أسماء التجار (مثل dani est, Tamara) بالتصنيف والحساب الذي اختاره المستخدم آخر مرة،
 * ويطبقه تلقائياً في العمليات والرسائل القادمة.
 */
@Entity(
    tableName = "merchant_memories",
    indices = [Index(value = ["normalizedMerchant"], unique = true)]
)
data class MerchantMemoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val normalizedMerchant: String,
    val merchantDisplayName: String,
    val categoryId: Long? = null,
    val categoryName: String = "",
    val accountId: Long? = null,
    val accountName: String = "",
    val usageCount: Int = 1,
    val updatedAt: Long = System.currentTimeMillis()
) {
    val merchantKey: String
        get() = normalizedMerchant

    val displayName: String
        get() = merchantDisplayName

    constructor(
        merchantKey: String,
        displayName: String,
        categoryId: Long? = null,
        accountId: Long? = null
    ) : this(
        id = 0,
        normalizedMerchant = merchantKey,
        merchantDisplayName = displayName,
        categoryId = categoryId,
        accountId = accountId
    )
}


