package com.spala.dental

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spala.dental.ui.theme.*
import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/*
 * Appointment slots.
 * Admins define availability sessions in clinic settings (config.availability):
 *   {id, doctor, room?, startDate, endDate, weekdays[1..7] (empty = every day), start "HH:mm", end "HH:mm", slotMinutes, active}
 * Slots are computed from sessions; nothing per-slot is stored. A slot is booked when an active
 * appointment of the same doctor overlaps it. Staff compute slots from local data (no cloud reads);
 * patients get them from the getSlots function (one clinic read, no other patients' names).
 */

data class Slot(
    val sessionId: String, val doctor: String, val room: String, val date: LocalDate, val time: String,
    val startAt: Long, val durationMinutes: Int, val available: Boolean,
    val patientId: String? = null, val patientName: String? = null, val appointmentStatus: String? = null,
)

private val activeStatuses = setOf("Scheduled", "Confirmed", "Checked in", "In progress", "Requested")

private class Booking(val start: Long, val end: Long, val patientId: String, val patientName: String, val status: String)

/** Slot engine for staff, from the clinic config and locally cached patients. */
class SlotCalendar(private val config: JSONObject, patients: List<JSONObject>) {
    private val zone = runCatching { ClinicalRules.zone(config) }.getOrDefault(java.time.ZoneId.systemDefault())
    val sessions = config.optJSONArray("availability").objectList().filter { it.optBoolean("active", true) }
    private val bookings: Map<String, List<Booking>> = patients.filterNot { it.optBoolean("archived") }.flatMap { p ->
        p.optJSONArray("appointments").objectList().filter { it.optString("status") in activeStatuses }.mapNotNull { a ->
            val start = runCatching { ClinicalRules.appointmentMillis(a, config) }.getOrNull() ?: return@mapNotNull null
            a.optString("doctor").trim().lowercase() to Booking(start, start + a.optInt("durationMinutes", 30) * 60_000L, p.optString("id"), p.optString("name"), a.optString("status"))
        }
    }.groupBy({ it.first }, { it.second })

    fun slots(date: LocalDate, now: Long = System.currentTimeMillis()): List<Slot> = sessions.flatMap { session ->
        val first = runCatching { LocalDate.parse(session.optString("startDate")) }.getOrNull() ?: return@flatMap emptyList()
        val last = runCatching { LocalDate.parse(session.optString("endDate")) }.getOrNull() ?: return@flatMap emptyList()
        val weekdays = session.optJSONArray("weekdays")?.let { a -> (0 until a.length()).map { a.optInt(it) } }.orEmpty()
        if (date.isBefore(first) || date.isAfter(last) || (weekdays.isNotEmpty() && date.dayOfWeek.value !in weekdays)) return@flatMap emptyList()
        val step = session.optInt("slotMinutes", 30).coerceIn(5, 240)
        val from = runCatching { LocalTime.parse(session.optString("start")) }.getOrNull() ?: return@flatMap emptyList()
        val to = runCatching { LocalTime.parse(session.optString("end")) }.getOrNull() ?: return@flatMap emptyList()
        val doctor = session.optString("doctor").trim()
        generateSequence(from) { it.plusMinutes(step.toLong()) }.takeWhile { !it.plusMinutes(step.toLong()).isAfter(to) && !it.isBefore(from) }.map { time ->
            val startAt = date.atTime(time).atZone(zone).toInstant().toEpochMilli()
            val endAt = startAt + step * 60_000L
            val booked = bookings[doctor.lowercase()].orEmpty().firstOrNull { it.start < endAt && startAt < it.end }
            Slot(session.optString("id"), doctor, session.optString("room"), date, time.toString(), startAt, step,
                available = booked == null && startAt > now, patientId = booked?.patientId, patientName = booked?.patientName, appointmentStatus = booked?.status)
        }.toList()
    }.sortedWith(compareBy({ it.startAt }, { it.doctor }))
}

/** Slots returned by the getSlots function (patient app). */
fun slotsFromServer(items: List<JSONObject>): List<Slot> = items.map {
    Slot(it.optString("sessionId"), it.optString("doctor"), it.optString("room"), LocalDate.parse(it.optString("date")), it.optString("time"),
        it.optLong("startAt"), it.optInt("durationMinutes", 30), it.optBoolean("available"))
}

// ---------------------------------------------------------------------------------------------
// Month calendar
// ---------------------------------------------------------------------------------------------

/** Month grid. Each day shows a dot: green = free slots, grey = fully booked, none = no sessions. */
@Composable
fun MonthCalendar(month: YearMonth, selected: LocalDate, today: LocalDate, dayInfo: (LocalDate) -> Pair<Int, Int>, onMonth: (YearMonth) -> Unit, onSelect: (LocalDate) -> Unit) {
    SurfaceCard(padding = 12.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { onMonth(month.minusMonths(1)) }, modifier = Modifier.semantics { contentDescription = "Previous month" }) { GlyphIcon(Glyph.Back, Teal700, 20.dp) }
            Text(month.month.getDisplayName(TextStyle.FULL, Locale.getDefault()) + " " + month.year, style = MaterialTheme.typography.titleMedium, color = Ink,
                modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
            IconButton(onClick = { onMonth(month.plusMonths(1)) }, modifier = Modifier.semantics { contentDescription = "Next month" }) { GlyphIcon(Glyph.Chevron, Teal700, 20.dp) }
        }
        Row(Modifier.fillMaxWidth()) {
            DayOfWeek.values().forEach { day ->
                Text(day.getDisplayName(TextStyle.NARROW, Locale.getDefault()), Modifier.weight(1f), textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelSmall, color = InkMuted)
            }
        }
        val first = month.atDay(1)
        val offset = first.dayOfWeek.value - 1
        val cells = offset + month.lengthOfMonth()
        for (week in 0 until (cells + 6) / 7) {
            Row(Modifier.fillMaxWidth()) {
                for (column in 0 until 7) {
                    val dayNumber = week * 7 + column - offset + 1
                    if (dayNumber !in 1..month.lengthOfMonth()) Spacer(Modifier.weight(1f).height(48.dp))
                    else DayCell(month.atDay(dayNumber), selected, today, dayInfo, onSelect)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) { LegendDot(Green600, "Free slots"); LegendDot(InkSubtle, "Fully booked") }
    }
}

@Composable
private fun RowScope.DayCell(date: LocalDate, selected: LocalDate, today: LocalDate, dayInfo: (LocalDate) -> Pair<Int, Int>, onSelect: (LocalDate) -> Unit) {
                    val dayNumber = date.dayOfMonth
                    val (free, total) = dayInfo(date)
                    val isSelected = date == selected
                    Column(
                        Modifier.weight(1f).height(48.dp).padding(2.dp).clip(RoundedCornerShape(12.dp))
                            .background(if (isSelected) Teal700 else Color.Transparent)
                            .then(if (date == today && !isSelected) Modifier.border(1.dp, Teal500, RoundedCornerShape(12.dp)) else Modifier)
                            .clickable(role = Role.Button) { onSelect(date) }
                            .semantics { contentDescription = "${date.format(DateTimeFormatter.ofPattern("d MMMM"))}: " + if (total == 0) "no slots" else "$free of $total slots free" },
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
                    ) {
                        Text("$dayNumber", color = when { isSelected -> Color.White; date.isBefore(today) -> InkSubtle; else -> Ink },
                            fontWeight = if (isSelected || date == today) FontWeight.Bold else FontWeight.Normal, fontSize = 15.sp)
                        Box(Modifier.padding(top = 3.dp).size(6.dp).clip(CircleShape).background(when {
                            total == 0 -> Color.Transparent
                            free > 0 -> if (isSelected) Color.White else Green600
                            else -> if (isSelected) Color.White.copy(alpha = .6f) else InkSubtle
                        }))
                    }
}

/** Slots of one day, grouped by doctor. Free slots are outlined; booked slots are filled. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SlotGrid(slots: List<Slot>, showPatients: Boolean, onSlot: (Slot) -> Unit) {
    if (slots.isEmpty()) {
        SurfaceCard(background = SurfaceTint) { Text("No appointment slots on this day.", style = MaterialTheme.typography.bodySmall, color = InkMuted) }
        return
    }
    slots.groupBy { it.doctor }.forEach { (doctor, items) ->
        SurfaceCard(padding = 14.dp) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Avatar(doctor, 32.dp, listOf(Color(0xFF3B82F6), Teal700))
                Column(Modifier.weight(1f)) {
                    Text(doctor, style = MaterialTheme.typography.titleSmall, color = Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${items.count { it.available }} of ${items.size} free · ${items.first().durationMinutes} min" + items.first().room.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty(),
                        style = MaterialTheme.typography.bodySmall, color = InkMuted)
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items.forEach { slot ->
                    val booked = slot.patientName != null || (!slot.available && slot.startAt > System.currentTimeMillis())
                    val past = slot.startAt <= System.currentTimeMillis()
                    Surface(
                        onClick = { onSlot(slot) }, enabled = slot.available || (showPatients && slot.patientId != null),
                        shape = RoundedCornerShape(12.dp),
                        color = when { slot.available -> SurfaceWhite; booked -> SurfaceTint; else -> AppBackground },
                        border = BorderStroke(1.dp, if (slot.available) Teal500 else Hairline),
                        modifier = Modifier.semantics { contentDescription = "${slot.time} ${if (slot.available) "free" else if (booked) "booked" else "unavailable"}" },
                    ) {
                        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(slot.time, fontWeight = FontWeight.SemiBold, color = when { slot.available -> Teal700; past && !booked -> InkSubtle; else -> InkMuted })
                            if (showPatients && slot.patientName != null) Text(slot.patientName.substringBefore(' ').take(10), style = MaterialTheme.typography.labelSmall, color = InkMuted, maxLines = 1)
                            else if (!slot.available) Text(if (booked) "Booked" else "Past", style = MaterialTheme.typography.labelSmall, color = InkSubtle)
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Staff: calendar + slots + booking
// ---------------------------------------------------------------------------------------------

@Composable
fun SlotCalendarScreen(
    patients: List<JSONObject>, config: JSONObject, canManage: Boolean,
    onBook: (patient: JSONObject, slot: Slot, reason: String) -> String?,
    onSaveConfig: (JSONObject) -> Boolean, onOpenPatient: (String) -> Unit,
) {
    val zone = runCatching { ClinicalRules.zone(config) }.getOrDefault(java.time.ZoneId.systemDefault())
    val today = LocalDate.now(zone)
    var selectedText by rememberSaveable { mutableStateOf(today.toString()) }
    val selected = LocalDate.parse(selectedText)
    var month by remember { mutableStateOf(YearMonth.from(selected)) }
    var booking by remember { mutableStateOf<Slot?>(null) }
    var managing by remember { mutableStateOf(false) }
    val engine = remember(config.toString(), patients) { SlotCalendar(config, patients) }
    val monthInfo = remember(engine, month) { (1..month.lengthOfMonth()).associate { d -> val s = engine.slots(month.atDay(d)); month.atDay(d) to (s.count { it.available } to s.size) } }

    if (engine.sessions.isEmpty()) SurfaceCard(background = Amber100) {
        Text("No appointment slots yet", style = MaterialTheme.typography.titleSmall, color = Ink)
        Text(if (canManage) "Create availability for each doctor (days, hours and slot length). Patients and staff can then book free slots."
            else "Ask an administrator to create appointment availability.", style = MaterialTheme.typography.bodySmall, color = InkMuted)
    }
    if (canManage) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { managing = true }, shape = RoundedCornerShape(14.dp), modifier = Modifier.weight(1f)) { GlyphIcon(Glyph.Plus, Color.White, 16.dp); Spacer(Modifier.width(6.dp)); Text("Manage slots") }
    }
    MonthCalendar(month, selected, today, { monthInfo[it] ?: (0 to 0) }, { month = it }) { selectedText = it.toString(); month = YearMonth.from(it) }
    SectionHeader(selected.format(DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale.getDefault())))
    SlotGrid(engine.slots(selected), showPatients = true) { slot ->
        if (slot.available) booking = slot else slot.patientId?.let(onOpenPatient)
    }
    booking?.let { slot -> BookSlotDialog(slot, patients.filterNot { it.optBoolean("archived") }, { booking = null }) { patient, reason -> onBook(patient, slot, reason) } }
    if (managing) AvailabilityDialog(config, today, onClose = { managing = false }, onSave = onSaveConfig)
}

@Composable
private fun BookSlotDialog(slot: Slot, patients: List<JSONObject>, onClose: () -> Unit, onBook: (JSONObject, String) -> String?) {
    var search by remember { mutableStateOf("") }
    var chosen by remember { mutableStateOf<JSONObject?>(null) }
    var reason by remember { mutableStateOf("Check-up") }
    var error by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onClose, shape = RoundedCornerShape(28.dp),
        title = { Column { Text("Book ${slot.time}", style = MaterialTheme.typography.titleLarge); Text("${slot.date.format(DateTimeFormatter.ofPattern("EEE, d MMM yyyy"))} · ${slot.doctor} · ${slot.durationMinutes} min", style = MaterialTheme.typography.bodySmall, color = InkMuted) } },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(search, { search = it }, label = { Text("Find patient by name, phone or Patient ID") }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp))
                patients.filter { p -> search.isBlank() || "${p.optString("name")} ${p.optString("phone")} ${p.optString("id")} ${p.optJSONObject("portal")?.optString("loginId").orEmpty()}".contains(search.trim(), true) }
                    .take(6).forEach { p ->
                        val picked = chosen?.optString("id") == p.optString("id")
                        Surface(onClick = { chosen = p }, shape = RoundedCornerShape(14.dp), color = if (picked) Teal50 else SurfaceWhite, border = BorderStroke(1.dp, if (picked) Teal500 else Hairline)) {
                            Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Avatar(p.optString("name"), 32.dp)
                                Column(Modifier.weight(1f)) {
                                    Text(p.optString("name"), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(listOf(p.optString("phone"), p.optJSONObject("portal")?.optString("loginId")?.let { "ID $it" }.orEmpty()).filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = InkMuted)
                                }
                                if (picked) GlyphIcon(Glyph.Check, Teal700, 18.dp)
                            }
                        }
                    }
                OutlinedTextField(reason, { reason = it }, label = { Text("Reason / service") }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp))
                if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { Button(enabled = chosen != null, onClick = { val problem = onBook(chosen!!, reason.ifBlank { "Check-up" }); if (problem == null) onClose() else error = problem }) { Text("Book slot") } },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } })
}

// ---------------------------------------------------------------------------------------------
// Admin: availability sessions
// ---------------------------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AvailabilityDialog(config: JSONObject, today: LocalDate, onClose: () -> Unit, onSave: (JSONObject) -> Boolean) {
    val sessions = remember { mutableStateListOf<JSONObject>().apply { addAll(config.optJSONArray("availability").objectList().map { JSONObject(it.toString()) }) } }
    var patientBooking by remember { mutableStateOf(config.optBoolean("patientBooking", true)) }
    val clinicians = clinicianRecords(config).filter { it.optBoolean("active", true) }.map { it.optString("name") }.filter { it.isNotBlank() }
    var doctor by remember { mutableStateOf(clinicians.firstOrNull().orEmpty()) }
    var startDate by remember { mutableStateOf(today.toString()) }
    var endDate by remember { mutableStateOf(today.plusMonths(3).toString()) }
    val weekdays = remember { mutableStateListOf(1, 2, 3, 4, 5, 6) }
    var start by remember { mutableStateOf("09:00") }
    var end by remember { mutableStateOf("13:00") }
    var slotMinutes by remember { mutableStateOf(30) }
    var room by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onClose, shape = RoundedCornerShape(28.dp),
        title = { Text("Appointment slots") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Patients can book online", style = MaterialTheme.typography.titleSmall)
                        Text("From the patient app, using their Patient ID login", style = MaterialTheme.typography.bodySmall, color = InkMuted)
                    }
                    Switch(patientBooking, { patientBooking = it })
                }
                HorizontalDivider()
                Text("Current availability", style = MaterialTheme.typography.labelLarge)
                if (sessions.isEmpty()) Text("None yet.", style = MaterialTheme.typography.bodySmall, color = InkMuted)
                sessions.forEachIndexed { index, s ->
                    val days = s.optJSONArray("weekdays")?.let { a -> (0 until a.length()).map { DayOfWeek.of(a.optInt(it)).getDisplayName(TextStyle.SHORT, Locale.getDefault()) } }.orEmpty()
                    SurfaceCard(padding = 10.dp, background = if (s.optBoolean("active", true)) SurfaceWhite else SurfaceTint) {
                        Text("${s.optString("doctor")} · ${s.optString("start")}–${s.optString("end")} · ${s.optInt("slotMinutes")} min", style = MaterialTheme.typography.titleSmall)
                        Text("${s.optString("startDate")} → ${s.optString("endDate")} · ${if (days.isEmpty()) "every day" else days.joinToString(" ")}", style = MaterialTheme.typography.bodySmall, color = InkMuted)
                        Row {
                            TextButton(onClick = { sessions[index] = JSONObject(s.toString()).put("active", !s.optBoolean("active", true)) }) { Text(if (s.optBoolean("active", true)) "Pause" else "Resume") }
                            TextButton(onClick = { sessions.removeAt(index) }) { Text("Remove", color = Rose600) }
                        }
                    }
                }
                HorizontalDivider()
                Text("Add availability", style = MaterialTheme.typography.labelLarge)
                if (clinicians.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    clinicians.forEach { name -> FilterChip(selected = doctor == name, onClick = { doctor = name }, label = { Text(name) }) }
                }
                OutlinedTextField(doctor, { doctor = it }, label = { Text("Doctor") }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp))
                DateField(startDate, { startDate = it }, "From date", today = today)
                DateField(endDate, { endDate = it }, "Until date", today = today)
                Text("Days", style = MaterialTheme.typography.labelMedium, color = InkMuted)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    DayOfWeek.values().forEach { day ->
                        FilterChip(selected = day.value in weekdays, onClick = { if (day.value in weekdays) weekdays.remove(day.value) else weekdays.add(day.value) },
                            label = { Text(day.getDisplayName(TextStyle.SHORT, Locale.getDefault())) })
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TimeField(start, { start = it }, "Starts", Modifier.weight(1f))
                    TimeField(end, { end = it }, "Ends", Modifier.weight(1f))
                }
                Text("Slot length", style = MaterialTheme.typography.labelMedium, color = InkMuted)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(15, 20, 30, 45, 60, 90).forEach { m -> FilterChip(selected = slotMinutes == m, onClick = { slotMinutes = m }, label = { Text("$m min") }) }
                }
                OutlinedTextField(room, { room = it }, label = { Text("Room / chair (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp))
                OutlinedButton(onClick = {
                    error = runCatching {
                        require(doctor.isNotBlank()) { "Choose a doctor" }
                        require(!LocalDate.parse(endDate).isBefore(LocalDate.parse(startDate))) { "Until date must be on or after the from date" }
                        require(LocalTime.parse(end).isAfter(LocalTime.parse(start).plusMinutes(slotMinutes.toLong() - 1))) { "Hours must fit at least one slot" }
                        require(weekdays.isNotEmpty()) { "Choose at least one day" }
                        sessions.add(JSONObject().put("id", uid()).put("doctor", doctor.trim()).put("startDate", startDate).put("endDate", endDate)
                            .put("weekdays", JSONArray(weekdays.sorted())).put("start", start).put("end", end).put("slotMinutes", slotMinutes).put("active", true)
                            .apply { if (room.isNotBlank()) put("room", room.trim()) })
                    }.exceptionOrNull()?.localizedMessage.orEmpty()
                }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) { Text("+ Add to list") }
                if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                Text("Save, then Sync so patients see the new slots.", style = MaterialTheme.typography.bodySmall, color = InkMuted)
            }
        },
        confirmButton = { Button(onClick = {
            // Only close when the change was accepted; while a sync is running the save is refused.
            if (onSave(JSONObject(config.toString()).put("availability", JSONArray(sessions.toList())).put("patientBooking", patientBooking))) onClose()
            else error = "The app is busy syncing. Wait a moment, then tap Save again; your changes are kept here."
        }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } })
}

// ---------------------------------------------------------------------------------------------
// Patient: book from the portal
// ---------------------------------------------------------------------------------------------

@Composable
fun PatientBookingSection(store: ClinicStore) {
    val zone = runCatching { ClinicalRules.zone(store.portalClinic) }.getOrDefault(java.time.ZoneId.systemDefault())
    val today = LocalDate.now(zone)
    var selectedText by rememberSaveable { mutableStateOf(today.toString()) }
    val selected = LocalDate.parse(selectedText)
    var month by remember { mutableStateOf(YearMonth.from(today)) }
    var confirm by remember { mutableStateOf<Slot?>(null) }
    LaunchedEffect(month) { store.loadSlots(if (month == YearMonth.from(today)) today else month.atDay(1)) }
    val slots = remember(store.portalSlots) { slotsFromServer(store.portalSlots) }
    val byDay = remember(slots) { slots.groupBy { it.date } }
    SectionHeader("Book an appointment")
    if (!store.portalBookingEnabled) {
        SurfaceCard(background = SurfaceTint) { Text("Online booking is not available. Please call the clinic.", style = MaterialTheme.typography.bodySmall, color = InkMuted) }
        return
    }
    MonthCalendar(month, selected, today, { d -> byDay[d].orEmpty().let { s -> s.count { it.available } to s.size } }, { month = it }) { selectedText = it.toString() }
    SlotGrid(byDay[selected].orEmpty().filter { it.available || it.startAt > System.currentTimeMillis() }, showPatients = false) { slot -> if (slot.available) confirm = slot }
    confirm?.let { slot ->
        var reason by remember(slot) { mutableStateOf("Check-up") }
        var error by remember(slot) { mutableStateOf("") }
        AlertDialog(onDismissRequest = { confirm = null }, shape = RoundedCornerShape(28.dp),
            title = { Text("Confirm booking") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    InfoRow(Glyph.Calendar, slot.date.format(DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy")), "${slot.time} · ${slot.durationMinutes} min")
                    InfoRow(Glyph.Person, "Doctor", slot.doctor)
                    OutlinedTextField(reason, { reason = it }, label = { Text("Reason (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp))
                    if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { Button(enabled = !store.busy, onClick = { store.bookSlot(slot.startAt, slot.doctor, reason) { problem -> if (problem == null) confirm = null else error = problem } }) { Text("Book") } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } })
    }
}
