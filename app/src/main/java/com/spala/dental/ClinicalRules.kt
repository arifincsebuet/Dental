package com.spala.dental

import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.Currency
import java.util.Locale

/** Shared domain rules; the server repeats authoritative validation at cloud commit. */
object ClinicalRules {
    val roles = listOf("owner", "administrator", "dentist", "assistant", "receptionist", "cashier", "auditor")
    val appointmentStates = listOf("Scheduled", "Confirmed", "Checked in", "In progress", "Completed", "Cancelled", "No show")
    val treatmentStates = listOf("Planned", "Accepted", "In progress", "Completed", "Deferred", "Declined", "Cancelled", "Referred")
    val terminalAppointments = setOf("Completed", "Cancelled", "No show")
    fun defaults() = JSONObject().put("name", "Dental Care").put("currency", "BDT")
        .put("timeZone", "Asia/Dhaka").put("taxBps", 0).put("bufferMinutes", 5)
        .put("reminderHours", 24).put("remindersEnabled", false).put("quietStart", "21:00").put("quietEnd", "08:00")
        .put("staff", JSONArray()).put("services", JSONArray()).put("members", JSONObject())
    fun zone(config: JSONObject): ZoneId = ZoneId.of(config.optString("timeZone", "Asia/Dhaka"))
    fun currencyDigits(code: String): Int {
        val digits = Currency.getInstance(code.uppercase(Locale.ROOT)).defaultFractionDigits
        require(digits in 0..3) { "Unsupported currency precision" }
        return digits
    }
    fun parseMinor(value: String, currency: String = "BDT"): Long {
        require(value.trim().matches(Regex("\\d+(\\.\\d+)?"))) { "Enter a nonnegative decimal amount" }
        return BigDecimal(value.trim()).movePointRight(currencyDigits(currency)).longValueExact().also { require(it >= 0) }
    }
    fun money(minor: Long, currency: String): String = "$currency ${BigDecimal.valueOf(minor).movePointLeft(currencyDigits(currency)).toPlainString()}"
    fun invoiceTotal(invoice: JSONObject): Long {
        val lines = invoice.optJSONArray("lines")
        if (lines == null) return invoice.optLong("amount")
        var subtotal = 0L
        for (line in lines.objects()) {
            val quantity = line.optInt("quantity")
            val unit = line.optLong("unitPrice")
            require(quantity in 1..999 && unit >= 0) { "Invalid invoice quantity or price" }
            subtotal = Math.addExact(subtotal, Math.multiplyExact(unit, quantity.toLong()))
        }
        val discount = invoice.optLong("discount")
        require(discount in 0..subtotal) { "Discount cannot exceed subtotal" }
        val taxBps = invoice.optInt("taxBps")
        require(taxBps in 0..10000) { "Tax rate must be between 0 and 100 percent" }
        val net = Math.subtractExact(subtotal, discount)
        val tax = BigDecimal.valueOf(net).multiply(BigDecimal.valueOf(taxBps.toLong())).divide(BigDecimal(10000), 0, RoundingMode.HALF_UP).longValueExact()
        return Math.addExact(net, tax)
    }
    fun paid(invoice: JSONObject): Long = invoice.optJSONArray("payments")?.objects()?.fold(0L) { sum, p -> Math.addExact(sum, p.optLong("amount")) } ?: 0
    fun refunded(invoice: JSONObject): Long = invoice.optJSONArray("refunds")?.objects()?.fold(0L) { sum, p -> Math.addExact(sum, p.optLong("amount")) } ?: 0
    fun balance(invoice: JSONObject): Long = if (invoice.optString("status") == "Voided") 0 else Math.subtractExact(invoiceTotal(invoice), Math.subtractExact(paid(invoice), refunded(invoice)))
    fun appointmentMillis(appointment: JSONObject, config: JSONObject): Long =
        if (appointment.has("startAt")) appointment.getLong("startAt") else parseDateTime(appointment.optString("date"), config)
    fun parseDateTime(value: String, config: JSONObject): Long {
        require(value.matches(Regex("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}"))) { "Use date/time YYYY-MM-DD HH:mm" }
        val local = LocalDateTime.parse(value, DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm").withResolverStyle(java.time.format.ResolverStyle.STRICT))
        val offsets = zone(config).rules.getValidOffsets(local)
        require(offsets.size == 1) { "That time is ambiguous or unavailable in the clinic time zone" }
        return local.toInstant(offsets.first()).toEpochMilli()
    }
    fun displayTime(millis: Long, config: JSONObject): String = Instant.ofEpochMilli(millis).atZone(zone(config)).format(DateTimeFormatter.ofPattern("dd MMM yyyy · HH:mm"))
    fun appointmentText(millis: Long, config: JSONObject): String = Instant.ofEpochMilli(millis).atZone(zone(config)).format(DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm"))
    fun validateAppointment(candidate: JSONObject, patients: List<JSONObject>, config: JSONObject) {
        val duration = candidate.optInt("durationMinutes", 30)
        require(duration in 5..480) { "Duration must be 5–480 minutes" }
        require(candidate.optString("doctor").isNotBlank()) { "A clinician is required" }
        val start = appointmentMillis(candidate, config)
        val buffer = config.optInt("bufferMinutes", 5).coerceIn(0, 120) * 60_000L
        val end = Math.addExact(start, duration * 60_000L)
        val room = candidate.optString("room").trim()
        for (patient in patients) for (other in patient.optJSONArray("appointments")?.objects().orEmpty()) {
            if (other.optString("id") == candidate.optString("id") || other.optString("status") in terminalAppointments) continue
            val sameDoctor = other.optString("doctor").trim().equals(candidate.optString("doctor").trim(), true)
            val sameRoom = room.isNotBlank() && other.optString("room").trim().equals(room, true)
            if (!sameDoctor && !sameRoom) continue
            val otherStart = appointmentMillis(other, config)
            val otherEnd = otherStart + other.optInt("durationMinutes", 30) * 60_000L
            // Back-to-back slot bookings need no buffer: the slots already define the spacing.
            val gap = if (candidate.optBoolean("slot") && other.optBoolean("slot")) 0L else buffer
            require(!(start < otherEnd + gap && otherStart < end + gap)) {
                if (sameDoctor) "Clinician is already booked during this time (including buffer)" else "Room is already booked during this time"
            }
        }
    }
    fun validateConfig(config: JSONObject) {
        require(config.optString("name").isNotBlank()) { "Clinic name is required" }
        zone(config); currencyDigits(config.optString("currency", "BDT"))
        require(config.optInt("taxBps") in 0..10000)
        require(config.optInt("bufferMinutes", 5) in 0..120)
        require(config.optInt("reminderHours", 24) in 1..168)
        LocalTime.parse(config.optString("quietStart", "21:00")); LocalTime.parse(config.optString("quietEnd", "08:00"))
    }
    fun range(period: String, startText: String, endText: String, config: JSONObject): LongRange {
        val today = LocalDate.now(zone(config))
        val start: LocalDate
        val exclusiveEnd: LocalDate
        when (period) {
            "Day" -> { start = today; exclusiveEnd = today.plusDays(1) }
            "Week" -> { start = today.with(java.time.temporal.TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)); exclusiveEnd = start.plusWeeks(1) }
            "Month" -> { start = today.withDayOfMonth(1); exclusiveEnd = start.plusMonths(1) }
            "Custom" -> { start = LocalDate.parse(startText); exclusiveEnd = LocalDate.parse(endText).plusDays(1); require(exclusiveEnd.isAfter(start)) { "End date must not precede start date" } }
            else -> return Long.MIN_VALUE..Long.MAX_VALUE
        }
        return start.atStartOfDay(zone(config)).toInstant().toEpochMilli() until exclusiveEnd.atStartOfDay(zone(config)).toInstant().toEpochMilli()
    }
}
