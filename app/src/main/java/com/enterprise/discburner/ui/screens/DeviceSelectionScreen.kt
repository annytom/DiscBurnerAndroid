package com.enterprise.discburner.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.GridCells
import androidx.compose.foundation.lazy.LazyVerticalGrid
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.enterprise.discburner.usb.BurnerConfiguration
import com.enterprise.discburner.usb.BurnerModel
import com.enterprise.discburner.usb.WriteMode

/**
 * 刻录机配置界面（设置速度和模式）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BurnerConfigurationScreen(
    model: BurnerModel,
    currentSpeed: Int = 0,
    currentMode: WriteMode = WriteMode.DAO,
    onSpeedChanged: (Int) -> Unit,
    onModeChanged: (WriteMode) -> Unit,
    onSave: () -> Unit,
    onNavigateBack: () -> Unit
) {
    var selectedSpeed by remember { mutableStateOf(currentSpeed) }
    var selectedMode by remember { mutableStateOf(currentMode) }

    val speedOptions = remember(model.maxSpeed) {
        BurnerConfiguration.generateSpeedOptions(model.maxSpeed)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("刻录机配置") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, null)
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
            // 当前型号卡片
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "当前刻录机",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                    )
                    Text(
                        model.fullName,
                        style = MaterialTheme.typography.titleLarge
                    )
                    Text(
                        model.description,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            // 刻录速度选择
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "刻录速度",
                        style = MaterialTheme.typography.titleMedium
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        "选择较低速度可降低刻录错误率，推荐重要数据使用8x-16x",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // 速度选项 - 使用LazyVerticalGrid替代FlowRow
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 80.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 200.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(speedOptions) { speed ->
                            val speedText = if (speed == 0) "自动" else "${speed}x"
                            FilterChip(
                                selected = selectedSpeed == speed,
                                onClick = {
                                    selectedSpeed = speed
                                    onSpeedChanged(speed)
                                },
                                label = { Text(speedText) }
                            )
                        }
                    }
                }
            }

            // 默认刻录模式
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "默认刻录模式",
                        style = MaterialTheme.typography.titleMedium
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    model.supportedModes.forEach { mode ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = selectedMode == mode,
                                onClick = {
                                    selectedMode = mode
                                    onModeChanged(mode)
                                }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(mode.description)
                                Text(
                                    getModeDescription(mode),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            // 保存按钮
            Button(
                onClick = onSave,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Save, null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("保存配置")
            }
        }
    }
}

private fun getModeDescription(mode: WriteMode): String {
    return when (mode) {
        WriteMode.DAO -> "整盘刻录，兼容性最好，不支持追加"
        WriteMode.TAO -> "轨道刻录，支持追加，适合多会话"
        WriteMode.SAO -> "会话刻录，多版本备份推荐"
    }
}
