package com.spala.dental

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.tasks.await
import org.json.*
import java.text.SimpleDateFormat
import java.util.*

private val Teal = Color(0xFF0F766E)
private val Blue = Color(0xFF2563EB)
private val Mint = Color(0xFFF0FDFA)
private val Slate = Color(0xFF1E293B)
private fun date(value: Long) = SimpleDateFormat("dd MMM yyyy · HH:mm", Locale.getDefault()).format(Date(value))

class MainActivity : ComponentActivity() {
    private val store by lazy { (application as ClinicApplication).store }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = Teal, secondary = Blue, background = Mint, surface = Color.White, onSurface = Slate)) {
                CompositionLocalProvider(LocalEditorSaveStatus provides { EditorSaveStatus(store.saveSequence, store.saveError) }) {
                    ClinicApp(store)
                }
            }
        }
    }
}

private data class Field(val key: String, val label: String, val value: String = "", val required: Boolean = false)
private data class Form(val title: String, val fields: List<Field>, val save: (Map<String, String>) -> Unit)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ClinicApp(store: ClinicStore) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val saveStatus = LocalEditorSaveStatus.current
    var showHowTo by rememberSaveable { mutableStateOf(false) }
    var page by rememberSaveable { mutableStateOf("Home") }
    var patientId by rememberSaveable { mutableStateOf<String?>(null) }
    val patient = store.patients.firstOrNull { it.optString("id") == patientId }
    var tab by rememberSaveable { mutableStateOf("Overview") }
    var search by remember { mutableStateOf("") }
    var showArchived by remember { mutableStateOf(false) }
    var form by remember { mutableStateOf<Form?>(null) }
    var tooth by remember { mutableStateOf<String?>(null) }
    var chartPatientId by rememberSaveable { mutableStateOf<String?>(null) }
    var chartToken by rememberSaveable { mutableStateOf("") }
    var toothSurface by remember { mutableStateOf("") }
    val chartLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK && patientId == chartPatientId) {
            val token = result.data?.getStringExtra("sessionToken")
            if (token == chartToken && result.data?.getLongExtra("chartVersion", -1) == store.patients.firstOrNull { it.optString("id") == patientId }?.optJSONObject("chart")?.optLong("chartVersion")) { tooth = result.data?.getStringExtra("tooth"); toothSurface = result.data?.getStringExtra("surface").orEmpty() }
        }
    }
    var explanation by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var error by remember { mutableStateOf("") }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        scope.launch {
            snapshotFlow { store.busy }.first { !it }
            if (uri != null) runCatching {
                val text = store.pendingExport.getString("text")
                withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray(Charsets.UTF_8)) } ?: throw IllegalStateException("Cannot open export") }
                store.clearExport()
            }.onFailure { error = it.localizedMessage ?: "Export failed; encrypted draft retained" }
            else store.clearExport()
        }
    }
    val pdfExporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        scope.launch {
            snapshotFlow { store.busy }.first { !it }
            if (uri != null) runCatching {
                val snapshot = store.pendingExport.getJSONObject("patient"); val settings = store.pendingExport.getJSONObject("config")
                withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri)?.use { ClinicExports.patientPdf(snapshot, settings, it) } ?: throw IllegalStateException("Cannot open PDF export") }
                store.clearExport()
            }.onFailure { error = it.localizedMessage ?: "PDF export failed; encrypted draft retained" }
            else store.clearExport()
        }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch { runCatching {
            val raw = withContext(Dispatchers.IO) { context.contentResolver.openInputStream(uri)?.use { input -> val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192); var count = input.read(buffer); while (count >= 0) { out.write(buffer, 0, count); require(out.size() <= 650_000) { "Imported file is too large" }; count = input.read(buffer) }; val bytes = out.toByteArray(); require(bytes.size <= 650_000) { "Imported file is too large" }; String(bytes, Charsets.UTF_8) } ?: throw IllegalStateException("Cannot open import") }
            store.importPatient(raw)
        }.onFailure { error = it.localizedMessage ?: "Import failed" } }
    }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> if (!granted) error = "Notifications are disabled in Android settings. Appointments remain available in Schedule." }
    var imagePatientId by rememberSaveable { mutableStateOf<String?>(null) }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> if (uri != null && imagePatientId != null) store.attach(imagePatientId!!, uri) }
    fun addPatient() { if (store.currentRole in listOf("cashier", "auditor", "unknown")) { error = "Your current role cannot register patients"; return }; form = Form("New patient", listOf(Field("name", "Full name", required = true), Field("phone", "Phone"), Field("dob", "Date of birth (YYYY-MM-DD)"), Field("allergies", "Allergies / medical alerts"))) { values -> store.create(values["name"]!!, values["phone"]!!, values["dob"]!!, values["allergies"]!!) } }
    fun editRecord(p: JSONObject, action: String, edit: (JSONObject) -> Unit) { val copy = JSONObject(p.toString()); edit(copy); store.save(copy, action) }
    if (showHowTo) {
        BackHandler { showHowTo = false }
        HowToUseScreen(onBack = { showHowTo = false })
        return
    }
    Scaffold(containerColor = Mint, topBar = {
        TopAppBar(title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                DentalAppLogo(Modifier.size(40.dp))
                Column { Text("DENTAL CARE", fontSize = 19.sp, fontWeight = FontWeight.Bold, color = Teal); Text("Your clinic, connected", fontSize = 12.sp) }
            }
        }, actions = { if (store.busy) CircularProgressIndicator(Modifier.size(24.dp)); TextButton(onClick = { store.sync() }, enabled = !store.busy) { Text("Sync") } }, colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.White))
    }, bottomBar = {
        NavigationBar(containerColor = Color.White) { listOf("Home", "Patients", "Schedule", "Reports", "Settings").forEachIndexed { index, name -> NavigationBarItem(selected = page == name && patient == null, onClick = { page = name; patientId = null }, icon = { ClinicNavIcon(index) }, label = { Text(name, fontSize = 10.sp) }) } }
    }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (store.message.isNotBlank()) Surface(color = Color(0xFFDCFCE7), shape = RoundedCornerShape(12.dp)) { Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) { Text(store.message, Modifier.weight(1f)); TextButton(onClick = { store.clearMessage() }) { Text("Dismiss") } } }
            if (patient != null) {
                val p = patient
                val patientTabs = if (p.has("chart")) listOf("Overview", "Chart", "Encounters", "Treatments", "Billing", "History") else if (p.has("invoices")) listOf("Overview", "Billing") else listOf("Overview")
                TextButton(onClick = { patientId = null }) { Text("← Patients") }
                Heading(p.optString("name"), "${p.optString("phone").ifBlank { "No phone" }} • ${if (p.optBoolean("dirty")) "Device saved · sync pending" else "Cloud synchronized"}")
                if (p.has("operationId")) OutlinedButton(onClick = { store.resolveConflict(p.optString("id")) }, enabled = !store.busy) { Text("Review conflict · retain draft and load server version") }
                if (p.optString("allergies").isNotBlank()) CardBlock("Medical alerts", p.optString("allergies"), Color(0xFFFFF1F2))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) { patientTabs.forEach { name -> FilterChip(selected = tab == name, onClick = { tab = name }, label = { Text(name) }) } }
                when (tab.takeIf { it in patientTabs } ?: "Overview") {
                    "Overview" -> {
                        CardBlock("Patient profile", "Date of birth: ${p.optString("dob").ifBlank { "Not recorded" }}\nPhone: ${p.optString("phone").ifBlank { "Not recorded" }}")
                        OutlinedButton(onClick = {
                            val fields = mutableListOf(Field("name", "Full name", p.optString("name"), true), Field("phone", "Phone", p.optString("phone")), Field("dob", "Date of birth (YYYY-MM-DD)", p.optString("dob")))
                            if (p.has("allergies")) fields.add(Field("allergies", "Medical alerts", p.optString("allergies")))
                            form = Form("Edit profile", fields) { values -> editRecord(p, "Profile amended") { changed -> values.forEach { (key, value) -> changed.put(key, value) } } }
                        }) { Text("Edit profile") }
                        if (p.has("story")) {
                        CardBlock("Clinical story", p.optString("story").ifBlank { "Add complaint, medical context, examination notes, and care goals." })
                        Button(onClick = { form = Form("Clinical story", listOf(Field("story", "Clinical narrative", p.optString("story")))) { v -> editRecord(p, "Clinical story amended") { it.put("story", v["story"]) } } }) { Text("Update clinical story") }
                        }
                        OutlinedButton(onClick = { form = Form(if (p.optBoolean("archived")) "Restore archived patient" else "Archive patient", listOf(Field("reason", "Reason", required = true))) { values -> editRecord(p, "Patient archive state amended: ${values["reason"]}") { it.put("archived", !p.optBoolean("archived")) } } }) { Text(if (p.optBoolean("archived")) "Restore patient" else "Archive patient") }
                        Text("Appointments", fontWeight = FontWeight.Bold)
                        p.optJSONArray("appointments")?.objects().orEmpty().forEach { a -> CardBlock(a.optString("date"), "${a.optString("doctor")} · ${a.optString("reason")}\n${a.optString("status")}") }
                        OutlinedButton(onClick = { patientId = null; page = "Schedule" }) { Text("Manage appointments") }
                        Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(p.optBoolean("communicationConsent"), { allowed -> editRecord(p, "Reminder consent updated") { it.put("communicationConsent", allowed) } }); Text("Consent to appointment reminders") }
                        OutlinedButton(onClick = { store.stageExport(JSONObject().put("kind", "pdf").put("patient", p).put("config", store.config)) { pdfExporter.launch("care-summary-${p.optString("id")}.pdf") } }) { Text("Export care summary PDF") }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { if (p.has("images")) OutlinedButton(onClick = { imagePatientId = p.optString("id"); imagePicker.launch("image/*") }, enabled = !store.busy) { Text("Attach image") }; OutlinedButton(onClick = { store.stageExport(JSONObject().put("kind", "json").put("text", p.toString(2))) { exporter.launch("patient-${p.optString("id")}.json") } }) { Text("Export JSON") } }
                        p.optJSONArray("images")?.objects().orEmpty().forEach { image ->
                            OutlinedButton(onClick = { scope.launch { runCatching {
                                val bytes = com.google.firebase.storage.FirebaseStorage.getInstance().reference.child(image.getString("path")).getBytes(512L * 1024).await()
                                val decoded = withContext(Dispatchers.IO) { android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }
                                if (patientId == p.optString("id")) preview = decoded
                            }.onFailure { error = it.localizedMessage ?: "Unable to open image" } } }) { Text("View clinical image · ${image.optInt("bytes") / 1024} KB") }
                        }
                    }
                    "Chart" -> {
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf("permanent", "primary", "mixed").forEach { dentition -> FilterChip(selected = p.obj("chart").optString("dentition") == dentition, onClick = { editRecord(p, "Chart inventory: $dentition") { it.obj("chart").put("dentition", dentition) } }, label = { Text(dentition.replaceFirstChar { it.uppercase() }) }) } }
                        Button(onClick = {
                            chartPatientId = p.optString("id"); chartToken = uid()
                            val payload = JSONObject(p.obj("chart").toString()).put("sessionToken", chartToken)
                            chartLauncher.launch(Intent(context, Dental3DActivity::class.java).putExtra("chart", payload.toString()))
                        }, modifier = Modifier.fillMaxWidth()) { Text("Open interactive 3D chart") }
                        OutlinedButton(onClick = { explanation = true }) { Text("Patient explanation view") }
                        Text("Tap a tooth to assess or plan care", color = Slate.copy(alpha = .65f))
                        val inventory = p.obj("chart").optString("dentition")
                        if (inventory != "primary") NativeDentalChart(p, false) { tooth = it }
                        if (inventory != "permanent") NativeDentalChart(p, true) { tooth = it }
                        CardBlock("Chart legend", "White · unassessed    Teal · assessed\nAmber · finding    Gray · recorded tooth loss")
                    }
                    "Encounters" -> EncountersScreen(p, store.config) { changed, action -> store.save(changed, action) }
                    "Treatments" -> TreatmentWorkspaceScreen(p, store.config) { changed, action -> store.save(changed, action) }
                    "Billing" -> BillingScreen(p, store.config) { changed, action -> store.save(changed, action) }
                    "History" -> ChartHistoryScreen(p, store.config) { chart ->
                        chartPatientId = p.optString("id"); chartToken = uid()
                        chartLauncher.launch(Intent(context, Dental3DActivity::class.java).putExtra("chart", chart.put("sessionToken", chartToken).toString()))
                    }
                }
            } else when (page) {
                "Home" -> {
                    Heading("A clearer view of care", "Your clinic at a glance")
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { Stat("Patients", store.patients.size.toString(), Modifier.weight(1f)); Stat("Open plans", store.patients.sumOf { it.optJSONArray("treatments")?.objects().orEmpty().count { t -> t.optString("status") !in listOf("Completed", "Declined") } }.toString(), Modifier.weight(1f)) }
                    CardBlock("Welcome to Dental Care", "Keep patient profiles, clinical stories, dental charts, and treatment records together.")
                    Button(onClick = { addPatient() }, modifier = Modifier.fillMaxWidth()) { Text("+ Register patient") }
                    Text("Recent patients", fontWeight = FontWeight.Bold)
                    store.patients.sortedByDescending { it.optLong("updatedAt") }.take(5).forEach { p -> PatientCard(p) { patientId = p.optString("id"); tab = "Overview" } }
                }
                "Patients" -> {
                    Heading("Patients", "Find a profile and continue care")
                    OutlinedTextField(search, { search = it }, label = { Text("Search name, phone, or patient ID") }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), singleLine = true)
                    Button(onClick = { addPatient() }) { Text("+ Register patient") }
                    Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(showArchived, { showArchived = it }); Text("Include archived patients") }
                    store.patients.filter { (showArchived || !it.optBoolean("archived")) && "${it.optString("name")} ${it.optString("phone")} ${it.optString("id")}".contains(search, true) }.forEach { p -> PatientCard(p) { patientId = p.optString("id"); tab = "Overview" } }
                    if (store.patients.isEmpty()) CardBlock("Your patient list starts here", "Register a patient to create their profile and dental chart.")
                }
                "Schedule" -> SchedulingScreen(store.patients, store.config, { changed, action -> store.save(changed, action) }, { id -> patientId = id; tab = "Overview" })
                "Reports" -> AdvancedReportsScreen(store.patients, store.config)
                "Settings" -> {
                    Heading("Settings", "Storage and account preferences")
                    OutlinedButton(onClick = { showHowTo = true }, modifier = Modifier.fillMaxWidth()) { Text("How to use") }
                    if (store.cloudIdentity.isNotBlank()) androidx.compose.foundation.text.selection.SelectionContainer { Text("Cloud UID: ${store.cloudIdentity}\nWorkspace: ${store.workspaceId.ifBlank { store.cloudIdentity }}") }
                    if (store.reminderRebuildRequired) CardBlock("Refresh older appointment reminders", "Some saved appointments need their reminder settings refreshed. Review and save those appointments in Schedule, then synchronize.", Color(0xFFFFF7ED))
                    CardBlock("Device storage", "Patient records are encrypted on this device. Synchronize when you want to save changes to your cloud workspace.")
                    Button(onClick = { store.sync() }, enabled = !store.busy) { Text("Synchronize changes") }
                    OutlinedButton(onClick = { store.restore() }, enabled = !store.busy) { Text("Restore cloud records") }
                    OutlinedButton(onClick = { importer.launch(arrayOf("application/json", "text/plain")) }) { Text("Import patient JSON as a new record") }
                    OutlinedButton(onClick = { form = Form("Clinic workspace", listOf(Field("workspace", "Workspace ID (blank for personal)", store.workspaceId))) { v -> store.setWorkspace(v["workspace"].orEmpty()) } }) { Text("Choose cloud workspace") }
                    OutlinedButton(onClick = { form = Form("Close device workspace", listOf(Field("confirm", "Type CLOSE to remove synchronized local records"))) { v -> require(v["confirm"] == "CLOSE") { "Enter CLOSE" }; store.closeWorkspace() } }) { Text("Close synchronized device workspace") }
                    if (android.os.Build.VERSION.SDK_INT >= 33) OutlinedButton(onClick = { notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS) }) { Text("Allow appointment notifications") }
                    if (store.currentRole in listOf("owner", "admin", "administrator")) ClinicManagementScreen(store.config) { updated -> store.saveConfig(updated) }
                    if (store.conflicts.isNotEmpty()) {
                        Text("Conflict drafts", style = MaterialTheme.typography.titleLarge)
                        store.conflicts.forEach { draft ->
                            CardBlock(draft.optString("name"), "Retained draft · ${date(draft.optLong("retainedAt"))}")
                            OutlinedButton(onClick = { store.stageExport(JSONObject().put("kind", "json").put("text", draft.toString(2))) { exporter.launch("conflict-${draft.optString("id")}.json") } }) { Text("Export retained draft") }
                            Text(draft.optString("story"))
                            TextButton(onClick = { form = Form("Remove retained draft", listOf(Field("confirm", "Type EXPORTED after saving a copy"))) { v -> require(v["confirm"] == "EXPORTED") { "Export your draft first" }; store.discardRetainedDraft(draft.optString("id"), draft.optLong("retainedAt")) } }) { Text("Remove exported draft") }
                        }
                    }
                    OutlinedButton(onClick = { store.refreshClinicConfig() }, enabled = !store.busy) { Text("Load cloud clinic settings (retain local draft)") }
                    OutlinedButton(onClick = { store.registerCloudReminders(true) }, enabled = !store.busy) { Text("Enable cloud reminders on this device") }
                    OutlinedButton(onClick = { store.registerCloudReminders(false) }, enabled = !store.busy) { Text("Disable cloud reminders on this device") }
                    CardBlock("Image uploads", "Lossless PNG, maximum 512 KB. Larger images are rejected to preserve image detail.")
                    if (BuildConfig.GOOGLE_LOGIN_ENABLED) Button(onClick = { scope.launch { runCatching { val resource = context.resources.getIdentifier("default_web_client_id", "string", context.packageName); store.google(context, if (resource == 0) "" else context.getString(resource)) }.onFailure { error = it.localizedMessage ?: "Sign-in failed" } } }) { Text("Continue with Google") }
                    if (BuildConfig.GOOGLE_LOGIN_ENABLED && store.cloudIdentity.isNotBlank()) OutlinedButton(onClick = { store.signOut() }, enabled = !store.busy) { Text("Sign out after synchronizing") }
                    if (!BuildConfig.GOOGLE_LOGIN_ENABLED) CardBlock("Account", "Google sign-in is currently disabled. Cloud sync uses a private anonymous Firebase workspace when configured.")
                }
            }
        }
    }
    if (explanation && patient != null) PatientExplanationDialog(patient, { explanation = false }) { chart ->
        chartPatientId = patient.optString("id"); chartToken = uid()
        chartLauncher.launch(Intent(context, Dental3DActivity::class.java).putExtra("chart", chart.put("sessionToken", chartToken).toString()))
    }
    preview?.let { bitmap -> AlertDialog(onDismissRequest = { preview = null }, title = { Text("Clinical image") }, text = { androidx.compose.foundation.Image(bitmap.asImageBitmap(), "Clinical attachment", Modifier.fillMaxWidth().heightIn(max = 480.dp)) }, confirmButton = { TextButton(onClick = { preview = null }) { Text("Close") } }) }
    if (error.isNotBlank()) AlertDialog(onDismissRequest = { error = "" }, title = { Text("Please review") }, text = { Text(error) }, confirmButton = { TextButton(onClick = { error = "" }) { Text("OK") } })
    form?.let { active -> key(active) {
        val values = remember { mutableStateMapOf<String, String>().apply { active.fields.forEach { put(it.key, it.value) } } }
        var formError by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { form = null }, title = { Text(active.title) }, text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) { active.fields.forEach { field -> OutlinedTextField(values[field.key] ?: "", { values[field.key] = it }, label = { Text(field.label) }, modifier = Modifier.fillMaxWidth(), minLines = if (field.key == "story") 5 else 1) }; if (formError.isNotBlank()) Text(formError, color = MaterialTheme.colorScheme.error) } }, confirmButton = { TextButton(enabled = !store.busy, onClick = { runCatching { require(active.fields.none { it.required && values[it.key].isNullOrBlank() }) { "Complete the required fields" }; val before = saveStatus(); active.save(values.toMap()); requireAcceptedSave(before, saveStatus()) }.onSuccess { form = null }.onFailure { formError = it.localizedMessage ?: "Review the values" } }) { Text("Save") } }, dismissButton = { TextButton(onClick = { form = null }) { Text("Cancel") } })
    } }
    tooth?.let { number -> if (patient != null) ToothEditor(number, patient, toothSurface, { tooth = null; toothSurface = "" }) { changed, action -> store.save(changed, action); if (store.saveError == null) tooth = null } }
}

@Composable private fun Heading(title: String, subtitle: String) { Column(verticalArrangement = Arrangement.spacedBy(5.dp)) { Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold); Text(subtitle, color = Slate.copy(alpha = .65f)) } }
@Composable private fun CardBlock(title: String, body: String, background: Color = Color.White) { Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = background)) { Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { Text(title, fontWeight = FontWeight.SemiBold); Text(body, color = Slate.copy(alpha = .75f), style = MaterialTheme.typography.bodyMedium) } } }
@Composable private fun Stat(label: String, value: String, modifier: Modifier) { Card(modifier, colors = CardDefaults.cardColors(containerColor = Color.White)) { Column(Modifier.padding(20.dp)) { Text(value, fontSize = 30.sp, fontWeight = FontWeight.Bold, color = Teal); Text(label, color = Slate) } } }
@Composable private fun PatientCard(patient: JSONObject, click: () -> Unit) { Card(onClick = click, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) { Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) { Surface(color = Mint, shape = RoundedCornerShape(12.dp)) { Text(patient.optString("name").take(2).uppercase(), Modifier.padding(14.dp), color = Teal, fontWeight = FontWeight.Bold) }; Column(Modifier.weight(1f).padding(start = 14.dp)) { Text(patient.optString("name"), fontWeight = FontWeight.SemiBold); Text(patient.optString("phone").ifBlank { "No phone recorded" }, fontSize = 12.sp) }; Text("→", color = Teal) } } }
@Composable private fun ToothEditor(number: String, patient: JSONObject, initialSurface: String, close: () -> Unit, save: (JSONObject, String) -> Unit) {
    val saveStatus = LocalEditorSaveStatus.current
    val current = patient.obj("chart").obj("teeth").optJSONObject(number)
    var assessment by remember(number) { mutableStateOf(current?.optString("assessment") ?: "assessed") }
    var replacement by remember(number) { mutableStateOf(current?.optString("replacement") ?: "not recorded") }
    var presence by remember(number) { mutableStateOf(current?.optString("presence") ?: "unknown") }
    var surface by remember(number) { mutableStateOf(initialSurface.ifBlank { "Whole tooth" }) }
    var finding by remember(number) { mutableStateOf("none") }
    var restoration by remember(number) { mutableStateOf("none") }
    var clearFindings by remember(number) { mutableStateOf(false) }
    var procedure by remember(number) { mutableStateOf("") }
    var note by remember(number) { mutableStateOf("") }
    var toothError by remember(number) { mutableStateOf("") }
    AlertDialog(onDismissRequest = close, title = { Text("Tooth $number") }, text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Assessment"); Choices(listOf("assessed", "not_assessed", "unable_to_assess"), assessment) { assessment = it }
        Text("Presence"); Choices(listOf("unknown", "present", "unerupted", "missing", "extracted", "exfoliated", "congenitally_absent"), presence) { presence = it }
        Text("Replacement"); Choices(listOf("not recorded", "none", "implant", "pontic", "removable_prosthesis"), replacement) { replacement = it }
        Text("Surface"); Choices(listOf("Whole tooth", "M", "D", "F", "L", if (number.last().digitToInt() <= 3) "I" else "O"), surface) { surface = it }
        Text("Add finding"); Choices(listOf("none", "caries", "fracture", "wear", "endodontically_treated"), finding) { finding = it }
        Text("Add restoration (select a surface)")
        Choices(listOf("none", "composite", "amalgam", "glass_ionomer", "sealant", "crown", "inlay", "onlay"), restoration) { restoration = it }
        Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(clearFindings, { clearFindings = it }); Text("Resolve findings at selected scope") }
        OutlinedTextField(procedure, { procedure = it }, label = { Text("Planned procedure (optional)") })
        OutlinedTextField(note, { note = it }, label = { Text("Clinical note") })
        if (toothError.isNotBlank()) Text(toothError, color = MaterialTheme.colorScheme.error)
    } }, confirmButton = { TextButton(onClick = {
        val lost = presence in setOf("missing", "extracted", "exfoliated", "congenitally_absent")
        if (lost && replacement in setOf("none", "not recorded") && (finding != "none" || restoration != "none")) {
            toothError = "Record tooth loss separately. Surface findings or restorations require a present tooth or a recorded replacement."
            return@TextButton
        }
        val copy = JSONObject(patient.toString()); val teeth = copy.obj("chart").obj("teeth")
        val tooth = teeth.optJSONObject(number) ?: JSONObject()
        // Current natural-tooth facts end at tooth loss; the prior chart is preserved in history.
        if (lost && (current?.optString("presence") != presence || replacement in setOf("none", "not recorded"))) {
            tooth.remove("surfaces"); tooth.put("findings", JSONArray())
        }
        tooth.put("assessment", assessment).put("presence", presence)
        if (replacement != "not recorded") tooth.put("replacement", replacement)
        if (clearFindings) {
            if (surface == "Whole tooth") tooth.put("findings", JSONArray())
            else tooth.obj("surfaces").put(surface, JSONObject().put("assessment", "assessed").put("findings", JSONArray()).put("restorations", tooth.obj("surfaces").optJSONObject(surface)?.optJSONArray("restorations") ?: JSONArray()))
        }
        if (restoration != "none" && surface != "Whole tooth") {
            val observation = tooth.obj("surfaces").optJSONObject(surface) ?: JSONObject().put("assessment", "assessed").put("findings", JSONArray()).put("restorations", JSONArray())
            val codes = observation.array("restorations")
            if ((0 until codes.length()).none { codes.getString(it) == restoration }) codes.put(restoration)
            tooth.obj("surfaces").put(surface, observation)
        }
        if (finding != "none") {
            if (surface == "Whole tooth") { val codes = tooth.array("findings"); if ((0 until codes.length()).none { codes.getString(it) == finding }) codes.put(finding) }
            else { val observation = tooth.obj("surfaces").optJSONObject(surface) ?: JSONObject().put("assessment", "assessed").put("findings", JSONArray()).put("restorations", JSONArray()); val codes = observation.array("findings"); if ((0 until codes.length()).none { codes.getString(it) == finding }) codes.put(finding); tooth.obj("surfaces").put(surface, observation) }
        }
        teeth.put(number, tooth)
        if (procedure.isNotBlank()) { tooth.put("treatmentStates", JSONArray().put("planned")); copy.array("treatments").put(JSONObject().put("id", uid()).put("tooth", number).put("surface", surface).put("procedure", procedure.trim()).put("notes", note).put("status", "Planned").put("at", System.currentTimeMillis())) }
        val beforeSave = saveStatus()
        save(copy, "Tooth $number: $assessment; $presence; replacement $replacement; $surface; finding $finding; restoration $restoration; findings resolved=$clearFindings${if (procedure.isNotBlank()) "; planned $procedure" else ""}${if (note.isNotBlank()) "; $note" else ""}")
        val afterSave = saveStatus()
        if (afterSave.sequence != beforeSave.sequence && afterSave.rejection != null) toothError = afterSave.rejection
    }) { Text("Save assessment") } }, dismissButton = { TextButton(onClick = close) { Text("Cancel") } })
}
@Composable private fun Choices(values: List<String>, selected: String, select: (String) -> Unit) { Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(5.dp)) { values.forEach { FilterChip(selected = selected == it, onClick = { select(it) }, label = { Text(it) }) } } }
