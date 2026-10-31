package com.spala.dental

import android.Manifest
import android.app.Application
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import androidx.compose.runtime.snapshotFlow
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

class ClinicApplication : Application() {
    val store by lazy { ClinicStore(this) }
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("clinic-reminders", "Clinic reminders", NotificationManager.IMPORTANCE_DEFAULT))
    }
}

object ClinicReminders {
    fun schedule(context: Context, store: ClinicStore) {
        val manager = WorkManager.getInstance(context)
        manager.cancelAllWorkByTag("clinic-reminder")
        if (!store.config.optBoolean("remindersEnabled") || store.cloudReminders) return
        val zone = runCatching { ZoneId.of(store.config.optString("timeZone", "Asia/Dhaka")) }.getOrDefault(ZoneId.systemDefault())
        val now = System.currentTimeMillis()
        for (patient in store.patients.filter { !it.optBoolean("archived") && it.optBoolean("communicationConsent", it.optBoolean("reminderConsent")) }) {
            for (appointment in patient.optJSONArray("appointments")?.objects().orEmpty()) {
                if (appointment.optString("status") !in listOf("Scheduled", "Confirmed") || !appointment.optBoolean("reminderEnabled") || !appointment.optBoolean("consent")) continue
                val at = appointment.optLong("startAt").takeIf { it > 0 } ?: runCatching {
                    LocalDateTime.parse(appointment.getString("date"), DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")).atZone(zone).toInstant().toEpochMilli()
                }.getOrNull() ?: continue
                if (at <= now) continue
                val delay = (at - appointment.optLong("reminderHours", store.config.optLong("reminderHours", 24)) * 3_600_000 - now).coerceAtLeast(0)
                val work = OneTimeWorkRequestBuilder<ClinicReminderWorker>().setInitialDelay(delay, TimeUnit.MILLISECONDS)
                    .setInputData(workDataOf("patient" to patient.optString("id"), "appointment" to appointment.optString("id"), "at" to at))
                    .addTag("clinic-reminder").build()
                manager.enqueueUniqueWork("appointment-${appointment.optString("id")}-$at", ExistingWorkPolicy.REPLACE, work)
            }
        }
    }
}

class ClinicReminderWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val store = (applicationContext as ClinicApplication).store
        withContext(Dispatchers.Main) { snapshotFlow { store.busy }.first { !it } }
        if (!store.config.optBoolean("remindersEnabled") || store.cloudReminders) return Result.success()
        val patient = store.patients.firstOrNull { it.optString("id") == inputData.getString("patient") && it.optBoolean("communicationConsent", it.optBoolean("reminderConsent")) } ?: return Result.success()
        val appointment = patient.optJSONArray("appointments")?.objects().orEmpty().firstOrNull { it.optString("id") == inputData.getString("appointment") } ?: return Result.success()
        if (appointment.optString("status") !in listOf("Scheduled", "Confirmed") || !appointment.optBoolean("reminderEnabled") || !appointment.optBoolean("consent")) return Result.success()
        val at = inputData.getLong("at", 0)
        if (runCatching { ClinicalRules.appointmentMillis(appointment, store.config) }.getOrDefault(0) != at) return Result.success()
        if (at <= System.currentTimeMillis()) return Result.success()
        val delivered = applicationContext.getSharedPreferences("reminder-delivery", Context.MODE_PRIVATE)
        val key = "${appointment.optString("id")}:$at"
        if (delivered.getBoolean(key, false)) return Result.success()
        val zone = runCatching { ZoneId.of(store.config.optString("timeZone", "Asia/Dhaka")) }.getOrDefault(ZoneId.systemDefault())
        val now = ZonedDateTime.now(zone)
        val quietStart = runCatching { LocalTime.parse(store.config.optString("quietStart", "21:00")) }.getOrDefault(LocalTime.of(21, 0))
        val quietEnd = runCatching { LocalTime.parse(store.config.optString("quietEnd", "08:00")) }.getOrDefault(LocalTime.of(8, 0))
        val localTime = now.toLocalTime()
        val quiet = if (quietStart > quietEnd) localTime >= quietStart || localTime < quietEnd else localTime >= quietStart && localTime < quietEnd
        if (quiet) {
            var end = now.withHour(quietEnd.hour).withMinute(quietEnd.minute).withSecond(0)
            if (!end.isAfter(now)) end = end.plusDays(1)
            val request = OneTimeWorkRequestBuilder<ClinicReminderWorker>().setInputData(inputData)
                .setInitialDelay(Duration.between(now, end).toMillis(), TimeUnit.MILLISECONDS).addTag("clinic-reminder").build()
            WorkManager.getInstance(applicationContext).enqueueUniqueWork("quiet-$key", ExistingWorkPolicy.KEEP, request)
            return Result.success()
        }
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return Result.success()
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("clinic-reminders", "Clinic reminders", NotificationManager.IMPORTANCE_DEFAULT))
        val intent = PendingIntent.getActivity(applicationContext, 0, Intent(applicationContext, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(applicationContext, "clinic-reminders").setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Clinic appointment reminder").setContentText("Open Dental Care to review your upcoming schedule.")
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setContentIntent(intent).setAutoCancel(true).build()
        NotificationManagerCompat.from(applicationContext).notify(key.hashCode(), notification)
        delivered.edit().putBoolean(key, true).apply()
        return Result.success()
    }
}
