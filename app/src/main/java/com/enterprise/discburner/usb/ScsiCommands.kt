package com.enterprise.discburner.usb

/**
 * SCSI命令常量定义
 * 参考：SCSI Primary Commands (SPC) 和
 *       SCSI Multimedia Commands (MMC)
 */
object ScsiCommands {

    // ===== 基本命令 =====
    const val TEST_UNIT_READY: Byte = 0x00
    const val REQUEST_SENSE: Byte = 0x03
    const val INQUIRY: Byte = 0x12
    const val MODE_SELECT_6: Byte = 0x15
    const val MODE_SENSE_6: Byte = 0x1A
    const val START_STOP_UNIT: Byte = 0x1B
    const val PREVENT_ALLOW_MEDIUM_REMOVAL: Byte = 0x1E

    // ===== 读写命令 =====
    const val READ_CAPACITY_10: Byte = 0x25
    const val READ_10: Byte = 0x28
    const val WRITE_10: Byte = 0x2A
    const val SYNCHRONIZE_CACHE_10: Byte = 0x35

    // ===== 光盘特定命令 (MMC) =====
    const val READ_DISC_INFORMATION: Byte = 0x51
    const val READ_TRACK_INFORMATION: Byte = 0x52
    const val RESERVE_TRACK: Byte = 0x53
    const val CLOSE_TRACK_SESSION: Byte = 0x5B
    const val READ_BUFFER_CAPACITY: Byte = 0x5C
    const val BLANK: Byte = 0xA1

    // ===== 写模式 =====
    const val WRITE_TYPE_DAO = 0x01      // Disc At Once
    const val WRITE_TYPE_TAO = 0x00      // Track At Once
    const val WRITE_TYPE_PACKET = 0x02   // 包写入
    const val WRITE_TYPE_SAO = 0x03      // Session At Once

    // ===== 块长度 =====
    const val SECTOR_SIZE = 2048         // DVD-ROM Mode 1
    const val SECTOR_SIZE_RAW = 2352     // 原始扇区大小 (包含纠错)

    /**
     * 创建INQUIRY命令
     * @param allocationLength 返回数据长度
     */
    fun createInquiryCommand(allocationLength: Byte = 0x24): ByteArray {
        return byteArrayOf(
            INQUIRY,
            0x00,               // EVPD = 0 (标准查询)
            0x00,               // Page Code
            0x00,               // Reserved
            allocationLength,   // Allocation Length
            0x00                // Control
        )
    }

    /**
     * 创建TEST UNIT READY命令
     */
    fun createTestUnitReadyCommand(): ByteArray {
        return byteArrayOf(
            TEST_UNIT_READY,
            0x00, 0x00, 0x00, 0x00,  // Reserved
            0x00                       // Control
        )
    }

    /**
     * 创建READ CAPACITY命令
     */
    fun createReadCapacityCommand(): ByteArray {
        return byteArrayOf(
            READ_CAPACITY_10,
            0x00, 0x00, 0x00, 0x00,  // LBA (0 = 当前位置)
            0x00, 0x00, 0x00,          // Reserved
            0x00                       // Control
        )
    }

    /**
     * 创建READ DISC INFORMATION命令
     */
    fun createReadDiscInfoCommand(): ByteArray {
        return byteArrayOf(
            READ_DISC_INFORMATION,
            0x00,               // Data Type = 0 (标准信息)
            0x00, 0x00,         // Reserved
            0x00,               // Reserved
            0x22,               // Allocation Length = 34 bytes
            0x00                // Control
        )
    }

    /**
     * 创建MODE SELECT命令 (用于设置DAO模式)
     */
    fun createModeSelectDaoCommand(): ByteArray {
        val blockDesc = byteArrayOf(
            0x00, 0x00, 0x00, 0x00,  // 块地址
            0x00, 0x00, 0x08, 0x00   // 块长度 = 2048
        )

        val writeParamsPage = byteArrayOf(
            0x05,               // Page Code = Write Parameters
            0x32,               // Page Length = 50
            0x00, 0x00,         // Reserved
            WRITE_TYPE_DAO.toByte(),  // Write Type = DAO
            0x00,               // Multi-session = 0 (关闭)
            // ... 其他参数省略，实际使用时需要完整设置
        )

        val paramListLen = blockDesc.size + writeParamsPage.size + 4

        return byteArrayOf(
            MODE_SELECT_6,
            0x10,               // PF = 1 (遵循SCSI-2页格式)
            0x00,               // Reserved
            0x00,               // Reserved
            paramListLen.toByte(),
            0x00                // Control
        ) + blockDesc + writeParamsPage
    }

    /**
     * 创建RESERVE TRACK命令
     * @param reservationSize 预留大小（扇区数）
     */
    fun createReserveTrackCommand(reservationSize: Long): ByteArray {
        return byteArrayOf(
            RESERVE_TRACK,
            0x00,               // Reserved
            0x00, 0x00, 0x00, 0x00,  // LBA (0 = 自动分配)
            (reservationSize ushr 24).toByte(),
            (reservationSize ushr 16).toByte(),
            (reservationSize ushr 8).toByte(),
            reservationSize.toByte(),
            0x00, 0x00,         // Reserved
            0x00                // Control
        )
    }

    /**
     * 创建WRITE (10)命令
     * @param lba 逻辑块地址
     * @param transferLength 传输长度（扇区数）
     * @param fua Force Unit Access标志
     */
    fun createWrite10Command(
        lba: Int,
        transferLength: Short,
        fua: Boolean = false
    ): ByteArray {
        return byteArrayOf(
            WRITE_10,
            (if (fua) 0x08 else 0x00).toByte(),  // FUA bit
            (lba ushr 24).toByte(),
            (lba ushr 16).toByte(),
            (lba ushr 8).toByte(),
            lba.toByte(),
            0x00,               // Group Number
            (transferLength.toInt() ushr 8).toByte(),
            transferLength.toByte(),
            0x00                // Control
        )
    }

    /**
     * 创建READ (10)命令（用于校验）
     */
    fun createRead10Command(
        lba: Int,
        transferLength: Short
    ): ByteArray {
        return byteArrayOf(
            READ_10,
            0x00,               // Flags
            (lba ushr 24).toByte(),
            (lba ushr 16).toByte(),
            (lba ushr 8).toByte(),
            lba.toByte(),
            0x00,               // Group Number
            (transferLength.toInt() ushr 8).toByte(),
            transferLength.toByte(),
            0x00                // Control
        )
    }

    /**
     * 创建SYNCHRONIZE CACHE命令
     */
    fun createSynchronizeCacheCommand(): ByteArray {
        return byteArrayOf(
            SYNCHRONIZE_CACHE_10,
            0x00,               // Flags (Immediate = 0)
            0x00, 0x00, 0x00, 0x00,  // LBA
            0x00,               // Reserved
            0x00, 0x00,         // Number of Blocks (0 = 全部)
            0x00                // Control
        )
    }

    /**
     * 创建CLOSE TRACK/SESSION命令
     * @param closeSession true = 关闭会话, false = 关闭轨道
     */
    fun createCloseTrackSessionCommand(closeSession: Boolean = true): ByteArray {
        return byteArrayOf(
            CLOSE_TRACK_SESSION,
            (if (closeSession) 0x02 else 0x01).toByte(),  // Type
            0x00, 0x00,         // Reserved
            0x00, 0x00, 0x00, 0x00,  // Track Number (0 = 当前)
            0x00, 0x00,         // Reserved
            0x00                // Control
        )
    }

    /**
     * 创建START STOP UNIT命令（用于弹出光盘）
     * @param load true = 加载, false = 弹出
     */
    fun createStartStopCommand(load: Boolean = false): ByteArray {
        return byteArrayOf(
            START_STOP_UNIT,
            0x00,               // Reserved
            0x00,               // Reserved
            0x00,               // Reserved
            (if (load) 0x03 else 0x02).toByte(),  // Start + LoEj
            0x00                // Control
        )
    }
}

/**
 * 数据传输方向
 */
enum class Direction(val value: Int) {
    NONE(0),
    IN(1),
    OUT(2)
}
