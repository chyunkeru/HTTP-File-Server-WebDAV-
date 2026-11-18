package com.aistudio.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.aistudio.MainActivity
import com.aistudio.server.ServerManager

class FileServerService : Service() {
    companion object {
        const val CHANNEL_ID = "http_file_server_channel"
        const val CHANNEL_ID_MIN = "http_file_server_channel_min"
        const val NOTIFICATION_ID = 4004
        const val ACTION_STOP_SERVER = "com.aistudio.ACTION_STOP_SERVER"
        const val ACTION_UPDATE_NOTIFICATION = "com.aistudio.ACTION_UPDATE_NOTIFICATION"
    }

    private var wakeLock: android.os.PowerManager.WakeLock? = null
    private var wifiLock: android.net.wifi.WifiManager.WifiLock? = null

    override fun onCreate() {
        super.onCreate()
        
        // Ensure ServerManager is initialized
        ServerManager.init(applicationContext)

        createNotificationChannel()

        // Acquire WakeLock to keep CPU active during screen-off states
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
            wakeLock = powerManager?.newWakeLock(
                android.os.PowerManager.PARTIAL_WAKE_LOCK,
                "FileServerService::WakeLock"
            )?.apply {
                setReferenceCounted(false)
                acquire(10 * 3600 * 1000L) // Safe maximum limit of 10 hours
            }
        } catch (e: Exception) {
            android.util.Log.e("FileServerService", "Failed to acquire WakeLock: ${e.message}")
        }

        // Acquire WifiLock to keep Wi-Fi radio from sleep or low-power modes
        try {
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
            wifiLock = wifiManager?.createWifiLock(
                android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                "FileServerService::WifiLock"
            )?.apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (e: Exception) {
            android.util.Log.e("FileServerService", "Failed to acquire WifiLock: ${e.message}")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_SERVER) {
            ServerManager.stopEngine()
            stopForeground(true)
            stopSelf()
            return START_NOT_STICKY
        }

        if (intent?.action == ACTION_UPDATE_NOTIFICATION) {
            updateNotificationState()
            return START_STICKY
        }

        // Boot HTTP Server socket socket engine
        ServerManager.startEngine()

        updateNotificationState()

        return START_STICKY
    }

    private fun updateNotificationState() {
        if (ServerManager.isNotificationEnabled) {
            // Stop Action trigger within Notification actions
            val stopIntent = Intent(this, FileServerService::class.java).apply {
                action = ACTION_STOP_SERVER
            }
            val stopPendingIntent = PendingIntent.getService(
                this, 
                0, 
                stopIntent, 
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            // Main app launch when clicking notification body
            val mainIntent = Intent(this, MainActivity::class.java)
            val mainPendingIntent = PendingIntent.getActivity(
                this, 
                0, 
                mainIntent, 
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val url = ServerManager.serverUrl.value
            val channelToUse = if (ServerManager.isHideStatusBarIcon) CHANNEL_ID_MIN else CHANNEL_ID
            val priorityToUse = if (ServerManager.isHideStatusBarIcon) NotificationCompat.PRIORITY_MIN else NotificationCompat.PRIORITY_LOW

            val notification = NotificationCompat.Builder(this, channelToUse)
                .setContentTitle("File Server Active")
                .setContentText("Serving requests at: $url")
                .setSmallIcon(com.aistudio.R.drawable.ic_stat_cloud)
                .setContentIntent(mainPendingIntent)
                .setOngoing(true)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop Server", stopPendingIntent)
                .setPriority(priorityToUse)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .build()

            // Android 14 (U) requires declaring service foreground type as dataSync
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    startForeground(
                        NOTIFICATION_ID, 
                        notification, 
                        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                    )
                } else {
                    startForeground(NOTIFICATION_ID, notification)
                }
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } else {
            stopForeground(true)
        }
    }

    override fun onDestroy() {
        ServerManager.stopEngine()
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
                wakeLock = null
            }
        } catch (e: Exception) {
            // ignore
        }
        try {
            if (wifiLock?.isHeld == true) {
                wifiLock?.release()
                wifiLock = null
            }
        } catch (e: Exception) {
            // ignore
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            
            // Standard channel (shows icon in status bar)
            val channel = NotificationChannel(
                CHANNEL_ID,
                "File Server Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows active background file server status and network URL address."
            }
            manager.createNotificationChannel(channel)

            // Min channel (hides icon from status bar)
            val channelMin = NotificationChannel(
                CHANNEL_ID_MIN,
                "File Server Service (Hidden Status Bar)",
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = "Shows active background file server status with status bar icon hidden."
                setShowBadge(false)
            }
            manager.createNotificationChannel(channelMin)
        }
    }
}
