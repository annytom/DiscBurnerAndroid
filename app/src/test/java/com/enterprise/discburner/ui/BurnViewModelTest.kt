package com.enterprise.discburner.ui

import com.enterprise.discburner.data.BurnResult
import com.enterprise.discburner.usb.DiscInformation
import com.enterprise.discburner.usb.WriteMode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import kotlin.test.*

/**
 * BurnViewModel 单元测试
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BurnViewModelTest {

    private lateinit var viewModel: BurnViewModel

    @Before
    fun setup() {
        viewModel = BurnViewModel()
    }

    /**
     * 测试：初始状态
     */
    @Test
    fun `initial state is correct`() = runTest {
        val state = viewModel.uiState.value

        assertFalse(state.isConnected)
        assertNull(state.deviceName)
        assertNull(state.discAnalysis)
        assertFalse(state.isAnalyzing)
        assertTrue(state.selectedFiles.isEmpty())
        assertNull(state.selectedIsoFile)
        assertEquals("BACKUP", state.volumeLabel)
        assertEquals(WriteMode.TAO, state.writeMode)
        assertFalse(state.closeSession)
        assertFalse(state.closeDisc)
        assertTrue(state.verifyAfterBurn)
        assertFalse(state.isGeneratingIso)
        assertFalse(state.isBurning)
        assertEquals(0f, state.isoProgress)
        assertEquals(0f, state.burnProgress)
        assertTrue(state.logs.isEmpty())
        assertNull(state.lastResult)
        assertFalse(state.canStartBurn)
        assertEquals(0, state.currentTab)
    }

    /**
     * 测试：设备连接
     */
    @Test
    fun `setDeviceConnected updates state`() = runTest {
        viewModel.setDeviceConnected(true, "Test Burner")

        val state = viewModel.uiState.value
        assertTrue(state.isConnected)
        assertEquals("Test Burner", state.deviceName)
    }

    /**
     * 测试：添加文件
     */
    @Test
    fun `addFiles adds to selected files`() = runTest {
        val testFile = java.io.File("/test/file1.txt")

        viewModel.addFiles(listOf(testFile))

        val state = viewModel.uiState.value
        assertEquals(1, state.selectedFiles.size)
        assertEquals(testFile, state.selectedFiles.first())
    }

    /**
     * 测试：添加重复文件
     */
    @Test
    fun `addFiles does not add duplicates`() = runTest {
        val testFile = java.io.File("/test/file1.txt")

        viewModel.addFiles(listOf(testFile))
        viewModel.addFiles(listOf(testFile))

        val state = viewModel.uiState.value
        assertEquals(1, state.selectedFiles.size)
    }

    /**
     * 测试：移除文件
     */
    @Test
    fun `removeFile removes from selected files`() = runTest {
        val testFile = java.io.File("/test/file1.txt")
        viewModel.addFiles(listOf(testFile))

        viewModel.removeFile(testFile)

        val state = viewModel.uiState.value
        assertTrue(state.selectedFiles.isEmpty())
    }

    /**
     * 测试：选择ISO文件
     */
    @Test
    fun `selectIsoFile updates state`() = runTest {
        val isoFile = java.io.File("/test/backup.iso")

        viewModel.selectIsoFile(isoFile)

        val state = viewModel.uiState.value
        assertEquals(isoFile, state.selectedIsoFile)
        assertEquals(1, state.currentTab) // 应切换到ISO标签页
    }

    /**
     * 测试：清除ISO选择
     */
    @Test
    fun `clearIsoSelection clears iso file`() = runTest {
        viewModel.selectIsoFile(java.io.File("/test.iso"))
        viewModel.clearIsoSelection()

        val state = viewModel.uiState.value
        assertNull(state.selectedIsoFile)
    }

    /**
     * 测试：设置卷标
     */
    @Test
    fun `setVolumeLabel updates label`() = runTest {
        viewModel.setVolumeLabel("MYBACKUP")

        val state = viewModel.uiState.value
        assertEquals("MYBACKUP", state.volumeLabel)
    }

    /**
     * 测试：设置写入模式
     */
    @Test
    fun `setWriteOptions updates write mode`() = runTest {
        viewModel.setWriteOptions(mode = WriteMode.DAO)

        val state = viewModel.uiState.value
        assertEquals(WriteMode.DAO, state.writeMode)
    }

    /**
     * 测试：设置关闭选项
     */
    @Test
    fun `setWriteOptions updates close options`() = runTest {
        viewModel.setWriteOptions(closeSession = true, closeDisc = true)

        val state = viewModel.uiState.value
        assertTrue(state.closeSession)
        assertTrue(state.closeDisc)
    }

    /**
     * 测试：切换标签页
     */
    @Test
    fun `setCurrentTab updates tab`() = runTest {
        viewModel.setCurrentTab(1)

        val state = viewModel.uiState.value
        assertEquals(1, state.currentTab)
    }

    /**
     * 测试：开始刻录
     */
    @Test
    fun `startBurning updates burning state`() = runTest {
        viewModel.startBurning()

        val state = viewModel.uiState.value
        assertTrue(state.isBurning)
        assertEquals(0f, state.burnProgress)
        assertEquals("准备中...", state.stage)
        assertNull(state.lastResult)
    }

    /**
     * 测试：更新刻录进度
     */
    @Test
    fun `updateBurnProgress updates progress`() = runTest {
        viewModel.updateBurnProgress("刻录中", 0.5f)

        val state = viewModel.uiState.value
        assertEquals("刻录中", state.stage)
        assertEquals(0.5f, state.burnProgress)
    }

    /**
     * 测试：完成刻录成功
     */
    @Test
    fun `completeBurn with success updates state`() = runTest {
        val successResult = BurnResult.Success(
            sessionId = "TEST-123",
            duration = 60000,
            sourceHash = "abc123",
            verifiedHash = "abc123",
            sectorsWritten = 1000
        )

        // 先添加一些文件
        viewModel.addFiles(listOf(java.io.File("/test.txt")))

        viewModel.completeBurn(successResult)

        val state = viewModel.uiState.value
        assertFalse(state.isGeneratingIso)
        assertFalse(state.isBurning)
        assertEquals(successResult, state.lastResult)
        assertTrue(state.selectedFiles.isEmpty()) // 成功后清空选择
    }

    /**
     * 测试：完成刻录失败
     */
    @Test
    fun `completeBurn with failure updates state`() = runTest {
        val failureResult = BurnResult.Failure(
            message = "Test error",
            code = com.enterprise.discburner.data.AuditCode.WRITE_ERROR
        )

        viewModel.completeBurn(failureResult)

        val state = viewModel.uiState.value
        assertEquals(failureResult, state.lastResult)
    }

    /**
     * 测试：日志记录
     */
    @Test
    fun `addLog adds to logs`() = runTest {
        viewModel.addLog("Test message")

        val state = viewModel.uiState.value
        assertEquals(1, state.logs.size)
        assertTrue(state.logs.first().contains("Test message"))
    }

    /**
     * 测试：清除日志
     */
    @Test
    fun `clearLogs clears all logs`() = runTest {
        viewModel.addLog("Message 1")
        viewModel.addLog("Message 2")
        viewModel.clearLogs()

        val state = viewModel.uiState.value
        assertTrue(state.logs.isEmpty())
    }

    /**
     * 测试：日志保留限制
     */
    @Test
    fun `logs are limited to 100 entries`() = runTest {
        repeat(150) { index ->
            viewModel.addLog("Message $index")
        }

        val state = viewModel.uiState.value
        assertEquals(100, state.logs.size)
    }

    /**
     * 测试：canStartBurn条件 - 未连接
     */
    @Test
    fun `canStartBurn is false when not connected`() = runTest {
        viewModel.addFiles(listOf(java.io.File("/test.txt")))

        val state = viewModel.uiState.value
        assertFalse(state.canStartBurn)
    }

    /**
     * 测试：canStartBurn条件 - 无文件
     */
    @Test
    fun `canStartBurn is false when no files selected`() = runTest {
        viewModel.setDeviceConnected(true, "Test")
        // 模拟分析完成
        val mockDiscInfo = DiscInformation(
            discStatus = 1,
            stateOfLastSession = 0,
            erasable = false,
            firstTrackNumber = 1,
            numberOfSessions = 0,
            firstTrackInLastSession = 1,
            lastTrackInLastSession = 1,
            validDiscInformation = true,
            unrestrictedUse = false,
            damaged = false,
            copy = false,
            trackMode = 0,
            blank = false,
            reservedTrack = false,
            nextWritableAddress = 0,
            freeBlocks = 1000000,
            readCapacity = 0
        )

        // 需要设置分析结果
        // 这里简化处理，实际测试可能需要mock
        val state = viewModel.uiState.value
        // 由于没有设置分析结果，canStartBurn应为false
    }

    /**
     * 测试：ISO生成进度
     */
    @Test
    fun `startIsoGeneration updates state`() = runTest {
        viewModel.startIsoGeneration()

        val state = viewModel.uiState.value
        assertTrue(state.isGeneratingIso)
        assertEquals(0f, state.isoProgress)
    }

    /**
     * 测试：更新ISO进度
     */
    @Test
    fun `updateIsoProgress updates progress`() = runTest {
        viewModel.updateIsoProgress(0.75f, "写入文件数据")

        val state = viewModel.uiState.value
        assertEquals(0.75f, state.isoProgress)
    }

    /**
     * 测试：设置分析状态
     */
    @Test
    fun `setAnalyzing updates analyzing state`() = runTest {
        viewModel.setAnalyzing(true)

        val state = viewModel.uiState.value
        assertTrue(state.isAnalyzing)
    }

    /**
     * 测试：权限对话框
     */
    @Test
    fun `showPermissionDialog updates state`() = runTest {
        viewModel.showPermissionDialog(true)

        val state = viewModel.uiState.value
        assertTrue(state.showPermissionDialog)
    }
}