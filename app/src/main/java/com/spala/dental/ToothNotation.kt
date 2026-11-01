package com.spala.dental

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Display numbering for teeth. Stored chart keys are always canonical FDI ("16"); this only
 * changes what clinicians read on screen (PRD 5.5: a UI numbering preference never rewrites keys).
 *
 * - Palmer (Zsigmondy): 1–8 / A–E within each quadrant, e.g. upper right first molar = UR6.
 * - FDI (ISO 3950): quadrant + position, e.g. 16.
 * - Universal: 1–32 / A–T around the mouth, e.g. upper right first molar = #3.
 */
object ToothNotation {
    const val PALMER = "palmer"
    const val FDI = "fdi"
    const val UNIVERSAL = "universal"
    val all = listOf(PALMER, FDI, UNIVERSAL)

    var current by mutableStateOf(PALMER)
        private set

    fun load(context: Context) {
        current = context.getSharedPreferences("display", Context.MODE_PRIVATE).getString("toothNotation", PALMER)
            ?.takeIf { it in all } ?: PALMER
    }

    fun set(context: Context, value: String) {
        if (value !in all) return
        current = value
        context.getSharedPreferences("display", Context.MODE_PRIVATE).edit().putString("toothNotation", value).apply()
    }

    fun title(system: String) = when (system) { FDI -> "FDI (16)"; UNIVERSAL -> "Universal (#3)"; else -> "Palmer (UR6)" }

    private fun valid(key: String) = key.length == 2 && key[0] in '1'..'8' && key[1].isDigit() &&
        key[1].digitToInt() in 1..(if (key[0] >= '5') 5 else 8)

    private fun quadrantPrefix(quadrant: Int) = when (quadrant) { 1, 5 -> "UR"; 2, 6 -> "UL"; 3, 7 -> "LL"; else -> "LR" }

    /** Short form drawn on a tooth: "6", "E", "16" or "3". */
    fun short(key: String, system: String = current): String {
        if (!valid(key)) return key
        val quadrant = key[0].digitToInt(); val position = key[1].digitToInt()
        return when (system) {
            FDI -> key
            UNIVERSAL -> universal(quadrant, position)
            else -> if (quadrant >= 5) ('A' + position - 1).toString() else position.toString()
        }
    }

    /** Unambiguous form for lists and titles: "UR6", "16" or "#3". */
    fun label(key: String, system: String = current): String {
        if (!valid(key)) return key
        val quadrant = key[0].digitToInt()
        return when (system) {
            FDI -> key
            UNIVERSAL -> "#" + short(key, UNIVERSAL)
            else -> quadrantPrefix(quadrant) + short(key, PALMER)
        }
    }

    /** Title + the canonical FDI reference, e.g. "UR6 · Upper right first molar (FDI 16)". */
    fun describe(key: String, system: String = current): String =
        if (system == FDI) "${label(key, system)} · ${toothName(key)}" else "${label(key, system)} · ${toothName(key)} (FDI $key)"

    private fun universal(quadrant: Int, position: Int): String = when (quadrant) {
        1 -> (9 - position).toString()
        2 -> (8 + position).toString()
        3 -> (25 - position).toString()
        4 -> (24 + position).toString()
        5 -> ('A' + (5 - position)).toString()
        6 -> ('E' + position).toString()
        7 -> ('K' + (5 - position)).toString()
        else -> ('O' + position).toString()
    }

    /** FDI keys in clinical reading order: upper right → upper left, lower left → lower right. */
    fun orderIndex(key: String): Int {
        if (!valid(key)) return 999
        val quadrant = key[0].digitToInt(); val position = key[1].digitToInt()
        val primary = quadrant >= 5
        val base = when ((quadrant - 1) % 4) { 0 -> 0; 1 -> 1; 2 -> 2; else -> 3 }
        val within = if (base == 0 || base == 2) 10 - position else position
        return (if (primary) 100 else 0) + base * 20 + within
    }
}
