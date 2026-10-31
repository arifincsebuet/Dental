package com.spala.dental

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalTime
import java.util.Locale

private val FeatureTeal = Color(0xFF0F766E)
private data class FeatureField(val key: String, val label: String, val value: String = "", val required: Boolean = true)
private data class FeatureForm(val title: String, val fields: List<FeatureField>, val save: (Map<String, String>) -> Unit)
private fun copyJson(value: JSONObject) = JSONObject(value.toString())
private fun records(value: JSONObject, key: String) = value.optJSONArray(key)?.objects().orEmpty()
private fun membershipRecords(config: JSONObject): List<JSONObject> {
    val members = config.optJSONObject("members") ?: return records(config, "members")
    return members.keys().asSequence().map { uid -> copyJson(members.getJSONObject(uid)).put("uid", uid) }.toList()
}
private fun membershipMap(members: List<JSONObject>): JSONObject = JSONObject().also { map ->
    members.forEach { entry -> map.put(entry.getString("uid"), copyJson(entry).apply { remove("uid") }) }
}
private fun currentCurrency(config: JSONObject) = config.optString("currency", "BDT")
private fun shareText(context: Context, title: String, text: String, type: String = "text/plain") {
    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType(type).putExtra(Intent.EXTRA_SUBJECT, title).putExtra(Intent.EXTRA_TEXT, text), title))
}

@Composable private fun FeaturePanel(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold, color = Color(0xFF1E293B))
            content()
        }
    }
}
@Composable private fun FeatureHeading(title: String, subtitle: String) {
    Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
    Text(subtitle, color = Color(0xFF64748B), style = MaterialTheme.typography.bodyMedium)
}
@Composable private fun FeatureChoices(values: List<String>, value: String, change: (String) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        values.forEach { item -> FilterChip(selected = value == item, onClick = { change(item) }, label = { Text(item) }) }
    }
}
@Composable private fun FeatureDialog(form: FeatureForm?, dismiss: () -> Unit) {
    val saveStatus = LocalEditorSaveStatus.current
    form?.let { active -> key(active) {
        val values = remember { mutableStateMapOf<String, String>().apply { active.fields.forEach { put(it.key, it.value) } } }
        var error by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = dismiss, title = { Text(active.title) }, text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                active.fields.forEach { field -> OutlinedTextField(value = values[field.key].orEmpty(), onValueChange = { values[field.key] = it }, label = { Text(field.label) }, modifier = Modifier.fillMaxWidth()) }
                if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
            }
        }, confirmButton = { TextButton(onClick = {
            runCatching {
                require(active.fields.none { it.required && values[it.key].isNullOrBlank() }) { "Complete the required fields" }
                val beforeSave = saveStatus(); active.save(values.mapValues { it.value.trim() }); requireAcceptedSave(beforeSave, saveStatus())
            }.onSuccess { dismiss() }.onFailure { error = it.localizedMessage ?: "Review the values" }
        }) { Text("Save") } }, dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } })
    } }
}

@Composable
fun ClinicManagementScreen(config: JSONObject, onSave: (JSONObject) -> Unit) {
    var section by remember { mutableStateOf("Clinic") }
    var form by remember { mutableStateOf<FeatureForm?>(null) }
    FeatureHeading("Clinic management", "Configure your workspace, team, services, and reminders")
    FeatureChoices(listOf("Clinic", "Staff", "Services", "Roles", "Reminders"), section) { section = it }
    when (section) {
        "Clinic" -> {
            FeaturePanel(config.optString("name", "Dental Care")) {
                Text("${config.optString("timeZone", "Asia/Dhaka")} · ${currentCurrency(config)}")
                Text("Appointment buffer ${config.optInt("bufferMinutes", 5)} minutes · default tax ${BigDecimal(config.optInt("taxBps")).movePointLeft(2)}%")
                Button(onClick = { form = FeatureForm("Clinic settings", listOf(
                    FeatureField("name", "Clinic name", config.optString("name", "Dental Care")),
                    FeatureField("timeZone", "Time zone (IANA)", config.optString("timeZone", "Asia/Dhaka")),
                    FeatureField("currency", "Currency code", currentCurrency(config)),
                    FeatureField("tax", "Default tax percent", BigDecimal(config.optInt("taxBps")).movePointLeft(2).toPlainString()),
                    FeatureField("buffer", "Appointment buffer minutes", config.optInt("bufferMinutes", 5).toString())
                )) { v ->
                    val changed = copyJson(config).put("name", v.getValue("name")).put("timeZone", v.getValue("timeZone"))
                        .put("currency", v.getValue("currency").uppercase(Locale.ROOT))
                        .put("taxBps", v.getValue("tax").toBigDecimal().movePointRight(2).intValueExact())
                        .put("bufferMinutes", v.getValue("buffer").toInt())
                    ClinicalRules.validateConfig(changed); onSave(changed)
                } }) { Text("Edit clinic settings") }
            }
        }
        "Staff" -> {
            Text("Staff directory is separate from cloud account membership.", color = Color(0xFF64748B))
            fun editStaff(staff: JSONObject?) {
                form = FeatureForm(if (staff == null) "Add staff member" else "Edit staff member", listOf(
                    FeatureField("name", "Full name", staff?.optString("name").orEmpty()),
                    FeatureField("role", "Role: ${ClinicalRules.roles.joinToString()}", staff?.optString("role") ?: "dentist"),
                    FeatureField("email", "Email", staff?.optString("email").orEmpty(), false),
                    FeatureField("phone", "Phone", staff?.optString("phone").orEmpty(), false),
                    FeatureField("registration", "Professional registration", staff?.optString("registration").orEmpty(), false),
                    FeatureField("hours", "Working hours / availability", staff?.optString("hours").orEmpty(), false)
                )) { v ->
                    require(v.getValue("role") in ClinicalRules.roles) { "Choose a supported role" }
                    val changed = copyJson(config)
                    val people = records(changed, "staff").toMutableList()
                    val entry = JSONObject(v).put("id", staff?.optString("id") ?: uid()).put("active", staff?.optBoolean("active", true) ?: true)
                    val index = people.indexOfFirst { it.optString("id") == entry.optString("id") }
                    if (index == -1) people.add(entry) else people[index] = entry
                    changed.put("staff", JSONArray(people)); onSave(changed)
                }
            }
            Button(onClick = { editStaff(null) }) { Text("+ Add staff member") }
            records(config, "staff").forEach { staff -> FeaturePanel(staff.optString("name")) {
                Text("${staff.optString("role")} · ${if (staff.optBoolean("active", true)) "Active" else "Inactive"}")
                Text(listOf(staff.optString("email"), staff.optString("phone"), staff.optString("registration"), staff.optString("hours")).filter { it.isNotBlank() }.joinToString("\n"))
                Row { TextButton(onClick = { editStaff(staff) }) { Text("Edit") }; TextButton(onClick = {
                    val changed = copyJson(config); records(changed, "staff").first { it.optString("id") == staff.optString("id") }.put("active", !staff.optBoolean("active", true)); onSave(changed)
                }) { Text(if (staff.optBoolean("active", true)) "Deactivate" else "Activate") } }
            } }
        }
        "Services" -> {
            val currency = currentCurrency(config)
            fun editService(service: JSONObject?) {
                form = FeatureForm(if (service == null) "Add service" else "Edit service", listOf(
                    FeatureField("name", "Service name", service?.optString("name").orEmpty()),
                    FeatureField("code", "Procedure code", service?.optString("code").orEmpty()),
                    FeatureField("fee", "Default fee ($currency)", BigDecimal.valueOf(service?.optLong("fee") ?: 0).movePointLeft(ClinicalRules.currencyDigits(currency)).toPlainString()),
                    FeatureField("duration", "Duration minutes", (service?.optInt("durationMinutes", 30) ?: 30).toString())
                )) { v ->
                    val duration = v.getValue("duration").toInt(); require(duration in 5..480) { "Duration must be 5–480 minutes" }
                    require(v.getValue("code").matches(Regex("[A-Za-z0-9_-]{1,64}"))) { "Use letters, digits, underscores, or hyphens for procedure codes" }
                    val changed = copyJson(config); val services = records(changed, "services").toMutableList()
                    require(services.none { it.optString("code").equals(v.getValue("code"), true) && it.optString("id") != service?.optString("id") }) { "Procedure code already exists" }
                    val entry = JSONObject().put("id", service?.optString("id") ?: uid()).put("name", v.getValue("name")).put("code", v.getValue("code"))
                        .put("fee", ClinicalRules.parseMinor(v.getValue("fee"), currency)).put("durationMinutes", duration).put("active", service?.optBoolean("active", true) ?: true)
                    val index = services.indexOfFirst { it.optString("id") == entry.optString("id") }; if (index == -1) services.add(entry) else services[index] = entry
                    changed.put("services", JSONArray(services)); onSave(changed)
                }
            }
            Button(onClick = { editService(null) }) { Text("+ Add service") }
            records(config, "services").forEach { service -> FeaturePanel(service.optString("name")) {
                Text("${service.optString("code")} · ${ClinicalRules.money(service.optLong("fee"), currency)} · ${service.optInt("durationMinutes", 30)} min")
                Text(if (service.optBoolean("active", true)) "Available for new care" else "Inactive; historical prices retained")
                Row { TextButton(onClick = { editService(service) }) { Text("Edit") }; TextButton(onClick = {
                    val changed = copyJson(config); records(changed, "services").first { it.optString("id") == service.optString("id") }.put("active", !service.optBoolean("active", true)); onSave(changed)
                }) { Text(if (service.optBoolean("active", true)) "Deactivate" else "Activate") } }
            } }
        }
        "Roles" -> {
            Text("Cloud membership is enforced by the backend. Enter the Firebase UID of the staff account.")
            Button(onClick = { form = FeatureForm("Assign cloud membership", listOf(FeatureField("uid", "Firebase authentication UID"), FeatureField("role", "Role: ${ClinicalRules.roles.joinToString()}", "dentist"))) { v ->
                require(v.getValue("role") in ClinicalRules.roles) { "Choose a supported role" }
                val changed = copyJson(config); val members = membershipRecords(changed).toMutableList()
                val entry = JSONObject().put("uid", v.getValue("uid")).put("role", v.getValue("role")).put("active", true)
                val index = members.indexOfFirst { it.optString("uid") == entry.optString("uid") }; if (index == -1) members.add(entry) else members[index] = entry
                changed.put("members", membershipMap(members)); onSave(changed)
            } }) { Text("+ Assign member") }
            membershipRecords(config).forEach { member -> FeaturePanel(member.optString("uid")) {
                Text("${member.optString("role")} · ${if (member.optBoolean("active", true)) "Active" else "Disabled"}")
                TextButton(onClick = { val changed = copyJson(config); val members = membershipRecords(changed); members.first { it.optString("uid") == member.optString("uid") }.put("active", !member.optBoolean("active", true)); changed.put("members", membershipMap(members)); onSave(changed) }) { Text(if (member.optBoolean("active", true)) "Disable access" else "Enable access") }
            } }
        }
        "Reminders" -> FeaturePanel("Appointment reminders") {
            Text("Patient consent and appointment reminder switches control eligibility. Cloud delivery needs a configured notification destination.")
            Row(verticalAlignment = Alignment.CenterVertically) { Switch(checked = config.optBoolean("remindersEnabled"), onCheckedChange = { onSave(copyJson(config).put("remindersEnabled", it)) }); Text("Enable clinic reminders", Modifier.padding(start = 10.dp)) }
            Text("${config.optInt("reminderHours", 24)} hours before · quiet hours ${config.optString("quietStart", "21:00")}–${config.optString("quietEnd", "08:00")}")
            OutlinedButton(onClick = { form = FeatureForm("Reminder timing", listOf(FeatureField("hours", "Hours before appointment", config.optInt("reminderHours", 24).toString()), FeatureField("start", "Quiet hours start HH:mm", config.optString("quietStart", "21:00")), FeatureField("end", "Quiet hours end HH:mm", config.optString("quietEnd", "08:00")))) { v ->
                val hours = v.getValue("hours").toInt(); require(hours in 1..168); LocalTime.parse(v.getValue("start")); LocalTime.parse(v.getValue("end"))
                onSave(copyJson(config).put("reminderHours", hours).put("quietStart", v.getValue("start")).put("quietEnd", v.getValue("end")))
            } }) { Text("Configure timing") }
        }
    }
    FeatureDialog(form) { form = null }
}

@Composable
fun BillingScreen(patient: JSONObject, config: JSONObject, onChange: (JSONObject, String) -> Unit) {
    val saveStatus = LocalEditorSaveStatus.current
    val context = LocalContext.current
    var creating by remember { mutableStateOf(false) }
    var form by remember { mutableStateOf<FeatureForm?>(null) }
    Text("Billing and receipts", fontWeight = FontWeight.Bold, fontSize = 20.sp)
    Text("Issued prices are retained. Refunds and voids preserve their history.", color = Color(0xFF64748B))
    Button(onClick = { creating = true }) { Text("+ Create invoice") }
    records(patient, "invoices").asReversed().forEach { invoice ->
        val currency = invoice.optString("currency", currentCurrency(config))
        val total = ClinicalRules.invoiceTotal(invoice); val paid = ClinicalRules.paid(invoice); val refunds = ClinicalRules.refunded(invoice)
        val remaining = ClinicalRules.balance(invoice); val voided = invoice.optString("status") == "Voided"
        FeaturePanel(invoice.optString("description", "Invoice")) {
            Text("#${invoice.optString("id").take(8).uppercase()} · ${if (voided) "Voided" else if (remaining == 0L) "Paid" else if (paid > refunds) "Part paid" else "Unpaid"}", color = FeatureTeal)
            records(invoice, "lines").forEach { line -> Text("${line.optInt("quantity")} × ${line.optString("description")} · ${ClinicalRules.money(line.optLong("unitPrice"), currency)}") }
            if (invoice.has("lines")) Text("Discount ${ClinicalRules.money(invoice.optLong("discount"), currency)} · tax ${BigDecimal(invoice.optInt("taxBps")).movePointLeft(2)}%")
            Text("Total ${ClinicalRules.money(total, currency)}\nReceived ${ClinicalRules.money(paid, currency)} · refunded ${ClinicalRules.money(refunds, currency)}\nOutstanding ${ClinicalRules.money(remaining, currency)}", fontWeight = FontWeight.SemiBold)
            if (voided) Text("Reason: ${invoice.optString("voidReason")}")
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                TextButton(enabled = !voided && remaining > 0, onClick = { form = FeatureForm("Receive payment", listOf(FeatureField("amount", "Amount ($currency)"), FeatureField("method", "Payment method", "Cash"), FeatureField("reference", "Reference (optional)", required = false))) { v ->
                    val amount = ClinicalRules.parseMinor(v.getValue("amount"), currency); require(amount in 1..remaining) { "Payment must be positive and within the outstanding balance" }
                    val changed = copyJson(patient); val target = records(changed, "invoices").first { it.optString("id") == invoice.optString("id") }
                    target.array("payments").put(JSONObject().put("id", uid()).put("amount", amount).put("method", v.getValue("method")).put("reference", v.getValue("reference")).put("at", System.currentTimeMillis()))
                    onChange(changed, "Payment received for invoice ${invoice.optString("id")}: ${ClinicalRules.money(amount, currency)}")
                } }) { Text("Receive payment") }
                TextButton(enabled = !voided && paid == refunds, onClick = { form = FeatureForm("Void invoice", listOf(FeatureField("reason", "Required reason"))) { v ->
                    val changed = copyJson(patient); records(changed, "invoices").first { it.optString("id") == invoice.optString("id") }.put("status", "Voided").put("voidReason", v.getValue("reason")).put("voidedAt", System.currentTimeMillis())
                    onChange(changed, "Invoice voided: ${v.getValue("reason")}")
                } }) { Text("Void") }
                TextButton(onClick = { shareText(context, "Invoice", invoiceText(patient, invoice, config)) }) { Text("Share invoice") }
            }
            records(invoice, "payments").forEach { payment ->
                val alreadyRefunded = records(invoice, "refunds").filter { it.optString("paymentId") == payment.optString("id") }.sumOf { it.optLong("amount") }
                Text("Receipt ${payment.optString("id").take(8)} · ${ClinicalRules.money(payment.optLong("amount"), currency)} · ${payment.optString("method")}", fontSize = 13.sp)
                Row {
                    TextButton(onClick = { shareText(context, "Payment receipt", "${config.optString("name", "Dental Care")}\nPAYMENT RECEIPT\nPatient: ${patient.optString("name")}\nInvoice: ${invoice.optString("id")}\nReceipt: ${payment.optString("id")}\n${ClinicalRules.displayTime(payment.optLong("at"), config)}\nReceived: ${ClinicalRules.money(payment.optLong("amount"), currency)}\nMethod: ${payment.optString("method")}\nReference: ${payment.optString("reference")}") }) { Text("Share receipt") }
                    TextButton(enabled = !voided && alreadyRefunded < payment.optLong("amount"), onClick = { form = FeatureForm("Refund payment", listOf(FeatureField("amount", "Refund amount ($currency)"), FeatureField("method", "Refund method", payment.optString("method")), FeatureField("reason", "Required reason"))) { v ->
                        val amount = ClinicalRules.parseMinor(v.getValue("amount"), currency)
                        require(amount in 1..(payment.optLong("amount") - alreadyRefunded)) { "Refund exceeds the unrefunded payment" }
                        val changed = copyJson(patient); records(changed, "invoices").first { it.optString("id") == invoice.optString("id") }.array("refunds").put(JSONObject().put("id", uid()).put("paymentId", payment.optString("id")).put("amount", amount).put("method", v.getValue("method")).put("reason", v.getValue("reason")).put("at", System.currentTimeMillis()))
                        onChange(changed, "Refund ${ClinicalRules.money(amount, currency)}: ${v.getValue("reason")}")
                    } }) { Text("Refund") }
                }
            }
            records(invoice, "refunds").forEach { refund -> Text("Refund ${ClinicalRules.money(refund.optLong("amount"), currency)} · ${refund.optString("reason")}", fontSize = 12.sp) }
        }
    }
    if (creating) InvoiceComposer(patient, config, { creating = false }) { invoice -> val changed = copyJson(patient); changed.array("invoices").put(invoice); val beforeSave = saveStatus(); onChange(changed, "Invoice issued: ${invoice.optString("description")}"); requireAcceptedSave(beforeSave, saveStatus()); creating = false }
    FeatureDialog(form) { form = null }
}

private fun invoiceText(patient: JSONObject, invoice: JSONObject, config: JSONObject): String {
    val currency = invoice.optString("currency", currentCurrency(config))
    return buildString {
        appendLine(config.optString("name", "Dental Care")); appendLine("INVOICE ${invoice.optString("id")}"); appendLine("Patient: ${patient.optString("name")}")
        appendLine(ClinicalRules.displayTime(invoice.optLong("at"), config))
        records(invoice, "lines").forEach { appendLine("${it.optInt("quantity")} × ${it.optString("description")} @ ${ClinicalRules.money(it.optLong("unitPrice"), currency)}") }
        appendLine("Total: ${ClinicalRules.money(ClinicalRules.invoiceTotal(invoice), currency)}")
        appendLine("Payments: ${ClinicalRules.money(ClinicalRules.paid(invoice), currency)}")
        appendLine("Refunds: ${ClinicalRules.money(ClinicalRules.refunded(invoice), currency)}")
        appendLine("Outstanding: ${ClinicalRules.money(ClinicalRules.balance(invoice), currency)}")
        if (invoice.optString("status") == "Voided") appendLine("VOIDED · ${invoice.optString("voidReason")}")
    }
}

@Composable private fun InvoiceComposer(patient: JSONObject, config: JSONObject, close: () -> Unit, issue: (JSONObject) -> Unit) {
    val currency = currentCurrency(config)
    val lines = remember { mutableStateListOf<JSONObject>() }
    var description by remember { mutableStateOf("") }; var unitPrice by remember { mutableStateOf("") }; var quantity by remember { mutableStateOf("1") }
    var serviceId by remember { mutableStateOf("") }; var discount by remember { mutableStateOf("0") }
    var tax by remember { mutableStateOf(BigDecimal(config.optInt("taxBps")).movePointLeft(2).toPlainString()) }; var error by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = close, title = { Text("New invoice · $currency") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("${patient.optString("name")} · issued prices will be retained")
            val services = records(config, "services").filter { it.optBoolean("active", true) }
            Row(Modifier.horizontalScroll(rememberScrollState())) { services.forEach { service -> FilterChip(selected = serviceId == service.optString("id"), onClick = { serviceId = service.optString("id"); description = service.optString("name"); unitPrice = BigDecimal.valueOf(service.optLong("fee")).movePointLeft(ClinicalRules.currencyDigits(currency)).toPlainString() }, label = { Text(service.optString("name")) }) } }
            OutlinedTextField(description, { description = it; serviceId = "" }, label = { Text("Line description") })
            OutlinedTextField(unitPrice, { unitPrice = it }, label = { Text("Unit price ($currency)") })
            OutlinedTextField(quantity, { quantity = it }, label = { Text("Quantity") })
            OutlinedButton(onClick = { runCatching {
                require(description.isNotBlank()) { "Enter a description" }; val count = quantity.toInt(); require(count in 1..999) { "Quantity must be 1–999" }
                val fee = ClinicalRules.parseMinor(unitPrice, currency)
                require(lines.size < 50) { "Maximum 50 lines per invoice" }
                lines.add(JSONObject().put("id", uid()).put("serviceId", serviceId).put("description", description.trim()).put("quantity", count).put("unitPrice", fee))
                description = ""; quantity = "1"; unitPrice = ""; serviceId = ""; error = ""
            }.onFailure { error = it.localizedMessage.orEmpty() } }) { Text("Add line") }
            lines.toList().forEachIndexed { index, line -> Row(verticalAlignment = Alignment.CenterVertically) { Text("${line.optInt("quantity")} × ${line.optString("description")} · ${ClinicalRules.money(line.optLong("unitPrice"), currency)}", Modifier.weight(1f)); TextButton(onClick = { lines.removeAt(index) }) { Text("Remove") } } }
            OutlinedTextField(discount, { discount = it }, label = { Text("Discount amount ($currency)") })
            OutlinedTextField(tax, { tax = it }, label = { Text("Tax percent") })
            if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
        }
    }, confirmButton = { TextButton(onClick = { runCatching {
        require(lines.isNotEmpty()) { "Add at least one invoice line" }
        val invoice = JSONObject().put("id", uid()).put("description", lines.joinToString { it.optString("description") }).put("currency", currency)
            .put("lines", JSONArray(lines.toList())).put("discount", ClinicalRules.parseMinor(discount, currency)).put("taxBps", tax.toBigDecimal().movePointRight(2).intValueExact())
            .put("at", System.currentTimeMillis()).put("status", "Issued").put("payments", JSONArray()).put("refunds", JSONArray())
        invoice.put("amount", ClinicalRules.invoiceTotal(invoice)); issue(invoice)
    }.onFailure { error = it.localizedMessage.orEmpty() } }) { Text("Issue invoice") } }, dismissButton = { TextButton(onClick = close) { Text("Cancel") } })
}

@Composable
fun SchedulingScreen(patients: List<JSONObject>, config: JSONObject, onChange: (JSONObject, String) -> Unit, onOpenPatient: (String) -> Unit) {
    val saveStatus = LocalEditorSaveStatus.current
    var period by remember { mutableStateOf("Day") }
    var reference by remember { mutableStateOf(LocalDate.now(ClinicalRules.zone(config)).toString()) }
    var editing by remember { mutableStateOf<Pair<JSONObject, JSONObject?>?>(null) }
    var booking by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    FeatureHeading("Appointments", "Clinic time zone · ${config.optString("timeZone", "Asia/Dhaka")}")
    FeatureChoices(listOf("Day", "Week", "Month", "All"), period) { period = it }
    OutlinedTextField(reference, { reference = it }, label = { Text("Calendar date YYYY-MM-DD") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { runCatching { reference = LocalDate.parse(reference).minusDays(when (period) { "Week" -> 7; "Month" -> 30; else -> 1 }).toString() }.onFailure { error = "Enter a valid calendar date" } }) { Text("← Previous") }
        OutlinedButton(onClick = { reference = LocalDate.now(ClinicalRules.zone(config)).toString() }) { Text("Today") }
        OutlinedButton(onClick = { runCatching { reference = LocalDate.parse(reference).plusDays(when (period) { "Week" -> 7; "Month" -> 30; else -> 1 }).toString() }.onFailure { error = "Enter a valid calendar date" } }) { Text("Next →") }
    }
    Button(enabled = patients.isNotEmpty(), onClick = { booking = true }) { Text("+ Book appointment") }
    val range = runCatching {
        val date = LocalDate.parse(reference); val zone = ClinicalRules.zone(config)
        val start = when (period) { "Week" -> date.with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY)); "Month" -> date.withDayOfMonth(1); else -> date }
        val end = when (period) { "Week" -> start.plusWeeks(1); "Month" -> start.plusMonths(1); else -> start.plusDays(1) }
        if (period == "All") Long.MIN_VALUE..Long.MAX_VALUE else start.atStartOfDay(zone).toInstant().toEpochMilli() until end.atStartOfDay(zone).toInstant().toEpochMilli()
    }.getOrNull()
    if (range == null) Text("Enter a valid calendar date", color = MaterialTheme.colorScheme.error)
    val entries = patients.flatMap { patient -> records(patient, "appointments").map { patient to it } }
        .filter { (_, appointment) -> range != null && runCatching { ClinicalRules.appointmentMillis(appointment, config) in range }.getOrDefault(false) }
        .sortedBy { ClinicalRules.appointmentMillis(it.second, config) }
    if (entries.isEmpty() && range != null) FeaturePanel("No appointments") { Text("No appointments match this calendar period.") }
    entries.forEach { (patient, appointment) -> FeaturePanel(ClinicalRules.displayTime(ClinicalRules.appointmentMillis(appointment, config), config)) {
        Text(patient.optString("name"), fontWeight = FontWeight.Bold)
        Text("${appointment.optString("doctor")} · ${appointment.optInt("durationMinutes", 30)} min · ${appointment.optString("room").ifBlank { "No room assigned" }}")
        Text(appointment.optString("reason")); Text(appointment.optString("status"), color = FeatureTeal)
        if (appointment.optBoolean("reminderEnabled")) Text("Reminder ${appointment.optInt("reminderHours", 24)}h before · ${if (appointment.optBoolean("consent")) "consented" else "consent missing"}", fontSize = 12.sp)
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            TextButton(onClick = { onOpenPatient(patient.optString("id")) }) { Text("Open patient") }
            TextButton(enabled = appointment.optString("status") !in ClinicalRules.terminalAppointments, onClick = { editing = patient to appointment }) { Text("Reschedule / edit") }
        }
        if (appointment.optString("status") !in ClinicalRules.terminalAppointments) {
            val status = appointment.optString("status")
            val next = when (status) {
                "Scheduled" -> listOf("Confirmed", "Checked in", "Cancelled", "No show")
                "Confirmed" -> listOf("Checked in", "Cancelled", "No show")
                "Checked in" -> listOf("In progress", "Cancelled")
                "In progress" -> listOf("Completed", "Cancelled")
                else -> emptyList()
            }
            Row(Modifier.horizontalScroll(rememberScrollState())) { next.forEach { state -> TextButton(onClick = {
                val changed = copyJson(patient)
                records(changed, "appointments").first { it.optString("id") == appointment.optString("id") }.put("status", state).put("statusAt", System.currentTimeMillis())
                onChange(changed, "Appointment ${appointment.optString("date")}: $state")
            }) { Text(state) } } }
        }
    } }
    if (booking) AlertDialog(onDismissRequest = { booking = false }, title = { Text("Choose patient") }, text = { Column(Modifier.verticalScroll(rememberScrollState())) { patients.sortedBy { it.optString("name") }.forEach { p -> TextButton(onClick = { editing = p to null; booking = false }, modifier = Modifier.fillMaxWidth()) { Text("${p.optString("name")} · ${p.optString("phone")}") } } } }, confirmButton = { TextButton(onClick = { booking = false }) { Text("Cancel") } })
    editing?.let { (patient, appointment) -> AppointmentEditor(patient, appointment, patients, config, { editing = null }) { saved ->
        val changed = copyJson(patient); val appointments = records(changed, "appointments").toMutableList()
        val index = appointments.indexOfFirst { it.optString("id") == saved.optString("id") }
        if (index == -1) appointments.add(saved) else appointments[index] = saved
        changed.put("appointments", JSONArray(appointments)); changed.put("communicationConsent", saved.optBoolean("consent"))
        val beforeSave = saveStatus()
        onChange(changed, if (appointment == null) "Appointment scheduled: ${saved.optString("date")}" else "Appointment amended: ${appointment.optString("date")} → ${saved.optString("date")}")
        requireAcceptedSave(beforeSave, saveStatus())
        editing = null
    } }
    if (error.isNotBlank()) AlertDialog(onDismissRequest = { error = "" }, title = { Text("Calendar") }, text = { Text(error) }, confirmButton = { TextButton(onClick = { error = "" }) { Text("OK") } })
}

@Composable private fun AppointmentEditor(patient: JSONObject, appointment: JSONObject?, patients: List<JSONObject>, config: JSONObject, close: () -> Unit, save: (JSONObject) -> Unit) {
    val clinicians = records(config, "staff").filter { it.optBoolean("active", true) && it.optString("role") in listOf("dentist", "owner", "administrator") }
    var date by remember { mutableStateOf(appointment?.optString("date") ?: "${LocalDate.now(ClinicalRules.zone(config))} 10:00") }
    var doctor by remember { mutableStateOf(appointment?.optString("doctor") ?: clinicians.firstOrNull()?.optString("name").orEmpty()) }
    var duration by remember { mutableStateOf((appointment?.optInt("durationMinutes", 30) ?: 30).toString()) }
    var room by remember { mutableStateOf(appointment?.optString("room").orEmpty()) }; var reason by remember { mutableStateOf(appointment?.optString("reason").orEmpty()) }
    var reminder by remember { mutableStateOf(appointment?.optBoolean("reminderEnabled") ?: config.optBoolean("remindersEnabled")) }
    var hours by remember { mutableStateOf((appointment?.optInt("reminderHours", 24) ?: config.optInt("reminderHours", 24)).toString()) }
    var consent by remember { mutableStateOf(appointment?.optBoolean("consent") ?: patient.optBoolean("communicationConsent")) }
    var error by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = close, title = { Text(if (appointment == null) "Book appointment" else "Edit appointment") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(patient.optString("name"), fontWeight = FontWeight.SemiBold)
            if (clinicians.isNotEmpty()) FeatureChoices(clinicians.map { it.optString("name") }, doctor) { doctor = it }
            OutlinedTextField(doctor, { doctor = it }, label = { Text("Clinician") })
            OutlinedTextField(date, { date = it }, label = { Text("YYYY-MM-DD HH:mm · clinic time") })
            OutlinedTextField(duration, { duration = it }, label = { Text("Duration minutes") })
            OutlinedTextField(room, { room = it }, label = { Text("Room / chair (optional)") })
            OutlinedTextField(reason, { reason = it }, label = { Text("Reason / service") })
            Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(reminder, { reminder = it }); Text("Schedule appointment reminder") }
            if (reminder) OutlinedTextField(hours, { hours = it }, label = { Text("Hours before appointment") })
            Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(consent, { consent = it }); Text("Patient consents to appointment notifications") }
            if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
        }
    }, confirmButton = { TextButton(onClick = { runCatching {
        require(reason.isNotBlank()) { "Enter the appointment reason" }
        val reminderHours = hours.toInt(); require(reminderHours in 1..168) { "Reminder lead must be 1–168 hours" }
        require(!reminder || consent) { "Patient consent is required for reminders" }
        val saved = appointment?.let(::copyJson) ?: JSONObject().put("id", uid()).put("status", "Scheduled").put("createdAt", System.currentTimeMillis())
        saved.put("date", date.trim()).put("startAt", ClinicalRules.parseDateTime(date.trim(), config)).put("durationMinutes", duration.toInt()).put("doctor", doctor.trim()).put("room", room.trim()).put("reason", reason.trim())
            .put("reminderEnabled", reminder).put("reminderHours", reminderHours).put("consent", consent).put("updatedAt", System.currentTimeMillis())
        ClinicalRules.validateAppointment(saved, patients, config); save(saved)
    }.onFailure { error = it.localizedMessage ?: "Review the appointment" } }) { Text("Save appointment") } }, dismissButton = { TextButton(onClick = close) { Text("Cancel") } })
}

@Composable
fun AdvancedReportsScreen(patients: List<JSONObject>, config: JSONObject) {
    val context = LocalContext.current
    var period by remember { mutableStateOf("Month") }; var start by remember { mutableStateOf(LocalDate.now(ClinicalRules.zone(config)).withDayOfMonth(1).toString()) }; var end by remember { mutableStateOf(LocalDate.now(ClinicalRules.zone(config)).toString()) }
    FeatureHeading("Practice reports", "Payment dates and clinic time zone determine the reporting period")
    FeatureChoices(listOf("Day", "Week", "Month", "Custom", "All"), period) { period = it }
    if (period == "Custom") { OutlinedTextField(start, { start = it }, label = { Text("From YYYY-MM-DD") }); OutlinedTextField(end, { end = it }, label = { Text("To YYYY-MM-DD, inclusive") }) }
    val rangeResult = runCatching { ClinicalRules.range(period, start, end, config) }
    val range = rangeResult.getOrNull()
    if (range == null) { Text(rangeResult.exceptionOrNull()?.localizedMessage ?: "Review the date range", color = MaterialTheme.colorScheme.error); return }
    val invoices = patients.flatMap { records(it, "invoices") }
    val currencies = invoices.map { it.optString("currency", currentCurrency(config)) }.distinct().sorted()
    val csv = StringBuilder("Currency,Billed in period,Payments received in period,Refunds in period,Net cash in period,Outstanding now\n")
    currencies.forEach { currency ->
        val entries = invoices.filter { it.optString("currency", currentCurrency(config)) == currency }
        val billed = entries.filter { it.optLong("at") in range && it.optString("status") != "Voided" }.sumOf { ClinicalRules.invoiceTotal(it) }
        val paid = entries.sumOf { invoice -> records(invoice, "payments").filter { it.optLong("at") in range }.sumOf { it.optLong("amount") } }
        val refunds = entries.sumOf { invoice -> records(invoice, "refunds").filter { it.optLong("at") in range }.sumOf { it.optLong("amount") } }
        val outstanding = entries.sumOf { ClinicalRules.balance(it) }
        FeaturePanel(currency) {
            Text("Billed in period: ${ClinicalRules.money(billed, currency)}")
            Text("Payments received: ${ClinicalRules.money(paid, currency)}")
            Text("Refunds paid out: ${ClinicalRules.money(refunds, currency)}")
            Text("Net cash: ${ClinicalRules.money(paid - refunds, currency)}", fontWeight = FontWeight.SemiBold)
            Text("Outstanding now (all invoices): ${ClinicalRules.money(outstanding, currency)}")
            Text("${entries.count { it.optLong("voidedAt") in range && it.optString("status") == "Voided" }} voided invoices in period", fontSize = 12.sp)
        }
        val digits = ClinicalRules.currencyDigits(currency)
        fun decimal(value: Long) = BigDecimal.valueOf(value).movePointLeft(digits).toPlainString()
        csv.appendLine(listOf(currency, decimal(billed), decimal(paid), decimal(refunds), decimal(paid - refunds), decimal(outstanding)).joinToString(","))
    }
    if (currencies.isEmpty()) FeaturePanel("No financial activity") { Text("Issued invoices and received payments will appear here.") }
    val appointments = patients.flatMap { records(it, "appointments") }.filter { runCatching { ClinicalRules.appointmentMillis(it, config) in range }.getOrDefault(false) }
    FeaturePanel("Appointments") {
        Text("${appointments.size} appointments in period", fontWeight = FontWeight.SemiBold)
        ClinicalRules.appointmentStates.forEach { state -> Text("$state: ${appointments.count { it.optString("status") == state }}") }
    }
    FeaturePanel("Clinician workload") {
        if (appointments.isEmpty()) Text("No appointments in this period")
        appointments.groupBy { it.optString("doctor").ifBlank { "Unassigned" } }.toSortedMap().forEach { (doctor, entries) ->
            Text("$doctor · ${entries.size} appointments · ${entries.filter { it.optString("status") != "Cancelled" }.sumOf { it.optInt("durationMinutes", 30) }} booked minutes")
        }
    }
    val treatments = patients.flatMap { records(it, "treatments") }
    val completed = treatments.filter { it.optString("status") == "Completed" && it.optLong("performedAt") in range }
    FeaturePanel("Clinical activity") {
        Text("${completed.size} completed procedures in period")
        completed.groupBy { it.optString("procedure") }.toSortedMap().forEach { (procedure, items) -> Text("$procedure: ${items.size}") }
        Text("Current treatment-plan states (all dates)", fontWeight = FontWeight.SemiBold)
        ClinicalRules.treatmentStates.forEach { state -> Text("$state: ${treatments.count { it.optString("status") == state }}") }
        Text("${patients.count { patient -> records(patient, "history").firstOrNull { it.optString("text") == "Patient registered" }?.optLong("at")?.let { it in range } == true }} newly registered patients in period")
    }
    OutlinedButton(onClick = { shareText(context, "Clinic financial report", csv.toString(), "text/csv") }) { Text("Share financial report CSV") }
}
