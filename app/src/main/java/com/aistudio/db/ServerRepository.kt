package com.aistudio.db

import kotlinx.coroutines.flow.Flow

class ServerRepository(private val dao: ServerDao) {
    val allLogs: Flow<List<LogEntry>> = dao.getAllLogs()
    val allFolders: Flow<List<SharedFolder>> = dao.getAllFolders()

    suspend fun insertLog(log: LogEntry) {
        dao.insertLog(log)
    }

    suspend fun clearLogs() {
        dao.clearLogs()
    }

    suspend fun insertFolder(folder: SharedFolder) {
        dao.insertFolder(folder)
    }

    suspend fun deleteFolderById(id: Long) {
        dao.deleteFolderById(id)
    }

    suspend fun updateFolderEnabled(id: Long, isEnabled: Boolean) {
        dao.updateFolderEnabled(id, isEnabled)
    }

    suspend fun getFoldersCount(): Int {
        return dao.getFoldersCount()
    }

    fun getAllFoldersSync(): List<SharedFolder> {
        return dao.getAllFoldersSync()
    }
}
