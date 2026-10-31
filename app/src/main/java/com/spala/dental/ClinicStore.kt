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
    val cloudIdentity: String get() = runCatching { FirebaseAuth.getInstance().currentUser?.uid }.getOrNull().orEmpty()
    val currentRole: String get() {
        if (workspaceId.isBlank()) return "owner"
        val user = runCatching { FirebaseAuth.getInstance().currentUser?.uid }.getOrNull().orEmpty()
        return config.optJSONObject("members")?.optJSONObject(user)?.takeIf { it.optBoolean("active", true) }?.optString("role") ?: "unknown"
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
            storageAvailable = true
        } catch (e: Exception) { message = "Local records could not be opened. Existing storage is preserved; restore a backup before editing." }
        finally { busy = false }
    } }

    private suspend fun persist(records: List<JSONObject>) = withContext(Dispatchers.IO) {
        val state = JSONObject().put("version", 2).put("patients", JSONArray(records)).put("config", config)
            .put("workspaceId", workspaceId).put("conflicts", JSONArray(conflicts)).put("pendingExport", pendingExport).put("cloudReminders", cloudReminders).put("reminderRebuildRequired", reminderRebuildRequired)
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
        return (auth.currentUser ?: auth.signInAnonymously().await().user!!).uid
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
        message = if (retryOnly) "Pending cloud submissions confirmed" else "Cloud synchronization complete"
    }
    fun restore() = operation {
        check(patients.none { it.optBoolean("dirty") || it.has("operationId") }) { "Sync local changes or resolve conflicts before restoring cloud records." }
        val clinicId = clinic()
        val result = functions().getHttpsCallable("getClinic").call(mapOf("clinicId" to clinicId)).await().data as Map<*, *>
        reminderRebuildRequired = result["reminderRebuildRequired"] == true
        if (!config.optBoolean("dirty")) config = JSONObject(result["config"] as? Map<*, *> ?: emptyMap<String, Any>()).put("revision", (result["revision"] as? Number)?.toLong() ?: 0)
        val records = mutableListOf<JSONObject>()
        var cursor: String? = null
        do {
            val response = functions().getHttpsCallable("listPatients").call(mapOf("clinicId" to clinicId, "pageSize" to 100, "afterId" to cursor)).await().data as Map<*, *>
            val page = response["patients"] as? List<*> ?: emptyList<Any>()
            records += page.map { entry -> val doc = JSONObject(entry as Map<*, *>); doc.put("remoteVersion", doc.optLong("revision")).put("cloudOwner", clinicId).put("dirty", false) }
            cursor = (response["nextCursor"] as? String)?.takeIf { it.isNotBlank() }
        } while (cursor != null)
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
        val bytes = withContext(Dispatchers.IO) {
            val source = ImageDecoder.createSource(context.contentResolver, uri)
            val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                check(info.size.width.toLong() * info.size.height <= 20_000_000) { "Image exceeds 20 megapixels" }
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
            try { ByteArrayOutputStream().use { output -> check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) { "PNG encoding failed" }; output.toByteArray().also { check(it.isNotEmpty()) { "Image encoding produced no data" } } } } finally { bitmap.recycle() }
        }
        check(bytes.size <= 512 * 1024) { "Lossless image exceeds 512 KB. Choose a smaller image; no image detail was discarded." }
        val hash = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val path = "workspaces/$owner/patients/$patientId/$hash.png"
        if (patient.array("images").objects().none { it.optString("path") == path }) {
            val reference = FirebaseStorage.getInstance().reference.child(path)
            try { reference.metadata.await() } catch (e: com.google.firebase.storage.StorageException) {
                if (e.errorCode != com.google.firebase.storage.StorageException.ERROR_OBJECT_NOT_FOUND) throw e
                reference.putBytes(bytes, com.google.firebase.storage.StorageMetadata.Builder().setContentType("image/png").build()).await()
            }
            // Store private gs:// reference, not a permanent public download token.
            val copy = JSONObject(patient.toString())
            copy.array("images").put(JSONObject().put("path", path).put("reference", reference.toString()).put("bytes", bytes.size).put("at", System.currentTimeMillis()))
            copy.put("dirty", true)
            copy.remove("operationId")
            copy.array("history").put(JSONObject().put("id", uid()).put("at", System.currentTimeMillis()).put("text", "Clinical image attached"))
            val next = patients.map { if (it.optString("id") == patientId) copy else it }
            check(copy.toString().toByteArray().size <= 650_000) { "Patient document is full; export/archive before attaching more images." }
            persist(next); patients = next
        }
        message = "Image uploaded; synchronize to save its reference in the patient document"
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
        persist(patients); ClinicReminders.schedule(context, this@ClinicStore)
        FirebaseAuth.getInstance().signOut(); message = "Signed out; cloud records are preserved"
    }
    private fun operation(block: suspend () -> Unit) {
        if (!storageAvailable) { message = "Local storage is unavailable; cloud operations are paused."; return }
        if (busy) return
        retryableFailure = false
        busy = true
        scope.launch { try { block() } catch (e: Exception) {
            message = e.localizedMessage ?: "Operation failed"
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
