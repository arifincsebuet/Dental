package com.spala.dental

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

@Composable
fun ClinicNavIcon(index: Int) {
    val color = MaterialTheme.colorScheme.primary
    Canvas(Modifier.size(24.dp)) {
        val w = size.width; val h = size.height; val stroke = Stroke(1.8.dp.toPx())
        when (index) {
            0 -> for (x in 0..1) for (y in 0..1) drawRoundRect(color, Offset(w * (.10f + x * .47f), h * (.10f + y * .47f)), Size(w * .33f, h * .33f), CornerRadius(3.dp.toPx()), style = stroke)
            1 -> { drawCircle(color, w * .17f, Offset(w * .5f, h * .27f), style = stroke); drawArc(color, 180f, 180f, false, Offset(w * .15f, h * .51f), Size(w * .70f, h * .64f), style = stroke) }
            2 -> { drawRoundRect(color, Offset(w * .12f, h * .17f), Size(w * .76f, h * .71f), CornerRadius(3.dp.toPx()), style = stroke); drawLine(color, Offset(w * .12f, h * .4f), Offset(w * .88f, h * .4f), stroke.width); for (x in listOf(.32f, .68f)) drawLine(color, Offset(w * x, h * .06f), Offset(w * x, h * .27f), stroke.width) }
            3 -> { for ((i, height) in listOf(.4f, .7f, .55f).withIndex()) drawRoundRect(color, Offset(w * (.12f + .28f * i), h * (.88f - height)), Size(w * .16f, h * height), CornerRadius(2.dp.toPx())); }
            else -> { drawCircle(color, w * .28f, center, style = stroke); drawCircle(color, w * .10f, center, style = stroke); for (i in 0..7) { val angle = i * Math.PI / 4; val dx = kotlin.math.cos(angle).toFloat(); val dy = kotlin.math.sin(angle).toFloat(); drawLine(color, center + Offset(dx * w * .31f, dy * h * .31f), center + Offset(dx * w * .43f, dy * h * .43f), stroke.width) } }
        }
    }
}
