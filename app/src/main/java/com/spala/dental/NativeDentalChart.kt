package com.spala.dental

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.json.JSONArray
import org.json.JSONObject

private val ChartTeal = Color(0xFF0F766E)
private val ChartBlue = Color(0xFF2563EB)
private val ChartSlate = Color(0xFF1E293B)
private val ChartMint = Color(0xFFF0FDFA)
private val ChartAmber = Color(0xFFB45309)
private val ChartUnknown = Color(0xFF94A3B8)
private val absentStates = setOf("missing", "extracted", "exfoliated", "congenitally_absent")

private fun JSONObject?._codes(key: String): List<String> = this?.optJSONArray(key)?.let { array ->
    (0 until array.length()).mapNotNull { index -> (array.opt(index) as? String)?.take(80) }
}.orEmpty()

private fun surfaceRecords(tooth: JSONObject?): List<JSONObject> {
    val surfaces = tooth?.optJSONObject("surfaces") ?: return emptyList()
    return surfaces.keys().asSequence().mapNotNull { surfaces.optJSONObject(it) }.toList()
}

/** Native, fully selectable odontogram; recorded facts never imply unassessed surfaces are healthy. */
@Composable
fun NativeDentalChart(patient: JSONObject, primary: Boolean, onSelect: (String) -> Unit) {
    val chart = patient.optJSONObject("chart")
    if (chart != null && chart.optInt("schemaVersion", 2) != 2) {
        Text("This chart version requires an application update.", color = MaterialTheme.colorScheme.error)
        return
    }
    val maxPosition = if (primary) 5 else 8
    val first = if (primary) 5 else 1
    val teeth = chart?.optJSONObject("teeth")
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(if (primary) "PRIMARY DENTITION" else "PERMANENT DENTITION", color = ChartTeal,
            fontWeight = FontWeight.Bold, fontSize = 12.sp)
        listOf(first to first + 1, first + 3 to first + 2).forEachIndexed { arch, pair ->
            Text(if (arch == 0) "Upper arch · patient right → left" else "Lower arch · patient right → left",
                color = ChartSlate.copy(alpha = .7f), fontSize = 11.sp)
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                val keys = (maxPosition downTo 1).map { "${pair.first}$it" } + (1..maxPosition).map { "${pair.second}$it" }
                keys.forEach { key ->
                    val tooth = teeth?.optJSONObject(key)
                    val presence = tooth?.optString("presence", "unknown") ?: "unknown"
                    val assessment = tooth?.optString("assessment", "not_assessed") ?: "not_assessed"
                    val surfaces = surfaceRecords(tooth)
                    val findingCount = tooth._codes("findings").size + surfaces.sumOf { it._codes("findings").size }
                    val restorationCount = surfaces.sumOf { it._codes("restorations").size }
                    val planCount = tooth._codes("treatmentStates").size
                    val description = "FDI tooth $key. ${if (primary) "Primary" else "Permanent"}. " +
                        "Assessment ${assessment.replace('_', ' ')}. Presence ${presence.replace('_', ' ')}. " +
                        "${tooth?.optString("replacement")?.takeIf { it.isNotBlank() && it != "none" }?.let { "Replacement ${it.replace('_', ' ')}. " }.orEmpty()}" +
                        "$findingCount finding codes, $restorationCount restoration codes, $planCount open treatment states. " +
                        "Unrecorded surfaces remain unassessed. Select to review or edit."
                    Surface(onClick = { onSelect(key) }, modifier = Modifier.width(58.dp).heightIn(min = 136.dp)
                        .semantics(mergeDescendants = true) { contentDescription = description },
                        color = if (presence in absentStates) Color(0xFFF1F5F9) else Color.White,
                        border = BorderStroke(1.dp, if (assessment == "assessed") ChartTeal.copy(alpha = .35f) else Color(0xFFCBD5E1)),
                        shape = RoundedCornerShape(12.dp)) {
                        Column(Modifier.padding(horizontal = 5.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(key, fontWeight = FontWeight.Bold, color = ChartSlate, fontSize = 14.sp)
                            Canvas(Modifier.width(44.dp).height(54.dp)) { drawClinicalTooth(key, tooth) }
                            Text(when { presence in absentStates -> "Recorded loss"; presence == "unknown" -> "Unknown";
                                presence == "unerupted" -> "Unerupted"; assessment == "not_assessed" -> "Unassessed";
                                assessment == "unable_to_assess" -> "Limited"; else -> "Assessed" },
                                fontSize = 9.sp, color = ChartSlate, maxLines = 2)
                            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text("F$findingCount", fontSize = 9.sp, color = ChartAmber, fontWeight = FontWeight.SemiBold)
                                Text("R$restorationCount", fontSize = 9.sp, color = ChartBlue, fontWeight = FontWeight.SemiBold)
                                Text("P$planCount", fontSize = 9.sp, color = ChartTeal, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
            }
        }
        Text("F = findings · R = restorations · P = open care states. Dashed outline = unassessed. Cross = recorded loss. " +
            "Counts describe recorded codes, not the number of procedures. Tap any tooth for details.",
            fontSize = 11.sp, color = ChartSlate.copy(alpha = .7f))
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawClinicalTooth(key: String, tooth: JSONObject?) {
    val w = size.width
    val h = size.height
    val anterior = key.last() <= '3'
    val presence = tooth?.optString("presence", "unknown") ?: "unknown"
    val assessed = tooth?.optString("assessment") == "assessed"
    val replacement = tooth?.optString("replacement", "none") ?: "none"
    val lost = presence in absentStates
    val path = Path().apply {
        if (anterior) {
            moveTo(.17f * w, .20f * h)
            cubicTo(.23f * w, .10f * h, .77f * w, .10f * h, .83f * w, .20f * h)
            cubicTo(.88f * w, .43f * h, .70f * w, .74f * h, .64f * w, .85f * h)
            quadraticTo(.50f * w, .91f * h, .36f * w, .85f * h)
            cubicTo(.30f * w, .74f * h, .12f * w, .43f * h, .17f * w, .20f * h)
        } else {
            moveTo(.28f * w, .10f * h)
            cubicTo(.08f * w, .04f * h, .02f * w, .30f * h, .12f * w, .59f * h)
            cubicTo(.14f * w, .91f * h, .35f * w, .95f * h, .50f * w, .86f * h)
            cubicTo(.72f * w, .98f * h, .90f * w, .90f * h, .89f * w, .60f * h)
            cubicTo(.99f * w, .30f * h, .88f * w, .03f * h, .66f * w, .10f * h)
            quadraticTo(.50f * w, .17f * h, .28f * w, .10f * h)
        }
        close()
    }
    val enamel = if (lost && replacement == "none") Color(0xFFE2E8F0) else if (presence == "unerupted") Color(0xFFE8EEF2) else Color(0xFFFFFBF1)
    drawPath(path, enamel)
    if (!lost || replacement != "none") {
        val mesialLeft = key.first() in setOf('2', '3', '6', '7')
        val patches = mapOf(
            "F" to Rect(.16f * w, .11f * h, .84f * w, .35f * h),
            "L" to Rect(.21f * w, .67f * h, .79f * w, .88f * h),
            "M" to if (mesialLeft) Rect(.08f * w, .30f * h, .35f * w, .71f * h) else Rect(.65f * w, .30f * h, .92f * w, .71f * h),
            "D" to if (mesialLeft) Rect(.65f * w, .30f * h, .92f * w, .71f * h) else Rect(.08f * w, .30f * h, .35f * w, .71f * h),
            (if (anterior) "I" else "O") to Rect(.32f * w, .32f * h, .68f * w, .68f * h))
        val observations = tooth?.optJSONObject("surfaces")
        clipPath(path) {
            patches.forEach { (surface, rectangle) ->
                val observation = observations?.optJSONObject(surface)
                if (observation._codes("restorations").isNotEmpty()) drawRoundRect(ChartBlue.copy(alpha = .55f), rectangle.topLeft, rectangle.size, CornerRadius(3.dp.toPx()))
                if (observation._codes("findings").isNotEmpty()) {
                    drawCircle(ChartAmber, 3.dp.toPx(), rectangle.center)
                    drawCircle(Color.White, 3.dp.toPx(), rectangle.center, style = Stroke(1.dp.toPx()))
                }
            }
            val detailColor = Color(0xFF64748B).copy(alpha = .55f)
            if (anterior) {
                drawLine(detailColor, Offset(.23f * w, .28f * h), Offset(.77f * w, .28f * h), 1.dp.toPx())
                if (key.last() == '3') {
                    drawLine(detailColor, Offset(.23f * w, .27f * h), Offset(.50f * w, .14f * h), 1.dp.toPx())
                    drawLine(detailColor, Offset(.50f * w, .14f * h), Offset(.77f * w, .27f * h), 1.dp.toPx())
                }
            } else {
                drawLine(detailColor, Offset(.50f * w, .24f * h), Offset(.50f * w, .77f * h), 1.dp.toPx())
                drawLine(detailColor, Offset(.24f * w, .49f * h), Offset(.76f * w, .49f * h), 1.dp.toPx())
                drawLine(detailColor, Offset(.24f * w, .49f * h), Offset(.16f * w, .37f * h), 1.dp.toPx())
                drawLine(detailColor, Offset(.76f * w, .49f * h), Offset(.84f * w, .37f * h), 1.dp.toPx())
            }
        }
    }
    drawPath(path, if (lost) ChartUnknown else if (assessed) ChartTeal else ChartUnknown,
        style = Stroke(1.5.dp.toPx(), pathEffect = if (!assessed) PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())) else null))
    if (lost && replacement == "none") {
        drawLine(ChartSlate.copy(alpha = .65f), Offset(.24f * w, .30f * h), Offset(.76f * w, .70f * h), 2.dp.toPx())
        drawLine(ChartSlate.copy(alpha = .65f), Offset(.76f * w, .30f * h), Offset(.24f * w, .70f * h), 2.dp.toPx())
    }
    if (replacement != "none") {
        drawRoundRect(ChartBlue, Offset(.37f * w, .83f * h), Size(.26f * w, .08f * h), CornerRadius(2.dp.toPx()))
    }
    if (tooth._codes("findings").isNotEmpty()) drawCircle(ChartAmber, 3.dp.toPx(), Offset(.86f * w, .16f * h))
    if (tooth._codes("treatmentStates").isNotEmpty()) {
        val center = Offset(.86f * w, .82f * h)
        drawCircle(ChartTeal, 5.dp.toPx(), center)
        drawLine(Color.White, center - Offset(2.5.dp.toPx(), 0f), center + Offset(2.5.dp.toPx(), 0f), 1.dp.toPx())
        drawLine(Color.White, center - Offset(0f, 2.5.dp.toPx()), center + Offset(0f, 2.5.dp.toPx()), 1.dp.toPx())
    }
}

/** A clinician-operated explanation view. No clinical narrative, medical alerts, finances or IDs are projected. */
@Composable
fun PatientExplanationDialog(patient: JSONObject, onClose: () -> Unit, onOpen3D: (JSONObject) -> Unit) {
    val treatments = patient.optJSONArray("treatments")?.objects().orEmpty()
    val proposed = treatments.filter { it.optString("status") in setOf("Planned", "Accepted", "In progress") }
    val performed = treatments.filter { it.optString("status") == "Completed" }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth(.94f).heightIn(max = 720.dp), shape = RoundedCornerShape(24.dp), color = ChartMint) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(22.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Your care, clearly explained", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = ChartTeal)
                Text("Your clinician can use this view to discuss recorded findings and care options with you. Planned treatment is separate from care already performed.", color = ChartSlate)
                ExplanationSection("Care to discuss or continue", proposed.map { item ->
                    "Tooth ${item.optString("tooth", "not specified")}: ${item.optString("procedure", "Treatment").replace('_', ' ')} · ${item.optString("status")}" })
                ExplanationSection("Care recorded as performed", performed.map { item ->
                    "Tooth ${item.optString("tooth", "not specified")}: ${item.optString("procedure", "Treatment").replace('_', ' ')}" })
                Text("The chart illustrates recorded tooth states. An unassessed or unknown tooth does not mean healthy, and this view does not make a diagnosis.",
                    style = MaterialTheme.typography.bodySmall, color = ChartSlate.copy(alpha = .75f))
                Button(onClick = { onOpen3D(explanationChart(patient)) }, modifier = Modifier.fillMaxWidth()) { Text("Explore the dental chart in 3D") }
                OutlinedButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text("Return to clinical workspace") }
            }
        }
    }
}

@Composable
private fun ExplanationSection(title: String, entries: List<String>) {
    Card(colors = CardDefaults.cardColors(containerColor = Color.White), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold, color = ChartTeal)
            if (entries.isEmpty()) Text("No care recorded in this category.", color = ChartSlate.copy(alpha = .7f))
            else entries.forEach { Text(it, color = ChartSlate) }
        }
    }
}

/** Whitelist only clinical rendering fields; even accidental source notes cannot enter explanation mode. */
private fun explanationChart(patient: JSONObject): JSONObject {
    val source = patient.optJSONObject("chart") ?: JSONObject()
    val teeth = JSONObject()
    val incoming = source.optJSONObject("teeth") ?: JSONObject()
    for (key in incoming.keys()) {
        if (!Regex("[1-4][1-8]|[5-8][1-5]").matches(key)) continue
        val tooth = incoming.optJSONObject(key) ?: continue
        val safe = JSONObject()
        listOf("assessment", "presence", "replacement").forEach { field -> if (tooth.opt(field) is String) safe.put(field, tooth.optString(field)) }
        listOf("findings", "treatmentStates").forEach { field -> if (tooth.has(field)) safe.put(field, JSONArray(tooth._codes(field))) }
        val surfaces = JSONObject()
        val inputSurfaces = tooth.optJSONObject("surfaces")
        val allowed = setOf("M", "D", "F", "L", if (key.last() <= '3') "I" else "O")
        inputSurfaces?.let { sourceSurfaces ->
            for (surface in sourceSurfaces.keys()) {
                if (surface in allowed) sourceSurfaces.optJSONObject(surface)?.let { observation ->
                    surfaces.put(surface, JSONObject().put("assessment", observation.optString("assessment", "not_assessed"))
                        .put("findings", JSONArray(observation._codes("findings"))).put("restorations", JSONArray(observation._codes("restorations"))))
                }
            }
        }
        if (surfaces.length() > 0) safe.put("surfaces", surfaces)
        teeth.put(key, safe)
    }
    return JSONObject().put("schemaVersion", source.optInt("schemaVersion", 2)).put("catalogVersion", source.optString("catalogVersion", "fdi-v1"))
        .put("dentition", source.optString("dentition", "permanent")).put("chartVersion", source.optLong("chartVersion"))
        .put("teeth", teeth).put("readOnly", true)
}
