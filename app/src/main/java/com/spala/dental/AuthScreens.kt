package com.spala.dental

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import kotlinx.coroutines.launch
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spala.dental.ui.theme.*
import org.json.JSONObject

// ---------------------------------------------------------------------------------------------
// Sign in
// ---------------------------------------------------------------------------------------------

@Composable
fun LoginScreen(store: ClinicStore) {
    val context = LocalContext.current
    var mode by rememberSaveable { mutableStateOf("Clinic team") }
    var loginId by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    Column(
        Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(HeroTop, AppBackground))).verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        DentalAppLogo(Modifier.size(72.dp))
        Text("Dental Care", style = MaterialTheme.typography.headlineMedium, color = Ink)
        Text("Clinic records, 3D dental charts and treatment history in one place.", style = MaterialTheme.typography.bodyMedium, color = InkMuted, textAlign = TextAlign.Center)
        SegmentedPills(listOf("Clinic team", "Patient"), mode, Modifier.fillMaxWidth()) { mode = it; error = "" }
        SurfaceCard {
            if (mode == "Clinic team") {
                Text("Doctors and staff", style = MaterialTheme.typography.titleMedium, color = Ink)
                Text("Sign in with the Google account your clinic administrator added in Settings › Team & roles. The first account to sign in creates a new clinic and becomes its administrator.",
                    style = MaterialTheme.typography.bodySmall, color = InkMuted)
                Button(
                    onClick = {
                        error = ""
                        val resource = context.resources.getIdentifier("default_web_client_id", "string", context.packageName)
                        if (!store.cloudConfigured || resource == 0) error = "Firebase is not configured in this build. Add app/src/main/res/values/firebase.xml (see firebase/firebase.xml.example)."
                        else store.staffGoogleSignIn(context, context.getString(resource))
                    },
                    enabled = !store.busy, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(16.dp),
                ) { Text("Continue with Google") }
                if (!store.cloudConfigured) OutlinedButton(onClick = { store.useLocally() }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                    Text("Use offline on this device")
                }
            } else {
                Text("Patients", style = MaterialTheme.typography.titleMedium, color = Ink)
                Text("Use the Patient ID and password given by your clinic.",
                    style = MaterialTheme.typography.bodySmall, color = InkMuted)
                OutlinedTextField(loginId, { loginId = it.uppercase().filter { c -> c.isLetterOrDigit() }.take(12) }, label = { Text("Patient ID") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters), modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp))
                OutlinedTextField(password, { password = it }, label = { Text("Password") }, singleLine = true, visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp))
                Button(onClick = { error = ""; store.patientSignIn(loginId, password) { problem -> error = problem.orEmpty(); if (problem == null) password = "" } },
                    enabled = !store.busy, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(16.dp)) { Text("Sign in") }
                Text("Forgot your password? Ask your clinic; they can set a new one.", style = MaterialTheme.typography.bodySmall, color = InkMuted)
            }
            if (store.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            if (store.message.isNotBlank() && error.isBlank()) Text(store.message, color = InkMuted, style = MaterialTheme.typography.bodySmall)
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Patient portal (read-only)
// ---------------------------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PatientPortalScreen(store: ClinicStore) {
    val record = store.portalRecord
    var focus by rememberSaveable { mutableStateOf<String?>(null) }
    var cancelling by remember { mutableStateOf<JSONObject?>(null) }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val scroll = rememberScrollState()
    val sections = remember { mutableStateMapOf<String, Int>() }
    fun mark(key: String) = Modifier.onGloballyPositioned { sections[key] = it.positionInParent().y.toInt() }
    BackHandler(enabled = drawerState.isOpen) { scope.launch { drawerState.close() } }
    // Ask once for notification permission so appointment reminders can appear (Android 13+).
    val context = LocalContext.current
    val notificationPermission = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(record != null) {
        val prefs = context.getSharedPreferences("display", android.content.Context.MODE_PRIVATE)
        if (record != null && android.os.Build.VERSION.SDK_INT >= 33 && !prefs.getBoolean("askedNotifications", false)) {
            prefs.edit().putBoolean("askedNotifications", true).apply()
            notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = drawerState.isOpen,
        drawerContent = {
            PatientDrawerContent(store, onSection = { key ->
                scope.launch { drawerState.close(); sections[key]?.let { scroll.animateScrollTo(it) } }
            }) { action ->
                scope.launch { drawerState.close() }
                when (action) {
                    "refresh" -> store.refreshMyRecord()
                    "signout" -> store.patientSignOut()
                }
            }
        },
    ) {
    Scaffold(containerColor = MaterialTheme.colorScheme.background, topBar = {
        TopAppBar(navigationIcon = { MenuButton(store.portalRecord?.let { upcomingAppointments(listOf(it), store.portalClinic, days = 365) } ?: 0) { scope.launch { drawerState.open() } } }, title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                DentalAppLogo(Modifier.size(36.dp))
                Column {
                    Text(store.portalClinic.optString("name").ifBlank { "Dental Care" }, style = MaterialTheme.typography.titleMedium, color = Ink)
                    Text("Patient view", style = MaterialTheme.typography.bodySmall, color = InkMuted)
                }
            }
        }, actions = { TextButton(onClick = { store.patientSignOut() }) { Text("Sign out") } },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = SurfaceWhite))
    }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(scroll).padding(horizontal = 18.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (record == null) {
                SurfaceCard {
                    Text("Loading your record…", style = MaterialTheme.typography.titleSmall, color = Ink)
                    if (store.message.isNotBlank()) Text(store.message, style = MaterialTheme.typography.bodySmall, color = InkMuted)
                    OutlinedButton(onClick = { store.refreshMyRecord() }, enabled = !store.busy) { Text("Try again") }
                }
                return@Column
            }
            val signature = record.toString() + ToothNotation.current
            val view = remember(signature) { displayChart(record).put("readOnly", true) }
            val records = remember(signature) { toothRecords(record) }
            Text("Hello, ${record.optString("name").substringBefore(' ')}", style = MaterialTheme.typography.headlineSmall, color = Ink)
            Teeth3DHeroCard(
                modifier = mark("teeth"),
                chart = view, title = "Your teeth", subtitle = "${records.count { it.completed > 0 }} treated · ${records.count { it.open > 0 }} planned",
                selectedTeeth = setOfNotNull(focus), onToothTap = { key -> focus = if (focus == key) null else key },
                banner = { HeroHint(focus?.let { "${ToothNotation.label(it)} · ${toothName(it)}" } ?: "Tap a tooth to see its treatment", highlight = focus != null) },
            ) {
                ChartLegendRow()
                Text("This view shows treatment recorded by your clinic. Ask your dentist about anything you don't understand.",
                    style = MaterialTheme.typography.bodySmall, color = InkMuted)
            }
            Spacer(mark("treatments"))
            ToothHistoryList(records, store.portalClinic, focus, canEdit = false,
                onToggle = { key -> focus = if (focus == key) null else key }, onRecord = {}, onEditStatus = {})
            val now = System.currentTimeMillis()
            val upcoming = record.optJSONArray("appointments").objectList()
                .filter { a -> a.optString("status") !in ClinicalRules.terminalAppointments && (runCatching { ClinicalRules.appointmentMillis(a, store.portalClinic) }.getOrNull() ?: 0L) >= now }
            SectionHeader("Upcoming appointments", mark("appointments"))
            if (store.portalPending > 0) SurfaceCard(background = Teal50) {
                Text("Sent to the clinic", style = MaterialTheme.typography.titleSmall, color = Teal700)
                Text("Your latest booking or cancellation is saved. The clinic's records update the next time they open the app.", style = MaterialTheme.typography.bodySmall, color = InkMuted)
            }
            if (upcoming.isEmpty()) SurfaceCard(background = SurfaceTint) { Text("No upcoming appointments.", style = MaterialTheme.typography.bodySmall, color = InkMuted) }
            upcoming.forEach { a ->
                val startAt = runCatching { ClinicalRules.appointmentMillis(a, store.portalClinic) }.getOrDefault(0L)
                SurfaceCard(padding = 14.dp) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(runCatching { ClinicalRules.displayTime(startAt, store.portalClinic) }.getOrDefault(a.optString("date")), style = MaterialTheme.typography.titleSmall, color = Ink)
                            Text(listOf(a.optString("doctor"), a.optString("reason")).filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = InkMuted)
                        }
                        if (startAt - now >= 2 * 3_600_000L) TextButton(onClick = { cancelling = a }, enabled = !store.busy) { Text("Cancel", color = Rose600) }
                    }
                }
            }
            Spacer(mark("book"))
            PatientBookingSection(store)
            val invoices = record.optJSONArray("invoices").objectList()
            if (invoices.isNotEmpty()) {
                val currency = store.portalClinic.optString("currency", "BDT")
                val due = invoices.filter { it.optString("status").lowercase() !in setOf("void", "voided") }
                    .sumOf { runCatching { ClinicalRules.invoiceTotal(it) - (ClinicalRules.paid(it) - ClinicalRules.refunded(it)) }.getOrDefault(0L) }
                SectionHeader("Billing", mark("billing"))
                SurfaceCard { InfoRow(Glyph.Clipboard, "Balance due", runCatching { ClinicalRules.money(due, currency) }.getOrDefault("$due")) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { store.refreshMyRecord() }, enabled = !store.busy, modifier = Modifier.weight(1f), shape = RoundedCornerShape(14.dp)) { Text("Refresh") }
                OutlinedButton(onClick = { store.patientSignOut() }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(14.dp)) { Text("Sign out") }
            }
        }
    }
    } // ModalNavigationDrawer
    cancelling?.let { appointment ->
        AlertDialog(onDismissRequest = { cancelling = null }, title = { Text("Cancel appointment?") },
            text = { Text("${appointment.optString("date")} with ${appointment.optString("doctor")}. The slot becomes free for others.") },
            confirmButton = { TextButton(onClick = { store.cancelMyAppointment(appointment.optString("id")) { cancelling = null } }) { Text("Cancel appointment", color = Rose600) } },
            dismissButton = { TextButton(onClick = { cancelling = null }) { Text("Keep") } })
    }
}

// ---------------------------------------------------------------------------------------------
// Admin: patient app access
// ---------------------------------------------------------------------------------------------

@Composable
fun PatientAccessCard(patient: JSONObject, store: ClinicStore) {
    val portal = patient.optJSONObject("portal")
    var loginDialog by remember { mutableStateOf<Boolean?>(null) } // false = create, true = reset
    SurfaceCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(Teal50), contentAlignment = Alignment.Center) { GlyphIcon(Glyph.Person, Teal700, 20.dp) }
            Column(Modifier.weight(1f)) {
                Text("Patient app access", style = MaterialTheme.typography.titleSmall, color = Ink)
                Text(if (portal == null) "No login yet. Set a Patient ID and password so the patient can see their 3D chart, history and book appointments."
                    else "Patient ID ${portal.optString("loginId")} · can view records and book",
                    style = MaterialTheme.typography.bodySmall, color = InkMuted)
            }
        }
        var problem by remember { mutableStateOf("") }
        if (!store.can("patientLogins")) Text("Ask your admin for \"Patient app logins\" access to manage this.", style = MaterialTheme.typography.bodySmall, color = InkMuted)
        else if (!store.cloudConfigured) Text("Available when the app is connected to the clinic cloud.", style = MaterialTheme.typography.bodySmall, color = InkMuted)
        else if (portal == null) Button(onClick = { loginDialog = false }, enabled = !store.busy, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
            Text("Create patient login")
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { loginDialog = true }, enabled = !store.busy, shape = RoundedCornerShape(14.dp), modifier = Modifier.weight(1f)) {
                    Text("Change ID / password")
                }
                TextButton(onClick = { store.removePatientLogin(patient.optString("id")) { problem = it.orEmpty() } }, enabled = !store.busy) { Text("Turn off", color = Rose600) }
            }
        }
        if (problem.isNotBlank()) Text(problem, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        if (store.cloudConfigured && (patient.optBoolean("dirty") || patient.optLong("remoteVersion") == 0L))
            Text("Unsynced changes to this patient are saved to the cloud when you create the login.", style = MaterialTheme.typography.bodySmall, color = InkMuted)
    }
    loginDialog?.let { reset -> PatientLoginDialog(patient, store, reset) { loginDialog = null } }
}

/** Admin chooses the Patient ID and password (suggestions are prefilled and can be changed). */
@Composable
fun PatientLoginDialog(patient: JSONObject, store: ClinicStore, reset: Boolean, onClose: () -> Unit) {
    var loginId by remember { mutableStateOf(patient.optJSONObject("portal")?.optString("loginId")?.ifBlank { null } ?: ClinicCloud.suggestLoginId()) }
    var password by remember { mutableStateOf(ClinicCloud.suggestPassword()) }
    var showPassword by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }
    val idProblem = if (loginId.isNotEmpty() && !ClinicCloud.validLoginId(loginId)) "4–12 letters or numbers" else null
    val passwordProblem = password.takeIf { it.isNotEmpty() }?.let { ClinicCloud.passwordProblem(it) }
    AlertDialog(
        onDismissRequest = { if (!store.busy) onClose() }, shape = RoundedCornerShape(28.dp),
        title = { Column {
            Text(if (reset) "Change Patient ID / password" else "Create patient login")
            Text(patient.optString("name"), style = MaterialTheme.typography.bodySmall, color = InkMuted)
        } },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if (reset) "Set the Patient ID and a new password. The old password stops working at once."
                    else "Choose a simple Patient ID and password for the patient. Suggestions are filled in; you can type your own.",
                    style = MaterialTheme.typography.bodySmall, color = InkMuted)
                OutlinedTextField(loginId, { loginId = it.filter { c -> c.isLetterOrDigit() }.take(12).uppercase(); error = "" },
                    label = { Text("Patient ID") }, singleLine = true, isError = idProblem != null,
                    supportingText = { Text(idProblem ?: "4–12 letters or numbers, e.g. RAHIM01") },
                    trailingIcon = { TextButton(onClick = { loginId = ClinicCloud.suggestLoginId() }) { Text("New") } },
                    modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp))
                OutlinedTextField(password, { password = it.take(64); error = "" },
                    label = { Text("Password") }, singleLine = true, isError = passwordProblem != null,
                    visualTransformation = if (showPassword) androidx.compose.ui.text.input.VisualTransformation.None else androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    supportingText = { Text(passwordProblem ?: "At least 6 characters. Share it only with the patient.") },
                    trailingIcon = { TextButton(onClick = { showPassword = !showPassword }) { Text(if (showPassword) "Hide" else "Show") } },
                    modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp))
                TextButton(onClick = { password = ClinicCloud.suggestPassword() }) { Text("Suggest another password") }
                if (store.busy) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Text("Saving login…", style = MaterialTheme.typography.bodySmall)
                }
                if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            Button(enabled = !store.busy && ClinicCloud.validLoginId(loginId) && ClinicCloud.passwordProblem(password) == null, onClick = {
                error = ""
                store.createPatientLogin(patient.optString("id"), loginId, password, reset) { problem -> if (problem == null) onClose() else error = problem }
            }) { Text(if (reset) "Save new login" else "Create login") }
        },
        dismissButton = { TextButton(enabled = !store.busy, onClick = onClose) { Text("Cancel") } },
    )
}

/** Shows the one-time credentials so the admin can hand them to the patient. Never stored on the device. */
@Composable
fun CredentialDialog(notice: JSONObject, onClose: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val text = "Dental Care patient login for ${notice.optString("name")}\nPatient ID: ${notice.optString("loginId")}\nTemporary password: ${notice.optString("password")}\nOpen the Dental Care app, choose Patient, and sign in."
    AlertDialog(
        onDismissRequest = {}, shape = RoundedCornerShape(28.dp),
        title = { Text(if (notice.optBoolean("reset")) "Patient login changed" else "Patient login created") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Share these with ${notice.optString("name")}. The password is shown only once." +
                    if (notice.optBoolean("reset")) " The previous password no longer works." else "", style = MaterialTheme.typography.bodySmall, color = InkMuted)
                listOf("Patient ID" to notice.optString("loginId"), "Temporary password" to notice.optString("password")).forEach { (label, value) ->
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(SurfaceTint).padding(12.dp)) {
                        Text(label, style = MaterialTheme.typography.labelSmall, color = InkMuted)
                        Text(value, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 20.sp, color = Ink)
                    }
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { clipboard.setText(AnnotatedString(text)) }) { Text("Copy") }
                TextButton(onClick = { context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Share login")) }) { Text("Share") }
                Button(onClick = onClose) { Text("Done") }
            }
        },
    )
}
