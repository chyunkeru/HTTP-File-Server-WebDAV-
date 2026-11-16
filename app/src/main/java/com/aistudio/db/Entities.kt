package com.aistudio.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "log_entries")
data class LogEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val clientIp: String,
    val method: String,
    val uri: String,
    val responseCode: Int,
    val byteCount: Long = 0,
    val details: String = ""
)

@Entity(tableName = "shared_folders")
data class SharedFolder(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val absolutePath: String,
    val isWritable: Boolean = true,
    val isDefault: Boolean = false
)
