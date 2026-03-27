package com.enterprise.discburner.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.*
import kotlin.test.*

/**
 * 审计日志单元测试
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class AuditLoggerTest {

    private lateinit var context: Context
    private lateinit var auditLogger: AuditLogger
    private lateinit var testDir: File

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        auditLogger = AuditLogger(context)
        testDir = File(context.getExternalFilesDir(null), "audit_logs")
    }

    @After
    fun tearDown() {
        // 清理测试文件
        testDir.listFiles()?.forEach { it.delete() }
    }

    /**
     * 测试：基本日志记录
     */
    @Test
    fun `log creates audit file`() {
        val sessionId = "TEST-1234567890-0001"

        auditLogger.log(
            event = AuditEvent.BURN_STARTED,
            sessionId = sessionId,
            details = mapOf("test" to "value")
        )

        // 验证日志文件被创建
        val logFiles = testDir.listFiles { f -> f.extension == "log" }
        assertNotNull(logFiles)
        assertTrue(logFiles!!.isNotEmpty(), "应创建日志文件")
    }

    /**
     * 测试：日志内容格式
     */
    @Test
    fun `log contains correct format`() {
        val sessionId = "TEST-1234567890-0002"

        auditLogger.log(
            event = AuditEvent.BURN_COMPLETED,
            sessionId = sessionId,
            details = mapOf("duration" to 5000)
        )

        // 读取日志内容
        val logFiles = testDir.listFiles { f -> f.extension == "log" }
        val content = logFiles!!.first().readText()

        // 验证格式
        assertTrue(content.contains(sessionId), "应包含会话ID")
        assertTrue(content.contains("B002"), "应包含事件代码") // B002 = BURN_COMPLETED
        assertTrue(content.contains("刻录完成"), "应包含事件描述")
        assertTrue(content.contains("duration: 5000"), "应包含详细信息")
    }

    /**
     * 测试：多事件记录
     */
    @Test
    fun `log records multiple events`() {
        val sessionId = "TEST-1234567890-0003"

        auditLogger.log(AuditEvent.BURN_STARTED, sessionId)
        auditLogger.log(AuditEvent.BURN_COMPLETED, sessionId)
        auditLogger.log(AuditEvent.VERIFY_PASSED, sessionId)

        val logFiles = testDir.listFiles { f -> f.extension == "log" }
        val content = logFiles!!.first().readText()

        // 验证所有事件都被记录
        assertEquals(3, content.split("---").size - 1, "应记录3个事件")
    }

    /**
     * 测试：导出日志
     */
    @Test
    fun `exportLogs creates zip file`() {
        // 创建一些测试日志
        auditLogger.log(AuditEvent.BURN_STARTED, "TEST-1")
        auditLogger.log(AuditEvent.BURN_COMPLETED, "TEST-2")

        // 导出
        val exportedFile = auditLogger.exportLogs()

        assertTrue(exportedFile.exists(), "导出文件应存在")
        assertTrue(exportedFile.name.endsWith(".zip"), "应是ZIP文件")
        assertTrue(exportedFile.length() > 0, "导出文件应有内容")
    }

    /**
     * 测试：按日期过滤导出
     */
    @Test
    fun `exportLogs with since date filters correctly`() {
        // 创建旧日志
        val oldDate = Date(System.currentTimeMillis() - 86400000 * 7) // 7天前
        auditLogger.log(AuditEvent.BURN_STARTED, "OLD-SESSION")

        // 导出最近3天的日志
        val recentDate = Date(System.currentTimeMillis() - 86400000 * 3)
        val exportedFile = auditLogger.exportLogs(since = recentDate)

        assertTrue(exportedFile.exists())
    }

    /**
     * 测试：获取特定会话日志
     */
    @Test
    fun `getSessionLog returns only matching entries`() {
        val targetSession = "TARGET-SESSION-1234"
        val otherSession = "OTHER-SESSION-5678"

        auditLogger.log(AuditEvent.BURN_STARTED, targetSession)
        auditLogger.log(AuditEvent.BURN_STARTED, otherSession)
        auditLogger.log(AuditEvent.BURN_COMPLETED, targetSession)

        val sessionLog = auditLogger.getSessionLog(targetSession)

        assertTrue(sessionLog.contains(targetSession), "应包含目标会话")
        assertFalse(sessionLog.contains(otherSession), "不应包含其他会话")
        assertEquals(2, sessionLog.lines().count { it.contains(targetSession) }, "应有2条记录")
    }

    /**
     * 测试：所有审计事件类型
     */
    @Test
    fun `all audit events have correct codes`() {
        val expectedCodes = mapOf(
            AuditEvent.BURN_STARTED to "B001",
            AuditEvent.BURN_COMPLETED to "B002",
            AuditEvent.BURN_FAILED to "B003",
            AuditEvent.VERIFY_STARTED to "V001",
            AuditEvent.VERIFY_PASSED to "V002",
            AuditEvent.VERIFY_FAILED to "V003",
            AuditEvent.DEVICE_CONNECTED to "D001",
            AuditEvent.DEVICE_DISCONNECTED to "D002",
            AuditEvent.EXCEPTION to "E001"
        )

        expectedCodes.forEach { (event, code) ->
            assertEquals(code, event.code, "${event.name}应有正确代码")
        }
    }

    /**
     * 测试：审计错误代码
     */
    @Test
    fun `audit codes have correct values`() {
        assertEquals("A001", AuditCode.MODE_SELECT_FAILED.code)
        assertEquals("A002", AuditCode.DISC_READ_FAILED.code)
        assertEquals("A003", AuditCode.DISC_NOT_BLANK.code)
        assertEquals("A004", AuditCode.INSUFFICIENT_SPACE.code)
        assertEquals("A005", AuditCode.RESERVE_TRACK_FAILED.code)
        assertEquals("A006", AuditCode.WRITE_ERROR.code)
        assertEquals("A007", AuditCode.CLOSE_SESSION_FAILED.code)
        assertEquals("A008", AuditCode.FINALIZE_FAILED.code)
        assertEquals("A009", AuditCode.VERIFY_MISMATCH.code)
        assertEquals("A999", AuditCode.EXCEPTION.code)
    }

    /**
     * 测试：BurnResult Success
     */
    @Test
    fun `BurnResult Success contains all fields`() {
        val result = BurnResult.Success(
            sessionId = "TEST-123",
            duration = 60000,
            sourceHash = "abc123...",
            verifiedHash = "abc123...",
            sectorsWritten = 1000
        )

        assertEquals("TEST-123", result.sessionId)
        assertEquals(60000, result.duration)
        assertEquals("abc123...", result.sourceHash)
        assertEquals("abc123...", result.verifiedHash)
        assertEquals(1000, result.sectorsWritten)
    }

    /**
     * 测试：BurnResult Failure
     */
    @Test
    fun `BurnResult Failure contains error info`() {
        val result = BurnResult.Failure(
            message = "Test error",
            code = AuditCode.WRITE_ERROR,
            recoverable = true,
            sessionId = "TEST-456"
        )

        assertEquals("Test error", result.message)
        assertEquals(AuditCode.WRITE_ERROR, result.code)
        assertTrue(result.recoverable)
        assertEquals("TEST-456", result.sessionId)
    }

    /**
     * 测试：复杂详情记录
     */
    @Test
    fun `log handles complex details`() {
        val sessionId = "TEST-COMPLEX"
        val complexDetails = mapOf(
            "fileName" to "backup.iso",
            "fileSize" to 4718592000L,
            "writeMode" to "DAO",
            "sectorCount" to 2304000,
            "hasError" to false,
            "nullValue" to null
        )

        auditLogger.log(AuditEvent.BURN_STARTED, sessionId, complexDetails)

        val logFiles = testDir.listFiles { f -> f.extension == "log" }
        val content = logFiles!!.first().readText()

        complexDetails.filter { it.value != null }.forEach { (key, value) ->
            assertTrue(content.contains("$key: $value"), "应包含$key: $value")
        }
    }
}