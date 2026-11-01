package com.spala.dental

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.*
import com.google.firebase.firestore.FieldPath
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.tasks.await
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.TimeUnit

/*
 * Patient appointment reminders without Cloud Functions.
 *
 * 1. Token: the patient app writes its FCM token into its own patient document
 *    (workspaces/{clinic}/patients/{patient}.patientDevices.{deviceId}). Firestore rules let a
 *    signed-in patient change only that field of only their own document: no extra documents.
 * 2. Delivery: FCM messages can only be *sent* with server credentials, which must never ship in
 *    the app. Reminders are therefore scheduled on the patient's phone (WorkManager, survives
 *    reboots) 24 h and 2 h before each upcoming appointment, refreshed whenever the record loads.
 *    If a sender is added later, ClinicMessagingService already displays "patient_reminder" pushes.
 */

private const val CHANNEL = "patient-reminders"
private const val TAG = "patient-reminder"

object PatientPush {
    private fun deviceId(context: Context): String {
        val prefs = context.getSharedPreferences("device", Context.MODE_PRIVATE)
        return prefs.getString("id", null) ?: UUID.randomUUID().toString().replace("-", "").also { prefs.edit().putString("id", it).apply() }
    }

    /** Stores this device's FCM token in the patient's own Firestore document (one field, one write). */
    suspend fun register(context: Context, clinicId: String, patientId: String, token: String? = null) {
        val value = token ?: FirebaseMessaging.getInstance().token.await()
        FirebaseFirestore.getInstance().document("workspaces/$clinicId/patients/$patientId")
            .update(FieldPath.of("patientDevices", deviceId(context)), mapOf("token" to value, "platform" to "android", "updatedAt" to FieldValue.serverTimestamp()))
            .await()
    }

    /** Removes this device's token on sign-out so a shared phone stops receiving the patient's reminders. */
    suspend fun unregister(context: Context, clinicId: String, patientId: String) {
        FirebaseFirestore.getInstance().document("workspaces/$clinicId/patients/$patientId")
            .update(FieldPath.of("patientDevices", deviceId(context)), FieldValue.delete()).await()
    }
}

object PatientReminders {
    private val active = setOf("Requested", "Scheduled", "Confirmed")
    private val leads = listOf(24L to "tomorrow", 2L to "in 2 hours")

    /** Re-plans every reminder from the patient's current appointments (cancelled ones disappear). */
    fun schedule(context: Context, record: JSONObject, clinic: JSONObject) {
        val manager = WorkManager.getInstance(context)
        manager.cancelAllWorkByTag(TAG)
        if (!enabled(context)) return
        val zone = runCatching { ZoneId.of(clinic.optString("timeZone", "Asia/Dhaka")) }.getOrDefault(ZoneId.systemDefault())
        val now = System.currentTimeMillis()
        for (appointment in record.optJSONArray("appointments").objectList()) {
            if (appointment.optString("status") !in active) continue
            val at = runCatching { ClinicalRules.appointmentMillis(appointment, clinic) }.getOrNull() ?: continue
            if (at <= now) continue
            val time = Instant.ofEpochMilli(at).atZone(zone).format(DateTimeFormatter.ofPattern("EEE d MMM, HH:mm"))
            for ((hours, when_) in leads) {
                val fireAt = at - hours * 3_600_000L
                if (fireAt <= now) continue
                val work = OneTimeWorkRequestBuilder<PatientReminderWorker>()
                    .setInitialDelay(fireAt - now, TimeUnit.MILLISECONDS)
                    .setInputData(workDataOf("id" to "${appointment.optString("id")}:$at:$hours", "title" to "Dental appointment $when_",
                        "text" to listOf(time, appointment.optString("doctor"), clinic.optString("name")).filter { it.isNotBlank() }.joinToString(" · ")))
                    .addTag(TAG).build()
                manager.enqueueUniqueWork("patient-${appointment.optString("id")}-$hours", ExistingWorkPolicy.REPLACE, work)
            }
        }
    }

    fun cancelAll(context: Context) = WorkManager.getInstance(context).cancelAllWorkByTag(TAG)

    fun enabled(context: Context) = context.getSharedPreferences("display", Context.MODE_PRIVATE).getBoolean("patientReminders", true)
    fun setEnabled(context: Context, value: Boolean) {
        context.getSharedPreferences("display", Context.MODE_PRIVATE).edit().putBoolean("patientReminders", value).apply()
        if (!value) cancelAll(context)
    }

    /** Shows a reminder on the patient's own phone (details are hidden on a locked screen). */
    fun notify(context: Context, id: String, title: String, text: String) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        context.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CHANNEL, "Appointment reminders", NotificationManager.IMPORTANCE_HIGH))
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val public = NotificationCompat.Builder(context, CHANNEL).setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle("Dental appointment reminder").setContentText("Unlock to see details").build()
        val notification = NotificationCompat.Builder(context, CHANNEL).setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(title).setContentText(text).setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setPublicVersion(public)
            .setContentIntent(open).setAutoCancel(true).build()
        NotificationManagerCompat.from(context).notify(id.hashCode(), notification)
    }
}

class PatientReminderWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        if (!PatientReminders.enabled(applicationContext)) return Result.success()
        PatientReminders.notify(applicationContext, inputData.getString("id").orEmpty(),
            inputData.getString("title") ?: "Dental appointment reminder", inputData.getString("text").orEmpty())
        return Result.success()
    }
}
