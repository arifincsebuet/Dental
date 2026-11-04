package com.spala.dental

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStream
import java.time.Instant
import java.time.format.DateTimeFormatter

/*
 * PDF reports (A4, vector, no extra library): full patient record with the 2D dental chart,
 * financial report, appointments list, invoice and payment receipt. Same content as the web app
 * (Web/js/reports.js) so a printed report looks the same from either app.
 */

/** Small page-flow layout engine on top of android.graphics.pdf.PdfDocument. */
class PdfReport(title: String, private val subtitle: String, private val config: JSONObject) {
    companion object {
        val INK = Color.rgb(15, 30, 46)
        val MUTED = Color.rgb(100, 116, 139)
        val TEAL = Color.rgb(15, 118, 110)
        val LINE = Color.rgb(226, 232, 240)
        val SOFT = Color.rgb(240, 253, 250)
        val AMBER = Color.rgb(180, 83, 9)
        val GREEN = Color.rgb(21, 128, 61)
        val RED = Color.rgb(220, 38, 38)
        val BLUE = Color.rgb(37, 99, 235)
        private const val W = 595f
        private const val H = 842f
        private const val M = 40f
    }

    private val title = I18n.tr(title)
    /** Clinic brand: accent colour and logo (header and watermark). */
    private val accent = ClinicBrand.colorOf(config.optString("brandColor")) ?: TEAL
    private val logo: android.graphics.Bitmap? = ClinicBrand.decodeLogo(config.optString("logo"))
    private val document = PdfDocument()
    private var page: PdfDocument.Page? = null
    private var pageNumber = 0
    private var y = 0f
    private val canvas: Canvas get() = page!!.canvas
    val width: Float = W - 2 * M

    init { newPage() }

    private fun paint(size: Float, color: Int = INK, bold: Boolean = false): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size
        this.color = color
        typeface = Typeface.create(Typeface.SANS_SERIF, if (bold) Typeface.BOLD else Typeface.NORMAL)
    }
    private fun fill(color: Int): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; style = Paint.Style.FILL }
    private fun stroke(color: Int, width: Float = 0.7f): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; style = Paint.Style.STROKE; strokeWidth = width }

    private fun newPage() {
        page?.let { footer(); document.finishPage(it) }
        pageNumber++
        page = document.startPage(PdfDocument.PageInfo.Builder(595, 842, pageNumber).create())
        watermark()
        canvas.drawRect(0f, 0f, W, 6f, fill(accent))
        var x = M
        logo?.let { bmp ->
            val h = 24f; val w = minOf(60f, h * bmp.width / bmp.height.coerceAtLeast(1))
            canvas.drawBitmap(bmp, null, RectF(M, 12f, M + w, 12f + h), Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
            x = M + w + 8f
        }
        canvas.drawText(config.optString("name", "Dental Care"), x, 23f, paint(10f, accent, true))
        val p = paint(8.5f, MUTED)
        canvas.drawText(title, W - M - p.measureText(title), 23f, p)
        val line2 = listOf(config.optString("address"), config.optString("clinicPhone")).filter { it.isNotBlank() }.joinToString("  ·  ")
        if (line2.isNotBlank()) { val ap = paint(7f, MUTED); canvas.drawText(wrap(line2, ap, W - M - x - 150f).firstOrNull() ?: "", x, 33f, ap) }
        canvas.drawLine(M, 40f, W - M, 40f, stroke(LINE))
        y = 58f
    }
    /** Faint clinic logo and name across the page (drawn first, under the content). */
    private fun watermark() {
        logo?.let { bmp ->
            val h = 220f; val w = minOf(320f, h * bmp.width / bmp.height.coerceAtLeast(1))
            canvas.drawBitmap(bmp, null, RectF((W - w) / 2, (H - h) / 2, (W + w) / 2, (H + h) / 2), Paint(Paint.FILTER_BITMAP_FLAG).apply { alpha = 16 })
        }
        val name = config.optString("name", "Dental Care")
        if (name.isNotBlank()) {
            val p = paint((900f / name.length.coerceAtLeast(8)).coerceIn(28f, 60f), accent, true).apply { alpha = 20 }
            canvas.save(); canvas.rotate(-45f, W / 2, H / 2 + 120f)
            canvas.drawText(name, W / 2 - p.measureText(name) / 2, H / 2 + 120f, p)
            canvas.restore()
        }
    }
    private fun footer() {
        val p = paint(7.5f, MUTED)
        canvas.drawText("${ClinicalRules.displayTime(System.currentTimeMillis(), config)} · ${config.optString("name", "Dental Care")}", M, H - 22f, p)
        val s = "$pageNumber"
        canvas.drawText(s, W - M - p.measureText(s), H - 22f, p)
    }
    fun ensure(height: Float) { if (y + height > H - 44f) newPage() }

    fun wrap(text: String, p: Paint, maxWidth: Float): List<String> {
        val out = mutableListOf<String>()
        for (paragraph in text.split('\n')) {
            if (paragraph.isBlank()) { out += ""; continue }
            var line = ""
            for (word in paragraph.split(' ')) {
                val candidate = if (line.isEmpty()) word else "$line $word"
                if (p.measureText(candidate) <= maxWidth) { line = candidate; continue }
                if (line.isNotEmpty()) out += line
                var rest = word
                while (p.measureText(rest) > maxWidth && rest.length > 1) {
                    val count = p.breakText(rest, true, maxWidth, null).coerceAtLeast(1)
                    out += rest.take(count); rest = rest.drop(count)
                }
                line = rest
            }
            if (line.isNotEmpty()) out += line
        }
        return out
    }

    fun header() {
        ensure(48f)
        canvas.drawText(title, M, y + 18f, paint(19f, INK, true))
        y += 28f
        if (subtitle.isNotBlank()) {
            val p = paint(9.5f, MUTED)
            wrap(subtitle, p, width).forEach { canvas.drawText(it, M, y + 10f, p); y += 13f }
        }
        y += 8f
    }
    fun heading(text: String) {
        ensure(64f)
        y += 10f
        canvas.drawText(I18n.tr(text).uppercase(), M, y + 9f, paint(8.5f, accent, true))
        y += 14f
        canvas.drawLine(M, y, W - M, y, stroke(LINE))
        y += 8f
    }
    fun text(value: String, size: Float = 9.5f, color: Int = INK, bold: Boolean = false, indent: Float = 0f) {
        val p = paint(size, color, bold)
        for (line in wrap(I18n.tr(value), p, width - indent)) { ensure(size + 4f); canvas.drawText(line, M + indent, y + size, p); y += size + 4f }
    }
    fun space(height: Float = 6f) { y += height }

    /** Label / value grid, [columns] per row. */
    fun facts(pairs: List<Pair<String, String>>, columns: Int = 2) {
        val colWidth = width / columns
        pairs.chunked(columns).forEach { row ->
            val valuePaint = paint(9.5f, INK, true)
            val wrapped = row.map { wrap(I18n.tr(it.second.ifBlank { "-" }), valuePaint, colWidth - 10f) }
            val height = 12f + (wrapped.maxOfOrNull { it.size } ?: 1) * 12f + 6f
            ensure(height)
            row.forEachIndexed { i, (label, _) ->
                val x = M + i * colWidth
                canvas.drawText(I18n.tr(label).uppercase(), x, y + 8f, paint(7f, MUTED, true))
                wrapped[i].forEachIndexed { j, line -> canvas.drawText(line, x, y + 20f + j * 12f, valuePaint) }
            }
            y += height
        }
    }

    /** Row of number tiles: (label, value, colour). */
    fun stats(items: List<Triple<String, String, Int>>) {
        if (items.isEmpty()) return
        ensure(52f)
        val gap = 8f
        val tile = (width - gap * (items.size - 1)) / items.size
        items.forEachIndexed { i, (label, value, color) ->
            val x = M + i * (tile + gap)
            canvas.drawRoundRect(RectF(x, y, x + tile, y + 44f), 6f, 6f, fill(Color.rgb(248, 250, 252)))
            canvas.drawRoundRect(RectF(x, y, x + tile, y + 44f), 6f, 6f, stroke(LINE))
            canvas.drawText(I18n.tr(label), x + 8f, y + 14f, paint(7.5f, MUTED, true))
            val vp = paint(if (value.length > 14) 10f else 13f, color, true)
            canvas.drawText(wrap(value, vp, tile - 14f).firstOrNull() ?: value, x + 8f, y + 33f, vp)
        }
        y += 52f
    }

    /** Table with wrapped cells; [weights] share the page width; [right] = right-aligned columns. */
    fun table(headers: List<String>, rows: List<List<String>>, weights: List<Float>, right: Set<Int> = emptySet(), empty: String = "Nothing recorded.") {
        if (rows.isEmpty()) { text(empty, 9f, MUTED); return }
        val total = weights.sum()
        val widths = weights.map { it / total * width }
        val headPaint = paint(7.5f, MUTED, true)
        val cellPaint = paint(8.5f)
        fun drawHeader() {
            ensure(36f)
            canvas.drawRect(M, y, W - M, y + 16f, fill(SOFT))
            var x = M
            headers.forEachIndexed { i, h ->
                val label = I18n.tr(h).uppercase()
                val tx = if (i in right) x + widths[i] - 4f - headPaint.measureText(label) else x + 4f
                canvas.drawText(label, tx, y + 11f, headPaint)
                x += widths[i]
            }
            y += 18f
        }
        drawHeader()
        rows.forEach { row ->
            val wrapped = row.mapIndexed { i, cell -> wrap(I18n.tr(cell), cellPaint, widths.getOrElse(i) { 60f } - 8f) }
            val height = (wrapped.maxOfOrNull { it.size } ?: 1).coerceAtLeast(1) * 11f + 6f
            if (y + height > H - 44f) { newPage(); drawHeader() }
            var x = M
            wrapped.forEachIndexed { i, lines ->
                lines.forEachIndexed { j, line ->
                    val tx = if (i in right) x + widths[i] - 4f - cellPaint.measureText(line) else x + 4f
                    canvas.drawText(line, tx, y + 10f + j * 11f, cellPaint)
                }
                x += widths.getOrElse(i) { 0f }
            }
            y += height
            canvas.drawLine(M, y - 2f, W - M, y - 2f, stroke(LINE, 0.5f))
        }
        y += 4f
    }

    /** 2D odontogram: five surfaces per tooth, coloured like the app (findings amber, restorations blue…). */
    fun chart(chart: JSONObject?) {
        val dentition = chart?.optString("dentition", "permanent") ?: "permanent"
        val teeth = chart?.optJSONObject("teeth") ?: JSONObject()
        val upperP = listOf(18, 17, 16, 15, 14, 13, 12, 11, 21, 22, 23, 24, 25, 26, 27, 28).map(Int::toString)
        val lowerP = listOf(48, 47, 46, 45, 44, 43, 42, 41, 31, 32, 33, 34, 35, 36, 37, 38).map(Int::toString)
        val upperD = listOf(55, 54, 53, 52, 51, 61, 62, 63, 64, 65).map(Int::toString)
        val lowerD = listOf(85, 84, 83, 82, 81, 71, 72, 73, 74, 75).map(Int::toString)
        val rows: List<Pair<List<String>, Boolean>> = when (dentition) {
            "primary" -> listOf(upperD to true, lowerD to false)
            "mixed" -> listOf(upperP to true, upperD to true, lowerD to false, lowerP to false)
            else -> listOf(upperP to true, lowerP to false)
        }
        val size = 24f
        val step = 29f
        val gap = 14f
        val rowHeight = size + 16f
        val total = rows.size * rowHeight + 10f + 34f
        ensure(total)
        val chartWidth = 16 * step - (step - size) + gap
        val x0 = M + (width - chartWidth) / 2
        y += 6f
        val top = y
        rows.forEachIndexed { index, (keys, upper) ->
            if (index == rows.size / 2) y += 10f
            val ty = y + if (upper) 12f else 0f
            val half = keys.size / 2
            keys.forEachIndexed { i, key ->
                val x = if (i < half) x0 + (8 - half + i) * step else x0 + 8 * step + gap + (i - half) * step
                tooth(key, teeth.optJSONObject(key), x, ty, size, upper)
                val label = ToothNotation.label(key)
                val lp = paint(6.5f, MUTED, true)
                canvas.drawText(label, x + size / 2 - lp.measureText(label) / 2, if (upper) ty - 4f else ty + size + 9f, lp)
            }
            y += rowHeight
        }
        val dash = stroke(Color.rgb(203, 213, 225), 0.6f).apply { pathEffect = DashPathEffect(floatArrayOf(3f, 3f), 0f) }
        val midX = x0 + 8 * step - (step - size) / 2 + gap / 2
        canvas.drawLine(midX, top, midX, y, dash)
        val midY = top + (rows.size / 2) * rowHeight + 5f
        canvas.drawLine(x0 - 6f, midY, x0 + chartWidth + 6f, midY, dash)
        val pl = paint(6.5f, MUTED, true)
        val rightLabel = I18n.tr("Right").uppercase(); val leftLabel = I18n.tr("Left").uppercase()
        canvas.drawText(rightLabel, x0, top - 2f, pl)
        canvas.drawText(leftLabel, x0 + chartWidth - pl.measureText(leftLabel), top - 2f, pl)
        y += 6f
        legend(listOf(Color.rgb(238, 242, 246) to "Not assessed", Color.rgb(255, 233, 194) to "Assessed", Color.rgb(96, 165, 250) to "Treated",
            Color.rgb(204, 251, 241) to "Planned", Color.rgb(245, 158, 11) to "Finding", Color.rgb(248, 250, 252) to "Missing"))
    }
    private fun legend(items: List<Pair<Int, String>>) {
        ensure(16f)
        var x = M
        val p = paint(7.5f, MUTED)
        items.forEach { (color, raw) ->
            val label = I18n.tr(raw)
            canvas.drawRect(x, y + 2f, x + 8f, y + 10f, fill(color))
            canvas.drawRect(x, y + 2f, x + 8f, y + 10f, stroke(Color.rgb(203, 213, 225), 0.5f))
            canvas.drawText(label, x + 11f, y + 9.5f, p)
            x += 11f + p.measureText(label) + 12f
        }
        y += 16f
    }
    private fun surfaceColor(tooth: JSONObject?, surface: String): Int {
        if (tooth == null) return Color.rgb(238, 242, 246)
        val observation = tooth.optJSONObject("surfaces")?.optJSONObject(surface)
        if ((observation?.optJSONArray("findings")?.length() ?: 0) > 0 || (tooth.optJSONArray("findings")?.length() ?: 0) > 0) return Color.rgb(245, 158, 11)
        if ((observation?.optJSONArray("restorations")?.length() ?: 0) > 0) return Color.rgb(96, 165, 250)
        if ((tooth.optJSONArray("treatmentStates")?.length() ?: 0) > 0) return Color.rgb(204, 251, 241)
        if (tooth.optBoolean("treated")) return Color.rgb(96, 165, 250)
        return if (tooth.optString("assessment") == "assessed") Color.rgb(255, 233, 194) else Color.rgb(238, 242, 246)
    }
    private fun tooth(key: String, state: JSONObject?, x: Float, y: Float, s: Float, upper: Boolean) {
        val quadrant = key[0].digitToInt()
        val patientRight = quadrant in listOf(1, 4, 5, 8)
        val anterior = key[1].digitToInt() <= 3
        val lost = state?.optString("presence") in setOf("missing", "extracted", "exfoliated", "congenitally_absent") &&
            state?.optString("replacement", "none").let { it == null || it == "none" || it.isBlank() }
        val i = s * 0.3f
        val top = if (upper) "F" else "L"
        val bottom = if (upper) "L" else "F"
        val left = if (patientRight) "D" else "M"
        val right = if (patientRight) "M" else "D"
        val centre = if (anterior) "I" else "O"
        val outline = stroke(Color.rgb(203, 213, 225), 0.6f)
        fun poly(points: List<Float>, surface: String) {
            val path = Path()
            path.moveTo(points[0], points[1])
            var k = 2
            while (k < points.size) { path.lineTo(points[k], points[k + 1]); k += 2 }
            path.close()
            canvas.drawPath(path, fill(if (lost) Color.rgb(248, 250, 252) else surfaceColor(state, surface)))
            canvas.drawPath(path, outline)
        }
        poly(listOf(x, y, x + s, y, x + s - i, y + i, x + i, y + i), top)
        poly(listOf(x + s, y, x + s, y + s, x + s - i, y + s - i, x + s - i, y + i), right)
        poly(listOf(x, y + s, x + s, y + s, x + s - i, y + s - i, x + i, y + s - i), bottom)
        poly(listOf(x, y, x + i, y + i, x + i, y + s - i, x, y + s), left)
        poly(listOf(x + i, y + i, x + s - i, y + i, x + s - i, y + s - i, x + i, y + s - i), centre)
        if (lost) {
            val cross = stroke(Color.rgb(100, 116, 139), 1.4f)
            canvas.drawLine(x + 5f, y + 5f, x + s - 5f, y + s - 5f, cross)
            canvas.drawLine(x + s - 5f, y + 5f, x + 5f, y + s - 5f, cross)
        } else if ((state?.optJSONArray("treatmentStates")?.length() ?: 0) > 0) {
            val plus = paint(11f, accent, true)
            canvas.drawText("+", x + s / 2 - plus.measureText("+") / 2, y + s / 2 + 4f, plus)
        }
    }

    fun writeTo(output: OutputStream) {
        page?.let { footer(); document.finishPage(it) }
        page = null
        try { document.writeTo(output) } finally { document.close() }
    }
}

/** The reports offered by the app. Every function writes one complete PDF. */
object ReportPdfs {
    private fun list(value: JSONObject?, key: String): List<JSONObject> {
        val array: JSONArray = value?.optJSONArray(key) ?: return emptyList()
        return (0 until array.length()).mapNotNull { array.optJSONObject(it) }
    }
    private fun day(millis: Long, config: JSONObject): String = if (millis <= 0) "-" else
        DateTimeFormatter.ofPattern("dd MMM yyyy").withZone(ClinicalRules.zone(config)).format(Instant.ofEpochMilli(millis))
    private fun money(minor: Long, currency: String): String = runCatching { ClinicalRules.money(minor, currency) }.getOrDefault(minor.toString())
    private fun pretty(code: String) = code.replace('_', ' ')
    private val lost = setOf("missing", "extracted", "exfoliated", "congenitally_absent")
    private val open = setOf("Planned", "Accepted", "In progress", "Deferred", "Referred")
    private fun appointmentTime(a: JSONObject, config: JSONObject): Long = runCatching { ClinicalRules.appointmentMillis(a, config) }.getOrDefault(0L)
    fun patientDue(patient: JSONObject): Long = list(patient, "invoices").sumOf { ClinicalRules.balance(it).coerceAtLeast(0) }

    /** Full patient record: profile, 2D chart, tooth findings, treatments with fees, encounters, appointments, billing, history. */
    fun patientReport(patient: JSONObject, config: JSONObject, output: OutputStream) {
        val currency = config.optString("currency", "BDT")
        val report = PdfReport("Patient record", "${patient.optString("name")} · printed ${ClinicalRules.displayTime(System.currentTimeMillis(), config)}", config)
        report.header()
        val chart = displayChart(patient)
        val teeth = chart.optJSONObject("teeth") ?: JSONObject()
        val keys = teeth.keys().asSequence().toList().sortedBy { ToothNotation.orderIndex(it) }
        val treatments = list(patient, "treatments")
        val dob = patient.optString("dob")
        val age = runCatching { java.time.Period.between(java.time.LocalDate.parse(dob), java.time.LocalDate.now(ClinicalRules.zone(config))).years.toString() + " y" }.getOrDefault("")
        report.heading("Profile")
        report.facts(listOf(
            "Name" to patient.optString("name"), "Patient record" to patient.optString("id").take(18),
            "Date of birth" to listOf(dob, age).filter { it.isNotBlank() }.joinToString(" · "), "Gender" to patient.optString("gender"),
            "Phone" to patient.optString("phone"), "Address" to patient.optString("address"),
            "Medical alerts / allergies" to patient.optString("allergies").ifBlank { "None recorded" },
            "Appointment reminders" to if (patient.optBoolean("communicationConsent")) "Consented" else "Not consented",
            "Patient app ID" to (patient.optJSONObject("portal")?.optString("loginId")?.ifBlank { null } ?: "No login"),
            "Record status" to (if (patient.optBoolean("archived")) "Archived · " else "") + "revision ${patient.optLong("revision", patient.optLong("remoteVersion"))}",
        ))
        val counts = listOf(
            keys.count { teeth.optJSONObject(it)?.optBoolean("treated") == true },
            treatments.filter { it.optString("status") in open }.map { it.optString("tooth") }.distinct().size,
            keys.count { k -> val t = teeth.optJSONObject(k); (t?.optJSONArray("findings")?.length() ?: 0) > 0 || t?.optJSONObject("surfaces")?.let { s -> s.keys().asSequence().any { (s.optJSONObject(it)?.optJSONArray("findings")?.length() ?: 0) > 0 } } == true },
            keys.count { teeth.optJSONObject(it)?.optString("presence") in lost },
        )
        report.space(4f)
        report.stats(listOf(Triple("Treated teeth", counts[0].toString(), PdfReport.BLUE), Triple("Open plan", counts[1].toString(), PdfReport.TEAL),
            Triple("Findings", counts[2].toString(), PdfReport.AMBER), Triple("Missing / lost", counts[3].toString(), PdfReport.MUTED),
            Triple("Balance due", money(patientDue(patient), currency), if (patientDue(patient) > 0) PdfReport.AMBER else PdfReport.GREEN)))
        report.heading("Dental chart · ${chart.optString("dentition", "permanent")} dentition · ${ToothNotation.title(ToothNotation.current)}")
        report.space(10f)
        report.chart(chart)
        val toothRows = keys.mapNotNull { key ->
            val t = teeth.optJSONObject(key) ?: return@mapNotNull null
            val findings = mutableListOf<String>()
            val restorations = mutableListOf<String>()
            t.optJSONArray("findings")?.let { a -> (0 until a.length()).forEach { findings += pretty(a.optString(it)) } }
            t.optJSONObject("surfaces")?.let { s -> s.keys().forEach { surface ->
                val o = s.optJSONObject(surface)
                o?.optJSONArray("findings")?.let { a -> (0 until a.length()).forEach { findings += "${pretty(a.optString(it))} ($surface)" } }
                o?.optJSONArray("restorations")?.let { a -> (0 until a.length()).forEach { restorations += "${pretty(a.optString(it))} ($surface)" } }
            } }
            val openCare = treatments.filter { it.optString("tooth") == key && it.optString("status") in open }.map { "${it.optString("status")}: ${it.optString("procedure")}" }
            val presence = t.optString("presence", "unknown")
            val replacement = t.optString("replacement", "none")
            if (findings.isEmpty() && restorations.isEmpty() && openCare.isEmpty() && presence !in lost && replacement in setOf("none", "") && !t.optBoolean("treated")) return@mapNotNull null
            listOf("${ToothNotation.label(key)} (${key})", pretty(presence) + if (replacement !in setOf("none", "")) " · ${pretty(replacement)}" else "",
                findings.joinToString(", ").ifBlank { "-" }, restorations.joinToString(", ").ifBlank { "-" }, openCare.joinToString(", ").ifBlank { "-" })
        }
        report.heading("Tooth findings")
        report.table(listOf("Tooth", "Presence", "Findings", "Restorations", "Open care"), toothRows, listOf(1f, 1.1f, 1.6f, 1.6f, 1.8f), empty = "No coded findings, restorations or open care.")
        report.heading("Clinical story")
        report.text(patient.optString("story").ifBlank { "No clinical narrative recorded." })
        report.heading("Treatment history")
        val shown = mutableSetOf<String>()
        report.table(listOf("Date", "Tooth", "Procedure", "Status", "Doctor", "Fee", "Paid", "Due"),
            treatments.sortedByDescending { it.optLong("at") }.map { t ->
                val ref = treatmentRef(t)
                val fee = if (shown.add(ref)) treatmentFee(patient, ref) else null
                listOf(day(t.optLong("performedAt", t.optLong("at")), config), ToothNotation.label(t.optString("tooth")),
                    t.optString("procedure") + t.optString("surface").takeIf { it.isNotBlank() && it != "Whole tooth" }?.let { " · $it" }.orEmpty() +
                        t.optString("notes").takeIf { it.isNotBlank() }?.let { "\n$it" }.orEmpty(),
                    t.optString("status"), t.optString("doctor"), fee?.let { money(it.total, it.currency) } ?: "", fee?.let { money(it.paid, it.currency) } ?: "",
                    fee?.let { money(it.due, it.currency) } ?: "")
            }, listOf(1.05f, 0.7f, 2.2f, 0.95f, 1.3f, 0.9f, 0.9f, 0.9f), right = setOf(5, 6, 7), empty = "No treatment recorded.")
        report.heading("Performed procedures")
        report.table(listOf("Date", "Tooth", "Procedure", "Doctor", "Outcome"), list(patient, "performedProcedures").filterNot { it.optBoolean("voided") }.sortedByDescending { it.optLong("at") }.map {
            listOf(day(it.optLong("at"), config), ToothNotation.label(it.optString("tooth")), it.optString("procedure"), it.optString("doctor"), it.optString("outcome"))
        }, listOf(1f, 0.7f, 1.5f, 1.3f, 2.5f))
        report.heading("Encounters")
        val encounters = list(patient, "encounters").sortedByDescending { it.optLong("at") }
        if (encounters.isEmpty()) report.text("No encounters recorded.", 9f, PdfReport.MUTED)
        encounters.forEach { e ->
            report.text("${day(e.optLong("at"), config)} · ${e.optString("status")} · ${e.optString("doctor")}", 9.5f, PdfReport.INK, true)
            listOf("Complaint" to "complaint", "Examination" to "examination", "Diagnosis" to "diagnosis", "Plan" to "plan").forEach { (label, key) ->
                if (e.optString(key).isNotBlank()) report.text("$label: ${e.optString(key)}", 9f, PdfReport.INK, false, 10f)
            }
            list(e, "amendments").forEach { report.text("Amendment ${day(it.optLong("at"), config)}: ${it.optString("reason")} · ${it.optString("text")}", 8.5f, PdfReport.BLUE, false, 10f) }
            report.space(4f)
        }
        report.heading("Appointments")
        report.table(listOf("When", "Doctor", "Reason", "Status"), list(patient, "appointments").sortedByDescending { appointmentTime(it, config) }.map {
            listOf(ClinicalRules.displayTime(appointmentTime(it, config), config), it.optString("doctor"), it.optString("reason"), it.optString("status"))
        }, listOf(1.4f, 1.3f, 2f, 0.9f))
        report.heading("Billing")
        val invoices = list(patient, "invoices").sortedByDescending { it.optLong("at") }
        report.table(listOf("Date", "Invoice", "Total", "Received", "Balance", "Status"), invoices.map { v ->
            val c = v.optString("currency", currency)
            listOf(day(v.optLong("at"), config), v.optString("description"), money(ClinicalRules.invoiceTotal(v), c), money(ClinicalRules.paid(v) - ClinicalRules.refunded(v), c),
                money(ClinicalRules.balance(v).coerceAtLeast(0), c), invoiceState(v))
        }, listOf(1f, 2.6f, 1f, 1f, 1f, 0.9f), right = setOf(2, 3, 4), empty = "No invoices.")
        val payments = invoices.flatMap { v -> list(v, "payments").map { v to it } }.sortedByDescending { it.second.optLong("at") }
        if (payments.isNotEmpty()) {
            report.text("Payments received", 9f, PdfReport.MUTED, true)
            report.table(listOf("Date", "For", "Method", "Reference", "Amount"), payments.map { (v, pay) ->
                listOf(day(pay.optLong("at"), config), v.optString("description"), pay.optString("method"), pay.optString("reference"), money(pay.optLong("amount"), v.optString("currency", currency)))
            }, listOf(1f, 2.4f, 0.9f, 1.4f, 1f), right = setOf(4))
        }
        report.heading("Recent history")
        report.table(listOf("When", "Change"), list(patient, "history").sortedByDescending { it.optLong("at") }.take(40).map {
            listOf(ClinicalRules.displayTime(it.optLong("at"), config), it.optString("text") + it.optString("actor").takeIf { a -> a.isNotBlank() }?.let { a -> " · $a" }.orEmpty())
        }, listOf(1.3f, 4.5f))
        report.space(8f)
        report.text("This record supports, and does not replace, professional clinical judgement. Tooth keys are stored in FDI.", 7.5f, PdfReport.MUTED)
        report.writeTo(output)
    }

    private fun invoiceState(v: JSONObject): String = when {
        v.optString("status") == "Voided" -> "Voided"
        ClinicalRules.balance(v) <= 0 -> "Paid"
        ClinicalRules.paid(v) - ClinicalRules.refunded(v) > 0 -> "Part paid"
        else -> "Unpaid"
    }

    /** Financial and practice report for [range] (Long.MIN_VALUE…MAX = all dates). */
    fun financialReport(patients: List<JSONObject>, config: JSONObject, periodLabel: String, range: LongRange, output: OutputStream) {
        val report = PdfReport("Financial report", "$periodLabel · ${config.optString("timeZone", "Asia/Dhaka")} · income counts payments received, not invoices issued", config)
        report.header()
        val invoices = patients.flatMap { p -> list(p, "invoices").map { p to it } }
        val currencies = invoices.map { it.second.optString("currency", config.optString("currency", "BDT")) }.distinct().sorted().ifEmpty { listOf(config.optString("currency", "BDT")) }
        currencies.forEach { currency ->
            val mine = invoices.filter { it.second.optString("currency", config.optString("currency", "BDT")) == currency }
            val billed = mine.filter { it.second.optLong("at") in range && it.second.optString("status") != "Voided" }.sumOf { ClinicalRules.invoiceTotal(it.second) }
            val pays = mine.flatMap { (p, v) -> list(v, "payments").filter { it.optLong("at") in range }.map { Triple(p, v, it) } }
            val received = pays.sumOf { it.third.optLong("amount") }
            val refunds = mine.sumOf { (_, v) -> list(v, "refunds").filter { it.optLong("at") in range }.sumOf { it.optLong("amount") } }
            val outstanding = mine.sumOf { ClinicalRules.balance(it.second).coerceAtLeast(0) }
            report.heading("Summary · $currency")
            report.stats(listOf(Triple("Billed in period", money(billed, currency), PdfReport.INK), Triple("Payments received", money(received, currency), PdfReport.GREEN),
                Triple("Refunds", money(refunds, currency), PdfReport.RED), Triple("Net cash", money(received - refunds, currency), PdfReport.TEAL),
                Triple("Outstanding now", money(outstanding, currency), PdfReport.AMBER)))
            report.text("By payment method", 9f, PdfReport.MUTED, true)
            report.table(listOf("Method", "Payments", "Amount", "Share"), pays.groupBy { it.third.optString("method").ifBlank { "Other" } }.toSortedMap().map { (method, items) ->
                val sum = items.sumOf { it.third.optLong("amount") }
                listOf(method, items.size.toString(), money(sum, currency), if (received > 0) "${sum * 100 / received}%" else "-")
            }, listOf(2f, 1f, 1.4f, 0.8f), right = setOf(1, 2, 3), empty = "No payments in this period.")
            report.heading("Payments received · $currency")
            report.table(listOf("Date", "Patient", "For", "Method", "Amount"), pays.sortedBy { it.third.optLong("at") }.map { (p, v, pay) ->
                listOf(day(pay.optLong("at"), config), p.optString("name"), v.optString("description"), pay.optString("method"), money(pay.optLong("amount"), currency))
            }, listOf(1f, 1.6f, 2.4f, 0.9f, 1.1f), right = setOf(4), empty = "No payments in this period.")
            report.heading("Invoices issued · $currency")
            report.table(listOf("Date", "Patient", "Invoice", "Total", "Balance", "Status"), mine.filter { it.second.optLong("at") in range }.sortedBy { it.second.optLong("at") }.map { (p, v) ->
                listOf(day(v.optLong("at"), config), p.optString("name"), v.optString("description"), money(ClinicalRules.invoiceTotal(v), currency), money(ClinicalRules.balance(v).coerceAtLeast(0), currency), invoiceState(v))
            }, listOf(1f, 1.5f, 2.3f, 1f, 1f, 0.9f), right = setOf(3, 4), empty = "No invoices issued in this period.")
            val treatmentFees = mine.filter { it.second.optString("treatmentRef").isNotBlank() && it.second.optString("status") != "Voided" }
            if (treatmentFees.isNotEmpty()) {
                report.heading("Treatment payments by procedure · $currency")
                report.table(listOf("Procedure", "Treatments", "Fees", "Received in period", "Due now"), treatmentFees.groupBy { (p, v) ->
                    list(p, "treatments").firstOrNull { treatmentRef(it) == v.optString("treatmentRef") }?.optString("procedure")?.ifBlank { null } ?: v.optString("description").substringBefore(" · ")
                }.toSortedMap().map { (procedure, items) ->
                    listOf(procedure, items.size.toString(), money(items.sumOf { ClinicalRules.invoiceTotal(it.second) }, currency),
                        money(items.sumOf { (_, v) -> list(v, "payments").filter { it.optLong("at") in range }.sumOf { it.optLong("amount") } }, currency),
                        money(items.sumOf { ClinicalRules.balance(it.second).coerceAtLeast(0) }, currency))
                }, listOf(2.2f, 0.9f, 1.1f, 1.3f, 1.1f), right = setOf(1, 2, 3, 4))
            }
            report.heading("Balances due by patient · $currency")
            report.table(listOf("Patient", "Phone", "Invoices open", "Due"), mine.groupBy { it.first.optString("id") }.mapNotNull { (_, items) ->
                val due = items.sumOf { ClinicalRules.balance(it.second).coerceAtLeast(0) }
                if (due <= 0) null else Triple(items.first().first, items.count { ClinicalRules.balance(it.second) > 0 }, due)
            }.sortedByDescending { it.third }.take(60).map { (p, n, due) -> listOf(p.optString("name"), p.optString("phone"), n.toString(), money(due, currency)) },
                listOf(2f, 1.4f, 1f, 1.2f), right = setOf(2, 3), empty = "No balances due.")
        }
        val appointments = patients.flatMap { list(it, "appointments") }.filter { appointmentTime(it, config) in range }
        report.heading("Appointments in period")
        report.table(listOf("Status", "Count"), ClinicalRules.appointmentStates.map { s -> listOf(s, appointments.count { it.optString("status") == s }.toString()) }.filter { it[1] != "0" },
            listOf(3f, 1f), right = setOf(1), empty = "No appointments in this period.")
        report.heading("Clinician workload")
        val completed = patients.flatMap { list(it, "treatments") }.filter { it.optString("status") == "Completed" && it.optLong("performedAt", it.optLong("at")) in range }
        report.table(listOf("Doctor", "Appointments", "Completed visits", "Booked minutes", "Procedures"), appointments.groupBy { it.optString("doctor").ifBlank { "Unassigned" } }.toSortedMap().map { (doctor, items) ->
            listOf(doctor, items.size.toString(), items.count { it.optString("status") == "Completed" }.toString(),
                items.filter { it.optString("status") != "Cancelled" }.sumOf { it.optInt("durationMinutes", 30) }.toString(), completed.count { it.optString("doctor") == doctor }.toString())
        }, listOf(2f, 1f, 1.1f, 1.1f, 1f), right = setOf(1, 2, 3, 4), empty = "No appointments in this period.")
        report.heading("Clinical activity")
        report.table(listOf("Completed procedure", "Count"), completed.groupBy { it.optString("procedure") }.toSortedMap().map { (p, items) -> listOf(p, items.size.toString()) },
            listOf(3f, 1f), right = setOf(1), empty = "No completed procedures in this period.")
        val registered = patients.count { p -> list(p, "history").firstOrNull { it.optString("text") == "Patient registered" }?.optLong("at")?.let { it in range } == true }
        report.text("$registered newly registered patients in period · ${patients.count { !it.optBoolean("archived") }} active patients", 9f, PdfReport.MUTED)
        report.writeTo(output)
    }

    /** Appointment list (day sheet / week / month) for [range]. */
    fun appointmentsReport(patients: List<JSONObject>, config: JSONObject, periodLabel: String, range: LongRange, output: OutputStream) {
        val entries = patients.flatMap { p -> list(p, "appointments").map { p to it } }.filter { appointmentTime(it.second, config) in range }.sortedBy { appointmentTime(it.second, config) }
        val report = PdfReport("Appointments", "$periodLabel · ${entries.size} appointments · ${config.optString("timeZone", "Asia/Dhaka")}", config)
        report.header()
        report.stats(listOf(Triple("Booked", entries.count { it.second.optString("status") != "Cancelled" }.toString(), PdfReport.TEAL),
            Triple("Completed", entries.count { it.second.optString("status") == "Completed" }.toString(), PdfReport.GREEN),
            Triple("Requested", entries.count { it.second.optString("status") == "Requested" }.toString(), PdfReport.AMBER),
            Triple("Cancelled / no show", entries.count { it.second.optString("status") in setOf("Cancelled", "No show") }.toString(), PdfReport.RED)))
        entries.groupBy { day(appointmentTime(it.second, config), config) }.forEach { (date, items) ->
            report.heading(date)
            report.table(listOf("Time", "Patient", "Phone", "Doctor", "Reason", "Status"), items.map { (p, a) ->
                listOf(ClinicalRules.displayTime(appointmentTime(a, config), config).substringAfter("· "), p.optString("name") + p.optString("allergies").takeIf { it.isNotBlank() }?.let { "\n⚠ $it" }.orEmpty(),
                    p.optString("phone"), a.optString("doctor") + a.optString("room").takeIf { it.isNotBlank() }?.let { "\n$it" }.orEmpty(), a.optString("reason"), a.optString("status"))
            }, listOf(0.7f, 1.8f, 1.2f, 1.4f, 1.6f, 0.9f))
        }
        if (entries.isEmpty()) report.text("No appointments in this period.", 9.5f, PdfReport.MUTED)
        report.writeTo(output)
    }

    /** One invoice with its lines, payments and refunds. */
    fun invoicePdf(patient: JSONObject, invoice: JSONObject, config: JSONObject, output: OutputStream) {
        val currency = invoice.optString("currency", config.optString("currency", "BDT"))
        val report = PdfReport("Invoice", "#${invoice.optString("id").take(8).uppercase()} · ${day(invoice.optLong("at"), config)} · ${invoiceState(invoice)}", config)
        report.header()
        report.facts(listOf("Billed to" to patient.optString("name"), "Phone" to patient.optString("phone"), "Invoice date" to day(invoice.optLong("at"), config), "Status" to invoiceState(invoice)))
        report.heading("Items")
        val lines = list(invoice, "lines").ifEmpty { listOf(JSONObject().put("description", invoice.optString("description")).put("quantity", 1).put("unitPrice", invoice.optLong("amount"))) }
        report.table(listOf("Description", "Qty", "Unit price", "Amount"), lines.map {
            listOf(it.optString("description"), it.optInt("quantity", 1).toString(), money(it.optLong("unitPrice"), currency), money(it.optLong("unitPrice") * it.optInt("quantity", 1), currency))
        }, listOf(3.4f, 0.6f, 1.2f, 1.2f), right = setOf(1, 2, 3))
        val totals = mutableListOf<Pair<String, String>>()
        if (invoice.optLong("discount") > 0) totals += "Discount" to "- " + money(invoice.optLong("discount"), currency)
        if (invoice.optInt("taxBps") > 0) totals += "Tax" to "${java.math.BigDecimal(invoice.optInt("taxBps")).movePointLeft(2).toPlainString()}%"
        totals += "Total" to money(ClinicalRules.invoiceTotal(invoice), currency)
        totals += "Received" to money(ClinicalRules.paid(invoice) - ClinicalRules.refunded(invoice), currency)
        totals += "Balance due" to money(ClinicalRules.balance(invoice).coerceAtLeast(0), currency)
        report.table(listOf("", ""), totals.map { listOf(it.first, it.second) }, listOf(4f, 1.6f), right = setOf(0, 1))
        if (list(invoice, "payments").isNotEmpty()) {
            report.heading("Payments")
            report.table(listOf("Date", "Method", "Reference", "Amount"), list(invoice, "payments").map {
                listOf(ClinicalRules.displayTime(it.optLong("at"), config), it.optString("method"), it.optString("reference"), money(it.optLong("amount"), currency))
            }, listOf(1.5f, 1f, 2f, 1.2f), right = setOf(3))
        }
        if (list(invoice, "refunds").isNotEmpty()) {
            report.heading("Refunds")
            report.table(listOf("Date", "Reason", "Amount"), list(invoice, "refunds").map { listOf(day(it.optLong("at"), config), it.optString("reason"), money(it.optLong("amount"), currency)) }, listOf(1.2f, 3f, 1.2f), right = setOf(2))
        }
        if (invoice.optString("voidReason").isNotBlank()) report.text("Voided: ${invoice.optString("voidReason")}", 9.5f, PdfReport.RED, true)
        report.space(12f)
        report.text("Thank you. Please keep this invoice for your records.", 8.5f, PdfReport.MUTED)
        report.writeTo(output)
    }

    /** Receipt for one payment. */
    fun receiptPdf(patient: JSONObject, invoice: JSONObject, payment: JSONObject, config: JSONObject, output: OutputStream) {
        val currency = invoice.optString("currency", config.optString("currency", "BDT"))
        val report = PdfReport("Payment receipt", "Receipt #${payment.optString("id").take(8).uppercase()} · ${ClinicalRules.displayTime(payment.optLong("at"), config)}", config)
        report.header()
        report.stats(listOf(Triple("Amount received", money(payment.optLong("amount"), currency), PdfReport.GREEN), Triple("Method", payment.optString("method"), PdfReport.INK),
            Triple("Balance due", money(ClinicalRules.balance(invoice).coerceAtLeast(0), currency), if (ClinicalRules.balance(invoice) > 0) PdfReport.AMBER else PdfReport.GREEN)))
        report.facts(listOf("Received from" to patient.optString("name"), "Phone" to patient.optString("phone"), "For" to invoice.optString("description"),
            "Reference" to payment.optString("reference"), "Invoice total" to money(ClinicalRules.invoiceTotal(invoice), currency),
            "Paid to date" to money(ClinicalRules.paid(invoice) - ClinicalRules.refunded(invoice), currency)))
        report.space(12f)
        report.text("Thank you for your payment.", 9f, PdfReport.MUTED)
        report.writeTo(output)
    }

    /** "Month · 1 Nov 2026 – 30 Nov 2026" style label for a report range. */
    fun rangeLabel(period: String, range: LongRange, config: JSONObject): String =
        if (range.first == Long.MIN_VALUE) "All dates" else "$period · ${day(range.first, config)} – ${day(range.last, config)}"
}
