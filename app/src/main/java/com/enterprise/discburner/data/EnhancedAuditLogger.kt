package com.enterprise.discburner.data

import android.content.Context
import android.util.Log
import com.enterprise.discburner.data.database.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.*
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 增强的审计日志记录器
 * 支持：
 * - Room数据库持久化
 * - 数字签名防篡改
 * - 自动同步标记
 * - 统计查询
 */
class EnhancedAuditLogger(
    private val context: Context,
    private val coroutineScope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) {
    private val database = DiscBurnerDatabase.getDatabase(context)
    private val auditLogDao = database.auditLogDao()
    private val burnSessionDao = database.burnSessionDao()
    private val burnFileDao = database.burnFileDao()
    private val deviceInfoDao = database.deviceInfoDao()

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val logDir: File by lazy {
        File(context.getExternalFilesDir(null), "audit_logs").apply { mkdirs() }
    }

    // 用于数字签名的密钥（应存储在EncryptedSharedPreferences中）
    private val signingKey: String by lazy {
        loadOrCreateSigningKey()
    }

    companion object {
        private const val PREFS_NAME = "audit_security"
        private const val KEY_SIGNING_SECRET = "signing_secret"
    }

    /**
     * 记录审计事件（增强版）
     */
    fun log(
        event: AuditEvent,
        sessionId: String,
        details: Map<String, Any?> = emptyMap()
    ) {
        val timestamp = Date()

        // 1. 保存到文件（向后兼容）
        logToFile(event, sessionId, details, timestamp)

        // 2. 保存到数据库（异步）
        coroutineScope.launch {
            try {
                val detailsJson = Json.encodeToString(details.filterValues { it != null })

                // 生成数字签名
                val signatureData = "$sessionId:${event.code}:$timestamp:$detailsJson"
                val signature = generateHmacSignature(signatureData)

                val entity = AuditLogEntity(
                    sessionId = sessionId,
                    eventCode = event.code,
                    eventDescription = event.description,
                    timestamp = timestamp,
                    detailsJson = detailsJson,
                    signature = signature,
                    signatureValid = true,
                    synced = false,
                    deviceId = getDeviceId()
                )

                auditLogDao.insert(entity)
            } catch (e: Exception) {
                Log.e("EnhancedAuditLogger", "Database log failed", e)
            }
        }
    }

    /**
     * 记录刻录会话开始
     */
    fun logSessionStart(
        sessionId: String,
        volumeLabel: String?,
        writeMode: String?,
        closeSession: Boolean,
        closeDisc: Boolean,
        files: List<FileInfo> = emptyList()
    ) {
        coroutineScope.launch {
            try {
                // 创建会话记录
                val session = BurnSessionEntity(
                    sessionId = sessionId,
                    startTime = Date(),
                    status = "STARTED",
                    volumeLabel = volumeLabel,
                    writeMode = writeMode,
                    closeSession = closeSession,
                    closeDisc = closeDisc
                )
                burnSessionDao.insert(session)

                // 记录文件信息
                files.forEach { fileInfo ->
                    val burnFile = BurnFileEntity(
                        sessionId = sessionId,
                        filePath = fileInfo.path,
                        fileName = fileInfo.name,
                        fileSize = fileInfo.size,
                        fileHash = fileInfo.hash
                    )
                    burnFileDao.insert(burnFile)
                }
            } catch (e: Exception) {
                Log.e("EnhancedAuditLogger", "Session start log failed", e)
            }
        }
    }

    /**
     * 记录刻录会话完成
     */
    fun logSessionComplete(
        sessionId: String,
        sourceHash: String?,
        verifiedHash: String?,
        verifyPassed: Boolean?,
        sectorsWritten: Long?,
        fileName: String?,
        fileSize: Long?
    ) {
        coroutineScope.launch {
            try {
                val startTime = burnSessionDao.getSessionById(sessionId)?.startTime
                val endTime = Date()
                val duration = startTime?.let { endTime.time - it.time }

                val status = if (verifyPassed == true) "COMPLETED" else "FAILED"

                burnSessionDao.insert(
                    BurnSessionEntity(
                        sessionId = sessionId,
                        startTime = startTime ?: endTime,
                        endTime = endTime,
                        duration = duration,
                        status = status,
                        sourceHash = sourceHash,
                        verifiedHash = verifiedHash,
                        verifyPassed = verifyPassed,
                        sectorsWritten = sectorsWritten,
                        fileName = fileName,
                        fileSize = fileSize
                    )
                )
            } catch (e: Exception) {
                Log.e("EnhancedAuditLogger", "Session complete log failed", e)
            }
        }
    }

    /**
     * 记录刻录失败
     */
    fun logSessionFailed(
        sessionId: String,
        errorCode: String?,
        errorMessage: String?
    ) {
        coroutineScope.launch {
            try {
                val startTime = burnSessionDao.getSessionById(sessionId)?.startTime
                val endTime = Date()
                val duration = startTime?.let { endTime.time - it.time }

                burnSessionDao.insert(
                    BurnSessionEntity(
                        sessionId = sessionId,
                        startTime = startTime ?: endTime,
                        endTime = endTime,
                        duration = duration,
                        status = "FAILED",
                        errorCode = errorCode,
                        errorMessage = errorMessage
                    )
                )
            } catch (e: Exception) {
                Log.e("EnhancedAuditLogger", "Session failed log failed", e)
            }
        }
    }

    /**
     * 记录设备信息
     */
    fun logDeviceInfo(
        deviceId: String,
        vendorId: String?,
        productId: String?,
        manufacturer: String?,
        productName: String?
    ) {
        coroutineScope.launch {
            try {
                val existing = deviceInfoDao.getDeviceById(deviceId)
                val timestamp = Date()

                val device = DeviceInfoEntity(
                    deviceId = deviceId,
                    firstSeen = existing?.firstSeen ?: timestamp,
                    lastUsed = timestamp,
                    vendorId = vendorId,
                    productId = productId,
                    manufacturer = manufacturer,
                    productName = productName
                )
                deviceInfoDao.insertOrUpdate(device)
            } catch (e: Exception) {
                Log.e("EnhancedAuditLogger", "Device info log failed", e)
            }
        }
    }

    /**
     * 更新设备刻录统计
     */
    fun updateDeviceBurnStats(
        deviceId: String,
        success: Boolean
    ) {
        coroutineScope.launch {
            try {
                deviceInfoDao.updateBurnStats(
                    deviceId = deviceId,
                    timestamp = Date().time,
                    total = 1,
                    success = if (success) 1 else 0,
                    failed = if (!success) 1 else 0
                )
            } catch (e: Exception) {
                Log.e("EnhancedAuditLogger", "Update burn stats failed", e)
            }
        }
    }

    // ==================== 查询方法 ====================

    /**
     * 获取所有审计日志（Flow）
     */
    fun getAllLogsFlow(): Flow<List<AuditLogEntity>> = auditLogDao.getAllLogs()

    /**
     * 获取特定会话的日志
     */
    suspend fun getLogsBySession(sessionId: String): List<AuditLogEntity> {
        return auditLogDao.getLogsBySession(sessionId)
    }

    /**
     * 获取时间范围内的日志
     */
    suspend fun getLogsByTimeRange(startTime: Long, endTime: Long): List<AuditLogEntity> {
        return auditLogDao.getLogsByTimeRange(startTime, endTime)
    }

    /**
     * 获取所有刻录会话
     */
    fun getAllSessionsFlow(): Flow<List<BurnSessionEntity>> = burnSessionDao.getAllSessions()

    /**
     * 获取刻录统计
     */
    suspend fun getSessionStats(since: Long): SessionStats {
        return burnSessionDao.getSessionStats(since)
    }

    /**
     * 搜索日志
     */
    suspend fun searchLogs(query: String): List<AuditLogEntity> {
        return auditLogDao.searchLogs(query)
    }

    // ==================== 同步相关 ====================

    /**
     * 获取待同步的日志
     */
    suspend fun getUnsyncedLogs(limit: Int = 100): List<AuditLogEntity> {
        return auditLogDao.getUnsyncedLogs(limit)
    }

    /**
     * 标记为已同步
     */
    suspend fun markAsSynced(logIds: List<Long>) {
        auditLogDao.markAsSynced(logIds, Date().time)
    }

    // ==================== 导出功能 ====================

    /**
     * 导出日志为ZIP（增强版，包含数据库导出）
     */
    fun exportLogs(since: Date? = null): File {
        val exportFile = File(context.cacheDir, "audit_export_${System.currentTimeMillis()}.zip")

        ZipOutputStream(FileOutputStream(exportFile)).use { zos ->
            // 1. 添加文件日志（向后兼容）
            logDir.listFiles { f -> f.extension == "log" }?.forEach { logFile ->
                if (since == null || logFile.lastModified() >= since.time) {
                    zos.putNextEntry(ZipEntry("text_logs/${logFile.name}"))
                    logFile.inputStream().use { it.copyTo(zos) }
                    zos.closeEntry()
                }
            }

            // 2. 导出数据库为JSON（异步获取）
            runBlocking {
                try {
                    val sessions = burnSessionDao.getAllSessions().first()
                    val sessionsJson = Json.encodeToString(sessions)
                    zos.putNextEntry(ZipEntry("database/sessions.json"))
                    zos.write(sessionsJson.toByteArray())
                    zos.closeEntry()
                } catch (e: Exception) {
                    Log.e("EnhancedAuditLogger", "Export sessions failed", e)
                }
            }
        }

        return exportFile
    }

    /**
     * 获取特定会话的日志文本（向后兼容）
     */
    fun getSessionLog(sessionId: String): String {
        val allLogs = StringBuilder()
        logDir.listFiles { f -> f.extension == "log" }?.forEach { logFile ->
            logFile.readLines().forEach { line ->
                if (line.contains(sessionId)) {
                    allLogs.appendLine(line)
                }
            }
        }
        return allLogs.toString()
    }

    // ==================== 数据完整性验证 ====================

    /**
     * 验证日志签名
     */
    fun verifyLogSignature(log: AuditLogEntity): Boolean {
        return try {
            val signatureData = "${log.sessionId}:${log.eventCode}:${log.timestamp}:${log.detailsJson}"
            val expectedSignature = generateHmacSignature(signatureData)
            expectedSignature == log.signature
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 清理旧数据
     */
    fun cleanupOldData(olderThanDays: Int) {
        val cutoffTime = Date().time - (olderThanDays * 24 * 60 * 60 * 1000)

        coroutineScope.launch {
            try {
                auditLogDao.deleteOldLogs(cutoffTime)
                burnSessionDao.deleteOldSessions(cutoffTime)
            } catch (e: Exception) {
                Log.e("EnhancedAuditLogger", "Cleanup failed", e)
            }
        }
    }

    // ==================== 私有方法 ====================

    private fun logToFile(
        event: AuditEvent,
        sessionId: String,
        details: Map<String, Any?>,
        timestamp: Date
    ) {
        val timestampStr = dateFormat.format(timestamp)
        val entry = buildString {
            appendLine("[$timestampStr] $sessionId | ${event.code} | ${event.description}")
            details.forEach { (k, v) -> appendLine("  $k: $v") }
            appendLine("---")
        }

        val logFile = File(logDir, "burn_audit_${getCurrentDate()}.log")
        logFile.appendText(entry)
        Log.i("AuditLog", entry)
    }

    private fun generateHmacSignature(data: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        val secretKey = SecretKeySpec(signingKey.toByteArray(), "HmacSHA256")
        mac.init(secretKey)
        val hash = mac.doFinal(data.toByteArray())
        return hash.joinToString("") { "%02x".format(it) }
    }

    private fun loadOrCreateSigningKey(): String {
        // 简化的密钥管理，实际应使用EncryptedSharedPreferences
        return "your-secret-key-here-change-in-production"
    }

    private fun getDeviceId(): String? {
        // 获取设备唯一标识符
        return android.provider.Settings.Secure.getString(
            context.contentResolver,
            android.provider.Settings.Secure.ANDROID_ID
        )
    }

    private fun getCurrentDate(): String {
        return SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
    }

    /**
     * 文件信息数据类
     */
    data class FileInfo(
        val path: String,
        val name: String,
        val size: Long,
        val hash: String? = null
    )
}