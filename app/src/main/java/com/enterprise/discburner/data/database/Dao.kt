package com.enterprise.discburner.data.database

import androidx.room.*
import kotlinx.coroutines.flow.Flow

/**
 * 审计日志DAO
 */
@Dao
interface AuditLogDao {

    @Query("SELECT * FROM audit_logs ORDER BY timestamp DESC")
    fun getAllLogs(): Flow<List<AuditLogEntity>>

    @Query("SELECT * FROM audit_logs WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    suspend fun getLogsBySession(sessionId: String): List<AuditLogEntity>

    @Query("SELECT * FROM audit_logs WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    fun getLogsBySessionFlow(sessionId: String): Flow<List<AuditLogEntity>>

    @Query("""
        SELECT * FROM audit_logs
        WHERE timestamp >= :startTime AND timestamp <= :endTime
        ORDER BY timestamp DESC
    """)
    suspend fun getLogsByTimeRange(startTime: Long, endTime: Long): List<AuditLogEntity>

    @Query("SELECT * FROM audit_logs WHERE synced = 0 ORDER BY timestamp ASC LIMIT :limit")
    suspend fun getUnsyncedLogs(limit: Int = 100): List<AuditLogEntity>

    @Query("UPDATE audit_logs SET synced = 1, syncTimestamp = :timestamp WHERE id IN (:ids)")
    suspend fun markAsSynced(ids: List<Long>, timestamp: Long)

    @Insert
    suspend fun insert(log: AuditLogEntity): Long

    @Insert
    suspend fun insertAll(logs: List<AuditLogEntity>)

    @Query("SELECT COUNT(*) FROM audit_logs")
    suspend fun getLogCount(): Int

    @Query("DELETE FROM audit_logs WHERE timestamp < :beforeTime")
    suspend fun deleteOldLogs(beforeTime: Long)

    @Query("""
        SELECT * FROM audit_logs
        WHERE eventCode LIKE '%' || :query || '%'
        OR eventDescription LIKE '%' || :query || '%'
        OR sessionId LIKE '%' || :query || '%'
        ORDER BY timestamp DESC
    """)
    suspend fun searchLogs(query: String): List<AuditLogEntity>
}

/**
 * 刻录会话DAO
 */
@Dao
interface BurnSessionDao {

    @Query("SELECT * FROM burn_sessions ORDER BY startTime DESC")
    fun getAllSessions(): Flow<List<BurnSessionEntity>>

    @Query("SELECT * FROM burn_sessions WHERE sessionId = :sessionId")
    suspend fun getSessionById(sessionId: String): BurnSessionEntity?

    @Query("SELECT * FROM burn_sessions WHERE status = :status ORDER BY startTime DESC")
    suspend fun getSessionsByStatus(status: String): List<BurnSessionEntity>

    @Query("""
        SELECT * FROM burn_sessions
        WHERE startTime >= :startTime AND startTime <= :endTime
        ORDER BY startTime DESC
    """)
    suspend fun getSessionsByTimeRange(startTime: Long, endTime: Long): List<BurnSessionEntity>

    @Query("""
        SELECT COUNT(*) as total,
               SUM(CASE WHEN status = 'COMPLETED' THEN 1 ELSE 0 END) as success,
               SUM(CASE WHEN status = 'FAILED' THEN 1 ELSE 0 END) as failed
        FROM burn_sessions
        WHERE startTime >= :startTime
    """)
    suspend fun getSessionStats(startTime: Long): SessionStats

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(session: BurnSessionEntity)

    @Update
    suspend fun update(session: BurnSessionEntity)

    @Query("UPDATE burn_sessions SET status = :status, endTime = :endTime, duration = :duration WHERE sessionId = :sessionId")
    suspend fun updateSessionStatus(sessionId: String, status: String, endTime: Long?, duration: Long?)

    @Query("DELETE FROM burn_sessions WHERE startTime < :beforeTime")
    suspend function deleteOldSessions(beforeTime: Long)

    @Query("SELECT COUNT(*) FROM burn_sessions")
    suspend fun getSessionCount(): Int
}

data class SessionStats(
    val total: Int,
    val success: Int,
    val failed: Int
)

/**
 * 刻录文件DAO
 */
@Dao
interface BurnFileDao {

    @Query("SELECT * FROM burn_files WHERE sessionId = :sessionId")
    suspend fun getFilesBySession(sessionId: String): List<BurnFileEntity>

    @Insert
    suspend fun insert(file: BurnFileEntity)

    @Insert
    suspend fun insertAll(files: List<BurnFileEntity>)

    @Query("SELECT * FROM burn_files WHERE fileHash = :hash LIMIT 1")
    suspend fun findFileByHash(hash: String): BurnFileEntity?
}

/**
 * 设备信息DAO
 */
@Dao
interface DeviceInfoDao {

    @Query("SELECT * FROM device_info ORDER BY lastUsed DESC")
    fun getAllDevices(): Flow<List<DeviceInfoEntity>>

    @Query("SELECT * FROM device_info WHERE deviceId = :deviceId")
    suspend fun getDeviceById(deviceId: String): DeviceInfoEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(device: DeviceInfoEntity)

    @Query("""
        UPDATE device_info
        SET lastUsed = :timestamp,
            totalBurns = totalBurns + :total,
            successfulBurns = successfulBurns + :success,
            failedBurns = failedBurns + :failed
        WHERE deviceId = :deviceId
    """)
    suspend fun updateBurnStats(
        deviceId: String,
        timestamp: Long,
        total: Int,
        success: Int,
        failed: Int
    )
}
