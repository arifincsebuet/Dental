package com.spala.dental.cloud

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/*
 * Clinic inventory (pure logic, no Firebase). Same rules as the web SDK (Web/sdk/inventory-core.js),
 * so Android and the web produce identical documents.
 *
 * The whole clinic inventory is ONE Firestore document: workspaces/{clinicId}/inventory/main
 *   items { id: { c catalog id?, n name?, g category (custom)?, u unit, q stock, m reorder level, p unit cost (minor),
 *                 b [{e expiry "YYYY-MM-DD", q qty, l lot?}] expiring batches, s supplier?, k SKU?, off 1? } }
 *   sup   { id: { n, ph? } }   tpl { procedure: { itemId: qty } }
 *   log   ["time s|itemId|change|type|ref|by"] (last 300)   mon { "YYYY-MM": { itemId: used } } (24 months)
 *   done  [opId] (last 100, safe retries)
 * Firestore does not allow arrays inside arrays, so batches are small maps and log lines are strings.
 * Names, categories, purposes and default material lists come from the catalog inside the app.
 */
object InventoryCore {
    const val LOG_MAX = 300
    const val DONE_MAX = 100
    const val MONTHS_MAX = 24
    val TYPES = linkedMapOf("in" to "Received", "use" to "Used", "adj" to "Count adjusted", "dmg" to "Damaged",
        "exp" to "Expired / disposed", "ret" to "Returned to supplier")

    fun r2(x: Double): Double = (x * 100).roundToLong() / 100.0
    private fun clip(v: String, n: Int) = v.replace("|", "/").take(n)

    fun empty(): JSONObject = JSONObject().put("v", 1).put("rev", 0).put("items", JSONObject()).put("sup", JSONObject())
        .put("tpl", JSONObject()).put("log", JSONArray()).put("mon", JSONObject()).put("done", JSONArray())

    fun newId(prefix: String = "i"): String {
        val a = "abcdefghijkmnpqrstuvwxyz23456789"
        return prefix + (1..7).map { a.random() }.joinToString("")
    }

    data class LogLine(val t: Long, val id: String, val dq: Double, val type: String, val ref: String, val by: String)

    fun parseLog(line: String): LogLine {
        val p = line.split("|")
        return LogLine(p.getOrNull(0)?.toLongOrNull() ?: 0, p.getOrElse(1) { "" }, p.getOrNull(2)?.toDoubleOrNull() ?: 0.0,
            p.getOrElse(3) { "" }, p.getOrElse(4) { "" }, p.getOrElse(5) { "" })
    }

    /** Batches with stock left, earliest expiry first. */
    fun batches(item: JSONObject): MutableList<JSONObject> {
        val b = item.optJSONArray("b") ?: return mutableListOf()
        return (0 until b.length()).mapNotNull { b.optJSONObject(it) }.sortedBy { it.optString("e") }.toMutableList()
    }

    private fun loose(item: JSONObject): Double = r2(item.optDouble("q", 0.0) - batches(item).sumOf { it.optDouble("q", 0.0) })

    /** Takes up to [qty], earliest expiry first; returns the amount taken. */
    private fun take(item: JSONObject, qty: Double): Double {
        var need = r2(qty)
        val free0 = max(0.0, loose(item))
        val list = batches(item)
        for (batch in list) {
            if (need <= 0) break
            val t = min(batch.optDouble("q", 0.0), need)
            batch.put("q", r2(batch.optDouble("q", 0.0) - t)); need = r2(need - t)
        }
        val kept = list.filter { it.optDouble("q", 0.0) > 0 }
        if (kept.isEmpty()) item.remove("b") else item.put("b", JSONArray(kept))
        need = r2(need - min(free0, need))
        val taken = r2(qty - need)
        item.put("q", r2(max(0.0, item.optDouble("q", 0.0) - taken)))
        return taken
    }

    private fun num(x: Double): Any = if (x == Math.floor(x) && kotlin.math.abs(x) < 1e15) x.toLong() else x

    /**
     * Applies operations (same set as the web SDK): item, receive, use, count, supplier, tpl.
     * Each op is a JSONObject with "op" and its fields. Returns a new document.
     */
    fun applyOps(doc: JSONObject?, ops: JSONArray, opId: String = "", by: String = "", now: Long = System.currentTimeMillis()): JSONObject {
        val d = JSONObject((doc?.takeIf { it.has("items") } ?: empty()).toString())
        listOf("sup", "tpl", "mon", "items").forEach { if (d.optJSONObject(it) == null) d.put(it, JSONObject()) }
        if (d.optJSONArray("log") == null) d.put("log", JSONArray())
        if (d.optJSONArray("done") == null) d.put("done", JSONArray())
        val done = d.getJSONArray("done")
        if (opId.isNotEmpty() && (0 until done.length()).any { done.optString(it) == opId }) return d
        val items = d.getJSONObject("items")
        val t = now / 1000
        val month = Instant.ofEpochMilli(now).atOffset(ZoneOffset.UTC).toLocalDate().toString().take(7)
        fun log(id: String, dq: Double, type: String, ref: String = "") {
            var e = "$t|${clip(id, 40)}|${num(r2(dq))}|$type"
            if (ref.isNotEmpty() || by.isNotEmpty()) e += "|${clip(ref, 40)}"
            if (by.isNotEmpty()) e += "|${clip(by, 40)}"
            d.getJSONArray("log").put(e)
        }
        for (i in 0 until ops.length()) {
            val o = ops.getJSONObject(i)
            val id = o.optString("id")
            val item = items.optJSONObject(id)
            when (o.optString("op")) {
                "item" -> {
                    val cur = item ?: JSONObject().put("u", o.optString("u", "piece").ifBlank { "piece" }).put("q", 0).put("m", 0)
                    for (k in listOf("c", "n", "u", "s", "k", "g")) if (o.has(k)) {
                        val v = o.opt(k)
                        if (v == null || v == JSONObject.NULL || v.toString().isEmpty()) cur.remove(k) else cur.put(k, v.toString().take(80))
                    }
                    if (o.has("m")) cur.put("m", num(max(0.0, r2(o.optDouble("m", 0.0)))))
                    if (o.has("p")) cur.put("p", max(0L, o.optDouble("p", 0.0).roundToLong()))
                    if (o.has("off")) { if (o.optBoolean("off") || o.optInt("off") == 1) cur.put("off", 1) else cur.remove("off") }
                    require(cur.has("c") || cur.has("n")) { "Give the item a name" }
                    items.put(id, cur)
                }
                "receive" -> {
                    requireNotNull(item) { "Unknown item" }
                    val qty = r2(o.optDouble("qty", 0.0))
                    require(qty > 0) { "Quantity must be more than zero" }
                    item.put("q", num(r2(item.optDouble("q", 0.0) + qty)))
                    val exp = o.optString("exp")
                    if (exp.isNotEmpty()) {
                        val row = JSONObject().put("e", exp.take(10)).put("q", num(qty))
                        if (o.optString("batch").isNotEmpty()) row.put("l", clip(o.optString("batch"), 30))
                        item.put("b", JSONArray(batches(item).also { it.add(row) }.sortedBy { it.optString("e") }))
                    }
                    if (o.has("cost") && o.opt("cost") != JSONObject.NULL && o.optString("cost").isNotEmpty()) item.put("p", max(0L, o.optDouble("cost", 0.0).roundToLong()))
                    if (o.optString("s").isNotEmpty()) item.put("s", o.optString("s"))
                    item.remove("off")
                    log(id, qty, "in", o.optString("batch"))
                }
                "use" -> {
                    if (item == null) continue
                    val type = o.optString("type", "use").takeIf { it in TYPES } ?: "use"
                    val taken = take(item, r2(o.optDouble("qty", 0.0)))
                    if (taken > 0) {
                        log(id, -taken, type, o.optString("ref"))
                        if (type == "use") {
                            val mon = d.getJSONObject("mon")
                            val m = mon.optJSONObject(month) ?: JSONObject().also { mon.put(month, it) }
                            m.put(id, num(r2(m.optDouble(id, 0.0) + taken)))
                        }
                    }
                }
                "count" -> {
                    requireNotNull(item) { "Unknown item" }
                    val qty = max(0.0, r2(o.optDouble("qty", 0.0)))
                    val diff = r2(qty - item.optDouble("q", 0.0))
                    if (diff < 0) take(item, -diff) else item.put("q", num(qty))
                    if (diff != 0.0) log(id, diff, "adj")
                }
                "supplier" -> {
                    require(o.optString("n").isNotBlank()) { "Give the supplier a name" }
                    val s = JSONObject().put("n", o.optString("n").take(80))
                    if (o.optString("ph").isNotEmpty()) s.put("ph", o.optString("ph").take(30))
                    d.getJSONObject("sup").put(id, s)
                }
                "tpl" -> {
                    val procedure = o.optString("procedure").take(60)
                    val clean = JSONObject()
                    val list = o.optJSONArray("list")
                    val map = o.optJSONObject("list")
                    when {
                        list != null -> for (k in 0 until min(list.length(), 30)) {
                            val row = list.optJSONArray(k) ?: continue
                            if (row.optString(0).isNotEmpty() && row.optDouble(1, 0.0) > 0) clean.put(row.optString(0), num(r2(row.optDouble(1))))
                        }
                        map != null -> map.keys().asSequence().take(30).forEach { k -> val q = map.optDouble(k, 0.0); if (q > 0) clean.put(k, num(r2(q))) }
                    }
                    if (list == null && map == null) d.getJSONObject("tpl").remove(procedure) else d.getJSONObject("tpl").put(procedure, clean)
                }
                else -> throw IllegalArgumentException("Unknown inventory operation")
            }
        }
        val log = d.getJSONArray("log")
        if (log.length() > LOG_MAX) d.put("log", JSONArray((log.length() - LOG_MAX until log.length()).map { log.get(it) }))
        val mon = d.getJSONObject("mon")
        val months = mon.keys().asSequence().sorted().toMutableList()
        while (months.size > MONTHS_MAX) mon.remove(months.removeAt(0))
        if (opId.isNotEmpty()) {
            done.put(opId)
            if (done.length() > DONE_MAX) d.put("done", JSONArray((done.length() - DONE_MAX until done.length()).map { done.get(it) }))
        }
        d.put("v", 1)
        return d
    }

    // ---------------------------------------------------------------- derived (computed in the app)

    fun daysTo(iso: String, today: LocalDate): Long = runCatching { ChronoUnit.DAYS.between(today, LocalDate.parse(iso.take(10))) }.getOrDefault(9999)

    /** out, low, expired, expiring (within [soon] days), inactive or ok. */
    fun status(item: JSONObject, today: LocalDate, soon: Long = 90): String {
        if (item.optInt("off") == 1) return "inactive"
        val exp = batches(item).filter { it.optDouble("q", 0.0) > 0 }.map { it.optString("e") }.minOrNull()
        if (exp != null && daysTo(exp, today) < 0) return "expired"
        val q = item.optDouble("q", 0.0)
        if (q <= 0) return "out"
        if (q <= item.optDouble("m", 0.0)) return "low"
        if (exp != null && daysTo(exp, today) <= soon) return "expiring"
        return "ok"
    }

    data class Summary(val items: Int, val low: Int, val out: Int, val expiring: Int, val expired: Int, val value: Long)

    fun summary(doc: JSONObject?, today: LocalDate): Summary {
        var n = 0; var low = 0; var out = 0; var expiring = 0; var expired = 0; var value = 0L
        val items = doc?.optJSONObject("items") ?: return Summary(0, 0, 0, 0, 0, 0)
        for (key in items.keys()) {
            val it = items.optJSONObject(key) ?: continue
            if (it.optInt("off") == 1) continue
            n++
            when (status(it, today)) { "low" -> low++; "out" -> out++; "expiring" -> expiring++; "expired" -> expired++ }
            value += (it.optDouble("q", 0.0) * it.optLong("p", 0)).roundToLong()
        }
        return Summary(n, low, out, expiring, expired, value)
    }

    /** Materials for a procedure: the clinic's list, else the catalog default limited to stocked items. */
    fun materialsFor(doc: JSONObject?, catalogTemplates: JSONObject?, procedure: String): List<Pair<String, Double>> {
        val items = doc?.optJSONObject("items") ?: JSONObject()
        fun active(id: String) = items.has(id) && items.optJSONObject(id)?.optInt("off") != 1
        doc?.optJSONObject("tpl")?.optJSONObject(procedure)?.let { map ->
            return map.keys().asSequence().map { it to map.optDouble(it, 0.0) }.filter { active(it.first) }.toList()
        }
        val byCatalog = HashMap<String, String>()
        for (key in items.keys()) items.optJSONObject(key)?.let { if (it.has("c") && it.optInt("off") != 1) byCatalog[it.optString("c")] = key }
        val defaults = catalogTemplates?.optJSONArray(procedure) ?: return emptyList()
        return (0 until defaults.length()).mapNotNull { defaults.optJSONArray(it) }.mapNotNull { row -> byCatalog[row.optString(0)]?.let { it to row.optDouble(1, 0.0) } }
    }

    fun materialCost(doc: JSONObject?, list: List<Pair<String, Double>>): Long =
        list.sumOf { (id, q) -> (doc?.optJSONObject("items")?.optJSONObject(id)?.optLong("p", 0) ?: 0L) * q }.roundToLong()

    /** Average monthly use over the last [months] stored months (reorder suggestions). */
    fun monthlyUse(doc: JSONObject?, id: String, months: Int = 3): Double {
        val mon = doc?.optJSONObject("mon") ?: return 0.0
        val keys = mon.keys().asSequence().sorted().toList().takeLast(months)
        if (keys.isEmpty()) return 0.0
        return r2(keys.sumOf { mon.optJSONObject(it)?.optDouble(id, 0.0) ?: 0.0 } / keys.size)
    }
}
