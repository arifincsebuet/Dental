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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spala.dental.ui.theme.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.tasks.await
import org.json.*
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.Period
import java.util.*

private fun date(value: Long) = SimpleDateFormat("dd MMM yyyy · HH:mm", Locale.getDefault()).format(Date(value))
private fun ageText(dob: String): String? = runCatching {
    val years = Period.between(LocalDate.parse(dob.trim()), LocalDate.now()).years
    if (years in 0..130) "$years yrs" else null
}.getOrNull()

class MainActivity : ComponentActivity() {
    private val store by lazy { (application as ClinicApplication).store }
    private lateinit var billing: BillingManager
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ToothNotation.load(this)
        billing = BillingManager(this) { purchase ->
            if (store.session == "local") { billing.acknowledgeLocally(purchase); store.markDeviceEntitled() }
            // Only call the server when the clinic is not already known to be active (saves calls on every launch).
            else if (!store.subscriptionActive) store.verifyPurchase(purchase.purchaseToken)
        }
        billing.connect()
        setContent {
            DentalTheme {
                CompositionLocalProvider(LocalEditorSaveStatus provides { EditorSaveStatus(store.saveSequence, store.saveError) }) {
                    when (store.session) {
                        "loading" -> Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                        "login" -> LoginScreen(store)
                        "patient" -> PatientPortalScreen(store)
                        // The clinic subscription unlocks the staff app; debug builds skip it for testing.
                        else -> if (store.subscriptionActive || BuildConfig.DEBUG) ClinicApp(store, billing) else PaywallScreen(store, billing)
                    }
                }
            }
        }
    }
}

private data class Field(val key: String, val label: String, val value: String = "", val required: Boolean = false)
private data class Form(val title: String, val fields: List<Field>, val save: (Map<String, String>) -> Unit)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ClinicApp(store: ClinicStore, billing: BillingManager) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val saveStatus = LocalEditorSaveStatus.current
    var showHowTo by rememberSaveable { mutableStateOf(false) }
    var page by rememberSaveable { mutableStateOf("Home") }
    var patientId by rememberSaveable { mutableStateOf<String?>(null) }
    var doctorId by rememberSaveable { mutableStateOf<String?>(null) }
    val patient = store.patients.firstOrNull { it.optString("id") == patientId }
    val doctor = doctorId?.let { id -> clinicianRecords(store.config).firstOrNull { it.optString("id") == id } }
    var tab by rememberSaveable { mutableStateOf("Overview") }
    var search by remember { mutableStateOf("") }
    var showArchived by remember { mutableStateOf(false) }
    var form by remember { mutableStateOf<Form?>(null) }
    var tooth by remember { mutableStateOf<String?>(null) }
    // Tooth focused on the 3D model / expanded in the history list, and multi-tooth selection mode.
    var focusTooth by rememberSaveable(patientId) { mutableStateOf<String?>(null) }
    var selecting by rememberSaveable(patientId) { mutableStateOf(false) }
    val selection = remember(patientId) { mutableStateListOf<String>() }
    var recordTeeth by remember { mutableStateOf<List<String>?>(null) }
    var recordSurface by remember { mutableStateOf("") }
    var chartPatientId by rememberSaveable { mutableStateOf<String?>(null) }
    var chartToken by rememberSaveable { mutableStateOf("") }
    var toothSurface by remember { mutableStateOf("") }
    val chartLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK && patientId == chartPatientId) {
            val token = result.data?.getStringExtra("sessionToken")
            if (token == chartToken && canEditClinical(store.currentRole) && result.data?.getLongExtra("chartVersion", -1) == store.patients.firstOrNull { it.optString("id") == patientId }?.optJSONObject("chart")?.optLong("chartVersion")) {
                // A tooth chosen in the full 3D chart opens the treatment recorder for it.
                result.data?.getStringExtra("tooth")?.let { key -> recordTeeth = listOf(key); recordSurface = result.data?.getStringExtra("surface").orEmpty(); focusTooth = key }
            }
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
    fun addPatient() { if (store.currentRole in listOf("cashier", "auditor", "unknown")) { error = "Your current role cannot register patients"; return }; form = Form("New patient", listOf(Field("name", "Full name", required = true), Field("phone", "Phone"), Field("dob", "Date of birth"), Field("allergies", "Allergies / medical alerts"))) { values -> store.create(values["name"]!!, values["phone"]!!, values["dob"]!!, values["allergies"]!!) } }
    fun editRecord(p: JSONObject, action: String, edit: (JSONObject) -> Unit) { val copy = JSONObject(p.toString()); edit(copy); store.save(copy, action) }
    /** Every 3D launch gets a fresh session token so stale results from an earlier chart are ignored. */
    fun open3D(p: JSONObject, chart: JSONObject) {
        chartPatientId = p.optString("id"); chartToken = uid()
        chart.put("notation", ToothNotation.current)
        if (!canEditClinical(store.currentRole)) chart.put("readOnly", true)
        chartLauncher.launch(Intent(context, Dental3DActivity::class.java).putExtra("chart", chart.put("sessionToken", chartToken).toString()))
    }
    fun openPatient(id: String) { patientId = id; tab = "Overview" }
    if (showHowTo) {
        BackHandler { showHowTo = false }
        HowToUseScreen(onBack = { showHowTo = false })
        return
    }
    BackHandler(enabled = patient != null || doctor != null) { if (patient != null) patientId = null else doctorId = null }
    Scaffold(containerColor = MaterialTheme.colorScheme.background, topBar = {
        TopAppBar(title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                DentalAppLogo(Modifier.size(38.dp))
                Column {
                    Text("Dental Care", style = MaterialTheme.typography.titleLarge, color = Ink)
                    Text(store.config.optString("name").takeIf { it.isNotBlank() && it != "Dental Care" } ?: "Your clinic, connected",
                        style = MaterialTheme.typography.bodySmall, color = InkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }, actions = {
            if (store.busy) CircularProgressIndicator(Modifier.size(22.dp).padding(end = 4.dp), strokeWidth = 2.dp)
            Surface(onClick = { store.sync() }, enabled = !store.busy, shape = RoundedCornerShape(50), color = Teal50,
                modifier = Modifier.padding(end = 12.dp).semantics { contentDescription = "Synchronize changes" }) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    GlyphIcon(Glyph.Sync, Teal700, 16.dp); Text("Sync", style = MaterialTheme.typography.labelLarge, color = Teal700)
                }
            }
        }, colors = TopAppBarDefaults.topAppBarColors(containerColor = SurfaceWhite))
    }, bottomBar = {
        NavigationBar(containerColor = SurfaceWhite, tonalElevation = 0.dp) {
            listOf("Home", "Patients", "Schedule", "Reports", "Settings").forEachIndexed { index, name ->
                NavigationBarItem(selected = page == name && patient == null && doctor == null, onClick = { page = name; patientId = null; doctorId = null },
                    icon = { ClinicNavIcon(index) }, label = { Text(name, style = MaterialTheme.typography.labelSmall) },
                    colors = NavigationBarItemDefaults.colors(indicatorColor = Teal100, selectedTextColor = Teal700, unselectedTextColor = InkMuted))
            }
        }
    }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (store.message.isNotBlank()) Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Green50).border(1.dp, Green600.copy(alpha = .25f), RoundedCornerShape(16.dp)).padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GlyphIcon(Glyph.Check, Green600, 18.dp)
                Text(store.message, Modifier.weight(1f).padding(horizontal = 10.dp), style = MaterialTheme.typography.bodyMedium, color = Ink)
                TextButton(onClick = { store.clearMessage() }) { Text("Dismiss") }
            }
            if (patient != null) {
                val p = patient
                val patientTabs = if (p.has("chart")) listOf("Overview", "Chart", "Encounters", "Treatments", "Billing", "History") else if (p.has("invoices")) listOf("Overview", "Billing") else listOf("Overview")
                TextButton(onClick = { patientId = null }, contentPadding = PaddingValues(horizontal = 4.dp)) {
                    GlyphIcon(Glyph.Back, Teal700, 18.dp); Spacer(Modifier.width(4.dp)); Text(if (doctor != null) "Back to ${doctor.optString("name")}" else "Patients")
                }
                val canEdit = canEditClinical(store.currentRole)
                // First view: the patient's 3D dentition, coloured by their full treatment history.
                if (p.has("chart")) {
                    val signature = p.toString() + ToothNotation.current
                    val view = remember(signature) { displayChart(p) }
                    val records = remember(signature) { toothRecords(p) }
                    val highlighted = if (selecting) selection.toSet() else setOfNotNull(focusTooth)
                    Teeth3DHeroCard(
                        chart = view,
                        title = p.optString("name"),
                        subtitle = listOfNotNull(ageText(p.optString("dob")), "${view.optString("dentition", "permanent").replaceFirstChar { it.uppercase() }} dentition",
                            "${records.count { it.completed > 0 }} teeth treated").joinToString(" · "),
                        selectedTeeth = highlighted,
                        onToothTap = { key ->
                            if (selecting) { if (key in selection) selection.remove(key) else selection.add(key) }
                            else focusTooth = if (focusTooth == key) null else key
                        },
                        onOpenFull = { open3D(p, displayChart(p)) },
                        banner = {
                            if (selecting) HeroHint(if (selection.isEmpty()) "Tap teeth to select them" else "${selection.size} selected · tap to add or remove", highlight = true)
                            else HeroHint(focusTooth?.let { "${ToothNotation.label(it)} · ${toothName(it)}" } ?: "Drag to rotate · pinch to zoom · tap a tooth", highlight = focusTooth != null)
                        },
                    ) {
                        if (selecting) {
                            Text(if (selection.isEmpty()) "Select one or more teeth on the model" else selection.sortedBy { ToothNotation.orderIndex(it) }.joinToString(", ") { ToothNotation.label(it) },
                                style = MaterialTheme.typography.titleSmall, color = Ink)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { recordTeeth = selection.sortedBy { ToothNotation.orderIndex(it) }; recordSurface = "" }, enabled = selection.isNotEmpty(),
                                    modifier = Modifier.weight(1f).height(50.dp), shape = RoundedCornerShape(16.dp)) { Text("Record treatment (${selection.size})") }
                                OutlinedButton(onClick = { selecting = false; selection.clear() }, modifier = Modifier.height(50.dp), shape = RoundedCornerShape(16.dp)) { Text("Cancel") }
                            }
                        } else {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                MiniMetric("${records.count { it.completed > 0 }}", "Treated", Blue600, Modifier.weight(1f))
                                MiniMetric("${records.count { it.open > 0 }}", "Planned", Teal700, Modifier.weight(1f))
                                MiniMetric("${records.count { it.findings.isNotEmpty() }}", "Findings", Amber600, Modifier.weight(1f))
                                MiniMetric("${records.count { it.presence in setOf("missing", "extracted", "exfoliated", "congenitally_absent") }}", "Missing", InkMuted, Modifier.weight(1f))
                            }
                            ChartLegendRow()
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { open3D(p, displayChart(p)) }, modifier = Modifier.weight(1f).height(50.dp), shape = RoundedCornerShape(16.dp)) {
                                    GlyphIcon(Glyph.Cube, Color.White, 18.dp); Spacer(Modifier.width(8.dp)); Text("Full 3D chart")
                                }
                                if (canEdit) FilledTonalButton(onClick = { selecting = true; selection.clear(); focusTooth?.let { selection.add(it) } },
                                    modifier = Modifier.weight(1f).height(50.dp), shape = RoundedCornerShape(16.dp)) {
                                    GlyphIcon(Glyph.Plus, Teal900, 16.dp); Spacer(Modifier.width(6.dp)); Text("Record treatment")
                                }
                            }
                            if (!canEdit) Text("View only · treatment entries are added by the dentist.", style = MaterialTheme.typography.bodySmall, color = InkMuted)
                        }
                    }
                    if (p.optString("allergies").isNotBlank()) AlertBanner("Medical alerts", p.optString("allergies"))
                    if (p.has("operationId")) OutlinedButton(onClick = { store.resolveConflict(p.optString("id")) }, enabled = !store.busy, modifier = Modifier.fillMaxWidth()) { Text("Review conflict · retain draft and load server version") }
                    ToothHistoryList(records, store.config, focusTooth, canEdit,
                        onToggle = { key -> focusTooth = if (focusTooth == key) null else key },
                        onRecord = { key -> recordTeeth = listOf(key); recordSurface = "" },
                        onEditStatus = { key -> tooth = key; toothSurface = "" })
                } else {
                    PatientHeader(p)
                    if (p.has("operationId")) OutlinedButton(onClick = { store.resolveConflict(p.optString("id")) }, enabled = !store.busy, modifier = Modifier.fillMaxWidth()) { Text("Review conflict · retain draft and load server version") }
                    if (p.has("name")) SurfaceCard(background = SurfaceTint) {
                        Text("Dental chart", style = MaterialTheme.typography.titleSmall, color = Ink)
                        Text("Clinical chart details are not available for your clinic role.", style = MaterialTheme.typography.bodySmall, color = InkMuted)
                    }
                }
                SegmentedPills(patientTabs, tab.takeIf { it in patientTabs } ?: "Overview", Modifier.fillMaxWidth()) { tab = it }
                when (tab.takeIf { it in patientTabs } ?: "Overview") {
                    "Overview" -> {
                        if (p.has("chart")) PatientHeader(p, showAlert = false)
                        if (canEdit || store.currentRole in listOf("owner", "admin", "administrator")) PatientAccessCard(p, store)
                        SectionHeader("Profile")
                        SurfaceCard {
                            InfoRow(Glyph.Calendar, "Date of birth", p.optString("dob").ifBlank { "Not recorded" } + (ageText(p.optString("dob"))?.let { " · $it" } ?: ""))
                            InfoRow(Glyph.Phone, "Phone", p.optString("phone").ifBlank { "Not recorded" })
                            InfoRow(Glyph.Person, "Patient ID", p.optString("id").take(8).uppercase())
                        }
                        SectionHeader("Quick actions")
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            ActionTile("Edit profile", Glyph.Edit, Modifier.weight(1f)) {
                                val fields = mutableListOf(Field("name", "Full name", p.optString("name"), true), Field("phone", "Phone", p.optString("phone")), Field("dob", "Date of birth", p.optString("dob")))
                                if (p.has("allergies")) fields.add(Field("allergies", "Medical alerts", p.optString("allergies")))
                                form = Form("Edit profile", fields) { values -> editRecord(p, "Profile amended") { changed -> values.forEach { (key, value) -> changed.put(key, value) } } }
                            }
                            ActionTile("Care summary PDF", Glyph.Export, Modifier.weight(1f)) { store.stageExport(JSONObject().put("kind", "pdf").put("patient", p).put("config", store.config)) { pdfExporter.launch("care-summary-${p.optString("id")}.pdf") } }
                            ActionTile("Export JSON", Glyph.Clipboard, Modifier.weight(1f)) { store.stageExport(JSONObject().put("kind", "json").put("text", p.toString(2))) { exporter.launch("patient-${p.optString("id")}.json") } }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            ActionTile("Attach image", Glyph.Image, Modifier.weight(1f), enabled = p.has("images") && !store.busy) { imagePatientId = p.optString("id"); imagePicker.launch("image/*") }
                            ActionTile("Appointments", Glyph.Calendar, Modifier.weight(1f)) { patientId = null; doctorId = null; page = "Schedule" }
                            ActionTile(if (p.optBoolean("archived")) "Restore patient" else "Archive patient", Glyph.Archive, Modifier.weight(1f), tint = if (p.optBoolean("archived")) Teal700 else Rose600) {
                                form = Form(if (p.optBoolean("archived")) "Restore archived patient" else "Archive patient", listOf(Field("reason", "Reason", required = true))) { values -> editRecord(p, "Patient archive state amended: ${values["reason"]}") { it.put("archived", !p.optBoolean("archived")) } }
                            }
                        }
                        if (p.has("story")) {
                            SectionHeader("Clinical story", action = "Update") { form = Form("Clinical story", listOf(Field("story", "Clinical narrative", p.optString("story")))) { v -> editRecord(p, "Clinical story amended") { it.put("story", v["story"]) } } }
                            SurfaceCard { Text(p.optString("story").ifBlank { "Add complaint, medical context, examination notes, and care goals." }, style = MaterialTheme.typography.bodyMedium, color = if (p.optString("story").isBlank()) InkMuted else Ink) }
                        }
                        SectionHeader("Appointments", action = "Manage") { patientId = null; doctorId = null; page = "Schedule" }
                        val appointments = p.optJSONArray("appointments")?.objects().orEmpty()
                        if (appointments.isEmpty()) SurfaceCard { Text("No appointments recorded.", color = InkMuted, style = MaterialTheme.typography.bodyMedium) }
                        appointments.forEach { a ->
                            SurfaceCard(padding = 14.dp) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(Blue100), contentAlignment = Alignment.Center) { GlyphIcon(Glyph.Calendar, Blue600, 20.dp) }
                                    Column(Modifier.weight(1f)) {
                                        Text(a.optString("date"), style = MaterialTheme.typography.titleSmall, color = Ink)
                                        Text(listOf(a.optString("doctor"), a.optString("reason")).filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = InkMuted)
                                    }
                                    StatusPill(a.optString("status").ifBlank { "Scheduled" }, SurfaceTint, InkMuted, dot = false)
                                }
                            }
                        }
                        SurfaceCard(padding = 12.dp) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("Appointment reminders", style = MaterialTheme.typography.titleSmall, color = Ink)
                                    Text("Patient consents to reminder messages", style = MaterialTheme.typography.bodySmall, color = InkMuted)
                                }
                                Switch(p.optBoolean("communicationConsent"), { allowed -> editRecord(p, "Reminder consent updated") { it.put("communicationConsent", allowed) } })
                            }
                        }
                        val images = p.optJSONArray("images")?.objects().orEmpty()
                        if (images.isNotEmpty()) SectionHeader("Clinical images")
                        images.forEach { image ->
                            SurfaceCard(padding = 12.dp, onClick = { scope.launch { runCatching {
                                val bytes = com.google.firebase.storage.FirebaseStorage.getInstance().reference.child(image.getString("path")).getBytes(512L * 1024).await()
                                val decoded = withContext(Dispatchers.IO) { android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }
                                if (patientId == p.optString("id")) preview = decoded
                            }.onFailure { error = it.localizedMessage ?: "Unable to open image" } } }) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    GlyphIcon(Glyph.Image, Teal700, 22.dp)
                                    Text("Clinical image · ${image.optInt("bytes") / 1024} KB", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                                    GlyphIcon(Glyph.Chevron, InkSubtle, 18.dp)
                                }
                            }
                        }
                    }
                    "Chart" -> {
                        SectionHeader("Dentition")
                        SegmentedPills(listOf("permanent", "primary", "mixed"), p.obj("chart").optString("dentition"), Modifier.fillMaxWidth(), label = { it.replaceFirstChar { c -> c.uppercase() } }) { dentition ->
                            editRecord(p, "Chart inventory: $dentition") { it.obj("chart").put("dentition", dentition) }
                        }
                        Surface(onClick = { open3D(p, JSONObject(p.obj("chart").toString())) }, shape = RoundedCornerShape(24.dp), color = Color.Transparent, modifier = Modifier.fillMaxWidth()) {
                            Row(Modifier.background(Brush.linearGradient(listOf(Teal600, HeroDeep))).padding(20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                Box(Modifier.size(52.dp).clip(RoundedCornerShape(16.dp)).background(Color.White.copy(alpha = .16f)), contentAlignment = Alignment.Center) { GlyphIcon(Glyph.Cube, Color.White, 28.dp) }
                                Column(Modifier.weight(1f)) {
                                    Text("Open interactive 3D chart", style = MaterialTheme.typography.titleMedium, color = Color.White)
                                    Text("Rotate, zoom and tap any tooth or surface to assess it", style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = .8f))
                                }
                                GlyphIcon(Glyph.Chevron, Color.White, 20.dp)
                            }
                        }
                        OutlinedButton(onClick = { explanation = true }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) { Text("Patient explanation view") }
                        SectionHeader("Odontogram")
                        SurfaceCard {
                            Text("Tap a tooth to assess or plan care", style = MaterialTheme.typography.bodySmall, color = InkMuted)
                            val inventory = p.obj("chart").optString("dentition")
                            if (inventory != "primary") NativeDentalChart(p, false) { if (canEdit) tooth = it else focusTooth = it }
                            if (inventory != "permanent") NativeDentalChart(p, true) { if (canEdit) tooth = it else focusTooth = it }
                        }
                        SurfaceCard(background = SurfaceTint) {
                            Text("Chart legend", style = MaterialTheme.typography.titleSmall, color = Ink)
                            ChartLegendRow()
                        }
                    }
                    "Encounters" -> EncountersScreen(p, store.config) { changed, action -> store.save(changed, action) }
                    "Treatments" -> TreatmentWorkspaceScreen(p, store.config) { changed, action -> store.save(changed, action) }
                    "Billing" -> BillingScreen(p, store.config) { changed, action -> store.save(changed, action) }
                    "History" -> ChartHistoryScreen(p, store.config) { chart -> open3D(p, chart) }
                }
            } else if (doctor != null) {
                DoctorProfileScreen(doctor, store.patients, store.config, onBack = { doctorId = null }, onOpenPatient = { id -> openPatient(id) })
            } else when (page) {
                "Home" -> {
                    HomeHero(store)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        StatTile("Patients", store.patients.count { !it.optBoolean("archived") }.toString(), Teal700, Glyph.Person, Modifier.weight(1f))
                        StatTile("Open plans", store.patients.sumOf { it.optJSONArray("treatments")?.objects().orEmpty().count { t -> t.optString("status") !in listOf("Completed", "Declined", "Cancelled") } }.toString(), Color(0xFF7C3AED), Glyph.Clipboard, Modifier.weight(1f))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        StatTile("Today's visits", todaysVisits(store).toString(), Blue600, Glyph.Calendar, Modifier.weight(1f))
                        StatTile("Sync pending", store.patients.count { it.optBoolean("dirty") }.toString(), Amber600, Glyph.Sync, Modifier.weight(1f))
                    }
                    SectionHeader("Quick actions")
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        ActionTile("Register patient", Glyph.Plus, Modifier.weight(1f)) { addPatient() }
                        ActionTile("Book visit", Glyph.Calendar, Modifier.weight(1f), tint = Blue600) { page = "Schedule" }
                        ActionTile("Find patient", Glyph.Person, Modifier.weight(1f), tint = Color(0xFF7C3AED)) { page = "Patients" }
                    }
                    val team = clinicianRecords(store.config).filter { it.optBoolean("active", true) }
                    SectionHeader("Care team")
                    if (team.isEmpty()) SurfaceCard(background = SurfaceTint) {
                        Text("Add your dentists in Settings › Clinic › Staff to see their profiles here.", style = MaterialTheme.typography.bodySmall, color = InkMuted)
                    } else Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        team.forEach { member -> DoctorChip(member) { doctorId = member.optString("id") } }
                    }
                    SectionHeader("Recent patients", action = "See all") { page = "Patients" }
                    val recent = store.patients.sortedByDescending { it.optLong("updatedAt") }.take(5)
                    if (recent.isEmpty()) SurfaceCard(background = SurfaceTint) { Text("Register your first patient to create their profile and 3D dental chart.", style = MaterialTheme.typography.bodySmall, color = InkMuted) }
                    recent.forEach { p -> PatientCard(p) { openPatient(p.optString("id")) } }
                }
                "Patients" -> {
                    Heading("Patients", "${store.patients.count { !it.optBoolean("archived") }} active profiles")
                    OutlinedTextField(search, { search = it }, placeholder = { Text("Search name, phone, or patient ID") }, leadingIcon = { GlyphIcon(Glyph.Person, InkMuted, 18.dp) },
                        modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(unfocusedContainerColor = SurfaceWhite, focusedContainerColor = SurfaceWhite, unfocusedBorderColor = Hairline))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Button(onClick = { addPatient() }, shape = RoundedCornerShape(14.dp)) { GlyphIcon(Glyph.Plus, Color.White, 16.dp); Spacer(Modifier.width(6.dp)); Text("Register patient") }
                        Spacer(Modifier.weight(1f))
                        FilterChip(selected = showArchived, onClick = { showArchived = !showArchived }, label = { Text("Include archived") })
                    }
                    store.patients.filter { (showArchived || !it.optBoolean("archived")) && "${it.optString("name")} ${it.optString("phone")} ${it.optString("id")}".contains(search, true) }.forEach { p -> PatientCard(p) { openPatient(p.optString("id")) } }
                    if (store.patients.isEmpty()) SurfaceCard(background = SurfaceTint) { Text("Your patient list starts here", style = MaterialTheme.typography.titleSmall); Text("Register a patient to create their profile and dental chart.", style = MaterialTheme.typography.bodySmall, color = InkMuted) }
                }
                "Schedule" -> SchedulingScreen(store.patients, store.config, { changed, action -> store.save(changed, action) }, { id -> openPatient(id) })
                "Reports" -> AdvancedReportsScreen(store.patients, store.config)
                "Settings" -> {
                    Heading("Settings", "Storage and account preferences")
                    SubscriptionCard(store, billing)
                    SurfaceCard {
                        Text("Tooth numbering", style = MaterialTheme.typography.titleSmall, color = Ink)
                        Text("How teeth are labelled on the 3D model, charts and history. Records are always stored in FDI.", style = MaterialTheme.typography.bodySmall, color = InkMuted)
                        SegmentedPills(ToothNotation.all, ToothNotation.current, Modifier.fillMaxWidth(), label = { ToothNotation.title(it) }) { ToothNotation.set(context, it) }
                    }
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
                    if (runCatching { com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.isAnonymous == true }.getOrDefault(false)) Button(onClick = { scope.launch { runCatching { val resource = context.resources.getIdentifier("default_web_client_id", "string", context.packageName); store.google(context, if (resource == 0) "" else context.getString(resource)) }.onFailure { error = it.localizedMessage ?: "Sign-in failed" } } }) { Text("Link this device workspace to Google") }
                    if (store.cloudIdentity.isNotBlank()) OutlinedButton(onClick = { store.signOut() }, enabled = !store.busy) { Text("Sign out after synchronizing") }
                    CardBlock("Account", if (store.session == "local") "Offline mode: records stay encrypted on this device only." else "Signed in as ${runCatching { com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.email }.getOrNull() ?: "this device"} · role ${store.currentRole}")
                }
            }
        }
    }
    if (explanation && patient != null) PatientExplanationDialog(patient, { explanation = false }) { chart -> open3D(patient, chart) }
    preview?.let { bitmap -> AlertDialog(onDismissRequest = { preview = null }, title = { Text("Clinical image") }, text = { androidx.compose.foundation.Image(bitmap.asImageBitmap(), "Clinical attachment", Modifier.fillMaxWidth().heightIn(max = 480.dp)) }, confirmButton = { TextButton(onClick = { preview = null }) { Text("Close") } }) }
    store.credentialNotice?.let { notice -> CredentialDialog(notice) { store.credentialNotice = null } }
    if (error.isNotBlank()) AlertDialog(onDismissRequest = { error = "" }, title = { Text("Please review") }, text = { Text(error) }, confirmButton = { TextButton(onClick = { error = "" }) { Text("OK") } })
    form?.let { active -> key(active) {
        val values = remember { mutableStateMapOf<String, String>().apply { active.fields.forEach { put(it.key, it.value) } } }
        var formError by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { form = null }, title = { Text(active.title) }, text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) { active.fields.forEach { field -> if (field.key == "dob") DateField(values[field.key] ?: "", { values[field.key] = it }, field.label, limit = DateLimit.PAST_OR_TODAY, optional = !field.required) else OutlinedTextField(values[field.key] ?: "", { values[field.key] = it }, label = { Text(field.label) }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), minLines = if (field.key == "story") 5 else 1) }; if (formError.isNotBlank()) Text(formError, color = MaterialTheme.colorScheme.error) } }, confirmButton = { TextButton(enabled = !store.busy, onClick = { runCatching { require(active.fields.none { it.required && values[it.key].isNullOrBlank() }) { "Complete the required fields" }; val before = saveStatus(); active.save(values.toMap()); requireAcceptedSave(before, saveStatus()) }.onSuccess { form = null }.onFailure { formError = it.localizedMessage ?: "Review the values" } }) { Text("Save") } }, dismissButton = { TextButton(onClick = { form = null }) { Text("Cancel") } })
    } }
    recordTeeth?.let { teeth -> if (patient != null) RecordTreatmentDialog(
        patient, teeth, recordSurface,
        clinicianRecords(store.config).filter { it.optBoolean("active", true) }.map { it.optString("name") }.filter { it.isNotBlank() },
        store.config, onDismiss = { recordTeeth = null; recordSurface = "" },
    ) { changed, action ->
        store.save(changed, action)
        store.saveError ?: run { selecting = false; selection.clear(); focusTooth = teeth.first(); null }
    } }
    tooth?.let { number -> if (patient != null && canEditClinical(store.currentRole)) ToothEditor(number, patient, toothSurface, { tooth = null; toothSurface = "" }) { changed, action -> store.save(changed, action); if (store.saveError == null) tooth = null } }
}

private fun todaysVisits(store: ClinicStore): Int = runCatching {
    val zone = ClinicalRules.zone(store.config)
    val start = LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli()
    val end = start + 86_400_000L
    store.patients.sumOf { p ->
        p.optJSONArray("appointments")?.objects().orEmpty().count { a ->
            a.optString("status") !in setOf("Cancelled", "No show") && runCatching { ClinicalRules.appointmentMillis(a, store.config) in start until end }.getOrDefault(false)
        }
    }
}.getOrDefault(0)

@Composable private fun HomeHero(store: ClinicStore) {
    val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    val greeting = when (hour) { in 5..11 -> "Good morning"; in 12..16 -> "Good afternoon"; else -> "Good evening" }
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(Brush.linearGradient(listOf(Teal600, HeroDeep)))) {
        Canvas(Modifier.matchParentSize()) {
            drawCircle(Color.White.copy(alpha = .07f), size.height * .9f, androidx.compose.ui.geometry.Offset(size.width * .95f, size.height * .1f))
            drawCircle(Color.White.copy(alpha = .05f), size.height * .6f, androidx.compose.ui.geometry.Offset(size.width * .8f, size.height * 1.05f))
        }
        Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(Date()).uppercase(), style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = .75f))
            Text(greeting, style = MaterialTheme.typography.headlineMedium, color = Color.White)
            Text("Here is ${store.config.optString("name").ifBlank { "your clinic" }} at a glance.", style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = .85f))
        }
    }
}

@Composable private fun PatientHeader(p: JSONObject, showAlert: Boolean = true) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Avatar(p.optString("name"), 60.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(p.optString("name"), style = MaterialTheme.typography.headlineSmall, color = Ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(listOfNotNull(ageText(p.optString("dob")), p.optString("phone").ifBlank { null }, "ID ${p.optString("id").take(6).uppercase()}").joinToString(" · "),
                style = MaterialTheme.typography.bodySmall, color = InkMuted)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (p.optBoolean("dirty")) StatusPill("Sync pending", Amber100, Amber600) else StatusPill("Synced", Green50, Green600)
                if (p.optBoolean("archived")) StatusPill("Archived", SurfaceTint, InkMuted)
                if (p.optString("allergies").isNotBlank()) StatusPill("Medical alert", Rose50, Rose600)
            }
        }
    }
    if (showAlert && p.optString("allergies").isNotBlank()) AlertBanner("Medical alerts", p.optString("allergies"))
}

@Composable private fun DoctorChip(member: JSONObject, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(20.dp), color = SurfaceWhite, border = BorderStroke(1.dp, Hairline), shadowElevation = 1.dp, modifier = Modifier.width(150.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Avatar(member.optString("name"), 44.dp, listOf(Color(0xFF3B82F6), Teal700))
            Text(member.optString("name"), style = MaterialTheme.typography.titleSmall, color = Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(member.optString("role").ifBlank { "clinician" }.replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.bodySmall, color = InkMuted)
        }
    }
}

@Composable private fun Heading(title: String, subtitle: String) { Column(verticalArrangement = Arrangement.spacedBy(4.dp)) { Text(title, style = MaterialTheme.typography.headlineMedium, color = Ink); Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = InkMuted) } }
@Composable private fun CardBlock(title: String, body: String, background: Color = SurfaceWhite) { SurfaceCard(background = background) { Text(title, style = MaterialTheme.typography.titleSmall, color = Ink); Text(body, color = InkMuted, style = MaterialTheme.typography.bodyMedium) } }
@Composable private fun PatientCard(patient: JSONObject, click: () -> Unit) {
    SurfaceCard(onClick = click, padding = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Avatar(patient.optString("name"), 46.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(patient.optString("name"), style = MaterialTheme.typography.titleSmall, color = Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(listOfNotNull(ageText(patient.optString("dob")), patient.optString("phone").ifBlank { "No phone recorded" }).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = InkMuted)
            }
            if (patient.optString("allergies").isNotBlank()) Box(Modifier.size(8.dp).clip(CircleShape).background(Rose600).semantics { contentDescription = "Medical alert" })
            if (patient.optBoolean("archived")) StatusPill("Archived", SurfaceTint, InkMuted, dot = false)
            GlyphIcon(Glyph.Chevron, InkSubtle, 18.dp)
        }
    }
}

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
    AlertDialog(onDismissRequest = close, shape = RoundedCornerShape(28.dp), title = { Column { Text("Chart status · ${ToothNotation.label(number)}", style = MaterialTheme.typography.titleLarge); Text(ToothNotation.describe(number), style = MaterialTheme.typography.bodySmall, color = InkMuted) } }, text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
