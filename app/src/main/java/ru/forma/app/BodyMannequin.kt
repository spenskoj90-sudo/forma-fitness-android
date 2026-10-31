package ru.forma.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import ru.forma.domain.Profile
import ru.forma.domain.bodyShape

/** A responsive, measurement-driven diagram, not a clinical body scan. */
@Composable
fun BodyMannequin(profile: Profile, modifier: Modifier = Modifier) {
    val proportions = bodyShape(profile)
    val primary = MaterialTheme.colorScheme.primary
    val body = MaterialTheme.colorScheme.onSurfaceVariant
    val back = MaterialTheme.colorScheme.surfaceVariant
    Column(modifier) {
        Canvas(
            Modifier.fillMaxWidth().height(250.dp).semantics {
                contentDescription = "Схематический манекен: вид спереди и сзади. Рост ${profile.heightCm} см, вес ${profile.weightKg} кг. " +
                    if (proportions.hasMeasurements) "Учитываются последние замеры тела." else "Для уточнения пропорций добавьте замеры тела."
            }
        ) {
            val h = size.height
            fun figure(centerX: Float, isBack: Boolean) {
                val shoulder = h * 0.133f * proportions.shoulderFactor
                val chest = h * 0.112f * proportions.chestFactor
                val waist = h * 0.090f * proportions.waistFactor
                val hips = h * 0.105f * proportions.hipFactor
                val yTop = h * 0.255f
                val yChest = h * 0.40f
                val yWaist = h * 0.57f
                val yHip = h * 0.67f
                val contour = Path().apply {
                    moveTo(centerX - shoulder, yTop)
                    cubicTo(centerX - chest, yChest * 0.93f, centerX - waist, yWaist * 0.91f, centerX - waist, yWaist)
                    cubicTo(centerX - hips, yHip * 0.98f, centerX - hips, yHip, centerX - hips * 0.92f, yHip)
                    lineTo(centerX + hips * 0.92f, yHip)
                    cubicTo(centerX + hips, yHip, centerX + hips, yHip * 0.98f, centerX + waist, yWaist)
                    cubicTo(centerX + waist, yWaist * 0.91f, centerX + chest, yChest * 0.93f, centerX + shoulder, yTop)
                    close()
                }
                drawPath(contour, if (isBack) back else body.copy(alpha = 0.85f))
                val limb = h * 0.061f
                drawLine(body, Offset(centerX - shoulder * 0.94f, yTop + h * 0.025f),
                    Offset(centerX - shoulder * 1.09f, h * 0.67f), limb, StrokeCap.Round)
                drawLine(body, Offset(centerX + shoulder * 0.94f, yTop + h * 0.025f),
                    Offset(centerX + shoulder * 1.09f, h * 0.67f), limb, StrokeCap.Round)
                drawLine(body, Offset(centerX - hips * 0.47f, yHip - h * 0.015f),
                    Offset(centerX - hips * 0.56f, h * 0.925f), limb * 1.1f, StrokeCap.Round)
                drawLine(body, Offset(centerX + hips * 0.47f, yHip - h * 0.015f),
                    Offset(centerX + hips * 0.56f, h * 0.925f), limb * 1.1f, StrokeCap.Round)
                drawCircle(body, h * 0.066f, Offset(centerX, h * 0.13f))
                drawLine(primary.copy(alpha = 0.76f), Offset(centerX - waist, yWaist),
                    Offset(centerX + waist, yWaist), 2.dp.toPx(), StrokeCap.Round)
                if (isBack) {
                    drawLine(primary.copy(alpha = 0.6f), Offset(centerX, yTop + h * 0.035f),
                        Offset(centerX, yHip - h * 0.05f), 2.dp.toPx(), StrokeCap.Round)
                } else {
                    drawLine(primary.copy(alpha = 0.72f), Offset(centerX - chest * 0.8f, yChest),
                        Offset(centerX + chest * 0.8f, yChest), 2.dp.toPx(), StrokeCap.Round)
                }
            }
            figure(size.width * 0.27f, false)
            figure(size.width * 0.73f, true)
        }
        Row(Modifier.fillMaxWidth()) {
            Text("Спереди", Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelMedium)
            Text("Сзади", Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelMedium)
        }
        Spacer(Modifier.height(8.dp))
        Text(
            if (proportions.hasMeasurements)
                "Контур условный: используются последние введённые обхваты; рост и вес указаны в профиле."
            else "Пока показан приблизительный контур по росту и весу. Для более индивидуального вида добавьте замеры плеч, груди, талии и бёдер.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall
        )
    }
}
