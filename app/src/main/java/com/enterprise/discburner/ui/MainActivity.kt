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
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import com.enterprise.discburner.service.ServiceState
import com.enterprise.discburner.ui.theme.DiscBurnerTheme
import com.enterprise.discburner.usb.UsbDeviceState
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : ComponentActivity() {

    private var burnService: BurnService? = null
    private var serviceBound = false

    private val viewModel = BurnViewModel()

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

    // 存储权限请求
    private val storagePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        if (allGranted) {
            viewModel.addLog("存储权限已授予")
            scanIsoFiles()
        } else {
            viewModel.addLog("存储权限被拒绝，无法读取ISO文件")
        }
    }

    // USB权限广播
    private val usbReceiver = UsbBurnerManager(this).usbReceiver

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 启动并绑定服务
        Intent(this, BurnService::class.java).also { intent ->
            startService(intent)
            bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }

        setContent {
            DiscBurnerTheme {
                BurnScreen(
                    viewModel = viewModel,
                    onScanDevice = { scanForDevice() },
                    onStartBurn = { startBurn() },
                    onCancelBurn = { cancelBurn() },
                    onExportLogs = { exportLogs() },
                    onClearLogs = { viewModel.clearLogs() },
                    onFileSelected = { file -> viewModel.selectFile(file) },
                    onRequestStoragePermission = { requestStoragePermission() }
                )
            }
        }

        // 请求存储权限
        requestStoragePermission()
    }

    override fun onResume() {
        super.onResume()
        // 注册USB广播
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

    private fun observeServiceState() {
        val service = burnService ?: return
        val usbManager = service.getUsbManager()

        // 观察USB设备状态
        lifecycleScope.launchWhenStarted {
            usbManager.deviceState.collect { state ->
                when (state) {
                    is UsbDeviceState.Connected -> {
                        viewModel.setDeviceConnected(true, state.deviceName)
                        // 自动准备刻录
                        usbManager.getCurrentDevice()?.let { device ->
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

        // 观察服务状态
        lifecycleScope.launchWhenStarted {
            service.serviceState.collect { state ->
                when (state) {
                    is ServiceState.Burning -> {
                        viewModel.startBurn()
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

        // 观察进度
        lifecycleScope.launchWhenStarted {
            service.burnProgress.collect { progress ->
                val stage = when {
                    progress < 0.10f -> "准备中"
                    progress < 0.50f -> "刻录中"
                    progress < 0.55f -> "完成写入"
                    progress < 1.0f -> "校验中"
                    else -> "完成"
                }
                viewModel.updateProgress(stage, progress)
            }
        }
    }

    private fun scanForDevice() {
        val device = burnService?.getUsbManager()?.scanForBurner()
        if (device != null) {
            viewModel.addLog("发现刻录机: ${device.deviceName}")
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

    private fun scanIsoFiles() {
        viewModel.setLoadingFiles(true)
        viewModel.addLog("扫描ISO文件...")

        // 扫描指定目录
        val directories = listOf(
            File(Environment.getExternalStorageDirectory(), "Backups"),
            File(Environment.getExternalStorageDirectory(), "ISOs"),
            File(Environment.getExternalStorageDirectory(), "Downloads"),
            getExternalFilesDir(null)
        )

        val isoFiles = mutableListOf<File>()
        directories.forEach { dir ->
            dir?.listFiles { file ->
                file.isFile && file.extension.equals("iso", ignoreCase = true)
            }?.let { isoFiles.addAll(it) }
        }

        viewModel.setAvailableIsos(isoFiles)
    }

    private fun startBurn() {
        val file = viewModel.uiState.value.selectedFile ?: return
        burnService?.startBurning(file)
    }

    private fun cancelBurn() {
        burnService?.cancelBurn()
        viewModel.addLog("刻录已取消")
    }

    private fun exportLogs() {
        val file = burnService?.exportAuditLogs()
        if (file != null) {
            viewModel.addLog("审计日志已导出: ${file.absolutePath}")
            // 可以添加分享功能
        }
    }

    private fun requestStoragePermission() {
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
                // Android 11+: 请求所有文件访问权限
                if (!Environment.isExternalStorageManager()) {
                    val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                    startActivity(intent)
                } else {
                    scanIsoFiles()
                }
            }
            else -> {
                // Android 10及以下: 请求传统存储权限
                storagePermissionLauncher.launch(
                    arrayOf(
                        android.Manifest.permission.READ_EXTERNAL_STORAGE,
                        android.Manifest.permission.WRITE_EXTERNAL_STORAGE
                    )
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BurnScreen(
    viewModel: BurnViewModel,
    onScanDevice: () -> Unit,
    onStartBurn: () -> Unit,
    onCancelBurn: () -> Unit,
    onExportLogs: () -> Unit,
    onClearLogs: () -> Unit,
    onFileSelected: (File) -> Unit,
    onRequestStoragePermission: () -> Unit
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
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 设备状态卡片
            DeviceStatusCard(
                isConnected = uiState.isConnected,
                deviceName = uiState.deviceName,
                onScanClick = onScanDevice
            )

            // ISO文件选择
            IsoFileSelector(
                selectedFile = uiState.selectedFile,
                availableFiles = uiState.availableIsos,
                isLoading = uiState.isLoadingFiles,
                onFileSelected = onFileSelected,
                onRefresh = { onRequestStoragePermission() }
            )

            // 进度显示
            if (uiState.isBurning) {
                BurnProgressCard(
                    stage = uiState.stage,
                    progress = uiState.progress
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
                modifier = Modifier.weight(1f)
            )

            // 操作按钮
            ActionButtons(
                isBurning = uiState.isBurning,
                canStart = uiState.canStartBurn,
                onStartBurn = onStartBurn,
                onCancelBurn = onCancelBurn
            )
        }
    }
}

@Composable
fun DeviceStatusCard(
    isConnected: Boolean,
    deviceName: String?,
    onScanClick: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (isConnected) Color(0xFFE8F5E9) else Color(0xFFFFF3E0)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (isConnected) Icons.Default.CheckCircle else Icons.Default.Usb,
                    null,
                    tint = if (isConnected) Color(0xFF4CAF50) else Color(0xFFFF9800)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text(
                        if (isConnected) "设备已连接" else "未连接刻录机",
                        style = MaterialTheme.typography.titleMedium
                    )
                    if (isConnected && deviceName != null) {
                        Text(deviceName, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            if (!isConnected) {
                Button(onClick = onScanClick) {
                    Text("扫描设备")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IsoFileSelector(
    selectedFile: File?,
    availableFiles: List<File>,
    isLoading: Boolean,
    onFileSelected: (File) -> Unit,
    onRefresh: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Card {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("选择ISO文件", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))

            ExposedDropdownMenuBox(
                expanded = expanded,
                onExpandedChange = { expanded = it }
            ) {
                OutlinedTextField(
                    value = selectedFile?.name ?: "请选择文件",
                    onValueChange = {},
                    readOnly = true,
                    trailingIcon = {
                        if (isLoading) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp))
                        } else {
                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
                        }
                    },
                    modifier = Modifier.menuAnchor()
                )

                ExposedDropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false }
                ) {
                    if (availableFiles.isEmpty()) {
                        DropdownMenuItem(
                            text = { Text("未找到ISO文件") },
                            onClick = {
                                expanded = false
                                onRefresh()
                            }
                        )
                    } else {
                        availableFiles.forEach { file ->
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(file.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(
                                            formatFileSize(file.length()),
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }
                                },
                                onClick = {
                                    onFileSelected(file)
                                    expanded = false
                                }
                            )
                        }
                    }
                }
            }
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
                    Text("会话ID: ${result.sessionId}")
                    Text("用时: ${formatDuration(result.duration)}")
                    Text("扇区数: ${result.sectorsWritten}")
                    Text("源文件SHA256: ${result.sourceHash.take(16)}...")
                    Text("校验SHA256: ${result.verifiedHash.take(16)}...")
                    Text("校验状态: ✓ 通过", color = Color(0xFF4CAF50))
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
                    Text(result.message)
                    Text("错误码: ${result.code.code}", style = MaterialTheme.typography.bodySmall)
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

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(16.dp),
                reverseLayout = true
            ) {
                items(logs.reversed()) { log ->
                    Text(
                        log,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(vertical = 2.dp)
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
                Text("取消刻录")
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

private fun formatFileSize(bytes: Long): String {
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
