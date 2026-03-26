package com.enterprise.discburner.util

import java.io.File
import java.security.MessageDigest

/**
 * 格式化文件大小
 */
fun formatFileSize(bytes: Long): String {
    return when {
        bytes >= 1024 * 1024 * 1024 -> "%.2f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
        bytes >= 1024 * 1024 -> "%.2f MB".format(bytes / (1024.0 * 1024.0))
        bytes >= 1024 -> "%.2f KB".format(bytes / 1024.0)
        else -> "$bytes B"
    }
}

/**
 * 格式化时长
 */
fun formatDuration(ms: Long): String {
    val seconds = ms / 1000
    val minutes = seconds / 60
    val hours = minutes / 60
    val remainingSeconds = seconds % 60
    val remainingMinutes = minutes % 60

    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, remainingMinutes, remainingSeconds)
    } else {
        "%d:%02d".format(minutes, remainingSeconds)
    }
}

/**
 * ByteArray转Hex字符串
 */
fun ByteArray.toHexString(): String {
    return joinToString("") { "%02x".format(it) }
}

/**
 * 计算文件SHA256
 */
fun File.sha256(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    inputStream().use { input ->
        val buffer = ByteArray(8192)
        var read: Int
        while (input.read(buffer).also { read = it } > 0) {
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().toHexString()
}

/**
 * 检查文件是否为有效的ISO文件
 */
fun File.isValidIso(): Boolean {
    if (!exists() || !isFile) return false
    if (extension.lowercase() != "iso") return false
    if (length() < 2048) return false // 小于一个扇区

    // 可以添加更多检查，如读取ISO签名等
    return true
}
