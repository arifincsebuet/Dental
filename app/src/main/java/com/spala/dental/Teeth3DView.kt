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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.viewinterop.AndroidView
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
    const val PALATE = 0xFFE9AAAF.toInt()
    const val MUCOSA = 0xFFC8606B.toInt()
    const val MARGIN = 0xFFF2AEB2.toInt()
    const val VESSEL = 0xFFA3424F.toInt()
    const val RUGA = 0xFFEDB3B7.toInt()
    const val CEMENTUM = 0xFFE6D2A8.toInt()
    const val APEX = 0xFFC9AD7C.toInt()
    const val FISSURE = 0xFFC4A57A.toInt()
    const val SELECT = 0xFF14B8A6.toInt()
}

private const val MAT_ENAMEL: Byte = 0
private const val MAT_GUM: Byte = 1
private const val MAT_ROOT: Byte = 2

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
// chart.gd: mottle — stable per-vertex enamel brightness variation
private fun mottle(color: Int, p: Vec): Int {
    val v = sin(p.x * 12.9898 + p.y * 78.233 + p.z * 37.719) * 43758.5453
    val n = (v - kotlin.math.floor(v)).toFloat()
    return scale(color, 0.975f + 0.05f * n)
}
// chart.gd: fissure_stain
private fun fissureStain(color: Int, kind: Kind, u: Float, v: Float): Int {
    if (kind != Kind.PREMOLAR && kind != Kind.MOLAR) return color
    var groove = exp(-v * v * 70f)
    if (kind == Kind.MOLAR) groove = max(groove, exp(-u * u * 70f) * 0.8f)
    return mix(color, Palette.FISSURE, 0.50f * groove)
}
// chart.gd: root_specs — [mesial, facial offset, length × crown height, radius x, radius z, mesial tilt, facial tilt]
private fun rootSpecs(kind: Kind, position: Int, upper: Boolean, primary: Boolean): List<FloatArray> {
    val specs = when (kind) {
        Kind.INCISOR -> listOf(floatArrayOf(0f, 0f, if (upper && position == 1) 1.30f else 1.20f, 0.70f, 0.90f, 0f, 0f))
        Kind.CANINE -> listOf(floatArrayOf(0f, 0f, 1.65f, 0.70f, 0.90f, 0f, 0f))
        Kind.PREMOLAR -> if (upper && position == 4) listOf(floatArrayOf(0f, 0.42f, 1.25f, 0.55f, 0.42f, 0f, 0.10f), floatArrayOf(0f, -0.42f, 1.20f, 0.55f, 0.42f, 0f, -0.10f))
            else listOf(floatArrayOf(0f, 0f, 1.35f, 0.62f, 0.88f, 0f, 0f))
        Kind.MOLAR -> if (upper) listOf(floatArrayOf(0.45f, 0.36f, 1.15f, 0.38f, 0.40f, 0.12f, 0.08f), floatArrayOf(-0.45f, 0.36f, 1.05f, 0.36f, 0.38f, -0.12f, 0.08f), floatArrayOf(0f, -0.45f, 1.25f, 0.40f, 0.42f, 0f, -0.18f))
            else listOf(floatArrayOf(0.45f, 0f, 1.20f, 0.40f, 0.80f, 0.10f, 0f), floatArrayOf(-0.45f, 0f, 1.10f, 0.38f, 0.74f, -0.08f, 0f))
    }
    if (primary) specs.forEach { it[2] *= 0.8f; it[5] *= 1.6f; it[6] *= 1.6f }
    return specs
}
// chart.gd: root_point — local (x mesial-signed, y toward the apex, z facial)
private fun rootPoint(spec: FloatArray, angle: Float, t: Float, sx: Float, sy: Float, sz: Float, signY: Float, mesialSign: Float): Triple<Float, Float, Float> {
    val length = spec[2] * sy
    val taper = 0.10f + 0.90f * (1f - t.pow(1.8f)).pow(0.7f) // wide neck, rounded apex
    val (ex, ez) = boundary(angle, sx * 0.5f * spec[3] * taper, sz * 0.5f * spec[4] * taper, 2.4f)
    val cx = spec[0] * sx * 0.5f * (1f - 0.25f * t) + spec[5] * length * t.pow(1.5f) - 0.06f * length * t * t
    val cz = spec[1] * sz * 0.5f * (1f - 0.25f * t) + spec[6] * length * t.pow(1.5f)
    return Triple(ex + cx * mesialSign, -signY * (t * length - 0.02f), ez + cz)
}
private fun smoothstep(a: Float, b: Float, x: Float): Float { val t = ((x - a) / (b - a)).coerceIn(0f, 1f); return t * t * (3f - 2f * t) }
// chart.gd: gum_color — zones, vessels and stippling. Alpha carries gloss for the preview shader
// (matte stippled attached gingiva → shiny alveolar mucosa); Godot uses its roughness texture instead.
private fun gumColor(toward: Float, apical: Float, angle: Float, cross: Float, p: Vec): Int {
    var c = mix(Palette.GUM, Palette.MUCOSA, smoothstep(0.35f, 0.75f, apical))
    c = mix(c, Palette.MARGIN, 0.5f * smoothstep(0.55f, 0.9f, toward))
    c = mix(c, Palette.SULCUS, 0.35f * toward.pow(6))
    val mucosa = smoothstep(0.45f, 0.85f, apical)
    val vessel = (0.5f + 0.5f * sin(angle * 11f + 2.2f * sin(cross * 3f + angle * 4f))).pow(10)
    c = mix(c, Palette.VESSEL, 0.55f * mucosa * vessel)
    val v = sin(p.x * 12.9898 + p.y * 78.233 + p.z * 37.719) * 43758.5453
    val n = (v - kotlin.math.floor(v)).toFloat()
    c = scale(c, 1f + (n - 0.5f) * 0.10f * (1f - mucosa))
    val gloss = (0.25f + 0.75f * mucosa).coerceIn(0f, 1f)
    return (c and 0x00FFFFFF) or ((gloss * 255f).toInt() shl 24)
}
// chart.gd: ruga — transverse palatal ridges near the front of the palate
private fun ruga(angle: Float, t: Float): Float =
    max(0f, sin(t * Math.PI.toFloat() * 6f)).pow(2) * (1f - abs(angle) / 0.75f).coerceIn(0f, 1f) * sin(t * Math.PI.toFloat())
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
            fun capColor(angle: Float, radius: Float, p: Vec): Int {
                val (ex, ez) = boundary(angle, hx, hz, exponent)
                return mottle(fissureStain(enamel(top, 1f, kind), kind, ex * radius * tw / hx, ez * radius * td / hz), p)
            }
            val inside = world(0f, signY * sy * 0.45f, 0f)
            val segments = 28; val levels = 10
            val step = (2.0 * Math.PI / segments).toFloat()
            for (segment in 0 until segments) {
                val a = step * segment; val b = step * (segment + 1)
                val face = surfaceColor(sideSurface((a + b) * 0.5f))
                // Neck: continues a little into the gingiva so no gap shows at the margin.
                val (nax, naz) = boundary(a, hx * 0.62f, hz * 0.62f, exponent)
                val (nbx, nbz) = boundary(b, hx * 0.62f, hz * 0.62f, exponent)
                val dark = enamel(face, 0f, kind)
                val na = world(nax, -signY * sy * 0.16f, naz); val nb = world(nbx, -signY * sy * 0.16f, nbz)
                builder.quad(na, nb, side(a, 0f), side(b, 0f), inside, dark, dark, dark, dark, slotIndex, MAT_ENAMEL)
                for (level in 0 until levels) {
                    val lo = level.toFloat() / levels; val hi = (level + 1).toFloat() / levels
                    val p0 = side(a, lo); val p1 = side(b, lo); val p2 = side(a, hi); val p3 = side(b, hi)
                    builder.quad(p0, p1, p2, p3, inside, mottle(enamel(face, lo, kind), p0), mottle(enamel(face, lo, kind), p1),
                        mottle(enamel(face, hi, kind), p2), mottle(enamel(face, hi, kind), p3), slotIndex, MAT_ENAMEL)
                }
            }
            val capInside = world(0f, signY * sy * 0.2f, 0f)
            val rings = 5
            for (ring in 0 until rings) {
                val r0 = ring.toFloat() / rings; val r1 = (ring + 1).toFloat() / rings
                for (segment in 0 until segments) {
                    val a = step * segment; val b = step * (segment + 1)
                    val q0 = cap(a, r0); val q1 = cap(b, r0); val q2 = cap(a, r1); val q3 = cap(b, r1)
                    if (ring == 0) builder.triangle(q0, q2, q3, capInside, capColor(a, 0f, q0), capColor(a, r1, q2), capColor(b, r1, q3), slotIndex, MAT_ENAMEL)
                    else builder.quad(q0, q1, q2, q3, capInside, capColor(a, r0, q0), capColor(b, r0, q1), capColor(a, r1, q2), capColor(b, r1, q3), slotIndex, MAT_ENAMEL)
                }
            }
            // chart.gd: add_roots — shown in X-ray mode (implants have no natural roots).
            if (replacement != "implant") for (spec in rootSpecs(kind, position, isUpper, primary)) {
                val rootSegments = 14; val rootRings = 8
                fun rp(angle: Float, t: Float): Vec { val (x, y, z) = rootPoint(spec, angle, t, sx, sy, sz, signY, mesialSign); return world(x, y, z) }
                for (ring in 0 until rootRings) {
                    val t0 = ring.toFloat() / rootRings; val t1 = (ring + 1).toFloat() / rootRings
                    val axis = (rp(0f, (t0 + t1) * 0.5f) + rp(Math.PI.toFloat(), (t0 + t1) * 0.5f)) * 0.5f
                    val c0 = mix(Palette.CEMENTUM, Palette.APEX, t0 * t0); val c1 = mix(Palette.CEMENTUM, Palette.APEX, t1 * t1)
                    for (segment in 0 until rootSegments) {
                        val a = (2.0 * Math.PI / rootSegments * segment).toFloat(); val b = (2.0 * Math.PI / rootSegments * (segment + 1)).toFloat()
                        val p0 = rp(a, t0); val p1 = rp(b, t0); val p2 = rp(a, t1); val p3 = rp(b, t1)
                        builder.quad(p0, p1, p2, p3, axis, mottle(c0, p0), mottle(c0, p1), mottle(c1, p2), mottle(c1, p3), slotIndex, MAT_ROOT)
                    }
                }
            }
        }
    }
}

// chart.gd: gum_band
private fun buildGum(builder: SceneBuilder, height: Float, factor: Float, wide: Boolean, maxPosition: Int) {
    val sections = 120; val rings = 16
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
        return P(p, gumColor(towardTeeth, max(0f, sin(cross) * up), angle, cross, p))
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
            builder.triangle(hub, a.p, b.p, interior, (Palette.GUM_DEEP and 0x00FFFFFF) or (0x40 shl 24), a.color, b.color, -1, MAT_GUM)
        }
    }
}

// chart.gd: add_palate — domed palate closing the upper arch.
private fun buildPalate(builder: SceneBuilder, centerY: Float, factor: Float, wide: Boolean) {
    val sections = 44; val rows = 12
    val peak = centerY + 0.42f * factor
    fun point(section: Int, row: Int): Vec {
        val angle = -1.35f + section.toFloat() / sections * 2.70f // interior of the arch only
        val t = row.toFloat() / rows
        val radiusX = ((if (wide) 0.72f else 0.44f) + 0.20f * (abs(angle) / 1.4f).coerceIn(0f, 1f)) * factor
        val outward = Vec(sin(angle) / ARCH_A, 0f, cos(angle) / ARCH_B).normalized()
        val lingual = Vec(sin(angle) * ARCH_A * factor, centerY, (cos(angle) * ARCH_B - ARCH_SHIFT) * factor) + outward * (-radiusX + if (wide) -0.20f * factor else 0f)
        return Vec(lingual.x * (1f - t), centerY + (peak - centerY) * t * t * (3f - 2f * t) - 0.05f * factor * ruga(angle, t), lingual.z)
    }
    fun tint(section: Int, row: Int): Int {
        val r = ruga(-1.35f + section.toFloat() / sections * 2.70f, row.toFloat() / rows)
        return (mix(Palette.PALATE, Palette.RUGA, 0.55f * r) and 0x00FFFFFF) or (0x60 shl 24) // palate: moist, fairly glossy
    }
    for (section in 0 until sections) for (row in 0 until rows) {
        val a = point(section, row); val b = point(section + 1, row); val c = point(section, row + 1); val d = point(section + 1, row + 1)
        val above = (a + d) * 0.5f + Vec(0f, 1f, 0f) // the palate faces down toward the teeth
        builder.quad(a, b, c, d, above, tint(section, row), tint(section + 1, row), tint(section, row + 1), tint(section + 1, row + 1), -1, MAT_GUM)
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
    val gloss = ((base ushr 24) and 0xFF) / 255f
    val spec = when (material) {
        MAT_ENAMEL -> pow2n(hk, 6) * 0.55f + pow2n(hf, 5) * 0.12f
        MAT_GUM -> pow2n(hk, 5) * (0.06f + 0.34f * gloss) + pow2n(hk, 3) * 0.05f * (1f - gloss) // stippled = broad, dull sheen
        else -> pow2n(hk, 4) * 0.14f
    }
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
    showRoots: Boolean = false,
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
    // GPU rendering (depth buffer + MSAA); switches to the CPU painter path if GL is unavailable.
    var useGl by remember { mutableStateOf(true) }
    val glHolder = remember { arrayOfNulls<TeethGLView>(1) }

    Box(modifier) {
    // Soft contact shadow under the model (behind the 3D layer).
    Canvas(Modifier.matchParentSize()) {
        val w = size.width; val h = size.height; val cx = w * 0.5f; val cy = h * 0.5f
        drawOval(
            Brush.radialGradient(listOf(Color(0x260F3D3A), Color.Transparent), center = Offset(cx, cy + h * 0.33f), radius = w * 0.40f),
            topLeft = Offset(cx - w * 0.40f, cy + h * 0.28f), size = androidx.compose.ui.geometry.Size(w * 0.80f, h * 0.10f),
        )
    }
    if (useGl) AndroidView(
        factory = { context -> TeethGLView(context) { useGl = false }.also { glHolder[0] = it } },
        modifier = Modifier.matchParentSize(),
        onRelease = { view -> view.dispose(); if (glHolder[0] === view) glHolder[0] = null },
    )
    Canvas(
        Modifier.matchParentSize()
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

        val gl = glHolder[0]
        if (useGl && gl != null && !gl.failed) {
            gl.submit(scene, GlFrame(floatArrayOf(r00, r01, r02, r10, r11, r12, r20, r21, r22), distance, focal, w.toInt(), h.toInt(), selectedIndices, showRoots))
        } else {
        val v = scene.vertices; val n = scene.normals; val vn = scene.vertexNormals
        var visible = 0
        for (t in 0 until scene.triangles) {
            if (scene.materials[t] == MAT_ROOT && !showRoots) continue
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
                val lit = shade(base, sx, sy, sz, material)
                // X-ray: translucent gingiva reveals the roots (painter's order blends back to front).
                buffers.shade[visible * 3 + k] = if (showRoots && material == MAT_GUM) (lit and 0x00FFFFFF) or (0x5C shl 24) else lit
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
        } // end CPU fallback

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
        // Front-most labels win; a label too close to one already placed is skipped so numbers never pile up.
        val placed = ArrayList<Offset>()
        val minGap = 20f * density.density
        val labelOrder = scene.slots.indices.filter { anchors[it * 3 + 2] > 0f && (showLabels || it in selectedIndices) }
            .sortedWith(compareBy({ it !in selectedIndices }, { anchors[it * 3 + 2] }))
        drawIntoCanvas { canvas ->
            labelOrder.forEach { index ->
                val slot = scene.slots[index]
                val at = Offset(labels[index * 2], labels[index * 2 + 1])
                if (index !in selectedIndices && placed.any { (it - at).getDistance() < minGap }) return@forEach
                placed += at
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
    } // Box
}

/** Screen anchors from the last frame, shared between drawing and tap handling. */
private class AnchorHolder { var anchors = FloatArray(0) }

// ---------------------------------------------------------------------------------------------
// GPU renderer (OpenGL ES 2.0 on a TextureView)
//
// The painter's-algorithm path above sorts whole triangles, which cannot resolve the places where
// the crown neck and the gingiva interpenetrate (jagged, saw-tooth gum line) and has no
// anti-aliasing. This renderer draws the same scene with a depth buffer, 4× MSAA and per-pixel
// lighting (a direct port of shade()). It falls back to the painter path if EGL/GL setup fails.
// ---------------------------------------------------------------------------------------------

/** Camera and display state for one frame (rotation matrix row-major, same maths as the overlays). */
private class GlFrame(
    val r: FloatArray, val distance: Float, val focal: Float, val width: Int, val height: Int,
    val selected: Set<Int>, val showRoots: Boolean,
)

private const val VERTEX_SHADER = """
uniform mat3 uR;
uniform vec4 uCam; // distance, focal/(w/2), focal/(h/2), unused
uniform vec2 uDepth; // a, b for clip z = a * depth + b
attribute vec3 aPos;
attribute vec3 aNormal;
attribute vec4 aColor;
attribute float aMat;
varying vec3 vNormal;
varying vec4 vColor;
varying float vMat;
void main() {
    vec3 p = uR * aPos;
    float depth = uCam.x - p.z;
    gl_Position = vec4(p.x * uCam.y, p.y * uCam.z, uDepth.x * depth + uDepth.y, depth);
    vNormal = uR * aNormal;
    vColor = aColor;
    vMat = aMat;
}
"""

private const val FRAGMENT_SHADER = """
precision mediump float;
varying vec3 vNormal;
varying vec4 vColor;
varying float vMat;
uniform float uRoots;
uniform float uAlpha;
const vec3 KEY = vec3(-0.4508, 0.6211, 0.6411);
const vec3 FILL = vec3(0.7217, 0.1203, 0.6816);
const vec3 BACK = vec3(0.0, 0.3489, -0.9372);
const vec3 BOUNCE = vec3(0.0, -0.7507, 0.6606);
float diffuse(vec3 n, vec3 l, float wrap) { return max(0.0, (dot(n, l) + wrap) / (1.0 + wrap)); }
void main() {
    if (vMat > 1.5 && uRoots < 0.5) discard;
    vec3 n = normalize(vNormal);
    if (!gl_FrontFacing) n = -n;
    bool enamel = vMat < 0.5;
    bool gum = vMat > 0.5 && vMat < 1.5;
    float wrap = enamel ? 0.35 : 0.2;
    float light = 0.40 + 0.62 * diffuse(n, KEY, wrap) + 0.22 * diffuse(n, FILL, wrap) + 0.10 * diffuse(n, BOUNCE, wrap);
    float hk = max(0.0, dot(n, normalize(KEY + vec3(0.0, 0.0, 1.0))));
    float hf = max(0.0, dot(n, normalize(FILL + vec3(0.0, 0.0, 1.0))));
    float gloss = vColor.a;
    float spec;
    if (enamel) spec = pow(hk, 64.0) * 0.55 + pow(hf, 32.0) * 0.12;
    else if (gum) spec = pow(hk, 32.0) * (0.06 + 0.34 * gloss) + pow(hk, 8.0) * 0.05 * (1.0 - gloss);
    else spec = pow(hk, 16.0) * 0.14;
    float edge = 1.0 - abs(n.z);
    float rim = edge * edge * edge * (enamel ? 0.20 : 0.12) + max(0.0, dot(n, BACK)) * 0.10;
    vec3 color = vColor.rgb * light + vec3(spec) + rim * vec3(0.85, 0.95, 1.0);
    gl_FragColor = vec4(min(color, vec3(1.0)), gum ? uAlpha : 1.0);
}
"""

private class TeethGLView(context: android.content.Context, private val onFailure: () -> Unit) :
    android.view.TextureView(context), android.view.TextureView.SurfaceTextureListener {

    private val thread = android.os.HandlerThread("teeth-gl").apply { start() }
    private val handler = android.os.Handler(thread.looper)
    private var display: android.opengl.EGLDisplay = android.opengl.EGL14.EGL_NO_DISPLAY
    private var eglContext: android.opengl.EGLContext = android.opengl.EGL14.EGL_NO_CONTEXT
    private var eglSurface: android.opengl.EGLSurface = android.opengl.EGL14.EGL_NO_SURFACE
    private var program = 0
    private val buffers = IntArray(4) // position, normal, colour, material
    private var uploadedScene: DentalScene? = null
    private var uploadedSelection: Set<Int>? = null
    private var gumStart = 0
    private var vertexCount = 0
    private var order = IntArray(0) // triangle upload order: enamel/roots first, gingiva last
    @Volatile private var pendingScene: DentalScene? = null
    @Volatile private var pendingFrame: GlFrame? = null
    @Volatile var failed = false; private set
    private val drawTask = Runnable { draw() }

    init {
        isOpaque = false
        surfaceTextureListener = this
    }

    fun submit(scene: DentalScene, frame: GlFrame) {
        pendingScene = scene; pendingFrame = frame
        handler.removeCallbacks(drawTask); handler.post(drawTask)
    }

    fun dispose() {
        handler.post { release(); thread.quitSafely() }
    }

    override fun onSurfaceTextureAvailable(texture: android.graphics.SurfaceTexture, width: Int, height: Int) {
        handler.post {
            if (!setup(texture)) fail() else draw()
        }
    }
    override fun onSurfaceTextureSizeChanged(texture: android.graphics.SurfaceTexture, width: Int, height: Int) { handler.post(drawTask) }
    override fun onSurfaceTextureDestroyed(texture: android.graphics.SurfaceTexture): Boolean {
        handler.post { release(); texture.release() }
        return false // released on the GL thread after the EGL surface is gone
    }
    override fun onSurfaceTextureUpdated(texture: android.graphics.SurfaceTexture) = Unit

    private fun fail() {
        failed = true
        release()
        post { onFailure() }
    }

    private fun setup(texture: android.graphics.SurfaceTexture): Boolean = runCatching {
        display = android.opengl.EGL14.eglGetDisplay(android.opengl.EGL14.EGL_DEFAULT_DISPLAY)
        check(android.opengl.EGL14.eglInitialize(display, IntArray(2), 0, IntArray(2), 1))
        val configs = arrayOfNulls<android.opengl.EGLConfig>(1); val count = IntArray(1)
        fun attributes(samples: Int) = intArrayOf(
            android.opengl.EGL14.EGL_RED_SIZE, 8, android.opengl.EGL14.EGL_GREEN_SIZE, 8, android.opengl.EGL14.EGL_BLUE_SIZE, 8,
            android.opengl.EGL14.EGL_ALPHA_SIZE, 8, android.opengl.EGL14.EGL_DEPTH_SIZE, 16,
            android.opengl.EGL14.EGL_RENDERABLE_TYPE, android.opengl.EGL14.EGL_OPENGL_ES2_BIT,
            android.opengl.EGL14.EGL_SAMPLE_BUFFERS, if (samples > 0) 1 else 0, android.opengl.EGL14.EGL_SAMPLES, samples,
            android.opengl.EGL14.EGL_NONE)
        // 4× MSAA for smooth tooth and gum edges; fall back to no MSAA on devices without it.
        if (!android.opengl.EGL14.eglChooseConfig(display, attributes(4), 0, configs, 0, 1, count, 0) || count[0] == 0)
            check(android.opengl.EGL14.eglChooseConfig(display, attributes(0), 0, configs, 0, 1, count, 0) && count[0] > 0)
        val config = configs[0]!!
        eglContext = android.opengl.EGL14.eglCreateContext(display, config, android.opengl.EGL14.EGL_NO_CONTEXT,
            intArrayOf(android.opengl.EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, android.opengl.EGL14.EGL_NONE), 0)
        check(eglContext != android.opengl.EGL14.EGL_NO_CONTEXT)
        eglSurface = android.opengl.EGL14.eglCreateWindowSurface(display, config, texture, intArrayOf(android.opengl.EGL14.EGL_NONE), 0)
        check(eglSurface != android.opengl.EGL14.EGL_NO_SURFACE)
        check(android.opengl.EGL14.eglMakeCurrent(display, eglSurface, eglSurface, eglContext))
        program = linkProgram()
        android.opengl.GLES20.glGenBuffers(4, buffers, 0)
        true
    }.getOrDefault(false)

    private fun compile(type: Int, source: String): Int {
        val shader = android.opengl.GLES20.glCreateShader(type)
        android.opengl.GLES20.glShaderSource(shader, source)
        android.opengl.GLES20.glCompileShader(shader)
        val ok = IntArray(1); android.opengl.GLES20.glGetShaderiv(shader, android.opengl.GLES20.GL_COMPILE_STATUS, ok, 0)
        check(ok[0] != 0) { android.opengl.GLES20.glGetShaderInfoLog(shader) }
        return shader
    }

    private fun linkProgram(): Int {
        val program = android.opengl.GLES20.glCreateProgram()
        android.opengl.GLES20.glAttachShader(program, compile(android.opengl.GLES20.GL_VERTEX_SHADER, VERTEX_SHADER))
        android.opengl.GLES20.glAttachShader(program, compile(android.opengl.GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER))
        android.opengl.GLES20.glBindAttribLocation(program, 0, "aPos")
        android.opengl.GLES20.glBindAttribLocation(program, 1, "aNormal")
        android.opengl.GLES20.glBindAttribLocation(program, 2, "aColor")
        android.opengl.GLES20.glBindAttribLocation(program, 3, "aMat")
        android.opengl.GLES20.glLinkProgram(program)
        val ok = IntArray(1); android.opengl.GLES20.glGetProgramiv(program, android.opengl.GLES20.GL_LINK_STATUS, ok, 0)
        check(ok[0] != 0) { android.opengl.GLES20.glGetProgramInfoLog(program) }
        return program
    }

    private fun directFloats(size: Int) = java.nio.ByteBuffer.allocateDirect(size * 4).order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer()

    private fun upload(scene: DentalScene) {
        // Gingiva last, so X-ray mode can blend it over the opaque teeth and roots.
        order = (0 until scene.triangles).sortedBy { if (scene.materials[it] == MAT_GUM) 1 else 0 }.toIntArray()
        gumStart = order.indexOfFirst { scene.materials[it] == MAT_GUM }.let { if (it < 0) scene.triangles else it } * 3
        vertexCount = scene.triangles * 3
        val positions = directFloats(vertexCount * 3); val normals = directFloats(vertexCount * 3); val materials = directFloats(vertexCount)
        for (t in order) {
            positions.put(scene.vertices, t * 9, 9)
            normals.put(scene.vertexNormals, t * 9, 9)
            val m = scene.materials[t].toFloat(); materials.put(m); materials.put(m); materials.put(m)
        }
        positions.position(0); normals.position(0); materials.position(0)
        bufferData(buffers[0], positions, vertexCount * 3 * 4)
        bufferData(buffers[1], normals, vertexCount * 3 * 4)
        bufferData(buffers[3], materials, vertexCount * 4)
        uploadedScene = scene
        uploadedSelection = null
    }

    private fun uploadColors(scene: DentalScene, selected: Set<Int>) {
        val colors = java.nio.ByteBuffer.allocateDirect(vertexCount * 4).order(java.nio.ByteOrder.nativeOrder())
        for (t in order) {
            val highlight = scene.owners[t] >= 0 && scene.owners[t] in selected
            for (k in 0 until 3) {
                val raw = scene.vertexColors[t * 3 + k]
                val rgb = if (highlight) mix(raw, Palette.SELECT, 0.45f) else raw
                colors.put(((rgb shr 16) and 0xFF).toByte()); colors.put(((rgb shr 8) and 0xFF).toByte()); colors.put((rgb and 0xFF).toByte())
                colors.put(((raw ushr 24) and 0xFF).toByte()) // alpha = gloss (gingiva)
            }
        }
        colors.position(0)
        bufferData(buffers[2], colors, vertexCount * 4)
        uploadedSelection = selected
    }

    private fun bufferData(id: Int, data: java.nio.Buffer, bytes: Int) {
        android.opengl.GLES20.glBindBuffer(android.opengl.GLES20.GL_ARRAY_BUFFER, id)
        android.opengl.GLES20.glBufferData(android.opengl.GLES20.GL_ARRAY_BUFFER, bytes, data, android.opengl.GLES20.GL_STATIC_DRAW)
    }

    private fun attribute(index: Int, buffer: Int, size: Int, type: Int, normalized: Boolean) {
        android.opengl.GLES20.glBindBuffer(android.opengl.GLES20.GL_ARRAY_BUFFER, buffer)
        android.opengl.GLES20.glEnableVertexAttribArray(index)
        android.opengl.GLES20.glVertexAttribPointer(index, size, type, normalized, 0, 0)
    }

    private fun draw() {
        if (failed || eglSurface == android.opengl.EGL14.EGL_NO_SURFACE) return
        val scene = pendingScene ?: return
        val frame = pendingFrame ?: return
        val result = runCatching {
            if (scene !== uploadedScene) upload(scene)
            if (uploadedSelection != frame.selected) uploadColors(scene, frame.selected)
            android.opengl.GLES20.glViewport(0, 0, frame.width, frame.height)
            android.opengl.GLES20.glClearColor(0f, 0f, 0f, 0f)
            android.opengl.GLES20.glClear(android.opengl.GLES20.GL_COLOR_BUFFER_BIT or android.opengl.GLES20.GL_DEPTH_BUFFER_BIT)
            android.opengl.GLES20.glEnable(android.opengl.GLES20.GL_DEPTH_TEST)
            android.opengl.GLES20.glDepthFunc(android.opengl.GLES20.GL_LEQUAL)
            android.opengl.GLES20.glDisable(android.opengl.GLES20.GL_CULL_FACE)
            android.opengl.GLES20.glUseProgram(program)
            val r = frame.r
            // GLSL mat3 is column-major.
            val columnMajor = floatArrayOf(r[0], r[3], r[6], r[1], r[4], r[7], r[2], r[5], r[8])
            android.opengl.GLES20.glUniformMatrix3fv(android.opengl.GLES20.glGetUniformLocation(program, "uR"), 1, false, columnMajor, 0)
            android.opengl.GLES20.glUniform4f(android.opengl.GLES20.glGetUniformLocation(program, "uCam"), frame.distance,
                frame.focal / (frame.width * 0.5f), frame.focal / (frame.height * 0.5f), 0f)
            val near = (frame.distance - 7f).coerceAtLeast(0.5f); val far = frame.distance + 7f
            android.opengl.GLES20.glUniform2f(android.opengl.GLES20.glGetUniformLocation(program, "uDepth"), (far + near) / (far - near), -2f * far * near / (far - near))
            android.opengl.GLES20.glUniform1f(android.opengl.GLES20.glGetUniformLocation(program, "uRoots"), if (frame.showRoots) 1f else 0f)
            attribute(0, buffers[0], 3, android.opengl.GLES20.GL_FLOAT, false)
            attribute(1, buffers[1], 3, android.opengl.GLES20.GL_FLOAT, false)
            attribute(2, buffers[2], 4, android.opengl.GLES20.GL_UNSIGNED_BYTE, true)
            attribute(3, buffers[3], 1, android.opengl.GLES20.GL_FLOAT, false)
            val alpha = android.opengl.GLES20.glGetUniformLocation(program, "uAlpha")
            // Opaque pass: enamel and roots.
            android.opengl.GLES20.glUniform1f(alpha, 1f)
            android.opengl.GLES20.glDisable(android.opengl.GLES20.GL_BLEND)
            if (gumStart > 0) android.opengl.GLES20.glDrawArrays(android.opengl.GLES20.GL_TRIANGLES, 0, gumStart)
            // Gingiva: opaque normally; translucent in X-ray mode so the roots show through.
            if (frame.showRoots) {
                android.opengl.GLES20.glEnable(android.opengl.GLES20.GL_BLEND)
                android.opengl.GLES20.glBlendFunc(android.opengl.GLES20.GL_SRC_ALPHA, android.opengl.GLES20.GL_ONE_MINUS_SRC_ALPHA)
                android.opengl.GLES20.glDepthMask(false)
                android.opengl.GLES20.glUniform1f(alpha, 0.36f)
            }
            if (vertexCount > gumStart) android.opengl.GLES20.glDrawArrays(android.opengl.GLES20.GL_TRIANGLES, gumStart, vertexCount - gumStart)
            android.opengl.GLES20.glDepthMask(true)
            check(android.opengl.EGL14.eglSwapBuffers(display, eglSurface))
        }
        if (result.isFailure) fail()
    }

    private fun release() {
        if (display == android.opengl.EGL14.EGL_NO_DISPLAY) return
        android.opengl.EGL14.eglMakeCurrent(display, android.opengl.EGL14.EGL_NO_SURFACE, android.opengl.EGL14.EGL_NO_SURFACE, android.opengl.EGL14.EGL_NO_CONTEXT)
        if (eglSurface != android.opengl.EGL14.EGL_NO_SURFACE) android.opengl.EGL14.eglDestroySurface(display, eglSurface)
        if (eglContext != android.opengl.EGL14.EGL_NO_CONTEXT) android.opengl.EGL14.eglDestroyContext(display, eglContext)
        // No eglTerminate: the default display is shared with the app's own UI renderer.
        display = android.opengl.EGL14.EGL_NO_DISPLAY; eglContext = android.opengl.EGL14.EGL_NO_CONTEXT; eglSurface = android.opengl.EGL14.EGL_NO_SURFACE
        uploadedScene = null; uploadedSelection = null
    }
}
