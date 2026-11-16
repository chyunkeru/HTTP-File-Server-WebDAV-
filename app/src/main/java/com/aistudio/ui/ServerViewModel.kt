package com.aistudio.ui

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aistudio.db.LogEntry
import com.aistudio.db.SharedFolder
import com.aistudio.server.RootAdvancedFeatures
import com.aistudio.server.ServerManager
import com.aistudio.service.FileServerService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ServerViewModel(application: Application) : AndroidViewModel(application) {
    private val context = application.applicationContext

    init {
        ServerManager.init(context)
    }

    val isRunning: StateFlow<Boolean> = ServerManager.isRunning
    val serverUrl: StateFlow<String> = ServerManager.serverUrl

    val allLogs: StateFlow<List<LogEntry>> = (ServerManager.repository?.allLogs
        ?: MutableStateFlow(emptyList()))
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allFolders: StateFlow<List<SharedFolder>> = (ServerManager.repository?.allFolders
        ?: MutableStateFlow(emptyList()))
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Live State inputs for Settings Screen
    val portState = MutableStateFlow(ServerManager.port.toString())
    val webdavState = MutableStateFlow(ServerManager.isWebdavEnabled)
    val anonymousState = MutableStateFlow(ServerManager.isAnonymousAllowed)
    val usernameState = MutableStateFlow(ServerManager.authUsername)
    val passwordState = MutableStateFlow(ServerManager.authPassword)
    val notificationState = MutableStateFlow(ServerManager.isNotificationEnabled)
    val timeoutState = MutableStateFlow(ServerManager.idleTimeoutMinutes.toString())
    val rootSimulationState = MutableStateFlow(RootAdvancedFeatures.isRootSimulationEnabled)
    val themeState = MutableStateFlow(ServerManager.themePreference)

    fun setThemePreference(theme: String) {
        ServerManager.themePreference = theme
        ServerManager.saveSettings(context)
        themeState.value = theme
    }

    fun startServer() {
        ServerManager.startServer(context)
    }

    fun stopServer() {
        ServerManager.stopServer(context)
    }

    fun saveSettings(port: String, webdav: Boolean, anonymous: Boolean, user: String, pass: String, notificationEnabled: Boolean, timeoutMinutes: String, rootSimulation: Boolean) {
        val parsedPort = port.toIntOrNull() ?: 8080
        val parsedTimeout = timeoutMinutes.toIntOrNull() ?: 120
        ServerManager.port = parsedPort
        ServerManager.isWebdavEnabled = webdav
        ServerManager.isAnonymousAllowed = anonymous
        ServerManager.authUsername = user
        ServerManager.authPassword = pass
        ServerManager.isNotificationEnabled = notificationEnabled
        ServerManager.idleTimeoutMinutes = parsedTimeout
        RootAdvancedFeatures.isRootSimulationEnabled = rootSimulation
        
        ServerManager.saveSettings(context)
        
        portState.value = port
        webdavState.value = webdav
        anonymousState.value = anonymous
        usernameState.value = user
        passwordState.value = pass
        notificationState.value = notificationEnabled
        timeoutState.value = timeoutMinutes
        rootSimulationState.value = rootSimulation

        if (ServerManager.isRunning.value) {
            val serviceIntent = Intent(context, FileServerService::class.java).apply {
                action = FileServerService.ACTION_UPDATE_NOTIFICATION
            }
            context.startService(serviceIntent)
        }
    }

    fun addFolderShare(name: String, absolutePath: String, isWritable: Boolean) {
        viewModelScope.launch {
            val folder = SharedFolder(
                name = name,
                absolutePath = absolutePath,
                isWritable = isWritable
            )
            ServerManager.repository?.insertFolder(folder)
        }
    }

    fun deleteFolderShare(id: Long) {
        viewModelScope.launch {
            ServerManager.repository?.deleteFolderById(id)
        }
    }

    fun clearLogHistory() {
        viewModelScope.launch {
            ServerManager.repository?.clearLogs()
        }
    }
}
