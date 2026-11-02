package com.spala.dental

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spala.dental.ui.theme.*
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Only clinicians with clinical authority record treatment; everyone else sees a read-only chart. */
fun canEditClinical(role: String) = role in setOf("owner", "admin", "administrator", "dentist")

private val toothKeyPattern = Regex("[1-4][1-8]|[5-8][1-5]")

/** Tooth keys referenced by a treatment/procedure record ("16", or older "16, 17" style lists). */
private fun keysOf(record: JSONObject): List<String> =
    toothKeyPattern.findAll(record.optString("tooth")).map { it.value }.distinct().toList()

private fun JSONObject?.codes(name: String): List<String> = this?.optJSONArray(name)?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()

/** One line in a tooth's treatment history. */
data class HistoryEntry(
    val status: String, val procedure: String, val surface: String, val at: Long,
    val doctor: String, val notes: String, val outcome: String,
    /** Id of the editable treatment item ("" for older standalone performed-care records). */
    val treatmentId: String = "",
)

/** Everything recorded for one tooth slot, newest first. */
data class ToothRecord(
    val key: String, val entries: List<HistoryEntry>, val findings: List<String>, val restorations: List<String>,
    val presence: String, val replacement: String,
) {
    val completed get() = entries.count { it.status == "Completed" }
    val open get() = entries.count { it.status in setOf("Planned", "Accepted", "In progress") }
}

fun toothRecords(patient: JSONObject): List<ToothRecord> {
    val entries = HashMap<String, MutableList<HistoryEntry>>()
    val treatments = patient.optJSONArray("treatments").objectList()
    val performed = patient.optJSONArray("performedProcedures").objectList()
    val matched = HashSet<String>()
    for (treatment in treatments) {
        val linked = performed.filter { it.optString("treatmentId") == treatment.optString("id") }
        linked.forEach { matched += it.optString("id") }
        val outcome = linked.firstOrNull { !it.optBoolean("voided") }
        val entry = HistoryEntry(
            status = treatment.optString("status", "Planned"),
            procedure = treatment.optString("procedure", "Treatment"),
            surface = treatment.optString("surface").takeIf { it.isNotBlank() && it != "Whole tooth" }.orEmpty(),
            at = treatment.optLong("performedAt").takeIf { it > 0 } ?: treatment.optLong("at"),
            doctor = treatment.optString("doctor").ifBlank { outcome?.optString("doctor").orEmpty() },
            notes = treatment.optString("notes"),
            outcome = outcome?.optString("outcome").orEmpty(),
            treatmentId = treatment.optString("id"),
        )
        keysOf(treatment).forEach { entries.getOrPut(it) { mutableListOf() } += entry }
    }
    for (procedure in performed) {
        if (procedure.optString("id") in matched || procedure.optBoolean("voided")) continue
        val entry = HistoryEntry("Completed", procedure.optString("procedure", "Procedure"), procedure.optString("surface").takeIf { it != "Whole tooth" }.orEmpty(),
            procedure.optLong("at"), procedure.optString("doctor"), "", procedure.optString("outcome"))
        keysOf(procedure).forEach { entries.getOrPut(it) { mutableListOf() } += entry }
    }
    val teeth = patient.optJSONObject("chart")?.optJSONObject("teeth")
    val keys = HashSet<String>(entries.keys)
    teeth?.let { teeth -> teeth.keys().forEach { key ->
        val tooth = teeth.optJSONObject(key) ?: return@forEach
        val surfaces = tooth.optJSONObject("surfaces")
        val hasFacts = tooth.codes("findings").isNotEmpty() || tooth.optString("presence") in setOf("missing", "extracted", "exfoliated", "congenitally_absent") ||
            tooth.optString("replacement", "none") !in setOf("none", "") ||
            surfaces?.let { all -> all.keys().asSequence().any { s -> all.optJSONObject(s).let { it.codes("findings").isNotEmpty() || it.codes("restorations").isNotEmpty() } } } == true
        if (hasFacts) keys += key
    } }
    return keys.filter { toothKeyPattern.matches(it) }.map { key ->
        val tooth = teeth?.optJSONObject(key)
        val surfaces = tooth?.optJSONObject("surfaces")
        val findings = tooth.codes("findings").map { it.replace('_', ' ') }.toMutableList()
        val restorations = mutableListOf<String>()
        surfaces?.let { surfaces -> surfaces.keys().forEach { s ->
            surfaces.optJSONObject(s).codes("findings").forEach { findings += "${it.replace('_', ' ')} ($s)" }
            surfaces.optJSONObject(s).codes("restorations").forEach { restorations += "${it.replace('_', ' ')} ($s)" }
        } }
        ToothRecord(key, entries[key].orEmpty().sortedByDescending { it.at }, findings, restorations,
            tooth?.optString("presence", "unknown") ?: "unknown", tooth?.optString("replacement", "none") ?: "none")
    }.sortedBy { ToothNotation.orderIndex(it.key) }
}

/**
 * Chart snapshot for rendering only: adds a `treated` flag to teeth with completed care so the
 * 3D model shows the whole treatment history at a glance. Never saved back to the record.
 */
fun displayChart(patient: JSONObject): JSONObject {
    val chart = JSONObject((patient.optJSONObject("chart") ?: JSONObject().put("schemaVersion", 2).put("dentition", "permanent")).toString())
    val teeth = chart.optJSONObject("teeth") ?: JSONObject().also { chart.put("teeth", it) }
    val treated = HashSet<String>()
    patient.optJSONArray("performedProcedures").objectList().filterNot { it.optBoolean("voided") }.forEach { treated += keysOf(it) }
    patient.optJSONArray("treatments").objectList().filter { it.optString("status") == "Completed" }.forEach { treated += keysOf(it) }
    val dentition = chart.optString("dentition", "permanent")
    for (key in treated) {
        val inInventory = when (dentition) { "permanent" -> key[0] in '1'..'4'; "primary" -> key[0] in '5'..'8'; else -> true }
        if (inInventory) (teeth.optJSONObject(key) ?: JSONObject().put("assessment", "not_assessed").put("presence", "unknown").also { teeth.put(key, it) }).put("treated", true)
    }
    return chart.put("notation", ToothNotation.current)
}

private fun shortDate(millis: Long, config: JSONObject): String = if (millis <= 0) "Date not recorded" else
    DateTimeFormatter.ofPattern("dd MMM yyyy").withZone(runCatching { ClinicalRules.zone(config) }.getOrDefault(ZoneId.systemDefault())).format(Instant.ofEpochMilli(millis))

private fun statusColors(status: String): Pair<Color, Color> = when (status) {
    "Completed" -> Blue100 to Blue600
    "Planned", "Accepted" -> Teal50 to Teal700
    "In progress" -> Color(0xFFEDE9FE) to Color(0xFF7C3AED)
    "Declined", "Cancelled" -> SurfaceTint to InkMuted
    else -> Amber100 to Amber600
}

/** Vertical per-tooth summary under the 3D model; a row expands to its full treatment timeline. */
@Composable
fun ToothHistoryList(
    records: List<ToothRecord>,
    config: JSONObject,
    expanded: String?,
    canEdit: Boolean,
    onToggle: (String) -> Unit,
    onEdit: (String) -> Unit,
) {
    val procedures = records.sumOf { it.entries.size }
    SectionHeader("Treatment history · ${records.size} teeth · $procedures procedures")
    if (records.isEmpty()) {
        SurfaceCard(background = SurfaceTint) {
            Text("No treatment recorded yet", style = MaterialTheme.typography.titleSmall, color = Ink)
            Text(if (canEdit) "Tap teeth on the 3D model, or use Record treatment, to add the first entry." else "Treated teeth will appear here and be highlighted on the 3D model.",
                style = MaterialTheme.typography.bodySmall, color = InkMuted)
        }
    }
    // A tooth tapped on the model that has no history yet still gets a detail card.
    if (expanded != null && records.none { it.key == expanded }) {
        ToothRow(ToothRecord(expanded, emptyList(), emptyList(), emptyList(), "unknown", "none"), config, true, canEdit, onToggle, onEdit)
    }
    records.forEach { record -> ToothRow(record, config, record.key == expanded, canEdit, onToggle, onEdit) }
}

/** Actions for the tooth selected on the 3D model; shown above (outside) the treatment history. */
@Composable
fun SelectedToothActions(key: String, onRecord: (String) -> Unit, onEditStatus: (String) -> Unit, onClose: () -> Unit) {
    SurfaceCard(background = Teal50) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(Brush.linearGradient(listOf(Teal500, Teal700))), contentAlignment = Alignment.Center) {
                Text(ToothNotation.label(key), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            }
            Column(Modifier.weight(1f)) {
                Text("Selected tooth", style = MaterialTheme.typography.labelMedium, color = Teal700)
                Text(toothName(key), style = MaterialTheme.typography.titleSmall, color = Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            TextButton(onClick = onClose) { Text("Clear") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { onRecord(key) }, shape = RoundedCornerShape(14.dp), modifier = Modifier.weight(1f)) {
                GlyphIcon(Glyph.Plus, Color.White, 16.dp); Spacer(Modifier.width(6.dp)); Text("Record treatment")
            }
            OutlinedButton(onClick = { onEditStatus(key) }, shape = RoundedCornerShape(14.dp), modifier = Modifier.weight(1f)) { Text("Chart status") }
        }
    }
}

@Composable
private fun ToothRow(
    record: ToothRecord, config: JSONObject, open: Boolean, canEdit: Boolean,
    onToggle: (String) -> Unit, onEdit: (String) -> Unit,
) {
    val lost = record.presence in setOf("missing", "extracted", "exfoliated", "congenitally_absent")
    Surface(
        onClick = { onToggle(record.key) }, shape = RoundedCornerShape(20.dp),
        color = if (open) Teal50 else SurfaceWhite, border = BorderStroke(if (open) 1.5.dp else 1.dp, if (open) Teal500 else Hairline),
        shadowElevation = if (open) 2.dp else 0.dp, modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(
                    Modifier.size(48.dp).clip(RoundedCornerShape(14.dp))
                        .background(if (open) Brush.linearGradient(listOf(Teal500, Teal700)) else Brush.linearGradient(listOf(SurfaceTint, SurfaceTint))),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(ToothNotation.label(record.key), color = if (open) Color.White else Teal700, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(toothName(record.key), style = MaterialTheme.typography.titleSmall, color = Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val summary = listOfNotNull(
                        record.completed.takeIf { it > 0 }?.let { "$it completed" },
                        record.open.takeIf { it > 0 }?.let { "$it planned" },
                        record.findings.size.takeIf { it > 0 }?.let { "$it finding${if (it > 1) "s" else ""}" },
                        record.presence.takeIf { lost }?.replace('_', ' '),
                    ).joinToString(" · ").ifBlank { "No treatment recorded" }
                    Text(summary, style = MaterialTheme.typography.bodySmall, color = InkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (record.completed > 0) Box(Modifier.size(9.dp).clip(CircleShape).background(Blue600))
                    if (record.open > 0) Box(Modifier.size(9.dp).clip(CircleShape).background(Teal700))
                    if (record.findings.isNotEmpty()) Box(Modifier.size(9.dp).clip(CircleShape).background(Amber600))
                    if (lost) Box(Modifier.size(9.dp).clip(CircleShape).background(InkMuted))
                }
                GlyphIcon(Glyph.Chevron, InkSubtle, 18.dp, Modifier.then(if (open) Modifier.padding(top = 2.dp) else Modifier))
            }
            AnimatedVisibility(open) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(ToothNotation.describe(record.key), style = MaterialTheme.typography.labelMedium, color = Teal700)
                    if (record.findings.isNotEmpty() || record.restorations.isNotEmpty() || record.replacement !in setOf("none", "")) {
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            record.findings.forEach { StatusPill(it, Amber100, Amber600) }
                            record.restorations.forEach { StatusPill(it, Blue100, Blue600) }
                            if (record.replacement !in setOf("none", "")) StatusPill(record.replacement.replace('_', ' '), SurfaceTint, InkMuted)
                        }
                    }
                    if (record.entries.isEmpty()) Text("No procedures recorded for this tooth.", style = MaterialTheme.typography.bodySmall, color = InkMuted)
                    record.entries.forEachIndexed { index, entry ->
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            // Timeline rail
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Box(Modifier.size(12.dp).clip(CircleShape).background(statusColors(entry.status).second))
                                if (index < record.entries.lastIndex) Box(Modifier.width(2.dp).height(56.dp).background(Hairline))
                            }
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(entry.procedure.replace('_', ' ').replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.titleSmall, color = Ink, modifier = Modifier.weight(1f))
                                    val (bg, fg) = statusColors(entry.status)
                                    StatusPill(entry.status, bg, fg, dot = false)
                                }
                                if (canEdit && entry.treatmentId.isNotBlank()) TextButton(onClick = { onEdit(entry.treatmentId) }, contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp),
                                    modifier = Modifier.height(30.dp)) { Text("Edit", style = MaterialTheme.typography.labelLarge, color = Teal700) }
                                Text(listOf(shortDate(entry.at, config), entry.doctor, entry.surface.takeIf { it.isNotBlank() }?.let { "Surface $it" }.orEmpty())
                                    .filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = InkMuted)
                                if (entry.outcome.isNotBlank()) Text("Outcome: ${entry.outcome}", style = MaterialTheme.typography.bodySmall, color = Ink)
                                if (entry.notes.isNotBlank()) Text(entry.notes, style = MaterialTheme.typography.bodySmall, color = Ink)
                            }
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Record treatment (doctor / admin only)
// ---------------------------------------------------------------------------------------------

private data class ProcedureOption(val label: String, val restoration: String? = null)
private val procedureOptions = listOf(
    ProcedureOption("Filling (composite)", "composite"), ProcedureOption("Filling (amalgam)", "amalgam"),
    ProcedureOption("Glass ionomer", "glass_ionomer"), ProcedureOption("Root canal"), ProcedureOption("Crown", "crown"),
    ProcedureOption("Extraction"), ProcedureOption("Implant"), ProcedureOption("Scaling & polishing"),
    ProcedureOption("Sealant", "sealant"), ProcedureOption("Inlay", "inlay"), ProcedureOption("Onlay", "onlay"),
    ProcedureOption("Bridge"), ProcedureOption("Veneer"), ProcedureOption("Orthodontic"),
)

/**
 * Records one procedure for one tooth or a set of teeth. Each tooth gets its own treatment item
 * (sharing a groupId) so per-tooth history, billing and workflow stay intact. Completed care also
 * writes a performed-procedure record and, where coded, the resulting restoration/tooth state.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RecordTreatmentDialog(
    patient: JSONObject,
    teeth: List<String>,
    initialSurface: String,
    clinicians: List<String>,
    config: JSONObject,
    onDismiss: () -> Unit,
    /** A recorded treatment item to correct; null records a new one. */
    editing: JSONObject? = null,
    onSave: (JSONObject, String) -> String?,
) {
    val zoneOf = { runCatching { ClinicalRules.zone(config) }.getOrDefault(ZoneId.systemDefault()) }
    val linkedOutcome = editing?.let { item -> patient.optJSONArray("performedProcedures").objectList().firstOrNull { it.optString("treatmentId") == item.optString("id") && !it.optBoolean("voided") } }
    var procedure by remember { mutableStateOf(editing?.optString("procedure").orEmpty()) }
    var status by remember { mutableStateOf(editing?.optString("status")?.ifBlank { null } ?: "Completed") }
    val surfaces = remember { mutableStateListOf<String>().apply {
        val start = editing?.optString("surface")?.takeIf { it != "Whole tooth" }?.map { it.toString() } ?: listOfNotNull(initialSurface.ifBlank { null })
        start.forEach { add(if (it in setOf("I", "O")) "Top" else it) }
    } }
    var doctor by remember { mutableStateOf(editing?.optString("doctor")?.ifBlank { null } ?: clinicians.firstOrNull().orEmpty()) }
    var date by remember { mutableStateOf(
        (editing?.let { it.optLong("performedAt").takeIf { v -> v > 0 } ?: it.optLong("at").takeIf { v -> v > 0 } }
            ?.let { java.time.Instant.ofEpochMilli(it).atZone(zoneOf()).toLocalDate() } ?: LocalDate.now(zoneOf())).toString()) }
    var notes by remember { mutableStateOf(editing?.optString("notes")?.ifBlank { null } ?: linkedOutcome?.optString("outcome").orEmpty()) }
    var error by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss, shape = RoundedCornerShape(28.dp),
        title = {
            Column {
                Text(if (editing != null) "Edit treatment" else "Record treatment", style = MaterialTheme.typography.titleLarge)
                Text(teeth.joinToString(", ") { ToothNotation.label(it) } + if (teeth.size == 1) " · ${toothName(teeth.first())}" else " · ${teeth.size} teeth",
                    style = MaterialTheme.typography.bodySmall, color = InkMuted)
            }
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Procedure", style = MaterialTheme.typography.labelLarge, color = Ink)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    procedureOptions.forEach { option -> FilterChip(selected = procedure == option.label, onClick = { procedure = option.label }, label = { Text(option.label) }) }
                }
                OutlinedTextField(procedure, { procedure = it }, label = { Text("Procedure (or type your own)") }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp))
                Text("Status", style = MaterialTheme.typography.labelLarge, color = Ink)
                SegmentedPills(if (editing != null) listOf("Completed", "In progress", "Planned", "Cancelled") else listOf("Completed", "In progress", "Planned"),
                    status, Modifier.fillMaxWidth()) { status = it }
                if (editing != null) Text("The previous values are kept in the treatment's change log and the patient history.",
                    style = MaterialTheme.typography.bodySmall, color = InkMuted)
                Text("Surfaces (optional)", style = MaterialTheme.typography.labelLarge, color = Ink)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("M" to "Mesial", "D" to "Distal", "F" to "Buccal/Labial", "L" to "Lingual/Palatal", "Top" to "Occlusal/Incisal").forEach { (code, name) ->
                        FilterChip(selected = code in surfaces, onClick = { if (code in surfaces) surfaces.remove(code) else surfaces.add(code) }, label = { Text(name) })
                    }
                }
                if (clinicians.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    clinicians.forEach { name -> FilterChip(selected = doctor == name, onClick = { doctor = name }, label = { Text(name) }) }
                }
                OutlinedTextField(doctor, { doctor = it }, label = { Text("Doctor") }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp))
                DateField(date, { date = it }, if (status == "Planned") "Planned date" else "Treatment date", today = LocalDate.now(runCatching { ClinicalRules.zone(config) }.getOrDefault(ZoneId.systemDefault())))
                OutlinedTextField(notes, { notes = it }, label = { Text("Notes / outcome") }, minLines = 3, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp))
                if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            Button(onClick = {
                runCatching {
                    val name = procedure.trim()
                    require(name.isNotBlank()) { "Choose or type a procedure" }
                    require(teeth.isNotEmpty()) { "Select at least one tooth" }
                    if (status == "Completed") require(doctor.isNotBlank()) { "Record the doctor who performed the treatment" }
                    val zone = runCatching { ClinicalRules.zone(config) }.getOrDefault(ZoneId.systemDefault())
                    val at = runCatching { LocalDate.parse(date.trim()).atTime(12, 0).atZone(zone).toInstant().toEpochMilli() }
                        .getOrElse { throw IllegalArgumentException("Choose the treatment date") }
                    require(at <= System.currentTimeMillis() + 366L * 86_400_000L) { "Check the treatment date" }
                    val copy = JSONObject(patient.toString())
                    val option = procedureOptions.firstOrNull { it.label.equals(name, true) }
                    if (editing != null) {
                        val description = editTreatment(copy, editing.optString("id"), name, status, surfaces.toList(), doctor.trim(), at, notes.trim(), option)
                        onSave(copy, "Treatment edited · $description")?.let { throw IllegalStateException(it) }
                        return@runCatching
                    }
                    val group = uid()
                    for (key in teeth) {
                        // Every treatment must point at a recorded chart slot (validated locally and on the server).
                        val slot = copy.obj("chart").obj("teeth").optJSONObject(key)
                            ?: JSONObject().put("assessment", "assessed").put("presence", "present").also { copy.obj("chart").obj("teeth").put(key, it) }
                        if (slot.optString("assessment") == "not_assessed") slot.put("assessment", "assessed")
                        if (slot.optString("presence", "unknown") == "unknown") slot.put("presence", "present")
                        val top = if (key[1].digitToInt() <= 3) "I" else "O"
                        val codes = surfaces.map { if (it == "Top") top else it }.distinct()
                        val surfaceText = if (codes.isEmpty()) "Whole tooth" else codes.joinToString("")
                        val id = uid()
                        val item = JSONObject().put("id", id).put("groupId", group).put("tooth", key).put("surface", surfaceText)
                            .put("procedure", name).put("notes", notes.trim()).put("status", status).put("at", at).put("doctor", doctor.trim())
                        item.array("events").put(JSONObject().put("id", uid()).put("to", status).put("at", System.currentTimeMillis()).put("doctor", doctor.trim()))
                        if (status == "Completed") {
                            item.put("performedAt", at)
                            copy.array("performedProcedures").put(JSONObject().put("id", uid()).put("treatmentId", id).put("tooth", key)
                                .put("surface", surfaceText).put("procedure", name).put("doctor", doctor.trim())
                                .put("outcome", notes.trim().ifBlank { "$name completed" }).put("at", at))
                            applyOutcome(copy, key, name, option, codes, top)
                        }
                        copy.array("treatments").put(item)
                    }
                    val teethText = teeth.joinToString(", ") { ToothNotation.label(it) + " (FDI $it)" }
                    onSave(copy, "Treatment recorded · $name · $status · $teethText")?.let { throw IllegalStateException(it) }
                }.onSuccess { onDismiss() }.onFailure { error = it.localizedMessage ?: "Review the treatment details" }
            }, shape = RoundedCornerShape(14.dp)) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * Corrects one recorded treatment in [copy]. The change is logged as an "edited" event holding the
 * previous values. Linked performed care follows the treatment: updated while Completed, kept but
 * marked voided when the treatment is moved to another status, and created when it becomes Completed.
 */
private fun editTreatment(copy: JSONObject, id: String, name: String, status: String, surfaces: List<String>, doctor: String, at: Long, notes: String, option: ProcedureOption?): String {
    val item = copy.optJSONArray("treatments").objectList().firstOrNull { it.optString("id") == id } ?: error("This treatment no longer exists. Reopen the patient.")
    val key = item.optString("tooth")
    val top = if (key[1].digitToInt() <= 3) "I" else "O"
    val codes = surfaces.map { if (it == "Top") top else it }.distinct()
    val surfaceText = if (codes.isEmpty()) "Whole tooth" else codes.joinToString("")
    if (status == "Completed") require(doctor.isNotBlank()) { "Record the doctor who performed the treatment" }
    val wasCompleted = item.optString("status") == "Completed"
    val previous = JSONObject()
    for (field in listOf("procedure", "status", "surface", "doctor", "at", "performedAt", "notes")) if (item.has(field)) previous.put(field, item.get(field))
    item.put("procedure", name).put("status", status).put("surface", surfaceText).put("doctor", doctor).put("at", at).put("notes", notes)
    if (status == "Completed") item.put("performedAt", at) else item.remove("performedAt")
    item.array("events").put(JSONObject().put("id", uid()).put("to", status).put("at", System.currentTimeMillis()).put("doctor", doctor)
        .put("edit", true).put("previous", previous))
    val performed = copy.array("performedProcedures")
    val linked = performed.objects().filter { it.optString("treatmentId") == id }
    if (status == "Completed") {
        val current = linked.firstOrNull() ?: JSONObject().put("id", uid()).put("treatmentId", id).put("tooth", key).also { performed.put(it) }
        current.put("surface", surfaceText).put("procedure", name).put("doctor", doctor).put("outcome", notes.ifBlank { "$name completed" }).put("at", at).remove("voided")
        linked.drop(1).forEach { it.put("voided", true) }
        if (!wasCompleted || !previous.optString("procedure").equals(name, true)) applyOutcome(copy, key, name, option, codes, top)
    } else linked.forEach { it.put("voided", true) }
    val changes = listOfNotNull(
        "procedure ${previous.optString("procedure")} → $name".takeIf { previous.optString("procedure") != name },
        "status ${previous.optString("status")} → $status".takeIf { previous.optString("status") != status },
        "surface ${previous.optString("surface")} → $surfaceText".takeIf { previous.optString("surface") != surfaceText },
        "doctor → $doctor".takeIf { previous.optString("doctor") != doctor },
        "date changed".takeIf { previous.optLong("at") != at },
        "notes updated".takeIf { previous.optString("notes") != notes },
    ).ifEmpty { listOf("no changes") }
    return "${ToothNotation.label(key)} (FDI $key) · " + changes.joinToString("; ")
}

/** Writes the chart consequence of completed care, using only the schema's coded vocabulary. */
private fun applyOutcome(copy: JSONObject, key: String, name: String, option: ProcedureOption?, surfaces: List<String>, top: String) {
    val tooth = copy.obj("chart").obj("teeth").obj(key)
    tooth.put("assessment", "assessed")
    val lostStates = setOf("missing", "extracted", "exfoliated", "congenitally_absent")
    when {
        name.equals("Extraction", true) -> {
            tooth.put("presence", "extracted"); tooth.remove("surfaces"); tooth.put("findings", JSONArray())
            return
        }
        name.equals("Implant", true) -> {
            if (tooth.optString("presence") in lostStates) tooth.put("replacement", "implant")
            return
        }
    }
    if (tooth.optString("presence", "unknown") == "unknown") tooth.put("presence", "present")
    if (tooth.optString("presence") in lostStates) return
    if (name.equals("Root canal", true)) {
        val findings = tooth.array("findings")
        if ((0 until findings.length()).none { findings.optString(it) == "endodontically_treated" }) findings.put("endodontically_treated")
    }
    val restoration = option?.restoration ?: return
    val targets = surfaces.ifEmpty { listOf(top) }
    for (surface in targets) {
        val observation = tooth.obj("surfaces").obj(surface)
        observation.put("assessment", "assessed"); observation.array("findings")
        val codes = observation.array("restorations")
        if ((0 until codes.length()).none { codes.optString(it) == restoration }) codes.put(restoration)
    }
}
