package com.aistudio.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface ServerDao {
    // Log entries queries
    @Query("SELECT * FROM log_entries ORDER BY timestamp DESC LIMIT 500")
    fun getAllLogs(): Flow<List<LogEntry>>

    @Insert
    suspend fun insertLog(log: LogEntry)

    @Query("DELETE FROM log_entries")
    suspend fun clearLogs()

    // Shared folders queries
    @Query("SELECT * FROM shared_folders ORDER BY id ASC")
    fun getAllFolders(): Flow<List<SharedFolder>>

    @Query("SELECT * FROM shared_folders ORDER BY id ASC")
    fun getAllFoldersSync(): List<SharedFolder>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFolder(folder: SharedFolder)

    @Query("DELETE FROM shared_folders WHERE id = :id")
    suspend fun deleteFolderById(id: Long)

    @Query("UPDATE shared_folders SET isEnabled = :isEnabled WHERE id = :id")
    suspend fun updateFolderEnabled(id: Long, isEnabled: Boolean)

    @Query("SELECT COUNT(*) FROM shared_folders")
    suspend fun getFoldersCount(): Int
}
