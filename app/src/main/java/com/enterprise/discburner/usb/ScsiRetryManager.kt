package com.enterprise.discburner.usb

import android.util.Log
import kotlinx.coroutines.delay

/**
 * SCSI命令重试管理器
 *
 * 特性：
 * - 可配置的重试策略
 * - 指数退避
 * - 特定错误码识别（可重试 vs 不可重试）
 * - 详细的重试日志
 */
class ScsiRetryManager(
    private val maxRetries: Int = 3,
    private val initialDelayMs: Long = 100,
    private val maxDelayMs: Long = 2000,
    private val backoffMultiplier: Double = 2.0
) {
    private val TAG = "ScsiRetryManager"

    /**
     * 可重试的错误类型
     *
     * 这些错误通常是暂时性的，重试可能成功
     */
    private val retryableErrors = setOf(
        ScsiError.UNIT_ATTENTION,      // 设备状态变化，需要重试
        ScsiError.NOT_READY,           // 设备未就绪
        ScsiError.MEDIUM_ERROR,        // 介质错误（可能是暂时性）
        ScsiError.RECOVERED_ERROR,     // 已恢复的错误
        ScsiError.BUSY,                // 设备忙
        ScsiError.TIMEOUT              // 超时
    )

    /**
     * 执行带重试的SCSI命令
     *
     * @param operation 要执行的操作
     * @param operationName 操作名称（用于日志）
     * @return 操作结果
     */
    suspend fun <T> executeWithRetry(
        operationName: String,
        operation: suspend () -> ScsiResult<T>
    ): ScsiResult<T> {
        var currentDelay = initialDelayMs
        var lastError: ScsiError? = null

        for (attempt in 0..maxRetries) {
            // 执行操作
            val result = operation()

            when (result) {
                is ScsiResult.Success -> {
                    if (attempt > 0) {
                        Log.i(TAG, "$operationName 在${attempt + 1}次尝试后成功")
                    }
                    return result
                }
                is ScsiResult.Failure -> {
                    lastError = result.error

                    // 检查是否可重试
                    if (attempt < maxRetries && isRetryable(result.error)) {
                        Log.w(TAG, "$operationName 失败 (尝试 ${attempt + 1}/$maxRetries): ${result.error}，${currentDelay}ms后重试...")
                        delay(currentDelay)

                        // 指数退避
                        currentDelay = (currentDelay * backoffMultiplier).toLong()
                            .coerceAtMost(maxDelayMs)
                    } else {
                        // 不可重试或已达到最大重试次数
                        if (attempt >= maxRetries) {
                            Log.e(TAG, "$operationName 在${maxRetries + 1}次尝试后最终失败: ${result.error}")
                        } else {
                            Log.e(TAG, "$operationName 遇到不可重试错误: ${result.error}")
                        }
                        return result
                    }
                }
            }
        }

        // 理论上不会到达这里
        return ScsiResult.Failure(lastError ?: ScsiError.UNKNOWN)
    }

    /**
     * 判断错误是否可重试
     */
    private fun isRetryable(error: ScsiError): Boolean {
        return error in retryableErrors
    }

    /**
     * 快速执行（无重试）
     */
    suspend fun <T> executeOnce(
        operation: suspend () -> ScsiResult<T>
    ): ScsiResult<T> {
        return operation()
    }
}

/**
 * SCSI操作结果
 */
sealed class ScsiResult<out T> {
    data class Success<T>(val data: T) : ScsiResult<T>()
    data class Failure(val error: ScsiError, val senseData: ByteArray? = null) : ScsiResult<Nothing>()
}

/**
 * SCSI错误类型
 */
enum class ScsiError(val senseKey: Int, val asc: Int? = null, val ascq: Int? = null) {
    // 成功
    NONE(0x00),

    // 可重试的错误
    RECOVERED_ERROR(0x01),          // 已恢复的错误
    NOT_READY(0x02),                // 未就绪
    MEDIUM_ERROR(0x03),             // 介质错误
    HARDWARE_ERROR(0x04),           // 硬件错误
    UNIT_ATTENTION(0x06),           // 设备状态变化
    BUSY(0x09),                     // 设备忙
    TIMEOUT(-1),                    // 超时（非SCSI标准）

    // 不可重试的错误
    ILLEGAL_REQUEST(0x05),          // 非法请求
    DATA_PROTECT(0x07),             // 写保护
    BLANK_CHECK(0x08),              // 空白检查
    COPY_ABORTED(0x0A),             // 复制中止
    ABORTED_COMMAND(0x0B),          // 命令中止
    VOLUME_OVERFLOW(0x0D),          // 卷溢出
    MISCOMPARE(0x0E),               // 数据不匹配

    // 未知错误
    UNKNOWN(-1),
    INVALID_RESPONSE(-2),           // 无效响应
    TRANSFER_ERROR(-3);             // 传输错误

    companion object {
        fun fromSenseData(senseData: ByteArray?): ScsiError {
            if (senseData == null || senseData.size < 13) {
                return UNKNOWN
            }

            val senseKey = senseData[2].toInt() and 0x0F
            val asc = senseData[12].toInt() and 0xFF
            val ascq = if (senseData.size > 13) senseData[13].toInt() and 0xFF else 0

            return values().find { it.senseKey == senseKey } ?: UNKNOWN
        }
    }
}

/**
 * 重试配置构建器
 */
class RetryConfigBuilder {
    var maxRetries: Int = 3
    var initialDelayMs: Long = 100
    var maxDelayMs: Long = 2000
    var backoffMultiplier: Double = 2.0

    fun build(): ScsiRetryManager {
        return ScsiRetryManager(maxRetries, initialDelayMs, maxDelayMs, backoffMultiplier)
    }
}

/**
 * 创建重试管理器的DSL
 */
fun scsiRetryManager(block: RetryConfigBuilder.() -> Unit): ScsiRetryManager {
    return RetryConfigBuilder().apply(block).build()
}
