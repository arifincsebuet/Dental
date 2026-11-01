package com.spala.dental

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spala.dental.ui.theme.*
import org.json.JSONArray
import org.json.JSONObject

// ---------------------------------------------------------------------------------------------
// Glyphs: crisp line icons drawn on Canvas (no icon font dependency).
// ---------------------------------------------------------------------------------------------

enum class Glyph { Back, Chevron, Sync, Plus, Cube, Phone, Calendar, Alert, Tooth, Edit, Export, Image, Archive, Person, Clipboard, Check, Expand, Labels }

@Composable
fun GlyphIcon(glyph: Glyph, tint: Color, size: Dp = 20.dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val w = this.size.width; val h = this.size.height
        val s = Stroke(width = 1.8.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        fun p(x: Float, y: Float) = Offset(w * x, h * y)
        fun line(a: Offset, b: Offset) = drawLine(tint, a, b, s.width, StrokeCap.Round)
        when (glyph) {
            Glyph.Back -> { line(p(.62f, .2f), p(.32f, .5f)); line(p(.32f, .5f), p(.62f, .8f)) }
            Glyph.Chevron -> { line(p(.4f, .22f), p(.68f, .5f)); line(p(.68f, .5f), p(.4f, .78f)) }
            Glyph.Sync -> {
                drawArc(tint, 200f, 230f, false, p(.18f, .18f), Size(w * .64f, h * .64f), style = s)
                line(p(.70f, .14f), p(.74f, .32f)); line(p(.74f, .32f), p(.56f, .34f))
            }
            Glyph.Plus -> { line(p(.5f, .2f), p(.5f, .8f)); line(p(.2f, .5f), p(.8f, .5f)) }
            Glyph.Cube -> {
                val top = Path().apply { moveTo(w * .5f, h * .12f); lineTo(w * .86f, h * .31f); lineTo(w * .5f, h * .5f); lineTo(w * .14f, h * .31f); close() }
                drawPath(top, tint, style = s)
                line(p(.14f, .31f), p(.14f, .70f)); line(p(.86f, .31f), p(.86f, .70f)); line(p(.5f, .5f), p(.5f, .89f))
                line(p(.14f, .70f), p(.5f, .89f)); line(p(.86f, .70f), p(.5f, .89f))
            }
            Glyph.Phone -> {
                drawRoundRect(tint, p(.3f, .1f), Size(w * .4f, h * .8f), CornerRadius(w * .08f), style = s)
                line(p(.45f, .78f), p(.55f, .78f))
            }
            Glyph.Calendar -> {
                drawRoundRect(tint, p(.14f, .2f), Size(w * .72f, h * .66f), CornerRadius(w * .1f), style = s)
                line(p(.14f, .42f), p(.86f, .42f)); line(p(.34f, .1f), p(.34f, .28f)); line(p(.66f, .1f), p(.66f, .28f))
            }
            Glyph.Alert -> {
                val tri = Path().apply { moveTo(w * .5f, h * .12f); lineTo(w * .9f, h * .84f); lineTo(w * .1f, h * .84f); close() }
                drawPath(tri, tint, style = s)
                line(p(.5f, .40f), p(.5f, .60f)); drawCircle(tint, w * .045f, p(.5f, .72f))
            }
            Glyph.Tooth -> {
                val tooth = Path().apply {
                    moveTo(w * .30f, h * .12f)
                    cubicTo(w * .10f, h * .10f, w * .08f, h * .40f, w * .2f, h * .55f)
                    cubicTo(w * .26f, h * .70f, w * .28f, h * .9f, w * .38f, h * .9f)
                    cubicTo(w * .46f, h * .9f, w * .44f, h * .62f, w * .5f, h * .62f)
                    cubicTo(w * .56f, h * .62f, w * .54f, h * .9f, w * .62f, h * .9f)
                    cubicTo(w * .72f, h * .9f, w * .74f, h * .70f, w * .8f, h * .55f)
                    cubicTo(w * .92f, h * .40f, w * .90f, h * .10f, w * .70f, h * .12f)
                    cubicTo(w * .6f, h * .14f, w * .4f, h * .14f, w * .30f, h * .12f)
                    close()
                }
                drawPath(tooth, tint, style = s)
            }
            Glyph.Edit -> {
                line(p(.22f, .78f), p(.72f, .28f)); line(p(.62f, .18f), p(.82f, .38f)); line(p(.22f, .78f), p(.18f, .84f))
            }
            Glyph.Export -> {
                line(p(.5f, .14f), p(.5f, .6f)); line(p(.32f, .32f), p(.5f, .14f)); line(p(.68f, .32f), p(.5f, .14f))
                drawRoundRect(tint, p(.16f, .5f), Size(w * .68f, h * .36f), CornerRadius(w * .08f), style = s)
            }
            Glyph.Image -> {
                drawRoundRect(tint, p(.12f, .18f), Size(w * .76f, h * .64f), CornerRadius(w * .1f), style = s)
                line(p(.2f, .74f), p(.42f, .5f)); line(p(.42f, .5f), p(.58f, .64f)); line(p(.58f, .64f), p(.8f, .44f))
                drawCircle(tint, w * .06f, p(.66f, .34f))
            }
            Glyph.Archive -> {
                drawRoundRect(tint, p(.12f, .18f), Size(w * .76f, h * .2f), CornerRadius(w * .05f), style = s)
                drawRoundRect(tint, p(.18f, .38f), Size(w * .64f, h * .46f), CornerRadius(w * .05f), style = s)
                line(p(.4f, .54f), p(.6f, .54f))
            }
            Glyph.Person -> {
                drawCircle(tint, w * .17f, p(.5f, .33f), style = s)
                drawArc(tint, 200f, 140f, false, p(.18f, .58f), Size(w * .64f, h * .64f), style = s)
            }
            Glyph.Clipboard -> {
                drawRoundRect(tint, p(.2f, .16f), Size(w * .6f, h * .72f), CornerRadius(w * .08f), style = s)
                line(p(.36f, .42f), p(.64f, .42f)); line(p(.36f, .58f), p(.64f, .58f)); line(p(.36f, .74f), p(.54f, .74f))
                drawRoundRect(tint, p(.38f, .1f), Size(w * .24f, h * .12f), CornerRadius(w * .04f), style = s)
            }
            Glyph.Check -> { line(p(.2f, .52f), p(.42f, .74f)); line(p(.42f, .74f), p(.8f, .3f)) }
            Glyph.Expand -> {
                line(p(.16f, .38f), p(.16f, .16f)); line(p(.16f, .16f), p(.38f, .16f))
                line(p(.84f, .62f), p(.84f, .84f)); line(p(.84f, .84f), p(.62f, .84f))
                line(p(.16f, .16f), p(.42f, .42f)); line(p(.84f, .84f), p(.58f, .58f))
            }
            Glyph.Labels -> {
                drawRoundRect(tint, p(.12f, .28f), Size(w * .76f, h * .44f), CornerRadius(w * .22f), style = s)
                line(p(.32f, .5f), p(.68f, .5f))
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Building blocks
// ---------------------------------------------------------------------------------------------

/** Two-letter initials; never more than the patient's own name. */
fun initials(name: String): String = name.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
    .let { parts -> if (parts.size >= 2) "${parts.first().first()}${parts.last().first()}" else parts.firstOrNull()?.take(2).orEmpty() }
    .uppercase().ifBlank { "?" }

@Composable
fun Avatar(name: String, size: Dp = 56.dp, colors: List<Color> = listOf(Teal500, Teal700)) {
    Box(
        Modifier.size(size).clip(CircleShape).background(Brush.linearGradient(colors)),
        contentAlignment = Alignment.Center,
    ) {
        Text(initials(name), color = Color.White, fontWeight = FontWeight.Bold, fontSize = (size.value * 0.36f).sp)
    }
}

@Composable
fun StatusPill(text: String, background: Color, content: Color, modifier: Modifier = Modifier, dot: Boolean = true) {
    Row(
        modifier.clip(RoundedCornerShape(50)).background(background).padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (dot) Box(Modifier.size(6.dp).clip(CircleShape).background(content))
        Text(text, color = content, style = MaterialTheme.typography.labelMedium, maxLines = 1)
    }
}

@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier, action: String? = null, onAction: (() -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = Ink, modifier = Modifier.weight(1f))
        if (action != null && onAction != null) TextButton(onClick = onAction, contentPadding = PaddingValues(horizontal = 8.dp)) {
            Text(action, style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** Elevated white surface with a hairline border, the base card of the app. */
@Composable
fun SurfaceCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    background: Color = SurfaceWhite,
    border: Color = Hairline,
    padding: Dp = 18.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(20.dp)
    if (onClick != null) {
        Surface(onClick = onClick, modifier = modifier.fillMaxWidth(), shape = shape, color = background, border = BorderStroke(1.dp, border), shadowElevation = 1.dp) {
            Column(Modifier.padding(padding), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
        }
    } else {
        Surface(modifier = modifier.fillMaxWidth(), shape = shape, color = background, border = BorderStroke(1.dp, border), shadowElevation = 1.dp) {
            Column(Modifier.padding(padding), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
        }
    }
}

@Composable
fun StatTile(label: String, value: String, accent: Color, glyph: Glyph, modifier: Modifier = Modifier) {
    Surface(modifier, shape = RoundedCornerShape(20.dp), color = SurfaceWhite, border = BorderStroke(1.dp, Hairline), shadowElevation = 1.dp) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(36.dp).clip(RoundedCornerShape(12.dp)).background(accent.copy(alpha = .12f)), contentAlignment = Alignment.Center) {
                GlyphIcon(glyph, accent, 20.dp)
            }
            Text(value, style = MaterialTheme.typography.headlineSmall, color = Ink)
            Text(label, style = MaterialTheme.typography.bodySmall, color = InkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Compact metric used inside cards (e.g. chart summary under the 3D model). */
@Composable
fun MiniMetric(value: String, label: String, accent: Color, modifier: Modifier = Modifier) {
    Column(modifier.clip(RoundedCornerShape(14.dp)).background(accent.copy(alpha = .08f)).padding(horizontal = 10.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = accent)
        Text(label, style = MaterialTheme.typography.labelSmall, color = InkMuted, maxLines = 1)
    }
}

@Composable
fun ActionTile(label: String, glyph: Glyph, modifier: Modifier = Modifier, tint: Color = Teal700, enabled: Boolean = true, onClick: () -> Unit) {
    Surface(onClick = onClick, enabled = enabled, modifier = modifier, shape = RoundedCornerShape(16.dp), color = SurfaceWhite,
        border = BorderStroke(1.dp, Hairline)) {
        Column(Modifier.padding(vertical = 14.dp, horizontal = 8.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(40.dp).clip(CircleShape).background(tint.copy(alpha = if (enabled) .10f else .04f)), contentAlignment = Alignment.Center) {
                GlyphIcon(glyph, if (enabled) tint else InkSubtle, 20.dp)
            }
            Text(label, style = MaterialTheme.typography.labelMedium, color = if (enabled) Ink else InkSubtle, maxLines = 2,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
    }
}

/** Single-select segmented control with pill styling. */
@Composable
fun SegmentedPills(options: List<String>, selected: String, modifier: Modifier = Modifier, label: (String) -> String = { it }, onSelect: (String) -> Unit) {
    Row(
        modifier.clip(RoundedCornerShape(50)).background(SurfaceTint).padding(4.dp).horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEach { option ->
            val active = option == selected
            Box(
                Modifier.clip(RoundedCornerShape(50))
                    .background(if (active) SurfaceWhite else Color.Transparent)
                    .then(if (active) Modifier.border(1.dp, Hairline, RoundedCornerShape(50)) else Modifier)
                    .clickable(role = Role.Tab) { onSelect(option) }
                    .padding(horizontal = 16.dp, vertical = 9.dp),
            ) {
                Text(label(option), style = MaterialTheme.typography.labelLarge, color = if (active) Teal700 else InkMuted, maxLines = 1)
            }
        }
    }
}

@Composable
fun InfoRow(glyph: Glyph, label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(SurfaceTint), contentAlignment = Alignment.Center) { GlyphIcon(glyph, InkMuted, 18.dp) }
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = InkMuted)
            Text(value, style = MaterialTheme.typography.bodyMedium, color = Ink)
        }
    }
}

@Composable
fun AlertBanner(title: String, body: String) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Rose50).border(1.dp, Rose600.copy(alpha = .25f), RoundedCornerShape(16.dp)).padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        GlyphIcon(Glyph.Alert, Rose600, 22.dp)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = Rose600)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = Ink)
        }
    }
}

@Composable
fun LegendDot(color: Color, label: String, hollow: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(10.dp).clip(CircleShape).then(if (hollow) Modifier.border(1.5.dp, color, CircleShape) else Modifier.background(color)))
        Text(label, style = MaterialTheme.typography.labelSmall, color = InkMuted)
    }
}

// ---------------------------------------------------------------------------------------------
// 3D hero card
// ---------------------------------------------------------------------------------------------

/**
 * The first thing on a profile: an interactive 3D dentition on a soft studio gradient.
 * The model rotates/zooms in place; [onOpenFull] launches the detailed Godot chart.
 */
@Composable
fun Teeth3DHeroCard(
    chart: JSONObject?,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    selectedTeeth: Set<String> = emptySet(),
    onToothTap: ((String) -> Unit)? = null,
    onOpenFull: (() -> Unit)? = null,
    height: Dp = 340.dp,
    banner: @Composable BoxScope.() -> Unit = { HeroHint("Drag to rotate · pinch to zoom · double-tap to reset") },
    footer: @Composable ColumnScope.() -> Unit = {},
) {
    var labels by rememberSaveable { mutableStateOf(true) }
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(28.dp), color = SurfaceWhite, border = BorderStroke(1.dp, Hairline), shadowElevation = 2.dp) {
        Column {
            Box(
                Modifier.fillMaxWidth().height(height)
                    .background(Brush.verticalGradient(listOf(HeroTop, HeroBottom))),
            ) {
                // Faint studio rings behind the model.
                Canvas(Modifier.matchParentSize()) {
                    val c = Offset(size.width / 2f, size.height * 0.55f)
                    for (i in 1..3) drawCircle(Color.White.copy(alpha = 0.35f / i), size.minDimension * (0.22f + i * 0.14f), c, style = Stroke(1.dp.toPx()))
                }
                Teeth3DView(chart, Modifier.matchParentSize(), selectedTeeth = selectedTeeth, showLabels = labels, onToothTap = onToothTap)
                // Leave room for the two round buttons at the top-right so long names never run under them.
                Column(Modifier.align(Alignment.TopStart).padding(start = 16.dp, top = 16.dp, end = 112.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box(Modifier.clip(RoundedCornerShape(8.dp)).background(Teal700).padding(horizontal = 7.dp, vertical = 3.dp)) {
                            Text("3D", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                        }
                        Text(title, style = MaterialTheme.typography.titleSmall, color = Teal900, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = InkMuted, modifier = Modifier.padding(top = 2.dp), maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Row(Modifier.align(Alignment.TopEnd).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HeroIconButton(Glyph.Labels, if (labels) "Hide tooth numbers" else "Show tooth numbers", active = labels) { labels = !labels }
                    if (onOpenFull != null) HeroIconButton(Glyph.Expand, "Open full 3D chart", active = false, onClick = onOpenFull)
                }
                banner()
            }
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = footer)
        }
    }
}

/** Small pill at the bottom of the 3D stage. */
@Composable
fun BoxScope.HeroHint(text: String, highlight: Boolean = false) {
    Text(
        text, style = MaterialTheme.typography.labelSmall, color = if (highlight) Color.White else InkMuted,
        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp)
            .clip(RoundedCornerShape(50)).background(if (highlight) Teal700 else Color.White.copy(alpha = .75f))
            .padding(horizontal = 12.dp, vertical = 5.dp),
    )
}

@Composable
private fun HeroIconButton(glyph: Glyph, description: String, active: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick, shape = CircleShape, color = if (active) Teal700 else Color.White.copy(alpha = .92f),
        border = BorderStroke(1.dp, if (active) Teal700 else Hairline), shadowElevation = 2.dp,
        modifier = Modifier.size(40.dp).semantics { contentDescription = description },
    ) {
        Box(contentAlignment = Alignment.Center) { GlyphIcon(glyph, if (active) Color.White else Teal700, 20.dp) }
    }
}

@Composable
fun ChartLegendRow() {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        LegendDot(Color(0xFFFFE9C2), "Assessed")
        LegendDot(InkSubtle, "Unassessed", hollow = true)
        LegendDot(Amber600, "Finding")
        LegendDot(Blue600, "Restoration")
        LegendDot(Color(0xFF60A5FA), "Treated")
        LegendDot(Teal700, "Planned care")
        LegendDot(Color(0xFF64748B), "Recorded loss")
    }
}

/** A neutral, fully assessed reference dentition for non-patient views (no clinical data). */
fun referenceChart(): JSONObject {
    val teeth = JSONObject()
    for (q in 1..4) for (p in 1..8) teeth.put("$q$p", JSONObject().put("assessment", "assessed").put("presence", "present"))
    return JSONObject().put("schemaVersion", 2).put("catalogVersion", "fdi-v1").put("dentition", "permanent").put("chartVersion", 0).put("teeth", teeth).put("readOnly", true)
}

internal fun JSONArray?.objectList(): List<JSONObject> = this?.let { array -> (0 until array.length()).mapNotNull { array.optJSONObject(it) } }.orEmpty()
