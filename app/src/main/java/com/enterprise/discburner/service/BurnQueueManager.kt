package com.enterprise.discburner.service

import com.enterprise.discburner.data.AuditEvent
import com.enterprise.discburner.data.EnhancedAuditLogger
import com.enterprise.discburner.usb.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * 刻录任务
 */
data class BurnQueueItem(
    val id: String = UUID.randomUUID().toString(),
    val task: BurnTask,
    val priority: BurnPriority = BurnPriority.NORMAL,
    val createdAt: Long = System.currentTimeMillis(),
    val options: WriteOptions,
    var status: QueueStatus = QueueStatus.PENDING,
    var errorMessage: String? = null,
    var retryCount: Int = 0
)

/**
 * 刻录优先级
 */
enum class BurnPriority(val value: Int) {
    LOW(0),
    NORMAL(1),
    HIGH(2),
    CRITICAL(3)
}

/**
 * 队列状态
 */
enum class QueueStatus {
    PENDING,        // 等待中
    PROCESSING,     // 处理中
    PAUSED,         // 暂停
    COMPLETED,      // 完成
    FAILED,         // 失败
    CANCELLED       // 已取消
}

/**
 * 刻录队列管理器
 *
 * 特性：
 * - 多任务排队
 * - 优先级管理
 * - 自动/手动模式
 * - 失败重试
 * - 批量刻录
 */
class BurnQueueManager(
    private val service: BurnService,
    private val auditLogger: EnhancedAuditLogger,
    private val coroutineScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
) {
    private val TAG = "BurnQueueManager"

    // 任务队列
    private val queue = ConcurrentLinkedQueue<BurnQueueItem>()

    // 当前处理的任务
    private var currentItem: BurnQueueItem? = null
    private var processingJob: Job? = null

    // 配置
    private var autoProcess = false
    private var maxRetries = 2

    // 状态流
    private val _queueState = MutableStateFlow<QueueState>(QueueState.Idle)
    val queueState: StateFlow<QueueState> = _queueState

    private val _queueItems = MutableStateFlow<List<BurnQueueItem>>(emptyList())
    val queueItems: StateFlow<List<BurnQueueItem>> = _queueItems

    // ==================== 任务管理 ====================

    /**
     * 添加任务到队列
     */
    fun enqueue(
        task: BurnTask,
        options: WriteOptions,
        priority: BurnPriority = BurnPriority.NORMAL
    ): String {
        val item = BurnQueueItem(
            task = task,
            options = options,
            priority = priority
        )

        // 按优先级插入队列
        insertByPriority(item)
        updateQueueItems()

        auditLogger.log(
            AuditEvent.BURN_STARTED,
            item.id,
            mapOf(
                "action" to "enqueued",
                "priority" to priority.name,
                "queueSize" to queue.size
            )
        )

        // 如果开启了自动处理且空闲，开始处理
        if (autoProcess && _queueState.value is QueueState.Idle) {
            startProcessing()
        }

        return item.id
    }

    /**
     * 批量添加任务
     */
    fun enqueueBatch(
        tasks: List<Pair<BurnTask, WriteOptions>>,
        priority: BurnPriority = BurnPriority.NORMAL
    ): List<String> {
        return tasks.map { (task, options) ->
            enqueue(task, options, priority)
        }
    }

    /**
     * 移除任务
     */
    fun remove(taskId: String): Boolean {
        val iterator = queue.iterator()
        while (iterator.hasNext()) {
            val item = iterator.next()
            if (item.id == taskId && item.status == QueueStatus.PENDING) {
                iterator.remove()
                item.status = QueueStatus.CANCELLED
                updateQueueItems()

                auditLogger.log(
                    AuditEvent.BURN_FAILED,
                    item.id,
                    mapOf("reason" to "removed_from_queue")
                )
                return true
            }
        }
        return false
    }

    /**
     * 清空队列
     */
    fun clear() {
        queue.removeAll { it.status == QueueStatus.PENDING }
        updateQueueItems()
    }

    // ==================== 处理控制 ====================

    /**
     * 开始处理队列
     */
    fun startProcessing() {
        if (processingJob?.isActive == true) {
            return
        }

        processingJob = coroutineScope.launch {
            _queueState.value = QueueState.Processing

            while (isActive) {
                val item = queue.poll()

                if (item == null) {
                    _queueState.value = QueueState.Idle
                    break
                }

                currentItem = item
                processItem(item)
            }
        }
    }

    /**
     * 暂停处理
     */
    fun pause() {
        processingJob?.cancel()
        currentItem?.let { item ->
            if (item.status == QueueStatus.PROCESSING) {
                item.status = QueueStatus.PAUSED
                // 将任务放回队列头部
                queue.offer(item)
            }
        }
        _queueState.value = QueueState.Paused
        updateQueueItems()
    }

    /**
     * 停止处理
     */
    fun stop() {
        processingJob?.cancel()
        currentItem = null
        _queueState.value = QueueState.Idle
    }

    /**
     * 重试失败的任务
     */
    fun retry(taskId: String): Boolean {
        val item = queueItems.value.find { it.id == taskId && it.status == QueueStatus.FAILED }
            ?: return false

        // 重置状态并重新加入队列
        val newItem = item.copy(
            id = UUID.randomUUID().toString(),
            status = QueueStatus.PENDING,
            errorMessage = null,
            retryCount = item.retryCount + 1
        )

        if (newItem.retryCount <= maxRetries) {
            insertByPriority(newItem)
            updateQueueItems()

            auditLogger.log(
                AuditEvent.BURN_STARTED,
                newItem.id,
                mapOf(
                    "action" to "retry",
                    "originalId" to taskId,
                    "retryCount" to newItem.retryCount
                )
            )

            if (_queueState.value is QueueState.Idle) {
                startProcessing()
            }
            return true
        }

        return false
    }

    // ==================== 配置 ====================

    /**
     * 设置自动处理模式
     */
    fun setAutoProcess(enabled: Boolean) {
        autoProcess = enabled
        if (enabled && _queueState.value is QueueState.Idle && queue.isNotEmpty()) {
            startProcessing()
        }
    }

    /**
     * 设置最大重试次数
     */
    fun setMaxRetries(count: Int) {
        maxRetries = count.coerceIn(0, 5)
    }

    // ==================== 私有方法 ====================

    private suspend fun processItem(item: BurnQueueItem) {
        item.status = QueueStatus.PROCESSING
        updateQueueItems()

        try {
            // 记录会话开始
            val files = when (val task = item.task) {
                is BurnTask.BurnIso -> listOf(task.isoFile)
                is BurnTask.BurnFiles -> task.sourceFiles
            }

            auditLogger.logSessionStart(
                sessionId = item.id,
                volumeLabel = item.options.sessionName,
                writeMode = item.options.writeMode.name,
                closeSession = item.options.closeSession,
                closeDisc = item.options.closeDisc,
                files = files.map {
                    EnhancedAuditLogger.FileInfo(
                        path = it.absolutePath,
                        name = it.name,
                        size = it.length()
                    )
                }
            )

            // 执行刻录
            val result = executeBurn(item)

            // 处理结果
            when (result) {
                is BurnResult.Success -> {
                    item.status = QueueStatus.COMPLETED
                    auditLogger.logSessionComplete(
                        sessionId = item.id,
                        sourceHash = result.sourceHash,
                        verifiedHash = result.verifiedHash,
                        verifyPassed = true,
                        sectorsWritten = result.sectorsWritten,
                        fileName = files.firstOrNull()?.name,
                        fileSize = files.firstOrNull()?.length()
                    )
                }
                is BurnResult.Failure -> {
                    item.status = QueueStatus.FAILED
                    item.errorMessage = result.message
                    auditLogger.logSessionFailed(
                        sessionId = item.id,
                        errorCode = result.code.code,
                        errorMessage = result.message
                    )

                    // 自动重试
                    if (item.retryCount < maxRetries && result.recoverable) {
                        retry(item.id)
                    }
                }
            }

        } catch (e: Exception) {
            item.status = QueueStatus.FAILED
            item.errorMessage = e.message
            auditLogger.logSessionFailed(
                sessionId = item.id,
                errorCode = "EXCEPTION",
                errorMessage = e.message
            )
        } finally {
            currentItem = null
            updateQueueItems()
        }
    }

    private suspend fun executeBurn(item: BurnQueueItem): BurnResult {
        return when (val task = item.task) {
            is BurnTask.BurnIso -> {
                executeWithTimeout { service.burnIso(task.isoFile, item.options) }
            }
            is BurnTask.BurnFiles -> {
                executeWithTimeout { service.burnFiles(task.sourceFiles, task.volumeLabel, item.options) }
            }
        }
    }

    private suspend fun <T> executeWithTimeout(
        timeoutMs: Long = 30 * 60 * 1000, // 30分钟超时
        block: suspend () -> T
    ): T {
        return withTimeoutOrNull(timeoutMs) {
            block()
        } ?: throw TimeoutException("刻录操作超时")
    }

    private fun insertByPriority(item: BurnQueueItem) {
        // 找到合适的插入位置（保持优先级顺序）
        val tempList = queue.toList()
        queue.clear()

        var inserted = false
        for (existing in tempList) {
            if (!inserted && existing.priority.value < item.priority.value) {
                queue.offer(item)
                inserted = true
            }
            queue.offer(existing)
        }

        if (!inserted) {
            queue.offer(item)
        }
    }

    private fun updateQueueItems() {
        _queueItems.value = queue.toList() + listOfNotNull(currentItem)
    }
}

/**
 * 队列状态
 */
sealed class QueueState {
    object Idle : QueueState()
    object Processing : QueueState()
    object Paused : QueueState()
    data class Error(val message: String) : QueueState()
}

/**
 * 超时异常
 */
class TimeoutException(message: String) : Exception(message)
