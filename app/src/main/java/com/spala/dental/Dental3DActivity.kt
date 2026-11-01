package com.spala.dental

import android.app.Activity
import android.content.Intent
import org.json.JSONArray
import org.json.JSONObject
import org.godotengine.godot.GodotActivity
import org.godotengine.godot.Godot
import org.godotengine.godot.plugin.GodotPlugin
import org.godotengine.godot.plugin.UsedByGodot

class Dental3DActivity : GodotActivity()

/** The renderer sees only the current chart, never profiles, notes or credentials. */
class DentalBridge(godot: Godot) : GodotPlugin(godot) {
    override fun getPluginName() = "DentalBridge"
    private val normalized by lazy { normalize(activity?.intent?.getStringExtra("chart")) }
    @UsedByGodot fun chart(): String = normalized.toString()
    @UsedByGodot fun selectTooth(key: String) {
        finishSelection(key, "")
    }
    @UsedByGodot fun selectSurface(key: String, surface: String) {
        finishSelection(key, surface)
    }
    private fun finishSelection(key: String, surface: String) {
        if (normalized.optBoolean("readOnly") || activity?.isFinishing == true) return
        if (normalized.optInt("schemaVersion") != 2 || !validKey(key, normalized.optString("dentition"))) return
        if (surface.isNotBlank() && surface !in surfaces(key)) return
        activity?.runOnUiThread {
            activity?.setResult(Activity.RESULT_OK, Intent().putExtra("tooth", key).putExtra("surface", surface)
                .putExtra("sessionToken", normalized.optString("sessionToken"))
                .putExtra("chartVersion", normalized.optLong("chartVersion")))
            activity?.finish()
        }
    }
    @UsedByGodot fun closeChart() { activity?.runOnUiThread { activity?.finish() } }

    private fun validKey(key: String, dentition: String): Boolean {
        if (!Regex("[1-4][1-8]|[5-8][1-5]").matches(key)) return false
        return when (dentition) {
            "permanent" -> key[0] in '1'..'4'
            "primary" -> key[0] in '5'..'8'
            "mixed" -> true
            else -> false
        }
    }

    private fun surfaces(key: String): Set<String> = setOf("M", "D", "F", "L", if (key[1] <= '3') "I" else "O")

    /** Whitelisting prevents narrative/profile fields accidentally entering the engine. */
    private fun normalize(raw: String?): JSONObject = runCatching {
        require(raw != null && raw.toByteArray(Charsets.UTF_8).size <= 64 * 1024)
        val input = JSONObject(raw)
        require(input.optInt("schemaVersion") == 2)
        val dentition = input.optString("dentition")
        require(dentition in setOf("permanent", "primary", "mixed"))
        val incomingTeeth = input.getJSONObject("teeth")
        require(incomingTeeth.length() <= 52)
        val outputTeeth = JSONObject()
        for (key in incomingTeeth.keys()) {
            require(validKey(key, dentition))
            val source = incomingTeeth.getJSONObject(key)
            val tooth = JSONObject()
                .put("assessment", checkedEnum(source, "assessment", assessment, "not_assessed"))
                .put("presence", checkedEnum(source, "presence", presence, "unknown"))
            if (source.optBoolean("treated")) tooth.put("treated", true)
            if (source.has("replacement")) tooth.put("replacement", checkedEnum(source, "replacement", replacements, "none"))
            if (source.has("findings")) tooth.put("findings", codes(source.getJSONArray("findings"), findings))
            if (source.has("treatmentStates")) tooth.put("treatmentStates", codes(source.getJSONArray("treatmentStates"), workflow))
            if (source.has("surfaces")) {
                val outputSurfaces = JSONObject()
                val inputSurfaces = source.getJSONObject("surfaces")
                require(inputSurfaces.length() <= 5)
                for (surface in inputSurfaces.keys()) {
                    require(surface in surfaces(key))
                    val observation = inputSurfaces.getJSONObject(surface)
                    outputSurfaces.put(surface, JSONObject()
                        .put("assessment", checkedEnum(observation, "assessment", assessment, "not_assessed"))
                        .put("findings", codes(observation.optJSONArray("findings") ?: JSONArray(), findings))
                        .put("restorations", codes(observation.optJSONArray("restorations") ?: JSONArray(), restorations)))
                }
                tooth.put("surfaces", outputSurfaces)
            }
            outputTeeth.put(key, tooth)
        }
        require(input.optString("catalogVersion", "fdi-v1") == "fdi-v1")
        val session = input.optString("sessionToken", "")
        require(session.length <= 128 && session.all { it.isLetterOrDigit() || it == '-' || it == '_' })
        val version = input.optLong("chartVersion", 0L)
        require(version >= 0)
        JSONObject().put("schemaVersion", 2).put("dentition", dentition).put("teeth", outputTeeth)
            .put("catalogVersion", "fdi-v1").put("chartVersion", version)
            .put("sessionToken", session).put("readOnly", input.optBoolean("readOnly", false))
            .put("sounds", input.optBoolean("sounds", true))
            .put("notation", input.optString("notation").takeIf { it in setOf("palmer", "fdi", "universal") } ?: "palmer")
    }.getOrElse { JSONObject().put("schemaVersion", 0).put("error", "unsupported_chart") }

    private fun checkedEnum(source: JSONObject, key: String, allowed: Set<String>, fallback: String): String {
        val value = source.optString(key, fallback)
        require(value in allowed)
        return value
    }
    private fun codes(source: JSONArray, allowed: Set<String>): JSONArray {
        require(source.length() <= allowed.size)
        val values = (0 until source.length()).map { source.getString(it) }
        require(values.distinct().size == values.size && values.all { it in allowed })
        return JSONArray(values)
    }
    private val assessment = setOf("not_assessed", "assessed", "unable_to_assess")
    private val presence = setOf("unknown", "present", "unerupted", "missing", "extracted", "exfoliated", "congenitally_absent")
    private val replacements = setOf("none", "implant", "pontic", "removable_prosthesis")
    private val findings = setOf("caries", "fracture", "wear", "endodontically_treated", "periapical_finding", "other")
    private val restorations = setOf("composite", "amalgam", "glass_ionomer", "sealant", "crown", "inlay", "onlay", "other")
    private val workflow = setOf("planned", "accepted", "in_progress", "deferred", "referred")
}
