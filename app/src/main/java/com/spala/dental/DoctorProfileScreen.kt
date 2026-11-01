package com.spala.dental

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.spala.dental.ui.theme.*
import org.json.JSONObject
import java.time.LocalDate

/** Clinicians who appear as "doctors" across the app. */
fun clinicianRecords(config: JSONObject): List<JSONObject> = config.optJSONArray("staff").objectList()
    .filter { it.optString("role") in listOf("dentist", "owner", "administrator") || it.optString("role").isBlank() }

/**
 * Doctor profile. The 3D model comes first (a neutral reference dentition: no patient data), then
 * identity, workload and upcoming appointments assigned to this clinician by name.
 */
@Composable
fun DoctorProfileScreen(
    staff: JSONObject,
    patients: List<JSONObject>,
    config: JSONObject,
    onBack: () -> Unit,
    onOpenPatient: (String) -> Unit,
) {
    val name = staff.optString("name").ifBlank { "Clinician" }
    var tooth by rememberSaveable(staff.optString("id")) { mutableStateOf<String?>(null) }
    val reference = remember { referenceChart() }
    val mine = remember(patients, name) {
        patients.flatMap { patient -> patient.optJSONArray("appointments").objectList().map { patient to it } }
            .filter { (_, appointment) -> appointment.optString("doctor").trim().equals(name.trim(), ignoreCase = true) }
    }
    val zone = runCatching { ClinicalRules.zone(config) }.getOrNull()
    val now = System.currentTimeMillis()
    fun millis(appointment: JSONObject) = runCatching { ClinicalRules.appointmentMillis(appointment, config) }.getOrNull()
    val todayStart = zone?.let { LocalDate.now(it).atStartOfDay(it).toInstant().toEpochMilli() } ?: 0L
    val todayEnd = todayStart + 86_400_000L
    val today = mine.count { (_, a) -> millis(a)?.let { it in todayStart until todayEnd } == true && a.optString("status") !in setOf("Cancelled", "No show") }
    val upcoming = mine.filter { (_, a) -> (millis(a) ?: Long.MIN_VALUE) >= now && a.optString("status") !in ClinicalRules.terminalAppointments }
        .sortedBy { (_, a) -> millis(a) ?: Long.MAX_VALUE }
    val patientCount = mine.map { it.first.optString("id") }.distinct().size
    val procedures = patients.sumOf { patient -> patient.optJSONArray("performedProcedures").objectList().count { it.optString("doctor").trim().equals(name.trim(), true) } }
    val active = staff.optBoolean("active", true)

    TextButton(onClick = onBack, contentPadding = PaddingValues(horizontal = 4.dp)) {
        GlyphIcon(Glyph.Back, Teal700, 18.dp); Spacer(Modifier.width(4.dp)); Text("Care team")
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Avatar(name, 60.dp, listOf(Color(0xFF3B82F6), Teal700))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(if (staff.optString("role") == "dentist" && !name.startsWith("Dr", true)) "Dr. $name" else name,
                style = MaterialTheme.typography.headlineSmall, color = Ink)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                StatusPill(staff.optString("role").ifBlank { "clinician" }.replaceFirstChar { it.uppercase() }, Teal50, Teal700, dot = false)
                StatusPill(if (active) "Active" else "Inactive", if (active) Green50 else SurfaceTint, if (active) Green600 else InkMuted)
            }
        }
    }

    Teeth3DHeroCard(
        chart = reference,
        title = "Clinical model",
        subtitle = "Reference dentition · tap a tooth to identify it",
        selectedTeeth = setOfNotNull(tooth),
        onToothTap = { tooth = if (tooth == it) null else it },
    ) {
        val selected = tooth
        if (selected != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(Brush.linearGradient(listOf(Teal500, Teal700))), contentAlignment = Alignment.Center) {
                    Text(ToothNotation.label(selected), color = Color.White, fontWeight = FontWeight.Bold)
                }
                Column(Modifier.weight(1f)) {
                    Text(toothName(selected), style = MaterialTheme.typography.titleSmall, color = Ink)
                    Text("${if (ToothNotation.current == ToothNotation.FDI) "" else "FDI $selected · "}${if (selected[1].digitToInt() <= 3) "Anterior" else "Posterior"} · ${if (selected[0] in "1256") "Maxillary" else "Mandibular"} arch",
                        style = MaterialTheme.typography.bodySmall, color = InkMuted)
                }
                TextButton(onClick = { tooth = null }) { Text("Clear") }
            }
        } else {
            Text("Use the model to explain anatomy and FDI numbering to patients. Patient charts open from each patient profile.",
                style = MaterialTheme.typography.bodySmall, color = InkMuted)
        }
    }

    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        StatTile("Today", today.toString(), Teal700, Glyph.Calendar, Modifier.weight(1f))
        StatTile("Upcoming", upcoming.size.toString(), Blue600, Glyph.Clipboard, Modifier.weight(1f))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        StatTile("Patients", patientCount.toString(), Color(0xFF7C3AED), Glyph.Person, Modifier.weight(1f))
        StatTile("Procedures", procedures.toString(), Amber600, Glyph.Tooth, Modifier.weight(1f))
    }

    SectionHeader("Upcoming appointments")
    if (upcoming.isEmpty()) SurfaceCard { Text("No upcoming appointments assigned to $name.", color = InkMuted) }
    upcoming.take(8).forEach { (patient, appointment) ->
        SurfaceCard(onClick = { onOpenPatient(patient.optString("id")) }, padding = 14.dp) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Avatar(patient.optString("name"), 40.dp)
                Column(Modifier.weight(1f)) {
                    Text(patient.optString("name"), style = MaterialTheme.typography.titleSmall, color = Ink)
                    Text(listOf(millis(appointment)?.let { ClinicalRules.displayTime(it, config) } ?: appointment.optString("date"), appointment.optString("reason"))
                        .filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = InkMuted)
                }
                StatusPill(appointment.optString("status").ifBlank { "Scheduled" }, Blue100, Blue600, dot = false)
            }
        }
    }

    SectionHeader("Contact & credentials")
    SurfaceCard {
        InfoRow(Glyph.Phone, "Phone", staff.optString("phone").ifBlank { "Not recorded" })
        InfoRow(Glyph.Export, "Email", staff.optString("email").ifBlank { "Not recorded" })
        InfoRow(Glyph.Clipboard, "Professional registration", staff.optString("registration").ifBlank { "Not recorded" })
        InfoRow(Glyph.Calendar, "Working hours", staff.optString("hours").ifBlank { "Not recorded" })
    }
}
