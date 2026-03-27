package com.enterprise.discburner.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.IBinder
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.enterprise.discburner.data.BurnResult
import com.enterprise.discburner.service.BurnService
import com.enterprise.discburner.service.BurnTask
import com.enterprise.discburner.service.ServiceState
import com.enterprise.discburner.data.database.DeviceInfoEntity
import com.enterprise.discburner.ui.screens.DeviceSelectionScreen
import com.enterprise.discburner.usb.BurnerModel
import com.enterprise.discburner.usb.BurnerModelDatabase
import com.enterprise.discburner.usb.WriteOptions
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : ComponentActivity() {

    private var burnService: BurnService? = null
    private var serviceBound = false
    private val viewModel = BurnViewModel()

    private var currentDevice: UsbDevice? = null
    private var selectedBurnerModel: BurnerModel? = null

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as BurnService.LocalBinder
            burnService = binder.getService()
            serviceBound = true
            observeServiceState()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            burnService = null
            serviceBound = false
        }
    }

    private val storagePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.entries.all { it.value }) {
            viewModel.addLog("✓ 存储权限已授予")
        } else {
            viewModel.addLog("✗ 存储权限被拒绝")
        }
    }

    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { handleFileSelection(it) }
    }

    private val multiFilePickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri>? ->
        uris?.let { handleMultipleFileSelection(it) }
    }

    private fun handleFileSelection(uri: Uri) {
        val file = File(uri.path ?: return)
        if (file.extension.lowercase() == "iso") {
            viewModel.selectIsoFile(file)
        } else {
            viewModel.addFiles(listOf(file))
        }
    }

    private fun handleMultipleFileSelection(uris: List<Uri>) {
        val files = uris.mapNotNull { uri ->
            uri.path?.let { File(it) }
        }
        viewModel.addFiles(files)
    }

    private val usbReceiver = UsbBurnerManager(this).usbReceiver

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Intent(this, BurnService::class.java).also { intent ->
            startService(intent)
            bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }

        setContent {
            DiscBurnerTheme {
                BurnScreen(
                    viewModel = viewModel,
                    onScanDevice = { scanForDevice() },
                    onAnalyzeDisc = { analyzeDisc() },
                    onStartBurn = { startBurn() },
                    onCancelBurn = { cancelBurn() },
                    onExportLogs = { exportLogs() },
                    onClearLogs = { viewModel.clearLogs() },
                    onAddFiles = { multiFilePickerLauncher.launch(arrayOf("*/*")) },
                    onAddIso = { filePickerLauncher.launch(arrayOf("application/x-iso9660-image")) },
                    onRequestStoragePermission = { requestStoragePermission() },
                    onSelectModel = { showDeviceSelection() }
                )
            }
        }

        requestStoragePermission()
    }

    private fun observeServiceState() {
        val service = burnService ?: return
        val usbManager = service.getUsbManager()

        lifecycleScope.launchWhenStarted {
            usbManager.deviceState.collect { state ->
                when (state) {
                    is UsbDeviceState.Connected -> {
                        viewModel.setDeviceConnected(true, state.deviceName)
                        usbManager.getCurrentDevice()?.let { device ->
                            currentDevice = device
                            // 自动检测型号
                            val autoModel = BurnerModelDatabase.autoDetect(device.vendorId, device.productId)
                            if (selectedBurnerModel == null) {
                                selectedBurnerModel = autoModel
                                viewModel.setDeviceModel(autoModel.displayName)
                            }
                            service.prepareBurn(device)
                        }
                    }
                    is UsbDeviceState.NoDevice -> {
                        viewModel.setDeviceConnected(false)
                    }
                    is UsbDeviceState.Error -> {
                        viewModel.addLog("设备错误: ${state.message}")
                    }
                    else -> {}
                }
            }
        }

        lifecycleScope.launchWhenStarted {
            service.serviceState.collect { state ->
                when (state) {
                    is ServiceState.AnalyzingDisc -> {
                        viewModel.setAnalyzing(true)
                    }
                    is ServiceState.AnalysisComplete -> {
                        viewModel.setDiscAnalysis(state.result)
                    }
                    is ServiceState.GeneratingIso -> {
                        viewModel.startIsoGeneration()
                    }
                    is ServiceState.Burning -> {
                        viewModel.startBurning()
                    }
                    is ServiceState.Completed -> {
                        viewModel.completeBurn(state.result)
                    }
                    is ServiceState.Error -> {
                        viewModel.completeBurn(
                            BurnResult.Failure(state.message, AuditCode.EXCEPTION)
                        )
                    }
                    else -> {}
                }
            }
        }

        lifecycleScope.launchWhenStarted {
            service.burnProgress.collect { progress ->
                viewModel.updateBurnProgress("刻录中", progress)
            }
        }

        lifecycleScope.launchWhenStarted {
            service.isoProgress.collect { progress ->
                viewModel.updateIsoProgress(progress, "")
            }
        }
    }

    private fun scanForDevice() {
        val device = burnService?.getUsbManager()?.scanForBurner()
        if (device != null) {
            currentDevice = device
            viewModel.addLog("发现刻录机: ${device.deviceName}")

            // 自动检测型号
            val autoModel = BurnerModelDatabase.autoDetect(device.vendorId, device.productId)
            selectedBurnerModel = autoModel
            viewModel.setDeviceModel(autoModel.displayName)
            viewModel.addLog("自动检测到型号: ${autoModel.fullName}")

            if (burnService?.getUsbManager()?.hasPermission(device) == true) {
                burnService?.prepareBurn(device)
            } else {
                viewModel.showPermissionDialog(true)
                burnService?.getUsbManager()?.requestPermission(device)
            }
        } else {
            viewModel.addLog("未找到刻录机，请检查连接")
        }
    }

    private fun analyzeDisc() {
        burnService?.analyzeDisc()
    }

    private fun startBurn() {
        val state = viewModel.uiState.value

        val options = WriteOptions(
            writeMode = state.writeMode,
            closeSession = state.closeSession,
            closeDisc = state.closeDisc,
            verifyAfterBurn = state.verifyAfterBurn,
            sessionName = state.volumeLabel
        )

        val task = when {
            state.selectedIsoFile != null -> {
                BurnTask.BurnIso(state.selectedIsoFile, options)
            }
            state.selectedFiles.isNotEmpty() -> {
                BurnTask.BurnFiles(state.selectedFiles, state.volumeLabel, options)
            }
            else -> return
        }

        burnService?.startBurnTask(task)
    }

    private fun cancelBurn() {
        burnService?.cancelTask()
        viewModel.addLog("任务已取消")
    }

    private fun showDeviceSelection() {
        val device = currentDevice
        if (device == null) {
            viewModel.addLog("请先连接刻录机")
            return
        }

        // 启动设备选择Activity或显示对话框
        // 这里简化处理，使用ViewModel来管理选择状态
        val intent = Intent(this, DeviceSelectionActivity::class.java).apply {
            putExtra("vendorId", device.vendorId)
            putExtra("productId", device.productId)
            putExtra("currentModelId", selectedBurnerModel?.modelId)
        }
        deviceSelectionLauncher.launch(intent)
    }

    private val deviceSelectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val modelId = result.data?.getStringExtra("selectedModelId")
            modelId?.let {
                val model = BurnerModelDatabase.findByModelId(it)
                if (model != null) {
                    selectedBurnerModel = model
                    viewModel.setDeviceModel(model.displayName)
                    viewModel.addLog("已选择刻录机: ${model.fullName}")

                    // 根据型号更新最大速度
                    viewModel.setMaxSpeed(model.maxSpeed)
                }
            }
        }
    }

    private fun exportLogs() {
        val file = burnService?.exportAuditLogs()
        if (file != null) {
            viewModel.addLog("审计日志已导出: ${file.absolutePath}")
        }
    }

    private fun requestStoragePermission() {
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
                if (!Environment.isExternalStorageManager()) {
                    val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                    startActivity(intent)
                }
            }
            else -> {
                storagePermissionLauncher.launch(
                    arrayOf(
                        android.Manifest.permission.READ_EXTERNAL_STORAGE,
                        android.Manifest.permission.WRITE_EXTERNAL_STORAGE
                    )
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val filter = android.content.IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction("com.enterprise.discburner.USB_PERMISSION")
        }
        registerReceiver(usbReceiver, filter)
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(usbReceiver)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (serviceBound) {
            unbindService(serviceConnection)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BurnScreen(
    viewModel: BurnViewModel,
    onScanDevice: () -> Unit,
    onAnalyzeDisc: () -> Unit,
    onStartBurn: () -> Unit,
    onCancelBurn: () -> Unit,
    onExportLogs: () -> Unit,
    onClearLogs: () -> Unit,
    onAddFiles: () -> Unit,
    onAddIso: () -> Unit,
    onRequestStoragePermission: () -> Unit,
    onSelectModel: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("企业光盘刻录") },
                actions = {
                    IconButton(onClick = onExportLogs) {
                        Icon(Icons.Default.Share, contentDescription = "导出日志")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Spacer(modifier = Modifier.height(4.dp))

            // 设备状态卡片
            DeviceStatusCard(
                isConnected = uiState.isConnected,
                deviceName = uiState.deviceName,
                selectedModel = uiState.selectedDeviceModel,
                onScanClick = onScanDevice,
                onSelectModel = onSelectModel
            )

            // 光盘分析卡片（仅在连接后显示）
            if (uiState.isConnected) {
                DiscAnalysisCard(
                    isAnalyzing = uiState.isAnalyzing,
                    analysis = uiState.discAnalysis,
                    onAnalyzeClick = onAnalyzeDisc
                )
            }

            // 刻录选项（仅在分析后显示）
            if (uiState.discAnalysis != null) {
                WriteOptionsCard(
                    writeMode = uiState.writeMode,
                    closeSession = uiState.closeSession,
                    closeDisc = uiState.closeDisc,
                    verifyAfterBurn = uiState.verifyAfterBurn,
                    canAppend = uiState.discAnalysis.canAppend,
                    hasExistingSessions = uiState.discAnalysis.sessions.isNotEmpty(),
                    onWriteModeChange = { viewModel.setWriteOptions(mode = it) },
                    onCloseSessionChange = { viewModel.setWriteOptions(closeSession = it) },
                    onCloseDiscChange = { viewModel.setWriteOptions(closeDisc = it) },
                    onVerifyChange = { viewModel.setWriteOptions(verify = it) }
                )
            }

            // 文件选择区域（标签页）
            if (uiState.discAnalysis != null && !uiState.discAnalysis.discInfo.isClosed) {
                FileSelectionTabs(
                    currentTab = uiState.currentTab,
                    onTabChange = { viewModel.setCurrentTab(it) },
                    fileTab = {
                        FileSelectionPanel(
                            selectedFiles = uiState.selectedFiles,
                            volumeLabel = uiState.volumeLabel,
                            onVolumeLabelChange = { viewModel.setVolumeLabel(it) },
                            onAddFiles = onAddFiles,
                            onRemoveFile = { viewModel.removeFile(it) }
                        )
                    },
                    isoTab = {
                        IsoSelectionPanel(
                            selectedIso = uiState.selectedIsoFile,
                            onSelectIso = onAddIso,
                            onClearIso = { viewModel.clearIsoSelection() }
                        )
                    }
                )
            }

            // 进度显示
            if (uiState.isGeneratingIso) {
                IsoProgressCard(progress = uiState.isoProgress)
            }

            if (uiState.isBurning) {
                BurnProgressCard(
                    stage = uiState.stage,
                    progress = uiState.burnProgress
                )
            }

            // 结果卡片
            uiState.lastResult?.let { result ->
                ResultCard(result = result)
            }

            // 日志输出
            LogOutput(
                logs = uiState.logs,
                onClear = onClearLogs,
                modifier = Modifier.height(200.dp)
            )

            // 操作按钮
            ActionButtons(
                isBurning = uiState.isBurning || uiState.isGeneratingIso,
                canStart = uiState.canStartBurn,
                onStartBurn = onStartBurn,
                onCancelBurn = onCancelBurn
            )

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
fun DeviceStatusCard(
    isConnected: Boolean,
    deviceName: String?,
    selectedModel: String?,
    onScanClick: () -> Unit,
    onSelectModel: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (isConnected) Color(0xFFE8F5E9) else Color(0xFFFFF3E0)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (isConnected) Icons.Default.CheckCircle else Icons.Default.Usb,
                        null,
                        tint = if (isConnected) Color(0xFF4CAF50) else Color(0xFFFF9800)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            if (isConnected) "设备已连接" else "未连接刻录机",
                            style = MaterialTheme.typography.titleMedium
                        )
                        if (isConnected && deviceName != null) {
                            Text(
                                deviceName,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                if (!isConnected) {
                    Button(onClick = onScanClick) {
                        Icon(Icons.Default.Refresh, null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("扫描")
                    }
                }
            }

            // 型号信息显示
            if (isConnected) {
                Spacer(modifier = Modifier.height(8.dp))
                Divider()
                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "刻录机型号",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            selectedModel ?: "未选择型号",
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (selectedModel != null)
                                MaterialTheme.colorScheme.onSurface
                            else
                                MaterialTheme.colorScheme.error
                        )
                    }

                    TextButton(onClick = onSelectModel) {
                        Icon(Icons.Default.Settings, null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(if (selectedModel != null) "更改" else "选择型号")
                    }
                }
            }
        }
    }
}

@Composable
fun DiscAnalysisCard(
    isAnalyzing: Boolean,
    analysis: DiscAnalysisResult?,
    onAnalyzeClick: () -> Unit
) {
    Card {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("光盘状态", style = MaterialTheme.typography.titleMedium)

                if (analysis == null && !isAnalyzing) {
                    Button(onClick = onAnalyzeClick) {
                        Text("分析光盘")
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            when {
                isAnalyzing -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(12.dp))
                        Text("正在分析光盘...")
                    }
                }
                analysis != null -> {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        InfoRow("状态", getDiscStatusText(analysis.discInfo.discStatus))
                        InfoRow("会话数", "${analysis.sessions.size}")
                        InfoRow("轨道数", "${analysis.tracks.size}")
                        InfoRow("剩余空间", formatSize(analysis.discInfo.remainingSectors * 2048))

                        if (analysis.canAppend) {
                            Text(
                                "✓ 可以追加刻录",
                                color = Color(0xFF4CAF50),
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }

                        if (analysis.discInfo.isClosed) {
                            Text(
                                "✗ 光盘已关闭，无法追加",
                                color = Color(0xFFF44336),
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
                else -> {
                    Text(
                        "请先分析光盘以获取信息",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
fun WriteOptionsCard(
    writeMode: WriteMode,
    closeSession: Boolean,
    closeDisc: Boolean,
    verifyAfterBurn: Boolean,
    canAppend: Boolean,
    hasExistingSessions: Boolean,
    onWriteModeChange: (WriteMode) -> Unit,
    onCloseSessionChange: (Boolean) -> Unit,
    onCloseDiscChange: (Boolean) -> Unit,
    onVerifyChange: (Boolean) -> Unit
) {
    Card {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("刻录选项", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(12.dp))

            // 写入模式选择
            Text("写入模式", style = MaterialTheme.typography.bodyMedium)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                WriteMode.values().forEach { mode ->
                    val enabled = when {
                        hasExistingSessions && mode == WriteMode.DAO -> false
                        !canAppend && mode != WriteMode.DAO -> false
                        else -> true
                    }

                    FilterChip(
                        selected = writeMode == mode,
                        onClick = { if (enabled) onWriteModeChange(mode) },
                        label = { Text(mode.description) },
                        enabled = enabled
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // 关闭选项
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = closeSession,
                        onCheckedChange = onCloseSessionChange
                    )
                    Text("关闭会话")
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = closeDisc,
                        onCheckedChange = onCloseDiscChange
                    )
                    Text("关闭光盘（不可再追加）")
                }
            }

            // 校验选项
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = verifyAfterBurn,
                    onCheckedChange = onVerifyChange
                )
                Text("刻录后自动校验数据完整性")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileSelectionTabs(
    currentTab: Int,
    onTabChange: (Int) -> Unit,
    fileTab: @Composable () -> Unit,
    isoTab: @Composable () -> Unit
) {
    Card {
        Column {
            TabRow(selectedTabIndex = currentTab) {
                Tab(
                    selected = currentTab == 0,
                    onClick = { onTabChange(0) },
                    text = { Text("刻录文件") },
                    icon = { Icon(Icons.Default.Folder, null) }
                )
                Tab(
                    selected = currentTab == 1,
                    onClick = { onTabChange(1) },
                    text = { Text("刻录ISO") },
                    icon = { Icon(Icons.Default.DiscFull, null) }
                )
            }

            Box(modifier = Modifier.padding(16.dp)) {
                if (currentTab == 0) {
                    fileTab()
                } else {
                    isoTab()
                }
            }
        }
    }
}

@Composable
fun FileSelectionPanel(
    selectedFiles: List<File>,
    volumeLabel: String,
    onVolumeLabelChange: (String) -> Unit,
    onAddFiles: () -> Unit,
    onRemoveFile: (File) -> Unit
) {
    Column {
        OutlinedTextField(
            value = volumeLabel,
            onValueChange = onVolumeLabelChange,
            label = { Text("卷标") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "已选择 ${selectedFiles.size} 个文件",
                style = MaterialTheme.typography.bodyMedium
            )
            Button(onClick = onAddFiles) {
                Icon(Icons.Default.Add, null)
                Spacer(modifier = Modifier.width(4.dp))
                Text("添加文件")
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // 文件列表
        selectedFiles.forEach { file ->
            FileListItem(
                file = file,
                onRemove = { onRemoveFile(file) }
            )
        }

        if (selectedFiles.isEmpty()) {
            Text(
                "点击"添加文件"选择要刻录的文件或文件夹",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun FileListItem(file: File, onRemove: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                if (file.isDirectory) Icons.Default.Folder else Icons.Default.InsertDriveFile,
                null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    file.name,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    formatSize(file.length()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        IconButton(onClick = onRemove, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Default.Close, null, tint = Color(0xFFF44336))
        }
    }
}

@Composable
fun IsoSelectionPanel(
    selectedIso: File?,
    onSelectIso: () -> Unit,
    onClearIso: () -> Unit
) {
    Column {
        if (selectedIso != null) {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFFE3F2FD))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            selectedIso.name,
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            "${formatSize(selectedIso.length())} · ISO 9660",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = onClearIso) {
                        Icon(Icons.Default.Close, null, tint = Color(0xFFF44336))
                    }
                }
            }
        } else {
            OutlinedButton(
                onClick = onSelectIso,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.FolderOpen, null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("选择ISO文件")
            }
            Text(
                "支持标准ISO 9660格式的镜像文件",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun IsoProgressCard(progress: Float) {
    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF8E1))) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text("正在生成ISO镜像...", style = MaterialTheme.typography.titleSmall)
            Spacer(modifier = Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                "${(progress * 100).toInt()}%",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
fun BurnProgressCard(stage: String, progress: Float) {
    Card {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text(stage, style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                "${(progress * 100).toInt()}%",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
fun ResultCard(result: BurnResult) {
    when (result) {
        is BurnResult.Success -> {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFFE8F5E9))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.CheckCircle, null, tint = Color(0xFF4CAF50))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("刻录成功", style = MaterialTheme.typography.titleMedium)
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("会话ID: ${result.sessionId}", style = MaterialTheme.typography.bodySmall)
                    Text("SHA256: ${result.sourceHash.take(16)}...", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        is BurnResult.Failure -> {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFFFFEBEE))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Error, null, tint = Color(0xFFF44336))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("刻录失败", style = MaterialTheme.typography.titleMedium)
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(result.message, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
fun LogOutput(
    logs: List<String>,
    onClear: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("日志", style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = onClear) {
                    Text("清除")
                }
            }

            Divider()

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                logs.forEach { log ->
                    Text(
                        log,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(vertical = 1.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun ActionButtons(
    isBurning: Boolean,
    canStart: Boolean,
    onStartBurn: () -> Unit,
    onCancelBurn: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        if (isBurning) {
            Button(
                onClick = onCancelBurn,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF44336))
            ) {
                Icon(Icons.Default.Close, null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("取消")
            }
        } else {
            Button(
                onClick = onStartBurn,
                enabled = canStart,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.PlayArrow, null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("开始刻录")
            }
        }
    }
}

@Composable
fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value)
    }
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
        bytes >= 1024 -> "%.2f KB".format(bytes / 1024.0)
        else -> "$bytes B"
    }
}
