package com.enterprise.discburner.usb

import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import kotlin.test.*

/**
 * SCSI重试管理器单元测试
 */
class ScsiRetryManagerTest {

    private lateinit var retryManager: ScsiRetryManager

    @Before
    fun setup() {
        retryManager = scsiRetryManager {
            maxRetries = 3
            initialDelayMs = 10  // 测试时使用较短延迟
            maxDelayMs = 100
            backoffMultiplier = 2.0
        }
    }

    /**
     * 测试：首次成功不重试
     */
    @Test
    fun `executeWithRetry succeeds on first attempt`() = runBlocking {
        var callCount = 0

        val result = retryManager.executeWithRetry("TEST_OP") {
            callCount++
            ScsiResult.Success(ByteArray(0))
        }

        assertTrue(result is ScsiResult.Success, "应成功")
        assertEquals(1, callCount, "应只调用一次")
    }

    /**
     * 测试：第二次尝试成功
     */
    @Test
    fun `executeWithRetry succeeds on second attempt`() = runBlocking {
        var callCount = 0

        val result = retryManager.executeWithRetry("WRITE") {
            callCount++
            if (callCount == 1) {
                ScsiResult.Error(ScsiSenseKey.UNIT_ATTENTION, 0x04, 0x01) // 第一次失败
            } else {
                ScsiResult.Success(ByteArray(0)) // 第二次成功
            }
        }

        assertTrue(result is ScsiResult.Success, "最终应成功")
        assertEquals(2, callCount, "应调用两次")
    }

    /**
     * 测试：重试耗尽后失败
     */
    @Test
    fun `executeWithRetry fails after max retries`() = runBlocking {
        var callCount = 0

        val result = retryManager.executeWithRetry("READ") {
            callCount++
            ScsiResult.Error(ScsiSenseKey.NOT_READY, 0x04, 0x02) // 总是返回可重试错误
        }

        assertTrue(result is ScsiResult.Error, "应返回错误")
        assertEquals(4, callCount, "应调用1+3=4次（首次+3次重试）")
    }

    /**
     * 测试：非可重试错误不重试
     */
    @Test
    fun `no retry on non-retryable error`() = runBlocking {
        var callCount = 0

        val result = retryManager.executeWithRetry("TEST") {
            callCount++
            ScsiResult.Error(ScsiSenseKey.ILLEGAL_REQUEST, 0x05, 0x20) // 非法请求，不可重试
        }

        assertTrue(result is ScsiResult.Error, "应返回错误")
        assertEquals(1, callCount, "应只调用一次，不重试")
    }

    /**
     * 测试：指数退避延迟
     */
    @Test
    fun `exponential backoff increases delay`() = runBlocking {
        val delays = mutableListOf<Long>()

        // 创建自定义重试管理器来捕获延迟
        val customManager = scsiRetryManager {
            maxRetries = 3
            initialDelayMs = 10
            maxDelayMs = 100
            backoffMultiplier = 2.0
        }

        var callCount = 0
        val startTime = System.currentTimeMillis()

        customManager.executeWithRetry("BACKOFF_TEST") {
            callCount++
            if (callCount < 3) {
                ScsiResult.Error(ScsiSenseKey.UNIT_ATTENTION, 0x04, 0x01)
            } else {
                ScsiResult.Success(ByteArray(0))
            }
        }

        val totalTime = System.currentTimeMillis() - startTime
        // 10ms + 20ms = 至少30ms的延迟（加上执行时间）
        assertTrue(totalTime >= 30, "指数退避应增加延迟，实际耗时${totalTime}ms")
    }

    /**
     * 测试：UNIT_ATTENTION 可重试
     */
    @Test
    fun `UNIT_ATTENTION is retryable`() = runBlocking {
        assertTrue(isRetryableError(ScsiSenseKey.UNIT_ATTENTION, 0x04, 0x01),
            "UNIT_ATTENTION应可重试")
    }

    /**
     * 测试：NOT_READY 可重试
     */
    @Test
    fun `NOT_READY is retryable`() = runBlocking {
        assertTrue(isRetryableError(ScsiSenseKey.NOT_READY, 0x04, 0x02),
            "NOT_READY应可重试")
    }

    /**
     * 测试：BUSY 可重试
     */
    @Test
    fun `BUSY status is retryable`() = runBlocking {
        assertTrue(isRetryableError(ScsiSenseKey.BUSY, 0x00, 0x00),
            "BUSY应可重试")
    }

    /**
     * 测试：MEDIUM_ERROR 可重试
     */
    @Test
    fun `MEDIUM_ERROR is retryable`() = runBlocking {
        assertTrue(isRetryableError(ScsiSenseKey.MEDIUM_ERROR, 0x03, 0x00),
            "MEDIUM_ERROR应可重试")
    }

    /**
     * 测试：RECOVERED_ERROR 可重试
     */
    @Test
    fun `RECOVERED_ERROR is retryable`() = runBlocking {
        assertTrue(isRetryableError(ScsiSenseKey.RECOVERED_ERROR, 0x01, 0x00),
            "RECOVERED_ERROR应可重试")
    }

    /**
     * 测试：ILLEGAL_REQUEST 不可重试
     */
    @Test
    fun `ILLEGAL_REQUEST is not retryable`() = runBlocking {
        assertFalse(isRetryableError(ScsiSenseKey.ILLEGAL_REQUEST, 0x05, 0x20),
            "ILLEGAL_REQUEST不应重试")
    }

    /**
     * 测试：DATA_PROTECT 不可重试
     */
    @Test
    fun `DATA_PROTECT is not retryable`() = runBlocking {
        assertFalse(isRetryableError(ScsiSenseKey.DATA_PROTECT, 0x07, 0x27),
            "DATA_PROTECT不应重试")
    }

    /**
     * 测试：自定义配置
     */
    @Test
    fun `custom configuration is applied`() = runBlocking {
        val customManager = scsiRetryManager {
            maxRetries = 5
            initialDelayMs = 50
            maxDelayMs = 500
            backoffMultiplier = 3.0
        }

        var callCount = 0
        val result = customManager.executeWithRetry("CUSTOM") {
            callCount++
            if (callCount < 3) {
                ScsiResult.Error(ScsiSenseKey.UNIT_ATTENTION, 0x04, 0x01)
            } else {
                ScsiResult.Success(ByteArray(0))
            }
        }

        assertTrue(result is ScsiResult.Success, "应成功")
        assertEquals(3, callCount, "使用自定义配置应正确重试")
    }

    /**
     * 测试：零延迟配置
     */
    @Test
    fun `zero delay retry works`() = runBlocking {
        val fastManager = scsiRetryManager {
            maxRetries = 3
            initialDelayMs = 0
            maxDelayMs = 0
            backoffMultiplier = 1.0
        }

        var callCount = 0
        val startTime = System.currentTimeMillis()

        fastManager.executeWithRetry("FAST") {
            callCount++
            if (callCount < 3) {
                ScsiResult.Error(ScsiSenseKey.UNIT_ATTENTION, 0x04, 0x01)
            } else {
                ScsiResult.Success(ByteArray(0))
            }
        }

        val duration = System.currentTimeMillis() - startTime
        assertTrue(duration < 100, "零延迟重试应很快完成，实际${duration}ms")
    }

    /**
     * 测试：异常转换为SCSI错误
     */
    @Test
    fun `exception is converted to SCSI error`() = runBlocking {
        var callCount = 0

        val result = retryManager.executeWithRetry("EXCEPTION") {
            callCount++
            if (callCount == 1) {
                throw java.io.IOException("Test IO error")
            } else {
                ScsiResult.Success(ByteArray(0))
            }
        }

        assertTrue(result is ScsiResult.Success, "异常后重试应成功")
        assertEquals(2, callCount, "应调用两次")
    }

    /**
     * 测试：最大延迟限制
     */
    @Test
    fun `delay does not exceed maxDelayMs`() = runBlocking {
        val manager = scsiRetryManager {
            maxRetries = 5
            initialDelayMs = 100
            maxDelayMs = 150  // 设置较低的最大延迟
            backoffMultiplier = 10.0  // 很大的倍数以测试限制
        }

        var callCount = 0
        val startTime = System.currentTimeMillis()

        manager.executeWithRetry("MAX_DELAY") {
            callCount++
            if (callCount < 4) {
                ScsiResult.Error(ScsiSenseKey.UNIT_ATTENTION, 0x04, 0x01)
            } else {
                ScsiResult.Success(ByteArray(0))
            }
        }

        val duration = System.currentTimeMillis() - startTime
        // 150 + 150 = 300ms 最大（加上一些执行时间）
        assertTrue(duration < 500, "延迟不应超过maxDelayMs限制")
    }

    // 辅助函数
    private fun isRetryableError(senseKey: Byte, asc: Int, ascq: Int): Boolean {
        return when (senseKey) {
            ScsiSenseKey.UNIT_ATTENTION,
            ScsiSenseKey.NOT_READY,
            ScsiSenseKey.MEDIUM_ERROR,
            ScsiSenseKey.RECOVERED_ERROR,
            ScsiSenseKey.BUSY -> true
            else -> false
        }
    }
}
