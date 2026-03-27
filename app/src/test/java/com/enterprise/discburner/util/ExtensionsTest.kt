package com.enterprise.discburner.util

import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 工具函数单元测试
 */
class ExtensionsTest {

    /**
     * 测试：文件大小格式化 - GB
     */
    @Test
    fun `formatFileSize formats gigabytes correctly`() {
        val size = 2L * 1024 * 1024 * 1024 // 2 GB
        val result = formatFileSize(size)
        assertEquals("2.00 GB", result)
    }

    /**
     * 测试：文件大小格式化 - MB
     */
    @Test
    fun `formatFileSize formats megabytes correctly`() {
        val size = 500L * 1024 * 1024 // 500 MB
        val result = formatFileSize(size)
        assertEquals("500.00 MB", result)
    }

    /**
     * 测试：文件大小格式化 - KB
     */
    @Test
    fun `formatFileSize formats kilobytes correctly`() {
        val size = 100L * 1024 // 100 KB
        val result = formatFileSize(size)
        assertEquals("100.00 KB", result)
    }

    /**
     * 测试：文件大小格式化 - Bytes
     */
    @Test
    fun `formatFileSize formats bytes correctly`() {
        val size = 512L // 512 B
        val result = formatFileSize(size)
        assertEquals("512 B", result)
    }

    /**
     * 测试：时长格式化 - 小时
     */
    @Test
    fun `formatDuration formats hours correctly`() {
        val ms = (2 * 60 * 60 + 30 * 60 + 45) * 1000L // 2:30:45
        val result = formatDuration(ms)
        assertEquals("2:30:45", result)
    }

    /**
     * 测试：时长格式化 - 分钟
     */
    @Test
    fun `formatDuration formats minutes correctly`() {
        val ms = (5 * 60 + 30) * 1000L // 5:30
        val result = formatDuration(ms)
        assertEquals("5:30", result)
    }

    /**
     * 测试：时长格式化 - 秒
     */
    @Test
    fun `formatDuration formats seconds correctly`() {
        val ms = 45 * 1000L // 45秒
        val result = formatDuration(ms)
        assertEquals("0:45", result)
    }

    /**
     * 测试：ByteArray转Hex
     */
    @Test
    fun `toHexString converts bytes correctly`() {
        val bytes = byteArrayOf(0x12, 0xAB, 0xCD.toByte(), 0xEF.toByte())
        val result = bytes.toHexString()
        assertEquals("12abcdef", result)
    }

    /**
     * 测试：空ByteArray转Hex
     */
    @Test
    fun `toHexString handles empty array`() {
        val bytes = byteArrayOf()
        val result = bytes.toHexString()
        assertEquals("", result)
    }

    /**
     * 测试：文件SHA256计算
     */
    @Test
    fun `File sha256 calculates correctly`() {
        val tempFile = File.createTempFile("test", ".txt")
        tempFile.writeText("Hello, World!")

        val hash = tempFile.sha256()

        // 已知 "Hello, World!" 的SHA256
        val expectedHash = "dffd6021bb2bd5b0af676290809ec3a53191dd81c7f70a4b28688a362182986f"
        assertEquals(expectedHash, hash)

        tempFile.delete()
    }

    /**
     * 测试：空文件SHA256
     */
    @Test
    fun `File sha256 handles empty file`() {
        val tempFile = File.createTempFile("empty", ".txt")
        // 空文件

        val hash = tempFile.sha256()

        // 空文件的SHA256
        assertEquals(64, hash.length) // SHA256长度应为64字符
        assertTrue(hash.matches(Regex("[0-9a-f]+")))

        tempFile.delete()
    }

    /**
     * 测试：有效ISO文件检查 - 有效
     */
    @Test
    fun `isValidIso returns true for valid iso`() {
        val tempFile = File.createTempFile("test", ".iso")
        // 写入超过2048字节的内容
        tempFile.writeBytes(ByteArray(4096) { 0x00 })

        assertTrue(tempFile.isValidIso())

        tempFile.delete()
    }

    /**
     * 测试：有效ISO文件检查 - 太小
     */
    @Test
    fun `isValidIso returns false for small file`() {
        val tempFile = File.createTempFile("small", ".iso")
        tempFile.writeBytes(ByteArray(1000)) // 小于2048字节

        assertFalse(tempFile.isValidIso())

        tempFile.delete()
    }

    /**
     * 测试：有效ISO文件检查 - 错误扩展名
     */
    @Test
    fun `isValidIso returns false for wrong extension`() {
        val tempFile = File.createTempFile("test", ".txt")
        tempFile.writeBytes(ByteArray(4096))

        assertFalse(tempFile.isValidIso())

        tempFile.delete()
    }

    /**
     * 测试：有效ISO文件检查 - 不存在
     */
    @Test
    fun `isValidIso returns false for nonexistent file`() {
        val nonExistent = File("/path/that/does/not/exist.iso")
        assertFalse(nonExistent.isValidIso())
    }

    /**
     * 测试：有效ISO文件检查 - 目录
     */
    @Test
    fun `isValidIso returns false for directory`() {
        val tempDir = File.createTempFile("dir", "")
        tempDir.delete()
        tempDir.mkdirs()

        assertFalse(tempDir.isValidIso())

        tempDir.delete()
    }
}