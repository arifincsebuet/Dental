package com.spala.dental

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

/** Validates a local edit without inserting fields hidden by role-based projection. */
object PatientValidation {
    private const val SAFE_INTEGER = 9_007_199_254_740_991L
    private val findings = setOf("caries", "fracture", "wear", "endodontically_treated", "periapical_finding", "other")
    private val restorations = setOf("composite", "amalgam", "glass_ionomer", "sealant", "crown", "inlay", "onlay", "other")
    private val assessments = setOf("not_assessed", "assessed", "unable_to_assess")
    private val presence = setOf("unknown", "present", "unerupted", "missing", "extracted", "exfoliated", "congenitally_absent")
    private val workflow = setOf("planned", "accepted", "in_progress", "deferred", "referred")
    private val transitions = mapOf(
        "planned" to setOf("planned", "accepted", "in_progress", "completed", "deferred", "declined", "cancelled", "referred"),
        "accepted" to setOf("accepted", "in_progress", "completed", "deferred", "declined", "cancelled", "referred"),
        "in_progress" to setOf("in_progress", "completed", "deferred", "cancelled", "referred"),
        "deferred" to setOf("deferred", "planned", "accepted", "in_progress", "cancelled", "referred"),
        "referred" to setOf("referred", "planned", "accepted", "in_progress", "completed", "cancelled"),
        "declined" to setOf("declined"), "cancelled" to setOf("cancelled"), "completed" to setOf("completed")
    )
    fun validate(patient: JSONObject, previous: JSONObject?, config: JSONObject, patients: List<JSONObject>) {
        require(patient.optInt("schemaVersion") == 3) { "Unsupported patient schema" }
        require(patient.opt("id") is String) { "Patient ID must be a string" }
        identifier(patient.optString("id"), "Patient ID")
        text(patient, "name", 200, required = true)
        listOf("phone", "gender").forEach { text(patient, it, 200) }
        text(patient, "dob", 10)
        listOf("address", "allergies", "story").forEach { key -> text(patient, key, if (key == "story") 100_000 else 20_000) }
        val dob = patient.optString("dob")
        if (dob.isNotBlank()) {
            require(dob.matches(Regex("\\d{4}-\\d{2}-\\d{2}"))) { "Date of birth must be YYYY-MM-DD" }
            val birth = LocalDate.parse(dob)
            require(!birth.isAfter(LocalDate.now(ClinicalRules.zone(config)))) { "Date of birth cannot be in the future" }
        }
        if (previous != null) require(previous.optString("id") == patient.optString("id")) { "Patient identity cannot change" }
        if (patient.has("communicationConsent")) require(patient.opt("communicationConsent") is Boolean) { "Communication consent must be explicitly true or false" }
        // Never infer a hidden chart or an absent clinical fact from a profile/billing-only payload.
        if (patient.has("chart")) validateChart(requiredObject(patient, "chart"), previous?.optJSONObject("chart"), patient.optString("id"))
        if (patient.has("treatments")) validateTreatments(patient, previous)
        if (patient.has("encounters")) validateEncounters(patient, previous)
        if (patient.has("performedProcedures")) validatePerformed(patient, previous)
        if (patient.has("invoices")) validateInvoices(patient, previous)
        if (patient.has("appointments")) {
            val appointments = boundedObjects(patient, "appointments", 1000)
            retainExisting(previous?.optJSONArray("appointments"), appointments, "Appointment history")
            val old = previous?.optJSONArray("appointments")?.objects()?.associateBy { it.optString("id") }.orEmpty()
            val otherPatients = patients.filterNot { it.optString("id") == patient.optString("id") }
            appointments.forEach { appointment ->
                val state = appointment.optString("status")
                require(state in ClinicalRules.appointmentStates) { "Invalid appointment state" }
                text(appointment, "doctor", 200, true); text(appointment, "reason", 2000, true); text(appointment, "room", 200)
                val startAt = ClinicalRules.appointmentMillis(appointment, config)
                if (appointment.has("startAt")) integer(appointment, "startAt", 0, SAFE_INTEGER)
                if (appointment.has("date") && appointment.has("startAt")) require(ClinicalRules.parseDateTime(appointment.getString("date"), config) == startAt) { "Appointment date and instant disagree" }
                val duration = if (appointment.has("durationMinutes")) integer(appointment, "durationMinutes", 5, 480).toInt() else 30
                require(duration in 5..480) { "Appointment duration must be 5–480 minutes" }
                listOf("reminderEnabled", "consent").forEach { field -> if (appointment.has(field)) require(appointment.opt(field) is Boolean) { "$field must be true or false" } }
                if (appointment.optBoolean("reminderEnabled")) {
                    if (!same(appointment, old[appointment.optString("id")])) require(appointment.optBoolean("consent") && patient.optBoolean("communicationConsent")) { "Patient consent is required for reminders" }
                    require(appointment.optInt("reminderHours", config.optInt("reminderHours", 24)) in 1..168) { "Invalid reminder timing" }
                }
                if (state !in ClinicalRules.terminalAppointments && !same(appointment, old[appointment.optString("id")])) {
                    // Include this patient's other appointments as well as all other patients.
                    ClinicalRules.validateAppointment(appointment, otherPatients + patient, config)
                }
            }
        }
        if (patient.has("images")) boundedObjects(patient, "images", 500).forEach { image ->
            integer(image, "bytes", 1, 512L * 1024)
            val path = image.optString("path")
            require(path.matches(Regex("workspaces/[A-Za-z0-9_-]{1,128}/patients/${Regex.escape(patient.optString("id"))}/[a-f0-9]{64}\\.png"))) { "Image path does not belong to this patient" }
            require(image.optString("reference").startsWith("gs://") && image.optString("reference").endsWith("/$path")) { "Use a private Firebase Storage reference" }
        }
        if (patient.has("history")) {
            val history = boundedObjects(patient, "history", 2000)
            appendOnly(previous?.optJSONArray("history"), history, "Clinical history")
        }
        require(patient.toString().toByteArray(Charsets.UTF_8).size <= 650_000) { "Patient record is full; export/archive before adding more history" }
    }

    private fun validateChart(chart: JSONObject, previous: JSONObject?, patientId: String) {
        require(chart.toString().toByteArray(Charsets.UTF_8).size <= 32 * 1024) { "Chart exceeds the 32 KB current-state budget; preserve the record and review its chart data before saving" }
        require(chart.optInt("schemaVersion") == 2 && chart.optString("catalogVersion") == "fdi-v1") { "Unsupported dental chart schema or catalogue" }
        require(!chart.has("numberingSystem") || chart.optString("numberingSystem") == "FDI") { "Unsupported numbering system" }
        if (chart.has("patientId")) require(chart.optString("patientId") == patientId) { "Chart belongs to a different patient" }
        val dentition = chart.optString("dentition")
        require(dentition in setOf("permanent", "primary", "mixed")) { "Choose primary, permanent, or mixed dentition" }
        val teeth = requiredObject(chart, "teeth")
        require(teeth.length() <= if (dentition == "permanent") 32 else if (dentition == "primary") 20 else 52) { "Too many tooth slots" }
        for (key in teeth.keys()) {
            toothKey(key)
            require(dentition == "mixed" || (dentition == "permanent") == (key.first() in '1'..'4')) { "Recorded teeth require mixed dentition to retain both sets" }
            val tooth = requiredObject(teeth, key)
            require(tooth.optString("assessment") in assessments) { "Invalid tooth assessment for $key" }
            require(tooth.optString("presence") in presence) { "Unknown or missing tooth presence for $key" }
            if (tooth.has("findings")) codes(tooth, "findings", findings)
            if (tooth.has("treatmentStates")) codes(tooth, "treatmentStates", workflow)
            if (tooth.has("replacement")) require(tooth.optString("replacement") in setOf("none", "implant", "pontic", "removable_prosthesis")) { "Invalid prosthetic replacement" }
            if (tooth.has("surfaces")) {
                val surfaces = requiredObject(tooth, "surfaces")
                require(surfaces.length() in 1..5) { "Surface map must contain 1–5 recorded surfaces" }
                for (surface in surfaces.keys()) {
                    validSurface(key, surface)
                    val observation = requiredObject(surfaces, surface)
                    require(observation.optString("assessment") in assessments) { "Invalid surface assessment" }
                    codes(observation, "findings", findings); codes(observation, "restorations", restorations)
                }
            }
        }
        previous?.optJSONObject("teeth")?.keys()?.forEach { key -> require(teeth.has(key)) { "Do not delete recorded tooth $key; record explicit presence or retain an amendment" } }
        rejectNulls(chart)
    }
    private fun validateTreatments(patient: JSONObject, previous: JSONObject?) {
        val treatments = boundedObjects(patient, "treatments", 1000)
        retainExisting(previous?.optJSONArray("treatments"), treatments, "Treatment history")
        val old = previous?.optJSONArray("treatments")?.objects()?.associateBy { it.optString("id") }.orEmpty()
        treatments.forEach { item ->
            val tooth = item.optString("tooth"); toothKey(tooth)
            if (patient.has("chart")) require(patient.getJSONObject("chart").getJSONObject("teeth").has(tooth)) { "Treatment must target a recorded chart slot" }
            if (item.has("surface") && item.optString("surface") !in setOf("", "Whole tooth")) validSurface(tooth, item.optString("surface"))
            text(item, "procedure", 500, true)
            val state = normalized(item.optString("status"))
            require(state in transitions.keys) { "Invalid treatment state" }
            old[item.optString("id")]?.let { original ->
                require(state in transitions[normalized(original.optString("status"))].orEmpty()) { "Invalid treatment transition" }
                appendOnly(original.optJSONArray("events"), optionalObjects(item, "events", 1000), "Treatment events")
                appendOnly(original.optJSONArray("amendments"), optionalObjects(item, "amendments", 1000), "Treatment amendments")
                if (normalized(original.optString("status")) == "completed") require(same(without(original, "events", "amendments", "amendmentReason"), without(item, "events", "amendments", "amendmentReason"))) { "Completed treatment base fields cannot be overwritten; append a correction" }
            }
        }
    }
    private fun validateEncounters(patient: JSONObject, previous: JSONObject?) {
        val encounters = boundedObjects(patient, "encounters", 1000)
        retainExisting(previous?.optJSONArray("encounters"), encounters, "Encounter history")
        val old = previous?.optJSONArray("encounters")?.objects()?.associateBy { it.optString("id") }.orEmpty()
        encounters.forEach { item ->
            require(item.optString("status") in setOf("Draft", "Finalized")) { "Invalid encounter state" }
            text(item, "doctor", 200, true); text(item, "complaint", 20_000, true)
            listOf("examination", "diagnosis", "plan").forEach { text(item, it, 50_000) }
            amendments(item)
            old[item.optString("id")]?.let { original ->
                appendOnly(original.optJSONArray("amendments"), optionalObjects(item, "amendments", 1000), "Encounter amendments")
                if (original.optString("status") == "Finalized") require(same(without(original, "amendments"), without(item, "amendments"))) { "Finalized notes cannot be rewritten; append an amendment" }
            }
        }
    }
    private fun validatePerformed(patient: JSONObject, previous: JSONObject?) {
        val performed = boundedObjects(patient, "performedProcedures", 1000)
        retainExisting(previous?.optJSONArray("performedProcedures"), performed, "Performed care")
        val old = previous?.optJSONArray("performedProcedures")?.objects()?.associateBy { it.optString("id") }.orEmpty()
        val treatments = patient.optJSONArray("treatments")?.objects()?.associateBy { it.optString("id") }.orEmpty()
        performed.forEach { item ->
            toothKey(item.optString("tooth")); text(item, "doctor", 200, true); text(item, "outcome", 50_000, true)
            val treatment = treatments[item.optString("treatmentId")]
            require(treatment != null && normalized(treatment.optString("status")) == "completed" && treatment.optString("tooth") == item.optString("tooth")) { "Performed procedure must reference a completed treatment for the same tooth" }
            amendments(item)
            old[item.optString("id")]?.let { original ->
                require(same(without(original, "amendments"), without(item, "amendments"))) { "Performed care cannot be removed or rewritten; append an amendment" }
                appendOnly(original.optJSONArray("amendments"), optionalObjects(item, "amendments", 1000), "Performed-procedure amendments")
            }
        }
    }
    private fun validateInvoices(patient: JSONObject, previous: JSONObject?) {
        val invoices = boundedObjects(patient, "invoices", 500)
        retainExisting(previous?.optJSONArray("invoices"), invoices, "Invoice history")
        val old = previous?.optJSONArray("invoices")?.objects()?.associateBy { it.optString("id") }.orEmpty()
        invoices.forEach { invoice ->
            val currency = invoice.optString("currency"); require(currency.matches(Regex("[A-Z]{3}"))) { "Invalid invoice currency" }; ClinicalRules.currencyDigits(currency)
            val amount = integer(invoice, "amount", 0, SAFE_INTEGER)
            if (invoice.has("lines")) {
                val lines = boundedObjects(invoice, "lines", 100); require(lines.isNotEmpty()) { "An invoice needs at least one line" }
                lines.forEach { line -> integer(line, "unitPrice", 0, SAFE_INTEGER); integer(line, "quantity", 1, 999); text(line, "description", 1000, true) }
                if (invoice.has("discount")) integer(invoice, "discount", 0, SAFE_INTEGER)
                if (invoice.has("taxBps")) integer(invoice, "taxBps", 0, 10000)
                require(amount == ClinicalRules.invoiceTotal(invoice)) { "Invoice total differs from its lines, discount, and tax" }
            }
            val payments = optionalObjects(invoice, "payments", 1000); val refunds = optionalObjects(invoice, "refunds", 1000)
            payments.forEach { integer(it, "amount", 1, SAFE_INTEGER); text(it, "method", 128, true) }
            refunds.forEach { refund ->
                integer(refund, "amount", 1, SAFE_INTEGER); text(refund, "reason", 2000, true)
                require(payments.any { it.optString("id") == refund.optString("paymentId") }) { "Refund must reference its original payment" }
            }
            val paid = ClinicalRules.paid(invoice); val refunded = ClinicalRules.refunded(invoice)
            require(refunded <= paid && paid - refunded <= amount) { "Payment or refund totals exceed the invoice" }
            payments.forEach { payment ->
                val refundedReceipt = refunds.filter { it.optString("paymentId") == payment.optString("id") }.fold(0L) { sum, r -> Math.addExact(sum, r.optLong("amount")) }
                require(refundedReceipt <= payment.optLong("amount")) { "Refund exceeds the original receipt" }
            }
            if (normalized(invoice.optString("status")) in setOf("void", "voided")) require(paid == refunded && invoice.optString("voidReason").isNotBlank()) { "Voiding requires a reason and all payments refunded" }
            old[invoice.optString("id")]?.let { original ->
                listOf("amount", "currency", "description", "lines", "discount", "taxBps").forEach { field -> require(same(original.opt(field), invoice.opt(field))) { "Issued invoice prices and lines cannot be rewritten" } }
                appendOnly(original.optJSONArray("payments"), payments, "Payments"); appendOnly(original.optJSONArray("refunds"), refunds, "Refunds")
                if (normalized(original.optString("status")) in setOf("void", "voided")) require(same(original, invoice)) { "Voided invoices are immutable" }
            }
        }
    }

    private fun amendments(record: JSONObject) = optionalObjects(record, "amendments", 1000).forEach { text(it, "reason", 2000, true); text(it, "text", 50_000, true) }
    private fun boundedObjects(parent: JSONObject, key: String, max: Int): List<JSONObject> {
        val array = parent.optJSONArray(key) ?: throw IllegalArgumentException("$key must be an array")
        require(array.length() <= max) { "$key has too many records" }
        val result = (0 until array.length()).map { index -> array.optJSONObject(index) ?: throw IllegalArgumentException("$key entries must be objects") }
        if (key != "images") {
            val ids = result.map { require(it.opt("id") is String) { "$key ID must be a string" }; it.optString("id").also { id -> identifier(id, "$key ID") } }
            require(ids.distinct().size == ids.size) { "Duplicate record IDs in $key" }
        } else require(result.map { it.optString("path") }.distinct().size == result.size) { "Duplicate images" }
        return result
    }
    private fun optionalObjects(parent: JSONObject, key: String, max: Int): List<JSONObject> = if (parent.has(key)) boundedObjects(parent, key, max) else emptyList()
    private fun requiredObject(parent: JSONObject, key: String): JSONObject = parent.optJSONObject(key) ?: throw IllegalArgumentException("$key must be an object")
    private fun retainExisting(old: JSONArray?, items: List<JSONObject>, name: String) { old?.objects()?.forEach { original -> require(items.any { it.optString("id") == original.optString("id") }) { "$name cannot be deleted" } } }
    private fun appendOnly(old: JSONArray?, items: List<JSONObject>, name: String) { old?.objects()?.forEach { original -> require(items.any { it.optString("id") == original.optString("id") && same(original, it) }) { "$name cannot be removed or rewritten" } } }
    private fun text(parent: JSONObject, key: String, max: Int, required: Boolean = false) {
        if (!parent.has(key) && !required) return
        val value = parent.opt(key); require(value is String && value.length <= max && (!required || value.isNotBlank())) { "Invalid $key" }
    }
    private fun integer(parent: JSONObject, key: String, min: Long, max: Long): Long {
        val value = parent.opt(key)
        require(value is Number && value.toDouble().isFinite() && value.toDouble() == value.toLong().toDouble() && value.toLong() in min..max) { "$key must be a bounded integer" }
        return value.toLong()
    }
    private fun identifier(value: String, label: String) { require(value.matches(Regex("[A-Za-z0-9_-]{1,128}"))) { "Invalid $label" } }
    private fun toothKey(key: String) { require(key.matches(Regex("[1-4][1-8]|[5-8][1-5]"))) { "Unsupported FDI tooth $key" } }
    private fun validSurface(tooth: String, surface: String) { require(surface in setOf("M", "D", "F", "L", if (tooth.last().digitToInt() <= 3) "I" else "O")) { "Invalid surface $surface for tooth $tooth" } }
    private fun codes(parent: JSONObject, field: String, allowed: Set<String>) {
        val array = parent.optJSONArray(field) ?: throw IllegalArgumentException("$field must be a recorded code array")
        require(array.length() <= allowed.size) { "Too many $field codes" }
        val values = (0 until array.length()).map { array.opt(it) }
        require(values.all { it is String && it in allowed } && values.distinct().size == values.size) { "Invalid or duplicate $field codes" }
    }
    private fun rejectNulls(value: Any?) {
        require(value != null && value !== JSONObject.NULL) { "A chart must use explicit unknown or omitted optional fields, not null" }
        when (value) { is JSONObject -> value.keys().forEach { rejectNulls(value.get(it)) }; is JSONArray -> (0 until value.length()).forEach { rejectNulls(value.get(it)) } }
    }
    private fun normalized(value: String) = value.lowercase().replace(Regex("[ -]+"), "_")
    private fun without(value: JSONObject, vararg keys: String) = JSONObject(value.toString()).apply { keys.forEach { remove(it) } }
    private fun same(a: Any?, b: Any?) = canonical(a) == canonical(b)
    private fun canonical(value: Any?): String = when (value) {
        null, JSONObject.NULL -> "null"
        is JSONObject -> value.keys().asSequence().sorted().joinToString(prefix = "{", postfix = "}") { JSONObject.quote(it) + ":" + canonical(value.get(it)) }
        is JSONArray -> (0 until value.length()).joinToString(prefix = "[", postfix = "]") { canonical(value.get(it)) }
        is String -> JSONObject.quote(value)
        is Number -> JSONObject.numberToString(value)
        is Boolean -> value.toString()
        else -> JSONObject.quote(value.toString())
    }
}
