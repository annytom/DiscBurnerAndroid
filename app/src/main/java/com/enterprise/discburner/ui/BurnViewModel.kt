package com.enterprise.discburner.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.enterprise.discburner.data.BurnResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * 刻录界面状态
 */
data class BurnUiState(
    val isConnected: Boolean = false,
    val deviceName: String? = null,
    val selectedFile: File? = null,
    val isBurning: Boolean = false,
    val progress: Float = 0f,
    val stage: String = "",
    val logs: List<String> = emptyList(),
    val lastResult: BurnResult? = null,
    val canStartBurn: Boolean = false,
    val showPermissionDialog: Boolean = false,
    val availableIsos: List<File> = emptyList(),
    val isLoadingFiles: Boolean = false
)

/**
 * 刻录界面ViewModel
 */
class BurnViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(BurnUiState())
    val uiState: StateFlow<BurnUiState> = _uiState

    private val _logs = MutableStateFlow<List<String>>(emptyList())

    /**
     * 添加日志
     */
    fun addLog(message: String) {
        viewModelScope.launch {
            val timestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
                .format(java.util.Date())
            val newLog = "[$timestamp] $message"
            _logs.value = (_logs.value + newLog).takeLast(100) // 保留最近100条
            _uiState.value = _uiState.value.copy(logs = _logs.value)
        }
    }

    /**
     * 设置设备连接状态
     */
    fun setDeviceConnected(connected: Boolean, name: String? = null) {
        _uiState.value = _uiState.value.copy(
            isConnected = connected,
            deviceName = name,
            canStartBurn = connected && _uiState.value.selectedFile != null && !_uiState.value.isBurning
        )
        if (connected) {
            addLog("设备已连接: ${name ?: "未知设备"}")
        } else {
            addLog("设备已断开")
        }
    }

    /**
     * 选择ISO文件
     */
    fun selectFile(file: File) {
        _uiState.value = _uiState.value.copy(
            selectedFile = file,
            canStartBurn = _uiState.value.isConnected && !_uiState.value.isBurning
        )
        addLog("选择文件: ${file.name} (${formatFileSize(file.length())})")
    }

    /**
     * 清除选择
     */
    fun clearSelection() {
        _uiState.value = _uiState.value.copy(
            selectedFile = null,
            canStartBurn = false
        )
    }

    /**
     * 设置可用ISO文件列表
     */
    fun setAvailableIsos(files: List<File>) {
        _uiState.value = _uiState.value.copy(
            availableIsos = files,
            isLoadingFiles = false
        )
        addLog("扫描到 ${files.size} 个ISO文件")
    }

    /**
     * 设置加载状态
     */
    fun setLoadingFiles(loading: Boolean) {
        _uiState.value = _uiState.value.copy(isLoadingFiles = loading)
    }

    /**
     * 开始刻录
     */
    fun startBurn() {
        _uiState.value = _uiState.value.copy(
            isBurning = true,
            progress: 0f,
            stage: "准备中...",
            canStartBurn = false,
            lastResult = null
        )
        addLog("开始刻录任务")
    }

    /**
     * 更新进度
     */
    fun updateProgress(stage: String, progress: Float) {
        _uiState.value = _uiState.value.copy(
            stage = stage,
            progress = progress
        )
    }

    /**
     * 完成刻录
     */
    fun completeBurn(result: BurnResult) {
        _uiState.value = _uiState.value.copy(
            isBurning = false,
            lastResult = result,
            canStartBurn = _uiState.value.isConnected && _uiState.value.selectedFile != null
        )

        when (result) {
            is BurnResult.Success -> {
                addLog("✓ 刻录完成: ${result.fileName}")
                addLog("  用时: ${formatDuration(result.duration)}")
                addLog("  扇区数: ${result.sectorsWritten}")
                addLog("  源文件SHA256: ${result.sourceHash.take(16)}...")
                addLog("  光盘校验SHA256: ${result.verifiedHash.take(16)}...")
                addLog("  校验结果: ✓ 通过")
            }
            is BurnResult.Failure -> {
                addLog("✗ 刻录失败: ${result.message}")
                addLog("  错误码: ${result.code.code}")
            }
        }
    }

    /**
     * 显示权限对话框
     */
    fun showPermissionDialog(show: Boolean) {
        _uiState.value = _uiState.value.copy(showPermissionDialog = show)
    }

    /**
     * 清除日志
     */
    fun clearLogs() {
        _logs.value = emptyList()
        _uiState.value = _uiState.value.copy(logs = emptyList())
    }

    private fun formatFileSize(bytes: Long): String {
        return when {
            bytes >= 1024 * 1024 * 1024 -> "%.2f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
            bytes >= 1024 * 1024 -> "%.2f MB".format(bytes / (1024.0 * 1024.0))
            bytes >= 1024 -> "%.2f KB".format(bytes / 1024.0)
            else -> "$bytes B"
        }
    }

    private fun formatDuration(ms: Long): String {
        val seconds = ms / 1000
        val minutes = seconds / 60
        val remainingSeconds = seconds % 60
        return "${minutes}分${remainingSeconds}秒"
    }
}
