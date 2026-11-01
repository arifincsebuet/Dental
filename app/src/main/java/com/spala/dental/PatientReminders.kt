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
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/*
 * Patient appointment reminders, scheduled on the patient's own phone (no server, no push).
 * Each time the patient app loads the record, reminders are rebuilt from the patient's upcoming
 * appointments: one at the clinic's reminder lead time (default 24 h) and one 2 hours before.
 * WorkManager keeps them across restarts. Nothing is stored in the cloud.
 */

private const val CHANNEL = "patient-reminders"
private const val TAG = "patient-reminder"

object PatientReminders {
    fun cancelAll(context: Context) = WorkManager.getInstance(context).cancelAllWorkByTag(TAG)

    fun schedule(context: Context, patient: JSONObject, clinic: JSONObject) {
        val manager = WorkManager.getInstance(context)
        manager.cancelAllWorkByTag(TAG)
        val now = System.currentTimeMillis()
        val leadHours = clinic.optInt("reminderHours", 24).coerceIn(1, 720)
        for (appointment in patient.optJSONArray("appointments").objectList()) {
            if (appointment.optString("status") !in listOf("Scheduled", "Confirmed")) continue
            val at = runCatching { ClinicalRules.appointmentMillis(appointment, clinic) }.getOrNull() ?: continue
            if (at <= now) continue
            val time = runCatching { ClinicalRules.displayTime(at, clinic) }.getOrDefault(appointment.optString("date"))
            val doctor = appointment.optString("doctor").ifBlank { "the clinic" }
            for (hours in setOf(leadHours, 2)) {
                val fireAt = at - hours * 3_600_000L
                if (fireAt <= now) continue
                val work = OneTimeWorkRequestBuilder<PatientReminderWorker>()
                    .setInitialDelay(fireAt - now, TimeUnit.MILLISECONDS)
                    .setInputData(workDataOf("id" to "${appointment.optString("id")}-$hours", "title" to "Dental appointment ${if (hours >= 24) "tomorrow" else "soon"}",
                        "text" to "$time with $doctor at ${clinic.optString("name", "the clinic")}"))
                    .addTag(TAG).build()
                manager.enqueueUniqueWork("patient-${appointment.optString("id")}-$hours", ExistingWorkPolicy.REPLACE, work)
            }
        }
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
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setPublicVersion(public)
            .setContentIntent(open).setAutoCancel(true).build()
        NotificationManagerCompat.from(context).notify(id.hashCode(), notification)
    }
}

class PatientReminderWorker(context: Context, parameters: WorkerParameters) : Worker(context, parameters) {
    override fun doWork(): Result {
        PatientReminders.notify(applicationContext, inputData.getString("id") ?: "reminder",
            inputData.getString("title") ?: "Dental appointment reminder", inputData.getString("text") ?: "Open Dental Care to see your appointment.")
        return Result.success()
    }
}
