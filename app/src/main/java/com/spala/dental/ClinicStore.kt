package com.spala.dental

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.compose.runtime.*
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.firestore.FieldPath
import com.google.firebase.firestore.Query
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await
import org.json.*
import java.io.ByteArrayOutputStream
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

fun uid() = UUID.randomUUID().toString()
fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }
fun JSONObject.array(key: String): JSONArray = optJSONArray(key) ?: JSONArray().also { put(key, it) }
fun JSONObject.obj(key: String): JSONObject = optJSONObject(key) ?: JSONObject().also { put(key, it) }

/** One remote document contains the profile, chart, story, history and attachments. */
class ClinicStore(private val context: Context) {
    var patients by mutableStateOf<List<JSONObject>>(emptyList()); private set
    var message by mutableStateOf(""); private set
    var busy by mutableStateOf(true); private set
    var saveSequence = 0L; private set
    var saveError: String? = null; private set
    private fun rejectSave(reason: String) { saveError = reason; message = reason }
    var retryableFailure by mutableStateOf(false); private set
    var config by mutableStateOf(ClinicalRules.defaults()); private set
    var workspaceId by mutableStateOf(""); private set
    var conflicts by mutableStateOf<List<JSONObject>>(emptyList()); private set
    var pendingExport by mutableStateOf(JSONObject()); private set
    var cloudReminders by mutableStateOf(false); private set
    var reminderRebuildRequired by mutableStateOf(false); private set
    /** "loading", "login", "staff", "patient" or "local" (Firebase not configured: this device only). */
    var session by mutableStateOf("loading"); private set
    var portalRecord by mutableStateOf<JSONObject?>(null); private set
    var portalClinic by mutableStateOf(JSONObject()); private set
    var mustChangePassword by mutableStateOf(false); private set
    /** One-time credentials to hand to a patient (shown once, never stored). */
    var credentialNotice by mutableStateOf<JSONObject?>(null)
    private var memberRole = ""
    /** Clinic subscription as last verified by the server ({active, expiresAt, ...}). */
    var subscription by mutableStateOf(JSONObject()); private set
    /** Recent and scheduled patient notifications (from the clinic document). */
    var patientPushes by mutableStateOf<List<JSONObject>>(emptyList()); private set
    /** Offline (no Firebase) mode: entitlement comes straight from this device's Play purchases. */
    var deviceEntitled by mutableStateOf(false); private set
    /** Signed in with one of the platform super-admin Google accounts (free, no paywall). */
    val isSuperAdmin: Boolean get() = runCatching {
        val user = FirebaseAuth.getInstance().currentUser
        val email = user?.email?.lowercase()
        user != null && !user.isAnonymous && email != null && email in BuildConfig.SUPER_ADMIN_EMAILS.split(',').map { it.trim().lowercase() }
    }.getOrDefault(false)
    val subscriptionActive: Boolean get() = isSuperAdmin ||
        (subscription.optBoolean("active") && subscription.optLong("expiresAt") > System.currentTimeMillis()) || (session == "local" && deviceEntitled)
    /** The clinic a Play purchase is bound to. */
    val subscriptionClinicId: String get() = workspaceId.ifBlank { cloudIdentity.ifBlank { "local-device" } }
    private var lastPullAt = 0L
    private var localMode = false
    val cloudIdentity: String get() = runCatching { FirebaseAuth.getInstance().currentUser?.uid }.getOrNull().orEmpty()
    val currentRole: String get() {
        if (workspaceId.isBlank()) return "owner"
        val user = runCatching { FirebaseAuth.getInstance().currentUser?.uid }.getOrNull().orEmpty()
        return config.optJSONObject("members")?.optJSONObject(user)?.takeIf { it.optBoolean("active", true) }?.optString("role")
            ?: memberRole.ifBlank { "unknown" }
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var storageAvailable = false
    private val file = android.util.AtomicFile(java.io.File(context.filesDir, "clinic-v1.enc"))
    private val key: SecretKey get() {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return (ks.getKey("clinic-store", null) as? SecretKey) ?: KeyGenerator.getInstance("AES", "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("clinic-store", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    init { scope.launch {
        try {
            val state = withContext(Dispatchers.IO) {
                if (!file.baseFile.exists()) JSONObject() else {
                    val bytes = file.readFully()
                    require(bytes.size > 28) { "Invalid encrypted file" }
                    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                    cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
                    val raw = String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
                    if (raw.trimStart().startsWith("[")) JSONObject().put("patients", JSONArray(raw)) else JSONObject(raw)
                }
            }
            patients = state.optJSONArray("patients")?.objects() ?: emptyList()
            config = state.optJSONObject("config") ?: config
            workspaceId = state.optString("workspaceId")
            conflicts = state.optJSONArray("conflicts")?.objects() ?: emptyList()
            pendingExport = state.optJSONObject("pendingExport") ?: JSONObject()
            cloudReminders = state.optBoolean("cloudReminders")
            reminderRebuildRequired = state.optBoolean("reminderRebuildRequired")
            memberRole = state.optString("memberRole")
            lastPullAt = state.optLong("lastPullAt")
            localMode = state.optBoolean("localMode")
            subscription = state.optJSONObject("subscription") ?: JSONObject()
            storageAvailable = true
            resolveSession()
        } catch (e: Exception) { message = "Local records could not be opened. Existing storage is preserved; restore a backup before editing." }
        finally { busy = false; if (session == "loading") session = if (configured()) "login" else "local" }
    } }

    private suspend fun persist(records: List<JSONObject>) = withContext(Dispatchers.IO) {
        val state = JSONObject().put("version", 2).put("patients", JSONArray(records)).put("config", config)
            .put("workspaceId", workspaceId).put("conflicts", JSONArray(conflicts)).put("pendingExport", pendingExport).put("cloudReminders", cloudReminders).put("reminderRebuildRequired", reminderRebuildRequired)
            .put("memberRole", memberRole).put("lastPullAt", lastPullAt).put("localMode", localMode).put("subscription", subscription)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }
        val bytes = cipher.iv + cipher.doFinal(state.toString().toByteArray(Charsets.UTF_8))
        val out = file.startWrite()
        try { out.write(bytes); file.finishWrite(out) } catch (e: Exception) { file.failWrite(out); throw e }
    }
    fun saveConfig(next: JSONObject) = operation {
        check(!config.has("operationId")) { "Retry the pending settings submission or load cloud settings before editing" }
        ClinicalRules.validateConfig(next)
        config = JSONObject(next.toString()).put("dirty", true)
        persist(patients); ClinicReminders.schedule(context, this@ClinicStore); message = "Clinic settings saved on this device"
    }
    fun refreshClinicConfig() = operation {
        val clinicId = clinic()
        val result = functions().getHttpsCallable("getClinic").call(mapOf("clinicId" to clinicId)).await().data as Map<*, *>
        if (config.optBoolean("dirty")) conflicts = conflicts + JSONObject().put("id", uid()).put("name", "Clinic settings draft").put("config", config).put("retainedAt", System.currentTimeMillis())
        reminderRebuildRequired = result["reminderRebuildRequired"] == true
        (result["subscription"] as? Map<*, *>)?.let { subscription = JSONObject(it) }
        (result["patientPushes"] as? List<*>)?.let { list -> patientPushes = list.mapNotNull { (it as? Map<*, *>)?.let(::JSONObject) } }
        config = JSONObject(result["config"] as Map<*, *>).put("revision", (result["revision"] as? Number)?.toLong() ?: 0)
        persist(patients); message = "Clinic settings refreshed; any local draft was retained"
    }
    fun discardRetainedDraft(id: String, retainedAt: Long) = operation {
        conflicts = conflicts.filterNot { it.optString("id") == id && it.optLong("retainedAt") == retainedAt }
        persist(patients); message = "Exported draft removed from this device"
    }
    fun setWorkspace(id: String) = operation {
        require(id.isBlank() || id.matches(Regex("[A-Za-z0-9_-]{1,128}"))) { "Invalid clinic workspace ID" }
        require(patients.isEmpty() && !config.optBoolean("dirty") && !config.has("operationId")) { "Synchronize settings and close the current workspace before switching clinics" }
        workspaceId = id.trim(); persist(patients); message = "Workspace updated"
    }
    fun closeWorkspace() = operation {
        require(patients.none { it.optBoolean("dirty") || it.has("operationId") }) { "Sync or resolve all local changes before closing the workspace" }
        require(conflicts.isEmpty()) { "Resolve retained conflicts before closing this workspace" }
        require(!config.optBoolean("dirty") && !config.has("operationId")) { "Synchronize or retain the clinic settings draft before closing this workspace" }
        patients = emptyList(); config = ClinicalRules.defaults(); workspaceId = ""; pendingExport = JSONObject(); persist(patients)
        ClinicReminders.schedule(context, this@ClinicStore)
        message = "Local workspace closed. Cloud records are preserved."
    }
    private fun functions(): FirebaseFunctions {
        val resource = context.resources.getIdentifier("firebase_functions_region", "string", context.packageName)
        return FirebaseFunctions.getInstance(if (resource == 0) "us-central1" else context.getString(resource))
    }
    private suspend fun clinic(): String {
        val owner = account()
        return workspaceId.ifBlank { owner }
    }
    fun clearMessage() { message = "" }
    fun stageExport(payload: JSONObject, ready: () -> Unit) = operation {
        pendingExport = JSONObject(payload.toString()); persist(patients); ready()
    }
    fun clearExport() = operation { pendingExport = JSONObject(); persist(patients) }
    fun save(patient: JSONObject, action: String = "") {
        saveSequence++; saveError = null
        if (!storageAvailable) { rejectSave("Local storage is unavailable; existing records have been preserved."); return }
        if (busy) { rejectSave("Please wait for the current operation."); return }
        val existing = patients.firstOrNull { it.optString("id") == patient.optString("id") }
        if (currentRole in listOf("auditor", "unknown")) { rejectSave("This workspace is read-only for your account"); return }
        if (currentRole !in listOf("owner", "admin", "administrator")) {
            val clinical = listOf("allergies", "medicalHistory", "medications", "story", "chart", "treatments", "encounters", "performedProcedures", "images")
            val member = config.optJSONObject("members")?.optJSONObject(cloudIdentity)
            val clinicalWrite = currentRole == "dentist" || (currentRole == "assistant" && member?.optJSONObject("permissions")?.optBoolean("clinicalWrite") == true)
            val deniedClinical = !clinicalWrite && clinical.any { field ->
                val value = patient.opt(field)
                val emptyInitial = existing == null && (value == null || value == "" || (value is JSONArray && value.length() == 0) || (field == "chart" && (value as? JSONObject)?.optJSONObject("teeth")?.length() == 0))
                !emptyInitial && value.toString() != existing?.opt(field).toString()
            }
            val deniedBilling = currentRole != "cashier" && patient.opt("invoices").toString() != existing?.opt("invoices").toString()
            val deniedProfile = currentRole == "cashier" && listOf("name", "phone", "dob", "archived", "communicationConsent", "appointments").any { patient.opt(it).toString() != existing?.opt(it).toString() }
            if (deniedClinical || deniedBilling || deniedProfile) { rejectSave("Your clinic role does not allow this change"); return }
        }
        if (existing?.has("operationId") == true) { rejectSave("This patient has a pending cloud submission. Retry sync before editing."); return }
        if (existing != null && patient.optLong("updatedAt") != existing.optLong("updatedAt")) { rejectSave("This record changed while the editor was open. Reopen it before saving."); return }
        val copy = JSONObject(patient.toString())
        copy.remove("operationId")
        if (copy.has("chart")) {
        val chart = copy.obj("chart")
        chart.put("numberingSystem", "FDI").put("chartId", copy.optString("id"))
            .put("patientId", copy.optString("id")).put("clinicId", copy.optString("cloudOwner", "local"))
            .put("updatedBy", copy.optString("cloudOwner", "device"))
            .put("chartVersion", existing?.optJSONObject("chart")?.optInt("chartVersion") ?: 0)
            .put("updatedAt", java.time.Instant.now().toString())
        // Recompute visual workflow summaries from authoritative items inside this patient.
        val teeth = copy.obj("chart").obj("teeth")
        for (key in teeth.keys()) teeth.getJSONObject(key).put("treatmentStates", JSONArray())
        val workflow = mapOf("Planned" to "planned", "Accepted" to "accepted", "In progress" to "in_progress", "Deferred" to "deferred", "Referred" to "referred")
        for (item in copy.array("treatments").objects()) {
            val state = workflow[item.optString("status")] ?: continue
            val tooth = teeth.optJSONObject(item.optString("tooth")) ?: continue
            val states = tooth.array("treatmentStates")
            if ((0 until states.length()).none { states.getString(it) == state }) states.put(state)
        }
        }
        if (copy.has("chart")) {
            val oldChart = existing?.optJSONObject("chart")
            val clinicalChanged = oldChart?.optJSONObject("teeth").toString() != copy.obj("chart").obj("teeth").toString() ||
                oldChart?.optString("dentition") != copy.obj("chart").optString("dentition") ||
                listOf("story", "treatments", "encounters", "performedProcedures", "images", "allergies").any { existing?.opt(it).toString() != copy.opt(it).toString() }
            if (clinicalChanged) copy.obj("chart").put("chartVersion", (oldChart?.optInt("chartVersion") ?: 0) + 1)
        }
        try { PatientValidation.validate(copy, existing, config, patients) }
        catch (e: Exception) { rejectSave(e.localizedMessage ?: "Invalid patient update"); return }
        copy.put("updatedAt", System.currentTimeMillis()).put("dirty", true)
        if (action.isNotBlank()) {
            val previous = patients.firstOrNull { it.optString("id") == copy.optString("id") }
            val before = JSONObject()
            if (previous != null) for (field in listOf("name", "phone", "dob", "allergies", "story", "chart", "treatments", "appointments", "invoices", "encounters", "performedProcedures", "communicationConsent")) {
                if (!copy.has(field) && !previous.has(field)) continue
                if (previous.opt(field).toString() != copy.opt(field).toString()) before.put(field, previous.opt(field))
            }
            copy.array("history").put(JSONObject().put("id", uid()).put("at", System.currentTimeMillis()).put("text", action).put("previousValues", before))
        }
        if (copy.toString().toByteArray().size > 650_000) { rejectSave("Patient record is approaching its document limit. Export the record before adding more history."); return }
        busy = true
        scope.launch { try {
            val updated = patients.filterNot { it.getString("id") == copy.getString("id") } + copy
            persist(updated); patients = updated; ClinicReminders.schedule(context, this@ClinicStore); message = "Saved on this device"
        } catch (e: Exception) { message = "Save failed: ${e.localizedMessage}" } finally { busy = false } }
    }
    fun create(name: String, phone: String, dob: String, allergies: String) {
        require(name.isNotBlank()) { "Patient name is required" }
        if (dob.isNotBlank()) java.time.LocalDate.parse(dob)
        require(patients.none { it.optString("name").equals(name.trim(), true) && it.optString("phone") == phone.trim() }) { "A patient with this name and phone already exists" }
        val record = JSONObject().put("id", uid()).put("schemaVersion", 3).put("name", name.trim()).put("phone", phone.trim())
            .put("dob", dob).put("remoteVersion", 0).put("appointments", JSONArray()).put("history", JSONArray())
        if (currentRole in listOf("owner", "admin", "administrator", "dentist", "assistant")) record
            .put("allergies", allergies).put("story", "")
            .put("chart", JSONObject().put("schemaVersion", 2).put("catalogVersion", "fdi-v1").put("dentition", "permanent").put("teeth", JSONObject()))
            .put("treatments", JSONArray()).put("images", JSONArray())
        if (currentRole in listOf("owner", "admin", "administrator", "cashier")) record.put("invoices", JSONArray())
        save(record, "Patient registered")
    }
    private fun configured(): Boolean = FirebaseApp.getApps(context).isNotEmpty()
    private suspend fun account(): String {
        check(configured()) { "Configure Firebase resources before using cloud synchronization." }
        val auth = FirebaseAuth.getInstance()
        return (auth.currentUser ?: error("Sign in with Google to use the clinic cloud")).uid
    }
    fun sync(retryOnly: Boolean = false) = operation {
        val clinicId = clinic()
        if (config.optBoolean("dirty") && (!retryOnly || config.has("operationId"))) {
            val operationId = config.optString("operationId").ifBlank { uid() }
            config.put("operationId", operationId); persist(patients)
            val cloudConfig = JSONObject(config.toString()).apply { listOf("dirty", "revision", "operationId").forEach { remove(it) } }
            if (clinicId == cloudIdentity && !cloudConfig.obj("members").has(clinicId)) cloudConfig.obj("members").put(clinicId, JSONObject().put("role", "owner").put("active", true))
            val result = functions().getHttpsCallable("updateClinic").call(mapOf("clinicId" to clinicId,
                "expectedRevision" to config.optLong("revision"), "operationId" to operationId, "config" to toMap(cloudConfig))).await().data as Map<*, *>
            reminderRebuildRequired = result["reminderRebuildRequired"] == true
        (result["subscription"] as? Map<*, *>)?.let { subscription = JSONObject(it) }
        (result["patientPushes"] as? List<*>)?.let { list -> patientPushes = list.mapNotNull { (it as? Map<*, *>)?.let(::JSONObject) } }
            config = JSONObject(result["config"] as? Map<*, *> ?: toMap(config)).put("revision", (result["revision"] as? Number)?.toLong() ?: config.optLong("revision"))
            config.remove("operationId"); config.remove("dirty"); persist(patients)
        }
        val next = patients.toMutableList()
        for (patient in patients.filter { it.optBoolean("dirty") && (!retryOnly || it.has("operationId")) }) {
            check(patient.optString("cloudOwner", clinicId) == clinicId) { "This record belongs to another clinic workspace." }
            val operationId = patient.optString("operationId").ifBlank { uid() }
            patient.put("operationId", operationId).put("cloudOwner", clinicId); persist(patients)
            try {
                val result = functions().getHttpsCallable("commitPatient").call(mapOf(
                    "clinicId" to clinicId, "patientId" to patient.getString("id"), "expectedRevision" to patient.optLong("remoteVersion"),
                    "operationId" to operationId, "patient" to toMap(patient))).await().data as Map<*, *>
                val committed = (result["revision"] as Number).toLong()
                val clean = JSONObject(result["patient"] as? Map<*, *> ?: toMap(patient)).put("dirty", false).put("remoteVersion", committed).put("cloudOwner", clinicId)
                clean.remove("operationId")
                next[next.indexOfFirst { it.optString("id") == patient.optString("id") }] = clean
                persist(next); patients = next.toList()
            } catch (e: com.google.firebase.functions.FirebaseFunctionsException) {
                if (e.code == com.google.firebase.functions.FirebaseFunctionsException.Code.ABORTED) {
                    message = "Concurrent edit detected for ${patient.optString("name")}. Use Review conflict to preserve and resolve your draft."
                    return@operation
                }
                if (e.code in listOf(com.google.firebase.functions.FirebaseFunctionsException.Code.INVALID_ARGUMENT,
                        com.google.firebase.functions.FirebaseFunctionsException.Code.FAILED_PRECONDITION,
                        com.google.firebase.functions.FirebaseFunctionsException.Code.RESOURCE_EXHAUSTED)) {
                    patient.remove("operationId"); persist(patients)
                }
                throw e
            }
        }
        if (!retryOnly) runCatching { pullChanges() }
        message = if (retryOnly) "Pending cloud submissions confirmed" else "Cloud synchronization complete"
    }
    fun restore() = operation {
        check(patients.none { it.optBoolean("dirty") || it.has("operationId") }) { "Sync local changes or resolve conflicts before restoring cloud records." }
        val clinicId = clinic()
        val result = functions().getHttpsCallable("getClinic").call(mapOf("clinicId" to clinicId)).await().data as Map<*, *>
        reminderRebuildRequired = result["reminderRebuildRequired"] == true
        (result["subscription"] as? Map<*, *>)?.let { subscription = JSONObject(it) }
        (result["patientPushes"] as? List<*>)?.let { list -> patientPushes = list.mapNotNull { (it as? Map<*, *>)?.let(::JSONObject) } }
        if (!config.optBoolean("dirty")) config = JSONObject(result["config"] as? Map<*, *> ?: emptyMap<String, Any>()).put("revision", (result["revision"] as? Number)?.toLong() ?: 0)
        val records = mutableListOf<JSONObject>()
        var cursor: String? = null
        do {
            val response = functions().getHttpsCallable("listPatients").call(mapOf("clinicId" to clinicId, "pageSize" to 100, "afterId" to cursor)).await().data as Map<*, *>
            val page = response["patients"] as? List<*> ?: emptyList<Any>()
            records += page.map { entry -> val doc = JSONObject(entry as Map<*, *>); doc.put("remoteVersion", doc.optLong("revision")).put("cloudOwner", clinicId).put("dirty", false) }
            cursor = (response["nextCursor"] as? String)?.takeIf { it.isNotBlank() }
        } while (cursor != null)
        lastPullAt = System.currentTimeMillis() - 60_000
        persist(records); patients = records; ClinicReminders.schedule(context, this@ClinicStore); message = "Cloud records restored"
    }
    fun resolveConflict(patientId: String) = operation {
        val clinicId = clinic()
        val local = patients.first { it.optString("id") == patientId }
        val result = functions().getHttpsCallable("getPatient").call(mapOf("clinicId" to clinicId, "patientId" to patientId)).await().data as Map<*, *>
        val remote = JSONObject(result["patient"] as Map<*, *>)
        val retained = JSONObject(local.toString()).put("retainedAt", System.currentTimeMillis())
        conflicts = conflicts + retained
        val fresh = remote.put("remoteVersion", remote.optLong("revision")).put("dirty", false).put("cloudOwner", clinicId)
        val next = patients.map { if (it.optString("id") == patientId) fresh else it }
        persist(next); patients = next
        message = "Server record loaded. Your draft is retained under Conflict drafts for comparison and export. Reapply reviewed changes explicitly."
    }
    fun importPatient(raw: String) {
        require(raw.toByteArray().size <= 650_000) { "Imported document is too large" }
        val imported = JSONObject(raw)
        require(imported.optInt("schemaVersion") == 3 && imported.optString("name").isNotBlank()) { "Unsupported patient export" }
        val originalId = imported.optString("id")
        imported.put("id", uid()).put("remoteVersion", 0).put("importedFrom", originalId).put("name", imported.optString("name") + " (imported)")
        listOf("operationId", "cloudOwner", "audit", "receipts", "revision").forEach { imported.remove(it) }
        imported.put("images", JSONArray()) // Private image paths belong to the original workspace.
        save(imported, "Patient imported from export; verify identity and clinical data")
    }
    fun attach(patientId: String, uri: Uri) = operation {
        val owner = clinic()
        val patient = patients.first { it.optString("id") == patientId }
        check(patient.optString("cloudOwner", owner) == owner) { "Patient belongs to another account" }
        check(!patient.has("operationId")) { "Resolve the pending submission before attaching images" }
        val encoded = withContext(Dispatchers.IO) { ImageCompressor.encode(context, uri) }
        val bytes = encoded.bytes
        val hash = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val path = "workspaces/$owner/patients/$patientId/$hash.${encoded.extension}"
        if (patient.array("images").objects().none { it.optString("path") == path }) {
            val reference = FirebaseStorage.getInstance().reference.child(path)
            try { reference.metadata.await() } catch (e: com.google.firebase.storage.StorageException) {
                if (e.errorCode != com.google.firebase.storage.StorageException.ERROR_OBJECT_NOT_FOUND) throw e
                reference.putBytes(bytes, com.google.firebase.storage.StorageMetadata.Builder().setContentType(encoded.mimeType).build()).await()
            }
            // Store private gs:// reference, not a permanent public download token.
            val copy = JSONObject(patient.toString())
            copy.array("images").put(JSONObject().put("path", path).put("reference", reference.toString()).put("bytes", bytes.size)
                .put("width", encoded.width).put("height", encoded.height).put("format", encoded.extension).put("at", System.currentTimeMillis()))
            copy.put("dirty", true)
            copy.remove("operationId")
            copy.array("history").put(JSONObject().put("id", uid()).put("at", System.currentTimeMillis()).put("text", "Clinical image attached"))
            val next = patients.map { if (it.optString("id") == patientId) copy else it }
            check(copy.toString().toByteArray().size <= 650_000) { "Patient document is full; export/archive before attaching more images." }
            persist(next); patients = next
        }
        message = "Image uploaded (${bytes.size / 1024} KB, lossless ${encoded.extension.uppercase()}${if (encoded.resized) ", resized to ${encoded.width}×${encoded.height} to fit 512 KB" else ""}). Sync to save it to the patient record."
    }
    fun registerCloudReminders(enabled: Boolean) = operation {
        val clinicId = clinic()
        val token = com.google.firebase.messaging.FirebaseMessaging.getInstance().token.await()
        functions().getHttpsCallable("registerDevice").call(mapOf("clinicId" to clinicId, "token" to token, "enabled" to enabled)).await()
        cloudReminders = enabled; persist(patients); ClinicReminders.schedule(context, this@ClinicStore)
        message = if (enabled) "Cloud reminders enabled for this device" else "Cloud reminders disabled for this device"
    }
    suspend fun google(context: Context, clientId: String) {
        check(BuildConfig.GOOGLE_LOGIN_ENABLED) { "Google login is disabled for this release" }
        check(configured() && clientId.isNotBlank()) { "Configure Firebase and the Google web client ID" }
        val option = com.google.android.libraries.identity.googleid.GetGoogleIdOption.Builder().setServerClientId(clientId).setFilterByAuthorizedAccounts(false).build()
        val credential = androidx.credentials.CredentialManager.create(context).getCredential(context, androidx.credentials.GetCredentialRequest.Builder().addCredentialOption(option).build()).credential
        val token = com.google.android.libraries.identity.googleid.GoogleIdTokenCredential.createFrom(credential.data).idToken
        val auth = FirebaseAuth.getInstance(); val firebaseCredential = GoogleAuthProvider.getCredential(token, null)
        check(auth.currentUser == null || auth.currentUser?.isAnonymous == true || patients.isEmpty()) { "Close the existing synchronized workspace before switching Google accounts" }
        if (auth.currentUser?.isAnonymous == true) auth.currentUser!!.linkWithCredential(firebaseCredential).await() else auth.signInWithCredential(firebaseCredential).await()
    }
    fun signOut() = operation {
        require(patients.none { it.optBoolean("dirty") || it.has("operationId") } && !config.optBoolean("dirty")) { "Synchronize or export all changes before signing out" }
        require(conflicts.isEmpty()) { "Export and resolve retained drafts first" }
        if (cloudReminders) {
            val token = com.google.firebase.messaging.FirebaseMessaging.getInstance().token.await()
            functions().getHttpsCallable("registerDevice").call(mapOf("clinicId" to clinic(), "token" to token, "enabled" to false)).await()
        }
        patients = emptyList(); config = ClinicalRules.defaults(); workspaceId = ""; pendingExport = JSONObject(); cloudReminders = false
        memberRole = ""; lastPullAt = 0L; subscription = JSONObject()
        persist(patients); ClinicReminders.schedule(context, this@ClinicStore)
        FirebaseAuth.getInstance().signOut(); session = "login"; message = "Signed out; cloud records are preserved"
    }
    // ------------------------------------------------------------------------------------------
    // Sessions: clinic staff sign in with Google; patients with their Patient ID + password.
    // ------------------------------------------------------------------------------------------

    val cloudConfigured: Boolean get() = configured()

    private suspend fun resolveSession() {
        if (!configured()) { session = if (localMode) "local" else "login"; return }
        val user = runCatching { FirebaseAuth.getInstance().currentUser }.getOrNull()
        session = when {
            user == null -> "login"
            user.isAnonymous -> "staff" // legacy device workspaces keep working until linked to Google
            else -> runCatching {
                val claims = user.getIdToken(false).await().claims
                if (claims["role"] == "patient") { mustChangePassword = claims["mustChange"] == true; "patient" } else "staff"
            }.getOrDefault("staff")
        }
        if (session == "patient") scope.launch { runCatching { loadMyRecord() } }
    }

    /** Offline use on this device only, when no Firebase project is configured. */
    /** Sends a Play purchase token to the server, which verifies, acknowledges and stores it for the clinic. */
    fun verifyPurchase(purchaseToken: String) = operation {
        val result = functions().getHttpsCallable("verifySubscription").call(mapOf("clinicId" to clinic(), "purchaseToken" to purchaseToken)).await().data as Map<*, *>
        subscription = JSONObject(result["subscription"] as Map<*, *>)
        persist(patients)
        message = if (subscriptionActive) "Subscription active · thank you!" else "Google Play reports this subscription is not active"
    }

    fun refreshSubscription() {
        if (session == "local") return
        operation {
            val result = functions().getHttpsCallable("getClinic").call(mapOf("clinicId" to clinic())).await().data as Map<*, *>
            (result["subscription"] as? Map<*, *>)?.let { subscription = JSONObject(it) }
        (result["patientPushes"] as? List<*>)?.let { list -> patientPushes = list.mapNotNull { (it as? Map<*, *>)?.let(::JSONObject) } }
            persist(patients)
        }
    }

    fun markDeviceEntitled() { deviceEntitled = true }

    /** Admin: send now (sendAt = null) or schedule a notification to patients' phones. */
    fun queuePatientPush(patientIds: List<String>, title: String, body: String, sendAt: Long?, done: (String?) -> Unit) {
        if (busy) { done("Please wait for the current operation"); return }
        busy = true
        scope.launch {
            val error = runCatching {
                check(configured() && session == "staff") { "Patient notifications need the clinic cloud" }
                val data = mutableMapOf<String, Any?>("clinicId" to clinic(), "patientIds" to patientIds, "title" to title, "body" to body, "sendAt" to sendAt)
                val result = functions().getHttpsCallable("queuePatientPush").call(data).await().data as Map<*, *>
                message = if (result["status"] == "sent") "Notification delivered to ${(result["delivered"] as? Number)?.toInt() ?: 0} device(s)"
                    else "Notification scheduled"
                refreshPushes()
            }.exceptionOrNull()?.localizedMessage
            busy = false
            done(error)
        }
    }

    fun cancelPatientPush(jobId: String) = operation {
        functions().getHttpsCallable("cancelPatientPush").call(mapOf("clinicId" to clinic(), "jobId" to jobId)).await()
        refreshPushes(); message = "Scheduled notification cancelled"
    }

    fun loadPatientPushes() { if (session == "staff" && configured()) operation { refreshPushes() } }

    private suspend fun refreshPushes() {
        val result = functions().getHttpsCallable("getClinic").call(mapOf("clinicId" to clinic())).await().data as Map<*, *>
        (result["patientPushes"] as? List<*>)?.let { list -> patientPushes = list.mapNotNull { (it as? Map<*, *>)?.let(::JSONObject) } }
    }

    fun leaveLocalMode() = operation { localMode = false; persist(patients); session = "login" }

    fun useLocally() = operation {
        check(!configured()) { "This app is connected to the clinic cloud. Sign in with Google." }
        localMode = true; persist(patients); session = "local"
    }

    fun staffGoogleSignIn(activity: Context, clientId: String) = operation {
        google(activity, clientId)
        val result = functions().getHttpsCallable("resolveWorkspace").call(emptyMap<String, Any>()).await().data as Map<*, *>
        val clinicId = result["clinicId"] as String
        memberRole = result["role"] as? String ?: ""
        if (clinicId != cloudIdentity) {
            check(patients.none { it.optBoolean("dirty") }) { "Sync or export this device's changes before joining a clinic" }
            workspaceId = clinicId
        }
        persist(patients); session = "staff"
        pullAll(); message = "Signed in · clinic records loaded"
    }

    private fun patientEmail(loginId: String) = "${loginId.trim().lowercase()}@patient.dentalcare.local"

    fun patientSignIn(loginId: String, password: String, done: (String?) -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            val error = runCatching {
                check(configured()) { "The clinic cloud is not configured on this device" }
                require(loginId.trim().matches(Regex("[A-Za-z0-9]{6,12}"))) { "Enter the Patient ID given by your clinic" }
                require(password.isNotBlank()) { "Enter your password" }
                val auth = FirebaseAuth.getInstance()
                auth.signInWithEmailAndPassword(patientEmail(loginId), password).await()
                val claims = auth.currentUser!!.getIdToken(true).await().claims
                if (claims["role"] != "patient") { auth.signOut(); error("This account is not a patient login") }
                mustChangePassword = claims["mustChange"] == true
                session = "patient"
                loadMyRecord()
            }.exceptionOrNull()?.let { e ->
                if (e is com.google.firebase.auth.FirebaseAuthException) "Patient ID or password is incorrect" else friendlyError(e)
            }
            busy = false
            done(error)
        }
    }

    private suspend fun loadMyRecord() {
        val result = functions().getHttpsCallable("getMyRecord").call(emptyMap<String, Any>()).await().data as Map<*, *>
        portalRecord = JSONObject(result["patient"] as Map<*, *>)
        portalClinic = JSONObject(result["clinic"] as? Map<*, *> ?: emptyMap<String, Any>())
        // Reminders come only as server pushes; the FCM token is kept in the patient's own document.
        PatientReminders.cancelAll(context) // clears reminders scheduled on-device by older app versions
        runCatching { registerPatientDevice() }
    }

    /** Free/booked slots for the patient app (getSlots: one clinic read, no other patients' names). */
    var portalSlots by mutableStateOf<List<JSONObject>>(emptyList()); private set
    var portalBookingEnabled by mutableStateOf(true); private set

    fun loadSlots(from: java.time.LocalDate) {
        scope.launch { runCatching {
            val result = functions().getHttpsCallable("getSlots").call(mapOf("from" to from.toString(), "days" to 42)).await().data as Map<*, *>
            portalSlots = (result["slots"] as? List<*>).orEmpty().map { JSONObject(it as Map<*, *>) }
            portalBookingEnabled = result["patientBooking"] != false
        }.onFailure { message = it.localizedMessage ?: "Could not load appointment slots" } }
    }

    fun bookSlot(startAt: Long, doctor: String, reason: String, done: (String?) -> Unit) = selfService("bookSlot", mapOf("startAt" to startAt, "doctor" to doctor, "reason" to reason), "Appointment booked", done)
    fun cancelMyAppointment(appointmentId: String, done: (String?) -> Unit) = selfService("cancelMyAppointment", mapOf("appointmentId" to appointmentId), "Appointment cancelled", done)
    private fun selfService(name: String, data: Map<String, Any>, success: String, done: (String?) -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            val error = runCatching {
                functions().getHttpsCallable(name).call(data).await()
                loadMyRecord()
                portalSlots.firstOrNull()?.optString("date")?.let { loadSlots(java.time.LocalDate.parse(it)) }
                message = success
            }.exceptionOrNull()?.localizedMessage
            busy = false
            done(error)
        }
    }

    fun refreshMyRecord() = operation { loadMyRecord(); message = "Your record is up to date" }

    /** Patient changes the temporary (or current) password; Firebase requires recent authentication. */
    fun changePatientPassword(current: String, next: String, done: (String?) -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            val error = runCatching {
                require(next.length >= 8) { "Use at least 8 characters" }
                require(next.any { it.isDigit() } && next.any { it.isLetter() }) { "Use letters and numbers" }
                val user = FirebaseAuth.getInstance().currentUser ?: error("Sign in again")
                user.reauthenticate(com.google.firebase.auth.EmailAuthProvider.getCredential(user.email!!, current)).await()
                user.updatePassword(next).await()
                functions().getHttpsCallable("completePasswordChange").call(emptyMap<String, Any>()).await()
                user.getIdToken(true).await()
                mustChangePassword = false
            }.exceptionOrNull()?.let { e ->
                if (e is com.google.firebase.auth.FirebaseAuthInvalidCredentialsException) "Current password is incorrect" else e.localizedMessage ?: "Password change failed"
            }
            busy = false
            done(error)
        }
    }

    /** Writes this device's FCM token into the signed-in patient's Firestore document (no Cloud Function). */
    suspend fun registerPatientDevice(token: String? = null) {
        val claims = FirebaseAuth.getInstance().currentUser?.getIdToken(false)?.await()?.claims ?: return
        if (claims["role"] != "patient") return
        PatientPush.register(context, claims["clinicId"] as String, claims["patientId"] as String, token)
    }

    fun patientSignOut() {
        PatientReminders.cancelAll(context)
        scope.launch {
            // Remove this phone's token first (needs the patient's sign-in), then sign out.
            runCatching {
                val claims = FirebaseAuth.getInstance().currentUser?.getIdToken(false)?.await()?.claims
                if (claims?.get("role") == "patient") PatientPush.unregister(context, claims["clinicId"] as String, claims["patientId"] as String)
            }
            runCatching { FirebaseAuth.getInstance().signOut() }
            portalRecord = null; mustChangePassword = false; session = "login"
        }
    }

    /** Admin: create the patient's app login. The temporary password is returned once for sharing. */
    fun createPatientLogin(patientId: String) = portalCall("createPatientLogin", patientId)
    fun resetPatientPassword(patientId: String) = portalCall("resetPatientPassword", patientId)
    private fun portalCall(name: String, patientId: String) = operation {
        val patient = patients.first { it.optString("id") == patientId }
        check(!patient.optBoolean("dirty") && patient.optLong("remoteVersion") > 0) { "Sync this patient to the cloud first, then create the login" }
        val result = functions().getHttpsCallable(name).call(mapOf("clinicId" to clinic(), "patientId" to patientId)).await().data as Map<*, *>
        val portal = JSONObject(result["portal"] as Map<*, *>)
        val next = patients.map { if (it.optString("id") == patientId) JSONObject(it.toString()).put("portal", portal) else it }
        persist(next); patients = next
        credentialNotice = JSONObject().put("name", patient.optString("name")).put("loginId", result["loginId"]).put("password", result["password"])
            .put("reset", name == "resetPatientPassword")
    }

    /** Incremental pull: only patient documents changed since the last pull are read. */
    private suspend fun pullChanges() {
        if (lastPullAt == 0L) { pullAll(); return }
        val clinicId = clinic()
        var cursor: String? = null; var cursorAt: Any? = null; var serverTime = lastPullAt
        val next = patients.toMutableList()
        do {
            val response = functions().getHttpsCallable("listPatients").call(mapOf("clinicId" to clinicId, "pageSize" to 100,
                "updatedAfter" to lastPullAt, "afterId" to cursor, "afterAt" to cursorAt)).await().data as Map<*, *>
            (response["serverTime"] as? Number)?.let { serverTime = it.toLong() }
            for (entry in response["patients"] as? List<*> ?: emptyList<Any>()) {
                val doc = JSONObject(entry as Map<*, *>)
                val fresh = doc.put("remoteVersion", doc.optLong("revision")).put("cloudOwner", clinicId).put("dirty", false)
                val index = next.indexOfFirst { it.optString("id") == fresh.optString("id") }
                when {
                    index < 0 -> next += fresh
                    !next[index].optBoolean("dirty") && !next[index].has("operationId") -> next[index] = fresh
                }
            }
            cursor = (response["nextCursor"] as? String)?.takeIf { it.isNotBlank() }
            cursorAt = response["nextAt"]
        } while (cursor != null)
        lastPullAt = serverTime - 60_000 // small overlap tolerates clock skew between instances
        persist(next); patients = next
    }

    private suspend fun pullAll() {
        val clinicId = clinic()
        val result = functions().getHttpsCallable("getClinic").call(mapOf("clinicId" to clinicId)).await().data as Map<*, *>
        reminderRebuildRequired = result["reminderRebuildRequired"] == true
        (result["subscription"] as? Map<*, *>)?.let { subscription = JSONObject(it) }
        (result["patientPushes"] as? List<*>)?.let { list -> patientPushes = list.mapNotNull { (it as? Map<*, *>)?.let(::JSONObject) } }
        if (!config.optBoolean("dirty")) config = JSONObject(result["config"] as? Map<*, *> ?: emptyMap<String, Any>()).put("revision", (result["revision"] as? Number)?.toLong() ?: 0)
        val dirty = patients.filter { it.optBoolean("dirty") || it.has("operationId") }
        val records = mutableListOf<JSONObject>()
        var cursor: String? = null; var serverTime = System.currentTimeMillis()
        do {
            val response = functions().getHttpsCallable("listPatients").call(mapOf("clinicId" to clinicId, "pageSize" to 100, "afterId" to cursor)).await().data as Map<*, *>
            (response["serverTime"] as? Number)?.let { serverTime = it.toLong() }
            records += (response["patients"] as? List<*> ?: emptyList<Any>()).map { entry -> val doc = JSONObject(entry as Map<*, *>); doc.put("remoteVersion", doc.optLong("revision")).put("cloudOwner", clinicId).put("dirty", false) }
            cursor = (response["nextCursor"] as? String)?.takeIf { it.isNotBlank() }
        } while (cursor != null)
        // Unsynchronized local edits are never overwritten by a pull.
        val merged = records.filterNot { r -> dirty.any { it.optString("id") == r.optString("id") } } + dirty
        lastPullAt = serverTime - 60_000
        persist(merged); patients = merged; ClinicReminders.schedule(context, this@ClinicStore)
    }

    /** Turns raw Firebase error codes (e.g. "NOT_FOUND") into actionable messages. */
    private fun friendlyError(e: Throwable): String {
        val code = (e as? com.google.firebase.functions.FirebaseFunctionsException)?.code
        return when (code) {
            com.google.firebase.functions.FirebaseFunctionsException.Code.NOT_FOUND ->
                if (e.message.orEmpty().equals("NOT_FOUND", true) || e.message.isNullOrBlank())
                    "The clinic cloud is not set up yet: its Cloud Functions are not deployed (or are in a different region). Run deploy.bat, then try again."
                else e.message!!
            com.google.firebase.functions.FirebaseFunctionsException.Code.UNAUTHENTICATED -> "Your sign-in expired. Sign in again."
            com.google.firebase.functions.FirebaseFunctionsException.Code.UNAVAILABLE -> "The clinic cloud is unreachable. Check the internet connection and try again."
            com.google.firebase.functions.FirebaseFunctionsException.Code.INTERNAL -> "The clinic cloud reported an internal error. Check the Cloud Functions logs in the Firebase console."
            else -> e.localizedMessage ?: "Operation failed"
        }
    }

    private fun operation(block: suspend () -> Unit) {
        if (!storageAvailable) { message = "Local storage is unavailable; cloud operations are paused."; return }
        if (busy) return
        retryableFailure = false
        busy = true
        scope.launch { try { block() } catch (e: Exception) {
            message = friendlyError(e)
            val transient = e is java.io.IOException || e is com.google.firebase.FirebaseNetworkException ||
                (e is com.google.firebase.functions.FirebaseFunctionsException && e.code in listOf(
                    com.google.firebase.functions.FirebaseFunctionsException.Code.UNAVAILABLE,
                    com.google.firebase.functions.FirebaseFunctionsException.Code.DEADLINE_EXCEEDED))
            retryableFailure = transient
            if (transient && (patients.any { it.has("operationId") } || config.has("operationId"))) {
                val work = androidx.work.OneTimeWorkRequestBuilder<CloudRetryWorker>()
                    .setConstraints(androidx.work.Constraints.Builder().setRequiredNetworkType(androidx.work.NetworkType.CONNECTED).build())
                    .setBackoffCriteria(androidx.work.BackoffPolicy.EXPONENTIAL, 30, java.util.concurrent.TimeUnit.SECONDS).build()
                androidx.work.WorkManager.getInstance(context).enqueueUniqueWork("pending-clinical-sync", androidx.work.ExistingWorkPolicy.KEEP, work)
            }
        } finally { busy = false } }
    }
    private fun toMap(json: JSONObject): Map<String, Any?> = json.keys().asSequence().associateWith { key -> convert(json.get(key)) }
    private fun convert(value: Any?): Any? = when (value) { JSONObject.NULL -> null; is JSONObject -> toMap(value); is JSONArray -> (0 until value.length()).map { convert(value.get(it)) }; else -> value }
}
