package com.spala.dental

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.spala.dental.ui.theme.*
import org.json.JSONArray
import org.json.JSONObject

/*
 * Team & roles. The admin adds each staff member (name + Google email), picks a role, and can
 * switch individual access items on or off. A role only fills in the starting toggles; the toggles
 * are what the app and the Firestore rules enforce (firebase/firestore.rules → defaultRoles).
 *
 * Stored in the clinic document: config.staff[] {id, name, email, role, active, permissions{...}};
 * on save the app copies role + permissions into config.members[uid] for staff who have joined.
 */

data class Permission(val key: String, val title: String, val detail: String)

object Access {
    val items = listOf(
        Permission("viewClinical", "View charts & treatment history", "3D chart, findings, treatments, clinical notes"),
        Permission("recordTreatment", "Record & edit treatment", "Add or correct treatments and chart status"),
        Permission("managePatients", "Register & edit patients", "New patients, profile details"),
        Permission("manageAppointments", "Book & change appointments", "Book slots for patients, reschedule, cancel"),
        Permission("manageSlots", "Open & close appointment slots", "Doctors' available times for booking"),
        Permission("billing", "Invoices & payments", "Create invoices, take payments, refunds"),
        Permission("patientLogins", "Patient app logins", "Create Patient IDs and passwords"),
        Permission("reports", "Reports", "Clinic reports and exports"),
        Permission("manageTeam", "Clinic settings, team & roles", "Add staff, change roles and access (admin)"),
    )

    /** Role presets, matching defaultRoles() in firestore.rules. */
    val roles = listOf("admin", "dentist", "assistant", "receptionist", "cashier", "auditor")
    fun roleTitle(role: String) = when (role) { "admin", "administrator" -> "Admin"; "owner" -> "Owner"; else -> role.replaceFirstChar { it.uppercase() } }
    fun roleSummary(role: String) = when (role) {
        "admin", "administrator" -> "Everything, including team and settings"
        "dentist" -> "Clinical care, patients, appointments, slots, patient logins"
        "assistant" -> "Views charts, registers patients, books appointments"
        "receptionist" -> "Front desk: patients, appointments, slots, patient logins"
        "cashier" -> "Billing and reports"
        "auditor" -> "Read-only charts and reports"
        else -> ""
    }
    private val defaults = mapOf(
        "viewClinical" to setOf("admin", "administrator", "dentist", "assistant", "auditor"),
        "recordTreatment" to setOf("admin", "administrator", "dentist"),
        "managePatients" to setOf("admin", "administrator", "dentist", "assistant", "receptionist"),
        "manageAppointments" to setOf("admin", "administrator", "dentist", "assistant", "receptionist"),
        "manageSlots" to setOf("admin", "administrator", "dentist", "receptionist"),
        "billing" to setOf("admin", "administrator", "cashier"),
        "patientLogins" to setOf("admin", "administrator", "dentist", "receptionist"),
        "reports" to setOf("admin", "administrator", "dentist", "cashier", "auditor"),
        "manageTeam" to setOf("admin", "administrator"),
    )
    fun roleDefault(role: String, key: String) = role == "owner" || role in defaults[key].orEmpty()
    fun preset(role: String): JSONObject = JSONObject().also { out -> items.forEach { out.put(it.key, roleDefault(role, it.key)) } }

    /** A member's effective permission: an explicit toggle wins, otherwise the role default. */
    fun allowed(role: String, permissions: JSONObject?, key: String): Boolean =
        if (permissions != null && permissions.has(key)) permissions.optBoolean(key) else roleDefault(role, key) ||
            (key == "recordTreatment" && role == "assistant" && permissions?.optBoolean("clinicalWrite") == true) ||
            (key == "viewClinical" && role == "assistant" && permissions?.optBoolean("clinicalRead") == true)
}

/** Staff list page (Settings › Team & roles). */
@Composable
fun TeamScreen(config: JSONObject, canManage: Boolean, busy: Boolean, onBack: () -> Unit, onSave: (JSONObject, String) -> Unit) {
    var editing by remember { mutableStateOf<JSONObject?>(null) }
    editing?.let { person ->
        StaffEditorScreen(person, config, busy, onBack = { editing = null }, onSave = { updated, action -> onSave(updated, action); editing = null })
        return
    }
    BackHandler(onBack = onBack)
    val staff = clinicianRecords(config)
    TextButton(onClick = onBack, contentPadding = PaddingValues(horizontal = 4.dp)) {
        GlyphIcon(Glyph.Back, Teal700, 18.dp); Spacer(Modifier.width(4.dp)); Text("Settings")
    }
    Text("Team & roles", style = MaterialTheme.typography.headlineMedium, color = Ink)
    Text("Add your doctors and staff, choose their role, and switch each access item on or off. They sign in with Google using the email you enter.",
        style = MaterialTheme.typography.bodyMedium, color = InkMuted)
    if (canManage) Button(onClick = { editing = JSONObject().put("role", "dentist").put("active", true) }, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp)) {
        GlyphIcon(Glyph.Plus, androidx.compose.ui.graphics.Color.White, 16.dp); Spacer(Modifier.width(6.dp)); Text("Add staff member")
    } else SurfaceCard(background = SurfaceTint) { Text("Only an admin can change the team.", style = MaterialTheme.typography.bodySmall, color = InkMuted) }
    if (staff.isEmpty()) SurfaceCard(background = SurfaceTint) {
        Text("No staff yet", style = MaterialTheme.typography.titleSmall, color = Ink)
        Text("Add a doctor or receptionist to give them access.", style = MaterialTheme.typography.bodySmall, color = InkMuted)
    }
    staff.forEach { person ->
        val active = person.optBoolean("active", true)
        val role = person.optString("role", "dentist")
        val enabled = Access.items.count { Access.allowed(role, person.optJSONObject("permissions"), it.key) }
        SurfaceCard(onClick = if (canManage) ({ editing = JSONObject(person.toString()) }) else null, background = if (active) SurfaceWhite else SurfaceTint) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Avatar(person.optString("name").ifBlank { person.optString("email") }, 44.dp)
                Column(Modifier.weight(1f)) {
                    Text(person.optString("name").ifBlank { "Unnamed" }, style = MaterialTheme.typography.titleSmall, color = Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(person.optString("email").ifBlank { "No sign-in email" }, style = MaterialTheme.typography.bodySmall, color = InkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                StatusPill(Access.roleTitle(role), if (active) Teal50 else SurfaceTint, if (active) Teal700 else InkMuted, dot = false)
            }
            Text(if (active) "$enabled of ${Access.items.size} access items on" else "Inactive · cannot sign in", style = MaterialTheme.typography.bodySmall, color = InkMuted)
        }
    }
}

/** One staff member: details, role, and access toggles. Saved to the clinic and synced at once. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StaffEditorScreen(person: JSONObject, config: JSONObject, busy: Boolean, onBack: () -> Unit, onSave: (JSONObject, String) -> Unit) {
    BackHandler(onBack = onBack)
    val isNew = !person.has("id")
    var name by remember { mutableStateOf(person.optString("name")) }
    var email by remember { mutableStateOf(person.optString("email")) }
    var phone by remember { mutableStateOf(person.optString("phone")) }
    var registration by remember { mutableStateOf(person.optString("registration")) }
    var role by remember { mutableStateOf(person.optString("role", "dentist").let { if (it == "administrator" || it == "owner") "admin" else it }) }
    var active by remember { mutableStateOf(person.optBoolean("active", true)) }
    val toggles = remember { mutableStateMapOf<String, Boolean>().apply {
        Access.items.forEach { put(it.key, Access.allowed(role, person.optJSONObject("permissions"), it.key)) }
    } }
    var error by remember { mutableStateOf("") }

    TextButton(onClick = onBack, contentPadding = PaddingValues(horizontal = 4.dp)) {
        GlyphIcon(Glyph.Back, Teal700, 18.dp); Spacer(Modifier.width(4.dp)); Text("Team & roles")
    }
    Text(if (isNew) "Add staff member" else name.ifBlank { "Staff member" }, style = MaterialTheme.typography.headlineMedium, color = Ink)

    SectionHeader("Details")
    SurfaceCard {
        OutlinedTextField(name, { name = it }, label = { Text("Full name") }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp))
        OutlinedTextField(email, { email = it.trim() }, label = { Text("Google account email (for sign-in)") }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp),
            supportingText = { Text("They sign in with this Google account to join your clinic.") })
        OutlinedTextField(phone, { phone = it }, label = { Text("Phone (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp))
        OutlinedTextField(registration, { registration = it }, label = { Text("Professional registration (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Active", style = MaterialTheme.typography.titleSmall, color = Ink)
                Text(if (active) "Can sign in and work in the clinic" else "Access removed; history is kept", style = MaterialTheme.typography.bodySmall, color = InkMuted)
            }
            Switch(active, { active = it })
        }
    }

    SectionHeader("Role")
    Text("Choosing a role fills in the access below. You can then switch any item on or off.", style = MaterialTheme.typography.bodySmall, color = InkMuted)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Access.roles.forEach { option ->
            FilterChip(selected = role == option, onClick = {
                role = option
                Access.items.forEach { toggles[it.key] = Access.roleDefault(option, it.key) }
            }, label = { Text(Access.roleTitle(option)) })
        }
    }
    Text(Access.roleSummary(role), style = MaterialTheme.typography.bodySmall, color = Teal700)

    SectionHeader("Access")
    SurfaceCard(padding = 6.dp) {
        Access.items.forEachIndexed { index, item ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(item.title, style = MaterialTheme.typography.titleSmall, color = Ink, fontWeight = FontWeight.Medium)
                    Text(item.detail, style = MaterialTheme.typography.bodySmall, color = InkMuted)
                }
                Switch(toggles[item.key] == true, { toggles[item.key] = it })
            }
            if (index < Access.items.lastIndex) HorizontalDivider(Modifier.padding(horizontal = 12.dp), color = Hairline)
        }
    }
    if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    Button(enabled = !busy, onClick = {
        error = runCatching {
            require(name.isNotBlank()) { "Enter the staff member's name" }
            require(email.isBlank() || email.matches(Regex("[^@\\s]+@[^@\\s]+\\.[^@\\s]+"))) { "Enter a valid email" }
            val others = clinicianRecords(config).filter { it.optString("id") != person.optString("id") }
            require(email.isBlank() || others.none { it.optString("email").equals(email, true) }) { "Another staff member already uses this email" }
            val entry = JSONObject(person.toString()).put("id", person.optString("id").ifBlank { uid() }).put("name", name.trim()).put("email", email.lowercase())
                .put("phone", phone.trim()).put("registration", registration.trim()).put("role", role).put("active", active)
                .put("permissions", JSONObject().also { p -> Access.items.forEach { p.put(it.key, toggles[it.key] == true) } })
            val updated = JSONObject(config.toString())
            updated.put("staff", JSONArray(others + entry))
            onSave(updated, if (isNew) "Staff member added" else "Staff member updated")
        }.exceptionOrNull()?.localizedMessage.orEmpty()
    }, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text(if (isNew) "Add to team" else "Save changes") }
    if (!isNew) OutlinedButton(enabled = !busy, onClick = {
        val updated = JSONObject(config.toString())
        updated.put("staff", JSONArray(clinicianRecords(config).filter { it.optString("id") != person.optString("id") }))
        onSave(updated, "Staff member removed")
    }, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) { Text("Remove from team", color = Rose600) }
}
