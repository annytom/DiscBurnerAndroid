package com.enterprise.discburner.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.enterprise.discburner.R
import com.enterprise.discburner.data.AuditLogger
import com.enterprise.discburner.data.BurnResult
import com.enterprise.discburner.usb.BurnStage
import com.enterprise.discburner.usb.DaoDiscBurner
import com.enterprise.discburner.usb.UsbBurnerManager
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
 * 刻录服务
 * 以前台服务形式运行，确保刻录过程不被系统杀死
 */
class BurnService : Service() {

    private val TAG = "BurnService"
    private val NOTIFICATION_ID = 1001
    private val CHANNEL_ID = "disc_burner_channel"

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private lateinit var usbManager: UsbBurnerManager
    private lateinit var auditLogger: AuditLogger

    private var currentBurner: DaoDiscBurner? = null
    private var burnJob: Job? = null

    // 服务状态
    private val _serviceState = MutableStateFlow<ServiceState>(ServiceState.Idle)
    val serviceState: StateFlow<ServiceState> = _serviceState

    // 刻录进度
    private val _burnProgress = MutableStateFlow(0f)
    val burnProgress: StateFlow<Float> = _burnProgress

    // 当前刻录信息
    private val _currentBurnInfo = MutableStateFlow<BurnInfo?>(null)
    val currentBurnInfo: StateFlow<BurnInfo?> = _currentBurnInfo

    inner class LocalBinder : Binder() {
        fun getService(): BurnService = this@BurnService
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "服务创建")

        usbManager = UsbBurnerManager(this)
        auditLogger = AuditLogger(this)

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
     * 准备刻录（连接设备）
     */
    fun prepareBurn(device: UsbDevice): Boolean {
        if (!usbManager.hasPermission(device)) {
            usbManager.requestPermission(device)
            return false
        }

        val connection = usbManager.connectDevice(device) ?: return false

        currentBurner = DaoDiscBurner(
            connection.first,
            connection.second,
            connection.third,
            connection.fourth,
            auditLogger
        )

        return true
    }

    /**
     * 开始刻录
     */
    fun startBurning(isoFile: File) {
        if (currentBurner == null) {
            updateNotification("错误", "刻录机未连接", true)
            return
        }

        burnJob?.cancel()
        burnJob = serviceScope.launch {
            _serviceState.value = ServiceState.Burning
            _currentBurnInfo.value = BurnInfo(
                fileName = isoFile.name,
                fileSize = isoFile.length(),
                startTime = System.currentTimeMillis()
            )

            val result = currentBurner!!.burnIsoDao(isoFile) { stage, progress ->
                _burnProgress.value = progress
                updateNotificationForStage(stage, progress)
            }

            when (result) {
                is BurnResult.Success -> {
                    _serviceState.value = ServiceState.Completed(result)
                    updateNotification(
                        "刻录完成",
                        "${isoFile.name} 已成功刻录并校验",
                        false
                    )
                    playCompletionSound()
                }
                is BurnResult.Failure -> {
                    _serviceState.value = ServiceState.Error(result.message)
                    updateNotification(
                        "刻录失败",
                        result.message,
                        true
                    )
                }
            }
        }
    }

    /**
     * 取消刻录
     */
    fun cancelBurn() {
        burnJob?.cancel()
        burnJob = null
        _serviceState.value = ServiceState.Idle
        _burnProgress.value = 0f
        updateNotification("已取消", "刻录任务已取消", false)
    }

    /**
     * 导出审计日志
     */
    fun exportAuditLogs(): File {
        return auditLogger.exportLogs()
    }

    /**
     * 获取USB管理器
     */
    fun getUsbManager(): UsbBurnerManager = usbManager

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        burnJob?.cancel()
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
            BurnStage.LEAD_IN -> "准备写入" to true
            BurnStage.WRITING_DATA -> "正在刻录 ${(progress * 100).toInt()}%" to false
            BurnStage.LEAD_OUT -> "完成写入" to true
            BurnStage.FINALIZING -> "关闭光盘" to true
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
        // 震动提示
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
 * 服务状态
 */
sealed class ServiceState {
    object Idle : ServiceState()
    object Burning : ServiceState()
    data class Completed(val result: BurnResult.Success) : ServiceState()
    data class Error(val message: String) : ServiceState()
}

/**
 * 刻录信息
 */
data class BurnInfo(
    val fileName: String,
    val fileSize: Long,
    val startTime: Long
) {
    fun getElapsedTime(): Long = System.currentTimeMillis() - startTime
}
