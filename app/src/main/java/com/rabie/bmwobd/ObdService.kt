package com.rabie.bmwobd

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Mantiene viva la app mientras hay conexion con el coche. No hace la lectura: eso es de
 * [ObdController]. Se para solo cuando la conexion termina.
 */
class ObdService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onBind(intent: Intent?): IBinder? = null

    private var watcher: Job? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        } catch (e: Exception) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (watcher?.isActive != true) {
            val controller = (application as BmwObdApp).controller
            watcher = scope.launch {
                controller.state.first { it.phase == Phase.IDLE || it.phase == Phase.ERROR }
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun notification() = NotificationCompat.Builder(this, channel())
        .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
        .setContentTitle("BMW OBD conectado")
        .setContentText("Leyendo y grabando el trayecto")
        .setOngoing(true)
        .setContentIntent(
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        )
        .build()

    private fun channel(): String {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Conexión con el coche", NotificationManager.IMPORTANCE_LOW),
        )
        return CHANNEL_ID
    }

    private companion object {
        const val CHANNEL_ID = "obd"
        const val NOTIFICATION_ID = 1
    }
}
