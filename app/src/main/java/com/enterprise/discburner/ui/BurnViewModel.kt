package com.enterprise.discburner.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.enterprise.discburner.data.BurnResult
import com.enterprise.discburner.usb.DiscAnalysisResult
import com.enterprise.discburner.usb.WriteMode
import com.enterprise.discburner.usb.WriteOptions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * 刻录界面状态
 */
data class BurnUiState(
    // 设备状态
    val isConnected: Boolean = false,
    val deviceName: String? = null,
    val selectedDeviceModel: String? = null,  // 选择的刻录机型号名称

    // 光盘分析
    val discAnalysis: DiscAnalysisResult? = null,
    val isAnalyzing: Boolean = false,

    // 文件选择
    val selectedFiles: List<File> = emptyList(),
    val selectedIsoFile: File? = null,
    val volumeLabel: String = "BACKUP",

    // 刻录选项
    val writeMode: WriteMode = WriteMode.TAO,
    val writeSpeed: Int = 0,  // 0 = 自动/最大速度
    val maxSupportedSpeed: Int = 52,  // 设备支持的最大速度
    val closeSession: Boolean = false,
    val closeDisc: Boolean = false,
    val verifyAfterBurn: Boolean = true,

    // 任务状态
    val isGeneratingIso: Boolean = false,
    val isoProgress: Float = 0f,
    val isBurning: Boolean = false,
    val burnProgress: Float = 0f,
    val stage: String = "",

    // 结果和日志
    val lastResult: BurnResult? = null,
    val logs: List<String> = emptyList(),

    // 操作状态
    val canStartBurn: Boolean = false,
    val showPermissionDialog: Boolean = false,
    val currentTab: Int = 0  // 0=文件刻录, 1=ISO刻录
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
            _logs.value = (_logs.value + newLog).takeLast(100)
            _uiState.value = _uiState.value.copy(logs = _logs.value)
        }
    }

    /**
     * 设置设备连接状态
     */
    fun setDeviceConnected(connected: Boolean, name: String? = null) {
        _uiState.value = _uiState.value.copy(
            isConnected = connected,
            deviceName = name
        )
        updateCanStartBurn()

        if (connected) {
            addLog("✓ 设备已连接: ${name ?: "未知设备"}")
        } else {
            addLog("✗ 设备已断开")
        }
    }

    /**
     * 设置光盘分析结果
     */
    fun setDiscAnalysis(result: DiscAnalysisResult?) {
        _uiState.value = _uiState.value.copy(
            discAnalysis = result,
            isAnalyzing = false
        )

        result?.let { analysis ->
            addLog("光盘分析完成:")
            addLog("  状态: ${getDiscStatusText(analysis.discInfo.discStatus)}")
            addLog("  会话数: ${analysis.sessions.size}")
            addLog("  轨道数: ${analysis.tracks.size}")
            addLog("  可追加: ${if (analysis.canAppend) "是" else "否"}")
            addLog("  剩余空间: ${formatSize(analysis.discInfo.remainingSectors * 2048)}")

            // 根据光盘状态推荐写入模式
            if (analysis.sessions.isNotEmpty() && !analysis.discInfo.isClosed) {
                // 已有数据但未关闭，推荐TAO模式追加
                _uiState.value = _uiState.value.copy(
                    writeMode = WriteMode.TAO,
                    closeSession = false,
                    closeDisc = false
                )
                addLog("  建议: 使用TAO模式追加刻录")
            }
        }

        updateCanStartBurn()
    }

    /**
     * 设置分析中状态
     */
    fun setAnalyzing(analyzing: Boolean) {
        _uiState.value = _uiState.value.copy(isAnalyzing = analyzing)
        if (analyzing) {
            addLog("正在分析光盘...")
        }
    }

    /**
     * 添加选择的文件
     */
    fun addFiles(files: List<File>) {
        val currentFiles = _uiState.value.selectedFiles.toMutableList()
        files.forEach { file ->
            if (currentFiles.none { it.absolutePath == file.absolutePath }) {
                currentFiles.add(file)
            }
        }
        _uiState.value = _uiState.value.copy(selectedFiles = currentFiles)
        addLog("添加了 ${files.size} 个文件，共 ${currentFiles.size} 个")
        updateCanStartBurn()
    }

    /**
     * 移除文件
     */
    fun removeFile(file: File) {
        val currentFiles = _uiState.value.selectedFiles.filter { it != file }
        _uiState.value = _uiState.value.copy(selectedFiles = currentFiles)
        addLog("移除了: ${file.name}")
        updateCanStartBurn()
    }

    /**
     * 选择ISO文件
     */
    fun selectIsoFile(file: File) {
        _uiState.value = _uiState.value.copy(
            selectedIsoFile = file,
            currentTab = 1
        )
        addLog("选择ISO: ${file.name} (${formatSize(file.length())})")
        updateCanStartBurn()
    }

    /**
     * 清除ISO选择
     */
    fun clearIsoSelection() {
        _uiState.value = _uiState.value.copy(selectedIsoFile = null)
        updateCanStartBurn()
    }

    /**
     * 设置卷标
     */
    fun setVolumeLabel(label: String) {
        _uiState.value = _uiState.value.copy(volumeLabel = label)
    }

    /**
     * 设置写入选项
     */
    fun setWriteOptions(
        mode: WriteMode? = null,
        closeSession: Boolean? = null,
        closeDisc: Boolean? = null,
        verify: Boolean? = null
    ) {
        _uiState.value = _uiState.value.copy(
            writeMode = mode ?: _uiState.value.writeMode,
            closeSession = closeSession ?: _uiState.value.closeSession,
            closeDisc = closeDisc ?: _uiState.value.closeDisc,
            verifyAfterBurn = verify ?: _uiState.value.verifyAfterBurn
        )

        mode?.let { addLog("写入模式: ${it.description}") }
        closeSession?.let { addLog("关闭会话: ${if (it) "是" else "否"}") }
        closeDisc?.let { addLog("关闭光盘: ${if (it) "是" else "否"}") }
    }

    /**
     * 切换标签页
     */
    fun setCurrentTab(tab: Int) {
        _uiState.value = _uiState.value.copy(currentTab = tab)
        updateCanStartBurn()
    }

    /**
     * 开始ISO生成
     */
    fun startIsoGeneration() {
        _uiState.value = _uiState.value.copy(
            isGeneratingIso = true,
            isoProgress = 0f
        )
        addLog("开始生成ISO...")
    }

    /**
     * 更新ISO生成进度
     */
    fun updateIsoProgress(progress: Float, stage: String) {
        _uiState.value = _uiState.value.copy(
            isoProgress = progress
        )
        if (stage.isNotEmpty()) {
            addLog("ISO生成: $stage")
        }
    }

    /**
     * 开始刻录
     */
    fun startBurning() {
        _uiState.value = _uiState.value.copy(
            isBurning = true,
            burnProgress = 0f,
            stage = "准备中...",
            lastResult = null
        )
        addLog("========== 开始刻录 ==========")
    }

    /**
     * 更新刻录进度
     */
    fun updateBurnProgress(stage: String, progress: Float) {
        _uiState.value = _uiState.value.copy(
            stage = stage,
            burnProgress = progress
        )
    }

    /**
     * 完成刻录
     */
    fun completeBurn(result: BurnResult) {
        _uiState.value = _uiState.value.copy(
            isGeneratingIso = false,
            isBurning = false,
            lastResult = result
        )

        when (result) {
            is BurnResult.Success -> {
                addLog("========== 刻录成功 ==========")
                addLog("会话ID: ${result.sessionId}")
                addLog("用时: ${formatDuration(result.duration)}")
                addLog("扇区数: ${result.sectorsWritten}")
                addLog("源文件SHA256: ${result.sourceHash.take(16)}...")
                addLog("光盘校验SHA256: ${result.verifiedHash.take(16)}...")
                addLog("校验结果: ✓ 通过")

                // 成功后清除选择，但保留追加选项
                _uiState.value = _uiState.value.copy(
                    selectedFiles = emptyList(),
                    selectedIsoFile = null
                )
            }
            is BurnResult.Failure -> {
                addLog("========== 刻录失败 ==========")
                addLog("错误: ${result.message}")
                addLog("错误码: ${result.code.code}")
            }
        }

        updateCanStartBurn()
    }

    /**
     * 设置设备型号
     */
    fun setDeviceModel(modelName: String?) {
        _uiState.value = _uiState.value.copy(selectedDeviceModel = modelName)
        if (modelName != null) {
            addLog("刻录机型号: $modelName")
        }
    }

    /**
     * 设置最大速度
     */
    fun setMaxSpeed(speed: Int) {
        _uiState.value = _uiState.value.copy(maxSupportedSpeed = speed)
        addLog("设备支持最大速度: ${speed}x")
    }

    /**
     * 设置刻录速度
     */
    fun setWriteSpeed(speed: Int) {
        _uiState.value = _uiState.value.copy(writeSpeed = speed)
        val speedText = if (speed == 0) "自动" else "${speed}x"
        addLog("刻录速度: $speedText")
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

    /**
     * 更新可开始刻录状态
     */
    private fun updateCanStartBurn() {
        val state = _uiState.value
        val canStart = state.isConnected
                && state.discAnalysis != null
                && !state.discAnalysis.discInfo.isClosed
                && (state.selectedFiles.isNotEmpty() || state.selectedIsoFile != null)
                && !state.isBurning
                && !state.isGeneratingIso

        _uiState.value = state.copy(canStartBurn = canStart)
    }

    private fun getDiscStatusText(status: Int): String {
        return when (status) {
            0 -> "空白"
            1 -> "已使用（可追加）"
            2 -> "完整（已关闭）"
            else -> "未知"
        }
    }

    private fun formatSize(bytes: Long): String {
        return when {
            bytes >= 1024 * 1024 * 1024 -> "%.2f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
            bytes >= 1024 * 1024 -> "%.2f MB".format(bytes / (1024.0 * 1024.0))
            else -> "%.2f KB".format(bytes / 1024.0)
        }
    }

    private fun formatDuration(ms: Long): String {
        val seconds = ms / 1000
        val minutes = seconds / 60
        val remainingSeconds = seconds % 60
        return "${minutes}分${remainingSeconds}秒"
    }
}
