package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
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
    version = 3,
    exportSchema = false
)
@TypeConverters(RoomTypeConverters::class)
abstract class SaidiQershDatabase : RoomDatabase() {
    abstract fun dao(): SaidiQershDao

    companion object {
        @Volatile
        private var INSTANCE: SaidiQershDatabase? = null

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE banks ADD COLUMN autoApproveEnabled INTEGER NOT NULL DEFAULT 0")
            }
        }

        fun getInstance(context: Context): SaidiQershDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    SaidiQershDatabase::class.java,
                    "saidi_qersh_local.db"
                )
                    .addMigrations(MIGRATION_2_3)
                    .fallbackToDestructiveMigration(true)
                    .build()
                INSTANCE = instance
                instance
            }
        }

        /**
         * يجهز البيانات التأسيسية لجهات الرسائل المستقلة (Alinma, AlRajhi, Enjaz, Urpay, STC Pay, VF-Cash, Fawry, SNB, CIB, NBE...)
         * والتصنيفات الـ 16 الجديدة والحسابات الافتراضية دون خلط بين الجهات.
         */
        suspend fun seedInitialDataIfEmpty(dao: SaidiQershDao) {
            val existingBanks = dao.getAllBanks()

            // تنظيف أي خلط سابق بين Urpay والراجحي في قواعد البيانات المرقاة
            existingBanks.firstOrNull { it.code == "ALRAJHI" && it.senderIdsCsv.contains("Urpay", ignoreCase = true) }?.let { rajhi ->
                dao.updateBank(
                    rajhi.copy(
                        senderIdsCsv = "AlRajhiBank,ALRAJHI,AlRajhi,مصرف الراجحي,الراجحي"
                    )
                )
            }

            val defaultSenders = listOf(
                BankEntity(
                    nameAr = "مصرف الإنماء (Alinma)",
                    nameEn = "Alinma Bank",
                    code = "ALINMA",
                    country = "SA",
                    defaultCurrency = "SAR",
                    senderIdsCsv = "Alinma,AlinmaBank,الإنماء,الانماء,مصرف الإنماء",
                    smsEnabled = true,
                    autoApproveEnabled = false
                ),
                BankEntity(
                    nameAr = "مصرف الراجحي (AlRajhi)",
                    nameEn = "Al Rajhi Bank",
                    code = "ALRAJHI",
                    country = "SA",
                    defaultCurrency = "SAR",
                    senderIdsCsv = "AlRajhiBank,ALRAJHI,AlRajhi,مصرف الراجحي,الراجحي",
                    smsEnabled = true,
                    autoApproveEnabled = false
                ),
                BankEntity(
                    nameAr = "إنجاز (Enjaz)",
                    nameEn = "Enjaz",
                    code = "ENJAZ",
                    country = "SA",
                    defaultCurrency = "SAR",
                    senderIdsCsv = "Enjaz,ENJAZ,EnjazPay,إنجاز,انجاز,بنك البلاد إنجاز",
                    smsEnabled = true,
                    autoApproveEnabled = false
                ),
                BankEntity(
                    nameAr = "Urpay (يورباي)",
                    nameEn = "Urpay",
                    code = "URPAY",
                    country = "SA",
                    defaultCurrency = "SAR",
                    senderIdsCsv = "Urpay,URPAY,يورباي",
                    smsEnabled = true,
                    autoApproveEnabled = false
                ),
                BankEntity(
                    nameAr = "STC Pay (إس تي سي باي)",
                    nameEn = "STC Pay",
                    code = "STCPAY",
                    country = "SA",
                    defaultCurrency = "SAR",
                    senderIdsCsv = "STC Pay,STCPAY,stcpay,STC Bank,اس تي سي باي,إس تي سي باي",
                    smsEnabled = true,
                    autoApproveEnabled = false
                ),
                BankEntity(
                    nameAr = "فودافون كاش (VF-Cash)",
                    nameEn = "Vodafone Cash",
                    code = "VFCASH",
                    country = "EG",
                    defaultCurrency = "EGP",
                    senderIdsCsv = "VF-Cash,VFCash,Vodafone,VodafoneCash,فودافون كاش",
                    smsEnabled = true,
                    autoApproveEnabled = false
                ),
                BankEntity(
                    nameAr = "فوري (Fawry)",
                    nameEn = "Fawry",
                    code = "FAWRY",
                    country = "EG",
                    defaultCurrency = "EGP",
                    senderIdsCsv = "Fawry,FAWRY,myFawry,فوري",
                    smsEnabled = true,
                    autoApproveEnabled = false
                ),
                BankEntity(
                    nameAr = "البنك الأهلي السعودي (SNB)",
                    nameEn = "Saudi National Bank",
                    code = "SNB",
                    country = "SA",
                    defaultCurrency = "SAR",
                    senderIdsCsv = "SNB,SNB AlAhli,AlAhli,الأهلي السعودي",
                    smsEnabled = true,
                    autoApproveEnabled = false
                ),
                BankEntity(
                    nameAr = "بنك الرياض",
                    nameEn = "Riyad Bank",
                    code = "RIYAD",
                    country = "SA",
                    defaultCurrency = "SAR",
                    senderIdsCsv = "RiyadBank,Riyad,بنك الرياض",
                    smsEnabled = true,
                    autoApproveEnabled = false
                ),
                BankEntity(
                    nameAr = "البنك السعودي الأول (SAB)",
                    nameEn = "Saudi Awwal Bank",
                    code = "SAB",
                    country = "SA",
                    defaultCurrency = "SAR",
                    senderIdsCsv = "SAB,SABB,البنك الأول",
                    smsEnabled = true,
                    autoApproveEnabled = false
                ),
                BankEntity(
                    nameAr = "البنك الأهلي المصري (NBE)",
                    nameEn = "National Bank of Egypt",
                    code = "NBE",
                    country = "EG",
                    defaultCurrency = "EGP",
                    senderIdsCsv = "NBE,AlAhly,البنك الأهلي المصري",
                    smsEnabled = true,
                    autoApproveEnabled = false
                ),
                BankEntity(
                    nameAr = "البنك التجاري الدولي (CIB)",
                    nameEn = "CIB Egypt",
                    code = "CIB",
                    country = "EG",
                    defaultCurrency = "EGP",
                    senderIdsCsv = "CIB,CIB Egypt",
                    smsEnabled = true,
                    autoApproveEnabled = false
                )
            )

            val existingCodes = existingBanks.map { it.code.uppercase() }.toSet()
            for (bank in defaultSenders) {
                if (bank.code.uppercase() !in existingCodes) {
                    dao.insertBank(bank)
                }
            }

            if (dao.getActiveSmsRules().isEmpty()) {
                val allBanks = dao.getAllBanks()
                val alinmaId = allBanks.firstOrNull { it.code == "ALINMA" }?.id ?: 1L
                val alRajhiId = allBanks.firstOrNull { it.code == "ALRAJHI" }?.id ?: 2L
                val snbId = allBanks.firstOrNull { it.code == "SNB" }?.id ?: 3L
                val riyadId = allBanks.firstOrNull { it.code == "RIYAD" }?.id ?: 4L

                dao.insertSmsRule(
                    SmsRuleEntity(
                        bankId = alinmaId,
                        ruleName = "شراء Samsung Wallet / مدى - الإنماء",
                        keywordOrRegex = "شراء عبر Samsung Wallet|شراء عبر نقاط البيع|بطاقة مدى",
                        suggestedType = SmsSuggestedType.CARD_PURCHASE,
                        priority = 25
                    )
                )
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
                        bankId = riyadId,
                        ruleName = "حوالة صادرة - بنك الرياض",
                        keywordOrRegex = "حوالة صادرة|تحويل صادر|Outgoing Transfer",
                        suggestedType = SmsSuggestedType.OUTGOING_TRANSFER,
                        priority = 15
                    )
                )
            }

            // شبكة التصنيفات الـ 16 المطلوبة مع أيقونات تعبيرية ملونة + تصنيفات الدخل
            val existingCategories = dao.getAllCategories()
            val requiredExpenseCategories = listOf(
                Triple("بقالة", "🛒", "#0D4732"),
                Triple("أكل بره", "🍔", "#D97706"),
                Triple("مواصلات", "🚕", "#2563EB"),
                Triple("تسوق", "🛍️", "#7C3AED"),
                Triple("فواتير", "🧾", "#0891B2"),
                Triple("اشتراكات", "📺", "#4F46E5"),
                Triple("سفر", "✈️", "#0284C7"),
                Triple("صحة", "💊", "#DC2626"),
                Triple("عناية شخصية", "✨", "#DB2777"),
                Triple("إيجار", "🏠", "#475569"),
                Triple("تعليم", "🎓", "#1D4ED8"),
                Triple("بنزين", "⛽", "#B45309"),
                Triple("أقساط", "💳", "#9333EA"),
                Triple("جمعيات", "🤝", "#059669"),
                Triple("أولاد", "🧸", "#EA580C"),
                Triple("أخرى", "📦", "#64748B")
            )
            val existingNames = existingCategories.map { it.nameAr }.toSet()
            for ((name, emoji, hex) in requiredExpenseCategories) {
                if (name !in existingNames) {
                    dao.insertCategory(
                        CategoryEntity(
                            nameAr = name,
                            type = CategoryType.EXPENSE,
                            iconName = emoji,
                            colorHex = hex,
                            isDefault = true
                        )
                    )
                }
            }
            val incomeDefaults = listOf(
                Triple("الراتب الشهري", "💰", "#1E7E55"),
                Triple("عمل حر ومكافآت", "🏆", "#C89D42"),
                Triple("عوائد واستثمارات", "📈", "#0F766E")
            )
            for ((name, emoji, hex) in incomeDefaults) {
                if (name !in existingNames) {
                    dao.insertCategory(
                        CategoryEntity(
                            nameAr = name,
                            type = CategoryType.INCOME,
                            iconName = emoji,
                            colorHex = hex,
                            isDefault = true
                        )
                    )
                }
            }

            if (dao.getAllAccounts().isEmpty()) {
                val banks = dao.getAllBanks()
                val alinma = banks.firstOrNull { it.code == "ALINMA" }
                val rajhi = banks.firstOrNull { it.code == "ALRAJHI" }

                dao.insertAccount(
                    AccountEntity(
                        name = "حساب الإنماء",
                        type = AccountType.BANK,
                        bankId = alinma?.id,
                        bankName = "مصرف الإنماء",
                        currency = "SAR",
                        openingBalanceMinor = 0L,
                        currentBalanceMinor = 0L,
                        accountLast4 = "2000",
                        cardLast4 = "5650",
                        smsParsingEnabled = true
                    )
                )
                dao.insertAccount(
                    AccountEntity(
                        name = "حساب الراجحي",
                        type = AccountType.BANK,
                        bankId = rajhi?.id,
                        bankName = "مصرف الراجحي",
                        currency = "SAR",
                        openingBalanceMinor = 0L,
                        currentBalanceMinor = 0L,
                        accountLast4 = "4821",
                        cardLast4 = "4821",
                        smsParsingEnabled = true
                    )
                )
                dao.insertAccount(
                    AccountEntity(
                        name = "المحفظة النقدية",
                        type = AccountType.CASH,
                        bankId = null,
                        bankName = "نقدي",
                        currency = "SAR",
                        openingBalanceMinor = 0L,
                        currentBalanceMinor = 0L,
                        accountLast4 = "",
                        cardLast4 = "",
                        smsParsingEnabled = false
                    )
                )
            }

            val currentYm = MoneyUtils.currentYearMonth()
            dao.upsertBudget(
                BudgetEntity(
                    yearMonth = currentYm,
                    categoryKey = "TOTAL",
                    categoryId = null,
                    categoryName = "الميزانية الشهرية الإجمالية",
                    limitMinor = 8000_00L,
                    currency = "SAR",
                    alertAt80 = true,
                    alertAt100 = true
                )
            )
        }
    }
}
