package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.example.domain.MoneyUtils

@Database(
    entities = [
        BankEntity::class,
        SmsRuleEntity::class,
        AccountEntity::class,
        CategoryEntity::class,
        TransactionEntity::class,
        SuggestedTransactionEntity::class,
        BudgetEntity::class,
        ObligationEntity::class,
        InstallmentPaymentEntity::class,
        SavingsGoalEntity::class,
        SavingsMovementEntity::class,
        ShoppingListEntity::class,
        ShoppingItemEntity::class,
        ImportAuditLogEntity::class
    ],
    version = 1,
    exportSchema = false
)
@TypeConverters(RoomTypeConverters::class)
abstract class SaidiQershDatabase : RoomDatabase() {
    abstract fun dao(): SaidiQershDao

    companion object {
        @Volatile
        private var INSTANCE: SaidiQershDatabase? = null

        fun getInstance(context: Context): SaidiQershDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    SaidiQershDatabase::class.java,
                    "saidi_qersh_local.db"
                ).fallbackToDestructiveMigration(true)
                    .build()
                INSTANCE = instance
                instance
            }
        }

        /**
         * يجهز البيانات التأسيسية للبنوك والتصنيفات والحسابات الافتراضية عند تشغيل التطبيق لأول مرة.
         */
        suspend fun seedInitialDataIfEmpty(dao: SaidiQershDao) {
            if (dao.getAllBanks().isEmpty()) {
                val alRajhiId = dao.insertBank(
                    BankEntity(
                        nameAr = "مصرف الراجحي",
                        nameEn = "Al Rajhi Bank",
                        code = "ALRAJHI",
                        country = "SA",
                        defaultCurrency = "SAR",
                        senderIdsCsv = "AlRajhiBank,ALRAJHI,مصرف الراجحي,Urpay",
                        smsEnabled = true
                    )
                )
                val snbId = dao.insertBank(
                    BankEntity(
                        nameAr = "البنك الأهلي السعودي (SNB)",
                        nameEn = "Saudi National Bank",
                        code = "SNB",
                        country = "SA",
                        defaultCurrency = "SAR",
                        senderIdsCsv = "SNB,SNB AlAhli,AlAhli,الأهلي,الاهلي",
                        smsEnabled = true
                    )
                )
                val alinmaId = dao.insertBank(
                    BankEntity(
                        nameAr = "مصرف الإنماء",
                        nameEn = "Alinma Bank",
                        code = "ALINMA",
                        country = "SA",
                        defaultCurrency = "SAR",
                        senderIdsCsv = "Alinma,AlinmaBank,الإنماء,الانماء",
                        smsEnabled = true
                    )
                )
                val riyadId = dao.insertBank(
                    BankEntity(
                        nameAr = "بنك الرياض",
                        nameEn = "Riyad Bank",
                        code = "RIYAD",
                        country = "SA",
                        defaultCurrency = "SAR",
                        senderIdsCsv = "RiyadBank,Riyad,بنك الرياض",
                        smsEnabled = true
                    )
                )
                dao.insertBank(
                    BankEntity(
                        nameAr = "البنك السعودي الأول (SAB)",
                        nameEn = "Saudi Awwal Bank",
                        code = "SAB",
                        country = "SA",
                        defaultCurrency = "SAR",
                        senderIdsCsv = "SAB,SABB,الأول",
                        smsEnabled = true
                    )
                )
                dao.insertBank(
                    BankEntity(
                        nameAr = "البنك الأهلي المصري (NBE)",
                        nameEn = "National Bank of Egypt",
                        code = "NBE",
                        country = "EG",
                        defaultCurrency = "EGP",
                        senderIdsCsv = "NBE,AlAhly,البنك الأهلي المصري",
                        smsEnabled = true
                    )
                )
                dao.insertBank(
                    BankEntity(
                        nameAr = "البنك التجاري الدولي (CIB)",
                        nameEn = "CIB Egypt",
                        code = "CIB",
                        country = "EG",
                        defaultCurrency = "EGP",
                        senderIdsCsv = "CIB,CIB Egypt",
                        smsEnabled = true
                    )
                )

                // قواعد رسائل افتراضية قابلة للتوسعة
                dao.insertSmsRule(
                    SmsRuleEntity(
                        bankId = alRajhiId,
                        ruleName = "شراء مدى/أثير - الراجحي",
                        keywordOrRegex = "شراء عبر نقاط البيع|شراء انترنت|شراء أثير|PoS Purchase",
                        suggestedType = SmsSuggestedType.CARD_PURCHASE,
                        priority = 20
                    )
                )
                dao.insertSmsRule(
                    SmsRuleEntity(
                        bankId = snbId,
                        ruleName = "شراء بطاقة مدى - SNB",
                        keywordOrRegex = "عملية شراء|مدى-أثير|POS Purchase",
                        suggestedType = SmsSuggestedType.CARD_PURCHASE,
                        priority = 20
                    )
                )
                dao.insertSmsRule(
                    SmsRuleEntity(
                        bankId = alinmaId,
                        ruleName = "سداد فاتورة - الإنماء",
                        keywordOrRegex = "سداد فاتورة|SADAD Bill",
                        suggestedType = SmsSuggestedType.BILL_PAYMENT,
                        priority = 15
                    )
                )
                dao.insertSmsRule(
                    SmsRuleEntity(
                        bankId = riyadId,
                        ruleName = "حوالة صادرة - بنك الرياض",
                        keywordOrRegex = "حوالة صادرة|تحويل صادر|Outgoing Transfer",
                        suggestedType = SmsSuggestedType.OUTGOING_TRANSFER,
                        priority = 15
                    )
                )
            }

            if (dao.getAllCategories().isEmpty()) {
                val defaultCategories = listOf(
                    CategoryEntity(nameAr = "مقاضي البيت والتموين", type = CategoryType.EXPENSE, iconName = "grocery", colorHex = "#0D4732", isDefault = true),
                    CategoryEntity(nameAr = "الوقود والمواصلات", type = CategoryType.EXPENSE, iconName = "fuel", colorHex = "#C89D42", isDefault = true),
                    CategoryEntity(nameAr = "المطاعم والكافيهات", type = CategoryType.EXPENSE, iconName = "restaurant", colorHex = "#B85D3B", isDefault = true),
                    CategoryEntity(nameAr = "الفواتير والاتصالات", type = CategoryType.EXPENSE, iconName = "bills", colorHex = "#285E61", isDefault = true),
                    CategoryEntity(nameAr = "السكن والإيجار", type = CategoryType.EXPENSE, iconName = "home", colorHex = "#4A5568", isDefault = true),
                    CategoryEntity(nameAr = "الصحةوالصيدلية", type = CategoryType.EXPENSE, iconName = "health", colorHex = "#9B2C2C", isDefault = true),
                    CategoryEntity(nameAr = "التعليم والأسرة", type = CategoryType.EXPENSE, iconName = "education", colorHex = "#2B6CB0", isDefault = true),
                    CategoryEntity(nameAr = "التسوق والمشتريات", type = CategoryType.EXPENSE, iconName = "shopping", colorHex = "#6B46C1", isDefault = true),
                    CategoryEntity(nameAr = "الراتب الشهري", type = CategoryType.INCOME, iconName = "salary", colorHex = "#1E7E55", isDefault = true),
                    CategoryEntity(nameAr = "عمل حر ومكافآت", type = CategoryType.INCOME, iconName = "freelance", colorHex = "#D4AF37", isDefault = true),
                    CategoryEntity(nameAr = "عوائد واستثمارات", type = CategoryType.INCOME, iconName = "investment", colorHex = "#0F766E", isDefault = true)
                )
                defaultCategories.forEach { dao.insertCategory(it) }
            }

            if (dao.getAllAccounts().isEmpty()) {
                val banks = dao.getAllBanks()
                val rajhi = banks.firstOrNull { it.code == "ALRAJHI" }
                dao.insertAccount(
                    AccountEntity(
                        name = "حساب الراجحي الجاري",
                        type = AccountType.BANK,
                        bankId = rajhi?.id,
                        bankName = rajhi?.nameAr ?: "مصرف الراجحي",
                        currency = "SAR",
                        openingBalanceMinor = 0L,
                        currentBalanceMinor = 0L,
                        smsParsingEnabled = true
                    )
                )
                dao.insertAccount(
                    AccountEntity(
                        name = "المحفظة النقدية (كاش)",
                        type = AccountType.CASH,
                        bankId = null,
                        bankName = "نقدي",
                        currency = "SAR",
                        openingBalanceMinor = 0L,
                        currentBalanceMinor = 0L,
                        smsParsingEnabled = false
                    )
                )
            }

            // إضافة ميزانية افتراضية للشهر الحالي إذا لم تكن موجودة
            val currentYm = MoneyUtils.currentYearMonth()
            dao.upsertBudget(
                BudgetEntity(
                    yearMonth = currentYm,
                    categoryKey = "TOTAL",
                    categoryId = null,
                    categoryName = "الميزانية الشهرية الإجمالية",
                    limitMinor = 8000_00L, // 8,000 ر.س افتراضية قابلة للتعديل
                    currency = "SAR",
                    alertAt80 = true,
                    alertAt100 = true
                )
            )
        }
    }
}
