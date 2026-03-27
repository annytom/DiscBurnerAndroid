package com.enterprise.discburner.ui.screens

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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.enterprise.discburner.service.BurnPriority
import com.enterprise.discburner.service.BurnTask
import com.enterprise.discburner.service.QueueState
import com.enterprise.discburner.service.QueueTask
import java.text.SimpleDateFormat
import java.util.*

/**
 * 刻录队列管理界面
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueScreen(
    queueState: QueueState,
    currentTask: QueueTask?,
    pendingTasks: List<QueueTask>,
    completedTasks: List<QueueTask>,
    failedTasks: List<QueueTask>,
    isAutoProcessing: Boolean,
    onStartProcessing: () -> Unit,
    onPauseProcessing: () -> Unit,
    onRetryTask: (String) -> Unit,
    onCancelTask: (String) -> Unit,
    onRemoveTask: (String) -> Unit,
    onClearCompleted: () -> Unit,
    onClearFailed: () -> Unit,
    onNavigateBack: () -> Unit,
    onNavigateToHistory: () -> Unit
) {
    var selectedTab by remember { mutableStateOf(0) }
    val tabs = listOf("待处理 (${pendingTasks.size})", "进行中", "已完成 (${completedTasks.size})", "失败 (${failedTasks.size})")

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("刻录队列管理") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = onNavigateToHistory) {
                        Icon(Icons.Default.History, contentDescription = "历史记录")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // 队列状态卡片
            QueueStatusCard(
                queueState = queueState,
                isAutoProcessing = isAutoProcessing,
                onStartProcessing = onStartProcessing,
                onPauseProcessing = onPauseProcessing
            )

            // 标签页
            TabRow(selectedTabIndex = selectedTab) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = { Text(title) }
                    )
                }
            }

            // 内容区域
            Box(modifier = Modifier.fillMaxSize()) {
                when (selectedTab) {
                    0 -> PendingTasksList(
                        tasks = pendingTasks,
                        onCancelTask = onCancelTask,
                        onRemoveTask = onRemoveTask
                    )
                    1 -> currentTask?.let {
                        CurrentTaskPanel(task = it, onCancel = { onCancelTask(it.id) })
                    } ?: Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("暂无进行中的任务", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    2 -> CompletedTasksList(
                        tasks = completedTasks,
                        onClearAll = onClearCompleted
                    )
                    3 -> FailedTasksList(
                        tasks = failedTasks,
                        onRetryTask = onRetryTask,
                        onRemoveTask = onRemoveTask,
                        onClearAll = onClearFailed
                    )
                }
            }
        }
    }
}

@Composable
fun QueueStatusCard(
    queueState: QueueState,
    isAutoProcessing: Boolean,
    onStartProcessing: () -> Unit,
    onPauseProcessing: () -> Unit
) {
    val statusColor = when (queueState) {
        QueueState.IDLE -> Color(0xFF9E9E9E)
        QueueState.PROCESSING -> Color(0xFF2196F3)
        QueueState.PAUSED -> Color(0xFFFF9800)
        QueueState.COMPLETED -> Color(0xFF4CAF50)
        QueueState.ERROR -> Color(0xFFF44336)
    }

    val statusText = when (queueState) {
        QueueState.IDLE -> "空闲"
        QueueState.PROCESSING -> "处理中"
        QueueState.PAUSED -> "已暂停"
        QueueState.COMPLETED -> "已完成"
        QueueState.ERROR -> "错误"
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .padding(end = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.Circle,
                        contentDescription = null,
                        tint = statusColor,
                        modifier = Modifier.size(12.dp)
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text("队列状态", style = MaterialTheme.typography.bodySmall)
                    Text(statusText, style = MaterialTheme.typography.titleMedium)
                }
            }

            // 自动处理开关
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "自动处理",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(end = 8.dp)
                )
                Switch(
                    checked = isAutoProcessing,
                    onCheckedChange = { checked ->
                        if (checked) onStartProcessing() else onPauseProcessing()
                    }
                )
            }
        }
    }
}

@Composable
fun PendingTasksList(
    tasks: List<QueueTask>,
    onCancelTask: (String) -> Unit,
    onRemoveTask: (String) -> Unit
) {
    if (tasks.isEmpty()) {
        EmptyStateMessage("暂无待处理任务")
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(tasks, key = { it.id }) { task ->
            PendingTaskCard(
                task = task,
                onCancel = { onCancelTask(task.id) },
                onRemove = { onRemoveTask(task.id) }
            )
        }
    }
}

@Composable
fun PendingTaskCard(
    task: QueueTask,
    onCancel: () -> Unit,
    onRemove: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PriorityChip(priority = task.priority)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        getTaskName(task.task),
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Row {
                    IconButton(onClick = onCancel) {
                        Icon(Icons.Default.Pause, null, tint = Color(0xFFFF9800))
                    }
                    IconButton(onClick = onRemove) {
                        Icon(Icons.Default.Delete, null, tint = Color(0xFFF44336))
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // 任务详情
            when (val t = task.task) {
                is BurnTask.BurnFiles -> {
                    Text("文件数量: ${t.files.size}", style = MaterialTheme.typography.bodyMedium)
                    Text("卷标: ${t.volumeLabel}", style = MaterialTheme.typography.bodySmall)
                }
                is BurnTask.BurnIso -> {
                    Text("ISO文件: ${t.isoFile.name}", style = MaterialTheme.typography.bodyMedium)
                    Text("大小: ${formatFileSize(t.isoFile.length())}", style = MaterialTheme.typography.bodySmall)
                }
                is BurnTask.BurnDirectory -> {
                    Text("目录: ${t.directory.name}", style = MaterialTheme.typography.bodyMedium)
                    Text("卷标: ${t.volumeLabel}", style = MaterialTheme.typography.bodySmall)
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                "添加时间: ${formatTimestamp(task.createdAt)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (task.retryCount > 0) {
                Text(
                    "重试次数: ${task.retryCount}/2",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFFF9800)
                )
            }
        }
    }
}

@Composable
fun CurrentTaskPanel(task: QueueTask, onCancel: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFFE3F2FD))
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        "正在刻录",
                        style = MaterialTheme.typography.titleLarge
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    getTaskName(task.task),
                    style = MaterialTheme.typography.titleMedium
                )

                Spacer(modifier = Modifier.height(8.dp))

                when (val t = task.task) {
                    is BurnTask.BurnFiles -> {
                        InfoRow("类型", "文件刻录")
                        InfoRow("文件数", "${t.files.size}")
                        InfoRow("卷标", t.volumeLabel)
                    }
                    is BurnTask.BurnIso -> {
                        InfoRow("类型", "ISO刻录")
                        InfoRow("文件", t.isoFile.name)
                        InfoRow("大小", formatFileSize(t.isoFile.length()))
                    }
                    is BurnTask.BurnDirectory -> {
                        InfoRow("类型", "目录刻录")
                        InfoRow("目录", t.directory.name)
                        InfoRow("卷标", t.volumeLabel)
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Button(
                    onClick = onCancel,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF44336)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Stop, null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("取消刻录")
                }
            }
        }
    }
}

@Composable
fun CompletedTasksList(
    tasks: List<QueueTask>,
    onClearAll: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        if (tasks.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onClearAll) {
                    Icon(Icons.Default.DeleteSweep, null)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("清空已完成")
                }
            }
        }

        if (tasks.isEmpty()) {
            EmptyStateMessage("暂无已完成任务")
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(tasks, key = { it.id }) { task ->
                    CompletedTaskCard(task = task)
                }
            }
        }
    }
}

@Composable
fun CompletedTaskCard(task: QueueTask) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFE8F5E9))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.CheckCircle, null, tint = Color(0xFF4CAF50))
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    getTaskName(task.task),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                "完成时间: ${formatTimestamp(task.completedAt ?: task.updatedAt)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun FailedTasksList(
    tasks: List<QueueTask>,
    onRetryTask: (String) -> Unit,
    onRemoveTask: (String) -> Unit,
    onClearAll: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        if (tasks.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onClearAll) {
                    Icon(Icons.Default.DeleteSweep, null)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("清空失败任务")
                }
            }
        }

        if (tasks.isEmpty()) {
            EmptyStateMessage("暂无失败任务")
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(tasks, key = { it.id }) { task ->
                    FailedTaskCard(
                        task = task,
                        onRetry = { onRetryTask(task.id) },
                        onRemove = { onRemoveTask(task.id) }
                    )
                }
            }
        }
    }
}

@Composable
fun FailedTaskCard(
    task: QueueTask,
    onRetry: () -> Unit,
    onRemove: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFEBEE))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Error, null, tint = Color(0xFFF44336))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        getTaskName(task.task),
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            task.errorMessage?.let { error ->
                Text(
                    "错误: $error",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFF44336)
                )
                Spacer(modifier = Modifier.height(4.dp))
            }

            Text(
                "失败时间: ${formatTimestamp(task.updatedAt)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row {
                Button(onClick = onRetry) {
                    Icon(Icons.Default.Replay, null)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("重试")
                }
                Spacer(modifier = Modifier.width(8.dp))
                OutlinedButton(onClick = onRemove) {
                    Text("移除")
                }
            }
        }
    }
}

@Composable
fun PriorityChip(priority: BurnPriority) {
    val (text, color) = when (priority) {
        BurnPriority.CRITICAL -> "紧急" to Color(0xFFF44336)
        BurnPriority.HIGH -> "高" to Color(0xFFFF9800)
        BurnPriority.NORMAL -> "普通" to Color(0xFF2196F3)
        BurnPriority.LOW -> "低" to Color(0xFF9E9E9E)
    }

    Surface(
        color = color.copy(alpha = 0.1f),
        shape = MaterialTheme.shapes.small
    ) {
        Text(
            text,
            color = color,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
        )
    }
}

@Composable
fun EmptyStateMessage(message: String) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Default.Inbox,
                null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                message,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun getTaskName(task: BurnTask): String {
    return when (task) {
        is BurnTask.BurnFiles -> "刻录 ${task.files.size} 个文件"
        is BurnTask.BurnIso -> "刻录 ISO: ${task.isoFile.name}"
        is BurnTask.BurnDirectory -> "刻录目录: ${task.directory.name}"
    }
}

private fun formatFileSize(bytes: Long): String {
    return when {
        bytes >= 1024 * 1024 * 1024 -> "%.2f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
        bytes >= 1024 * 1024 -> "%.2f MB".format(bytes / (1024.0 * 1024.0))
        bytes >= 1024 -> "%.2f KB".format(bytes / 1024.0)
        else -> "$bytes B"
    }
}

private fun formatTimestamp(timestamp: Long): String {
    val sdf = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
    return sdf.format(Date(timestamp))
}
