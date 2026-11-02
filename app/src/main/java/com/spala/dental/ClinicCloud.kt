package com.spala.dental

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldPath
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.tasks.await
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import java.time.Instant

/*
 * Direct Firestore access from the device (no Cloud Functions). Security lives in
 * firebase/firestore.rules. Documents per clinic, kept to the minimum:
 *
 *   workspaces/{clinicId}                 clinic: config (staff, members, services, availability…),
 *                                         revision, invites, subscription — every clinic-wide list
 *   workspaces/{clinicId}/public/info     what patients may see: name, time zone, currency, slot
 *                                         availability and busy times (no patient names)
 *   workspaces/{clinicId}/patients/{id}   one per patient: profile, chart, treatments, appointments,
 *                                         invoices, history, image references, portal login
 *
 * A patient needs a document of their own because Firestore rules secure whole documents: a
 * patient allowed to read a shared list document would see every other patient in it.
 */
object ClinicCloud {
    const val MAX_BYTES = 750_000
    const val PATIENT_EMAIL_DOMAIN = "patient.dentalcare.local"
    private val LOCAL_FIELDS = listOf("dirty", "operationId", "remoteVersion", "cloudOwner", "retainedAt", "syncedAt", "patientDevices")
    private val STAFF_ROLE = mapOf("owner" to "admin", "admin" to "admin", "administrator" to "admin", "dentist" to "dentist",
        "assistant" to "assistant", "receptionist" to "receptionist", "cashier" to "cashier", "auditor" to "auditor")
    private val random = SecureRandom()

    /** Optimistic-concurrency failure: the document changed on another device. */
    class Conflict(message: String) : Exception(message)

    private val db: FirebaseFirestore get() = FirebaseFirestore.getInstance()
    fun clinicRef(clinicId: String): DocumentReference = db.document("workspaces/$clinicId")
    fun publicRef(clinicId: String): DocumentReference = db.document("workspaces/$clinicId/public/info")
    fun patientRef(clinicId: String, patientId: String): DocumentReference = db.document("workspaces/$clinicId/patients/$patientId")

    // --------------------------------------------------------------------------------------
    // JSON <-> Firestore
    // --------------------------------------------------------------------------------------

    fun toMap(json: JSONObject): Map<String, Any?> = json.keys().asSequence().associateWith { key -> toValue(json.get(key)) }
    private fun toValue(value: Any?): Any? = when (value) {
        JSONObject.NULL, null -> null
        is JSONObject -> toMap(value)
        is JSONArray -> (0 until value.length()).map { toValue(value.get(it)) }
        else -> value
    }
    fun fromValue(value: Any?): Any? = when (value) {
        null -> JSONObject.NULL
        is Timestamp -> value.toDate().time
        is Map<*, *> -> JSONObject().also { out -> value.forEach { (k, v) -> out.put(k.toString(), fromValue(v)) } }
        is List<*> -> JSONArray().also { out -> value.forEach { out.put(fromValue(it)) } }
        else -> value
    }
    fun fromDoc(snapshot: DocumentSnapshot): JSONObject = (fromValue(snapshot.data ?: emptyMap<String, Any>()) as JSONObject)

    // --------------------------------------------------------------------------------------
    // Clinic
    // --------------------------------------------------------------------------------------

    /** Staff email → role from the clinic's staff list (the invitation list). */
    fun staffInvites(config: JSONObject): Map<String, String> {
        val invites = linkedMapOf<String, String>()
        for (person in config.optJSONArray("staff").objectList()) {
            val email = person.optString("email").trim().lowercase()
            if (email.isEmpty() || !email.matches(Regex("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")) || !person.optBoolean("active", true)) continue
            invites[email] = STAFF_ROLE[person.optString("role")] ?: "dentist"
        }
        return invites
    }

    /** Members who joined by email follow the staff list: removed from it → inactive; role changes apply. */
    private fun applyStaffInvites(config: JSONObject, clinicId: String, invites: Map<String, String>) {
        val members = config.obj("members")
        for (uid in members.keys().asSequence().toList()) {
            val member = members.optJSONObject(uid) ?: continue
            if (uid == clinicId || member.optString("email").isBlank()) continue
            val role = invites[member.optString("email")]
            member.put("active", role != null)
            if (role != null) member.put("role", role)
        }
    }

    /** Fields of the clinic that patients may read (public/info). */
    private fun publicInfo(config: JSONObject): Map<String, Any?> = mapOf(
        "name" to config.optString("name", "Dental Care"),
        "timeZone" to config.optString("timeZone", "Asia/Dhaka"),
        "currency" to config.optString("currency", "BDT"),
        "availability" to toValue(config.optJSONArray("availability") ?: JSONArray()),
        "patientBooking" to config.optBoolean("patientBooking", true),
        "remindersEnabled" to config.optBoolean("remindersEnabled"),
        "reminderHours" to config.optInt("reminderHours", 24),
    )

    data class Workspace(val clinicId: String, val role: String)

    /**
     * After Google sign-in: own clinic → owner. Invited by email → joins that clinic (adds itself to
     * config.members once; rules only allow the role the staff list grants). Otherwise a new clinic
     * with this account as owner (created at the first sync).
     */
    suspend fun resolveWorkspace(user: FirebaseUser): Workspace {
        val own = clinicRef(user.uid).get().await()
        if (own.exists()) return Workspace(user.uid, "owner")
        val email = user.email?.trim()?.lowercase()
        if (!email.isNullOrBlank()) {
            val invited = db.collection("workspaces").whereArrayContains("inviteEmails", email).limit(1).get().await().documents.firstOrNull()
            if (invited != null) {
                val role = db.runTransaction { tx ->
                    val snap = tx.get(invited.reference)
                    val granted = (snap.get("invites") as? Map<*, *>)?.get(email) as? String
                        ?: throw IllegalStateException("Your clinic invitation is no longer active")
                    val member = ((snap.get("config") as? Map<*, *>)?.get("members") as? Map<*, *>)?.get(user.uid) as? Map<*, *>
                    if (member == null || member["role"] != granted || member["active"] == false || member["email"] != email)
                        tx.update(invited.reference, FieldPath.of("config", "members", user.uid), mapOf("role" to granted, "active" to true, "email" to email))
                    granted
                }.await()
                return Workspace(invited.id, role)
            }
        }
        return Workspace(user.uid, "owner")
    }

    data class ClinicState(val config: JSONObject, val revision: Long, val subscription: JSONObject, val complimentary: Boolean)

    suspend fun getClinic(clinicId: String): ClinicState? {
        val snap = clinicRef(clinicId).get().await()
        if (!snap.exists()) return null
        val doc = fromDoc(snap)
        return ClinicState(doc.optJSONObject("config") ?: ClinicalRules.defaults(), doc.optLong("revision"),
            doc.optJSONObject("subscription") ?: JSONObject(), doc.optBoolean("complimentary"))
    }

    /** Saves clinic settings with a revision check and mirrors the patient-visible part to public/info. */
    suspend fun commitClinic(clinicId: String, uid: String, config: JSONObject, expectedRevision: Long, superAdminOwner: Boolean): ClinicState =
        db.runTransaction { tx ->
            val ref = clinicRef(clinicId)
            val snap = tx.get(ref)
            val serverRevision = snap.getLong("revision") ?: 0L
            if (serverRevision != expectedRevision) throw Conflict("Clinic settings changed on another device. Load cloud clinic settings, review, then save again.")
            val next = JSONObject(config.toString())
            listOf("dirty", "revision", "operationId").forEach { next.remove(it) }
            // Staff who joined by email after this device loaded settings are kept, not dropped.
            val serverMembers = fromValue((snap.get("config") as? Map<*, *>)?.get("members")) as? JSONObject
            val members = next.obj("members")
            serverMembers?.keys()?.forEach { id -> if (!members.has(id) && serverMembers.getJSONObject(id).has("email")) members.put(id, serverMembers.getJSONObject(id)) }
            if (clinicId == uid && !members.has(uid)) members.put(uid, JSONObject().put("role", "owner").put("active", true))
            val invites = staffInvites(next)
            applyStaffInvites(next, clinicId, invites)
            val revision = serverRevision + 1
            val data = mutableMapOf<String, Any?>("config" to toMap(next), "revision" to revision, "updatedAt" to System.currentTimeMillis(),
                "updatedBy" to uid, "invites" to invites, "inviteEmails" to invites.keys.take(200).toList())
            val complimentary = snap.getBoolean("complimentary") == true || superAdminOwner
            if (superAdminOwner && snap.getBoolean("complimentary") != true) data["complimentary"] = true
            if (snap.exists()) tx.update(ref, data) else tx.set(ref, data)
            tx.set(publicRef(clinicId), publicInfo(next), SetOptions.merge())
            ClinicState(next, revision, (fromValue(snap.get("subscription")) as? JSONObject) ?: JSONObject(), complimentary)
        }.await()

    /** Clinic-wide subscription written by the admin device that holds the Google Play purchase. */
    suspend fun saveSubscription(clinicId: String, subscription: JSONObject) {
        clinicRef(clinicId).update("subscription", toMap(subscription)).await()
    }

    // --------------------------------------------------------------------------------------
    // Patients (staff)
    // --------------------------------------------------------------------------------------

    /** Writes one patient document if nobody else changed it since [patient]'s remoteVersion. */
    suspend fun commitPatient(clinicId: String, uid: String, patient: JSONObject): JSONObject =
        db.runTransaction { tx ->
            val ref = patientRef(clinicId, patient.getString("id"))
            val snap = tx.get(ref)
            val serverRevision = snap.getLong("revision") ?: 0L
            if (serverRevision != patient.optLong("remoteVersion"))
                throw Conflict("${patient.optString("name")} was changed on another device or by the patient. Use Review conflict to keep your draft and load the cloud version.")
            val doc = JSONObject(patient.toString())
            LOCAL_FIELDS.forEach { doc.remove(it) }
            // The patient login is managed separately; always keep the cloud's copy.
            doc.remove("portal")
            (fromValue(snap.get("portal")) as? JSONObject)?.let { doc.put("portal", it) }
            doc.put("revision", serverRevision + 1).put("clinicId", clinicId).put("updatedBy", uid)
            check(doc.toString().toByteArray().size <= MAX_BYTES) { "Patient document is full. Export and archive older history first." }
            tx.set(ref, toMap(doc) + ("syncedAt" to FieldValue.serverTimestamp()))
            doc
        }.await()

    /** All patients, or only those changed after [since] (server time, ms). Returns records and the newest change time. */
    suspend fun listPatients(clinicId: String, since: Long?): Pair<List<JSONObject>, Long> {
        var query: com.google.firebase.firestore.Query = db.collection("workspaces/$clinicId/patients")
        if (since != null) query = query.whereGreaterThan("syncedAt", Timestamp(java.util.Date(since)))
        val docs = query.get().await().documents
        var newest = 0L
        val records = docs.map { snap ->
            (snap.getTimestamp("syncedAt")?.toDate()?.time ?: 0L).let { if (it > newest) newest = it }
            fromDoc(snap).also { doc -> doc.remove("syncedAt"); doc.remove("patientDevices") }
        }
        return records to newest
    }

    suspend fun getPatient(clinicId: String, patientId: String): JSONObject? =
        patientRef(clinicId, patientId).get().await().takeIf { it.exists() }?.let { fromDoc(it).also { doc -> doc.remove("syncedAt"); doc.remove("patientDevices") } }

    /**
     * Busy times for patient booking (no names). Rebuilt from the staff device's patients after a
     * sync and written only when it changed.
     */
    fun busyTimes(patients: List<JSONObject>, config: JSONObject, now: Long = System.currentTimeMillis()): List<Map<String, Any>> {
        val active = setOf("Requested", "Scheduled", "Confirmed", "Checked in", "In progress")
        return patients.filterNot { it.optBoolean("archived") }.flatMap { p ->
            p.optJSONArray("appointments").objectList().filter { it.optString("status") in active }.mapNotNull { a ->
                val start = runCatching { ClinicalRules.appointmentMillis(a, config) }.getOrNull() ?: return@mapNotNull null
                if (start < now - 86_400_000L) return@mapNotNull null
                mapOf("s" to start, "e" to start + a.optInt("durationMinutes", 30) * 60_000L, "d" to a.optString("doctor").trim().lowercase())
            }
        }.sortedWith(compareBy({ it["s"] as Long }, { it["d"] as String })).take(2000)
    }

    suspend fun publishBusy(clinicId: String, busy: List<Map<String, Any>>) {
        publicRef(clinicId).set(mapOf("busy" to busy), SetOptions.merge()).await()
    }

    // --------------------------------------------------------------------------------------
    // Patient logins (created on the admin's device)
    // --------------------------------------------------------------------------------------

    private const val LOGIN_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
    private const val PASSWORD_ALPHABET = "abcdefghjkmnpqrstuvwxyzABCDEFGHJKMNPQRSTUVWXYZ23456789"
    private fun randomText(alphabet: String, length: Int) = buildString { repeat(length) { append(alphabet[random.nextInt(alphabet.length)]) } }
    fun patientEmail(loginId: String) = "${loginId.trim().lowercase()}@$PATIENT_EMAIL_DOMAIN"
    fun isPatientAccount(user: FirebaseUser?) = user?.email?.lowercase()?.endsWith("@$PATIENT_EMAIL_DOMAIN") == true

    fun suggestLoginId() = randomText(LOGIN_ALPHABET, 8)
    fun suggestPassword() = randomText(PASSWORD_ALPHABET, 10)
    fun validLoginId(value: String) = value.trim().matches(Regex("[A-Za-z0-9]{6,12}"))
    fun passwordProblem(value: String): String? = when {
        value.length < 8 -> "Use at least 8 characters"
        !(value.any { it.isLetter() } && value.any { it.isDigit() }) -> "Use letters and numbers"
        value.any { it.isWhitespace() } -> "Spaces are not allowed"
        else -> null
    }

    /** The chosen Patient ID already belongs to another login. */
    class LoginTaken : Exception("This Patient ID is already in use. Choose another one.")

    /**
     * Creates the Email/Password account through Firebase Auth's sign-up endpoint. This does not
     * sign anyone in on this phone, so the admin stays signed in, and it carries this app's App Check
     * token and Android identity (needed when the API key is restricted to the app).
     */
    private suspend fun createAuthAccount(context: Context, email: String, password: String): String {
        val appCheck = runCatching { com.google.firebase.appcheck.FirebaseAppCheck.getInstance().getAppCheckToken(false).await().token }.getOrNull()
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val key = FirebaseApp.getInstance().options.apiKey
            val connection = java.net.URL("https://identitytoolkit.googleapis.com/v1/accounts:signUp?key=$key").openConnection() as java.net.HttpURLConnection
            try {
                connection.requestMethod = "POST"; connection.doOutput = true
                connection.connectTimeout = 20_000; connection.readTimeout = 20_000
                connection.setRequestProperty("Content-Type", "application/json")
                connection.setRequestProperty("X-Android-Package", context.packageName)
                signingSha1(context)?.let { connection.setRequestProperty("X-Android-Cert", it) }
                appCheck?.let { connection.setRequestProperty("X-Firebase-AppCheck", it) }
                connection.outputStream.use { it.write(JSONObject().put("email", email).put("password", password).put("returnSecureToken", true).toString().toByteArray()) }
                val ok = connection.responseCode in 200..299
                val body = (if (ok) connection.inputStream else connection.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
                if (ok) return@withContext JSONObject(body).getString("localId")
                val reason = runCatching { JSONObject(body).getJSONObject("error").getString("message") }.getOrDefault("HTTP ${connection.responseCode}")
                throw when {
                    reason.startsWith("EMAIL_EXISTS") -> LoginTaken()
                    reason.startsWith("OPERATION_NOT_ALLOWED") || reason.startsWith("ADMIN_ONLY_OPERATION") ->
                        IllegalStateException("Patient logins are turned off. In the Firebase console open Authentication → Sign-in method and enable Email/Password (and allow user sign-up).")
                    reason.startsWith("CONFIGURATION_NOT_FOUND") ->
                        IllegalStateException("Firebase Authentication is not set up for this project. Open Authentication in the Firebase console, press Get started and enable Email/Password.")
                    reason.startsWith("WEAK_PASSWORD") -> IllegalArgumentException("The password is too weak. Use at least 8 letters and numbers.")
                    reason.startsWith("TOO_MANY_ATTEMPTS") -> IllegalStateException("Too many attempts. Wait a few minutes and try again.")
                    reason.contains("App Check", true) || reason.contains("APP_CHECK", true) ->
                        IllegalStateException("App Check rejected this request. Add this device's debug token in the Firebase console (App Check → Manage debug tokens), or install the app from Google Play.")
                    reason.contains("blocked", true) || reason.contains("API_KEY", true) || reason.contains("PERMISSION_DENIED", true) ->
                        IllegalStateException("The Firebase API key refused this request ($reason). Check the key's Android app restriction (package com.spala.dental and this build's SHA-1) and that the Identity Toolkit API is allowed.")
                    else -> IllegalStateException("Could not create the patient login: $reason")
                }
            } finally { connection.disconnect() }
        }
    }

    /** SHA-1 of this build's signing certificate, as Google API key restrictions expect it. */
    private fun signingSha1(context: Context): String? = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES)
        val signer = info.signingInfo?.apkContentsSigners?.firstOrNull() ?: return null
        java.security.MessageDigest.getInstance("SHA-1").digest(signer.toByteArray()).joinToString("") { "%02X".format(it) }
    }.getOrNull()

    data class NewLogin(val loginId: String, val password: String, val portal: JSONObject, val revision: Long)

    /**
     * Creates the Patient ID and password the admin chose and links them to the patient's record.
     * "Reset" works the same way with a new Patient ID: the previous Patient ID loses access because
     * the record no longer names its account (changing another user's password needs a server).
     */
    suspend fun createPatientLogin(context: Context, clinicId: String, staffUid: String, patientId: String, loginId: String, password: String, reset: Boolean): NewLogin {
        val id = loginId.trim().uppercase()
        require(validLoginId(id)) { "Patient ID: 6–12 letters or numbers" }
        passwordProblem(password)?.let { throw IllegalArgumentException("Password: $it") }
        val ref = patientRef(clinicId, patientId)
        // Check the record first so a refused write doesn't leave an unlinked account behind.
        val before = ref.get().await()
        check(before.exists()) { "Sync this patient to the cloud first, then create the login" }
        if (!reset) check(before.get("portal") == null) { "This patient already has a login. Use Reset password." }
        val newUid = createAuthAccount(context, patientEmail(id), password)
        val now = System.currentTimeMillis()
        return db.runTransaction { tx ->
            val snap = tx.get(ref)
            val old = fromValue(snap.get("portal")) as? JSONObject
            val portal = JSONObject().put("loginId", id).put("uid", newUid).put("mustChange", true)
                .put("createdAt", old?.optLong("createdAt")?.takeIf { it > 0 } ?: now).put("createdBy", old?.optString("createdBy")?.ifBlank { null } ?: staffUid)
                .put("passwordResetAt", now).put("resetBy", staffUid)
            val revision = (snap.getLong("revision") ?: 0L) + 1
            tx.update(ref, mapOf("portal" to toMap(portal), "revision" to revision, "updatedBy" to staffUid, "syncedAt" to FieldValue.serverTimestamp()))
            NewLogin(id, password, portal, revision)
        }.await()
    }

    // --------------------------------------------------------------------------------------
    // Patient app
    // --------------------------------------------------------------------------------------

    /** Finds the record whose portal login is this account (one indexed query at sign-in). */
    suspend fun findMyRecord(uid: String): DocumentReference? =
        db.collectionGroup("patients").whereEqualTo("portal.uid", uid).limit(1).get().await().documents.firstOrNull()?.reference

    data class MyRecord(val patient: JSONObject, val clinic: JSONObject)

    suspend fun loadMyRecord(path: String): MyRecord {
        val ref = db.document(path)
        val clinicId = ref.parent.parent?.id ?: error("Invalid record path")
        val snap = ref.get().await()
        check(snap.exists()) { "Your record is not available. Contact the clinic." }
        val pub = publicRef(clinicId).get().await()
        return MyRecord(patientView(fromDoc(snap)), if (pub.exists()) fromDoc(pub) else JSONObject().put("name", "Dental Care"))
    }

    /** What the patient app shows: the patient's own care, without internal staff notes or audit. */
    private fun patientView(patient: JSONObject): JSONObject {
        val keep = listOf("id", "name", "dob", "phone", "chart", "treatments", "performedProcedures", "appointments", "invoices", "updatedAt", "portal", "revision")
        val out = JSONObject()
        for (key in keep) if (patient.has(key)) out.put(key, patient.get(key))
        out.optJSONArray("treatments").objectList().forEach { it.remove("events"); it.remove("amendments") }
        return out
    }

    suspend fun completePasswordChange(path: String) {
        db.document(path).update(FieldPath.of("portal", "mustChange"), false).await()
    }

    /** Patient books a free slot: one transaction on the patient's document and public/info. */
    suspend fun bookSlot(path: String, uid: String, startAt: Long, doctor: String, reason: String) {
        val patientDoc = db.document(path)
        val clinicId = patientDoc.parent.parent!!.id
        db.runTransaction { tx ->
            val pub = tx.get(publicRef(clinicId)); val snap = tx.get(patientDoc)
            check(pub.exists() && snap.exists()) { "Your record is not available" }
            val clinic = fromDoc(pub); val patient = fromDoc(snap)
            check(clinic.optBoolean("patientBooking", true)) { "Online booking is turned off for this clinic" }
            val now = System.currentTimeMillis()
            val zone = ClinicalRules.zone(clinic)
            val date = Instant.ofEpochMilli(startAt).atZone(zone).toLocalDate()
            val slot = SlotCalendar(clinic, emptyList()).slots(date, now).firstOrNull { it.startAt == startAt && it.doctor.equals(doctor.trim(), true) }
                ?: error("That slot is not offered")
            check(slot.available) { "That slot was just taken. Please choose another time." }
            val upcoming = patient.optJSONArray("appointments").objectList().count { it.optString("status") in listOf("Scheduled", "Confirmed") && it.optLong("startAt") > now }
            check(upcoming < 3) { "You already have 3 upcoming appointments. Contact the clinic to book more." }
            val appointment = JSONObject().put("id", "apt-" + java.util.UUID.randomUUID().toString().replace("-", "").take(20)).put("date", "${slot.date} ${slot.time}")
                .put("startAt", slot.startAt).put("durationMinutes", slot.durationMinutes).put("doctor", slot.doctor).put("room", slot.room)
                .put("reason", reason.trim().ifBlank { "Check-up" }.take(500)).put("status", "Scheduled").put("bookedBy", "patient").put("bookedAt", now)
                .put("slot", true).put("reminderEnabled", clinic.optBoolean("remindersEnabled")).put("consent", patient.optBoolean("communicationConsent"))
            selfService(tx, patientDoc, patient, uid, now, "Appointment booked by patient: ${slot.date} ${slot.time} with ${slot.doctor}") { it.put(appointment) }
            tx.update(publicRef(clinicId), "busy", FieldValue.arrayUnion(mapOf("s" to slot.startAt, "e" to slot.startAt + slot.durationMinutes * 60_000L, "d" to slot.doctor.lowercase())))
        }.await()
    }

    /** Patient cancels an upcoming appointment (at least 2 hours ahead). */
    suspend fun cancelMyAppointment(path: String, uid: String, appointmentId: String) {
        val patientDoc = db.document(path)
        val clinicId = patientDoc.parent.parent!!.id
        db.runTransaction { tx ->
            val pub = tx.get(publicRef(clinicId)); val snap = tx.get(patientDoc)
            val patient = fromDoc(snap); val now = System.currentTimeMillis()
            val appointment = patient.optJSONArray("appointments").objectList().firstOrNull { it.optString("id") == appointmentId && it.optString("status") in listOf("Scheduled", "Confirmed") }
                ?: error("Appointment not found")
            check(appointment.optLong("startAt") - now >= 2 * 3_600_000L) { "Appointments can be cancelled in the app up to 2 hours before. Please call the clinic." }
            val entry = mapOf("s" to appointment.optLong("startAt"), "e" to appointment.optLong("startAt") + appointment.optInt("durationMinutes", 30) * 60_000L,
                "d" to appointment.optString("doctor").trim().lowercase())
            selfService(tx, patientDoc, patient, uid, now, "Appointment cancelled by patient: ${appointment.optString("date")}") { list ->
                for (i in 0 until list.length()) { val a = list.getJSONObject(i); if (a.optString("id") == appointmentId) a.put("status", "Cancelled").put("cancelledBy", "patient").put("cancelledAt", now) }
            }
            val busy = (pub.get("busy") as? List<*>).orEmpty()
            if (busy.any { (it as? Map<*, *>)?.let { m -> (m["s"] as? Number)?.toLong() == entry["s"] && (m["e"] as? Number)?.toLong() == entry["e"] && m["d"] == entry["d"] } == true })
                tx.update(publicRef(clinicId), "busy", FieldValue.arrayRemove(entry))
        }.await()
    }

    /** The only patient-side write besides the password flag: appointments + one history line, revision +1. */
    private fun selfService(tx: com.google.firebase.firestore.Transaction, ref: DocumentReference, patient: JSONObject, uid: String, now: Long, action: String, change: (JSONArray) -> Unit) {
        val appointments = patient.optJSONArray("appointments") ?: JSONArray()
        change(appointments)
        val revision = patient.optLong("revision") + 1
        val history = patient.optJSONArray("history") ?: JSONArray()
        history.put(JSONObject().put("id", "self-$revision-$now").put("at", now).put("text", action).put("actor", uid).put("source", "patient-app"))
        tx.update(ref, mapOf("appointments" to toValue(appointments), "history" to toValue(history), "revision" to revision,
            "updatedAt" to now, "updatedBy" to uid, "syncedAt" to FieldValue.serverTimestamp()))
    }
}
