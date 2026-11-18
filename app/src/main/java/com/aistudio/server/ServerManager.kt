package com.aistudio.server

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import com.aistudio.db.AppDatabase
import com.aistudio.db.LogEntry
import com.aistudio.db.ServerRepository
import com.aistudio.db.SharedFolder
import com.aistudio.service.FileServerService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.net.NetworkInterface
import java.util.Collections

object ServerManager {
    private const val TAG = "ServerManager"

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning

    private val _serverUrl = MutableStateFlow("")
    val serverUrl: StateFlow<String> = _serverUrl

    // Server Configuration Properties
    var port = 8080
    var isWebdavEnabled = true
    var isAnonymousAllowed = true
    var authUsername = "admin"
    var authPassword = "admin"
    var isNotificationEnabled = false
    var isHideStatusBarIcon = false
    var idleTimeoutMinutes = 120
    var themePreference = "system"

    @Volatile
    var lastActiveTime = System.currentTimeMillis()
    private var timeoutJob: Job? = null
    private var applicationContext: Context? = null

    var repository: ServerRepository? = null
    private var activeServer: HttpServer? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun init(context: Context) {
        applicationContext = context.applicationContext
        val database = AppDatabase.getDatabase(context)
        repository = ServerRepository(database.serverDao())

        // Check root privilege on launch and auto-grant permissions if rooted
        RootAdvancedFeatures.ensureRootPrivilegesAndAutoGrant(context.packageName)

        // Load configurations
        val prefs = context.getSharedPreferences("server_settings", Context.MODE_PRIVATE)
        RootAdvancedFeatures.isRootSimulationEnabled = prefs.getBoolean("is_root_simulation_enabled", false)
        port = prefs.getInt("port", 8080)
        isWebdavEnabled = prefs.getBoolean("is_webdav_enabled", true)
        isAnonymousAllowed = prefs.getBoolean("is_anonymous_allowed", true)
        authUsername = prefs.getString("auth_username", "admin") ?: "admin"
        authPassword = prefs.getString("auth_password", "admin") ?: "admin"
        if (authPassword == "password") {
            authPassword = "admin"
            prefs.edit().putString("auth_password", "admin").apply()
        }
        isNotificationEnabled = prefs.getBoolean("is_notification_enabled", false)
        isHideStatusBarIcon = prefs.getBoolean("is_hide_status_bar_icon", false)
        idleTimeoutMinutes = prefs.getInt("idle_timeout_minutes", 120)
        themePreference = prefs.getString("theme_preference", "system") ?: "system"

        val isRunningDesired = prefs.getBoolean("is_running_desired", false)
        if (isRunningDesired && !_isRunning.value && activeServer == null) {
            scope.launch {
                delay(800)
                withContext(Dispatchers.Main) {
                    if (!_isRunning.value && activeServer == null) {
                        startServer(context)
                    }
                }
            }
        }

        // Seed default sandbox direct public folder if none exists
        scope.launch {
            repository?.let { repo ->
                if (repo.getFoldersCount() == 0) {
                    val defaultDir = File("/storage/emulated/0")
                    
                    // Create default index readme document if possible and storage permission permits writing
                    val isStorageWritable = try {
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                            android.os.Environment.isExternalStorageManager() && defaultDir.exists() && defaultDir.canWrite()
                        } else {
                            defaultDir.exists() && defaultDir.canWrite()
                        }
                    } catch (_: Exception) {
                        false
                    }

                    if (isStorageWritable) {
                        try {
                            val welcomeFile = File(defaultDir, "Welcome_ReadMe.html")
                            if (!welcomeFile.exists()) {
                                welcomeFile.writeText("""
                                    |<!DOCTYPE html>
                                    |<html>
                                    |<head>
                                    |    <meta charset="utf-8">
                                    |    <title>Internal Shared Drive</title>
                                    |    <style>
                                    |        body { font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif; color: #1e293b; max-width: 600px; margin: 60px auto; padding: 0 24px; line-height: 1.6; background-color: #f8fafc; }
                                    |        .card { background: #ffffff; border: 1px solid #e2e8f0; border-radius: 12px; padding: 32px; box-shadow: 0 4px 12px rgba(0,0,0,0.03); }
                                    |        h1 { color: #3b82f6; font-size: 26px; margin-top: 0; font-weight: 800; letter-spacing: -0.5px; }
                                    |        p { color: #475569; font-size: 15px; margin-bottom: 20px; }
                                    |        .badge { display: inline-block; background: #eff6ff; border: 1px solid #bfdbfe; border-radius: 9999px; padding: 4px 14px; font-size: 11px; font-weight: 700; color: #1d4ed8; text-transform: uppercase; }
                                    |    </style>
                                    |</head>
                                    |<body>
                                    |    <div class="card">
                                    |        <h1>Server sandbox storage is live! 🚀</h1>
                                    |        <p>This directory is mapped as your local server storage. Files uploaded from your browser or WebDAV explorer will appear on your device here.</p>
                                    |        <p>You can add additional custom shares through the Android app settings.</p>
                                    |        <span class="badge">Sandbox Enabled</span>
                                    |    </div>
                                    |</body>
                                    |</html>
                                """.trimMargin())
                            }
                        } catch (e: Exception) {
                            Log.d(TAG, "Notice: Could not write Welcome_ReadMe.html: ${e.message}")
                        }
                    }
                    
                    repo.insertFolder(SharedFolder(
                        name = "Internal Storage",
                        absolutePath = defaultDir.absolutePath,
                        isWritable = true,
                        isDefault = true
                    ))
                }
            }
        }
    }

    fun saveSettings(context: Context) {
        val prefs = context.getSharedPreferences("server_settings", Context.MODE_PRIVATE)
        prefs.edit()
            .putInt("port", port)
            .putBoolean("is_webdav_enabled", isWebdavEnabled)
            .putBoolean("is_anonymous_allowed", isAnonymousAllowed)
            .putString("auth_username", authUsername)
            .putString("auth_password", authPassword)
            .putBoolean("is_notification_enabled", isNotificationEnabled)
            .putBoolean("is_hide_status_bar_icon", isHideStatusBarIcon)
            .putInt("idle_timeout_minutes", idleTimeoutMinutes)
            .putBoolean("is_root_simulation_enabled", RootAdvancedFeatures.isRootSimulationEnabled)
            .putString("theme_preference", themePreference)
            .apply()
    }

    fun startServer(context: Context) {
        val prefs = context.getSharedPreferences("server_settings", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("is_running_desired", true).apply()

        if (_isRunning.value) return
        val serviceIntent = Intent(context, FileServerService::class.java)
        if (isNotificationEnabled) {
            ContextCompat.startForegroundService(context, serviceIntent)
        } else {
            context.startService(serviceIntent)
        }
    }

    fun stopServer(context: Context) {
        val prefs = context.getSharedPreferences("server_settings", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("is_running_desired", false).apply()

        val serviceIntent = Intent(context, FileServerService::class.java)
        context.stopService(serviceIntent)
        _isRunning.value = false
        _serverUrl.value = ""
    }

    fun restartServer(context: Context) {
        stopServer(context)
        scope.launch {
            delay(800)
            withContext(Dispatchers.Main) {
                startServer(context)
            }
        }
    }

    // Starts background socket acceptor on port
    fun startEngine() {
        val hostIp = getLocalIpAddress()
        val url = "http://$hostIp:$port"
        _serverUrl.value = url

        activeServer?.stop()

        activeServer = HttpServer(
            port = port,
            isWebdavEnabled = isWebdavEnabled,
            isAnonymousAllowed = isAnonymousAllowed,
            authUsername = authUsername,
            authPassword = authPassword,
            onLogRequest = { entry ->
                lastActiveTime = System.currentTimeMillis()
                scope.launch {
                    repository?.insertLog(entry)
                }
            },
            getFoldersProvider = {
                repository?.getAllFoldersSync() ?: emptyList()
            }
        )

        activeServer?.start()
        _isRunning.value = true
        Log.d(TAG, "Server running at: $url")

        // Start inactivity monitor (configurable disconnect check)
        lastActiveTime = System.currentTimeMillis()
        timeoutJob?.cancel()
        timeoutJob = scope.launch {
            while (isActive && _isRunning.value) {
                delay(60000) // check every minute
                if (idleTimeoutMinutes <= 0) continue // <= 0 means Never/disabled
                val elapsed = System.currentTimeMillis() - lastActiveTime
                val timeoutMs = idleTimeoutMinutes * 60 * 1000L
                if (elapsed >= timeoutMs) {
                    Log.d(TAG, "No activity for $idleTimeoutMinutes minutes. Automatically disconnecting server.")
                    applicationContext?.let { ctx ->
                        withContext(Dispatchers.Main) {
                            stopServer(ctx)
                        }
                    }
                }
            }
        }
    }

    fun stopEngine() {
        timeoutJob?.cancel()
        timeoutJob = null
        activeServer?.stop()
        activeServer = null
        _isRunning.value = false
        _serverUrl.value = ""
        Log.d(TAG, "Server engine stopped.")
    }

    fun restartEngineOnly() {
        stopEngine()
        scope.launch {
            delay(500)
            startEngine()
        }
    }

    fun getLocalIpAddress(): String {
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            // First check Wi-Fi, Ethernet, and AccessPoint AP interfaces
            for (networkInterface in interfaces) {
                if (networkInterface.name.startsWith("wlan") || networkInterface.name.startsWith("ap") || networkInterface.name.startsWith("eth")) {
                    val addresses = Collections.list(networkInterface.inetAddresses)
                    for (address in addresses) {
                        if (!address.isLoopbackAddress) {
                            val ip = address.hostAddress ?: ""
                            if (ip.indexOf(':') < 0) return ip
                        }
                    }
                }
            }
            // fallback check any active address
            for (networkInterface in interfaces) {
                val addresses = Collections.list(networkInterface.inetAddresses)
                for (address in addresses) {
                    if (!address.isLoopbackAddress) {
                        val ip = address.hostAddress ?: ""
                        if (ip.indexOf(':') < 0) return ip
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return "127.0.0.1"
    }
}
