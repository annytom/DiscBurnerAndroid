package com.enterprise.discburner.filesystem

import org.junit.Before
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse

/**
 * ISO 9660 生成器单元测试
 */
class Iso9660GeneratorTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var generator: Iso9660Generator

    @Before
    fun setup() {
        generator = Iso9660Generator()
    }

    /**
     * 测试：空文件列表应生成最小ISO
     */
    @Test
    fun `generateIso with empty file list creates minimal ISO`() {
        val outputFile = tempFolder.newFile("empty.iso")
        var lastProgress = 0f
        var lastStage = ""

        val result = generator.generateIso(
            sourceFiles = emptyList(),
            outputFile = outputFile,
            volumeLabel = "EMPTY",
            callback = { progress, stage ->
                lastProgress = progress
                lastStage = stage
            }
        )

        assertTrue(result.isSuccess, "ISO生成应成功")
        assertTrue(outputFile.exists(), "输出文件应存在")
        assertTrue(outputFile.length() > 0, "输出文件应有内容")

        // 验证ISO基本结构
        val isoSize = result.getOrNull()!!
        assertTrue(isoSize >= 2048 * 20, "最小ISO应至少包含系统区域和卷描述符")
    }

    /**
     * 测试：单文件刻录
     */
    @Test
    fun `generateIso with single file creates valid ISO`() {
        // 创建测试文件
        val testFile = tempFolder.newFile("test.txt")
        testFile.writeText("Hello, World! This is test content.")

        val outputFile = tempFolder.newFile("single.iso")

        val result = generator.generateIso(
            sourceFiles = listOf(testFile),
            outputFile = outputFile,
            volumeLabel = "SINGLE",
            callback = { _, _ -> }
        )

        assertTrue(result.isSuccess)

        // 验证ISO文件大小包含数据
        val isoSize = result.getOrNull()!!
        assertTrue(isoSize >= testFile.length(), "ISO应包含文件数据")

        // 验证ISO签名
        RandomAccessFile(outputFile, "r").use { raf ->
            // 检查系统区域（应为空）
            val systemArea = ByteArray(2048 * 16)
            raf.read(systemArea)
            assertTrue(systemArea.all { it == 0x00.toByte() }, "系统区域应为空")

            // 检查卷描述符签名
            val vdBuffer = ByteArray(8)
            raf.seek(2048 * 16L)
            raf.read(vdBuffer)
            assertEquals(0x01.toByte(), vdBuffer[0], "应是主卷描述符")
            assertEquals("CD001", String(vdBuffer, 1, 5), "应有标准标识符")
        }
    }

    /**
     * 测试：多文件刻录
     */
    @Test
    fun `generateIso with multiple files includes all files`() {
        // 创建多个测试文件
        val file1 = tempFolder.newFile("file1.txt")
        file1.writeText("Content of file 1")

        val file2 = tempFolder.newFile("file2.txt")
        file2.writeText("Content of file 2 which is longer")

        val file3 = tempFolder.newFile("file3.txt")
        file3.writeText("File 3 content")

        val outputFile = tempFolder.newFile("multi.iso")
        val callbackCalls = mutableListOf<Pair<Float, String>>()

        val result = generator.generateIso(
            sourceFiles = listOf(file1, file2, file3),
            outputFile = outputFile,
            volumeLabel = "MULTI",
            callback = { progress, stage ->
                callbackCalls.add(progress to stage)
            }
        )

        assertTrue(result.isSuccess)

        // 验证回调被调用
        assertTrue(callbackCalls.isNotEmpty(), "应有进度回调")
        assertEquals(1.0f, callbackCalls.last().first, "最终进度应为100%")

        // 验证ISO大小
        val totalInputSize = file1.length() + file2.length() + file3.length()
        val isoSize = result.getOrNull()!!
        assertTrue(isoSize >= totalInputSize, "ISO应包含所有文件数据")
    }

    /**
     * 测试：目录结构保留
     */
    @Test
    fun `generateIso preserves directory structure`() {
        // 创建目录结构
        val subDir = tempFolder.newFolder("subdir")
        val nestedFile = File(subDir, "nested.txt")
        nestedFile.writeText("Nested file content")

        val rootFile = tempFolder.newFile("root.txt")
        rootFile.writeText("Root file content")

        val outputFile = tempFolder.newFile("hierarchy.iso")

        val result = generator.generateIso(
            sourceFiles = listOf(tempFolder.root),
            outputFile = outputFile,
            volumeLabel = "HIERARCHY",
            callback = { _, _ -> }
        )

        assertTrue(result.isSuccess)
    }

    /**
     * 测试：大文件处理
     */
    @Test
    fun `generateIso handles large files`() {
        // 创建1MB的测试文件
        val largeFile = tempFolder.newFile("large.bin")
        val oneMB = ByteArray(1024 * 1024) { (it % 256).toByte() }
        largeFile.writeBytes(oneMB)

        val outputFile = tempFolder.newFile("large.iso")

        val result = generator.generateIso(
            sourceFiles = listOf(largeFile),
            outputFile = outputFile,
            volumeLabel = "LARGE",
            callback = { _, _ -> }
        )

        assertTrue(result.isSuccess)

        val isoSize = result.getOrNull()!!
        assertTrue(isoSize >= largeFile.length(), "ISO应包含完整大文件")
    }

    /**
     * 测试：特殊文件名处理
     */
    @Test
    fun `generateIso handles special characters in filenames`() {
        val fileWithSpaces = tempFolder.newFile("file with spaces.txt")
        fileWithSpaces.writeText("content")

        val fileWithDash = tempFolder.newFile("file-with-dash.txt")
        fileWithDash.writeText("content")

        val outputFile = tempFolder.newFile("special.iso")

        val result = generator.generateIso(
            sourceFiles = listOf(fileWithSpaces, fileWithDash),
            outputFile = outputFile,
            volumeLabel = "SPECIAL",
            callback = { _, _ -> }
        )

        assertTrue(result.isSuccess)
    }

    /**
     * 测试：卷标长度限制
     */
    @Test
    fun `generateIso truncates long volume label`() {
        val testFile = tempFolder.newFile("test.txt")
        testFile.writeText("content")

        val outputFile = tempFolder.newFile("label.iso")
        val veryLongLabel = "ThisIsAVeryLongVolumeLabelThatExceedsSixteenCharacters"

        val result = generator.generateIso(
            sourceFiles = listOf(testFile),
            outputFile = outputFile,
            volumeLabel = veryLongLabel,
            callback = { _, _ -> }
        )

        assertTrue(result.isSuccess)
        // ISO 9660卷标限制为16字符，超长应被截断
    }

    /**
     * 测试：空文件处理
     */
    @Test
    fun `generateIso handles empty files`() {
        val emptyFile = tempFolder.newFile("empty.txt")
        // 文件内容为空

        val outputFile = tempFolder.newFile("emptyfile.iso")

        val result = generator.generateIso(
            sourceFiles = listOf(emptyFile),
            outputFile = outputFile,
            volumeLabel = "EMPTYFILE",
            callback = { _, _ -> }
        )

        assertTrue(result.isSuccess)
    }

    /**
     * 测试：不存在的文件应返回失败
     */
    @Test
    fun `generateIso with nonexistent file returns failure`() {
        val nonExistentFile = File("/path/that/does/not/exist.txt")
        val outputFile = tempFolder.newFile("error.iso")

        val result = generator.generateIso(
            sourceFiles = listOf(nonExistentFile),
            outputFile = outputFile,
            volumeLabel = "ERROR",
            callback = { _, _ -> }
        )

        assertTrue(result.isFailure, "不存在的文件应导致失败")
    }

    /**
     * 测试：进度回调顺序
     */
    @Test
    fun `generateIso calls callback in order`() {
        val testFile = tempFolder.newFile("test.txt")
        testFile.writeText("content")

        val outputFile = tempFolder.newFile("order.iso")
        val progressValues = mutableListOf<Float>()

        generator.generateIso(
            sourceFiles = listOf(testFile),
            outputFile = outputFile,
            volumeLabel = "ORDER",
            callback = { progress, _ ->
                progressValues.add(progress)
            }
        )

        // 验证进度单调递增
        for (i in 1 until progressValues.size) {
            assertTrue(
                progressValues[i] >= progressValues[i - 1],
                "进度应单调递增: ${progressValues[i-1]} -> ${progressValues[i]}"
            )
        }

        // 验证进度范围
        assertTrue(progressValues.first() >= 0f, "起始进度应>=0")
        assertTrue(progressValues.last() <= 1.0f, "最终进度应<=1.0")
    }
}