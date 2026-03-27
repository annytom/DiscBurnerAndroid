package com.enterprise.discburner.usb

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse

/**
 * SCSI命令单元测试
 */
class ScsiCommandsTest {

    /**
     * 测试：INQUIRY命令格式
     */
    @Test
    fun `createInquiryCommand returns correct format`() {
        val command = ScsiCommands.createInquiryCommand()

        assertEquals(6, command.size, "INQUIRY命令应为6字节")
        assertEquals(ScsiCommands.INQUIRY, command[0], "操作码应为INQUIRY (0x12)")
        assertEquals(0x00.toByte(), command[1], "EVPD应为0")
        assertEquals(0x24.toByte(), command[4], "分配长度应为36字节")
        assertEquals(0x00.toByte(), command[5], "控制字节应为0")
    }

    /**
     * 测试：自定义INQUIRY长度
     */
    @Test
    fun `createInquiryCommand with custom length`() {
        val command = ScsiCommands.createInquiryCommand(0xFF.toByte())

        assertEquals(0xFF.toByte(), command[4], "应使用自定义分配长度")
    }

    /**
     * 测试：TEST UNIT READY命令
     */
    @Test
    fun `createTestUnitReadyCommand returns correct format`() {
        val command = ScsiCommands.createTestUnitReadyCommand()

        assertEquals(6, command.size, "命令应为6字节")
        assertEquals(ScsiCommands.TEST_UNIT_READY, command[0], "操作码应为TEST UNIT READY (0x00)")
        assertTrue(command.slice(1..4).all { it == 0x00.toByte() }, "保留字节应为0")
        assertEquals(0x00.toByte(), command[5], "控制字节应为0")
    }

    /**
     * 测试：READ CAPACITY命令
     */
    @Test
    fun `createReadCapacityCommand returns correct format`() {
        val command = ScsiCommands.createReadCapacityCommand()

        assertEquals(10, command.size, "READ CAPACITY应为10字节")
        assertEquals(ScsiCommands.READ_CAPACITY_10, command[0], "操作码应为READ CAPACITY (0x25)")
    }

    /**
     * 测试：WRITE(10)命令 - 基本参数
     */
    @Test
    fun `createWrite10Command with basic parameters`() {
        val lba = 1234
        val length: Short = 64

        val command = ScsiCommands.createWrite10Command(lba, length)

        assertEquals(10, command.size, "WRITE(10)应为10字节")
        assertEquals(ScsiCommands.WRITE_10, command[0], "操作码应为WRITE(10) (0x2A)")
        assertEquals(0x00.toByte(), command[1], "FUA应为0")

        // 验证LBA (Big Endian)
        assertEquals(((lba shr 24) and 0xFF).toByte(), command[2])
        assertEquals(((lba shr 16) and 0xFF).toByte(), command[3])
        assertEquals(((lba shr 8) and 0xFF).toByte(), command[4])
        assertEquals((lba and 0xFF).toByte(), command[5])

        // 验证传输长度 (Big Endian)
        assertEquals(((length.toInt() shr 8) and 0xFF).toByte(), command[7])
        assertEquals((length.toInt() and 0xFF).toByte(), command[8])
    }

    /**
     * 测试：WRITE(10)命令 - FUA标志
     */
    @Test
    fun `createWrite10Command with FUA enabled`() {
        val command = ScsiCommands.createWrite10Command(0, 1, fua = true)

        assertEquals(0x08.toByte(), command[1], "FUA位应设置 (bit 3)")
    }

    /**
     * 测试：READ(10)命令格式
     */
    @Test
    fun `createRead10Command returns correct format`() {
        val lba = 5678
        val length: Short = 32

        val command = ScsiCommands.createRead10Command(lba, length)

        assertEquals(10, command.size, "READ(10)应为10字节")
        assertEquals(ScsiCommands.READ_10, command[0], "操作码应为READ(10) (0x28)")
        assertEquals(0x00.toByte(), command[1], "标志应为0")

        // 验证LBA
        assertEquals(((lba shr 24) and 0xFF).toByte(), command[2])
        assertEquals((lba and 0xFF).toByte(), command[5])

        // 验证长度
        assertEquals(((length.toInt() shr 8) and 0xFF).toByte(), command[7])
        assertEquals((length.toInt() and 0xFF).toByte(), command[8])
    }

    /**
     * 测试：RESERVE TRACK命令
     */
    @Test
    fun `createReserveTrackCommand with large size`() {
        val size: Long = 0x12345678

        val command = ScsiCommands.createReserveTrackCommand(size)

        assertEquals(10, command.size, "RESERVE TRACK应为10字节")
        assertEquals(ScsiCommands.RESERVE_TRACK, command[0], "操作码应为RESERVE TRACK (0x53)")

        // 验证预留大小 (Big Endian)
        assertEquals(((size shr 24) and 0xFF).toByte(), command[4])
        assertEquals(((size shr 16) and 0xFF).toByte(), command[5])
        assertEquals(((size shr 8) and 0xFF).toByte(), command[6])
        assertEquals((size and 0xFF).toByte(), command[7])
    }

    /**
     * 测试：CLOSE TRACK/SESSION命令
     */
    @Test
    fun `createCloseTrackSessionCommand for track`() {
        val command = ScsiCommands.createCloseTrackSessionCommand(closeSession = false)

        assertEquals(10, command.size, "命令应为10字节")
        assertEquals(ScsiCommands.CLOSE_TRACK_SESSION, command[0], "操作码应为CLOSE TRACK/SESSION (0x5B)")
        assertEquals(0x01.toByte(), command[1], "类型应为1 (关闭轨道)")
    }

    @Test
    fun `createCloseTrackSessionCommand for session`() {
        val command = ScsiCommands.createCloseTrackSessionCommand(closeSession = true)

        assertEquals(0x02.toByte(), command[1], "类型应为2 (关闭会话)")
    }

    /**
     * 测试：SYNCHRONIZE CACHE命令
     */
    @Test
    fun `createSynchronizeCacheCommand returns correct format`() {
        val command = ScsiCommands.createSynchronizeCacheCommand()

        assertEquals(10, command.size, "命令应为10字节")
        assertEquals(ScsiCommands.SYNCHRONIZE_CACHE_10, command[0], "操作码应为SYNCHRONIZE CACHE (0x35)")
    }

    /**
     * 测试：READ DISC INFO命令
     */
    @Test
    fun `createReadDiscInfoCommand returns correct format`() {
        val command = ScsiCommands.createReadDiscInfoCommand()

        assertEquals(10, command.size, "命令应为10字节")
        assertEquals(ScsiCommands.READ_DISC_INFORMATION, command[0], "操作码应为READ DISC INFORMATION (0x51)")
        assertEquals(0x00.toByte(), command[1], "数据类型应为0")
        assertEquals(0x22.toByte(), command[7], "分配长度低字节应为34")
    }

    /**
     * 测试：常量值
     */
    @Test
    fun `command constants have correct values`() {
        assertEquals(0x00.toByte(), ScsiCommands.TEST_UNIT_READY)
        assertEquals(0x03.toByte(), ScsiCommands.REQUEST_SENSE)
        assertEquals(0x12.toByte(), ScsiCommands.INQUIRY)
        assertEquals(0x15.toByte(), ScsiCommands.MODE_SELECT_6)
        assertEquals(0x25.toByte(), ScsiCommands.READ_CAPACITY_10)
        assertEquals(0x28.toByte(), ScsiCommands.READ_10)
        assertEquals(0x2A.toByte(), ScsiCommands.WRITE_10)
        assertEquals(0x35.toByte(), ScsiCommands.SYNCHRONIZE_CACHE_10)
        assertEquals(0x51.toByte(), ScsiCommands.READ_DISC_INFORMATION)
        assertEquals(0x53.toByte(), ScsiCommands.RESERVE_TRACK)
        assertEquals(0x5B.toByte(), ScsiCommands.CLOSE_TRACK_SESSION)
    }

    /**
     * 测试：写入模式常量
     */
    @Test
    fun `write type constants have correct values`() {
        assertEquals(0x01, ScsiCommands.WRITE_TYPE_DAO)
        assertEquals(0x00, ScsiCommands.WRITE_TYPE_TAO)
        assertEquals(0x02, ScsiCommands.WRITE_TYPE_PACKET)
        assertEquals(0x03, ScsiCommands.WRITE_TYPE_SAO)
    }

    /**
     * 测试：扇区大小常量
     */
    @Test
    fun `sector size constants are correct`() {
        assertEquals(2048, ScsiCommands.SECTOR_SIZE)
        assertEquals(2352, ScsiCommands.SECTOR_SIZE_RAW)
    }

    /**
     * 测试：LBA边界值
     */
    @Test
    fun `createWrite10Command handles maximum LBA`() {
        val maxLba = 0x7FFFFFFF // DVD最大LBA
        val command = ScsiCommands.createWrite10Command(maxLba, 1)

        assertEquals(0x7F.toByte(), command[2])
        assertEquals(0xFF.toByte(), command[3])
        assertEquals(0xFF.toByte(), command[4])
        assertEquals(0xFF.toByte(), command[5])
    }

    /**
     * 测试：传输长度边界值
     */
    @Test
    fun `createWrite10Command handles maximum transfer length`() {
        val maxLength: Short = 0x7FFF // 最大32K扇区
        val command = ScsiCommands.createWrite10Command(0, maxLength)

        assertEquals(0x7F.toByte(), command[7])
        assertEquals(0xFF.toByte(), command[8])
    }
}