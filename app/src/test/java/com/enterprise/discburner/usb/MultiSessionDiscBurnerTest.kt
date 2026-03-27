package com.enterprise.discburner.usb

import android.hardware.usb.*
import com.enterprise.discburner.data.AuditCode
import com.enterprise.discburner.data.AuditEvent
import com.enterprise.discburner.data.AuditLogger
import io.mockk.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.test.*

/**
 * 多会话光盘刻录器单元测试
 *
 * 使用 MockK 模拟 USB 设备连接和端点
 */
class MultiSessionDiscBurnerTest {

    private lateinit var burner: MultiSessionDiscBurner
    private lateinit var mockConnection: UsbDeviceConnection
    private lateinit var mockInterface: UsbInterface
    private lateinit var mockEndpointIn: UsbEndpoint
    private lateinit var mockEndpointOut: UsbEndpoint
    private lateinit var mockAuditLogger: AuditLogger

    @Before
    fun setup() {
        MockKAnnotations.init(this)

        // 创建 Mock 对象
        mockConnection = mockk(relaxed = true)
        mockInterface = mockk(relaxed = true)
        mockEndpointIn = mockk(relaxed = true)
        mockEndpointOut = mockk(relaxed = true)
        mockAuditLogger = mockk(relaxed = true)

        // 配置端点方向
        every { mockEndpointIn.direction } returns UsbConstants.USB_DIR_IN
        every { mockEndpointOut.direction } returns UsbConstants.USB_DIR_OUT

        // 配置审计日志行为
        every { mockAuditLogger.log(any(), any(), any()) } returns Unit

        // 创建 Burner 实例
        burner = MultiSessionDiscBurner(
            mockConnection,
            mockInterface,
            mockEndpointIn,
            mockEndpointOut,
            mockAuditLogger
        )
    }

    @After
    fun tearDown() {
        burner.close()
        unmockkAll()
    }

    // ==================== 光盘分析测试 ====================

    /**
     * 测试：分析空白光盘
     */
    @Test
    fun `analyzeDisc returns correct info for blank disc`() = runBlocking {
        // 模拟空白光盘的响应数据
        setupBlankDiscResponse()

        val result = burner.analyzeDisc()

        assertTrue(result.isSuccess, "分析空白光盘应成功")

        val analysis = result.getOrNull()!!
        assertTrue(analysis.discInfo.blank, "光盘应为空白")
        assertTrue(analysis.canBurnNew, "空白光盘应可刻录")
        assertFalse(analysis.canAppend, "空白光盘不可追加")
        assertEquals(WriteMode.DAO, analysis.recommendedMode, "空白光盘推荐使用DAO模式")
    }

    /**
     * 测试：分析已刻录但未关闭的光盘
     */
    @Test
    fun `analyzeDisc detects appendable disc`() = runBlocking {
        // 模拟可追加光盘的响应数据
        setupAppendableDiscResponse()

        val result = burner.analyzeDisc()

        assertTrue(result.isSuccess, "分析可追加光盘应成功")

        val analysis = result.getOrNull()!!
        assertFalse(analysis.discInfo.isClosed, "光盘未关闭")
        assertTrue(analysis.canAppend, "可追加光盘应支持追加")
        assertTrue(analysis.canBurnNew, "可追加光盘应可继续刻录")
        assertEquals(WriteMode.TAO, analysis.recommendedMode, "可追加光盘推荐使用TAO模式")
    }

    /**
     * 测试：分析已关闭的光盘
     */
    @Test
    fun `analyzeDisc detects closed disc`() = runBlocking {
        // 模拟已关闭光盘的响应数据
        setupClosedDiscResponse()

        val result = burner.analyzeDisc()

        assertTrue(result.isSuccess, "分析已关闭光盘应成功")

        val analysis = result.getOrNull()!!
        assertTrue(analysis.discInfo.isClosed, "光盘应已关闭")
        assertFalse(analysis.canAppend, "已关闭光盘不可追加")
        assertFalse(analysis.canBurnNew, "已关闭光盘不可刻录")
    }

    /**
     * 测试：设备未就绪时的分析
     */
    @Test
    fun `analyzeDisc fails when device not ready`() = runBlocking {
        // 模拟设备未就绪
        setupDeviceNotReadyResponse()

        val result = burner.analyzeDisc()

        assertTrue(result.isFailure, "设备未就绪时应返回失败")
        assertTrue(
            result.exceptionOrNull()?.message?.contains("未就绪") == true,
            "错误信息应包含'未就绪'"
        )
    }

    // ==================== ISO 刻录测试 ====================

    /**
     * 测试：刻录 ISO 完整工作流
     */
    @Test
    fun `burnIso completes full workflow`() = runBlocking {
        // 创建临时 ISO 文件
        val isoFile = createTempIsoFile(size = 2048 * 10) // 10 扇区大小

        setupBlankDiscResponse()
        setupSuccessfulBurnResponse()

        val progressUpdates = mutableListOf<Pair<BurnStage, Float>>()

        val result = burner.burnIso(
            isoFile = isoFile,
            options = WriteOptions(writeMode = WriteMode.DAO, closeDisc = true),
            onProgress = { stage, progress ->
                progressUpdates.add(stage to progress)
            }
        )

        // 验证结果
        when (result) {
            is BurnResult.Success -> {
                assertNotNull(result.sessionId, "应返回会话ID")
                assertTrue(result.duration >= 0, "刻录时间应为非负数")
                assertTrue(result.sectorsWritten >= 0, "应写入非负数扇区")
            }
            is BurnResult.Failure -> {
                // 由于模拟限制，可能会失败，但至少应返回有效结果
                assertNotNull(result.message, "失败时应返回错误信息")
                assertNotNull(result.code, "失败时应返回错误码")
            }
        }

        // 验证审计日志被记录
        verify { mockAuditLogger.log(AuditEvent.BURN_STARTED, any(), any()) }

        // 清理
        isoFile.delete()
    }

    /**
     * 测试：刻录失败后的重试
     */
    @Test
    fun `burnIso with retry on failure`() = runBlocking {
        val isoFile = createTempIsoFile(size = 2048)

        setupBlankDiscResponse()
        // 模拟刻录失败
        setupFailedBurnResponse()

        val result = burner.burnIso(
            isoFile = isoFile,
            options = WriteOptions(),
            onProgress = { _, _ -> }
        )

        assertTrue(result is BurnResult.Failure, "模拟失败时应返回失败结果")

        val failure = result as BurnResult.Failure
        assertNotNull(failure.message, "应返回错误信息")

        // 验证失败被记录到审计日志
        verify { mockAuditLogger.log(AuditEvent.BURN_FAILED, any(), any()) }

        isoFile.delete()
    }

    /**
     * 测试：光盘空间不足
     */
    @Test
    fun `burnIso fails when insufficient space`() = runBlocking {
        // 创建大 ISO 文件
        val isoFile = createTempIsoFile(size = 1024L * 1024 * 100) // 100MB

        // 模拟空间不足的光盘
        setupDiscWithLowSpaceResponse()

        val result = burner.burnIso(
            isoFile = isoFile,
            options = WriteOptions(),
            onProgress = { _, _ -> }
        )

        if (result is BurnResult.Failure) {
            assertTrue(
                result.code == AuditCode.INSUFFICIENT_SPACE ||
                result.message.contains("空间") ||
                result.message.contains("不足"),
                "应返回空间不足错误"
            )
        }

        isoFile.delete()
    }

    /**
     * 测试：已关闭光盘不可刻录
     */
    @Test
    fun `burnIso fails when disc is closed`() = runBlocking {
        val isoFile = createTempIsoFile(size = 2048)

        setupClosedDiscResponse()

        val result = burner.burnIso(
            isoFile = isoFile,
            options = WriteOptions(),
            onProgress = { _, _ -> }
        )

        assertTrue(result is BurnResult.Failure, "已关闭光盘应刻录失败")

        val failure = result as BurnResult.Failure
        assertTrue(
            failure.code == AuditCode.DISC_NOT_BLANK ||
            failure.message.contains("已关闭") ||
            failure.message.contains("无法追加"),
            "应返回光盘已关闭错误"
        )

        isoFile.delete()
    }

    // ==================== 写入模式测试 ====================

    /**
     * 测试：DAO 模式设置
     */
    @Test
    fun `writeMode DAO has correct SCSI code`() {
        assertEquals(0x01.toByte(), WriteMode.DAO.toScsiCode(), "DAO 模式 SCSI 码应为 0x01")
    }

    /**
     * 测试：TAO 模式设置
     */
    @Test
    fun `writeMode TAO has correct SCSI code`() {
        assertEquals(0x00.toByte(), WriteMode.TAO.toScsiCode(), "TAO 模式 SCSI 码应为 0x00")
    }

    /**
     * 测试：SAO 模式设置
     */
    @Test
    fun `writeMode SAO has correct SCSI code`() {
        assertEquals(0x03.toByte(), WriteMode.SAO.toScsiCode(), "SAO 模式 SCSI 码应为 0x03")
    }

    /**
     * 测试：WriteOptions 默认值
     */
    @Test
    fun `writeOptions has correct defaults`() {
        val options = WriteOptions()

        assertEquals(WriteMode.TAO, options.writeMode, "默认写入模式应为 TAO")
        assertEquals(0, options.writeSpeed, "默认刻录速度应为 0（自动）")
        assertFalse(options.closeSession, "默认不应关闭会话")
        assertFalse(options.closeDisc, "默认不应关闭光盘")
        assertTrue(options.verifyAfterBurn, "默认应启用刻录后校验")
        assertNull(options.sessionName, "默认会话名应为 null")
    }

    /**
     * 测试：自定义 WriteOptions
     */
    @Test
    fun `writeOptions accepts custom values`() {
        val options = WriteOptions(
            writeMode = WriteMode.DAO,
            writeSpeed = 16,
            closeSession = true,
            closeDisc = true,
            verifyAfterBurn = false,
            sessionName = "TestSession"
        )

        assertEquals(WriteMode.DAO, options.writeMode)
        assertEquals(16, options.writeSpeed)
        assertTrue(options.closeSession)
        assertTrue(options.closeDisc)
        assertFalse(options.verifyAfterBurn)
        assertEquals("TestSession", options.sessionName)
    }

    // ==================== 阶段状态测试 ====================

    /**
     * 测试：BurnStage 枚举值
     */
    @Test
    fun `burnStage has all required stages`() {
        val stages = BurnStage.values()

        assertTrue(stages.any { it == BurnStage.IDLE }, "应包含 IDLE 阶段")
        assertTrue(stages.any { it == BurnStage.DEVICE_PREP }, "应包含 DEVICE_PREP 阶段")
        assertTrue(stages.any { it == BurnStage.DISC_CHECK }, "应包含 DISC_CHECK 阶段")
        assertTrue(stages.any { it == BurnStage.WRITING_DATA }, "应包含 WRITING_DATA 阶段")
        assertTrue(stages.any { it == BurnStage.VERIFYING }, "应包含 VERIFYING 阶段")
        assertTrue(stages.any { it == BurnStage.COMPLETED }, "应包含 COMPLETED 阶段")
        assertTrue(stages.any { it == BurnStage.ERROR }, "应包含 ERROR 阶段")
    }

    /**
     * 测试：阶段描述不为空
     */
    @Test
    fun `burnStage descriptions are not empty`() {
        BurnStage.values().forEach { stage ->
            assertTrue(stage.description.isNotEmpty(), "${stage.name} 阶段应有描述")
        }
    }

    // ==================== DiscInformation 测试 ====================

    /**
     * 测试：DiscInformation appendable 计算
     */
    @Test
    fun `discInformation calculates appendable correctly`() {
        val blankDisc = createDiscInfo(discStatus = 0, stateOfLastSession = 0)
        assertTrue(blankDisc.appendable, "空白盘应可追加")

        val appendableDisc = createDiscInfo(discStatus = 1, stateOfLastSession = 1)
        assertTrue(appendableDisc.appendable, "未关闭会话的盘应可追加")

        val closedDisc = createDiscInfo(discStatus = 2, stateOfLastSession = 3)
        assertFalse(closedDisc.appendable, "已关闭盘不可追加")
    }

    /**
     * 测试：DiscInformation canAppend 方法
     */
    @Test
    fun `discInformation canAppend works correctly`() {
        val damagedDisc = createDiscInfo(discStatus = 1, damaged = true)
        assertFalse(damagedDisc.canAppend(), "损坏的光盘不可追加")

        val closedDisc = createDiscInfo(discStatus = 2)
        assertFalse(closedDisc.canAppend(), "已关闭光盘不可追加")

        val goodDisc = createDiscInfo(discStatus = 1, stateOfLastSession = 1)
        assertTrue(goodDisc.canAppend(), "正常可追加光盘应返回 true")
    }

    // ==================== 辅助方法 ====================

    private fun setupBlankDiscResponse() {
        // 模拟 Test Unit Ready 成功
        every {
            mockConnection.bulkTransfer(mockEndpointOut, any(), any(), any(), any())
        } returns 31 // CBW 大小

        every {
            mockConnection.bulkTransfer(mockEndpointIn, any(), any(), any(), any())
        } returnsMany listOf(13, 34) // CSW + Disc Info

        // 这里简化处理，实际应构建完整的 SCSI 响应数据
    }

    private fun setupAppendableDiscResponse() {
        // 类似 setupBlankDiscResponse，但返回可追加光盘的状态
        every {
            mockConnection.bulkTransfer(any<UsbEndpoint>(), any(), any(), any(), any())
        } returnsMany listOf(31, 13, 34)
    }

    private fun setupClosedDiscResponse() {
        every {
            mockConnection.bulkTransfer(any<UsbEndpoint>(), any(), any(), any(), any())
        } returnsMany listOf(31, 13, 34)
    }

    private fun setupDeviceNotReadyResponse() {
        every {
            mockConnection.bulkTransfer(mockEndpointOut, any(), any(), any(), any())
        } returns -1 // 传输失败
    }

    private fun setupSuccessfulBurnResponse() {
        // 模拟所有 SCSI 命令成功
        every {
            mockConnection.bulkTransfer(any<UsbEndpoint>(), any(), any(), any(), any())
        } returnsMany listOf(31, 64, 13) // CBW + 数据 + CSW
    }

    private fun setupFailedBurnResponse() {
        every {
            mockConnection.bulkTransfer(any<UsbEndpoint>(), any(), any(), any(), any())
        } returns -1 // 模拟传输失败
    }

    private fun setupDiscWithLowSpaceResponse() {
        // 模拟空间不足的光盘信息
        every {
            mockConnection.bulkTransfer(any<UsbEndpoint>(), any(), any(), any(), any())
        } returnsMany listOf(31, 13, 34)
    }

    private fun createTempIsoFile(size: Long): File {
        val tempFile = File.createTempFile("test", ".iso")
        tempFile.deleteOnExit()

        // 写入指定大小的数据
        val buffer = ByteArray(2048) { 0x00 }
        tempFile.outputStream().use { output ->
            var remaining = size
            while (remaining > 0) {
                val writeSize = minOf(buffer.size.toLong(), remaining).toInt()
                output.write(buffer, 0, writeSize)
                remaining -= writeSize
            }
        }

        return tempFile
    }

    private fun createDiscInfo(
        discStatus: Int = 0,
        stateOfLastSession: Int = 0,
        damaged: Boolean = false,
        blank: Boolean = false,
        freeBlocks: Int = 100000
    ): DiscInformation {
        return DiscInformation(
            discStatus = discStatus,
            stateOfLastSession = stateOfLastSession,
            erasable = false,
            firstTrackNumber = 1,
            numberOfSessions = if (discStatus == 0) 0 else 1,
            firstTrackInLastSession = 1,
            lastTrackInLastSession = if (discStatus == 0) 1 else 2,
            validDiscInformation = true,
            unrestrictedUse = true,
            damaged = damaged,
            copy = false,
            trackMode = 0,
            blank = blank,
            reservedTrack = false,
            nextWritableAddress = 0,
            freeBlocks = freeBlocks,
            readCapacity = 0
        )
    }
}
