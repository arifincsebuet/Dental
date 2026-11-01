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
import com.google.firebase.storage.FirebaseStorage
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
    /** "loading", "login", "staff", "patient" or "local" (Firebase not configured: this device only). */
    var session by mutableStateOf("loading"); private set
    var portalRecord by mutableStateOf<JSONObject?>(null); private set
    var portalClinic by mutableStateOf(JSONObject()); private set
    var mustChangePassword by mutableStateOf(false); private set
    /** One-time credentials to hand to a patient (shown once, never stored). */
    var credentialNotice by mutableStateOf<JSONObject?>(null)
    private var memberRole = ""
    /** Clinic subscription recorded by the admin device holding the Play purchase ({active, expiresAt, ...}). */
    var subscription by mutableStateOf(JSONObject()); private set
    /** Clinic owned by a platform super admin: free for all its staff. */
    private var complimentary = false
    /** Signed-in patient's record path (workspaces/{clinic}/patients/{id}). */
    private var portalPath = ""
    /** Hash of the busy times last published to public/info (avoids repeated writes). */
    private var busyHash = 0
    /** Offline (no Firebase) mode: entitlement comes straight from this device's Play purchases. */
    var deviceEntitled by mutableStateOf(false); private set
    /** Signed in with one of the platform super-admin Google accounts (free, no paywall). */
    val isSuperAdmin: Boolean get() = runCatching {
        val user = FirebaseAuth.getInstance().currentUser
        val email = user?.email?.lowercase()
        user != null && !user.isAnonymous && email != null && email in BuildConfig.SUPER_ADMIN_EMAILS.split(',').map { it.trim().lowercase() }
    }.getOrDefault(false)
    val subscriptionActive: Boolean get() = isSuperAdmin || complimentary ||
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
            complimentary = state.optBoolean("complimentary")
            portalPath = state.optString("portalPath")
            busyHash = state.optInt("busyHash")
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
            .put("workspaceId", workspaceId).put("conflicts", JSONArray(conflicts)).put("pendingExport", pendingExport)
            .put("memberRole", memberRole).put("lastPullAt", lastPullAt).put("localMode", localMode).put("subscription", subscription)
            .put("complimentary", complimentary).put("portalPath", portalPath).put("busyHash", busyHash)
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
        val state = ClinicCloud.getClinic(clinicId)
        if (config.optBoolean("dirty")) conflicts = conflicts + JSONObject().put("id", uid()).put("name", "Clinic settings draft").put("config", config).put("retainedAt", System.currentTimeMillis())
        applyClinic(state)
        if (state == null) config = ClinicalRules.defaults().put("revision", 0)
        persist(patients); message = "Clinic settings refreshed; any local draft was retained"
    }
    /** Takes the cloud clinic settings and subscription (keeps an unsynced local settings draft). */
    private fun applyClinic(state: ClinicCloud.ClinicState?, keepDraft: Boolean = false) {
        if (state == null) return
        subscription = state.subscription; complimentary = state.complimentary
        if (!(keepDraft && config.optBoolean("dirty"))) config = JSONObject(state.config.toString()).put("revision", state.revision)
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
        val me = cloudIdentity
        if (config.optBoolean("dirty") && (!retryOnly || config.has("operationId"))) {
            config.put("operationId", config.optString("operationId").ifBlank { uid() }); persist(patients)
            val state = ClinicCloud.commitClinic(clinicId, me, config, config.optLong("revision"), isSuperAdmin && clinicId == me)
            applyClinic(state)
            persist(patients)
        }
        val next = patients.toMutableList()
        for (patient in patients.filter { it.optBoolean("dirty") && (!retryOnly || it.has("operationId")) }) {
            check(patient.optString("cloudOwner", clinicId) == clinicId) { "This record belongs to another clinic workspace." }
            patient.put("operationId", patient.optString("operationId").ifBlank { uid() }).put("cloudOwner", clinicId); persist(patients)
            try {
                val committed = ClinicCloud.commitPatient(clinicId, me, patient)
                val clean = committed.put("dirty", false).put("remoteVersion", committed.optLong("revision")).put("cloudOwner", clinicId)
                next[next.indexOfFirst { it.optString("id") == patient.optString("id") }] = clean
                persist(next); patients = next.toList()
            } catch (e: Exception) {
                val conflict = e as? ClinicCloud.Conflict ?: e.cause as? ClinicCloud.Conflict
                if (conflict != null) { message = conflict.message.orEmpty(); return@operation }
                // A rejected record (too large, refused by the rules) is not retried automatically.
                if (e is IllegalStateException || e.cause is IllegalStateException ||
                    (e as? com.google.firebase.firestore.FirebaseFirestoreException)?.code == com.google.firebase.firestore.FirebaseFirestoreException.Code.PERMISSION_DENIED) {
                    patient.remove("operationId"); persist(patients)
                }
                throw e
            }
        }
        if (!retryOnly) { runCatching { pullChanges() }; runCatching { publishBusyTimes(clinicId) } }
        message = if (retryOnly) "Pending cloud submissions confirmed" else "Cloud synchronization complete"
    }
    /** Keeps patient booking's anonymous busy times in step with the clinic's appointments (written only on change). */
    private suspend fun publishBusyTimes(clinicId: String) {
        if (currentRole in listOf("auditor", "unknown")) return
        val busy = ClinicCloud.busyTimes(patients, config)
        val hash = busy.hashCode()
        if (hash == busyHash) return
        ClinicCloud.publishBusy(clinicId, busy)
        busyHash = hash; persist(patients)
    }
    fun restore() = operation {
        check(patients.none { it.optBoolean("dirty") || it.has("operationId") }) { "Sync local changes or resolve conflicts before restoring cloud records." }
        lastPullAt = 0L
        pullAll()
        message = "Cloud records restored"
    }
    fun resolveConflict(patientId: String) = operation {
        val clinicId = clinic()
        val local = patients.first { it.optString("id") == patientId }
        val remote = ClinicCloud.getPatient(clinicId, patientId) ?: error("This patient is not in the cloud yet")
        val retained = JSONObject(local.toString()).put("retainedAt", System.currentTimeMillis())
        conflicts = conflicts + retained
        val fresh = remote.put("remoteVersion", remote.optLong("revision")).put("dirty", false).put("cloudOwner", clinicId)
        val next = patients.map { if (it.optString("id") == patientId) fresh else it }
        persist(next); patients = next
        message = "Cloud record loaded. Your draft is retained under Conflict drafts for comparison and export. Reapply reviewed changes explicitly."
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
        patients = emptyList(); config = ClinicalRules.defaults(); workspaceId = ""; pendingExport = JSONObject()
        memberRole = ""; lastPullAt = 0L; subscription = JSONObject(); complimentary = false; busyHash = 0
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
            ClinicCloud.isPatientAccount(user) -> "patient"
            else -> "staff"
        }
        if (session == "patient") scope.launch { runCatching { loadMyRecord() } }
    }

    /**
     * A Google Play purchase on this device (already acknowledged by [BillingManager]). Without a
     * server the device's Play record is the proof: the admin's device records the clinic-wide
     * subscription in the clinic document so every staff device unlocks. It is refreshed whenever
     * Play reports the purchase again (each app start), so a lapsed subscription expires.
     */
    fun recordPurchase(purchase: com.android.billingclient.api.Purchase) {
        if (session == "local") { deviceEntitled = true; return }
        val boundTo = purchase.accountIdentifiers?.obfuscatedAccountId
        if (boundTo != null && boundTo != BillingManager.accountTag(subscriptionClinicId)) return // bought for another clinic
        val renewed = JSONObject().put("active", true).put("productId", purchase.products.firstOrNull().orEmpty())
            .put("orderId", purchase.orderId.orEmpty()).put("autoRenewing", purchase.isAutoRenewing).put("purchaseTime", purchase.purchaseTime)
            // Monthly plan: valid until a few days after the next renewal; the next refresh extends it.
            .put("expiresAt", System.currentTimeMillis() + 34L * 86_400_000L).put("verifiedAt", System.currentTimeMillis()).put("linkedBy", cloudIdentity)
        if (subscription.optBoolean("active") && subscription.optLong("verifiedAt") > System.currentTimeMillis() - 86_400_000L) return // refreshed today
        if (currentRole !in listOf("owner", "admin", "administrator")) return
        operation {
            // Unlock this device at once; then share the entitlement with the clinic's other devices.
            subscription = renewed; persist(patients)
            val clinicId = clinic()
            if (ClinicCloud.getClinic(clinicId) == null && clinicId == cloudIdentity)
                applyClinic(ClinicCloud.commitClinic(clinicId, cloudIdentity, config, 0, isSuperAdmin)) // saves any settings draft too
            ClinicCloud.saveSubscription(clinicId, renewed)
            subscription = renewed; persist(patients)
            message = "Subscription active · thank you!"
        }
    }

    fun refreshSubscription() {
        if (session != "staff") return
        operation { applyClinic(ClinicCloud.getClinic(clinic()), keepDraft = true); persist(patients) }
    }

    fun markDeviceEntitled() { deviceEntitled = true }

    fun leaveLocalMode() = operation { localMode = false; persist(patients); session = "login" }

    fun useLocally() = operation {
        check(!configured()) { "This app is connected to the clinic cloud. Sign in with Google." }
        localMode = true; persist(patients); session = "local"
    }

    fun staffGoogleSignIn(activity: Context, clientId: String) = operation {
        google(activity, clientId)
        val user = FirebaseAuth.getInstance().currentUser ?: error("Google sign-in did not complete")
        check(!ClinicCloud.isPatientAccount(user)) { "Patient accounts cannot open the clinic workspace" }
        val workspace = ClinicCloud.resolveWorkspace(user)
        val clinicId = workspace.clinicId
        memberRole = workspace.role
        if (clinicId != cloudIdentity) {
            check(patients.none { it.optBoolean("dirty") }) { "Sync or export this device's changes before joining a clinic" }
            workspaceId = clinicId
        }
        persist(patients); session = "staff"
        pullAll(); message = "Signed in · clinic records loaded"
    }

    fun patientSignIn(loginId: String, password: String, done: (String?) -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            val error = runCatching {
                check(configured()) { "The clinic cloud is not configured on this device" }
                require(loginId.trim().matches(Regex("[A-Za-z0-9]{6,12}"))) { "Enter the Patient ID given by your clinic" }
                require(password.isNotBlank()) { "Enter your password" }
                val auth = FirebaseAuth.getInstance()
                auth.signInWithEmailAndPassword(ClinicCloud.patientEmail(loginId), password).await()
                val ref = try { ClinicCloud.findMyRecord(auth.currentUser!!.uid) } catch (e: Exception) { auth.signOut(); throw e }
                if (ref == null) { auth.signOut(); error("This Patient ID is no longer active. Ask the clinic for your current Patient ID.") }
                portalPath = ref.path; persist(patients)
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
        if (portalPath.isBlank()) {
            val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
            portalPath = ClinicCloud.findMyRecord(uid)?.path ?: error("Your record is not available. Contact the clinic.")
            persist(patients)
        }
        val mine = ClinicCloud.loadMyRecord(portalPath)
        portalRecord = mine.patient
        portalClinic = mine.clinic
        clinicInfoAt = System.currentTimeMillis()
        mustChangePassword = mine.patient.optJSONObject("portal")?.optBoolean("mustChange") == true
        // Reminders are scheduled on this phone from the patient's own appointments (no server pushes).
        PatientReminders.schedule(context, mine.patient, mine.clinic)
    }

    private var clinicInfoAt = 0L
    /** Re-reads the clinic's slots and busy times for booking (at most once a minute). */
    fun refreshClinicInfo() {
        if (session != "patient" || busy || System.currentTimeMillis() - clinicInfoAt < 60_000) return
        scope.launch { runCatching { loadMyRecord() } }
    }

    fun bookSlot(startAt: Long, doctor: String, reason: String, done: (String?) -> Unit) =
        selfService("Appointment booked", done) { ClinicCloud.bookSlot(portalPath, cloudIdentity, startAt, doctor, reason) }
    fun cancelMyAppointment(appointmentId: String, done: (String?) -> Unit) =
        selfService("Appointment cancelled", done) { ClinicCloud.cancelMyAppointment(portalPath, cloudIdentity, appointmentId) }
    private fun selfService(success: String, done: (String?) -> Unit, block: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            val error = runCatching { block(); loadMyRecord(); message = success }.exceptionOrNull()?.let { friendlyError(it) }
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
                if (mustChangePassword) ClinicCloud.completePasswordChange(portalPath)
                mustChangePassword = false
            }.exceptionOrNull()?.let { e ->
                if (e is com.google.firebase.auth.FirebaseAuthInvalidCredentialsException) "Current password is incorrect" else e.localizedMessage ?: "Password change failed"
            }
            busy = false
            done(error)
        }
    }

    fun patientSignOut() {
        PatientReminders.cancelAll(context)
        scope.launch {
            runCatching { FirebaseAuth.getInstance().signOut() }
            portalRecord = null; mustChangePassword = false; portalPath = ""; persist(patients); session = "login"
        }
    }

    /** Admin: create the patient's app login. The temporary password is returned once for sharing. */
    fun createPatientLogin(patientId: String) = portalCall(patientId, reset = false)
    /** Issues a new Patient ID and temporary password; the previous Patient ID stops working. */
    fun resetPatientPassword(patientId: String) = portalCall(patientId, reset = true)
    private fun portalCall(patientId: String, reset: Boolean) = operation {
        val patient = patients.first { it.optString("id") == patientId }
        check(!patient.optBoolean("dirty") && patient.optLong("remoteVersion") > 0) { "Sync this patient to the cloud first, then create the login" }
        check(isSuperAdmin || currentRole in listOf("owner", "admin", "administrator", "dentist")) { "Only clinic administrators manage patient logins" }
        val login = ClinicCloud.createPatientLogin(context, clinic(), cloudIdentity, patientId, reset)
        val next = patients.map { if (it.optString("id") == patientId) JSONObject(it.toString()).put("portal", login.portal).put("remoteVersion", login.revision).put("revision", login.revision) else it }
        persist(next); patients = next
        credentialNotice = JSONObject().put("name", patient.optString("name")).put("loginId", login.loginId).put("password", login.password).put("reset", reset)
    }

    /** Incremental pull: only patient documents changed since the last pull are read. */
    private suspend fun pullChanges() {
        if (lastPullAt == 0L) { pullAll(); return }
        val clinicId = clinic()
        val (records, newest) = ClinicCloud.listPatients(clinicId, lastPullAt)
        val next = patients.toMutableList()
        for (doc in records) {
            val fresh = doc.put("remoteVersion", doc.optLong("revision")).put("cloudOwner", clinicId).put("dirty", false)
            val index = next.indexOfFirst { it.optString("id") == fresh.optString("id") }
            when {
                index < 0 -> next += fresh
                !next[index].optBoolean("dirty") && !next[index].has("operationId") -> next[index] = fresh
            }
        }
        if (newest > 0) lastPullAt = maxOf(lastPullAt, newest - 60_000) // small overlap tolerates commit-time skew
        persist(next); patients = next; ClinicReminders.schedule(context, this@ClinicStore)
    }

    private suspend fun pullAll() {
        val clinicId = clinic()
        applyClinic(ClinicCloud.getClinic(clinicId), keepDraft = true)
        val dirty = patients.filter { it.optBoolean("dirty") || it.has("operationId") }
        val startedAt = System.currentTimeMillis()
        val (docs, newest) = ClinicCloud.listPatients(clinicId, null)
        val records = docs.map { doc -> doc.put("remoteVersion", doc.optLong("revision")).put("cloudOwner", clinicId).put("dirty", false) }
        // Unsynchronized local edits are never overwritten by a pull.
        val merged = records.filterNot { r -> dirty.any { it.optString("id") == r.optString("id") } } + dirty
        lastPullAt = (if (newest > 0) newest else startedAt) - 60_000
        persist(merged); patients = merged; ClinicReminders.schedule(context, this@ClinicStore)
    }

    /** Turns raw Firebase error codes into actionable messages. */
    private fun friendlyError(e: Throwable): String {
        val code = (e as? com.google.firebase.firestore.FirebaseFirestoreException)?.code
            ?: (e.cause as? com.google.firebase.firestore.FirebaseFirestoreException)?.code
        return when (code) {
            com.google.firebase.firestore.FirebaseFirestoreException.Code.PERMISSION_DENIED ->
                "The clinic cloud refused this change. Check that your account is an active clinic member with this permission, and that the latest Firestore rules are deployed (deploy.bat rules)."
            com.google.firebase.firestore.FirebaseFirestoreException.Code.FAILED_PRECONDITION ->
                "The clinic cloud is missing an index or setting. Run deploy.bat to deploy the Firestore rules and indexes, then try again."
            com.google.firebase.firestore.FirebaseFirestoreException.Code.UNAUTHENTICATED -> "Your sign-in expired. Sign in again."
            com.google.firebase.firestore.FirebaseFirestoreException.Code.UNAVAILABLE -> "The clinic cloud is unreachable. Check the internet connection and try again."
            com.google.firebase.firestore.FirebaseFirestoreException.Code.ABORTED -> "Another device saved at the same moment. Try again."
            // Checks inside a Firestore transaction may arrive wrapped; show the original message.
            else -> (e.cause as? IllegalStateException ?: e.cause as? IllegalArgumentException ?: e.cause as? ClinicCloud.Conflict)?.message
                ?: e.localizedMessage ?: "Operation failed"
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
                (e is com.google.firebase.firestore.FirebaseFirestoreException && e.code in listOf(
                    com.google.firebase.firestore.FirebaseFirestoreException.Code.UNAVAILABLE,
                    com.google.firebase.firestore.FirebaseFirestoreException.Code.DEADLINE_EXCEEDED))
            retryableFailure = transient
            if (transient && (patients.any { it.has("operationId") } || config.has("operationId"))) {
                val work = androidx.work.OneTimeWorkRequestBuilder<CloudRetryWorker>()
                    .setConstraints(androidx.work.Constraints.Builder().setRequiredNetworkType(androidx.work.NetworkType.CONNECTED).build())
                    .setBackoffCriteria(androidx.work.BackoffPolicy.EXPONENTIAL, 30, java.util.concurrent.TimeUnit.SECONDS).build()
                androidx.work.WorkManager.getInstance(context).enqueueUniqueWork("pending-clinical-sync", androidx.work.ExistingWorkPolicy.KEEP, work)
            }
        } finally { busy = false } }
    }
}
