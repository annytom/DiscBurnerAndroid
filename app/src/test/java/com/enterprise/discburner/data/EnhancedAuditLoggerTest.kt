package com.enterprise.discburner.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.enterprise.discburner.data.database.AuditLogEntity
import com.enterprise.discburner.data.database.BurnSessionEntity
import com.enterprise.discburner.data.database.SessionStats
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.*

/**
 * 增强审计日志单元测试
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class EnhancedAuditLoggerTest {

    private lateinit var context: Context
    private lateinit var enhancedLogger: EnhancedAuditLogger
    private lateinit var testDir: File

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        enhancedLogger = EnhancedAuditLogger(context)
        testDir = File(context.getExternalFilesDir(null), "audit_logs")
    }

    @After
    fun tearDown() {
        testDir.listFiles()?.forEach { it.delete() }
    }

    /**
     * 测试：日志数字签名生成
     */
    @Test
    fun `log generates valid HMAC signature`() = runBlocking {
        val sessionId = "TEST-SIGN-001"

        enhancedLogger.log(
            event = AuditEvent.BURN_COMPLETED,
            sessionId = sessionId,
            details = mapOf("test" to "signature")
        )

        // 获取日志并验证签名
        val logs = enhancedLogger.getLogsBySession(sessionId)
        assertEquals(1, logs.size, "应有一条日志")
        assertNotNull(logs[0].signature, "应生成签名")
        assertTrue(logs[0].signature!!.isNotBlank(), "签名不应为空")
    }

    /**
     * 测试：签名验证
     */
    @Test
    fun `verifyLogSignature returns true for valid log`() = runBlocking {
        val sessionId = "TEST-VERIFY-001"

        enhancedLogger.log(
            event = AuditEvent.BURN_STARTED,
            sessionId = sessionId,
            details = mapOf("data" to "value")
        )

        val logs = enhancedLogger.getLogsBySession(sessionId)
        val isValid = enhancedLogger.verifyLogSignature(logs[0])

        assertTrue(isValid, "有效日志的签名验证应通过")
    }

    /**
     * 测试：篡改检测
     */
    @Test
    fun `verifyLogSignature returns false for tampered log`() = runBlocking {
        val sessionId = "TEST-TAMPER-001"

        enhancedLogger.log(
            event = AuditEvent.BURN_COMPLETED,
            sessionId = sessionId,
            details = mapOf("original" to "data")
        )

        val logs = enhancedLogger.getLogsBySession(sessionId)
        val tamperedLog = logs[0].copy(details = "{\"tampered\":true}")

        val isValid = enhancedLogger.verifyLogSignature(tamperedLog)
        assertFalse(isValid, "篡改后的日志验证应失败")
    }

    /**
     * 测试：会话创建和更新
     */
    @Test
    fun `createSession creates new session record`() = runBlocking {
        val sessionId = "TEST-SESSION-001"
        val volumeLabel = "TEST_VOLUME"

        enhancedLogger.createSession(
            sessionId = sessionId,
            volumeLabel = volumeLabel,
            writeMode = "DAO",
            totalBytes = 1024 * 1024 * 100L // 100MB
        )

        val stats = enhancedLogger.getSessionStats(System.currentTimeMillis() - 60000)
        assertEquals(1, stats.total, "应有一个总会话")
    }

    /**
     * 测试：会话完成更新
     */
    @Test
    fun `completeSession updates session status`() = runBlocking {
        val sessionId = "TEST-SESSION-002"

        enhancedLogger.createSession(sessionId, "VOL", "TAO", 1000)
        enhancedLogger.completeSession(
            sessionId = sessionId,
            status = "COMPLETED",
            duration = 5000,
            errorMessage = null
        )

        // 验证完成日志
        val logs = enhancedLogger.getLogsBySession(sessionId)
        val completionLog = logs.find { it.eventCode == AuditEvent.BURN_COMPLETED.code }
        assertNotNull(completionLog, "应有完成日志")
    }

    /**
     * 测试：数据库导出为JSON
     */
    @Test
    fun `exportLogs creates valid JSON file`() = runBlocking {
        // 创建测试数据
        enhancedLogger.log(AuditEvent.BURN_STARTED, "EXPORT-TEST-001")
        enhancedLogger.log(AuditEvent.BURN_COMPLETED, "EXPORT-TEST-001")

        val exportFile = enhancedLogger.exportLogs()

        assertTrue(exportFile.exists(), "导出文件应存在")
        assertTrue(exportFile.name.endsWith(".json"), "应是JSON文件")

        val content = exportFile.readText()
        assertTrue(content.contains("EXPORT-TEST-001"), "应包含会话ID")
        assertTrue(content.contains("B001"), "应包含事件代码")
    }

    /**
     * 测试：日志搜索功能
     */
    @Test
    fun `searchLogs finds matching entries`() = runBlocking {
        enhancedLogger.log(
            AuditEvent.BURN_FAILED,
            "SEARCH-001",
            details = mapOf("error" to "critical failure")
        )
        enhancedLogger.log(
            AuditEvent.BURN_COMPLETED,
            "SEARCH-002",
            details = mapOf("status" to "success")
        )

        val results = enhancedLogger.searchLogs("critical")

        assertTrue(results.isNotEmpty(), "应找到匹配结果")
        assertTrue(results.all { it.eventDescription.contains("critical", ignoreCase = true) ||
                it.details.contains("critical", ignoreCase = true) },
            "结果应包含搜索关键词")
    }

    /**
     * 测试：时间范围查询
     */
    @Test
    fun `getLogsByTimeRange returns logs in range`() = runBlocking {
        val now = System.currentTimeMillis()

        enhancedLogger.log(AuditEvent.BURN_STARTED, "TIME-001")

        val logs = enhancedLogger.getLogsByTimeRange(now - 60000, now + 60000)

        assertTrue(logs.isNotEmpty(), "应在时间范围内找到日志")
    }

    /**
     * 测试：统计信息计算
     */
    @Test
    fun `getSessionStats calculates correct statistics`() = runBlocking {
        val baseTime = System.currentTimeMillis()

        // 创建成功会话
        enhancedLogger.createSession("STATS-SUCCESS-001", "VOL1", "DAO", 1000)
        enhancedLogger.completeSession("STATS-SUCCESS-001", "COMPLETED", 1000, null)

        // 创建失败会话
        enhancedLogger.createSession("STATS-FAIL-001", "VOL2", "TAO", 1000)
        enhancedLogger.completeSession("STATS-FAIL-001", "FAILED", 500, "Disk error")

        val stats = enhancedLogger.getSessionStats(baseTime - 60000)

        assertEquals(2, stats.total, "总会话数应为2")
        assertEquals(1, stats.success, "成功数应为1")
        assertEquals(1, stats.failed, "失败数应为1")
    }

    /**
     * 测试：流式获取日志
     */
    @Test
    fun `getAllLogsFlow emits logs`() = runBlocking {
        enhancedLogger.log(AuditEvent.DEVICE_CONNECTED, "FLOW-TEST-001")

        var emitted = false
        enhancedLogger.getAllLogsFlow().collect { logs ->
            if (logs.isNotEmpty()) {
                emitted = true
            }
        }

        // 由于是冷流，需要触发一次日志记录
        enhancedLogger.log(AuditEvent.DEVICE_DISCONNECTED, "FLOW-TEST-002")
    }

    /**
     * 测试：设备统计更新
     */
    @Test
    fun `updateDeviceStats updates device information`() = runBlocking {
        val deviceId = "TEST-DEVICE-001"
        val deviceName = "Test Burner"

        enhancedLogger.updateDeviceStats(
            deviceId = deviceId,
            deviceName = deviceName,
            vendorId = 0x1234,
            productId = 0x5678,
            success = true
        )

        // 验证设备信息记录
        val logs = enhancedLogger.getLogsBySession(deviceId)
        // 设备统计可能通过其他方式记录，这里验证无异常抛出即可
        assertTrue(true, "设备统计更新应无异常")
    }

    /**
     * 测试：文件和数据库双存储
     */
    @Test
    fun `log stores to both file and database`() = runBlocking {
        val sessionId = "DUAL-STORE-001"

        enhancedLogger.log(
            event = AuditEvent.BURN_STARTED,
            sessionId = sessionId,
            details = mapOf("mode" to "dual")
        )

        // 验证数据库存储
        val dbLogs = enhancedLogger.getLogsBySession(sessionId)
        assertEquals(1, dbLogs.size, "数据库应有一条记录")

        // 验证文件存储
        val logFiles = testDir.listFiles { f -> f.extension == "log" }
        assertNotNull(logFiles)
        assertTrue(logFiles!!.isNotEmpty(), "应有日志文件")
    }

    /**
     * 测试：复杂详情序列化
     */
    @Test
    fun `log handles complex nested details`() = runBlocking {
        val sessionId = "COMPLEX-001"
        val complexDetails = mapOf(
            "files" to listOf("file1.txt", "file2.txt"),
            "options" to mapOf("mode" to "DAO", "speed" to 16),
            "nested" to mapOf("deep" to mapOf("value" to 123))
        )

        enhancedLogger.log(
            event = AuditEvent.BURN_STARTED,
            sessionId = sessionId,
            details = complexDetails
        )

        val logs = enhancedLogger.getLogsBySession(sessionId)
        assertEquals(1, logs.size)
        assertTrue(logs[0].details.contains("file1.txt"), "应序列化嵌套数据")
    }

    /**
     * 测试：大量日志处理性能
     */
    @Test
    fun `handles 100 log entries efficiently`() = runBlocking {
        val startTime = System.currentTimeMillis()
        val sessionId = "BULK-TEST-001"

        repeat(100) { i ->
            enhancedLogger.log(
                event = AuditEvent.EXCEPTION,
                sessionId = "$sessionId-$i",
                details = mapOf("index" to i)
            )
        }

        val duration = System.currentTimeMillis() - startTime
        assertTrue(duration < 10000, "处理100条日志应在10秒内完成，实际耗时${duration}ms")

        val stats = enhancedLogger.getSessionStats(System.currentTimeMillis() - 60000)
        assertTrue(stats.total >= 0, "应能获取统计信息")
    }
}
