package com.spala.dental

import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.spala.dental.ui.theme.*
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId

/*
 * Payments for a treatment, paid over several visits (e.g. root canal: fee 5,000, paid 1,000 at
 * each of 5 visits). Each treatment (or multi-tooth treatment group) has one linked invoice in the
 * patient's invoices list: {treatmentRef, amount = total fee, one line, payments[]}. Every visit's
 * payment is appended to it, so the Billing tab, receipts, refunds, reports and the patient app all
 * see the same numbers. The total fee may be revised later, never below what was already paid.
 */

/** The key a treatment's payments are kept under: its group (several teeth) or its own id. */
fun treatmentRef(treatment: JSONObject): String = treatment.optString("groupId").ifBlank { treatment.optString("id") }

/** The active invoice that holds a treatment's fee and payments, if any. */
fun treatmentInvoice(patient: JSONObject, ref: String): JSONObject? =
    patient.optJSONArray("invoices").objectList().lastOrNull { it.optString("treatmentRef") == ref && it.optString("status") != "Voided" }

/** Fee summary for the treatment history: total fee, net paid, currency. */
data class TreatmentFee(val total: Long, val paid: Long, val currency: String, val visits: Int) {
    val due: Long get() = (total - paid).coerceAtLeast(0)
}

fun treatmentFee(patient: JSONObject, ref: String): TreatmentFee? = treatmentInvoice(patient, ref)?.let { invoice ->
    TreatmentFee(ClinicalRules.invoiceTotal(invoice), ClinicalRules.paid(invoice) - ClinicalRules.refunded(invoice),
        invoice.optString("currency", "BDT"), invoice.optJSONArray("payments")?.length() ?: 0)
}

fun feeText(fee: TreatmentFee): String = runCatching {
    "Fee ${ClinicalRules.money(fee.total, fee.currency)} · Paid ${ClinicalRules.money(fee.paid, fee.currency)}" +
        if (fee.due > 0) " · Due ${ClinicalRules.money(fee.due, fee.currency)}" else " · Fully paid"
}.getOrDefault("")

private fun decimal(minor: Long, currency: String) = BigDecimal.valueOf(minor).movePointLeft(ClinicalRules.currencyDigits(currency)).stripTrailingZeros().toPlainString()

/** Default fee of the clinic service with this procedure name (Settings › Services), if any. */
fun serviceFee(config: JSONObject, procedure: String): Long? =
    config.optJSONArray("services").objectList().firstOrNull { it.optBoolean("active", true) && it.optString("name").equals(procedure.trim(), true) }?.optLong("fee")

/**
 * Creates the fee invoice for a newly recorded treatment (all teeth of its group). Same document
 * shape as the payment dialog and the web app (D.saveTreatmentPayment), so Fee · Paid · Due works
 * everywhere. Returns the history text.
 */
fun setTreatmentFee(patient: JSONObject, treatment: JSONObject, fee: Long, config: JSONObject): String {
    require(fee > 0) { "The fee must be more than zero" }
    val ref = treatmentRef(treatment)
    val currency = config.optString("currency", "BDT")
    val teeth = patient.optJSONArray("treatments").objectList().filter { treatmentRef(it) == ref }.map { it.optString("tooth") }.distinct()
    val description = treatment.optString("procedure", "Treatment") + " · " + teeth.joinToString(", ") { ToothNotation.label(it) + " (FDI $it)" }
    patient.array("invoices").put(JSONObject().put("id", uid()).put("treatmentRef", ref).put("treatmentId", treatment.optString("id")).put("description", description)
        .put("currency", currency).put("lines", JSONArray().put(JSONObject().put("id", uid()).put("description", description).put("quantity", 1).put("unitPrice", fee)))
        .put("discount", 0).put("taxBps", 0).put("amount", fee).put("at", System.currentTimeMillis()).put("status", "Issued")
        .put("payments", JSONArray()).put("refunds", JSONArray()))
    return "fee ${ClinicalRules.money(fee, currency)}"
}

/**
 * Fee and payments for one treatment: set or revise the total fee, add a payment for today's visit,
 * see every earlier payment and share a receipt. [onSave] returns null or the reason it was refused.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TreatmentPaymentDialog(patient: JSONObject, treatmentId: String, config: JSONObject, onClose: () -> Unit, onSave: (JSONObject, String) -> String?) {
    val context = LocalContext.current
    val treatment = patient.optJSONArray("treatments").objectList().firstOrNull { it.optString("id") == treatmentId } ?: return
    val ref = treatmentRef(treatment)
    val invoice = treatmentInvoice(patient, ref)
    val currency = invoice?.optString("currency") ?: config.optString("currency", "BDT")
    val teeth = patient.optJSONArray("treatments").objectList().filter { treatmentRef(it) == ref }.map { it.optString("tooth") }.distinct()
    val procedure = treatment.optString("procedure", "Treatment")
    val serviceFee = config.optJSONArray("services").objectList().firstOrNull { it.optBoolean("active", true) && it.optString("name").equals(procedure, true) }?.optLong("fee")
    val total = invoice?.let { ClinicalRules.invoiceTotal(it) }
    val paid = invoice?.let { ClinicalRules.paid(it) - ClinicalRules.refunded(it) } ?: 0L
    val payments = invoice?.optJSONArray("payments").objectList()
    val zone = runCatching { ClinicalRules.zone(config) }.getOrDefault(ZoneId.systemDefault())

    var feeText by remember { mutableStateOf((total ?: serviceFee)?.let { decimal(it, currency) }.orEmpty()) }
    val lastAmount = payments.lastOrNull()?.optLong("amount")
    var amountText by remember { mutableStateOf(lastAmount?.let { decimal(it, currency) }.orEmpty()) }
    var method by remember { mutableStateOf(payments.lastOrNull()?.optString("method")?.ifBlank { null } ?: "Cash") }
    var date by remember { mutableStateOf(LocalDate.now(zone).toString()) }
    var note by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }

    AlertDialog(onDismissRequest = onClose, shape = RoundedCornerShape(28.dp),
        title = { Column {
            Text("Treatment payments")
            Text("$procedure · " + teeth.joinToString(", ") { ToothNotation.label(it) }, style = MaterialTheme.typography.bodySmall, color = InkMuted)
        } },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (invoice != null) SurfaceCard(padding = 12.dp, background = Teal50) {
                    Text(feeText(TreatmentFee(total!!, paid, currency, payments.size)), style = MaterialTheme.typography.titleSmall, color = Teal700)
                    Text("${payments.size} payment${if (payments.size == 1) "" else "s"} so far", style = MaterialTheme.typography.bodySmall, color = InkMuted)
                }
                OutlinedTextField(feeText, { feeText = it.filter { c -> c.isDigit() || c == '.' } }, label = { Text("Total fee for this treatment ($currency)") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp),
                    supportingText = { Text(if (invoice == null) "e.g. 5000 for a root canal paid over several visits" else "You can raise or lower it later, but not below what is already paid") })

                Text("Payment for this visit", style = MaterialTheme.typography.labelLarge, color = Ink)
                OutlinedTextField(amountText, { amountText = it.filter { c -> c.isDigit() || c == '.' } }, label = { Text("Amount paid now ($currency)") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), supportingText = { Text("Leave empty to only save the fee") })
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    PAYMENT_METHODS.forEach { m -> FilterChip(selected = method == m, onClick = { method = m }, label = { Text(m) }) }
                }
                DateField(date, { date = it }, "Payment date", limit = DateLimit.PAST_OR_TODAY, today = LocalDate.now(zone))
                OutlinedTextField(note, { note = it.take(200) }, label = { Text("Note (optional, e.g. visit 2 of 5)") }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp))

                if (payments.isNotEmpty()) {
                    Text("Earlier payments", style = MaterialTheme.typography.labelLarge, color = Ink)
                    payments.asReversed().forEachIndexed { index, payment ->
                        Surface(shape = RoundedCornerShape(14.dp), border = BorderStroke(1.dp, Hairline), color = SurfaceWhite) {
                            Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("Visit ${payments.size - index} · ${runCatching { ClinicalRules.money(payment.optLong("amount"), currency) }.getOrDefault("")}",
                                        style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                    Text(listOf(runCatching { ClinicalRules.displayTime(payment.optLong("at"), config) }.getOrDefault(""), payment.optString("method"), payment.optString("reference"))
                                        .filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = InkMuted)
                                }
                                TextButton(onClick = {
                                    val text = "${config.optString("name", "Dental Care")}\nPAYMENT RECEIPT\nPatient: ${patient.optString("name")}\nTreatment: $procedure (${teeth.joinToString(", ") { ToothNotation.label(it) }})\n" +
                                        "Date: ${ClinicalRules.displayTime(payment.optLong("at"), config)}\nReceived: ${ClinicalRules.money(payment.optLong("amount"), currency)} (${payment.optString("method")})\n" +
                                        "Total fee: ${ClinicalRules.money(total ?: 0, currency)} · Paid so far: ${ClinicalRules.money(paid, currency)} · Due: ${ClinicalRules.money(((total ?: 0) - paid).coerceAtLeast(0), currency)}"
                                    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Share receipt"))
                                }) { Text("Receipt") }
                            }
                        }
                    }
                }
                if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { Button(onClick = {
            error = runCatching {
                require(feeText.isNotBlank()) { "Enter the total fee for this treatment" }
                val fee = ClinicalRules.parseMinor(feeText, currency)
                require(fee > 0) { "The fee must be more than zero" }
                require(fee >= paid) { "The fee can't be less than the ${ClinicalRules.money(paid, currency)} already paid" }
                val amount = amountText.takeIf { it.isNotBlank() }?.let { ClinicalRules.parseMinor(it, currency) }
                if (amount != null) {
                    require(amount > 0) { "Enter the amount paid" }
                    require(paid + amount <= fee) { "That is more than the ${ClinicalRules.money(fee - paid, currency)} still due. Raise the total fee first if the treatment costs more." }
                }
                val at = LocalDate.parse(date).atTime(java.time.LocalTime.now(zone)).atZone(zone).toInstant().toEpochMilli().coerceAtMost(System.currentTimeMillis())
                val changed = JSONObject(patient.toString())
                val invoices = changed.array("invoices")
                val existing = invoices.objectList().lastOrNull { it.optString("treatmentRef") == ref && it.optString("status") != "Voided" }
                val actions = mutableListOf<String>()
                val description = "$procedure · " + teeth.joinToString(", ") { ToothNotation.label(it) + " (FDI $it)" }
                val target: JSONObject = existing ?: JSONObject().put("id", uid()).put("treatmentRef", ref).put("treatmentId", treatmentId).put("description", description).put("currency", currency)
                    .put("lines", JSONArray().put(JSONObject().put("id", uid()).put("description", description).put("quantity", 1).put("unitPrice", fee)))
                    .put("discount", 0).put("taxBps", 0).put("amount", fee).put("at", System.currentTimeMillis()).put("status", "Issued")
                    .put("payments", JSONArray()).put("refunds", JSONArray())
                    .also { invoices.put(it); actions += "fee ${ClinicalRules.money(fee, currency)}" }
                if (existing != null && ClinicalRules.invoiceTotal(existing) != fee) {
                    val before = ClinicalRules.invoiceTotal(existing)
                    existing.optJSONArray("lines")?.optJSONObject(0)?.put("unitPrice", fee)?.put("quantity", 1)
                    existing.put("amount", fee)
                    existing.array("feeChanges").put(JSONObject().put("id", uid()).put("from", before).put("to", fee).put("at", System.currentTimeMillis()))
                    actions += "fee changed ${ClinicalRules.money(before, currency)} → ${ClinicalRules.money(fee, currency)}"
                }
                if (amount != null) {
                    target.array("payments").put(JSONObject().put("id", uid()).put("amount", amount).put("method", method).put("reference", note.trim()).put("at", at)
                        .put("treatmentRef", ref))
                    actions += "payment ${ClinicalRules.money(amount, currency)} ($method)"
                }
                require(actions.isNotEmpty()) { "Nothing to save. Enter an amount paid, or change the fee." }
                onSave(changed, "Treatment payment · $procedure · ${actions.joinToString("; ")}")?.let { throw IllegalStateException(it) }
            }.exceptionOrNull()?.localizedMessage.orEmpty()
            if (error.isBlank()) onClose()
        }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onClose) { Text("Close") } })
}
