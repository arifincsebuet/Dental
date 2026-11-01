package com.spala.dental

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.spala.dental.ui.theme.InkMuted
import com.spala.dental.ui.theme.Teal700
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/*
 * Calendar pickers for every date input. Values keep their stored text formats
 * ("YYYY-MM-DD", "YYYY-MM-DD HH:mm", "HH:mm"), so validation and cloud sync are unchanged;
 * only the way people enter them changes. The field shows a readable date and opens a
 * Material calendar (plus a clock for times) when tapped.
 */

private val isoDate = DateTimeFormatter.ISO_LOCAL_DATE
private val isoDateTime = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
private val friendlyDate = DateTimeFormatter.ofPattern("EEE, d MMM yyyy", Locale.getDefault())
private val friendlyTime = DateTimeFormatter.ofPattern("HH:mm")

/** Which calendar days may be picked. */
enum class DateLimit { ANY, PAST_OR_TODAY, TODAY_OR_FUTURE }

private fun utcMillis(date: LocalDate) = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
private fun fromUtcMillis(millis: Long) = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()

@OptIn(ExperimentalMaterial3Api::class)
private class LimitedDates(private val limit: DateLimit, private val today: LocalDate) : SelectableDates {
    override fun isSelectableDate(utcTimeMillis: Long): Boolean {
        val day = fromUtcMillis(utcTimeMillis)
        return when (limit) {
            DateLimit.ANY -> true
            DateLimit.PAST_OR_TODAY -> !day.isAfter(today)
            DateLimit.TODAY_OR_FUTURE -> !day.isBefore(today)
        }
    }
    override fun isSelectableYear(year: Int): Boolean = when (limit) {
        DateLimit.ANY -> true
        DateLimit.PAST_OR_TODAY -> year <= today.year
        DateLimit.TODAY_OR_FUTURE -> year >= today.year
    }
}

/** Read-only text field that opens a picker when tapped anywhere on it. */
@Composable
private fun PickerField(display: String, label: String, placeholder: String, modifier: Modifier, onClear: (() -> Unit)?, onOpen: () -> Unit) {
    Box(modifier) {
        OutlinedTextField(
            value = display, onValueChange = {}, readOnly = true, singleLine = true,
            label = { Text(label) }, placeholder = { Text(placeholder) },
            leadingIcon = { GlyphIcon(Glyph.Calendar, Teal700, 20.dp) },
            trailingIcon = if (onClear == null) null else { { TextButton(onClick = onClear) { Text("Clear") } } },
            shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth(),
        )
        // The overlay receives the tap (a read-only text field would otherwise swallow it).
        Box(Modifier.matchParentSize().padding(end = if (onClear != null) 72.dp else 0.dp)
            .clickable(role = Role.Button, onClickLabel = "Open calendar", onClick = onOpen)
            .semantics { contentDescription = "$label, ${display.ifBlank { "not set" }}. Double-tap to choose." })
    }
}

/**
 * Date input with a calendar. [value] and the result use "YYYY-MM-DD"; blank means not set.
 * Optional fields show a Clear button.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateField(
    value: String, onValueChange: (String) -> Unit, label: String,
    modifier: Modifier = Modifier.fillMaxWidth(), limit: DateLimit = DateLimit.ANY, optional: Boolean = false, today: LocalDate = LocalDate.now(),
) {
    var open by remember { mutableStateOf(false) }
    val parsed = runCatching { LocalDate.parse(value.trim(), isoDate) }.getOrNull()
    PickerField(parsed?.format(friendlyDate) ?: value, label, "Tap to choose a date", modifier,
        onClear = if (optional && value.isNotBlank()) ({ onValueChange("") }) else null) { open = true }
    if (open) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = parsed?.let(::utcMillis) ?: if (limit == DateLimit.PAST_OR_TODAY && label.contains("birth", true)) null else utcMillis(today),
            initialDisplayedMonthMillis = (parsed ?: today).let(::utcMillis),
            yearRange = 1900..(today.year + 5),
            selectableDates = LimitedDates(limit, today),
        )
        DatePickerDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TextButton(enabled = state.selectedDateMillis != null, onClick = {
                    state.selectedDateMillis?.let { onValueChange(fromUtcMillis(it).format(isoDate)) }
                    open = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { open = false }) { Text("Cancel") } },
        ) { DatePicker(state, title = { Text(label, Modifier.padding(start = 24.dp, top = 16.dp), color = InkMuted) }) }
    }
}

/** Time input with a clock dial. Uses "HH:mm" (24-hour storage; display follows the device setting). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimeField(value: String, onValueChange: (String) -> Unit, label: String, modifier: Modifier = Modifier.fillMaxWidth()) {
    var open by remember { mutableStateOf(false) }
    val parsed = runCatching { LocalTime.parse(value.trim(), friendlyTime) }.getOrNull()
    PickerField(parsed?.format(friendlyTime) ?: value, label, "Tap to choose a time", modifier, null) { open = true }
    if (open) TimeDialog(parsed ?: LocalTime.of(9, 0), label, onDismiss = { open = false }) { onValueChange(it.format(friendlyTime)); open = false }
}

/**
 * Appointment date + time: calendar first, then the clock. Uses "YYYY-MM-DD HH:mm" in clinic time.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateTimeField(value: String, onValueChange: (String) -> Unit, label: String, modifier: Modifier = Modifier.fillMaxWidth(), today: LocalDate = LocalDate.now()) {
    var step by remember { mutableStateOf(0) } // 0 closed, 1 date, 2 time
    var pickedDate by remember { mutableStateOf<LocalDate?>(null) }
    val parsed = runCatching { LocalDateTime.parse(value.trim(), isoDateTime) }.getOrNull()
    PickerField(parsed?.let { "${it.toLocalDate().format(friendlyDate)} · ${it.toLocalTime().format(friendlyTime)}" } ?: value,
        label, "Tap to choose date and time", modifier, null) { step = 1 }
    if (step == 1) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = utcMillis(parsed?.toLocalDate() ?: today), yearRange = (today.year - 5)..(today.year + 5),
        )
        DatePickerDialog(
            onDismissRequest = { step = 0 },
            confirmButton = { TextButton(enabled = state.selectedDateMillis != null, onClick = { pickedDate = state.selectedDateMillis?.let(::fromUtcMillis); step = 2 }) { Text("Next") } },
            dismissButton = { TextButton(onClick = { step = 0 }) { Text("Cancel") } },
        ) { DatePicker(state, title = { Text(label, Modifier.padding(start = 24.dp, top = 16.dp), color = InkMuted) }) }
    }
    if (step == 2) TimeDialog(parsed?.toLocalTime() ?: LocalTime.of(9, 0), label, onDismiss = { step = 0 }) { time ->
        pickedDate?.let { onValueChange(LocalDateTime.of(it, time).format(isoDateTime)) }
        step = 0
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeDialog(initial: LocalTime, label: String, onDismiss: () -> Unit, onPick: (LocalTime) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val state = rememberTimePickerState(initialHour = initial.hour, initialMinute = initial.minute, is24Hour = android.text.format.DateFormat.is24HourFormat(context))
    AlertDialog(
        onDismissRequest = onDismiss, shape = RoundedCornerShape(28.dp),
        title = { Text(label) },
        text = { Box(Modifier.fillMaxWidth(), contentAlignment = androidx.compose.ui.Alignment.Center) { TimePicker(state) } },
        confirmButton = { TextButton(onClick = { onPick(LocalTime.of(state.hour, state.minute)) }) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
