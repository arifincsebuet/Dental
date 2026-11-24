package com.spala.dental

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.spala.dental.cloud.InventoryCore
import com.spala.dental.ui.theme.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStream
import java.time.Instant
import java.time.LocalDate

/*
 * Clinic inventory (Android). Same document and rules as the web app (Web/js/inventory.js).
 * Cloud cost:
 *  - ONE Firestore document per clinic (workspaces/{clinic}/inventory/main), compact keys.
 *  - Read only when the Inventory screen, the home alerts or the record-treatment dialog need it,
 *    at most once every 15 minutes (cached on this device). Refresh forces a read.
 *  - Each change is one transaction (1 read + 1 write) that merges with other devices' changes.
 *  - Offline changes wait in a queue on this device and are sent at the next load or sync.
 *  - Names, categories, purposes and default material lists come from assets/inventory/catalog.json.
 */
class InventoryStore(private val context: Context, private val store: ClinicStore) {
    var doc by mutableStateOf<JSONObject?>(null); private set
    var loadedAt by mutableStateOf(0L); private set
    var loading by mutableStateOf(false); private set
    var pendingCount by mutableStateOf(0); private set
    private var pending = mutableListOf<JSONObject>() // {opId, ops, at}
    private var base: JSONObject? = null
    private var clinic = ""
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val prefs = context.getSharedPreferences("inventory-v1", Context.MODE_PRIVATE)

    /** Local catalog (never stored in the cloud). */
    val catalog: JSONObject by lazy {
        runCatching { JSONObject(context.assets.open("inventory/catalog.json").bufferedReader().use { it.readText() }) }.getOrDefault(JSONObject())
    }
    private val catalogItems: Map<String, JSONObject> by lazy { catalog.optJSONArray("items").objectList().associateBy { it.optString("id") } }
    val categories: List<Pair<String, String>> by lazy { catalog.optJSONArray("categories").objectList().map { it.optString("id") to it.optString("n") } }
    fun catalogItemsIn(category: String) = catalog.optJSONArray("items").objectList().filter { it.optString("cat") == category }
    fun catalogItem(id: String?): JSONObject? = id?.let { catalogItems[it] }

    private val cloud: Boolean get() = store.session == "staff"
    private val clinicId: String get() = store.workspaceId.ifBlank { store.cloudIdentity.ifBlank { "local-device" } }
    fun canStock() = store.can("inventory")
    fun canUse() = store.can("inventory") || store.can("recordTreatment")
    fun canSee() = canUse() || store.can("reports") || store.currentRole == "auditor"

    // -------------------------------------------------------------- names
    fun item(id: String): JSONObject? = doc?.optJSONObject("items")?.optJSONObject(id)
    fun name(id: String, it: JSONObject? = item(id)): String = it?.optString("n")?.ifBlank { null } ?: catalogItem(it?.optString("c"))?.optString("n") ?: if (doc != null) "Removed item" else "Stock item"
    fun category(it: JSONObject?): String = it?.optString("g")?.ifBlank { null } ?: catalogItem(it?.optString("c"))?.optString("cat") ?: "other"
    fun categoryName(id: String) = categories.firstOrNull { it.first == id }?.second ?: "Other"
    fun purpose(it: JSONObject?): String = catalogItem(it?.optString("c"))?.optString("use").orEmpty()
    fun today(): LocalDate = LocalDate.now(runCatching { ClinicalRules.zone(store.config) }.getOrDefault(java.time.ZoneId.systemDefault()))
    fun status(it: JSONObject) = InventoryCore.status(it, today())
    fun nextExpiry(it: JSONObject): String = InventoryCore.batches(it).firstOrNull { b -> b.optDouble("q") > 0 }?.optString("e").orEmpty()
    fun items(): List<Pair<String, JSONObject>> { val m = doc?.optJSONObject("items") ?: return emptyList(); return m.keys().asSequence().mapNotNull { k -> m.optJSONObject(k)?.let { k to it } }.toList() }

    // -------------------------------------------------------------- cache
    private fun readCache() {
        if (clinic == clinicId) return
        clinic = clinicId; doc = null; loadedAt = 0; pending = mutableListOf(); base = null
        runCatching {
            val j = JSONObject(prefs.getString(clinic, null) ?: return)
            base = j.optJSONObject("base"); loadedAt = j.optLong("at")
            pending = j.optJSONArray("pending").objectList().toMutableList()
            doc = withPending(base)
        }
        pendingCount = pending.size
    }
    private fun writeCache() {
        val j = JSONObject().put("base", base ?: JSONObject.NULL).put("at", loadedAt).put("pending", JSONArray(pending))
        prefs.edit().putString(clinic, j.toString()).apply()
        pendingCount = pending.size
    }
    private fun withPending(start: JSONObject?): JSONObject {
        var d = start ?: InventoryCore.empty()
        pending.forEach { p -> runCatching { d = InventoryCore.applyOps(d, p.getJSONArray("ops"), p.optString("opId"), store.cloudIdentity, p.optLong("at")) } }
        return d
    }

    /** Loads the stock when needed (cached 15 minutes). Cost: 0 or 1 read. */
    fun ensure(force: Boolean = false, then: () -> Unit = {}) {
        readCache()
        if (!cloud) { if (doc == null) doc = withPending(base); then(); return }
        if (!force && doc != null && System.currentTimeMillis() - loadedAt < TTL) { if (pending.isNotEmpty()) flush(); then(); return }
        if (loading) { then(); return }
        loading = true
        scope.launch {
            try {
                base = ClinicCloud.getInventory(clinicId)
                loadedAt = System.currentTimeMillis()
                doc = withPending(base); writeCache()
                flushNow()
            } catch (e: Exception) { if (force) toast(e.localizedMessage ?: "Could not load the stock") }
            finally { loading = false; then() }
        }
    }
    /** Background refresh for the home alerts, only when the cached copy is old. */
    fun touch() { if (!canSee()) return; readCache(); if (doc == null || (cloud && System.currentTimeMillis() - loadedAt >= TTL)) ensure() }
    /** Sync: send waiting changes and refresh an old copy. */
    fun sync() { if (!canSee()) return; readCache(); if (pending.isNotEmpty()) flush(); if (cloud && doc != null && System.currentTimeMillis() - loadedAt >= TTL) ensure() }

    private var flushing = false
    private fun flush() = scope.launch { flushNow() }
    private suspend fun flushNow() {
        if (flushing || !cloud || pending.isEmpty()) return
        flushing = true
        try {
            while (pending.isNotEmpty()) {
                val p = pending.first()
                try {
                    base = ClinicCloud.applyInventory(clinicId, store.cloudIdentity, p.getJSONArray("ops"), p.optString("opId"))
                    pending.removeAt(0); loadedAt = System.currentTimeMillis()
                } catch (e: Exception) {
                    val code = (e as? com.google.firebase.firestore.FirebaseFirestoreException)?.code ?: (e.cause as? com.google.firebase.firestore.FirebaseFirestoreException)?.code
                    val offline = e is java.io.IOException || e is com.google.firebase.FirebaseNetworkException ||
                        code == com.google.firebase.firestore.FirebaseFirestoreException.Code.UNAVAILABLE || code == com.google.firebase.firestore.FirebaseFirestoreException.Code.DEADLINE_EXCEEDED
                    if (offline) break
                    pending.removeAt(0)
                    toast("Stock change not saved: " + (e.cause?.localizedMessage ?: e.localizedMessage ?: "refused"))
                }
            }
            doc = withPending(base); writeCache()
        } finally { flushing = false }
    }

    /** Applies operations at once on this device and sends them to the cloud. Returns an error or null. */
    fun apply(ops: JSONArray, message: String = ""): String? {
        readCache()
        val onlyUse = (0 until ops.length()).all { ops.getJSONObject(it).optString("op") == "use" && ops.getJSONObject(it).optString("type", "use") == "use" }
        if (!(canStock() || (onlyUse && canUse()))) return "You don't have access to change the inventory."
        val p = JSONObject().put("opId", "op" + uid().replace("-", "").take(16)).put("ops", ops).put("at", System.currentTimeMillis())
        val next = runCatching { InventoryCore.applyOps(doc ?: InventoryCore.empty(), ops, p.optString("opId"), store.cloudIdentity, p.optLong("at")) }
            .getOrElse { return it.localizedMessage ?: "Check the details" }
        doc = next
        if (cloud) { pending.add(p); writeCache(); flush() } else { base = next; writeCache() }
        if (message.isNotBlank()) toast(if (cloud && pending.isNotEmpty()) "$message · syncing" else message)
        return null
    }
    private fun toast(text: String) = Toast.makeText(context, I18n.tr(text), Toast.LENGTH_SHORT).show()

    // -------------------------------------------------------------- derived
    fun summary() = InventoryCore.summary(doc, today())
    fun suggestOrder(id: String, it: JSONObject): Long {
        val m = InventoryCore.monthlyUse(doc, id, 3)
        val target = maxOf(it.optDouble("m") * 2, m * 2, it.optDouble("m") + 1)
        return kotlin.math.ceil(target - it.optDouble("q")).toLong().coerceAtLeast(0)
    }
    fun alerts(): List<Pair<String, JSONObject>> {
        val order = listOf("expired", "out", "low", "expiring")
        return items().filter { status(it.second) in order }.sortedBy { order.indexOf(status(it.second)) }
    }
    /** Proposed materials: consumables once per visit, the rest per tooth. */
    fun propose(procedure: String, teeth: Int): List<Pair<String, Double>> =
        InventoryCore.materialsFor(doc, catalog.optJSONObject("templates"), procedure).map { (id, q) -> id to if (category(item(id)) == "consumables") q else InventoryCore.r2(q * teeth) }
    fun deduct(list: List<Pair<String, Double>>, treatmentId: String, procedure: String) {
        if (list.isEmpty()) return
        val ops = JSONArray().also { a -> list.forEach { (id, q) -> a.put(JSONObject().put("op", "use").put("id", id).put("qty", q).put("ref", treatmentId)) } }
        apply(ops, "Materials for $procedure taken from stock")
    }
    fun procedures(): List<String> = (procedureNames + catalog.optJSONObject("templates")?.keys()?.asSequence()?.toList().orEmpty() +
        doc?.optJSONObject("tpl")?.keys()?.asSequence()?.toList().orEmpty()).distinct()

    companion object { const val TTL = 15 * 60_000L }
}

fun qtyText(x: Double): String = InventoryCore.r2(x).let { if (it == kotlin.math.floor(it)) it.toLong().toString() else it.toString() }
private fun parseQty(text: String): Double? = text.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it >= 0 }
private val StatusLabel = mapOf("ok" to "In stock", "low" to "Low", "out" to "Out of stock", "expiring" to "Expiring soon", "expired" to "Expired", "inactive" to "Not used")
private val GreenInk = Color(0xFF15803D)
private val VioletInk = Color(0xFF7C3AED)
private fun statusColor(s: String): Color = when (s) { "ok" -> GreenInk; "low", "expiring" -> Amber600; "out", "expired" -> Rose600; else -> InkSubtle }

@Composable
private fun StockStatus(s: String) = StatusPill(StatusLabel[s] ?: s, statusColor(s).copy(alpha = .12f), statusColor(s))

// ================================================================== screen
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun InventoryScreen(inv: InventoryStore, store: ClinicStore) {
    LaunchedEffect(store.workspaceId, store.cloudIdentity) { inv.ensure() }
    val savePdf = rememberPdfSaver()
    var tab by remember { mutableStateOf("Stock") }
    var filter by remember { mutableStateOf("All") }
    var cat by remember { mutableStateOf("") }
    var search by remember { mutableStateOf("") }
    var dialog by remember { mutableStateOf<Pair<String, String>?>(null) } // kind to id
    val currency = store.config.optString("currency", "BDT")
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Inventory", style = MaterialTheme.typography.headlineMedium, color = Ink)
        Text(if (inv.loadedAt > 0) "Updated ${ClinicalRules.displayTime(inv.loadedAt, store.config)}" + (if (inv.pendingCount > 0) " · ${inv.pendingCount} waiting to sync" else "") else "Stock, expiry and materials used in treatments",
            style = MaterialTheme.typography.bodyMedium, color = InkMuted)
    }
    val doc = inv.doc
    if (doc == null) { SurfaceCard(background = SurfaceTint) { Text(if (inv.loading) "Loading stock…" else "Stock list not loaded yet. Pull Refresh.", color = InkMuted) }; OutlinedButton(onClick = { inv.ensure(true) }) { Text("Refresh") }; return }
    ButtonRow(listOfNotNull<@Composable (Modifier) -> Unit>(
        if (inv.canStock()) ({ m -> Button(onClick = { dialog = "receive" to "" }, modifier = m, shape = RoundedCornerShape(14.dp)) { GlyphIcon(Glyph.Box, Color.White, 16.dp); Spacer(Modifier.width(6.dp)); ButtonLabel("Receive stock") } }) else null,
        if (inv.canStock()) ({ m -> OutlinedButton(onClick = { dialog = "item" to "" }, modifier = m, shape = RoundedCornerShape(14.dp)) { ButtonLabel("Add item") } }) else null,
        { m -> OutlinedButton(onClick = { inv.ensure(true) }, enabled = !inv.loading, modifier = m, shape = RoundedCornerShape(14.dp)) { ButtonLabel(if (inv.loading) "Loading…" else "Refresh") } },
        { m -> OutlinedButton(onClick = {
            val snapshot = JSONObject(doc.toString()); val settings = JSONObject(store.config.toString())
            savePdf(pdfName("inventory", store.config.optString("name", "clinic"), inv.today().toString())) { out -> InventoryPdf.write(inv, snapshot, settings, out) }
        }, modifier = m, shape = RoundedCornerShape(14.dp)) { ButtonLabel("PDF") } },
    ), minItemWidth = 140.dp)
    val sum = inv.summary()
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        StatTile("Low or out", "${sum.low + sum.out}", Amber600, Glyph.Alert, Modifier.weight(1f).clickable { tab = "Stock"; filter = "Low or out" })
        StatTile("Expiring (90 days)", "${sum.expiring}", VioletInk, Glyph.Calendar, Modifier.weight(1f).clickable { tab = "Stock"; filter = "Expiry" })
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        StatTile("Expired", "${sum.expired}", Rose600, Glyph.Alert, Modifier.weight(1f).clickable { tab = "Stock"; filter = "Expired" })
        StatTile("Items in use", "${sum.items}", Teal700, Glyph.Box, Modifier.weight(1f).clickable { tab = "Stock"; filter = "All" })
    }
    if (inv.canStock() || store.can("reports")) SurfaceCard(padding = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Stock value (last purchase cost)", style = MaterialTheme.typography.bodyMedium, color = InkMuted, modifier = Modifier.weight(1f))
            Text(ClinicalRules.money(wholeUnits(sum.value, currency), currency), style = MaterialTheme.typography.titleMedium, color = GreenInk, fontWeight = FontWeight.SemiBold)
        }
    }
    SegmentedPills(listOf("Stock", "Reorder", "Activity", "Materials", "Suppliers"), tab, Modifier.fillMaxWidth()) { tab = it }
    when (tab) {
        "Reorder" -> ReorderTab(inv, currency)
        "Activity" -> ActivityTab(inv, store)
        "Materials" -> MaterialsTab(inv, currency) { dialog = "tpl" to it }
        "Suppliers" -> SuppliersTab(inv) { dialog = "supplier" to it }
        else -> {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("All", "Low or out", "Expiry", "Expired", "Not used").forEach { f -> FilterChip(selected = filter == f, onClick = { filter = f }, label = { Text(f) }) }
            }
            val rows = inv.items().map { (id, it) -> Triple(id, it, inv.status(it)) }
            val cats = rows.map { inv.category(it.second) }.distinct().sortedBy { inv.categoryName(it) }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(selected = cat.isBlank(), onClick = { cat = "" }, label = { Text("All categories") })
                cats.forEach { c -> FilterChip(selected = cat == c, onClick = { cat = c }, label = { Text(inv.categoryName(c), maxLines = 1) }) }
            }
            OutlinedTextField(search, { search = it }, placeholder = { Text("Search items") }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp))
            val shown = rows.filter { (_, it, st) ->
                when (filter) { "Low or out" -> st in setOf("low", "out"); "Expiry" -> st in setOf("expiring", "expired"); "Expired" -> st == "expired"; "Not used" -> st == "inactive"; else -> st != "inactive" } &&
                    (cat.isBlank() || inv.category(it) == cat) && "${inv.name("", it)} ${it.optString("k")} ${inv.purpose(it)}".contains(search.trim(), true)
            }.sortedWith(compareBy({ inv.categoryName(inv.category(it.second)) }, { inv.name(it.first, it.second) }))
            if (shown.isEmpty()) SurfaceCard(background = SurfaceTint) { Text(if (rows.isEmpty()) "No items yet. Add the items your clinic keeps in stock." else "No items match.", color = InkMuted) }
            shown.forEach { (id, it, st) ->
                SurfaceCard(padding = 14.dp) {
                    Row(verticalAlignment = Alignment.Top) {
                        Column(Modifier.weight(1f)) {
                            Text(inv.name(id, it), style = MaterialTheme.typography.titleSmall, color = Ink)
                            Text(listOf(inv.categoryName(inv.category(it)), it.optString("k"), inv.purpose(it)).filter { s -> s.isNotBlank() }.joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall, color = InkMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        StockStatus(st)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Column { Text("${qtyText(it.optDouble("q"))} ${it.optString("u")}", fontWeight = FontWeight.SemiBold, color = Ink); Text("In stock", style = MaterialTheme.typography.labelSmall, color = InkMuted) }
                        Column { Text(qtyText(it.optDouble("m")), color = Ink); Text("Reorder at", style = MaterialTheme.typography.labelSmall, color = InkMuted) }
                        inv.nextExpiry(it).takeIf { e -> e.isNotBlank() }?.let { e -> Column { Text(e, color = Ink); Text("${InventoryCore.daysTo(e, inv.today())} days", style = MaterialTheme.typography.labelSmall, color = InkMuted) } }
                        if (it.optLong("p") > 0) Column { Text(ClinicalRules.money(it.optLong("p"), currency), color = Ink); Text("Unit cost", style = MaterialTheme.typography.labelSmall, color = InkMuted) }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (inv.canStock()) TextButton(onClick = { dialog = "receive" to id }) { Text("Receive") }
                        if (inv.canUse()) TextButton(onClick = { dialog = "use" to id }) { Text("Use") }
                        if (inv.canStock()) TextButton(onClick = { dialog = "count" to id }) { Text("Count") }
                        if (inv.canStock()) TextButton(onClick = { dialog = "item" to id }) { Text("Edit") }
                    }
                }
            }
        }
    }
    val open = dialog
    if (open != null) {
        val kind = open.first; val id = open.second
        val close = { dialog = null }
        when (kind) {
            "item" -> ItemDialog(inv, id, currency, close)
            "receive" -> ReceiveDialog(inv, id, currency, close)
            "use" -> UseDialog(inv, id, close)
            "count" -> CountDialog(inv, id, close)
            "supplier" -> SupplierDialog(inv, id, close)
            "tpl" -> TemplateDialog(inv, id, close)
        }
    }
}

fun wholeUnits(minor: Long, currency: String): Long {
    val d = runCatching { ClinicalRules.currencyDigits(currency) }.getOrDefault(2)
    var f = 1L; repeat(d) { f *= 10 }
    return Math.round(minor.toDouble() / f) * f
}

@Composable
private fun ReorderTab(inv: InventoryStore, currency: String) {
    val sup = inv.doc?.optJSONObject("sup") ?: JSONObject()
    val list = inv.items().filter { inv.status(it.second) in setOf("low", "out") }
    Text("Suggested quantity covers about two months, from the average use of the last three months and the reorder level.", style = MaterialTheme.typography.bodySmall, color = InkMuted)
    if (list.isEmpty()) SurfaceCard(background = SurfaceTint) { Text("Nothing to reorder.", color = InkMuted) }
    var total = 0L
    list.forEach { (id, it) ->
        val order = inv.suggestOrder(id, it); val cost = it.optLong("p") * order; total += cost
        SurfaceCard(padding = 14.dp) {
            Text(inv.name(id, it), style = MaterialTheme.typography.titleSmall, color = Ink)
            Text("In stock ${qtyText(it.optDouble("q"))} ${it.optString("u")} · used ${qtyText(InventoryCore.monthlyUse(inv.doc, id, 3))} a month", style = MaterialTheme.typography.bodySmall, color = InkMuted)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Order $order ${it.optString("u")}", fontWeight = FontWeight.SemiBold, color = Teal700, modifier = Modifier.weight(1f))
                if (cost > 0) Text(ClinicalRules.money(cost, currency), color = Ink)
            }
            sup.optJSONObject(it.optString("s"))?.let { s -> Text("${s.optString("n")} ${s.optString("ph")}".trim(), style = MaterialTheme.typography.bodySmall, color = InkMuted) }
        }
    }
    if (total > 0) Text("Total estimated: ${ClinicalRules.money(total, currency)}", fontWeight = FontWeight.SemiBold, color = Ink)
}

@Composable
private fun ActivityTab(inv: InventoryStore, store: ClinicStore) {
    val log = inv.doc?.optJSONArray("log") ?: JSONArray()
    val lines = (log.length() - 1 downTo maxOf(0, log.length() - 80)).map { InventoryCore.parseLog(log.optString(it)) }
    Text("The latest ${InventoryCore.LOG_MAX} stock movements are kept.", style = MaterialTheme.typography.bodySmall, color = InkMuted)
    if (lines.isEmpty()) SurfaceCard(background = SurfaceTint) { Text("No stock movements yet.", color = InkMuted) }
    lines.forEach { l ->
        val patientRef = store.patients.firstNotNullOfOrNull { p -> p.optJSONArray("treatments").objectList().firstOrNull { it.optString("id") == l.ref }?.let { "${p.optString("name")} · ${it.optString("procedure")}" } }
        SurfaceCard(padding = 12.dp) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(inv.name(l.id), style = MaterialTheme.typography.titleSmall, color = Ink)
                    Text(listOf(ClinicalRules.displayTime(l.t * 1000, store.config), InventoryCore.TYPES[l.type] ?: l.type, patientRef ?: l.ref).filter { it.isNotBlank() }.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall, color = InkMuted)
                }
                Text((if (l.dq > 0) "+" else "") + qtyText(l.dq) + " " + inv.item(l.id)?.optString("u").orEmpty(), color = if (l.dq < 0) Rose600 else GreenInk, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun MaterialsTab(inv: InventoryStore, currency: String, onEdit: (String) -> Unit) {
    Text("When a treatment is recorded as Completed, these materials are proposed and, after you confirm, taken from stock (earliest expiry first).", style = MaterialTheme.typography.bodySmall, color = InkMuted)
    inv.procedures().forEach { p ->
        val list = InventoryCore.materialsFor(inv.doc, inv.catalog.optJSONObject("templates"), p)
        SurfaceCard(padding = 14.dp, onClick = if (inv.canStock()) ({ onEdit(p) }) else null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(p, style = MaterialTheme.typography.titleSmall, color = Ink, modifier = Modifier.weight(1f))
                Text(ClinicalRules.money(InventoryCore.materialCost(inv.doc, list), currency), style = MaterialTheme.typography.bodySmall, color = InkMuted)
            }
            Text(list.joinToString(", ") { (id, q) -> "${inv.name(id)} × ${qtyText(q)}" }.ifBlank { "None in stock" }, style = MaterialTheme.typography.bodySmall, color = InkMuted)
        }
    }
}

@Composable
private fun SuppliersTab(inv: InventoryStore, onEdit: (String) -> Unit) {
    if (inv.canStock()) OutlinedButton(onClick = { onEdit("") }, shape = RoundedCornerShape(14.dp)) { Text("Add supplier") }
    val sup = inv.doc?.optJSONObject("sup") ?: JSONObject()
    if (sup.length() == 0) SurfaceCard(background = SurfaceTint) { Text("No suppliers yet.", color = InkMuted) }
    sup.keys().asSequence().toList().forEach { id ->
        val s = sup.getJSONObject(id)
        SurfaceCard(padding = 14.dp, onClick = if (inv.canStock()) ({ onEdit(id) }) else null) {
            Text(s.optString("n"), style = MaterialTheme.typography.titleSmall, color = Ink)
            Text("${s.optString("ph").ifBlank { "No phone" }} · ${inv.items().count { it.second.optString("s") == id }} items", style = MaterialTheme.typography.bodySmall, color = InkMuted)
        }
    }
}

/** Home: items that need action (no cloud read unless the cached copy is old). */
@Composable
fun InventoryHomeAlerts(inv: InventoryStore, onOpen: () -> Unit) {
    LaunchedEffect(Unit) { inv.touch() }
    val alerts = inv.alerts()
    if (alerts.isEmpty()) return
    SectionHeader("Stock needs attention", action = "Inventory", onAction = onOpen)
    alerts.take(4).forEach { (id, it) ->
        val st = inv.status(it); val e = inv.nextExpiry(it)
        SurfaceCard(padding = 12.dp, onClick = onOpen, background = if (st in setOf("out", "expired")) Color(0xFFFFF1F2) else Amber100) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                GlyphIcon(Glyph.Box, statusColor(st), 22.dp)
                Column(Modifier.weight(1f)) {
                    Text(inv.name(id, it), style = MaterialTheme.typography.titleSmall, color = Ink)
                    Text(when (st) { "expired" -> "Expired on $e: dispose and record"; "expiring" -> "Expires $e: use first"; "out" -> "Out of stock"
                        else -> "${qtyText(it.optDouble("q"))} ${it.optString("u")} left, reorder at ${qtyText(it.optDouble("m"))}" }, style = MaterialTheme.typography.bodySmall, color = InkMuted)
                }
            }
        }
    }
    if (alerts.size > 4) Text("${alerts.size - 4} more in Inventory", style = MaterialTheme.typography.bodySmall, color = InkMuted)
}

// ================================================================== dialogs
@Composable
private fun InvDialog(title: String, confirm: String = "Save", onDismiss: () -> Unit, onConfirm: () -> String?, content: @Composable ColumnScope.() -> Unit) {
    var error by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, shape = RoundedCornerShape(28.dp),
        title = { Text(title, style = MaterialTheme.typography.titleLarge) },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) { content(); if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error) } },
        confirmButton = { Button(onClick = { val e = runCatching { onConfirm() }.getOrElse { it.localizedMessage ?: "Check the details" }; if (e == null) onDismiss() else error = e }, shape = RoundedCornerShape(14.dp)) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

@Composable
private fun InvField(value: String, onChange: (String) -> Unit, label: String, number: Boolean = false, hint: String = "") =
    OutlinedTextField(value, onChange, label = { Text(label) }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp),
        keyboardOptions = if (number) KeyboardOptions(keyboardType = KeyboardType.Decimal) else KeyboardOptions.Default,
        supportingText = if (hint.isNotBlank()) ({ Text(hint) }) else null)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SupplierChips(inv: InventoryStore, selected: String, onSelect: (String) -> Unit) {
    val sup = inv.doc?.optJSONObject("sup") ?: JSONObject()
    if (sup.length() == 0) return
    Text("Supplier", style = MaterialTheme.typography.labelLarge, color = Ink)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterChip(selected = selected.isBlank(), onClick = { onSelect("") }, label = { Text("None") })
        sup.keys().asSequence().toList().forEach { id -> FilterChip(selected = selected == id, onClick = { onSelect(id) }, label = { Text(sup.getJSONObject(id).optString("n")) }) }
    }
}

/** Search box + matching list (catalog items or stocked items). */
@Composable
private fun PickList(query: String, onQuery: (String) -> Unit, label: String, options: List<Pair<String, String>>, selected: String, onPick: (String) -> Unit) {
    OutlinedTextField(query, onQuery, label = { Text(label) }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp))
    val matches = options.filter { it.second.contains(query.trim(), true) }.take(8)
    Column(Modifier.clip(RoundedCornerShape(14.dp)).background(SurfaceTint)) {
        matches.forEach { (id, text) ->
            Row(Modifier.fillMaxWidth().clickable { onPick(id) }.background(if (id == selected) Teal50 else Color.Transparent).padding(horizontal = 12.dp, vertical = 10.dp)) {
                Text(text, color = if (id == selected) Teal700 else Ink, style = MaterialTheme.typography.bodyMedium)
            }
        }
        if (matches.isEmpty()) Text("No match", color = InkMuted, modifier = Modifier.padding(12.dp))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ItemDialog(inv: InventoryStore, id: String, currency: String, onDismiss: () -> Unit) {
    val it = if (id.isNotBlank()) inv.item(id) else null
    var c by remember { mutableStateOf(it?.optString("c").orEmpty()) }
    var query by remember { mutableStateOf("") }
    var n by remember { mutableStateOf(it?.optString("n").orEmpty()) }
    var g by remember { mutableStateOf(it?.optString("g")?.ifBlank { null } ?: "consumables") }
    var u by remember { mutableStateOf(it?.optString("u") ?: "piece") }
    var m by remember { mutableStateOf(it?.let { x -> qtyText(x.optDouble("m")) }.orEmpty()) }
    var p by remember { mutableStateOf(it?.optLong("p")?.takeIf { v -> v > 0 }?.let { v -> java.math.BigDecimal.valueOf(v).movePointLeft(ClinicalRules.currencyDigits(currency)).stripTrailingZeros().toPlainString() }.orEmpty()) }
    var k by remember { mutableStateOf(it?.optString("k").orEmpty()) }
    var s by remember { mutableStateOf(it?.optString("s").orEmpty()) }
    var off by remember { mutableStateOf(it?.optInt("off") == 1) }
    var q by remember { mutableStateOf("") }
    var exp by remember { mutableStateOf("") }
    val used = inv.items().mapNotNull { x -> x.second.optString("c").ifBlank { null } }.toSet()
    val options = remember(used) { inv.catalog.optJSONArray("items").objectList().filter { x -> x.optString("id") !in used || x.optString("id") == c }.map { x -> x.optString("id") to "${x.optString("n")} · ${inv.categoryName(x.optString("cat"))}" } }
    InvDialog(if (it != null) "Edit item" else "Add item", onDismiss = onDismiss, onConfirm = {
        if (c.isBlank() && n.isBlank()) return@InvDialog "Choose an item from the list or type a name"
        val newId = id.ifBlank { InventoryCore.newId("i") }
        val catalogName = inv.catalogItem(c)?.optString("n")
        val op = JSONObject().put("op", "item").put("id", newId).put("c", c).put("n", if (n.isNotBlank() && n.trim() != catalogName) n.trim() else "")
            .put("g", if (c.isBlank()) g else "").put("u", u.trim().ifBlank { "piece" }).put("m", parseQty(m) ?: 0.0).put("k", k.trim()).put("s", s)
        if (p.isNotBlank()) op.put("p", ClinicalRules.parseMinor(p.trim(), currency))
        if (it != null) op.put("off", off)
        val ops = JSONArray().put(op)
        val opening = parseQty(q) ?: 0.0
        if (it == null && opening > 0) {
            if (exp.isNotBlank()) require(runCatching { LocalDate.parse(exp.trim()) }.isSuccess) { "Write the expiry date as YYYY-MM-DD" }
            ops.put(JSONObject().put("op", "receive").put("id", newId).put("qty", opening).put("exp", exp.trim()))
        }
        inv.apply(ops, if (it != null) "Item saved" else "Item added")
    }) {
        if (c.isNotBlank()) {
            SurfaceCard(padding = 12.dp, background = Teal50) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text(inv.catalogItem(c)?.optString("n").orEmpty(), color = Ink, fontWeight = FontWeight.SemiBold); Text(inv.catalogItem(c)?.optString("use").orEmpty(), style = MaterialTheme.typography.bodySmall, color = InkMuted) }
                    if (it == null) TextButton(onClick = { c = "" }) { Text("Change") }
                }
            }
        } else {
            if (it == null) PickList(query, { query = it }, "Search the dental list (182 items)", options, c) { pick -> c = pick; inv.catalogItem(pick)?.let { x -> u = x.optString("u"); g = x.optString("cat") } }
            Text("Or a custom item:", style = MaterialTheme.typography.labelLarge, color = Ink)
            Text("Category", style = MaterialTheme.typography.labelMedium, color = InkMuted)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                (inv.categories + ("other" to "Other")).forEach { (cid, cn) -> FilterChip(selected = g == cid, onClick = { g = cid }, label = { Text(cn, maxLines = 1) }) }
            }
        }
        InvField(n, { n = it }, if (c.isBlank()) "Name" else "Name (optional, to rename)")
        InvField(u, { u = it }, "Unit (piece, box, syringe…)")
        InvField(m, { m = it }, "Reorder level", number = true, hint = "Alert at or below this quantity")
        InvField(p, { p = it }, "Unit cost ($currency)", number = true)
        InvField(k, { k = it }, "SKU / code (optional)")
        SupplierChips(inv, s) { s = it }
        if (it != null) Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(off, { off = it }); Text("Not used any more (hidden from lists)") }
        else { InvField(q, { q = it }, "Opening stock (optional)", number = true); InvField(exp, { exp = it }, "Expiry YYYY-MM-DD (optional)") }
    }
}

@Composable
private fun ReceiveDialog(inv: InventoryStore, id: String, currency: String, onDismiss: () -> Unit) {
    var itemId by remember { mutableStateOf(id) }
    var query by remember { mutableStateOf("") }
    val it = inv.item(itemId)
    var qty by remember { mutableStateOf("") }
    var cost by remember(itemId) { mutableStateOf(it?.optLong("p")?.takeIf { v -> v > 0 }?.let { v -> java.math.BigDecimal.valueOf(v).movePointLeft(ClinicalRules.currencyDigits(currency)).stripTrailingZeros().toPlainString() }.orEmpty()) }
    var exp by remember { mutableStateOf("") }
    var batch by remember { mutableStateOf("") }
    var s by remember(itemId) { mutableStateOf(it?.optString("s").orEmpty()) }
    InvDialog(if (it != null) "Receive · ${inv.name(itemId)}" else "Receive stock", "Add to stock", onDismiss, onConfirm = {
        if (it == null) return@InvDialog "Choose the item"
        val q = parseQty(qty)?.takeIf { v -> v > 0 } ?: return@InvDialog "Enter the quantity received"
        if (exp.isNotBlank()) require(runCatching { LocalDate.parse(exp.trim()) }.isSuccess) { "Write the expiry date as YYYY-MM-DD" }
        val op = JSONObject().put("op", "receive").put("id", itemId).put("qty", q).put("exp", exp.trim()).put("batch", batch.trim()).put("s", s)
        if (cost.isNotBlank()) op.put("cost", ClinicalRules.parseMinor(cost.trim(), currency))
        inv.apply(JSONArray().put(op), "Received ${qtyText(q)} ${it.optString("u")} of ${inv.name(itemId)}")
    }) {
        if (id.isBlank()) PickList(query, { query = it }, "Item", inv.items().filter { x -> x.second.optInt("off") != 1 }.map { x -> x.first to "${inv.name(x.first, x.second)} (${qtyText(x.second.optDouble("q"))} ${x.second.optString("u")})" }.sortedBy { x -> x.second }, itemId) { itemId = it }
        InvField(qty, { qty = it }, "Quantity received" + (it?.let { x -> " (${x.optString("u")})" } ?: ""), number = true)
        InvField(cost, { cost = it }, "Unit cost ($currency)", number = true)
        InvField(exp, { exp = it }, "Expiry YYYY-MM-DD", hint = "For medicines and materials that expire")
        InvField(batch, { batch = it }, "Batch / lot")
        SupplierChips(inv, s) { s = it }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun UseDialog(inv: InventoryStore, id: String, onDismiss: () -> Unit) {
    val it = inv.item(id) ?: return
    val batches = InventoryCore.batches(it)
    var type by remember { mutableStateOf(if (batches.firstOrNull()?.let { b -> InventoryCore.daysTo(b.optString("e"), inv.today()) < 0 } == true && inv.canStock()) "exp" else "use") }
    var qty by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    InvDialog("Take from stock · ${inv.name(id)}", "Take from stock", onDismiss, onConfirm = {
        val q = parseQty(qty)?.takeIf { v -> v > 0 } ?: return@InvDialog "Enter the quantity"
        if (q > it.optDouble("q")) return@InvDialog "Only ${qtyText(it.optDouble("q"))} ${it.optString("u")} in stock. Use Count if the number is wrong."
        inv.apply(JSONArray().put(JSONObject().put("op", "use").put("id", id).put("qty", q).put("type", type).put("ref", note.trim())), "${InventoryCore.TYPES[type]}: ${qtyText(q)} ${it.optString("u")} of ${inv.name(id)}")
    }) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            (if (inv.canStock()) InventoryCore.TYPES.filterKeys { k -> k in setOf("use", "dmg", "exp", "ret") } else InventoryCore.TYPES.filterKeys { k -> k == "use" })
                .forEach { (k, label) -> FilterChip(selected = type == k, onClick = { type = k }, label = { Text(label) }) }
        }
        InvField(qty, { qty = it }, "Quantity (${it.optString("u")})", number = true, hint = "In stock: ${qtyText(it.optDouble("q"))}")
        InvField(note, { note = it }, "Note (optional)")
        if (batches.isNotEmpty()) Text("Taken from the earliest expiry first: " + batches.joinToString(", ") { b -> "${b.optString("e")} × ${qtyText(b.optDouble("q"))}" + b.optString("l").takeIf { l -> l.isNotBlank() }?.let { l -> " ($l)" }.orEmpty() },
            style = MaterialTheme.typography.bodySmall, color = InkMuted)
    }
}

@Composable
private fun CountDialog(inv: InventoryStore, id: String, onDismiss: () -> Unit) {
    val it = inv.item(id) ?: return
    var qty by remember { mutableStateOf(qtyText(it.optDouble("q"))) }
    InvDialog("Stock count · ${inv.name(id)}", onDismiss = onDismiss, onConfirm = {
        val q = parseQty(qty) ?: return@InvDialog "Enter the counted quantity"
        inv.apply(JSONArray().put(JSONObject().put("op", "count").put("id", id).put("qty", q)), "Stock count saved")
    }) {
        InvField(qty, { qty = it }, "Counted quantity (${it.optString("u")})", number = true, hint = "The app shows ${qtyText(it.optDouble("q"))}. The difference is logged.")
    }
}

@Composable
private fun SupplierDialog(inv: InventoryStore, id: String, onDismiss: () -> Unit) {
    val s = if (id.isNotBlank()) inv.doc?.optJSONObject("sup")?.optJSONObject(id) else null
    var n by remember { mutableStateOf(s?.optString("n").orEmpty()) }
    var ph by remember { mutableStateOf(s?.optString("ph").orEmpty()) }
    InvDialog(if (s != null) "Edit supplier" else "Add supplier", onDismiss = onDismiss, onConfirm = {
        if (n.isBlank()) return@InvDialog "Enter the supplier's name"
        inv.apply(JSONArray().put(JSONObject().put("op", "supplier").put("id", id.ifBlank { InventoryCore.newId("s") }).put("n", n.trim()).put("ph", ph.trim())), "Supplier saved")
    }) {
        InvField(n, { n = it }, "Name")
        InvField(ph, { ph = it }, "Phone")
    }
}

/** Editable list of materials (id → quantity text) with an "add item" picker. */
@Composable
fun MaterialEditor(inv: InventoryStore, rows: SnapshotStateMap<String, String>) {
    var adding by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    rows.keys.toList().forEach { id ->
        val it = inv.item(id)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f)) {
                Text(inv.name(id), style = MaterialTheme.typography.bodyMedium, color = Ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("${qtyText(it?.optDouble("q") ?: 0.0)} ${it?.optString("u").orEmpty()} in stock", style = MaterialTheme.typography.labelSmall, color = InkMuted)
            }
            OutlinedTextField(rows[id].orEmpty(), { v -> rows[id] = v }, singleLine = true, modifier = Modifier.width(88.dp), shape = RoundedCornerShape(12.dp),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
        }
    }
    if (adding) PickList(query, { query = it }, "Add item", inv.items().filter { x -> x.second.optInt("off") != 1 && x.first !in rows.keys }.map { x -> x.first to inv.name(x.first, x.second) }.sortedBy { x -> x.second }, "") { rows[it] = "1"; adding = false; query = "" }
    else TextButton(onClick = { adding = true }) { Text("+ Add another item") }
}
fun materialList(rows: Map<String, String>): List<Pair<String, Double>> = rows.mapNotNull { (id, v) -> parseQty(v)?.takeIf { it > 0 }?.let { id to InventoryCore.r2(it) } }

@Composable
private fun TemplateDialog(inv: InventoryStore, procedure: String, onDismiss: () -> Unit) {
    val rows = remember { mutableStateMapOf<String, String>().apply { InventoryCore.materialsFor(inv.doc, inv.catalog.optJSONObject("templates"), procedure).forEach { (id, q) -> put(id, qtyText(q)) } } }
    val custom = inv.doc?.optJSONObject("tpl")?.has(procedure) == true
    InvDialog("Materials · $procedure", onDismiss = onDismiss, onConfirm = {
        val list = JSONArray().also { a -> materialList(rows).forEach { (id, q) -> a.put(JSONArray().put(id).put(q)) } }
        inv.apply(JSONArray().put(JSONObject().put("op", "tpl").put("procedure", procedure).put("list", list)), "Materials for $procedure saved")
    }) {
        Text("Quantity per visit. Set 0 to remove an item.", style = MaterialTheme.typography.bodySmall, color = InkMuted)
        MaterialEditor(inv, rows)
        if (custom) TextButton(onClick = { inv.apply(JSONArray().put(JSONObject().put("op", "tpl").put("procedure", procedure)), "Default materials restored"); onDismiss() }) { Text("Use the app's default list again") }
    }
}

// ================================================================== PDF
object InventoryPdf {
    fun write(inv: InventoryStore, doc: JSONObject, config: JSONObject, output: OutputStream) {
        val currency = config.optString("currency", "BDT")
        val today = inv.today()
        val money = { v: Long -> runCatching { ClinicalRules.money(v, currency) }.getOrDefault(v.toString()) }
        val s = InventoryCore.summary(doc, today)
        val items = doc.optJSONObject("items") ?: JSONObject()
        val rows = items.keys().asSequence().mapNotNull { k -> items.optJSONObject(k)?.let { k to it } }.filter { it.second.optInt("off") != 1 }.toList()
        val sup = doc.optJSONObject("sup") ?: JSONObject()
        val report = PdfReport("Inventory", "$today · ${s.items} items", config)
        report.header()
        report.stats(listOf(Triple("Items in use", "${s.items}", PdfReport.TEAL), Triple("Low or out", "${s.low + s.out}", PdfReport.AMBER),
            Triple("Expiring (90 days)", "${s.expiring}", PdfReport.AMBER), Triple("Expired", "${s.expired}", PdfReport.RED), Triple("Stock value", money(wholeUnits(s.value, currency)), PdfReport.GREEN)))
        val name = { id: String, it: JSONObject -> it.optString("n").ifBlank { null } ?: inv.catalogItem(it.optString("c"))?.optString("n") ?: id }
        val stockText = { it: JSONObject -> "${qtyText(it.optDouble("q"))} ${it.optString("u")}" }
        val status = { it: JSONObject -> InventoryCore.status(it, today) }
        val exp = { it: JSONObject -> InventoryCore.batches(it).firstOrNull()?.optString("e").orEmpty() }
        val reorder = rows.filter { status(it.second) in setOf("low", "out") }
        if (reorder.isNotEmpty()) {
            report.heading("To reorder")
            report.table(listOf("Item", "In stock", "Suggested order", "Supplier", "Estimated cost"), reorder.map { (id, it) ->
                val o = inv.suggestOrder(id, it)
                listOf(name(id, it), stockText(it), "$o ${it.optString("u")}", sup.optJSONObject(it.optString("s"))?.optString("n").orEmpty(), if (it.optLong("p") > 0) money(it.optLong("p") * o) else "-")
            }, listOf(3f, 1.2f, 1.4f, 1.8f, 1.3f), setOf(4))
        }
        val expiring = rows.filter { status(it.second) in setOf("expired", "expiring") }
        if (expiring.isNotEmpty()) {
            report.heading("Expired or expiring")
            report.table(listOf("Item", "In stock", "Earliest expiry", "Status"), expiring.map { (id, it) -> listOf(name(id, it), stockText(it), exp(it), StatusLabel[status(it)].orEmpty()) }, listOf(3.4f, 1.2f, 1.4f, 1.4f))
        }
        rows.groupBy { inv.categoryName(inv.category(it.second)) }.toSortedMap().forEach { (cat, list) ->
            report.heading(cat)
            report.table(listOf("Item", "In stock", "Reorder at", "Expiry", "Unit cost", "Value", "Status"), list.sortedBy { name(it.first, it.second) }.map { (id, it) ->
                listOf(name(id, it), stockText(it), qtyText(it.optDouble("m")), exp(it).ifBlank { "-" }, if (it.optLong("p") > 0) money(it.optLong("p")) else "-",
                    Math.round(it.optDouble("q") * it.optLong("p")).let { v -> if (v > 0) money(v) else "-" }, StatusLabel[status(it)].orEmpty())
            }, listOf(3f, 1.1f, 0.9f, 1.1f, 1.1f, 1.2f, 1.1f), setOf(2, 4, 5))
        }
        val mon = doc.optJSONObject("mon")
        mon?.keys()?.asSequence()?.sorted()?.lastOrNull()?.let { month ->
            val used = mon.getJSONObject(month)
            report.heading("Used in $month")
            report.table(listOf("Item", "Used", "Cost"), used.keys().asSequence().map { id -> val it = items.optJSONObject(id) ?: JSONObject(); val q = used.optDouble(id)
                Triple(name(id, it), "${qtyText(q)} ${it.optString("u")}", Math.round(q * it.optLong("p"))) }.sortedByDescending { it.third }.map { listOf(it.first, it.second, if (it.third > 0) money(it.third) else "-") }.toList(),
                listOf(4f, 1.4f, 1.4f), setOf(2))
        }
        report.writeTo(output)
    }
}
