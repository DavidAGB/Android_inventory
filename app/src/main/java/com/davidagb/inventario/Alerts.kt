package com.davidagb.inventario

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import java.util.concurrent.TimeUnit

class AlertJob : JobService() {
    override fun onStartJob(params: JobParameters?): Boolean {
        Alerts.check(this)
        return false
    }

    override fun onStopJob(params: JobParameters?) = false
}

/**
 * Cada 12 horas revisa el stock bajo y lo que vence en 7 días.
 * Solo notifica cuando la situación cambia, para no repetir el mismo aviso.
 */
object Alerts {
    private const val CHANNEL = "alertas"
    private const val JOB_ID = 1
    private const val NOTIFICATION_ID = 1

    fun schedule(ctx: Context) {
        val js = ctx.getSystemService(JobScheduler::class.java) ?: return
        if (js.getPendingJob(JOB_ID) != null) return
        js.schedule(
            JobInfo.Builder(JOB_ID, ComponentName(ctx, AlertJob::class.java))
                .setPeriodic(TimeUnit.HOURS.toMillis(12))
                .setPersisted(true)
                .build(),
        )
    }

    fun check(ctx: Context) {
        val inv = Inventory(ctx)
        val low = inv.products().filter { it.low }
        val expiring = inv.expiring(7)
        val prefs = ctx.getSharedPreferences("alertas", Context.MODE_PRIVATE)
        if (low.isEmpty() && expiring.isEmpty()) {
            prefs.edit().remove("last").apply()
            return
        }
        val key = low.joinToString(",") { "${it.id}:${it.units}" } + "|" + expiring.joinToString(",") { "${it.productId}:${it.expiry}" }
        if (prefs.getString("last", null) == key) return
        if (Build.VERSION.SDK_INT >= 33 &&
            ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return

        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Alertas de inventario", NotificationManager.IMPORTANCE_DEFAULT))
        val title = listOfNotNull(
            if (low.isNotEmpty()) "${low.size} por reponer" else null,
            if (expiring.isNotEmpty()) "${expiring.size} por vencer" else null,
        ).joinToString(" · ")
        val lines = low.map { "Reponer: ${it.name} (quedan ${it.units})" } +
            expiring.map { "${it.name}: ${Fmt.expiryText(it.expiry).replaceFirstChar { c -> c.lowercase() }}" }
        val text = lines.joinToString("\n")
        val open = PendingIntent.getActivity(
            ctx, 0,
            Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val n = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(title)
            .setContentText(lines.first())
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        nm.notify(NOTIFICATION_ID, n)
        prefs.edit().putString("last", key).apply()
    }
}
