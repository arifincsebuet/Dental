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

    /** Staff email → invitation {role, permissions?} from the clinic's staff list. */
    fun staffInvites(config: JSONObject): Map<String, Map<String, Any>> {
        val invites = linkedMapOf<String, Map<String, Any>>()
        for (person in config.optJSONArray("staff").objectList()) {
            val email = person.optString("email").trim().lowercase()
            if (email.isEmpty() || !email.matches(Regex("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")) || !person.optBoolean("active", true)) continue
            val role = STAFF_ROLE[person.optString("role")] ?: "dentist"
            val invite = mutableMapOf<String, Any>("role" to role)
            person.optJSONObject("permissions")?.let { perms -> invite["permissions"] = Access.items.associate { it.key to perms.optBoolean(it.key, Access.roleDefault(role, it.key)) } }
            invites[email] = invite
        }
        return invites
    }

    /** Members who joined by email follow the staff list: removed → inactive; role and access changes apply. */
    private fun applyStaffInvites(config: JSONObject, clinicId: String, invites: Map<String, Map<String, Any>>) {
        val members = config.obj("members")
        for (uid in members.keys().asSequence().toList()) {
            val member = members.optJSONObject(uid) ?: continue
            if (uid == clinicId || member.optString("email").isBlank()) continue
            val invite = invites[member.optString("email")]
            member.put("active", invite != null)
            if (invite != null) {
                member.put("role", invite["role"])
                @Suppress("UNCHECKED_CAST")
                (invite["permissions"] as? Map<String, Boolean>)?.let { member.put("permissions", JSONObject(it)) } ?: member.remove("permissions")
            }
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
                    // Invitations are {role, permissions?} (older clinics: just the role name).
                    val invite = (snap.get("invites") as? Map<*, *>)?.get(email) ?: throw IllegalStateException("Your clinic invitation is no longer active")
                    val granted = (invite as? String) ?: ((invite as? Map<*, *>)?.get("role") as? String) ?: throw IllegalStateException("Your clinic invitation is invalid")
                    val entry = mutableMapOf<String, Any>("role" to granted, "active" to true, "email" to email)
                    ((invite as? Map<*, *>)?.get("permissions") as? Map<*, *>)?.let { entry["permissions"] = it }
                    val member = ((snap.get("config") as? Map<*, *>)?.get("members") as? Map<*, *>)?.get(user.uid) as? Map<*, *>
                    if (member != entry) tx.update(invited.reference, FieldPath.of("config", "members", user.uid), entry)
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
    suspend fun commitClinic(clinicId: String, uid: String, config: JSONObject, expectedRevision: Long, superAdminOwner: Boolean, slotsOnly: Boolean = false): ClinicState =
        db.runTransaction { tx ->
            val ref = clinicRef(clinicId)
            val snap = tx.get(ref)
            val serverRevision = snap.getLong("revision") ?: 0L
            if (slotsOnly) {
                // Staff with only "manage slots" access change availability on top of the cloud settings.
                val server = (fromValue(snap.get("config")) as? JSONObject) ?: error("The clinic settings are not set up yet. Ask your admin.")
                config.optJSONArray("availability")?.let { server.put("availability", it) }
                if (config.has("patientBooking")) server.put("patientBooking", config.optBoolean("patientBooking"))
                tx.update(ref, mapOf("config.availability" to toValue(server.optJSONArray("availability") ?: JSONArray()),
                    "config.patientBooking" to server.optBoolean("patientBooking", true), "revision" to serverRevision + 1,
                    "updatedAt" to System.currentTimeMillis(), "updatedBy" to uid))
                tx.set(publicRef(clinicId), publicInfo(server), SetOptions.merge())
                return@runTransaction ClinicState(server, serverRevision + 1, (fromValue(snap.get("subscription")) as? JSONObject) ?: JSONObject(), snap.getBoolean("complimentary") == true)
            }
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
    private const val PASSWORD_ALPHABET = "abcdefghjkmnpqrstuvwxyz23456789"
    private fun randomText(alphabet: String, length: Int) = buildString { repeat(length) { append(alphabet[random.nextInt(alphabet.length)]) } }
    /** Older versions created Firebase accounts for patients; those never act as staff. */
    fun isPatientAccount(user: FirebaseUser?) = user?.email?.lowercase()?.endsWith("@$PATIENT_EMAIL_DOMAIN") == true

    fun suggestLoginId() = randomText(LOGIN_ALPHABET, 6)
    fun suggestPassword() = randomText(PASSWORD_ALPHABET, 6)
    fun normalizeLoginId(value: String) = value.trim().uppercase()
    fun validLoginId(value: String) = value.trim().matches(Regex("[A-Za-z0-9]{4,12}"))
    fun passwordProblem(value: String): String? = when {
        value.length < 6 -> "Use at least 6 characters"
        value.any { it.isWhitespace() } -> "Spaces are not allowed"
        else -> null
    }

    // --------------------------------------------------------------------------------------
    // Patient app (no Firebase Authentication)
    //
    // A patient's login is a document portals/{key} with key = SHA-256 of the Patient ID and
    // password. Only someone who knows both can open it. It holds a read-only copy of the patient's
    // record (refreshed by staff on sync) and the patient's booking/cancel requests, which the next
    // staff sync applies to the real patient document.
    // --------------------------------------------------------------------------------------

    fun portalKey(loginId: String, password: String): String =
        java.security.MessageDigest.getInstance("SHA-256").digest("dental-care-portal:v1:${normalizeLoginId(loginId)}:$password".toByteArray())
            .joinToString("") { "%02x".format(it) }
    fun portalRef(key: String): DocumentReference = db.document("portals/$key")

    /** What the patient app shows: the patient's own care, without internal staff notes or audit. */
    fun patientView(patient: JSONObject): JSONObject {
        val keep = listOf("id", "name", "dob", "phone", "chart", "treatments", "performedProcedures", "appointments", "invoices", "updatedAt")
        val out = JSONObject(JSONObject(patient.toString()).let { copy -> JSONObject().also { o -> keep.forEach { k -> if (copy.has(k)) o.put(k, copy.get(k)) } } }.toString())
        out.optJSONArray("treatments").objectList().forEach { it.remove("events"); it.remove("amendments") }
        if (!out.has("appointments")) out.put("appointments", JSONArray())
        return out
    }

    private fun clinicView(config: JSONObject) = mapOf("name" to config.optString("name", "Dental Care"),
        "timeZone" to config.optString("timeZone", "Asia/Dhaka"), "currency" to config.optString("currency", "BDT"))

    data class NewLogin(val loginId: String, val password: String, val portal: JSONObject, val revision: Long)

    /**
     * Staff create (or replace) the patient's login with the Patient ID and password they chose.
     * Writes the portal document and the patient's `portal` field; a replaced login's document is
     * deleted, so the old Patient ID / password stop working at once.
     */
    suspend fun createPatientLogin(clinicId: String, staffUid: String, patient: JSONObject, config: JSONObject, loginId: String, password: String): NewLogin {
        val id = normalizeLoginId(loginId)
        require(validLoginId(id)) { "Patient ID: 4–12 letters or numbers" }
        passwordProblem(password)?.let { throw IllegalArgumentException("Password: $it") }
        val key = portalKey(id, password)
        val patientId = patient.getString("id")
        val ref = patientRef(clinicId, patientId)
        val now = System.currentTimeMillis()
        return db.runTransaction { tx ->
            val snap = tx.get(ref)
            check(snap.exists()) { "Sync this patient to the cloud first, then create the login" }
            val existing = tx.get(portalRef(key))
            if (existing.exists()) throw IllegalArgumentException("This Patient ID and password are already in use. Choose a different Patient ID.")
            val old = fromValue(snap.get("portal")) as? JSONObject
            val record = patientView(fromDoc(snap))
            tx.set(portalRef(key), mapOf("clinicId" to clinicId, "patientId" to patientId, "loginId" to id, "record" to toMap(record),
                "clinic" to clinicView(config), "requests" to emptyList<Any>(), "pending" to false, "createdAt" to now, "createdBy" to staffUid, "updatedAt" to now))
            old?.optString("key")?.takeIf { it.isNotBlank() && it != key }?.let { tx.delete(portalRef(it)) }
            val portal = JSONObject().put("loginId", id).put("key", key).put("createdAt", old?.optLong("createdAt")?.takeIf { it > 0 } ?: now)
                .put("createdBy", old?.optString("createdBy")?.ifBlank { null } ?: staffUid).put("updatedAt", now).put("updatedBy", staffUid)
                .put("handled", old?.optJSONArray("handled") ?: JSONArray())
            val revision = (snap.getLong("revision") ?: 0L) + 1
            tx.update(ref, mapOf("portal" to toMap(portal), "revision" to revision, "updatedBy" to staffUid, "syncedAt" to FieldValue.serverTimestamp()))
            NewLogin(id, password, portal, revision)
        }.await()
    }

    /** Turns off the patient's app access: deletes the portal document and the record's login. */
    suspend fun removePatientLogin(clinicId: String, staffUid: String, patientId: String): Long {
        val ref = patientRef(clinicId, patientId)
        return db.runTransaction { tx ->
            val snap = tx.get(ref)
            val old = fromValue(snap.get("portal")) as? JSONObject ?: error("This patient has no app login")
            old.optString("key").takeIf { it.isNotBlank() }?.let { tx.delete(portalRef(it)) }
            val revision = (snap.getLong("revision") ?: 0L) + 1
            tx.update(ref, mapOf("portal" to FieldValue.delete(), "revision" to revision, "updatedBy" to staffUid, "syncedAt" to FieldValue.serverTimestamp()))
            revision
        }.await()
    }

    /** Portal documents of this clinic with patient requests waiting for staff. */
    suspend fun pendingPortals(clinicId: String): List<Pair<String, JSONObject>> =
        db.collection("portals").whereEqualTo("clinicId", clinicId).whereEqualTo("pending", true).get().await()
            .documents.map { it.id to fromDoc(it) }

    /** Rewrites the patient app copy and drops requests that were applied ([handled]). */
    suspend fun refreshPortal(key: String, patient: JSONObject, config: JSONObject, handled: Set<String>) {
        db.runTransaction { tx ->
            val snap = tx.get(portalRef(key))
            if (!snap.exists()) return@runTransaction
            val remaining = (snap.get("requests") as? List<*>).orEmpty().filter { (it as? Map<*, *>)?.get("id") !in handled }
            tx.update(portalRef(key), mapOf("record" to toMap(patientView(patient)), "clinic" to clinicView(config), "requests" to remaining,
                "pending" to remaining.isNotEmpty(), "loginId" to (patient.optJSONObject("portal")?.optString("loginId") ?: snap.getString("loginId")),
                "updatedAt" to System.currentTimeMillis()))
        }.await()
    }

    data class MyRecord(val loginId: String, val patient: JSONObject, val clinic: JSONObject, val pending: Int)

    /** Patient sign-in and refresh: the portal document plus the clinic's public info (2 reads). */
    suspend fun loadPortal(key: String): MyRecord? {
        val snap = portalRef(key).get().await()
        if (!snap.exists()) return null
        val doc = fromDoc(snap)
        val clinicId = doc.getString("clinicId")
        val pub = publicRef(clinicId).get().await()
        val clinic = (if (pub.exists()) fromDoc(pub) else JSONObject())
        doc.optJSONObject("clinic")?.let { c -> c.keys().forEach { k -> if (!clinic.has(k)) clinic.put(k, c.get(k)) } }
        return MyRecord(doc.optString("loginId"), doc.optJSONObject("record") ?: JSONObject(), clinic.put("clinicId", clinicId), doc.optJSONArray("requests")?.length() ?: 0)
    }

    /** Patient books a free slot: one transaction on the portal document and public/info. */
    suspend fun bookSlot(key: String, startAt: Long, doctor: String, reason: String) {
        db.runTransaction { tx ->
            val portalSnap = tx.get(portalRef(key))
            check(portalSnap.exists()) { "Your login has changed. Sign in again." }
            val portal = fromDoc(portalSnap)
            val clinicId = portal.getString("clinicId")
            val pub = tx.get(publicRef(clinicId))
            check(pub.exists()) { "The clinic has not opened online booking yet" }
            val clinic = fromDoc(pub)
            check(clinic.optBoolean("patientBooking", true)) { "Online booking is turned off for this clinic" }
            val now = System.currentTimeMillis()
            val date = Instant.ofEpochMilli(startAt).atZone(ClinicalRules.zone(clinic)).toLocalDate()
            val slot = SlotCalendar(clinic, emptyList()).slots(date, now).firstOrNull { it.startAt == startAt && it.doctor.equals(doctor.trim(), true) }
                ?: error("That slot is not offered")
            check(slot.available) { "That slot was just taken. Please choose another time." }
            val record = portal.optJSONObject("record") ?: JSONObject()
            val appointments = record.optJSONArray("appointments") ?: JSONArray()
            val upcoming = appointments.objectList().count { it.optString("status") in listOf("Scheduled", "Confirmed", "Requested") && it.optLong("startAt") > now }
            check(upcoming < 3) { "You already have 3 upcoming appointments. Contact the clinic to book more." }
            val appointment = JSONObject().put("id", "apt-" + java.util.UUID.randomUUID().toString().replace("-", "").take(20)).put("date", "${slot.date} ${slot.time}")
                .put("startAt", slot.startAt).put("durationMinutes", slot.durationMinutes).put("doctor", slot.doctor).put("room", slot.room)
                .put("reason", reason.trim().ifBlank { "Check-up" }.take(500)).put("status", "Scheduled").put("bookedBy", "patient").put("bookedAt", now).put("slot", true)
            appointments.put(appointment)
            val requests = (portalSnap.get("requests") as? List<*>).orEmpty() + mapOf("id" to "req-${java.util.UUID.randomUUID()}", "type" to "book", "at" to now, "appointment" to toMap(appointment))
            tx.update(portalRef(key), mapOf("requests" to requests, "record.appointments" to toValue(appointments), "pending" to true, "updatedAt" to now))
            tx.update(publicRef(clinicId), mapOf("busy" to FieldValue.arrayUnion(mapOf("s" to slot.startAt, "e" to slot.startAt + slot.durationMinutes * 60_000L, "d" to slot.doctor.lowercase())), "lastPortal" to key))
        }.await()
    }

    /** Patient cancels an upcoming appointment (at least 2 hours ahead). */
    suspend fun cancelMyAppointment(key: String, appointmentId: String) {
        db.runTransaction { tx ->
            val portalSnap = tx.get(portalRef(key))
            check(portalSnap.exists()) { "Your login has changed. Sign in again." }
            val portal = fromDoc(portalSnap)
            val clinicId = portal.getString("clinicId")
            val pub = tx.get(publicRef(clinicId))
            val now = System.currentTimeMillis()
            val appointments = portal.optJSONObject("record")?.optJSONArray("appointments") ?: JSONArray()
            val appointment = appointments.objectList().firstOrNull { it.optString("id") == appointmentId && it.optString("status") in listOf("Scheduled", "Confirmed", "Requested") }
                ?: error("Appointment not found")
            check(appointment.optLong("startAt") - now >= 2 * 3_600_000L) { "Appointments can be cancelled in the app up to 2 hours before. Please call the clinic." }
            appointment.put("status", "Cancelled").put("cancelledBy", "patient").put("cancelledAt", now)
            val requests = (portalSnap.get("requests") as? List<*>).orEmpty() + mapOf("id" to "req-${java.util.UUID.randomUUID()}", "type" to "cancel", "at" to now, "appointmentId" to appointmentId)
            tx.update(portalRef(key), mapOf("requests" to requests, "record.appointments" to toValue(appointments), "pending" to true, "updatedAt" to now))
            val entry = mapOf("s" to appointment.optLong("startAt"), "e" to appointment.optLong("startAt") + appointment.optInt("durationMinutes", 30) * 60_000L,
                "d" to appointment.optString("doctor").trim().lowercase())
            val busy = (pub.get("busy") as? List<*>).orEmpty()
            if (busy.any { (it as? Map<*, *>)?.let { m -> (m["s"] as? Number)?.toLong() == entry["s"] && (m["e"] as? Number)?.toLong() == entry["e"] && m["d"] == entry["d"] } == true })
                tx.update(publicRef(clinicId), mapOf("busy" to FieldValue.arrayRemove(entry), "lastPortal" to key))
        }.await()
    }

    /**
     * Applies a portal's requests to the staff copy of the patient (marks it dirty). Returns the
     * request ids that were applied. Booking requests become appointments; if the time is no longer
     * free they are kept as "Requested" for the front desk to confirm.
     */
    fun applyPortalRequests(patient: JSONObject, requests: List<JSONObject>, config: JSONObject, patients: List<JSONObject>): Set<String> {
        val portal = patient.obj("portal")
        val done = portal.optJSONArray("handled").let { a -> (0 until (a?.length() ?: 0)).map { a!!.optString(it) }.toMutableSet() }
        val applied = mutableSetOf<String>()
        val appointments = patient.array("appointments")
        val now = System.currentTimeMillis()
        for (request in requests) {
            val id = request.optString("id")
            if (id.isBlank()) continue
            if (id in done) { applied += id; continue }
            when (request.optString("type")) {
                "book" -> request.optJSONObject("appointment")?.let { a ->
                    if (appointments.objectList().none { it.optString("id") == a.optString("id") }) {
                        val others = patients.filter { it.optString("id") != patient.optString("id") }
                        val start = a.optLong("startAt"); val end = start + a.optInt("durationMinutes", 30) * 60_000L
                        val clash = others.any { p -> p.optJSONArray("appointments").objectList().any { o ->
                            o.optString("status") in setOf("Scheduled", "Confirmed", "Checked in", "In progress") && o.optString("doctor").equals(a.optString("doctor"), true) &&
                                (runCatching { ClinicalRules.appointmentMillis(o, config) }.getOrNull() ?: -1L).let { s -> s < end && start < s + o.optInt("durationMinutes", 30) * 60_000L }
                        } }
                        val consent = patient.optBoolean("communicationConsent")
                        appointments.put(JSONObject(a.toString()).put("status", if (clash) "Requested" else "Scheduled").put("consent", consent)
                            .put("reminderEnabled", consent && config.optBoolean("remindersEnabled")))
                        patient.array("history").put(JSONObject().put("id", uid()).put("at", now)
                            .put("text", "Patient app: booked ${a.optString("date")} with ${a.optString("doctor")}" + if (clash) " (time no longer free; please confirm)" else ""))
                    }
                }
                "cancel" -> appointments.objectList().firstOrNull { it.optString("id") == request.optString("appointmentId") }?.let { a ->
                    if (a.optString("status") in setOf("Scheduled", "Confirmed", "Requested")) {
                        a.put("status", "Cancelled").put("cancelledBy", "patient").put("cancelledAt", request.optLong("at", now))
                        patient.array("history").put(JSONObject().put("id", uid()).put("at", now).put("text", "Patient app: cancelled ${a.optString("date")}"))
                    }
                }
            }
            done += id; applied += id
        }
        portal.put("handled", JSONArray(done.toList().takeLast(100)))
        return applied
    }
}
