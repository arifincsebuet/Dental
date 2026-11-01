package com.spala.dental

import android.content.Context
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private fun stamp(time: Long, config: JSONObject = JSONObject()) = DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm").withZone(runCatching { ClinicalRules.zone(config) }.getOrDefault(ZoneId.systemDefault())).format(Instant.ofEpochMilli(time))
private fun chartSummary(chart: JSONObject?): List<String> {
    val teeth = chart?.optJSONObject("teeth") ?: return listOf("No chart available")
    val result = mutableListOf<String>()
    for (key in teeth.keys().asSequence().sorted()) {
        val tooth = teeth.getJSONObject(key)
        result += "Tooth $key: ${tooth.optString("assessment", "not_assessed").replace('_', ' ')}; ${tooth.optString("presence", "unknown").replace('_', ' ')}"
        tooth.optJSONArray("findings")?.let { codes -> if (codes.length() > 0) result += "  Findings: " + (0 until codes.length()).joinToString { codes.getString(it).replace('_', ' ') } }
        tooth.optJSONObject("surfaces")?.let { surfaces -> for (surface in surfaces.keys()) {
            val observation = surfaces.getJSONObject(surface)
            val findings = observation.optJSONArray("findings") ?: JSONArray()
            val restorations = observation.optJSONArray("restorations") ?: JSONArray()
            result += "  $surface: ${observation.optString("assessment").replace('_', ' ')}; findings " +
                (0 until findings.length()).joinToString { findings.getString(it).replace('_', ' ') }.ifBlank { "none recorded" } +
                "; restorations " + (0 until restorations.length()).joinToString { restorations.getString(it).replace('_', ' ') }.ifBlank { "none recorded" }
        } }
    }
    if (result.isEmpty()) result += "No teeth assessed in this chart"
    result += "Unrecorded teeth/surfaces remain unassessed, with unknown presence."
    return result
}
private data class ClinicalForm(val title: String, val fields: Map<String, String>, val commit: (Map<String, String>) -> Unit)

@Composable
fun EncountersScreen(patient: JSONObject, config: JSONObject, onChange: (JSONObject, String) -> Unit) {
    var form by remember { mutableStateOf<ClinicalForm?>(null) }
    Text("Clinical encounters", style = MaterialTheme.typography.titleLarge)
    Text("Finalized notes retain their original text. Corrections are recorded as amendments.")
    patient.optJSONArray("encounters")?.objects().orEmpty().asReversed().forEach { encounter ->
        ClinicalCard("${stamp(encounter.optLong("at"), config)} · ${encounter.optString("status")}") {
            Text("Clinician: ${encounter.optString("doctor")}")
            listOf("complaint", "examination", "diagnosis", "plan").forEach { field -> Text("${field.replaceFirstChar { it.uppercase() }}: ${encounter.optString(field)}") }
            encounter.optJSONArray("amendments")?.objects().orEmpty().forEach { amendment ->
                Text("Amendment · ${stamp(amendment.optLong("at"), config)}", color = Color(0xFF2563EB))
                Text("${amendment.optString("reason")}\n${amendment.optString("text")}")
            }
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                if (encounter.optString("status") == "Draft") {
                    TextButton(onClick = { form = ClinicalForm("Edit encounter", listOf("doctor", "complaint", "examination", "diagnosis", "plan").associateWith { encounter.optString(it) }) { values ->
                        require(values["doctor"].orEmpty().isNotBlank()) { "Record the clinician" }
                        val copy = JSONObject(patient.toString()); val target = copy.array("encounters").objects().first { it.optString("id") == encounter.optString("id") }
                        values.forEach { (key, value) -> target.put(key, value) }; onChange(copy, "Encounter draft updated")
                    } }) { Text("Edit draft") }
                    TextButton(onClick = {
                        val copy = JSONObject(patient.toString())
                        copy.array("encounters").objects().first { it.optString("id") == encounter.optString("id") }.put("status", "Finalized").put("finalizedAt", System.currentTimeMillis())
                        onChange(copy, "Encounter finalized by ${encounter.optString("doctor")}")
                    }) { Text("Finalize") }
                } else TextButton(onClick = { form = ClinicalForm("Amend finalized encounter", linkedMapOf("reason" to "", "text" to "")) { values ->
                    require(values.values.all { it.isNotBlank() }) { "Reason and correction are required" }
                    val copy = JSONObject(patient.toString())
                    copy.array("encounters").objects().first { it.optString("id") == encounter.optString("id") }.array("amendments")
                        .put(JSONObject(values).put("id", uid()).put("at", System.currentTimeMillis()))
                    onChange(copy, "Finalized encounter amended: ${values["reason"]}")
                } }) { Text("Amend with reason") }
            }
        }
    }
    Button(onClick = { form = ClinicalForm("New encounter", linkedMapOf("doctor" to "", "complaint" to "", "examination" to "", "diagnosis" to "", "plan" to "")) { values ->
        require(values["doctor"].orEmpty().isNotBlank() && values["complaint"].orEmpty().isNotBlank()) { "Clinician and complaint are required" }
        val copy = JSONObject(patient.toString())
        copy.array("encounters").put(JSONObject(values).put("id", uid()).put("at", System.currentTimeMillis()).put("status", "Draft").put("amendments", JSONArray()))
        onChange(copy, "Clinical encounter started")
    } }) { Text("+ New encounter") }
    ClinicalFormDialog(form) { form = null }
}

@Composable
fun TreatmentWorkspaceScreen(patient: JSONObject, config: JSONObject, onChange: (JSONObject, String) -> Unit) {
    var form by remember { mutableStateOf<ClinicalForm?>(null) }
    Text("Treatment plan", style = MaterialTheme.typography.titleLarge)
    if ((patient.optJSONArray("treatments")?.length() ?: 0) == 0) Text("No treatment yet. Use Record treatment on the 3D model above, or tap a tooth in the Chart tab.")
    patient.optJSONArray("treatments")?.objects().orEmpty().forEach { treatment ->
        val status = treatment.optString("status", "Planned")
        ClinicalCard("${ToothNotation.label(treatment.optString("tooth"))} · ${treatment.optString("procedure")}") {
            Text("${treatment.optString("surface", "Whole tooth")} · $status", color = Color(0xFF0F766E))
            Text(treatment.optString("notes"))
            val nextStates = when (status) {
                "Planned" -> listOf("Accepted", "Deferred", "Declined", "Referred", "Cancelled")
                "Accepted" -> listOf("In progress", "Deferred", "Cancelled")
                "In progress" -> listOf("Completed", "Deferred", "Referred")
                "Deferred", "Referred" -> listOf("Accepted", "Cancelled")
                else -> emptyList()
            }
            Row(Modifier.horizontalScroll(rememberScrollState())) { nextStates.forEach { next ->
                TextButton(onClick = {
                    val fields = if (next == "Completed") linkedMapOf("doctor" to "", "outcome" to "", "restoration" to "none") else linkedMapOf("reason" to "")
                    form = ClinicalForm("$next · ${treatment.optString("procedure")}", fields) { values ->
                        require(values.values.firstOrNull().orEmpty().isNotBlank()) { "Record the clinician or decision reason" }
                        if (next == "Completed") require(values["outcome"].orEmpty().isNotBlank()) { "Record the performed procedure outcome" }
                        val copy = JSONObject(patient.toString())
                        val target = copy.array("treatments").objects().first { it.optString("id") == treatment.optString("id") }
                        target.put("status", next)
                        target.array("events").put(JSONObject(values).put("id", uid()).put("from", status).put("to", next).put("at", System.currentTimeMillis()))
                        if (next == "Completed") {
                            target.put("performedAt", System.currentTimeMillis()).put("doctor", values["doctor"])
                            copy.array("performedProcedures").put(JSONObject(values).put("id", uid()).put("treatmentId", target.optString("id"))
                                .put("tooth", target.optString("tooth")).put("surface", target.optString("surface"))
                                .put("procedure", target.optString("procedure")).put("at", System.currentTimeMillis()))
                            val restoration = values["restoration"].orEmpty().trim()
                            val allowed = setOf("none", "composite", "amalgam", "glass_ionomer", "sealant", "crown", "inlay", "onlay")
                            require(restoration in allowed) { "Use a supported restoration code or none" }
                            val surfaceKey = target.optString("surface")
                            if (restoration != "none") {
                                // Multi-surface items such as "MO" restore each listed surface.
                                val keys = surfaceKey.map { it.toString() }
                                require(keys.isNotEmpty() && keys.all { it in listOf("M", "D", "F", "L", "I", "O") }) { "Record a specific surface in the tooth editor before adding a restoration outcome" }
                                val tooth = copy.obj("chart").obj("teeth").obj(target.optString("tooth"))
                                for (key in keys) {
                                    val observation = tooth.obj("surfaces").obj(key)
                                    observation.put("assessment", "assessed"); observation.array("findings")
                                    val restorations = observation.array("restorations")
                                    if ((0 until restorations.length()).none { restorations.getString(it) == restoration }) restorations.put(restoration)
                                }
                            }
                        }
                        onChange(copy, "Treatment ${target.optString("procedure")}: $status → $next")
                    }
                }) { Text(next) }
            } }
        }
    }
    Text("Performed procedures", style = MaterialTheme.typography.titleMedium)
    patient.optJSONArray("performedProcedures")?.objects().orEmpty().asReversed().forEach { performed ->
        ClinicalCard("${performed.optString("procedure")} · tooth ${performed.optString("tooth")}") {
            Text("${stamp(performed.optLong("at"), config)} · ${performed.optString("doctor")}")
            Text(performed.optString("outcome"))
            performed.optJSONArray("amendments")?.objects().orEmpty().forEach { Text("Amendment: ${it.optString("reason")} · ${it.optString("text")}") }
            TextButton(onClick = { form = ClinicalForm("Amend procedure record", linkedMapOf("reason" to "", "text" to "")) { values ->
                require(values.values.all { it.isNotBlank() }) { "Provide both a reason and correction" }
                val copy = JSONObject(patient.toString())
                copy.array("performedProcedures").objects().first { it.optString("id") == performed.optString("id") }.array("amendments")
                    .put(JSONObject(values).put("id", uid()).put("at", System.currentTimeMillis()))
                onChange(copy, "Performed procedure amended: ${values["reason"]}")
            } }) { Text("Amend") }
        }
    }
    ClinicalFormDialog(form) { form = null }
}

@Composable
fun ChartHistoryScreen(patient: JSONObject, config: JSONObject, openChart: (JSONObject) -> Unit) {
    val history = patient.optJSONArray("history")?.objects().orEmpty()
    var comparison by remember(patient.toString()) { mutableStateOf<String?>(null) }
    Text("Clinical timeline", style = MaterialTheme.typography.titleLarge)
    history.indices.reversed().forEach { index ->
        val entry = history[index]
        ClinicalCard(stamp(entry.optLong("at"), config)) {
            Text(entry.optString("text")); if (entry.has("actor")) Text("Recorded by ${entry.optString("actor")}")
            if (entry.optJSONObject("previousValues")?.length()?.let { it > 0 } == true) {
                TextButton(onClick = {
                    val earlier = JSONObject(patient.toString())
                    for (i in history.lastIndex downTo index) history[i].optJSONObject("previousValues")?.let { before -> for (key in before.keys()) earlier.put(key, before.get(key)) }
                    comparison = "Before this change\n${chartSummary(earlier.optJSONObject("chart")).joinToString("\n")}\n\nCurrent chart\n${chartSummary(patient.optJSONObject("chart")).joinToString("\n")}"
                }) { Text("Compare chart") }
                TextButton(onClick = {
                    val earlier = JSONObject(patient.toString())
                    for (i in history.lastIndex downTo index) history[i].optJSONObject("previousValues")?.let { before -> for (key in before.keys()) earlier.put(key, before.get(key)) }
                    openChart(earlier.obj("chart").put("readOnly", true))
                }) { Text("View earlier chart in 3D") }
            }
        }
    }
    if (history.isEmpty()) Text("No clinical changes recorded yet.")
    Text("Server audit", style = MaterialTheme.typography.titleMedium)
    patient.optJSONArray("audit")?.objects()?.asReversed()?.forEach { event -> ClinicalCard("Revision ${event.optLong("revision")}") { Text("${event.optString("action").replace('_', ' ')} · ${event.optString("actor")}\n${stamp(event.optLong("at"), config)}") } }
    comparison?.let { text -> AlertDialog(onDismissRequest = { comparison = null }, title = { Text("Chart comparison") }, text = { Text(text, Modifier.verticalScroll(rememberScrollState())) }, confirmButton = { TextButton(onClick = { comparison = null }) { Text("Close") } }) }
}

@Composable
private fun ClinicalCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { Text(title, style = MaterialTheme.typography.titleMedium); content() }
    }
}

@Composable
private fun ClinicalFormDialog(form: ClinicalForm?, close: () -> Unit) {
    val saveStatus = LocalEditorSaveStatus.current
    form?.let { active -> key(active) {
        val values = remember { mutableStateMapOf<String, String>().apply { putAll(active.fields) } }
        var error by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = close, title = { Text(active.title) }, text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                values.keys.toList().forEach { field -> OutlinedTextField(values[field].orEmpty(), { values[field] = it }, label = { Text(field.replaceFirstChar { it.uppercase() }) }, modifier = Modifier.fillMaxWidth()) }
                if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
            }
        }, confirmButton = { TextButton(onClick = { runCatching { val beforeSave = saveStatus(); active.commit(values.toMap()); requireAcceptedSave(beforeSave, saveStatus()) }.onSuccess { close() }.onFailure { error = it.localizedMessage ?: "Review your input" } }) { Text("Save") } }, dismissButton = { TextButton(onClick = close) { Text("Cancel") } })
    } }
}

object ClinicExports {
    fun patientPdf(patient: JSONObject, config: JSONObject, output: OutputStream) {
        val lines = mutableListOf(config.optString("name", "Dental Care"), "PATIENT CARE SUMMARY", "Patient: ${patient.optString("name")}", "DOB: ${patient.optString("dob")}",
            "Generated: ${stamp(System.currentTimeMillis(), config)}", if (patient.optBoolean("dirty")) "DEVICE RECORD · cloud confirmation pending" else "Cloud revision ${patient.optLong("remoteVersion")}",
            "Medical alerts: ${patient.optString("allergies")}", "", "CLINICAL STORY", patient.optString("story"), "", "DENTAL CHART · FDI")
        lines += chartSummary(patient.optJSONObject("chart"))
        lines += listOf("", "TREATMENT PLAN")
        patient.optJSONArray("treatments")?.objects().orEmpty().forEach { lines += "Tooth ${it.optString("tooth")}: ${it.optString("procedure")} · ${it.optString("status")}" }
        lines += listOf("", "PERFORMED CARE")
        patient.optJSONArray("performedProcedures")?.objects().orEmpty().forEach { lines += "${it.optString("procedure")} · ${it.optString("doctor")} · ${it.optString("outcome")}" }
        val document = PdfDocument()
        try {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 10f; color = AndroidColor.rgb(30, 41, 59) }
            val wrapped = lines.flatMap { wrap(it, paint, 510f) }
            wrapped.chunked(45).forEachIndexed { index, chunk ->
                val page = document.startPage(PdfDocument.PageInfo.Builder(595, 842, index + 1).create())
                paint.color = AndroidColor.rgb(15, 118, 110); page.canvas.drawRect(0f, 0f, 595f, 12f, paint); paint.color = AndroidColor.rgb(30, 41, 59)
                chunk.forEachIndexed { row, line -> page.canvas.drawText(line, 42f, 48f + row * 16f, paint) }
                page.canvas.drawText("Page ${index + 1} · Dental Care", 42f, 812f, paint)
                document.finishPage(page)
            }
            document.writeTo(output)
        } finally { document.close() }
    }
    private fun wrap(text: String, paint: Paint, width: Float): List<String> {
        val result = mutableListOf<String>()
        for (paragraph in text.split('\n')) {
            var rest = paragraph
            if (rest.isEmpty()) result += ""
            while (rest.isNotEmpty()) { val count = paint.breakText(rest, true, width, null).coerceAtLeast(1); result += rest.take(count); rest = rest.drop(count) }
        }
        return result
    }
}
