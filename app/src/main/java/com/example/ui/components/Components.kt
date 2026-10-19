package com.example.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.domain.MoneyUtils
import com.example.ui.CategorySpendingSlice
import com.example.ui.theme.ExpenseRed
import com.example.ui.theme.IncomeGreen
import com.example.ui.theme.SaidiGoldPrimary
import com.example.ui.theme.SaidiGreenDark

/**
 * الشعار الأصلي المرسوم برمجيًا لتطبيق «الصعيدي قرش» (محفظة مع عملة قرش ذهبية).
 */
@Composable
fun SaidiQershWalletLogo(
    modifier: Modifier = Modifier,
    size: Dp = 44.dp
) {
    Canvas(modifier = modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height

        // دائرة الخلفية الخضراء الداكنة
        drawCircle(
            color = SaidiGreenDark,
            radius = w * 0.48f,
            center = Offset(w * 0.5f, h * 0.5f)
        )

        // عملة القرش الذهبية البارزة أعلى المحفظة
        drawCircle(
            color = SaidiGoldPrimary,
            radius = w * 0.16f,
            center = Offset(w * 0.5f, h * 0.30f)
        )
        drawCircle(
            color = SaidiGreenDark,
            radius = w * 0.08f,
            center = Offset(w * 0.5f, h * 0.30f)
        )

        // جسم المحفظة الكريمي
        drawRoundRect(
            color = Color(0xFFFAF6EE),
            topLeft = Offset(w * 0.22f, h * 0.38f),
            size = Size(w * 0.56f, h * 0.38f),
            cornerRadius = CornerRadius(w * 0.08f, w * 0.08f)
        )

        // قفل المحفظة الجانبي مع الزر الذهبي
        drawRoundRect(
            color = SaidiGreenDark,
            topLeft = Offset(w * 0.54f, h * 0.49f),
            size = Size(w * 0.25f, h * 0.16f),
            cornerRadius = CornerRadius(w * 0.05f, w * 0.05f)
        )
        drawCircle(
            color = SaidiGoldPrimary,
            radius = w * 0.045f,
            center = Offset(w * 0.62f, h * 0.57f)
        )
    }
}

/**
 * رسم بياني دائري فعلي (Donut Chart) لتوزيع المصروفات حسب التصنيف.
 */
@Composable
fun CategorySpendingDonutChart(
    slices: List<CategorySpendingSlice>,
    currency: String,
    modifier: Modifier = Modifier
) {
    if (slices.isEmpty()) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "لا توجد مصروفات مؤكدة هذا الشهر لعرض الرسم البياني",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }

    val palette = listOf(
        Color(0xFF0D4732),
        Color(0xFFC89D42),
        Color(0xFFB85D3B),
        Color(0xFF285E61),
        Color(0xFF6B46C1),
        Color(0xFF9B2C2C),
        Color(0xFF2B6CB0)
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Box(
            modifier = Modifier.size(130.dp),
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.fillMaxSize().padding(10.dp)) {
                var startAngle = -90f
                val strokeWidth = 24.dp.toPx()
                slices.forEachIndexed { idx, slice ->
                    val sweep = (slice.percentage * 360f).coerceAtLeast(4f)
                    val sliceColor = parseColorSafe(slice.colorHex, palette[idx % palette.size])
                    drawArc(
                        color = sliceColor,
                        startAngle = startAngle,
                        sweepAngle = sweep,
                        useCenter = false,
                        style = Stroke(width = strokeWidth, cap = StrokeCap.Butt)
                    )
                    startAngle += sweep
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "${slices.size}",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "تصنيفات",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            slices.take(5).forEachIndexed { idx, slice ->
                val dotColor = parseColorSafe(slice.colorHex, palette[idx % palette.size])
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(dotColor)
                        )
                        Text(
                            text = slice.categoryName,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1
                        )
                    }
                    Text(
                        text = "${(slice.percentage * 100).toInt()}% (${MoneyUtils.formatMinor(slice.amountMinor, currency)})",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

/**
 * رسم بياني عمودي لمقارنة مصروفات الشهر الحالي بالشهر السابق والدخل.
 */
@Composable
fun MonthlyComparisonBarChart(
    currentMonthExpenseMinor: Long,
    prevMonthExpenseMinor: Long,
    currentMonthIncomeMinor: Long,
    currency: String,
    modifier: Modifier = Modifier
) {
    val maxVal = maxOf(currentMonthExpenseMinor, prevMonthExpenseMinor, currentMonthIncomeMinor, 100_00L).toFloat()

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                text = "مقارنة الشهر الحالي بالشهر السابق",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            ComparisonBarRow(
                label = "مصروفات الشهر الحالي",
                amountMinor = currentMonthExpenseMinor,
                ratio = (currentMonthExpenseMinor.toFloat() / maxVal).coerceIn(0.03f, 1f),
                barColor = ExpenseRed,
                currency = currency
            )
            ComparisonBarRow(
                label = "مصروفات الشهر السابق",
                amountMinor = prevMonthExpenseMinor,
                ratio = (prevMonthExpenseMinor.toFloat() / maxVal).coerceIn(0.03f, 1f),
                barColor = SaidiGoldPrimary,
                currency = currency
            )
            ComparisonBarRow(
                label = "إجمالي دخل الشهر الحالي",
                amountMinor = currentMonthIncomeMinor,
                ratio = (currentMonthIncomeMinor.toFloat() / maxVal).coerceIn(0.03f, 1f),
                barColor = IncomeGreen,
                currency = currency
            )

            val diff = currentMonthExpenseMinor - prevMonthExpenseMinor
            val diffText = when {
                prevMonthExpenseMinor == 0L -> "لا توجد مصروفات مسجلة في الشهر السابق للمقارنة"
                diff > 0L -> "ارتفع الإنفاق هذا الشهر بمقدار ${MoneyUtils.formatMinor(diff, currency)} عن الشهر السابق"
                diff < 0L -> "وفّرت هذا الشهر ${MoneyUtils.formatMinor(-diff, currency)} مقارنة بالشهر السابق"
                else -> "الإنفاق مساوٍ تمامًا للشهر السابق"
            }
            Text(
                text = diffText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ComparisonBarRow(
    label: String,
    amountMinor: Long,
    ratio: Float,
    barColor: Color,
    currency: String
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(text = label, style = MaterialTheme.typography.bodySmall)
            Text(
                text = MoneyUtils.formatMinor(amountMinor, currency),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(10.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(MaterialTheme.colorScheme.surface)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(ratio)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(5.dp))
                    .background(barColor)
            )
        }
    }
}

fun parseColorSafe(hex: String, fallback: Color): Color {
    return try {
        val clean = hex.removePrefix("#")
        val longVal = clean.toLong(16)
        if (clean.length == 6) {
            Color(longVal or 0xFF000000)
        } else {
            Color(longVal)
        }
    } catch (_: Exception) {
        fallback
    }
}
