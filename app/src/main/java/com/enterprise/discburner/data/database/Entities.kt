package com.enterprise.discburner.data.database

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import java.util.Date

/**
 * 审计日志实体
 */
@Entity(tableName = "audit_logs")
@TypeConverters(DateConverter::class)
data class AuditLogEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    val sessionId: String,
    val eventCode: String,
    val eventDescription: String,
    val timestamp: Date,
    val detailsJson: String?,  // JSON格式的详细信息

    // 数字签名（防篡改）
    val signature: String? = null,
    val signatureValid: Boolean = true,

    // 同步状态
    val synced: Boolean = false,
    val syncTimestamp: Date? = null,

    // 设备信息
    val deviceId: String? = null,
    val deviceModel: String? = null
)

/**
 * 刻录会话实体
 */
@Entity(tableName = "burn_sessions")
@TypeConverters(DateConverter::class)
data class BurnSessionEntity(
    @PrimaryKey
    val sessionId: String,

    val startTime: Date,
    val endTime: Date? = null,
    val duration: Long? = null,

    val status: String,  // STARTED, COMPLETED, FAILED, CANCELLED

    // 刻录信息
    val volumeLabel: String? = null,
    val writeMode: String? = null,
    val closeSession: Boolean = false,
    val closeDisc: Boolean = false,

    // 文件信息
    val fileName: String? = null,
    val fileSize: Long? = null,
    val sectorsWritten: Long? = null,

    // 校验信息
    val sourceHash: String? = null,
    val verifiedHash: String? = null,
    val verifyPassed: Boolean? = null,

    // 错误信息
    val errorCode: String? = null,
    val errorMessage: String? = null,

    // 光盘信息
    val discStatus: Int? = null,
    val discSessions: Int? = null,
    val discTracks: Int? = null,

    // 数字签名
    val sessionSignature: String? = null
)

/**
 * 刻录文件实体（记录每个会话刻录的文件）
 */
@Entity(
    tableName = "burn_files",
    primaryKeys = ["sessionId", "filePath"]
)
data class BurnFileEntity(
    val sessionId: String,
    val filePath: String,
    val fileName: String,
    val fileSize: Long,
    val isoPath: String? = null,
    val fileHash: String? = null
)

/**
 * 设备信息实体
 */
@Entity(tableName = "device_info")
@TypeConverters(DateConverter::class)
data class DeviceInfoEntity(
    @PrimaryKey
    val deviceId: String,

    val firstSeen: Date,
    val lastUsed: Date,

    val vendorId: String? = null,
    val productId: String? = null,
    val manufacturer: String? = null,
    val productName: String? = null,

    val totalBurns: Int = 0,
    val successfulBurns: Int = 0,
    val failedBurns: Int = 0
)

/**
 * Date类型转换器
 */
class DateConverter {
    @TypeConverter
    fun fromTimestamp(value: Long?): Date? {
        return value?.let { Date(it) }
    }

    @TypeConverter
    fun dateToTimestamp(date: Date?): Long? {
        return date?.time
    }
}
