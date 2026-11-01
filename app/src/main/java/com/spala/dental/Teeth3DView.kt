package com.spala.dental

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.produceState
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * Inline 3D dentition for profile headers.
 *
 * A small software renderer (flat-shaded triangles, painter's sort, Canvas.drawVertices)
 * keeps the model on the profile screen itself. The full Godot chart still owns detailed
 * orbit/pan/surface selection; only one Godot engine may exist per process, so it cannot be
 * embedded in scrolling lists. This view reads the same sparse schema-2 chart snapshot and
 * never needs names, notes or other profile data.
 */

private const val LOST_PRESENCE = "missing|extracted|exfoliated|congenitally_absent"
private fun isLost(presence: String) = presence in LOST_PRESENCE.split('|')

/** Readable FDI slot name, e.g. "Upper right first molar". */
fun toothName(key: String): String {
    if (key.length != 2 || !key[0].isDigit() || !key[1].isDigit()) return "Tooth $key"
    val quadrant = key[0].digitToInt()
    val position = key[1].digitToInt()
    val primary = quadrant >= 5
    val arch = if (quadrant in listOf(1, 2, 5, 6)) "Upper" else "Lower"
    val side = if (quadrant in listOf(1, 4, 5, 8)) "right" else "left"
    val names = if (primary) listOf("central incisor", "lateral incisor", "canine", "first molar", "second molar")
    else listOf("central incisor", "lateral incisor", "canine", "first premolar", "second premolar", "first molar", "second molar", "third molar")
    val name = names.getOrNull(position - 1) ?: return "Tooth $key"
    return "$arch $side ${if (primary) "primary " else ""}$name"
}

/** Counts used by profile summaries; absent slots stay "unrecorded", never "healthy". */
data class ChartCounts(val recorded: Int, val assessed: Int, val findings: Int, val restorations: Int, val planned: Int, val lost: Int)

fun chartCounts(chart: JSONObject?): ChartCounts {
    val teeth = chart?.optJSONObject("teeth") ?: return ChartCounts(0, 0, 0, 0, 0, 0)
    var assessed = 0; var findings = 0; var restorations = 0; var planned = 0; var lost = 0
    for (key in teeth.keys()) {
        val tooth = teeth.optJSONObject(key) ?: continue
        if (tooth.optString("assessment") == "assessed") assessed++
        if (isLost(tooth.optString("presence"))) lost++
        val surfaces = tooth.optJSONObject("surfaces")
        var toothFinding = (tooth.optJSONArray("findings")?.length() ?: 0) > 0
        var toothRestoration = false
        surfaces?.let { surfaces -> surfaces.keys().forEach { surface ->
            val observation = surfaces.optJSONObject(surface)
            if ((observation?.optJSONArray("findings")?.length() ?: 0) > 0) toothFinding = true
            if ((observation?.optJSONArray("restorations")?.length() ?: 0) > 0) toothRestoration = true
        } }
        if (toothFinding) findings++
        if (toothRestoration) restorations++
        if ((tooth.optJSONArray("treatmentStates")?.length() ?: 0) > 0) planned++
    }
    return ChartCounts(teeth.length(), assessed, findings, restorations, planned, lost)
}

// ---------------------------------------------------------------------------------------------
// Geometry
// ---------------------------------------------------------------------------------------------

private class FloatBuffer(capacity: Int) {
    var data = FloatArray(capacity); var size = 0
    fun add(value: Float) { if (size == data.size) data = data.copyOf(data.size * 2); data[size++] = value }
}

private class IntBuffer(capacity: Int) {
    var data = IntArray(capacity); var size = 0
    fun add(value: Int) { if (size == data.size) data = data.copyOf(data.size * 2); data[size++] = value }
}

private class Vec(val x: Float, val y: Float, val z: Float) {
    operator fun plus(o: Vec) = Vec(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: Vec) = Vec(x - o.x, y - o.y, z - o.z)
    operator fun times(s: Float) = Vec(x * s, y * s, z * s)
    fun dot(o: Vec) = x * o.x + y * o.y + z * o.z
    fun cross(o: Vec) = Vec(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)
    fun normalized(): Vec { val l = sqrt(dot(this)); return if (l < 1e-6f) Vec(0f, 1f, 0f) else Vec(x / l, y / l, z / l) }
}

/** One selectable slot: anchor points for badges/picking and its rendering facts. */
private class SlotInfo(
    val key: String, val tip: Vec, val cervical: Vec, val facial: Vec,
    val lost: Boolean, val planned: Boolean, val finding: Boolean, val restoration: Boolean, val treated: Boolean,
)

private class DentalScene(
    val vertices: FloatArray,  // 9 floats per triangle, model space
    val normals: FloatArray,   // 3 floats per triangle (face normal, used for culling)
    val vertexNormals: FloatArray, // 9 floats per triangle (smoothed, used for shading)
    val colors: IntArray,      // base ARGB per triangle
    val owners: IntArray,      // slot index, or -1 for gingiva
    val occlusion: FloatArray, // 3 floats per triangle: soft darkening toward the gumline
    val triangles: Int,
    val slots: List<SlotInfo>,
)

private object Palette {
    const val ENAMEL = 0xFFFFF6E4.toInt()
    const val UNASSESSED = 0xFFE3E8EC.toInt()
    const val UNERUPTED = 0xFFD5DEE5.toInt()
    const val FINDING = 0xFFF59E0B.toInt()
    const val RESTORATION = 0xFF4F7FE8.toInt()
    const val IMPLANT = 0xFFC9D2DA.toInt()
    const val PONTIC = 0xFFF1ECF8.toInt()
    const val GUM = 0xFFEAA2A5.toInt()
    const val GUM_DEEP = 0xFFD9868C.toInt()
    const val SELECT = 0xFF14B8A6.toInt()
    const val TREATED = 0xFF60A5FA.toInt()
}

private class SceneBuilder {
    val vertices = FloatBuffer(120_000)
    val normals = FloatBuffer(40_000)
    val colors = IntBuffer(14_000)
    val owners = IntBuffer(14_000)
    val occlusion = FloatBuffer(42_000)
    /** When set, vertices near the cervical line are darkened (cheap ambient occlusion). */
    var aoCervicalY = 0f; var aoSign = 0f; var aoHeight = 0f
    private fun ao(p: Vec): Float = if (aoHeight <= 0f) 1f else 0.74f + 0.26f * (((p.y - aoCervicalY) * aoSign) / aoHeight).coerceIn(0f, 1f)

    /** Adds a triangle whose normal faces away from [inside]. */
    fun triangle(a: Vec, b: Vec, c: Vec, inside: Vec, color: Int, owner: Int) {
        var n = (b - a).cross(c - a)
        if (n.dot(n) < 1e-12f) return
        n = n.normalized()
        val centroid = (a + b + c) * (1f / 3f)
        val outward = n.dot(centroid - inside) >= 0f
        val v = if (outward) arrayOf(a, b, c) else arrayOf(a, c, b)
        if (!outward) n = n * -1f
        for (p in v) { vertices.add(p.x); vertices.add(p.y); vertices.add(p.z); occlusion.add(ao(p)) }
        normals.add(n.x); normals.add(n.y); normals.add(n.z)
        colors.add(color); owners.add(owner)
    }

    /**
     * Smooth shading: average the face normals that share a vertex position within the same
     * object (tooth or gingiva), so curved enamel reads as polished rather than faceted.
     */
    fun smoothNormals(): FloatArray {
        val count = colors.size
        val v = vertices.data
        val accumulated = HashMap<Long, Int>(count * 2)
        val sums = FloatBuffer(count * 3)
        val slot = IntArray(count * 3)
        fun key(x: Float, y: Float, z: Float, owner: Int): Long {
            val qx = (Math.round(x * 2000f) + 32768).toLong() and 0xFFFF
            val qy = (Math.round(y * 2000f) + 32768).toLong() and 0xFFFF
            val qz = (Math.round(z * 2000f) + 32768).toLong() and 0xFFFF
            return ((owner + 2).toLong() shl 48) or (qx shl 32) or (qy shl 16) or qz
        }
        for (t in 0 until count) {
            val nx = normals.data[t * 3]; val ny = normals.data[t * 3 + 1]; val nz = normals.data[t * 3 + 2]
            for (k in 0 until 3) {
                val base = t * 9 + k * 3
                val id = key(v[base], v[base + 1], v[base + 2], owners.data[t])
                val index = accumulated.getOrPut(id) { sums.add(0f); sums.add(0f); sums.add(0f); sums.size / 3 - 1 }
                sums.data[index * 3] += nx; sums.data[index * 3 + 1] += ny; sums.data[index * 3 + 2] += nz
                slot[t * 3 + k] = index
            }
        }
        val out = FloatArray(count * 9)
        for (i in 0 until count * 3) {
            val index = slot[i]
            val x = sums.data[index * 3]; val y = sums.data[index * 3 + 1]; val z = sums.data[index * 3 + 2]
            val length = sqrt(x * x + y * y + z * z).takeIf { it > 1e-6f } ?: 1f
            out[i * 3] = x / length; out[i * 3 + 1] = y / length; out[i * 3 + 2] = z / length
        }
        return out
    }

    fun quad(a: Vec, b: Vec, c: Vec, d: Vec, inside: Vec, color: Int, owner: Int) {
        triangle(a, b, c, inside, color, owner); triangle(b, d, c, inside, color, owner)
    }
}

private enum class Kind { INCISOR, CANINE, PREMOLAR, MOLAR }

private fun capHeight(kind: Kind, u: Float, v: Float): Float = when (kind) {
    Kind.INCISOR -> 0.96f - 0.10f * v * v
    Kind.CANINE -> 1.0f - 0.29f * abs(u) - 0.13f * abs(v)
    else -> {
        var base = 0.73f
        val peaks = if (kind == Kind.PREMOLAR) listOf(0f to -0.47f, 0f to 0.47f)
        else listOf(-0.43f to -0.43f, 0.43f to -0.43f, -0.43f to 0.43f, 0.43f to 0.43f)
        for ((px, pz) in peaks) { val d = (u - px) * (u - px) + (v - pz) * (v - pz); base += 0.18f * exp(-d * 10f) }
        base -= 0.045f * exp(-v * v * 70f)
        if (kind == Kind.MOLAR) base -= 0.035f * exp(-u * u * 70f)
        base
    }
}

private fun superellipse(angle: Float, hx: Float, hz: Float, exponent: Float): Pair<Float, Float> {
    val c = cos(angle); val s = sin(angle)
    val x = Math.signum(c) * abs(c).pow(2f / exponent) * hx
    val z = Math.signum(s) * abs(s).pow(2f / exponent) * hz
    return x to z
}

private fun mix(a: Int, b: Int, t: Float): Int {
    fun ch(v: Int, shift: Int) = (v shr shift) and 0xFF
    fun lerp(shift: Int) = (ch(a, shift) + (ch(b, shift) - ch(a, shift)) * t).toInt().coerceIn(0, 255)
    return (0xFF shl 24) or (lerp(16) shl 16) or (lerp(8) shl 8) or lerp(0)
}

private fun buildScene(chart: JSONObject?): DentalScene {
    val builder = SceneBuilder()
    val slots = mutableListOf<SlotInfo>()
    val teeth = chart?.optJSONObject("teeth")
    val dentition = chart?.optString("dentition", "permanent")?.takeIf { it in setOf("primary", "permanent", "mixed") } ?: "permanent"
    if (dentition != "primary") {
        for (q in 1..4) buildQuadrant(builder, slots, teeth, q, primary = false, factor = 1f)
        buildGum(builder, upper = true, factor = 1f, wide = dentition == "mixed")
        buildGum(builder, upper = false, factor = 0.96f, wide = dentition == "mixed")
    }
    if (dentition != "permanent") {
        val factor = if (dentition == "mixed") 0.70f else 0.92f
        for (q in 5..8) buildQuadrant(builder, slots, teeth, q, primary = true, factor = factor)
        if (dentition == "primary") {
            buildGum(builder, upper = true, factor = factor * 0.78f, wide = false)
            buildGum(builder, upper = false, factor = factor * 0.75f, wide = false)
        }
    }
    return DentalScene(
        builder.vertices.data.copyOf(builder.vertices.size), builder.normals.data.copyOf(builder.normals.size),
        builder.smoothNormals(), builder.colors.data.copyOf(builder.colors.size), builder.owners.data.copyOf(builder.owners.size),
        builder.occlusion.data.copyOf(builder.occlusion.size),
        builder.colors.size, slots,
    )
}

private const val ARCH_A = 3.4f
private const val ARCH_B = 4.0f
private const val ARCH_SHIFT = 1.95f
private const val OCCLUSAL_GAP = 0.07f

private fun buildQuadrant(builder: SceneBuilder, slots: MutableList<SlotInfo>, teeth: JSONObject?, quadrant: Int, primary: Boolean, factor: Float) {
    val maxPosition = if (primary) 5 else 8
    val upper = quadrant in listOf(1, 2, 5, 6)
    val patientRight = quadrant in listOf(1, 4, 5, 8)
    val side = if (patientRight) -1f else 1f   // patient right appears on the viewer's left
    val jawScale = if (upper) 1f else 0.96f
    val spatial = factor * jawScale * (if (primary) 0.78f else 1f)
    for (position in 1..maxPosition) {
        val key = "$quadrant$position"
        val state = teeth?.optJSONObject(key)
        val theta = (position - 0.45f) / maxPosition * 1.62f
        val center = Vec(sin(theta) * ARCH_A * side * spatial, 0f, cos(theta) * ARCH_B * spatial - ARCH_SHIFT * spatial)
        val facial = Vec(sin(theta) * side / ARCH_A, 0f, cos(theta) / ARCH_B).normalized()
        // Mesial runs along the arch toward the midline (decreasing theta); exactly perpendicular to facial.
        val mesial = Vec(-cos(theta) * ARCH_A * side, 0f, sin(theta) * ARCH_B).normalized()
        val kind = when {
            position <= 2 -> Kind.INCISOR
            position == 3 -> Kind.CANINE
            primary || position >= 6 -> Kind.MOLAR
            else -> Kind.PREMOLAR
        }
        var sx = 0.72f; var sy = 0.96f; var sz = 0.40f
        if (position == 2 && kind == Kind.INCISOR) sx = 0.62f
        when (kind) {
            Kind.CANINE -> { sx = 0.64f; sy = 1.08f; sz = 0.62f }
            Kind.PREMOLAR -> { sx = 0.70f; sy = 0.86f; sz = 0.80f }
            Kind.MOLAR -> { sx = if (position == 8) 0.88f else 0.96f; sy = 0.80f; sz = 0.98f }
            else -> Unit
        }
        val scale = factor * jawScale
        sx *= scale; sy *= scale; sz *= scale
        val occlusal = if (upper) -1f else 1f
        val cervical = Vec(center.x, -occlusal * (OCCLUSAL_GAP + sy), center.z)
        val up = Vec(0f, occlusal, 0f)
        fun world(lx: Float, ly: Float, lz: Float) = cervical + mesial * lx + up * ly + facial * lz

        val presence = state?.optString("presence", "unknown") ?: "unknown"
        val replacement = state?.optString("replacement", "none") ?: "none"
        val assessment = state?.optString("assessment", "not_assessed") ?: "not_assessed"
        val lost = isLost(presence)
        val showCrown = !lost || replacement !in setOf("none", "")
        val surfaces = state?.optJSONObject("surfaces")
        fun codes(obj: JSONObject?, name: String) = (obj?.optJSONArray(name)?.length() ?: 0) > 0
        val surfaceFinding = HashMap<String, Boolean>()
        val surfaceRestoration = HashMap<String, Boolean>()
        surfaces?.let { surfaces -> surfaces.keys().forEach { s ->
            surfaceFinding[s] = codes(surfaces.optJSONObject(s), "findings")
            surfaceRestoration[s] = codes(surfaces.optJSONObject(s), "restorations")
        } }
        val wholeFinding = codes(state, "findings")
        val planned = codes(state, "treatmentStates")
        val treated = state?.optBoolean("treated") == true
        val slotIndex = slots.size
        slots.add(SlotInfo(key, world(0f, sy * 1.02f, 0f), world(0f, -0.05f, 0f), facial, lost && !showCrown, planned,
            wholeFinding || surfaceFinding.values.any { it }, surfaceRestoration.values.any { it }, treated))
        if (!showCrown) continue
        builder.aoCervicalY = cervical.y; builder.aoSign = occlusal; builder.aoHeight = sy * 0.45f

        var base = when {
            lost && replacement == "implant" -> Palette.IMPLANT
            lost && replacement == "pontic" -> Palette.PONTIC
            lost -> Palette.PONTIC
            presence == "unerupted" -> Palette.UNERUPTED
            assessment == "assessed" -> Palette.ENAMEL
            else -> Palette.UNASSESSED
        }
        if (treated) base = mix(base, Palette.TREATED, 0.22f)
        if (wholeFinding) base = mix(base, Palette.FINDING, 0.28f)
        fun surfaceColor(surface: String): Int = when {
            surfaceFinding[surface] == true -> mix(base, Palette.FINDING, 0.78f)
            surfaceRestoration[surface] == true -> mix(base, Palette.RESTORATION, 0.72f)
            else -> base
        }
        val topSurface = if (kind == Kind.INCISOR || kind == Kind.CANINE) "I" else "O"
        val exponent = if (kind == Kind.PREMOLAR || kind == Kind.MOLAR) 2.8f else 2.5f
        val hx = sx * 0.5f; val hz = sz * 0.5f
        val tipW = if (kind == Kind.INCISOR) 0.88f else 0.90f
        val tipD = when (kind) { Kind.INCISOR -> 0.46f; Kind.CANINE -> 0.80f; else -> 0.88f }
        val segments = 18
        val levels = 7
        val inside = world(0f, sy * 0.45f, 0f)

        fun sidePoint(angle: Float, level: Float): Vec {
            val (ex, ez) = superellipse(angle, hx, hz, exponent)
            val belly = 0.76f + sin(level * Math.PI.toFloat() * 0.85f) * 0.26f
            val t4 = level.pow(4)
            val w = belly + (tipW - belly) * t4
            val d = belly + (tipD - belly) * t4
            val top = capHeight(kind, ex * tipW / hx, ez * tipD / hz)
            return world(ex * w, level * top * sy, ez * d)
        }
        fun surfaceForAngle(angle: Float): String {
            val c = cos(angle); val s = sin(angle)
            return if (abs(s) >= abs(c)) (if (s > 0f) "F" else "L") else (if (c > 0f) "M" else "D")
        }
        val step = (2.0 * Math.PI / segments).toFloat()
        // Root neck (sinks into the gingiva) and lofted crown walls.
        for (segment in 0 until segments) {
            val a = step * segment; val b = step * (segment + 1)
            val color = surfaceColor(surfaceForAngle((a + b) * 0.5f))
            val (ax, az) = superellipse(a, hx * 0.62f, hz * 0.62f, exponent)
            val (bx, bz) = superellipse(b, hx * 0.62f, hz * 0.62f, exponent)
            builder.quad(world(ax, -sy * 0.16f, az), world(bx, -sy * 0.16f, bz), sidePoint(a, 0f), sidePoint(b, 0f), inside, base, slotIndex)
            for (level in 0 until levels) {
                val low = level.toFloat() / levels; val high = (level + 1).toFloat() / levels
                builder.quad(sidePoint(a, low), sidePoint(b, low), sidePoint(a, high), sidePoint(b, high), inside, color, slotIndex)
            }
        }
        // Occlusal / incisal table with cusp and fissure relief.
        val rings = 3
        val capColor = surfaceColor(topSurface)
        fun capPoint(angle: Float, radius: Float): Vec {
            val (ex, ez) = superellipse(angle, hx, hz, exponent)
            val x = ex * radius * tipW; val z = ez * radius * tipD
            return world(x, capHeight(kind, x / hx, z / hz) * sy, z)
        }
        val capInside = world(0f, sy * 0.2f, 0f)
        for (ring in 0 until rings) {
            val r0 = ring.toFloat() / rings; val r1 = (ring + 1).toFloat() / rings
            for (segment in 0 until segments) {
                val a = step * segment; val b = step * (segment + 1)
                if (ring == 0) builder.triangle(capPoint(a, 0f), capPoint(a, r1), capPoint(b, r1), capInside, capColor, slotIndex)
                else builder.quad(capPoint(a, r0), capPoint(b, r0), capPoint(a, r1), capPoint(b, r1), capInside, capColor, slotIndex)
            }
        }
    }
}

private fun buildGum(builder: SceneBuilder, upper: Boolean, factor: Float, wide: Boolean) {
    builder.aoHeight = 0f
    val sections = 64
    val ringSegments = 12
    val sign = if (upper) 1f else -1f
    val jaw = if (upper) 1f else 0.96f
    // Wider over the molars, whose crowns are deeper bucco-lingually than the incisors.
    fun rx(angle: Float) = ((if (wide) 0.72f else 0.44f) + 0.20f * (abs(angle) / 1.4f).coerceIn(0f, 1f)) * factor
    val ry = 0.24f * factor + 0.04f
    val inner = if (wide) -0.20f * factor else 0f
    // Crown height falls from ~1.0 at the incisors to ~0.8 at the molars; the gingival margin
    // follows the cervical line and overlaps each crown by only a thin collar.
    fun centerAt(angle: Float): Vec {
        val crown = (1.0f - 0.2f * (abs(angle) / 1.4f).coerceIn(0f, 1f)) * factor * jaw
        val height = sign * (OCCLUSAL_GAP + crown + ry - 0.10f)
        return Vec(sin(angle) * ARCH_A * factor, height, cos(angle) * ARCH_B * factor - ARCH_SHIFT * factor)
    }
    fun point(section: Int, ring: Int): Vec {
        val angle = -1.70f + section.toFloat() / sections * 3.40f
        val c = centerAt(angle)
        val outward = Vec(sin(angle) / ARCH_A, 0f, cos(angle) / ARCH_B).normalized()
        val cross = ring.toFloat() / ringSegments * (2.0 * Math.PI).toFloat()
        return c + outward * (cos(cross) * rx(angle) + inner) + Vec(0f, sin(cross) * ry, 0f)
    }
    for (section in 0 until sections) {
        val angle = -1.70f + (section + 0.5f) / sections * 3.40f
        val outward = Vec(sin(angle) / ARCH_A, 0f, cos(angle) / ARCH_B).normalized()
        val inside = centerAt(angle) + outward * inner
        for (ring in 0 until ringSegments) {
            // Slightly deeper tone on the tissue that faces away from the teeth.
            val facingTeeth = sin((ring + 0.5f) / ringSegments * 2f * Math.PI.toFloat()) * -sign > 0f
            val color = if (facingTeeth) Palette.GUM else Palette.GUM_DEEP
            builder.quad(point(section, ring), point(section + 1, ring), point(section, ring + 1), point(section + 1, ring + 1), inside, color, -1)
        }
    }
    // Close both ends of the tube so side views never look hollow.
    for (end in listOf(0, sections)) {
        val angle = -1.70f + end.toFloat() / sections * 3.40f
        val outward = Vec(sin(angle) / ARCH_A, 0f, cos(angle) / ARCH_B).normalized()
        val hub = centerAt(angle) + outward * inner
        val tangent = Vec(cos(angle) * ARCH_A, 0f, -sin(angle) * ARCH_B).normalized()
        val interior = hub + tangent * (if (end == 0) 1f else -1f)  // a point inside the tube
        for (ring in 0 until ringSegments) builder.triangle(hub, point(end, ring), point(end, ring + 1), interior, Palette.GUM_DEEP, -1)
    }
}

// ---------------------------------------------------------------------------------------------
// Rendering
// ---------------------------------------------------------------------------------------------

/** Reusable per-frame buffers so drawing does not allocate. */
private class FrameBuffers(triangles: Int) {
    val screen = FloatArray(triangles * 6)
    val sortKeys = LongArray(triangles)
    val shade = IntArray(triangles * 3)
    val outVertices = FloatArray(triangles * 6)
    val outColors = IntArray(triangles * 6)   // generous: platform checks may count floats
    val paint = Paint().apply { isAntiAlias = false; color = android.graphics.Color.WHITE }
    val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }
}

private fun shadeColor(base: Int, nx: Float, ny: Float, nz: Float, occlusion: Float = 1f): Int {
    // Camera-space lighting: soft key from upper left, cool fill, specular glint and rim.
    val key = max(0f, nx * -0.38f + ny * 0.62f + nz * 0.69f)
    val fill = max(0f, nx * 0.55f + ny * -0.15f + nz * 0.82f)
    val hz = nx * -0.19f + ny * 0.31f + nz * 0.93f
    val h1 = max(0f, hz); val h2 = h1 * h1; val h4 = h2 * h2; val h8 = h4 * h4; val h16 = h8 * h8
    val spec = h16 * h8 * h4 * 0.42f
    val edge = 1f - abs(nz)
    val rim = edge * edge * edge * 0.12f
    val light = (0.40f + key * 0.50f + fill * 0.18f) * occlusion
    fun ch(shift: Int, rimTint: Float): Int {
        val c = ((base shr shift) and 0xFF) / 255f
        return ((c * light + spec + rim * rimTint) * 255f).toInt().coerceIn(0, 255)
    }
    return (0xFF shl 24) or (ch(16, 0.8f) shl 16) or (ch(8, 1f) shl 8) or ch(0, 1f)
}

/**
 * Interactive 3D dentition preview.
 *
 * @param chart schema-2 chart snapshot (sparse teeth map). Null renders a neutral, unassessed
 *   reference model, e.g. on a clinician profile.
 * @param onToothTap called with the FDI key of the tapped tooth.
 */
@Composable
fun Teeth3DView(
    chart: JSONObject?,
    modifier: Modifier = Modifier,
    selectedTeeth: Set<String> = emptySet(),
    showLabels: Boolean = false,
    onToothTap: ((String) -> Unit)? = null,
) {
    val signature = chart?.optJSONObject("teeth")?.toString().orEmpty() + "|" + chart?.optString("dentition").orEmpty()
    // Geometry (~10–20k triangles) is built off the main thread so opening a profile never stutters;
    // the previous model stays on screen while a changed chart rebuilds.
    val sceneState = produceState<DentalScene?>(initialValue = null, signature) { value = withContext(Dispatchers.Default) { buildScene(chart) } }
    val scene = sceneState.value
    if (scene == null) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Text("Loading 3D model…", color = Color(0xFF0F766E), fontSize = 13.sp)
        }
        return
    }
    val buffers = remember(scene) { FrameBuffers(scene.triangles) }
    val density = LocalDensity.current
    var yaw by remember { mutableFloatStateOf(-0.75f) }
    var pitch by remember { mutableFloatStateOf(0.16f) }
    var zoom by remember { mutableFloatStateOf(1f) }
    val intro = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        // Brief reveal swing: makes the model read as 3D, then rests and waits for the user.
        intro.animateTo(1f, tween(1400, easing = FastOutSlowInEasing)) { yaw = -0.75f + 0.75f * value }
    }
    val summary = remember(signature) { chartCounts(chart) }
    val description = "Interactive 3D dental model. ${summary.recorded} recorded teeth, ${summary.findings} with findings, " +
        "${summary.restorations} with restorations, ${summary.planned} with planned care. Drag to rotate, pinch to zoom" +
        if (onToothTap != null) ", tap a tooth to open it." else "."
    val holder = remember(scene) { AnchorHolder() }
    val tapCallback by rememberUpdatedState(onToothTap)

    Canvas(
        modifier
            .semantics { contentDescription = description }
            .pointerInput(scene) {
                detectTransformGestures { _, pan, gestureZoom, _ ->
                    yaw += pan.x * 0.008f
                    pitch = (pitch + pan.y * 0.006f).coerceIn(-1.2f, 1.25f)
                    zoom = (zoom * gestureZoom).coerceIn(0.75f, 2.4f)
                }
            }
            .pointerInput(scene) {
                detectTapGestures(
                    onDoubleTap = { yaw = 0f; pitch = 0.16f; zoom = 1f },
                    onTap = { position ->
                        val callback = tapCallback ?: return@detectTapGestures
                        val anchors = holder.anchors
                        var best = -1; var bestScore = Float.MAX_VALUE
                        val radius = 30f * density.density
                        for (i in scene.slots.indices) {
                            if (anchors.size < (i + 1) * 3 || anchors[i * 3 + 2] <= 0f) continue
                            val dx = anchors[i * 3] - position.x; val dy = anchors[i * 3 + 1] - position.y
                            val distance = sqrt(dx * dx + dy * dy)
                            val score = distance + anchors[i * 3 + 2] * 2f
                            if (distance < radius && score < bestScore) { best = i; bestScore = score }
                        }
                        if (best >= 0) callback(scene.slots[best].key)
                    },
                )
            },
    ) {
        val w = size.width; val h = size.height
        val cx = w * 0.5f; val cy = h * 0.53f
        val distance = 13f
        val focal = min(w * 1.42f, h * 2.7f) * zoom
        val cosY = cos(yaw); val sinY = sin(yaw); val cosP = cos(pitch); val sinP = sin(pitch)
        // Rotation = Rx(pitch) * Ry(yaw)
        val r00 = cosY; val r01 = 0f; val r02 = sinY
        val r10 = sinP * sinY; val r11 = cosP; val r12 = -sinP * cosY
        val r20 = -cosP * sinY; val r21 = sinP; val r22 = cosP * cosY
        val selectedIndices = scene.slots.indices.filter { scene.slots[it].key in selectedTeeth }.toHashSet()

        // Backdrop: soft studio floor shadow.
        drawOval(
            Brush.radialGradient(listOf(Color(0x2A0F766E), Color.Transparent), center = Offset(cx, h * 0.86f), radius = w * 0.42f),
            topLeft = Offset(cx - w * 0.42f, h * 0.80f), size = androidx.compose.ui.geometry.Size(w * 0.84f, h * 0.12f),
        )

        val v = scene.vertices; val n = scene.normals; val vn = scene.vertexNormals
        var visible = 0
        for (t in 0 until scene.triangles) {
            val nx0 = n[t * 3]; val ny0 = n[t * 3 + 1]; val nz0 = n[t * 3 + 2]
            val nx = r00 * nx0 + r01 * ny0 + r02 * nz0
            val ny = r10 * nx0 + r11 * ny0 + r12 * nz0
            val nz = r20 * nx0 + r21 * ny0 + r22 * nz0
            var depthSum = 0f
            var facing = 0f
            val o = visible * 6
            for (k in 0 until 3) {
                val px = v[t * 9 + k * 3]; val py = v[t * 9 + k * 3 + 1]; val pz = v[t * 9 + k * 3 + 2]
                val x = r00 * px + r01 * py + r02 * pz
                val y = r10 * px + r11 * py + r12 * pz
                val z = r20 * px + r21 * py + r22 * pz
                val depth = distance - z
                if (k == 0) facing = nx * -x + ny * -y + nz * (distance - z)
                buffers.screen[o + k * 2] = cx + focal * x / depth
                buffers.screen[o + k * 2 + 1] = cy - focal * y / depth
                depthSum += depth
            }
            if (facing <= 0f) continue
            val owner = scene.owners[t]
            var base = scene.colors[t]
            if (owner >= 0 && owner in selectedIndices) base = mix(base, Palette.SELECT, 0.45f)
            for (k in 0 until 3) {
                val i = t * 9 + k * 3
                val sx = r00 * vn[i] + r01 * vn[i + 1] + r02 * vn[i + 2]
                val sy = r10 * vn[i] + r11 * vn[i + 1] + r12 * vn[i + 2]
                val sz = r20 * vn[i] + r21 * vn[i + 1] + r22 * vn[i + 2]
                buffers.shade[visible * 3 + k] = shadeColor(base, sx, sy, sz, scene.occlusion[t * 3 + k])
            }
            // Far triangles first; depth is positive, so sort descending via negation.
            val depthBits = ((100f - depthSum / 3f) * 10_000f).toLong().coerceIn(0, Int.MAX_VALUE.toLong())
            buffers.sortKeys[visible] = (depthBits shl 32) or visible.toLong()
            visible++
        }
        java.util.Arrays.sort(buffers.sortKeys, 0, visible)
        for (i in 0 until visible) {
            val source = (buffers.sortKeys[i] and 0xFFFFFFFFL).toInt()
            System.arraycopy(buffers.screen, source * 6, buffers.outVertices, i * 6, 6)
            buffers.outColors[i * 3] = buffers.shade[source * 3]
            buffers.outColors[i * 3 + 1] = buffers.shade[source * 3 + 1]
            buffers.outColors[i * 3 + 2] = buffers.shade[source * 3 + 2]
        }
        drawIntoCanvas { canvas ->
            if (visible > 0) canvas.nativeCanvas.drawVertices(
                android.graphics.Canvas.VertexMode.TRIANGLES, visible * 6, buffers.outVertices, 0,
                null, 0, buffers.outColors, 0, null, 0, 0, buffers.paint,
            )
        }

        // Overlays: status badges and recorded-loss markers, drawn only on the visible side.
        val anchors = FloatArray(scene.slots.size * 3)
        val badge = 7.5f * density.density
        scene.slots.forEachIndexed { index, slot ->
            fun project(p: Vec): Triple<Float, Float, Float> {
                val x = r00 * p.x + r01 * p.y + r02 * p.z
                val y = r10 * p.x + r11 * p.y + r12 * p.z
                val z = r20 * p.x + r21 * p.y + r22 * p.z
                val depth = distance - z
                return Triple(cx + focal * x / depth, cy - focal * y / depth, z)
            }
            val facingZ = r20 * slot.facial.x + r21 * slot.facial.y + r22 * slot.facial.z
            val anchor = project(if (slot.lost) slot.cervical else slot.tip)
            anchors[index * 3] = anchor.first; anchors[index * 3 + 1] = anchor.second
            anchors[index * 3 + 2] = if (facingZ > -0.55f) 0.0001f + (1f - facingZ) else 0f
            if (facingZ < -0.25f) return@forEachIndexed
            val point = Offset(anchor.first, anchor.second)
            if (slot.lost) {
                val s = badge * 0.8f
                drawLine(Color(0xFF64748B), point - Offset(s, s), point + Offset(s, s), 2.2f * density.density)
                drawLine(Color(0xFF64748B), point - Offset(s, -s), point + Offset(s, -s), 2.2f * density.density)
            }
            if (slot.planned) {
                val c = project(slot.cervical + slot.facial * 0.42f)
                val at = Offset(c.first, c.second)
                drawCircle(Color.White, badge + 1.6f * density.density, at)
                drawCircle(Color(0xFF0F766E), badge, at)
                drawLine(Color.White, at - Offset(badge * 0.5f, 0f), at + Offset(badge * 0.5f, 0f), 1.8f * density.density)
                drawLine(Color.White, at - Offset(0f, badge * 0.5f), at + Offset(0f, badge * 0.5f), 1.8f * density.density)
            }
            if (index in selectedIndices) {
                drawCircle(Color(0xFF14B8A6), badge * 2.2f, point, style = Stroke(2f * density.density))
            }
        }
        holder.anchors = anchors

        // Labels: always for the selection, optionally for every visible tooth.
        drawIntoCanvas { canvas ->
            scene.slots.forEachIndexed { index, slot ->
                if (!(showLabels || index in selectedIndices)) return@forEachIndexed
                if (anchors[index * 3 + 2] <= 0f) return@forEachIndexed
                val upperSlot = slot.key[0] in "1256"
                val x = anchors[index * 3]
                val y = anchors[index * 3 + 1]
                val selected = index in selectedIndices
                val text = ToothNotation.short(slot.key)
                buffers.label.textSize = (if (selected) 13f else 9.5f) * density.density
                val tw = buffers.label.measureText(text) / 2f + (if (selected) 8f else 4f) * density.density
                val ty = y + (if (upperSlot) 1f else -1f) * (if (selected) 26f else 14f) * density.density
                val th = buffers.label.textSize * 0.8f
                buffers.label.color = if (selected) 0xFF0F766E.toInt() else 0xE6FFFFFF.toInt()
                canvas.nativeCanvas.drawRoundRect(x - tw, ty - th, x + tw, ty + th * 0.55f, th, th, buffers.label)
                buffers.label.color = if (selected) android.graphics.Color.WHITE else 0xFF0F766E.toInt()
                canvas.nativeCanvas.drawText(text, x, ty + th * 0.18f, buffers.label)
            }
        }
    }
}

/** Screen anchors from the last frame, shared between drawing and tap handling. */
private class AnchorHolder { var anchors = FloatArray(0) }
