package com.enterprise.discburner.data

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 审计事件类型
 */
enum class AuditEvent(val code: String, val description: String) {
    BURN_STARTED("B001", "刻录开始"),
    BURN_COMPLETED("B002", "刻录完成"),
    BURN_FAILED("B003", "刻录失败"),
    VERIFY_STARTED("V001", "校验开始"),
    VERIFY_PASSED("V002", "校验通过"),
    VERIFY_FAILED("V003", "校验失败"),
    DEVICE_CONNECTED("D001", "设备连接"),
    DEVICE_DISCONNECTED("D002", "设备断开"),
    EXCEPTION("E001", "异常发生");
}

/**
 * 审计错误代码
 */
enum class AuditCode(val code: String) {
    MODE_SELECT_FAILED("A001"),
    DISC_READ_FAILED("A002"),
    DISC_NOT_BLANK("A003"),
    INSUFFICIENT_SPACE("A004"),
    RESERVE_TRACK_FAILED("A005"),
    WRITE_ERROR("A006"),
    CLOSE_SESSION_FAILED("A007"),
    FINALIZE_FAILED("A008"),
    VERIFY_MISMATCH("A009"),
    EXCEPTION("A999");
}

/**
 * 刻录结果
 */
sealed class BurnResult {
    data class Success(
        val sessionId: String,
        val duration: Long,
        val sourceHash: String,
        val verifiedHash: String,
        val sectorsWritten: Long
    ) : BurnResult()

    data class Failure(
        val message: String,
        val code: AuditCode,
        val recoverable: Boolean = false,
        val sessionId: String? = null
    ) : BurnResult()
}

/**
 * 校验结果
 */
sealed class VerifyResult {
    data class Success(val hash: String, val sectorsRead: Int) : VerifyResult()
    data class Failure(val error: String, val failedAtSector: Int) : VerifyResult()
}

/**
 * 审计日志记录器
 * 用于法规遵从性审计追踪
 */
class AuditLogger(private val context: Context) {

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val logDir: File by lazy {
        File(context.getExternalFilesDir(null), "audit_logs").apply { mkdirs() }
    }

    /**
     * 记录审计事件
     */
    fun log(event: AuditEvent, sessionId: String, details: Map<String, Any?> = emptyMap()) {
        val timestamp = dateFormat.format(Date())
        val entry = buildString {
            appendLine("[$timestamp] $sessionId | ${event.code} | ${event.description}")
            details.forEach { (k, v) -> appendLine("  $k: $v") }
            appendLine("---")
        }

        // 写入日志文件
        val logFile = File(logDir, "burn_audit_${getCurrentDate()}.log")
        logFile.appendText(entry)

        // 输出到Logcat
        Log.i("AuditLog", entry)
    }

    /**
     * 导出日志为ZIP文件
     * @param since 可选的起始时间，null则导出所有
     * @return 导出的ZIP文件
     */
    fun exportLogs(since: Date? = null): File {
        val exportFile = File(context.cacheDir, "audit_export_${System.currentTimeMillis()}.zip")

        ZipOutputStream(FileOutputStream(exportFile)).use { zos ->
            logDir.listFiles { f -> f.extension == "log" }?.forEach { logFile ->
                if (since == null || logFile.lastModified() >= since.time) {
                    zos.putNextEntry(ZipEntry(logFile.name))
                    logFile.inputStream().use { it.copyTo(zos) }
                    zos.closeEntry()
                }
            }
        }

        return exportFile
    }

    /**
     * 获取特定会话的日志
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

    private fun getCurrentDate(): String {
        return SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
    }
}
