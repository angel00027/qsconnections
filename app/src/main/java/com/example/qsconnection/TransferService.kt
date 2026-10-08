package com.example.qsconnection

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

class TransferService : Service() {

    private val CHANNEL_ID = "transfer_service_channel"
    private val NOTIFICATION_ID = 101
    private val COMPLETION_NOTIFICATION_ID = 102

    companion object {
        const val ACTION_STOP_TRANSFERS = "com.example.qsconnection.STOP_TRANSFERS"

        // Indica si el servicio está en primer plano. El ViewModel lo consulta
        // antes de arrancarlo/actualizarlo para no crear un servicio en primer
        // plano que se cierre de inmediato (causa del cierre de la app con
        // archivos pequeños).
        @Volatile
        var running = false
            private set
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_TRANSFERS) {
            // Enviar un broadcast para que el ViewModel cancele todo
            sendBroadcast(Intent(ACTION_STOP_TRANSFERS))
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            running = false
            return START_NOT_STICKY
        }

        val fileName = intent?.getStringExtra("FILE_NAME") ?: "Transferencia"
        val progress = intent?.getIntExtra("PROGRESS", 0) ?: 0
        val activeCount = intent?.getIntExtra("ACTIVE_COUNT", 1) ?: 1
        val isDone = intent?.getBooleanExtra("IS_DONE", false) ?: false
        val speed = intent?.getStringExtra("SPEED") ?: ""
        val remaining = intent?.getStringExtra("REMAINING_TIME") ?: ""
        val result = intent?.getStringExtra("RESULT") ?: "done"

        if (isDone) {
            val manager = getSystemService(NotificationManager::class.java)
            manager?.cancel(NOTIFICATION_ID)
            stopForeground(STOP_FOREGROUND_REMOVE)
            // Aviso final para que el usuario sepa qué pasó aunque esté usando
            // otra app (así puede dejarlo en segundo plano con calma).
            try {
                val contentIntent = PendingIntent.getActivity(
                    this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
                )
                val (title, icon) = when (result) {
                    "error" -> "Transferencia fallida" to android.R.drawable.stat_notify_error
                    "cancel" -> "Transferencia cancelada" to android.R.drawable.ic_menu_close_clear_cancel
                    else -> "Transferencia completada" to android.R.drawable.stat_sys_upload_done
                }
                val done = NotificationCompat.Builder(this, CHANNEL_ID)
                    .setContentTitle(title)
                    .setContentText(fileName)
                    .setSmallIcon(icon)
                    .setAutoCancel(true)
                    .setContentIntent(contentIntent)
                    .build()
                manager?.notify(COMPLETION_NOTIFICATION_ID, done)
            } catch (_: Exception) {
            }
            stopSelf()
            running = false
            return START_NOT_STICKY
        }

        val notification = createNotification(fileName, progress, activeCount, speed, remaining)
        startForeground(NOTIFICATION_ID, notification)
        running = true

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        super.onDestroy()
    }

    private fun createNotification(fileName: String, progress: Int, activeCount: Int, speed: String = "", remaining: String = ""): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent, PendingIntent.FLAG_IMMUTABLE
        )

        // Acción para detener
        val stopIntent = Intent(this, TransferService::class.java).apply {
            action = ACTION_STOP_TRANSFERS
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 1, stopIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val title = if (activeCount > 1) "Transfiriendo $activeCount archivos ($progress%)" else "QsConnections ($progress%)"
        
        val contentText = buildString {
            if (activeCount > 1) {
                append("Total: $progress% - ")
            }
            append(fileName)
            if (speed.isNotBlank()) append(" - $speed")
            if (remaining.isNotBlank()) append(" (Faltan $remaining)")
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setProgress(100, progress, false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Detener todo", stopPendingIntent)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Transferencias de Archivos",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
