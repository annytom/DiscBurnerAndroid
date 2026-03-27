package com.enterprise.discburner.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.hardware.usb.UsbDevice
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.enterprise.discburner.R
import com.enterprise.discburner.data.AuditEvent
import com.enterprise.discburner.data.EnhancedAuditLogger
import com.enterprise.discburner.data.BurnResult
import com.enterprise.discburner.filesystem.Iso9660Generator
import com.enterprise.discburner.usb.BurnStage
import com.enterprise.discburner.usb.DiscAnalysisResult
import com.enterprise.discburner.usb.WriteMode
import com.enterprise.discburner.usb.WriteOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 刻录任务类型
 */
sealed class BurnTask {
    data class BurnIso(
        val isoFile: File,
        val options: WriteOptions
    ) : BurnTask()

    data class BurnFiles(
        val sourceFiles: List<File>,
        val volumeLabel: String,
        val options: WriteOptions
    ) : BurnTask()
}

/**
 * 服务状态
 */
sealed class ServiceState {
    object Idle : ServiceState()
    object AnalyzingDisc : ServiceState()
    data class AnalysisComplete(val result: DiscAnalysisResult) : ServiceState()
    object GeneratingIso : ServiceState()
    object Burning : ServiceState()
    data class Completed(val result: BurnResult.Success) : ServiceState()
    data class Error(val message: String) : ServiceState()
}

/**
 * 刻录服务 V2
 * 支持：
 * 1. 直接刻录ISO文件
 * 2. 将任意文件打包成ISO后刻录
 * 3. 多会话（补刻）支持
 * 4. 刻录队列管理
 * 5. 增强审计日志
 */
class BurnService : Service() {

    private val TAG = "BurnService"
    private val NOTIFICATION_ID = 1001
    private val CHANNEL_ID = "disc_burner_channel"

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private lateinit var usbManager: UsbBurnerManager
    private lateinit var auditLogger: EnhancedAuditLogger
    private lateinit var isoGenerator: Iso9660Generator
    private var burnQueueManager: BurnQueueManager? = null

    private var currentBurner: MultiSessionDiscBurner? = null
    private var burnJob: Job? = null
    private var isoGenerationJob: Job? = null

    // 服务状态
    private val _serviceState = MutableStateFlow<ServiceState>(ServiceState.Idle)
    val serviceState: StateFlow<ServiceState> = _serviceState

    // 刻录进度
    private val _burnProgress = MutableStateFlow(0f)
    val burnProgress: StateFlow<Float> = _burnProgress

    // ISO生成进度
    private val _isoProgress = MutableStateFlow(0f)
    val isoProgress: StateFlow<Float> = _isoProgress

    // 当前任务信息
    private val _currentTaskInfo = MutableStateFlow<TaskInfo?>(null)
    val currentTaskInfo: StateFlow<TaskInfo?> = _currentTaskInfo

    inner class LocalBinder : Binder() {
        fun getService(): BurnService = this@BurnService
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "服务创建")

        usbManager = UsbBurnerManager(this)
        auditLogger = EnhancedAuditLogger(this)
        isoGenerator = Iso9660Generator()
        burnQueueManager = BurnQueueManager(this, auditLogger)

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createIdleNotification())
    }

    override fun onBind(intent: Intent): IBinder {
        return binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_NOT_STICKY
    }

    /**
     * 获取队列管理器
     */
    fun getQueueManager(): BurnQueueManager? = burnQueueManager

    /**
     * 分析光盘状态
     */
    fun analyzeDisc() {
        val burner = currentBurner ?: run {
            _serviceState.value = ServiceState.Error("刻录机未连接")
            return
        }

        serviceScope.launch {
            _serviceState.value = ServiceState.AnalyzingDisc
            updateNotification("分析光盘", "正在读取光盘信息...", false)

            val result = burner.analyzeDisc()

            result.onSuccess { analysis ->
                _serviceState.value = ServiceState.AnalysisComplete(analysis)
                updateNotification(
                    "分析完成",
                    "发现 ${analysis.sessions.size} 个会话，${analysis.tracks.size} 个轨道",
                    false
                )
            }.onFailure { error ->
                _serviceState.value = ServiceState.Error("分析失败: ${error.message}")
                updateNotification("分析失败", error.message ?: "未知错误", true)
            }
        }
    }

    /**
     * 准备刻录（连接设备）
     */
    fun prepareBurn(device: UsbDevice): Boolean {
        if (!usbManager.hasPermission(device)) {
            usbManager.requestPermission(device)
            return false
        }

        val connection = usbManager.connectDevice(device) ?: return false

        currentBurner = MultiSessionDiscBurner(
            connection.first,
            connection.second,
            connection.third,
            connection.fourth,
            auditLogger
        )

        return true
    }

    /**
     * 添加任务到队列（新版本）
     */
    fun enqueueTask(task: BurnTask, options: WriteOptions, priority: BurnPriority = BurnPriority.NORMAL): String {
        val queueManager = burnQueueManager ?: run {
            throw IllegalStateException("队列管理器未初始化")
        }
        return queueManager.enqueue(task, options, priority)
    }

    /**
     * 开始刻录任务（直接执行，绕过队列）
     */
    fun startBurnTask(task: BurnTask) {
        when (task) {
            is BurnTask.BurnIso -> {
                startBurningIso(task.isoFile, task.options)
            }
            is BurnTask.BurnFiles -> {
                startBurningFiles(task.sourceFiles, task.volumeLabel, task.options)
            }
        }
    }

    /**
     * 直接刻录ISO文件
     */
    private fun startBurningIso(isoFile: File, options: WriteOptions) {
        val burner = currentBurner ?: run {
            updateNotification("错误", "刻录机未连接", true)
            return
        }

        burnJob?.cancel()
        burnJob = serviceScope.launch {
            _serviceState.value = ServiceState.Burning
            _currentTaskInfo.value = TaskInfo(
                type = "ISO刻录",
                fileName = isoFile.name,
                fileSize = isoFile.length(),
                startTime = System.currentTimeMillis()
            )

            val result = burner.burnIso(isoFile, options) { stage, progress ->
                _burnProgress.value = progress
                updateNotificationForStage(stage, progress)
            }

            handleBurnResult(result)
        }
    }

    /**
     * 刻录任意文件（先生成ISO）
     */
    private fun startBurningFiles(
        sourceFiles: List<File>,
        volumeLabel: String,
        options: WriteOptions
    ) {
        val burner = currentBurner ?: run {
            updateNotification("错误", "刻录机未连接", true)
            return
        }

        burnJob?.cancel()
        burnJob = serviceScope.launch {
            _serviceState.value = ServiceState.GeneratingIso
            _currentTaskInfo.value = TaskInfo(
                type = "文件刻录",
                fileName = "$volumeLabel.iso",
                fileSize = sourceFiles.sumOf { it.length() },
                startTime = System.currentTimeMillis()
            )

            // 1. 生成ISO文件
            val cacheDir = File(cacheDir, "iso_cache")
            cacheDir.mkdirs()
            val isoFile = File(cacheDir, "${volumeLabel}_${System.currentTimeMillis()}.iso")

            val isoResult = withContext(Dispatchers.IO) {
                isoGenerator.generateIso(
                    sourceFiles = sourceFiles,
                    outputFile = isoFile,
                    volumeLabel = volumeLabel,
                    callback = { progress, stage ->
                        _isoProgress.value = progress
                        updateNotification("生成ISO", stage, false)
                    }
                )
            }

            isoResult.onSuccess { isoSize ->
                // 2. 刻录生成的ISO
                _serviceState.value = ServiceState.Burning
                _currentTaskInfo.value = _currentTaskInfo.value?.copy(
                    fileSize = isoSize
                )

                val burnResult = burner.burnIso(isoFile, options) { stage, progress ->
                    _burnProgress.value = progress
                    updateNotificationForStage(stage, progress)
                }

                handleBurnResult(burnResult)

                // 3. 清理临时ISO文件
                isoFile.delete()

            }.onFailure { error ->
                _serviceState.value = ServiceState.Error("ISO生成失败: ${error.message}")
                updateNotification("错误", "ISO生成失败: ${error.message}", true)
            }
        }
    }

    private fun handleBurnResult(result: BurnResult) {
        when (result) {
            is BurnResult.Success -> {
                _serviceState.value = ServiceState.Completed(result)
                updateNotification("刻录完成", "校验通过，数据完整性确认", false)
                playCompletionSound()
            }
            is BurnResult.Failure -> {
                _serviceState.value = ServiceState.Error(result.message)
                updateNotification("刻录失败", result.message, true)
            }
        }
    }

    /**
     * 取消任务
     */
    fun cancelTask() {
        burnJob?.cancel()
        isoGenerationJob?.cancel()
        burnJob = null
        isoGenerationJob = null
        _serviceState.value = ServiceState.Idle
        _burnProgress.value = 0f
        _isoProgress.value = 0f
        updateNotification("已取消", "任务已取消", false)
    }

    /**
     * 导出审计日志
     */
    fun exportAuditLogs(): File {
        return auditLogger.exportLogs()
    }

    /**
     * 获取审计日志Flow
     */
    fun getAuditLogsFlow() = auditLogger.getAllLogsFlow()

    /**
     * 获取刻录会话Flow
     */
    fun getBurnSessionsFlow() = auditLogger.getAllSessionsFlow()

    /**
     * 获取USB管理器
     */
    fun getUsbManager(): UsbBurnerManager = usbManager

    /**
     * 获取当前刻录器
     */
    fun getBurner(): MultiSessionDiscBurner? = currentBurner

    /**
     * 获取审计日志器
     */
    fun getAuditLogger(): EnhancedAuditLogger = auditLogger

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        burnJob?.cancel()
        isoGenerationJob?.cancel()
        currentBurner?.close()
        Log.i(TAG, "服务销毁")
    }

    // ===== 通知管理 =====

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "光盘刻录服务",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "显示刻录进度和状态"
                setShowBadge(false)
            }

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createIdleNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("光盘刻录服务")
            .setContentText("准备就绪")
            .setSmallIcon(R.drawable.ic_disc)
            .setOngoing(true)
            .setProgress(0, 0, false)
            .build()
    }

    private fun updateNotification(title: String, content: String, isError: Boolean) {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(if (isError) R.drawable.ic_error else R.drawable.ic_disc)
            .setOngoing(_serviceState.value is ServiceState.Burning)
            .build()

        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun updateNotificationForStage(stage: BurnStage, progress: Float) {
        val (title, indeterminate) = when (stage) {
            BurnStage.DEVICE_PREP -> "准备设备" to true
            BurnStage.DISC_CHECK -> "检查光盘" to true
            BurnStage.SESSION_ANALYSIS -> "分析现有数据" to true
            BurnStage.LEAD_IN -> "准备写入" to true
            BurnStage.WRITING_DATA -> "正在刻录 ${(progress * 100).toInt()}%" to false
            BurnStage.LEAD_OUT -> "完成写入" to true
            BurnStage.FINALIZING -> "关闭会话" to true
            BurnStage.VERIFYING -> "校验数据 ${((progress - 0.55f) / 0.45f * 100).toInt()}%" to false
            else -> "处理中" to true
        }

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(stage.description)
            .setSmallIcon(R.drawable.ic_disc)
            .setOngoing(true)
            .setProgress(100, (progress * 100).toInt(), indeterminate)
            .build()

        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun playCompletionSound() {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vibratorManager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(500, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(500)
        }
    }
}

/**
 * 任务信息
 */
data class TaskInfo(
    val type: String,
    val fileName: String,
    val fileSize: Long,
    val startTime: Long
) {
    fun getElapsedTime(): Long = System.currentTimeMillis() - startTime
}
