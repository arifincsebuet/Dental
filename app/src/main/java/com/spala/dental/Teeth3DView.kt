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
 * A small software renderer (smooth-shaded triangles, painter's sort, Canvas.drawVertices)
 * keeps the model on the profile screen itself. Its geometry, colours and camera are a port of
 * the Godot chart (assets/chart.gd, "shared tooth geometry spec v2") so both views match. The full Godot chart still owns detailed
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
// Shared tooth geometry spec v2 — a line-for-line port of app/src/main/assets/chart.gd so the
// profile preview and the full Godot chart show the same model. Change both together.
// ---------------------------------------------------------------------------------------------

private const val ARCH_A = 3.5f
private const val ARCH_B = 4.1f
private const val ARCH_SHIFT = 1.9f
private const val COLLAR = 1.30f

private object Palette {
    const val ENAMEL = 0xFFFFF7E8.toInt()
    const val UNKNOWN = 0xFFDFE6EB.toInt()
    const val UNERUPTED = 0xFFD5DEE5.toInt()
    const val IMPLANT = 0xFFE9EEF2.toInt()
    const val PONTIC = 0xFFF1ECF8.toInt()
    const val FINDING = 0xFFF59E0B.toInt()
    const val RESTORATION = 0xFF4F7FE8.toInt()
    const val TREATED = 0xFF60A5FA.toInt()
    const val DENTIN = 0xFFEAD7B2.toInt()
    const val TRANSLUCENT = 0xFFD3E0EA.toInt()
    const val GUM = 0xFFE8979D.toInt()
    const val GUM_DEEP = 0xFFD4848B.toInt()
    const val SULCUS = 0xFFC46D75.toInt()
    const val PALATE = 0xFFE3A0A5.toInt()
    const val SELECT = 0xFF14B8A6.toInt()
}

private const val MAT_ENAMEL: Byte = 0
private const val MAT_GUM: Byte = 1

private fun mix(a: Int, b: Int, t: Float): Int {
    val k = t.coerceIn(0f, 1f)
    fun ch(v: Int, shift: Int) = (v shr shift) and 0xFF
    fun lerp(shift: Int) = (ch(a, shift) + (ch(b, shift) - ch(a, shift)) * k).toInt().coerceIn(0, 255)
    return (0xFF shl 24) or (lerp(16) shl 16) or (lerp(8) shl 8) or lerp(0)
}
private fun scale(color: Int, f: Float): Int {
    fun ch(shift: Int) = (((color shr shift) and 0xFF) * f).toInt().coerceIn(0, 255)
    return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
}

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

/** One selectable slot: anchor points for badges/picking/labels. */
private class SlotInfo(val key: String, val tip: Vec, val cervical: Vec, val facial: Vec, val label: Vec, val lost: Boolean, val planned: Boolean)

private class DentalScene(
    val vertices: FloatArray,      // 9 per triangle
    val normals: FloatArray,       // 3 per triangle (face, culling)
    val vertexNormals: FloatArray, // 9 per triangle (smoothed, shading)
    val vertexColors: IntArray,    // 3 per triangle (baked enamel/gum colour)
    val materials: ByteArray,      // 1 per triangle
    val owners: IntArray,          // slot index or -1
    val triangles: Int,
    val slots: List<SlotInfo>,
)

private class SceneBuilder {
    val vertices = FloatBuffer(200_000)
    val normals = FloatBuffer(70_000)
    val colors = IntBuffer(70_000)
    val owners = IntBuffer(24_000)
    val materials = ArrayList<Byte>(24_000)
    val count get() = owners.size

    /** Adds a triangle whose normal faces away from [inside]; colours follow the vertices. */
    fun triangle(a: Vec, b: Vec, c: Vec, inside: Vec, ca: Int, cb: Int, cc: Int, owner: Int, material: Byte) {
        var n = (b - a).cross(c - a)
        if (n.dot(n) < 1e-14f) return
        n = n.normalized()
        val outward = n.dot((a + b + c) * (1f / 3f) - inside) >= 0f
        val points = if (outward) arrayOf(a, b, c) else arrayOf(a, c, b)
        val cols = if (outward) intArrayOf(ca, cb, cc) else intArrayOf(ca, cc, cb)
        if (!outward) n = n * -1f
        for (i in 0 until 3) { val p = points[i]; vertices.add(p.x); vertices.add(p.y); vertices.add(p.z); colors.add(cols[i]) }
        normals.add(n.x); normals.add(n.y); normals.add(n.z)
        owners.add(owner); materials.add(material)
    }

    fun quad(a: Vec, b: Vec, c: Vec, d: Vec, inside: Vec, ca: Int, cb: Int, cc: Int, cd: Int, owner: Int, material: Byte) {
        triangle(a, b, c, inside, ca, cb, cc, owner, material); triangle(b, d, c, inside, cb, cd, cc, owner, material)
    }

    /** Smooth shading: average face normals sharing a position within the same object. */
    fun smoothNormals(): FloatArray {
        val n = count; val v = vertices.data
        val index = HashMap<Long, Int>(n * 2); val sums = FloatBuffer(n * 3); val slot = IntArray(n * 3)
        fun key(x: Float, y: Float, z: Float, owner: Int): Long {
            val qx = (Math.round(x * 2000f) + 32768).toLong() and 0xFFFF
            val qy = (Math.round(y * 2000f) + 32768).toLong() and 0xFFFF
            val qz = (Math.round(z * 2000f) + 32768).toLong() and 0xFFFF
            return ((owner + 2).toLong() shl 48) or (qx shl 32) or (qy shl 16) or qz
        }
        for (t in 0 until n) for (k in 0 until 3) {
            val b = t * 9 + k * 3
            val i = index.getOrPut(key(v[b], v[b + 1], v[b + 2], owners.data[t])) { sums.add(0f); sums.add(0f); sums.add(0f); sums.size / 3 - 1 }
            sums.data[i * 3] += normals.data[t * 3]; sums.data[i * 3 + 1] += normals.data[t * 3 + 1]; sums.data[i * 3 + 2] += normals.data[t * 3 + 2]
            slot[t * 3 + k] = i
        }
        val out = FloatArray(n * 9)
        for (i in 0 until n * 3) {
            val s = slot[i]; val x = sums.data[s * 3]; val y = sums.data[s * 3 + 1]; val z = sums.data[s * 3 + 2]
            val l = sqrt(x * x + y * y + z * z).takeIf { it > 1e-6f } ?: 1f
            out[i * 3] = x / l; out[i * 3 + 1] = y / l; out[i * 3 + 2] = z / l
        }
        return out
    }

    fun build(slots: List<SlotInfo>) = DentalScene(vertices.data.copyOf(vertices.size), normals.data.copyOf(normals.size), smoothNormals(),
        colors.data.copyOf(colors.size), materials.toByteArray(), owners.data.copyOf(owners.size), count, slots)
}

private enum class Kind { INCISOR, CANINE, PREMOLAR, MOLAR }

// chart.gd: top_height
private fun topHeight(kind: Kind, x: Float, z: Float): Float = when (kind) {
    Kind.INCISOR -> 0.96f - 0.10f * z * z + 0.012f * cos(x * 9f)
    Kind.CANINE -> 1.0f - 0.29f * abs(x) - 0.13f * abs(z)
    else -> {
        var base = 0.73f
        val peaks = if (kind == Kind.PREMOLAR) listOf(0f to -0.47f, 0f to 0.47f) else listOf(-0.43f to -0.43f, 0.43f to -0.43f, -0.43f to 0.43f, 0.43f to 0.43f)
        for ((px, pz) in peaks) base += 0.18f * exp(-((x - px) * (x - px) + (z - pz) * (z - pz)) * 10f)
        base -= 0.045f * exp(-z * z * 70f)
        if (kind == Kind.MOLAR) base -= 0.035f * exp(-x * x * 70f)
        base
    }
}
// chart.gd: boundary
private fun boundary(angle: Float, hx: Float, hz: Float, exponent: Float): Pair<Float, Float> {
    val c = cos(angle); val s = sin(angle)
    return Math.signum(c) * abs(c).pow(2f / exponent) * hx to Math.signum(s) * abs(s).pow(2f / exponent) * hz
}
private fun tipWidth(kind: Kind) = if (kind == Kind.INCISOR) 0.88f else 0.90f
private fun tipDepth(kind: Kind) = when (kind) { Kind.INCISOR -> 0.46f; Kind.CANINE -> 0.80f; else -> 0.88f }
// chart.gd: lingual_profile
private fun lingualProfile(level: Float) = 1f - 0.20f * sin(Math.PI.toFloat() * level) * ((level - 0.12f) / 0.25f).coerceIn(0f, 1f)
// chart.gd: enamel
private fun enamel(color: Int, level: Float, kind: Kind): Int {
    var result = mix(color, Palette.DENTIN, 0.30f * (1f - level) * (1f - level))
    if (kind == Kind.INCISOR || kind == Kind.CANINE) result = mix(result, Palette.TRANSLUCENT, 0.42f * level.pow(5))
    return scale(result, 0.80f + 0.20f * (level / 0.35f).coerceIn(0f, 1f))
}
// chart.gd: papilla
private fun papilla(angle: Float, maxPosition: Int) = 0.5f + 0.5f * cos(2f * Math.PI.toFloat() * (abs(angle) * maxPosition / 1.62f - 0.05f))

private fun buildScene(chart: JSONObject?): DentalScene {
    val builder = SceneBuilder()
    val slots = mutableListOf<SlotInfo>()
    val teeth = chart?.optJSONObject("teeth")
    val dentition = chart?.optString("dentition", "permanent")?.takeIf { it in setOf("primary", "permanent", "mixed") } ?: "permanent"
    if (dentition != "primary") {
        buildJaw(builder, slots, teeth, 1, false, 1.0f, COLLAR)
        buildJaw(builder, slots, teeth, 4, false, 0.96f, COLLAR)
        buildGum(builder, COLLAR, 1.0f, dentition == "mixed", 8)
        buildGum(builder, -COLLAR, 0.96f, dentition == "mixed", 8)
    }
    if (dentition != "permanent") {
        val factor = if (dentition == "mixed") 0.70f else 0.92f
        val collar = if (dentition == "primary") COLLAR * 0.92f else COLLAR
        buildJaw(builder, slots, teeth, 5, true, factor, collar)
        buildJaw(builder, slots, teeth, 8, true, factor * 0.96f, collar)
        if (dentition == "primary") {
            buildGum(builder, collar, factor * 0.78f, false, 5)
            buildGum(builder, -collar, factor * 0.75f, false, 5)
        }
    }
    return builder.build(slots)
}

// chart.gd: build_jaw + crown_mesh
private fun buildJaw(builder: SceneBuilder, slots: MutableList<SlotInfo>, teeth: JSONObject?, firstQuadrant: Int, primary: Boolean, factor: Float, collar: Float) {
    val maxPosition = if (primary) 5 else 8
    val isUpper = firstQuadrant == 1 || firstQuadrant == 5
    val signY = if (isUpper) -1f else 1f
    val collarY = if (isUpper) collar else -collar
    val spatial = factor * (if (primary) 0.78f else 1f)
    for (side in 0..1) {
        val quadrant = if (isUpper) firstQuadrant + side else firstQuadrant - side
        val patientRight = quadrant in listOf(1, 4, 5, 8)
        val sideSign = if (patientRight) -1f else 1f
        for (position in 1..maxPosition) {
            val key = "$quadrant$position"
            val state = teeth?.optJSONObject(key)
            val theta = (position - 0.45f) / maxPosition * 1.62f
            val origin = Vec(sin(theta) * ARCH_A * sideSign * spatial, collarY, (cos(theta) * ARCH_B - ARCH_SHIFT) * spatial)
            val facial = Vec(sin(theta) * sideSign / ARCH_A, 0f, cos(theta) / ARCH_B).normalized()
            // Godot basis: local +Z = facial, local +X = up × facial (mesial on the patient's right).
            val localX = Vec(0f, 1f, 0f).cross(facial).normalized()
            val mesialSign = if (patientRight) 1f else -1f
            fun world(lx: Float, ly: Float, lz: Float) = origin + localX * lx + Vec(0f, ly, 0f) + facial * lz
            val kind = when { position <= 2 -> Kind.INCISOR; position == 3 -> Kind.CANINE; primary || position >= 6 -> Kind.MOLAR; else -> Kind.PREMOLAR }
            var sx = 0.72f; var sy = 0.96f; var sz = 0.39f
            if (position == 2) sx = 0.62f
            when (kind) {
                Kind.CANINE -> { sx = 0.64f; sy = 1.08f; sz = 0.61f }
                Kind.PREMOLAR -> { sx = 0.71f; sy = 0.86f; sz = 0.80f }
                Kind.MOLAR -> { sx = if (position == 8) 0.88f else 0.96f; sy = 0.80f; sz = 0.98f }
                else -> Unit
            }
            sx *= factor; sy *= factor; sz *= factor

            val presence = state?.optString("presence", "unknown") ?: "unknown"
            val replacement = state?.optString("replacement", "none") ?: "none"
            val lost = isLost(presence)
            val showCrown = !lost || replacement !in setOf("none", "")
            val planned = (state?.optJSONArray("treatmentStates")?.length() ?: 0) > 0
            val slotIndex = slots.size
            slots.add(SlotInfo(key, world(0f, signY * sy * 1.02f, 0f), world(0f, -signY * 0.05f, 0f), facial,
                world(0f, signY * (sy + 0.24f), sz * 0.35f), lost && !showCrown, planned))
            if (!showCrown) continue

            // chart.gd: base colour and surface_colors
            var base = if (state?.optString("assessment", "not_assessed") == "assessed") Palette.ENAMEL else Palette.UNKNOWN
            if (presence == "unerupted") base = Palette.UNERUPTED
            if (lost) base = if (replacement == "implant") Palette.IMPLANT else Palette.PONTIC
            if (state?.optBoolean("treated") == true) base = mix(base, Palette.TREATED, 0.22f)
            if ((state?.optJSONArray("findings")?.length() ?: 0) > 0) base = mix(base, Palette.FINDING, 0.28f)
            val surfaces = state?.optJSONObject("surfaces")
            fun surfaceColor(code: String): Int {
                val observation = surfaces?.optJSONObject(code)
                var color = base
                if ((observation?.optJSONArray("restorations")?.length() ?: 0) > 0) color = mix(base, Palette.RESTORATION, 0.72f)
                if ((observation?.optJSONArray("findings")?.length() ?: 0) > 0) color = mix(base, Palette.FINDING, 0.78f)
                return color
            }
            val top = surfaceColor(if (position <= 3) "I" else "O")
            fun sideSurface(angle: Float): String {
                val c = cos(angle); val s = sin(angle)
                return if (abs(s) >= abs(c)) (if (s > 0f) "F" else "L") else (if ((c > 0f) == (mesialSign > 0f)) "M" else "D")
            }

            val exponent = if (kind == Kind.PREMOLAR || kind == Kind.MOLAR) 2.8f else 2.5f
            val hx = sx * 0.5f; val hz = sz * 0.5f
            val tw = tipWidth(kind); val td = tipDepth(kind)
            // chart.gd: crown_side (y runs from the collar toward the biting surface: sign_y)
            fun side(angle: Float, level: Float): Vec {
                val (ex, ez) = boundary(angle, hx, hz, exponent)
                val belly = 0.76f + sin(level * Math.PI.toFloat() * 0.85f) * 0.26f
                val t4 = level.pow(4)
                var z = ez * (belly + (td - belly) * t4)
                if (z < 0f && (kind == Kind.INCISOR || kind == Kind.CANINE)) z *= lingualProfile(level)
                val height = topHeight(kind, ex * tw / hx, ez * td / hz)
                return world(ex * (belly + (tw - belly) * t4), level * height * sy * signY, z)
            }
            fun cap(angle: Float, radius: Float): Vec {
                val (ex, ez) = boundary(angle, hx, hz, exponent)
                val x = ex * radius * tw; val z = ez * radius * td
                return world(x, topHeight(kind, x / hx, z / hz) * sy * signY, z)
            }
            val inside = world(0f, signY * sy * 0.45f, 0f)
            val segments = 20; val levels = 8
            val step = (2.0 * Math.PI / segments).toFloat()
            for (segment in 0 until segments) {
                val a = step * segment; val b = step * (segment + 1)
                val face = surfaceColor(sideSurface((a + b) * 0.5f))
                // Neck: continues a little into the gingiva so no gap shows at the margin.
                val (nax, naz) = boundary(a, hx * 0.62f, hz * 0.62f, exponent)
                val (nbx, nbz) = boundary(b, hx * 0.62f, hz * 0.62f, exponent)
                val dark = enamel(face, 0f, kind)
                builder.quad(world(nax, -signY * sy * 0.16f, naz), world(nbx, -signY * sy * 0.16f, nbz), side(a, 0f), side(b, 0f), inside,
                    dark, dark, dark, dark, slotIndex, MAT_ENAMEL)
                for (level in 0 until levels) {
                    val lo = level.toFloat() / levels; val hi = (level + 1).toFloat() / levels
                    builder.quad(side(a, lo), side(b, lo), side(a, hi), side(b, hi), inside,
                        enamel(face, lo, kind), enamel(face, lo, kind), enamel(face, hi, kind), enamel(face, hi, kind), slotIndex, MAT_ENAMEL)
                }
            }
            val capInside = world(0f, signY * sy * 0.2f, 0f)
            val topColor = enamel(top, 1f, kind)
            val rings = 4
            for (ring in 0 until rings) {
                val r0 = ring.toFloat() / rings; val r1 = (ring + 1).toFloat() / rings
                for (segment in 0 until segments) {
                    val a = step * segment; val b = step * (segment + 1)
                    if (ring == 0) builder.triangle(cap(a, 0f), cap(a, r1), cap(b, r1), capInside, topColor, topColor, topColor, slotIndex, MAT_ENAMEL)
                    else builder.quad(cap(a, r0), cap(b, r0), cap(a, r1), cap(b, r1), capInside, topColor, topColor, topColor, topColor, slotIndex, MAT_ENAMEL)
                }
            }
        }
    }
}

// chart.gd: gum_band
private fun buildGum(builder: SceneBuilder, height: Float, factor: Float, wide: Boolean, maxPosition: Int) {
    val sections = 84; val rings = 14
    val radiusY = 0.24f * factor + 0.04f
    val inner = if (wide) -0.20f * factor else 0f
    val up = if (height > 0f) 1f else -1f
    val centerY = height + up * (radiusY - 0.12f)
    val tau = (2.0 * Math.PI).toFloat()
    class P(val p: Vec, val color: Int)
    fun point(section: Int, ring: Int): P {
        val angle = -1.70f + section.toFloat() / sections * 3.40f
        val cross = ring.toFloat() / rings * tau
        val radiusX = ((if (wide) 0.72f else 0.44f) + 0.20f * (abs(angle) / 1.4f).coerceIn(0f, 1f)) * factor
        val outward = Vec(sin(angle) / ARCH_A, 0f, cos(angle) / ARCH_B).normalized()
        val center = Vec(sin(angle) * ARCH_A * factor, centerY, (cos(angle) * ARCH_B - ARCH_SHIFT) * factor)
        val towardTeeth = max(0f, -sin(cross) * up)
        val reach = 0.16f * factor * towardTeeth * papilla(angle, maxPosition)
        // Alveolar ridge / model base: the half away from the teeth is taller and squarer.
        var sn = sin(cross); var cs = cos(cross); var stretch = 1f
        if (sn * up > 0f) { sn = Math.signum(sn) * abs(sn).pow(0.45f); cs = Math.signum(cs) * abs(cs).pow(0.7f); stretch = 1.9f }
        val p = center + outward * (cs * radiusX + inner) + Vec(0f, sn * radiusY * stretch - up * reach, 0f)
        val color = mix(mix(Palette.GUM, Palette.GUM_DEEP, max(0f, sin(cross) * up) * 0.6f), Palette.SULCUS, 0.35f * towardTeeth.pow(4))
        return P(p, color)
    }
    for (section in 0 until sections) {
        val angle = -1.70f + (section + 0.5f) / sections * 3.40f
        val outward = Vec(sin(angle) / ARCH_A, 0f, cos(angle) / ARCH_B).normalized()
        val inside = Vec(sin(angle) * ARCH_A * factor, centerY, (cos(angle) * ARCH_B - ARCH_SHIFT) * factor) + outward * inner
        for (ring in 0 until rings) {
            val a = point(section, ring); val b = point(section + 1, ring); val c = point(section, ring + 1); val d = point(section + 1, ring + 1)
            builder.quad(a.p, b.p, c.p, d.p, inside, a.color, b.color, c.color, d.color, -1, MAT_GUM)
        }
    }
    if (up > 0f) buildPalate(builder, centerY, factor, wide)
    for (end in listOf(0, sections)) {
        val angle = -1.70f + end.toFloat() / sections * 3.40f
        val outward = Vec(sin(angle) / ARCH_A, 0f, cos(angle) / ARCH_B).normalized()
        val hub = Vec(sin(angle) * ARCH_A * factor, centerY, (cos(angle) * ARCH_B - ARCH_SHIFT) * factor) + outward * inner
        val tangent = Vec(cos(angle) * ARCH_A, 0f, -sin(angle) * ARCH_B).normalized()
        val interior = hub + tangent * (if (end == 0) 1f else -1f) // a point inside the tube
        for (ring in 0 until rings) {
            val a = point(end, ring); val b = point(end, ring + 1)
            builder.triangle(hub, a.p, b.p, interior, Palette.GUM_DEEP, a.color, b.color, -1, MAT_GUM)
        }
    }
}

// chart.gd: add_palate — domed palate closing the upper arch.
private fun buildPalate(builder: SceneBuilder, centerY: Float, factor: Float, wide: Boolean) {
    val sections = 44; val rows = 6
    val peak = centerY + 0.42f * factor
    fun point(section: Int, row: Int): Vec {
        val angle = -1.35f + section.toFloat() / sections * 2.70f // interior of the arch only
        val t = row.toFloat() / rows
        val radiusX = ((if (wide) 0.72f else 0.44f) + 0.20f * (abs(angle) / 1.4f).coerceIn(0f, 1f)) * factor
        val outward = Vec(sin(angle) / ARCH_A, 0f, cos(angle) / ARCH_B).normalized()
        val lingual = Vec(sin(angle) * ARCH_A * factor, centerY, (cos(angle) * ARCH_B - ARCH_SHIFT) * factor) + outward * (-radiusX + if (wide) -0.20f * factor else 0f)
        return Vec(lingual.x * (1f - t), centerY + (peak - centerY) * t * t * (3f - 2f * t), lingual.z)
    }
    for (section in 0 until sections) for (row in 0 until rows) {
        val a = point(section, row); val b = point(section + 1, row); val c = point(section, row + 1); val d = point(section + 1, row + 1)
        val above = (a + d) * 0.5f + Vec(0f, 1f, 0f) // the palate faces down toward the teeth
        builder.quad(a, b, c, d, above, Palette.PALATE, Palette.PALATE, Palette.PALATE, Palette.PALATE, -1, MAT_GUM)
    }
}

// ---------------------------------------------------------------------------------------------
// Rendering
// ---------------------------------------------------------------------------------------------

private class FrameBuffers(triangles: Int) {
    val screen = FloatArray(triangles * 6)
    val sortKeys = LongArray(triangles)
    val shade = IntArray(triangles * 3)
    val outVertices = FloatArray(triangles * 6)
    val outColors = IntArray(triangles * 6)   // generous: platform checks may count floats
    val paint = Paint().apply { isAntiAlias = true; isDither = true; color = android.graphics.Color.WHITE }
    val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }
}

/** Camera-space lights approximating the Godot studio rig (warm key, cool fill, back rim, low bounce). */
private object Light {
    private fun n(x: Float, y: Float, z: Float): FloatArray { val l = sqrt(x * x + y * y + z * z); return floatArrayOf(x / l, y / l, z / l) }
    val key = n(-0.45f, 0.62f, 0.64f)
    val fill = n(0.72f, 0.12f, 0.68f)
    val back = n(0.0f, 0.35f, -0.94f)
    val bounce = n(0.0f, -0.75f, 0.66f)
    val keyHalf = n(key[0], key[1], key[2] + 1f)
    val fillHalf = n(fill[0], fill[1], fill[2] + 1f)
}

private fun pow2n(x: Float, n: Int): Float { var r = x; repeat(n) { r *= r }; return r } // x^(2^n)

/**
 * Enamel: wrap-diffuse (soft subsurface look), sharp Blinn highlight, cool Fresnel rim.
 * Gingiva: softer, broader sheen. Matches the Godot materials (roughness 0.18 / 0.45, rim).
 */
private fun shade(base: Int, nx: Float, ny: Float, nz: Float, material: Byte): Int {
    val k = Light.key; val f = Light.fill; val b = Light.back; val bo = Light.bounce
    val wrap = if (material == MAT_ENAMEL) 0.35f else 0.2f
    fun diffuse(l: FloatArray) = max(0f, (nx * l[0] + ny * l[1] + nz * l[2] + wrap) / (1f + wrap))
    val light = 0.40f + 0.62f * diffuse(k) + 0.22f * diffuse(f) + 0.10f * diffuse(bo)
    val hk = max(0f, nx * Light.keyHalf[0] + ny * Light.keyHalf[1] + nz * Light.keyHalf[2])
    val hf = max(0f, nx * Light.fillHalf[0] + ny * Light.fillHalf[1] + nz * Light.fillHalf[2])
    val spec = if (material == MAT_ENAMEL) pow2n(hk, 6) * 0.55f + pow2n(hf, 5) * 0.12f else pow2n(hk, 4) * 0.14f
    val edge = 1f - abs(nz)
    val rim = edge * edge * edge * (if (material == MAT_ENAMEL) 0.20f else 0.12f) + max(0f, nx * b[0] + ny * b[1] + nz * b[2]) * 0.10f
    fun ch(shift: Int, rimTint: Float): Int {
        val c = ((base shr shift) and 0xFF) / 255f
        return ((c * light + spec + rim * rimTint) * 255f).toInt().coerceIn(0, 255)
    }
    return (0xFF shl 24) or (ch(16, 0.85f) shl 16) or (ch(8, 0.95f) shl 8) or ch(0, 1f)
}

/**
 * Interactive 3D dentition preview (same model and camera as the full Godot chart).
 *
 * @param chart schema-2 chart snapshot (sparse teeth map). Null renders a neutral reference model.
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
    // Geometry is built off the main thread; the previous model stays visible while a changed chart rebuilds.
    val sceneState = produceState<DentalScene?>(initialValue = null, signature) { value = withContext(Dispatchers.Default) { buildScene(chart) } }
    val scene = sceneState.value
    if (scene == null) {
        Box(modifier, contentAlignment = Alignment.Center) { Text("Loading 3D model…", color = Color(0xFF0F766E), fontSize = 13.sp) }
        return
    }
    val buffers = remember(scene) { FrameBuffers(scene.triangles) }
    val density = LocalDensity.current
    // Same conventions as chart.gd: rig rotation (pitch, yaw); camera raised by 0.30 × distance.
    var yaw by remember { mutableFloatStateOf(-0.6f) }
    var pitch by remember { mutableFloatStateOf(0f) }
    var zoom by remember { mutableFloatStateOf(1f) }
    val intro = remember { Animatable(0f) }
    LaunchedEffect(Unit) { intro.animateTo(1f, tween(1400, easing = FastOutSlowInEasing)) { yaw = -0.6f + 0.6f * value } }
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
                    yaw += pan.x * 0.006f
                    pitch = (pitch + pan.y * 0.005f).coerceIn(-1.35f, 1.35f)
                    zoom = (zoom * gestureZoom).coerceIn(0.75f, 2.4f)
                }
            }
            .pointerInput(scene) {
                detectTapGestures(
                    onDoubleTap = { yaw = 0f; pitch = 0f; zoom = 1f },
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
        val cx = w * 0.5f; val cy = h * 0.5f
        // Godot: camera at (0, 0.30 d, d), d = 12 (Front view), fov 40° (vertical).
        val distance = 12f * sqrt(1.09f)
        val focal = min(h * 0.5f / 0.36397f, w * 1.9f) * zoom
        val elevation = 0.2915f
        val cosY = cos(yaw); val sinY = sin(yaw); val cosP = cos(pitch); val sinP = sin(pitch); val cosE = cos(elevation); val sinE = sin(elevation)
        // M = Rx(elevation) · Ry(yaw) · Rx(pitch)   (Godot YXZ Euler on the rig, then the raised camera)
        val a00 = cosY; val a01 = sinY * sinP; val a02 = sinY * cosP
        val a10 = 0f; val a11 = cosP; val a12 = -sinP
        val a20 = -sinY; val a21 = cosY * sinP; val a22 = cosY * cosP
        val r00 = a00; val r01 = a01; val r02 = a02
        val r10 = cosE * a10 - sinE * a20; val r11 = cosE * a11 - sinE * a21; val r12 = cosE * a12 - sinE * a22
        val r20 = sinE * a10 + cosE * a20; val r21 = sinE * a11 + cosE * a21; val r22 = sinE * a12 + cosE * a22
        val selectedIndices = scene.slots.indices.filter { scene.slots[it].key in selectedTeeth }.toHashSet()

        // Soft contact shadow under the model.
        drawOval(
            Brush.radialGradient(listOf(Color(0x260F3D3A), Color.Transparent), center = Offset(cx, cy + h * 0.33f), radius = w * 0.40f),
            topLeft = Offset(cx - w * 0.40f, cy + h * 0.28f), size = androidx.compose.ui.geometry.Size(w * 0.80f, h * 0.10f),
        )

        val v = scene.vertices; val n = scene.normals; val vn = scene.vertexNormals
        var visible = 0
        for (t in 0 until scene.triangles) {
            val nx0 = n[t * 3]; val ny0 = n[t * 3 + 1]; val nz0 = n[t * 3 + 2]
            val nx = r00 * nx0 + r01 * ny0 + r02 * nz0
            val ny = r10 * nx0 + r11 * ny0 + r12 * nz0
            val nz = r20 * nx0 + r21 * ny0 + r22 * nz0
            var depthSum = 0f; var facing = 0f
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
            val selected = scene.owners[t] >= 0 && scene.owners[t] in selectedIndices
            val material = scene.materials[t]
            for (k in 0 until 3) {
                val i = t * 9 + k * 3
                val sx = r00 * vn[i] + r01 * vn[i + 1] + r02 * vn[i + 2]
                val sy = r10 * vn[i] + r11 * vn[i + 1] + r12 * vn[i + 2]
                val sz = r20 * vn[i] + r21 * vn[i + 1] + r22 * vn[i + 2]
                var base = scene.vertexColors[t * 3 + k]
                if (selected) base = mix(base, Palette.SELECT, 0.45f)
                buffers.shade[visible * 3 + k] = shade(base, sx, sy, sz, material)
            }
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

        fun project(p: Vec): Triple<Float, Float, Float> {
            val x = r00 * p.x + r01 * p.y + r02 * p.z
            val y = r10 * p.x + r11 * p.y + r12 * p.z
            val z = r20 * p.x + r21 * p.y + r22 * p.z
            val depth = distance - z
            return Triple(cx + focal * x / depth, cy - focal * y / depth, z)
        }
        // Overlays: planned-care "+" (as in Godot), recorded-loss cross, selection ring.
        val anchors = FloatArray(scene.slots.size * 3)
        val labels = FloatArray(scene.slots.size * 2)
        val badge = 7.5f * density.density
        scene.slots.forEachIndexed { index, slot ->
            val facingZ = r20 * slot.facial.x + r21 * slot.facial.y + r22 * slot.facial.z
            val anchor = project(if (slot.lost) slot.cervical else slot.tip)
            anchors[index * 3] = anchor.first; anchors[index * 3 + 1] = anchor.second
            anchors[index * 3 + 2] = if (facingZ > -0.55f) 0.0001f + (1f - facingZ) else 0f
            val labelAt = project(slot.label)
            labels[index * 2] = labelAt.first; labels[index * 2 + 1] = labelAt.second
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
            if (index in selectedIndices) drawCircle(Color(0xFF14B8A6), badge * 2.2f, point, style = Stroke(2f * density.density))
        }
        holder.anchors = anchors

        // Tooth numbers at the same 3D anchor as the Godot labels (beyond the biting edge).
        drawIntoCanvas { canvas ->
            scene.slots.forEachIndexed { index, slot ->
                if (!(showLabels || index in selectedIndices) || anchors[index * 3 + 2] <= 0f) return@forEachIndexed
                val selected = index in selectedIndices
                val text = ToothNotation.short(slot.key)
                val x = labels[index * 2]; val y = labels[index * 2 + 1]
                buffers.label.textSize = (if (selected) 13f else 9.5f) * density.density
                val tw = buffers.label.measureText(text) / 2f + (if (selected) 8f else 4f) * density.density
                val th = buffers.label.textSize * 0.8f
                buffers.label.color = if (selected) 0xFF0F766E.toInt() else 0xE6FFFFFF.toInt()
                canvas.nativeCanvas.drawRoundRect(x - tw, y - th, x + tw, y + th * 0.55f, th, th, buffers.label)
                buffers.label.color = if (selected) android.graphics.Color.WHITE else 0xFF334155.toInt()
                canvas.nativeCanvas.drawText(text, x, y + th * 0.18f, buffers.label)
            }
        }
    }
}

/** Screen anchors from the last frame, shared between drawing and tap handling. */
private class AnchorHolder { var anchors = FloatArray(0) }
