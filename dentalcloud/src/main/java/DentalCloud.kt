package com.spala.dental.cloud

import android.content.Context
import android.net.Uri
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageException
import com.google.firebase.storage.StorageMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/*
 * Dental Cloud SDK (Android) v1.0
 *
 * Reusable Firebase data layer for clinic software, shared with the web SDK (Web/sdk/dental-cloud.js):
 *   - ONE Firestore document per patient: profile, chart, treatments, appointments, invoices,
 *     history and image links are lists/JSON inside that document (no sub-documents).
 *   - Revision-checked writes (1 read + 1 write per save) and incremental sync (only documents
 *     changed since the last sync are read).
 *   - Clinical images compressed losslessly on the device (WebP lossless or PNG, <= 512 KB),
 *     stored once per content hash in Firebase Storage; the Storage link (gs:// reference and
 *     path) is saved in the patient's document.
 *
 * Pass your own FirebaseFirestore / FirebaseStorage (any FirebaseApp) and paths, so an existing
 * app can adopt it without changing its Firebase project:
 *
 *   val cloud = DentalCloud(FirebaseFirestore.getInstance(), FirebaseStorage.getInstance())
 *   val saved = cloud.records.commit(clinicId, uid, patient)
 *   val (changed, newest) = cloud.records.listSince(clinicId, lastSync)
 *   val link = cloud.images.upload(context, clinicId, patientId, uri)
 *   cloud.records.commit(clinicId, uid, cloud.images.attach(patient, link))
 */
class DentalCloud(
    private val db: FirebaseFirestore = FirebaseFirestore.getInstance(),
    private val storage: FirebaseStorage? = runCatching { FirebaseStorage.getInstance() }.getOrNull(),
    val options: Options = Options(),
) {
    data class Options(
        val root: String = "workspaces",
        val records: String = "patients",
        val maxDocBytes: Int = 750_000,
        val maxImageBytes: Int = LosslessImage.MAX_BYTES,
        val localFields: List<String> = listOf("dirty", "operationId", "remoteVersion", "cloudOwner", "retainedAt", "syncedAt", "patientDevices"),
        /** Fields owned by other writers (e.g. the patient login); kept from the cloud copy. */
        val serverOwnedFields: List<String> = listOf("portal"),
        val conflictMessage: (JSONObject) -> String = { "${it.optString("name", "This record")} was changed on another device. Review the conflict before saving again." },
    )

    /** The record changed on another device since it was loaded (optimistic concurrency). */
    class Conflict(message: String) : Exception(message)

    companion object { const val VERSION = "1.0.0" }

    val paths = Paths(options)
    val records = Records()
    val images = Images()

    class Paths(private val o: Options) {
        fun clinic(clinicId: String) = "${o.root}/$clinicId"
        fun publicInfo(clinicId: String) = "${o.root}/$clinicId/public/info"
        fun records(clinicId: String) = "${o.root}/$clinicId/${o.records}"
        fun record(clinicId: String, id: String) = "${o.root}/$clinicId/${o.records}/$id"
        fun image(clinicId: String, id: String, sha256: String, ext: String) = "${o.root}/$clinicId/${o.records}/$id/$sha256.$ext"
    }

    inner class Records {
        /**
         * Writes the whole record as one document if its cloud revision still equals
         * record.remoteVersion; returns the stored record (revision + 1) or throws [Conflict].
         */
        suspend fun commit(clinicId: String, uid: String, record: JSONObject): JSONObject = db.runTransaction { tx ->
            val ref = db.document(paths.record(clinicId, record.getString("id")))
            val snap = tx.get(ref)
            val serverRevision = snap.getLong("revision") ?: 0L
            if (serverRevision != record.optLong("remoteVersion")) throw Conflict(options.conflictMessage(record))
            val doc = JSONObject(record.toString())
            options.localFields.forEach { doc.remove(it) }
            options.serverOwnedFields.forEach { key -> doc.remove(key); snap.get(key)?.let { doc.put(key, DocCodec.fromValue(it)) } }
            doc.put("revision", serverRevision + 1).put("clinicId", clinicId).put("updatedBy", uid)
            check(sizeOf(doc) <= options.maxDocBytes) { "Patient document is full. Export and archive older history first." }
            tx.set(ref, DocCodec.toMap(doc) + ("syncedAt" to FieldValue.serverTimestamp()))
            doc
        }.await()

        /** Records changed after [since] (server ms; all when null) and the newest change time. Reads = changed documents only. */
        suspend fun listSince(clinicId: String, since: Long?): Pair<List<JSONObject>, Long> {
            var query: Query = db.collection(paths.records(clinicId))
            if (since != null) query = query.whereGreaterThan("syncedAt", Timestamp(java.util.Date(since)))
            var newest = 0L
            val list = query.get().await().documents.map { snap ->
                (snap.getTimestamp("syncedAt")?.toDate()?.time ?: 0L).let { if (it > newest) newest = it }
                clean(snap)
            }
            return list to newest
        }

        suspend fun get(clinicId: String, id: String): JSONObject? =
            db.document(paths.record(clinicId, id)).get().await().takeIf { it.exists() }?.let(::clean)

        fun sizeOf(record: JSONObject): Int = record.toString().toByteArray().size
        private fun clean(snap: DocumentSnapshot) = DocCodec.fromDoc(snap).also { it.remove("syncedAt"); it.remove("patientDevices") }
    }

    /** Link to one stored image, kept in the record's "images" list. */
    data class ImageLink(val path: String, val reference: String, val sha256: String, val bytes: Int, val width: Int, val height: Int,
                         val format: String, val resized: Boolean, val at: Long = System.currentTimeMillis()) {
        fun toJson(): JSONObject = JSONObject().put("path", path).put("reference", reference).put("sha256", sha256).put("bytes", bytes)
            .put("width", width).put("height", height).put("format", format).put("at", at)
    }

    inner class Images {
        fun encodeLossless(context: Context, uri: Uri): LosslessImage.Encoded = LosslessImage.encode(context, uri, options.maxImageBytes)

        /**
         * Compresses losslessly on the device, then uploads to {root}/{clinic}/{records}/{id}/{sha256}.{ext}.
         * The same image is stored once (content-hash name + one metadata check before uploading).
         */
        suspend fun upload(context: Context, clinicId: String, recordId: String, uri: Uri): ImageLink {
            val encoded = withContext(Dispatchers.IO) { encodeLossless(context, uri) }
            return upload(clinicId, recordId, encoded)
        }
        suspend fun upload(clinicId: String, recordId: String, encoded: LosslessImage.Encoded): ImageLink {
            val store = storage ?: error("Firebase Storage is not configured")
            check(encoded.bytes.size <= options.maxImageBytes) { "Image is larger than 512 KB" }
            val sha256 = MessageDigest.getInstance("SHA-256").digest(encoded.bytes).joinToString("") { "%02x".format(it) }
            val path = paths.image(clinicId, recordId, sha256, encoded.extension)
            val ref = store.reference.child(path)
            try { ref.metadata.await() } catch (e: StorageException) {
                if (e.errorCode != StorageException.ERROR_OBJECT_NOT_FOUND) throw e
                ref.putBytes(encoded.bytes, StorageMetadata.Builder().setContentType(encoded.mimeType).setCacheControl("private, max-age=31536000, immutable").build()).await()
            }
            return ImageLink(path, ref.toString(), sha256, encoded.bytes.size, encoded.width, encoded.height, encoded.extension, encoded.resized)
        }

        /** Copy of the record with the link added to its images list (no duplicates). */
        fun attach(record: JSONObject, link: ImageLink): JSONObject {
            val copy = JSONObject(record.toString())
            val list = copy.optJSONArray("images") ?: JSONArray().also { copy.put("images", it) }
            if ((0 until list.length()).none { list.optJSONObject(it)?.optString("path") == link.path }) list.put(link.toJson())
            return copy
        }

        /** Image bytes for display (Storage rules check the clinic's access). */
        suspend fun download(path: String): ByteArray = (storage ?: error("Firebase Storage is not configured")).reference.child(path).getBytes(options.maxImageBytes.toLong()).await()
    }
}

/** JSON (org.json) <-> Firestore values. */
object DocCodec {
    fun toMap(json: JSONObject): Map<String, Any?> = json.keys().asSequence().associateWith { key -> toValue(json.get(key)) }
    fun toValue(value: Any?): Any? = when (value) {
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
    fun fromDoc(snapshot: DocumentSnapshot): JSONObject = fromValue(snapshot.data ?: emptyMap<String, Any>()) as JSONObject
}
