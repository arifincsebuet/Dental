package com.spala.dental

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.spala.dental.ui.theme.*

/*
 * Navigation drawers. The drawer opens from the ☰ button only (swipe-to-open is disabled so
 * dragging the 3D model never pulls it out); Back or a tap outside closes it.
 */

private val activeAppointmentStates = setOf("Requested", "Scheduled", "Confirmed", "Checked in", "In progress")

/** Active appointments starting between now and [days] days ahead (clinic time). */
fun upcomingAppointments(patients: List<org.json.JSONObject>, config: org.json.JSONObject, days: Int = 7): Int {
    val now = System.currentTimeMillis(); val end = now + days * 86_400_000L
    return patients.filterNot { it.optBoolean("archived") }.sumOf { patient ->
        patient.optJSONArray("appointments").objectList().count { a ->
            a.optString("status") in activeAppointmentStates &&
                (runCatching { ClinicalRules.appointmentMillis(a, config) }.getOrNull() ?: -1L) in now until end
        }
    }
}

/** ☰ button with a notification dot/count for upcoming appointments. */
@Composable
fun MenuButton(badgeCount: Int = 0, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.semantics {
        contentDescription = if (badgeCount > 0) "Open menu, $badgeCount upcoming appointments" else "Open menu"
    }) {
        BadgedBox(badge = { if (badgeCount > 0) Badge(containerColor = Rose600, contentColor = Color.White) { Text(if (badgeCount > 99) "99+" else "$badgeCount") } }) {
            GlyphIcon(Glyph.Menu, Ink, 24.dp)
        }
    }
}

@Composable
private fun DrawerHeader(title: String, subtitle: String, pill: String?, initialsOf: String) {
    Box(Modifier.fillMaxWidth().padding(12.dp).clip(RoundedCornerShape(24.dp)).background(Brush.linearGradient(listOf(Teal600, HeroDeep)))) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Avatar(initialsOf, 48.dp, listOf(Color.White.copy(alpha = .30f), Color.White.copy(alpha = .15f)))
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = .8f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (pill != null) Box(Modifier.clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = .18f)).padding(horizontal = 10.dp, vertical = 4.dp)) {
                Text(pill, color = Color.White, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun DrawerSection(label: String) {
    Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = InkSubtle, modifier = Modifier.padding(start = 28.dp, top = 14.dp, bottom = 6.dp))
}

@Composable
private fun DrawerEntry(label: String, glyph: Glyph, selected: Boolean = false, tint: Color = Teal700, badge: String? = null, alert: Int = 0, onClick: () -> Unit) {
    NavigationDrawerItem(
        label = { Text(label, style = MaterialTheme.typography.labelLarge) },
        icon = { GlyphIcon(glyph, if (selected) Teal700 else tint, 22.dp) },
        // A count needing attention shows as a filled notification badge; otherwise plain info text.
        badge = if (alert > 0) { { Badge(containerColor = Rose600, contentColor = Color.White) { Text(if (alert > 99) "99+" else "$alert", modifier = Modifier.semantics { contentDescription = "$alert upcoming" }) } } }
            else if (badge != null) { { Text(badge, style = MaterialTheme.typography.labelSmall, color = InkMuted) } } else null,
        selected = selected, onClick = onClick,
        colors = NavigationDrawerItemDefaults.colors(selectedContainerColor = Teal50, selectedTextColor = Teal700, unselectedTextColor = Ink),
        modifier = Modifier.padding(horizontal = 12.dp),
    )
}

/** Clinic team drawer. [onAction] receives: register, book, sync, notify, howto, signout. */
@Composable
fun StaffDrawerContent(store: ClinicStore, currentPage: String, onNavigate: (String) -> Unit, onAction: (String) -> Unit) {
    val email = runCatching { com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.email }.getOrNull()
    val role = when {
        store.isSuperAdmin -> "Super admin"
        store.session == "local" -> "Offline · this device"
        else -> store.currentRole.replaceFirstChar { it.uppercase() }
    }
    val admin = store.isSuperAdmin || store.currentRole in listOf("owner", "admin", "administrator")
    val clinicName = store.config.optString("name").ifBlank { "Dental Care" }
    ModalDrawerSheet(drawerContainerColor = SurfaceWhite, modifier = Modifier.widthIn(max = 320.dp)) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
            DrawerHeader(clinicName, email ?: "Signed in on this device", role, email ?: clinicName)
            DrawerSection("Clinic")
            DrawerEntry("Home", Glyph.Home, currentPage == "Home") { onNavigate("Home") }
            DrawerEntry("Patients", Glyph.Person, currentPage == "Patients", badge = store.patients.count { !it.optBoolean("archived") }.toString()) { onNavigate("Patients") }
            DrawerEntry("Appointments", Glyph.Calendar, currentPage == "Schedule", alert = upcomingAppointments(store.patients, store.config)) { onNavigate("Schedule") }
            DrawerEntry("Reports", Glyph.Bars, currentPage == "Reports") { onNavigate("Reports") }
            DrawerSection("Quick actions")
            DrawerEntry("Register patient", Glyph.Plus) { onAction("register") }
            DrawerEntry("Book appointment", Glyph.Calendar, tint = Blue600) { onAction("book") }
            val pending = store.patients.count { it.optBoolean("dirty") }
            DrawerEntry("Sync now", Glyph.Sync, badge = if (pending > 0) "$pending pending" else null) { onAction("sync") }
            if (admin) {
                DrawerSection("Administration")
                DrawerEntry("Notify patients", Glyph.Alert, tint = Amber600) { onAction("notify") }
                DrawerEntry("Clinic settings & staff", Glyph.Shield) { onNavigate("Settings") }
                DrawerEntry("Subscription", Glyph.Card, badge = if (store.isSuperAdmin) "Free" else if (store.subscriptionActive) "Active" else null) { onNavigate("Settings") }
            }
            DrawerSection("Help")
            DrawerEntry("How to use", Glyph.Help) { onAction("howto") }
            DrawerEntry("Settings", Glyph.Gear, currentPage == "Settings") { onNavigate("Settings") }
            HorizontalDivider(Modifier.padding(horizontal = 24.dp, vertical = 12.dp), color = Hairline)
            DrawerEntry("Sign out", Glyph.Logout, tint = Rose600) { onAction("signout") }
        }
    }
}

/** Patient drawer. [onSection] receives: teeth, treatments, appointments, book, billing. [onAction]: password, refresh, signout. */
@Composable
fun PatientDrawerContent(store: ClinicStore, onSection: (String) -> Unit, onAction: (String) -> Unit) {
    val record = store.portalRecord
    val name = record?.optString("name").orEmpty().ifBlank { "Patient" }
    val loginId = runCatching { com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.email?.substringBefore('@')?.uppercase() }.getOrNull()
    ModalDrawerSheet(drawerContainerColor = SurfaceWhite, modifier = Modifier.widthIn(max = 320.dp)) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
            DrawerHeader(name, store.portalClinic.optString("name").ifBlank { "Dental Care" }, loginId?.let { "Patient ID $it" }, name)
            DrawerSection("My care")
            DrawerEntry("My teeth (3D)", Glyph.Tooth) { onSection("teeth") }
            DrawerEntry("Treatment history", Glyph.Clipboard) { onSection("treatments") }
            DrawerEntry("My appointments", Glyph.Calendar, alert = record?.let { upcomingAppointments(listOf(it), store.portalClinic, days = 365) } ?: 0) { onSection("appointments") }
            DrawerEntry("Book appointment", Glyph.Plus, tint = Blue600) { onSection("book") }
            if ((record?.optJSONArray("invoices")?.length() ?: 0) > 0) DrawerEntry("Billing", Glyph.Card) { onSection("billing") }
            DrawerSection("Account")
            DrawerEntry("Change password", Glyph.Lock) { onAction("password") }
            DrawerEntry("Refresh my record", Glyph.Sync) { onAction("refresh") }
            HorizontalDivider(Modifier.padding(horizontal = 24.dp, vertical = 12.dp), color = Hairline)
            DrawerEntry("Sign out", Glyph.Logout, tint = Rose600) { onAction("signout") }
        }
    }
}
