package com.spala.dental

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.spala.dental.ui.theme.*
import org.json.JSONObject
import java.time.LocalDate
import java.time.ZoneId

/*
 * Admin → patient notifications. The admin writes a message and sends it now or schedules it;
 * the queuePatientPush function delivers "now" immediately and the dispatchPatientPushes
 * scheduled function delivers scheduled ones to the tokens stored in each patient's document.
 * Only patients who have signed in to the patient app (and allowed notifications) receive them.
 */

private val templates = listOf(
    "Appointment reminder" to "Reminder: you have a dental appointment soon. Open the app for the time and doctor.",
    "Check-up due" to "It's time for your dental check-up. Book a slot in the app.",
    "Clinic closed" to "The clinic will be closed on the announced day. Please book another time in the app.",
    "Custom" to "",
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PatientPushDialog(store: ClinicStore, preset: JSONObject?, onClose: () -> Unit) {
    val zone = runCatching { ClinicalRules.zone(store.config) }.getOrDefault(ZoneId.systemDefault())
    val today = LocalDate.now(zone)
    val withApp = store.patients.filter { !it.optBoolean("archived") && it.optJSONObject("portal") != null }
    var audience by remember { mutableStateOf(if (preset != null) "This patient" else "Appointments on a day") }
    var day by remember { mutableStateOf(today.plusDays(1).toString()) }
    val chosen = remember { mutableStateListOf<String>() }
    var search by remember { mutableStateOf("") }
    var template by remember { mutableStateOf(templates.first().first) }
    var title by remember { mutableStateOf(templates.first().first) }
    var body by remember { mutableStateOf(templates.first().second) }
    var timing by remember { mutableStateOf("Send now") }
    var sendAt by remember { mutableStateOf("${today.plusDays(1)} 09:00") }
    var error by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { store.loadPatientPushes() }

    val recipients: List<JSONObject> = when (audience) {
        "This patient" -> listOfNotNull(preset)
        "Appointments on a day" -> {
            val date = runCatching { LocalDate.parse(day) }.getOrNull()
            withApp.filter { p ->
                p.optJSONArray("appointments").objectList().any { a ->
                    a.optString("status") in setOf("Scheduled", "Confirmed", "Requested") &&
                        runCatching { java.time.Instant.ofEpochMilli(ClinicalRules.appointmentMillis(a, store.config)).atZone(zone).toLocalDate() }.getOrNull() == date
                }
            }
        }
        "All with app access" -> withApp
        else -> withApp.filter { it.optString("id") in chosen }
    }

    AlertDialog(
        onDismissRequest = onClose, shape = RoundedCornerShape(28.dp),
        title = { Column { Text("Notify patients"); Text("Push notification to the patient app", style = MaterialTheme.typography.bodySmall, color = InkMuted) } },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Send to", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    (if (preset != null) listOf("This patient") else listOf("Appointments on a day", "All with app access", "Choose patients")).forEach { option ->
                        FilterChip(selected = audience == option, onClick = { audience = option }, label = { Text(option) })
                    }
                }
                when (audience) {
                    "This patient" -> Text(preset?.optString("name").orEmpty(), style = MaterialTheme.typography.titleSmall)
                    "Appointments on a day" -> DateField(day, { day = it }, "Patients with appointments on", today = today)
                    "Choose patients" -> {
                        OutlinedTextField(search, { search = it }, label = { Text("Search patients with app access") }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp))
                        withApp.filter { search.isBlank() || "${it.optString("name")} ${it.optString("phone")}".contains(search.trim(), true) }.take(8).forEach { p ->
                            val id = p.optString("id")
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(id in chosen, { if (it) chosen += id else chosen -= id })
                                Text(p.optString("name"), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
                Text("${recipients.size} recipient(s) · ${withApp.size} of ${store.patients.count { !it.optBoolean("archived") }} patients use the patient app",
                    style = MaterialTheme.typography.bodySmall, color = if (recipients.isEmpty()) Amber600 else InkMuted)

                Text("Message", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    templates.forEach { (name, text) ->
                        FilterChip(selected = template == name, onClick = { template = name; if (name != "Custom") { title = name; body = text } }, label = { Text(name) })
                    }
                }
                OutlinedTextField(title, { title = it.take(65) }, label = { Text("Title") }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp),
                    supportingText = { Text("${title.length}/65") })
                OutlinedTextField(body, { body = it.take(240) }, label = { Text("Message") }, minLines = 3, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp),
                    supportingText = { Text("${body.length}/240 · shown on the patient's phone; avoid sensitive clinical details") })

                SegmentedPills(listOf("Send now", "Schedule"), timing, Modifier.fillMaxWidth()) { timing = it }
                if (timing == "Schedule") DateTimeField(sendAt, { sendAt = it }, "Send at (clinic time)", today = today)

                HorizontalDivider()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Automatic appointment reminders", style = MaterialTheme.typography.titleSmall)
                        Text("Also push the clinic's appointment reminders to patients' phones", style = MaterialTheme.typography.bodySmall, color = InkMuted)
                    }
                    Switch(store.config.optBoolean("patientPushReminders"), { on -> store.saveConfig(JSONObject(store.config.toString()).put("patientPushReminders", on)) })
                }

                val history = store.patientPushes.sortedByDescending { it.optLong("sendAt") }.take(6)
                if (history.isNotEmpty()) {
                    Text("Recent and scheduled", style = MaterialTheme.typography.labelLarge)
                    history.forEach { job ->
                        Surface(shape = RoundedCornerShape(14.dp), border = BorderStroke(1.dp, Hairline), color = SurfaceWhite) {
                            Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(job.optString("title"), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text("${runCatching { ClinicalRules.displayTime(job.optLong("sendAt"), store.config) }.getOrDefault("")} · ${job.optInt("recipients")} patients · " +
                                        job.optString("status").replace('_', ' ') + if (job.optInt("delivered") > 0) " (${job.optInt("delivered")} devices)" else "",
                                        style = MaterialTheme.typography.bodySmall, color = InkMuted)
                                }
                                if (job.optString("status") == "scheduled") TextButton(onClick = { store.cancelPatientPush(job.optString("id")) }) { Text("Cancel", color = Rose600) }
                            }
                        }
                    }
                }
                if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            Button(enabled = recipients.isNotEmpty() && title.isNotBlank() && body.isNotBlank() && !store.busy, onClick = {
                error = ""
                val at = if (timing == "Schedule") runCatching { ClinicalRules.parseDateTime(sendAt, store.config) }.getOrNull() else null
                if (timing == "Schedule" && (at == null || at < System.currentTimeMillis())) { error = "Choose a future time"; return@Button }
                store.queuePatientPush(recipients.map { it.optString("id") }, title.trim(), body.trim(), at) { problem -> if (problem == null) onClose() else error = problem }
            }) { Text(if (timing == "Schedule") "Schedule" else "Send now") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Close") } },
    )
}
