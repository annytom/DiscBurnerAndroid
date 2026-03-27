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
import com.enterprise.discburner.data.database.BurnSessionEntity
import com.enterprise.discburner.data.database.SessionStats
import java.text.SimpleDateFormat
import java.util.*

/**
 * 刻录历史记录界面
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    sessions: List<BurnSessionEntity>,
    sessionStats: SessionStats?,
    onNavigateBack: () -> Unit,
    onViewSessionDetail: (String) -> Unit,
    onExportSession: (String) -> Unit,
    onDeleteSession: (String) -> Unit,
    onSearch: (String) -> Unit,
    onFilterByTimeRange: (Long, Long) -> Unit,
    onRefresh: () -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    var showFilterDialog by remember { mutableStateOf(false) }
    var selectedTimeRange by remember { mutableStateOf(TimeRange.ALL) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("刻录历史") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { showFilterDialog = true }) {
                        Icon(Icons.Default.FilterList, contentDescription = "筛选")
                    }
                    IconButton(onClick = onRefresh) {
                        Icon(Icons.Default.Refresh, contentDescription = "刷新")
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
            // 统计卡片
            sessionStats?.let {
                StatsCard(stats = it)
            }

            // 搜索栏
            OutlinedTextField(
                value = searchQuery,
                onValueChange = {
                    searchQuery = it
                    onSearch(it)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                placeholder = { Text("搜索会话ID、卷标...") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                singleLine = true
            )

            // 时间范围指示器
            if (selectedTimeRange != TimeRange.ALL) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "时间范围: ${selectedTimeRange.displayName}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    TextButton(onClick = {
                        selectedTimeRange = TimeRange.ALL
                        onFilterByTimeRange(0, Long.MAX_VALUE)
                    }) {
                        Text("清除")
                    }
                }
            }

            // 会话列表
            if (sessions.isEmpty()) {
                EmptyHistoryState()
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(sessions, key = { it.sessionId }) { session ->
                        SessionCard(
                            session = session,
                            onClick = { onViewSessionDetail(session.sessionId) },
                            onExport = { onExportSession(session.sessionId) },
                            onDelete = { onDeleteSession(session.sessionId) }
                        )
                    }
                }
            }
        }
    }

    // 筛选对话框
    if (showFilterDialog) {
        TimeRangeFilterDialog(
            currentRange = selectedTimeRange,
            onDismiss = { showFilterDialog = false },
            onSelect = { range ->
                selectedTimeRange = range
                val (start, end) = range.toTimeRange()
                onFilterByTimeRange(start, end)
                showFilterDialog = false
            }
        )
    }
}

@Composable
fun StatsCard(stats: SessionStats) {
    val successRate = if (stats.total > 0) {
        (stats.success * 100 / stats.total)
    } else 0

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("统计概览", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                StatItem("总会话", stats.total.toString(), Color(0xFF2196F3))
                StatItem("成功", stats.success.toString(), Color(0xFF4CAF50))
                StatItem("失败", stats.failed.toString(), Color(0xFFF44336))
                StatItem("成功率", "$successRate%", Color(0xFFFF9800))
            }
        }
    }
}

@Composable
fun StatItem(label: String, value: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            value,
            style = MaterialTheme.typography.headlineSmall,
            color = color
        )
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun SessionCard(
    session: BurnSessionEntity,
    onClick: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit
) {
    val statusColor = when (session.status) {
        "COMPLETED" -> Color(0xFF4CAF50)
        "FAILED" -> Color(0xFFF44336)
        "CANCELLED" -> Color(0xFFFF9800)
        else -> Color(0xFF9E9E9E)
    }

    val statusText = when (session.status) {
        "COMPLETED" -> "成功"
        "FAILED" -> "失败"
        "CANCELLED" -> "取消"
        "RUNNING" -> "进行中"
        else -> "未知"
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        when (session.status) {
                            "COMPLETED" -> Icons.Default.CheckCircle
                            "FAILED" -> Icons.Default.Error
                            else -> Icons.Default.Schedule
                        },
                        null,
                        tint = statusColor,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        session.sessionId.takeLast(8),
                        style = MaterialTheme.typography.titleMedium
                    )
                }

                Surface(
                    color = statusColor.copy(alpha = 0.1f),
                    shape = MaterialTheme.shapes.small
                ) {
                    Text(
                        statusText,
                        color = statusColor,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // 会话详情
            InfoRow("卷标", session.volumeLabel ?: "无")
            InfoRow("刻录模式", session.writeMode)
            InfoRow("总大小", formatFileSize(session.totalBytes))

            session.duration?.let { duration ->
                InfoRow("耗时", formatDuration(duration))
            }

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                "开始时间: ${formatTimestamp(session.startTime)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            session.errorMessage?.let { error ->
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "错误: $error",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFF44336),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                IconButton(onClick = onExport) {
                    Icon(Icons.Default.Share, null, tint = MaterialTheme.colorScheme.primary)
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, null, tint = Color(0xFFF44336))
                }
            }
        }
    }
}

@Composable
fun EmptyHistoryState() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Default.History,
                null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                "暂无刻录记录",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                "完成刻录任务后将在此处显示",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
        }
    }
}

@Composable
fun TimeRangeFilterDialog(
    currentRange: TimeRange,
    onDismiss: () -> Unit,
    onSelect: (TimeRange) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择时间范围") },
        text = {
            Column {
                TimeRange.values().forEach { range ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = currentRange == range,
                            onClick = { onSelect(range) }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(range.displayName)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

enum class TimeRange(val displayName: String) {
    ALL("全部时间"),
    TODAY("今天"),
    WEEK("最近7天"),
    MONTH("最近30天");

    fun toTimeRange(): Pair<Long, Long> {
        val now = System.currentTimeMillis()
        val start = when (this) {
            ALL -> 0
            TODAY -> now - 24 * 60 * 60 * 1000
            WEEK -> now - 7 * 24 * 60 * 60 * 1000
            MONTH -> now - 30L * 24 * 60 * 60 * 1000
        }
        return start to now
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

private fun formatDuration(millis: Long): String {
    val seconds = millis / 1000
    val minutes = seconds / 60
    val hours = minutes / 60
    return when {
        hours > 0 -> "%d小时%02d分".format(hours, minutes % 60)
        minutes > 0 -> "%d分%02d秒".format(minutes, seconds % 60)
        else -> "%d秒".format(seconds)
    }
}

private fun formatTimestamp(timestamp: Long): String {
    val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    return sdf.format(Date(timestamp))
}
