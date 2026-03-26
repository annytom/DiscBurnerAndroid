package com.enterprise.discburner.usb

import android.hardware.usb.*
import com.enterprise.discburner.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlin.math.min

/**
 * 刻录阶段
 */
enum class BurnStage(val description: String) {
    IDLE("空闲"),
    DEVICE_PREP("设备准备"),
    DISC_CHECK("光盘检查"),
    LEAD_IN("写入Lead-in"),
    WRITING_DATA("写入数据"),
    LEAD_OUT("写入Lead-out"),
    FINALIZING("关闭光盘"),
    VERIFYING("校验数据"),
    COMPLETED("完成"),
    ERROR("错误")
}

/**
 * 光盘信息
 */
data class DiscInformation(
    val discStatus: Int,
    val stateOfLastSession: Int,
    val erasable: Boolean,
    val firstTrackNumber: Int,
    val numberOfSessions: Int,
    val firstTrackInLastSession: Int,
    val lastTrackInLastSession: Int,
    val validDiscInformation: Boolean,
    val unrestrictedUse: Boolean,
    val damaged: Boolean,
    val copy: Boolean,
    val trackMode: Int,
    val blank: Boolean,
    val reservedTrack: Boolean,
    val nextWritableAddress: Int,
    val freeBlocks: Int,
    val readCapacity: Long
) {
    val appendable: Boolean
        get() = discStatus == 1 || discStatus == 2  // 追加或未完整

    val remainingSectors: Long
        get() = freeBlocks.toLong()
}

/**
 * DAO模式光盘刻录器
 * 支持DAO模式刻录、SHA256自动校验、审计日志
 */
class DaoDiscBurner(
    private val usbConnection: UsbDeviceConnection,
    private val usbInterface: UsbInterface,
    private val endpointIn: UsbEndpoint,
    private val endpointOut: UsbEndpoint,
    private val auditLogger: AuditLogger
) {
    private val TAG = "DaoDiscBurner"

    // 缓冲区大小：64KB（32个扇区）
    private val BUFFER_SIZE = 64 * 1024
    private val SECTOR_SIZE = ScsiCommands.SECTOR_SIZE

    private val _stage = MutableStateFlow(BurnStage.IDLE)
    val stage: StateFlow<BurnStage> = _stage

    private var currentSessionId: String? = null

    /**
     * 生成会话ID
     */
    private fun generateSessionId(): String {
        return "BURN-${System.currentTimeMillis()}-${(1000..9999).random()}"
    }

    /**
     * 执行完整的DAO刻录流程
     */
    suspend fun burnIsoDao(
        isoFile: File,
        onProgress: (BurnStage, Float) -> Unit
    ): BurnResult = withContext(Dispatchers.IO) {

        currentSessionId = generateSessionId()
        val sessionId = currentSessionId!!
        val startTime = System.currentTimeMillis()

        auditLogger.log(
            AuditEvent.BURN_STARTED, sessionId,
            mapOf(
                "fileName" to isoFile.name,
                "fileSize" to isoFile.length(),
                "filePath" to isoFile.absolutePath
            )
        )

        try {
            // ===== Phase 1: 设备与光盘验证 =====
            _stage.value = BurnStage.DEVICE_PREP
            onProgress(BurnStage.DEVICE_PREP, 0.05f)

            if (!testUnitReady()) {
                auditLogger.log(AuditEvent.BURN_FAILED, sessionId,
                    mapOf("code" to AuditCode.MODE_SELECT_FAILED))
                return@withContext BurnResult.Failure(
                    "设备未就绪，请检查刻录机连接",
                    AuditCode.MODE_SELECT_FAILED
                )
            }

            // 设置DAO模式
            if (!sendModeSelectDao()) {
                auditLogger.log(AuditEvent.BURN_FAILED, sessionId,
                    mapOf("code" to AuditCode.MODE_SELECT_FAILED))
                return@withContext BurnResult.Failure(
                    "无法设置DAO写入模式",
                    AuditCode.MODE_SELECT_FAILED
                )
            }

            // 读取光盘信息
            _stage.value = BurnStage.DISC_CHECK
            onProgress(BurnStage.DISC_CHECK, 0.08f)

            val discInfo = readDiscInformation()
                ?: run {
                    auditLogger.log(AuditEvent.BURN_FAILED, sessionId,
                        mapOf("code" to AuditCode.DISC_READ_FAILED))
                    return@withContext BurnResult.Failure(
                        "无法读取光盘信息，请检查光盘",
                        AuditCode.DISC_READ_FAILED
                    )
                }

            // 检查光盘状态
            if (discInfo.damaged) {
                auditLogger.log(AuditEvent.BURN_FAILED, sessionId,
                    mapOf("code" to AuditCode.DISC_READ_FAILED, "reason" to "光盘损坏"))
                return@withContext BurnResult.Failure(
                    "光盘损坏，请更换",
                    AuditCode.DISC_READ_FAILED
                )
            }

            if (!discInfo.erasable && !discInfo.blank && !discInfo.appendable) {
                auditLogger.log(AuditEvent.BURN_FAILED, sessionId,
                    mapOf("code" to AuditCode.DISC_NOT_BLANK))
                return@withContext BurnResult.Failure(
                    "光盘已关闭且不可写入，请使用空白光盘",
                    AuditCode.DISC_NOT_BLANK
                )
            }

            // 计算所需扇区数
            val fileSize = isoFile.length()
            val requiredSectors = (fileSize + SECTOR_SIZE - 1) / SECTOR_SIZE

            auditLogger.log(AuditEvent.DEVICE_CONNECTED, sessionId,
                mapOf(
                    "discStatus" to discInfo.discStatus,
                    "blank" to discInfo.blank,
                    "erasable" to discInfo.erasable,
                    "remainingSectors" to discInfo.remainingSectors,
                    "requiredSectors" to requiredSectors
                )
            )

            // 检查空间
            if (requiredSectors > discInfo.remainingSectors) {
                auditLogger.log(AuditEvent.BURN_FAILED, sessionId,
                    mapOf(
                        "code" to AuditCode.INSUFFICIENT_SPACE,
                        "required" to requiredSectors,
                        "available" to discInfo.remainingSectors
                    ))
                return@withContext BurnResult.Failure(
                    "空间不足: 需要${requiredSectors}扇区(${formatSize(requiredSectors * SECTOR_SIZE)}), " +
                            "光盘剩余${discInfo.remainingSectors}扇区(${formatSize(discInfo.remainingSectors * SECTOR_SIZE)})",
                    AuditCode.INSUFFICIENT_SPACE
                )
            }

            // ===== Phase 2: 预留轨道（DAO模式）=====
            _stage.value = BurnStage.LEAD_IN
            onProgress(BurnStage.LEAD_IN, 0.10f)

            // 预留轨道空间
            if (!reserveTrack(requiredSectors)) {
                auditLogger.log(AuditEvent.BURN_FAILED, sessionId,
                    mapOf("code" to AuditCode.RESERVE_TRACK_FAILED))
                return@withContext BurnResult.Failure(
                    "无法预留轨道空间",
                    AuditCode.RESERVE_TRACK_FAILED
                )
            }

            // ===== Phase 3: 数据写入 =====
            _stage.value = BurnStage.WRITING_DATA

            val sha256Digest = MessageDigest.getInstance("SHA-256")
            var bytesWritten = 0L
            var sectorsWritten = 0L
            val lbaStart = discInfo.nextWritableAddress

            isoFile.inputStream().use { input ->
                val buffer = ByteArray(BUFFER_SIZE)

                while (isActive) {
                    val read = input.read(buffer)
                    if (read <= 0) break

                    // 更新源文件哈希
                    sha256Digest.update(buffer, 0, read)

                    // 2KB扇区对齐
                    val alignedSize = if (read % SECTOR_SIZE == 0) {
                        read
                    } else {
                        ((read / SECTOR_SIZE) + 1) * SECTOR_SIZE
                    }

                    // 填充零
                    if (alignedSize > read) {
                        for (i in read until alignedSize) {
                            buffer[i] = 0
                        }
                    }

                    val sector = lbaStart + sectorsWritten
                    val sectorsToWrite = alignedSize / SECTOR_SIZE

                    // 写入扇区
                    if (!writeSectors(sector.toInt(), buffer, sectorsToWrite)) {
                        auditLogger.log(AuditEvent.BURN_FAILED, sessionId,
                            mapOf(
                                "code" to AuditCode.WRITE_ERROR,
                                "sector" to sector,
                                "sectorsWritten" to sectorsWritten,
                                "totalSectors" to requiredSectors
                            ))
                        return@withContext BurnResult.Failure(
                            "写入失败于扇区$sector，已写入$sectorsWritten/$requiredSectors扇区",
                            AuditCode.WRITE_ERROR
                        )
                    }

                    bytesWritten += read
                    sectorsWritten += sectorsToWrite

                    // 更新进度 (10% ~ 50%)
                    val progress = 0.10f + 0.40f * (sectorsWritten.toFloat() / requiredSectors)
                    onProgress(BurnStage.WRITING_DATA, progress)
                }
            }

            val sourceHash = sha256Digest.digest().toHexString()

            auditLogger.log(AuditEvent.BURN_COMPLETED, sessionId,
                mapOf(
                    "sectorsWritten" to sectorsWritten,
                    "bytesWritten" to bytesWritten,
                    "sourceHash" to sourceHash,
                    "duration" to (System.currentTimeMillis() - startTime)
                )
            )

            // ===== Phase 4: 关闭轨道与会话 =====
            _stage.value = BurnStage.LEAD_OUT
            onProgress(BurnStage.LEAD_OUT, 0.50f)

            // 同步缓存
            if (!synchronizeCache()) {
                auditLogger.log(AuditEvent.BURN_FAILED, sessionId,
                    mapOf("code" to AuditCode.CLOSE_SESSION_FAILED, "step" to "sync"))
            }

            // 关闭轨道
            if (!closeTrack()) {
                auditLogger.log(AuditEvent.BURN_FAILED, sessionId,
                    mapOf("code" to AuditCode.CLOSE_SESSION_FAILED))
                return@withContext BurnResult.Failure(
                    "无法关闭轨道",
                    AuditCode.CLOSE_SESSION_FAILED
                )
            }

            // 关闭会话（DAO模式下关闭整个光盘）
            if (!closeSession()) {
                auditLogger.log(AuditEvent.BURN_FAILED, sessionId,
                    mapOf("code" to AuditCode.FINALIZE_FAILED))
                return@withContext BurnResult.Failure(
                    "无法关闭会话（光盘最终化失败）",
                    AuditCode.FINALIZE_FAILED
                )
            }

            _stage.value = BurnStage.FINALIZING
            onProgress(BurnStage.FINALIZING, 0.55f)

            // DAO模式下光盘已不可追加
            auditLogger.log(AuditEvent.BURN_COMPLETED, sessionId,
                mapOf(
                    "finalized" to true,
                    "writeMode" to "DAO",
                    "sourceHash" to sourceHash
                )
            )

            // ===== Phase 5: 自动校验（关键！）=====
            _stage.value = BurnStage.VERIFYING
            auditLogger.log(AuditEvent.VERIFY_STARTED, sessionId,
                mapOf("expectedSectors" to sectorsWritten))

            val verifyResult = verifyDiscData(
                lbaStart,
                sectorsWritten,
                sourceHash
            ) { vProgress ->
                onProgress(BurnStage.VERIFYING, 0.55f + 0.45f * vProgress)
            }

            return@withContext when (verifyResult) {
                is VerifyResult.Success -> {
                    auditLogger.log(AuditEvent.VERIFY_PASSED, sessionId,
                        mapOf(
                            "verifiedHash" to verifyResult.hash,
                            "sectorsRead" to verifyResult.sectorsRead,
                            "duration" to (System.currentTimeMillis() - startTime)
                        ))

                    _stage.value = BurnStage.COMPLETED

                    BurnResult.Success(
                        sessionId = sessionId,
                        duration = System.currentTimeMillis() - startTime,
                        sourceHash = sourceHash,
                        verifiedHash = verifyResult.hash,
                        sectorsWritten = sectorsWritten
                    )
                }
                is VerifyResult.Failure -> {
                    auditLogger.log(AuditEvent.VERIFY_FAILED, sessionId,
                        mapOf(
                            "error" to verifyResult.error,
                            "sector" to verifyResult.failedAtSector,
                            "sourceHash" to sourceHash
                        ))

                    _stage.value = BurnStage.ERROR

                    // 校验失败 = 刻录失败！光盘不可信
                    BurnResult.Failure(
                        "数据校验失败: ${verifyResult.error} " +
                                "(失败扇区: ${verifyResult.failedAtSector})。\n" +
                                "此光盘可能包含错误数据，请勿用于备份。",
                        AuditCode.VERIFY_MISMATCH,
                        recoverable = false,
                        sessionId = sessionId
                    )
                }
            }

        } catch (e: Exception) {
            auditLogger.log(AuditEvent.EXCEPTION, sessionId,
                mapOf("error" to e.message, "stackTrace" to e.stackTraceToString()))

            _stage.value = BurnStage.ERROR

            BurnResult.Failure(
                "刻录过程中发生异常: ${e.message}",
                AuditCode.EXCEPTION
            )
        }
    }

    /**
     * 校验光盘数据
     */
    private suspend fun verifyDiscData(
        startLba: Int,
        sectorsToVerify: Long,
        expectedHash: String,
        onProgress: (Float) -> Unit
    ): VerifyResult = withContext(Dispatchers.IO) {

        val digest = MessageDigest.getInstance("SHA-256")
        var sectorsRead = 0L
        var currentLba = startLba
        val buffer = ByteArray(BUFFER_SIZE)

        try {
            while (sectorsRead < sectorsToVerify && isActive) {
                val sectorsInBatch = min(
                    buffer.size / SECTOR_SIZE,
                    (sectorsToVerify - sectorsRead).toInt()
                )

                if (!readSectors(currentLba, buffer, sectorsInBatch)) {
                    return@withContext VerifyResult.Failure(
                        "读取扇区失败",
                        currentLba
                    )
                }

                // 计算哈希（注意：最后一个batch可能不需要全部字节）
                val bytesToHash = if (sectorsRead + sectorsInBatch >= sectorsToVerify) {
                    // 最后一组，可能需要部分计算
                    val remainingSectors = (sectorsToVerify - sectorsRead).toInt()
                    remainingSectors * SECTOR_SIZE
                } else {
                    sectorsInBatch * SECTOR_SIZE
                }

                digest.update(buffer, 0, bytesToHash)

                sectorsRead += sectorsInBatch
                currentLba += sectorsInBatch

                onProgress(sectorsRead.toFloat() / sectorsToVerify)
            }

            val discHash = digest.digest().toHexString()

            if (discHash == expectedHash) {
                VerifyResult.Success(discHash, sectorsRead.toInt())
            } else {
                VerifyResult.Failure(
                    "哈希不匹配:\n源: $expectedHash\n盘: $discHash",
                    -1
                )
            }

        } catch (e: Exception) {
            VerifyResult.Failure("校验异常: ${e.message}", currentLba)
        }
    }

    /**
     * 发送SCSI命令（使用USB Mass Storage Bulk-Only Transport）
     */
    private fun sendCommand(
        command: ByteArray,
        dataTransfer: ByteArray? = null,
        dataDirection: Direction = Direction.NONE,
        timeoutMs: Int = 30000
    ): Boolean {

        // 构建CBW (Command Block Wrapper) - 31 bytes
        val cbw = buildCbw(command, dataTransfer?.size ?: 0, dataDirection)

        // 发送CBW
        val cbwSent = usbConnection.bulkTransfer(
            endpointOut, cbw, cbw.size, 5000
        )
        if (cbwSent != cbw.size) {
            return false
        }

        // 传输数据（如果需要）
        if (dataTransfer != null && dataDirection == Direction.OUT) {
            var offset = 0
            while (offset < dataTransfer.size) {
                val chunkSize = min(64 * 1024, dataTransfer.size - offset)
                val sent = usbConnection.bulkTransfer(
                    endpointOut,
                    dataTransfer,
                    offset,
                    chunkSize,
                    timeoutMs
                )
                if (sent < 0) return false
                offset += sent
            }
        } else if (dataDirection == Direction.IN && dataTransfer != null) {
            var offset = 0
            val toRead = dataTransfer.size
            while (offset < toRead) {
                val chunkSize = min(64 * 1024, toRead - offset)
                val received = usbConnection.bulkTransfer(
                    endpointIn,
                    dataTransfer,
                    offset,
                    chunkSize,
                    timeoutMs
                )
                if (received < 0) return false
                offset += received
            }
        }

        // 接收CSW (Command Status Wrapper) - 13 bytes
        val csw = ByteArray(13)
        val cswReceived = usbConnection.bulkTransfer(
            endpointIn, csw, csw.size, 5000
        )

        if (cswReceived != 13) {
            return false
        }

        // 验证CSW签名
        val signature = csw.copyOfRange(0, 4)
        if (!signature.contentEquals(byteArrayOf(0x55, 0x53, 0x42, 0x53))) {
            return false
        }

        // 检查状态 (bCSWStatus: 0=OK, 1=命令失败, 2=阶段错误)
        return csw[12] == 0x00.toByte()
    }

    /**
     * 构建CBW
     */
    private fun buildCbw(
        command: ByteArray,
        dataLength: Int,
        direction: Direction
    ): ByteArray {
        val cbw = ByteArray(31)

        // dCBWSignature: "USBC" (0x43425355)
        cbw[0] = 0x55.toByte()
        cbw[1] = 0x53.toByte()
        cbw[2] = 0x42.toByte()
        cbw[3] = 0x43.toByte()

        // dCBWTag
        val tag = System.currentTimeMillis().toInt()
        cbw[4] = (tag and 0xFF).toByte()
        cbw[5] = ((tag shr 8) and 0xFF).toByte()
        cbw[6] = ((tag shr 16) and 0xFF).toByte()
        cbw[7] = ((tag shr 24) and 0xFF).toByte()

        // dCBWDataTransferLength
        cbw[8] = (dataLength and 0xFF).toByte()
        cbw[9] = ((dataLength shr 8) and 0xFF).toByte()
        cbw[10] = ((dataLength shr 16) and 0xFF).toByte()
        cbw[11] = ((dataLength shr 24) and 0xFF).toByte()

        // bmCBWFlags
        cbw[12] = if (direction == Direction.IN) 0x80.toByte() else 0x00.toByte()

        // bCBWLUN
        cbw[13] = 0x00.toByte()

        // bCBWCBLength
        cbw[14] = command.size.toByte()

        // CBWCB
        command.copyInto(cbw, 15)

        return cbw
    }

    // ===== SCSI命令包装 =====

    private fun testUnitReady(): Boolean {
        return sendCommand(ScsiCommands.createTestUnitReadyCommand())
    }

    private fun sendModeSelectDao(): Boolean {
        // 简化的DAO模式设置，实际可能需要更多参数
        val modePage = byteArrayOf(
            // Mode Parameter Header (6 bytes for Mode Select 6)
            0x00,       // Reserved
            0x00,       // Medium Type
            0x00,       // Device-Specific Parameter
            0x08,       // Block Descriptor Length

            // Block Descriptor (8 bytes)
            0x00, 0x00, 0x00, 0x00,  // Number of Blocks (0 = 全部)
            0x00, 0x00, 0x08, 0x00,  // Block Length = 2048

            // Write Parameters Page (0x05)
            0x05,       // Page Code
            0x32,       // Page Length = 50
            0x00, 0x00, // Reserved
            ScsiCommands.WRITE_TYPE_DAO.toByte(),  // Write Type = DAO
            0x00,       // Multi-session = 0, Track Mode = 0
            0x00,       // Data Block Type
            0x00,       // Session Format
            0x00, 0x00, 0x00, 0x00,  // Reserved
            0x00, 0x00,              // Packet Size
            0x00, 0x00,              // Audio Pause Length
            0x00, 0x00, 0x00, 0x00,  // Media Catalog Number (MCN)
            0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00,  // ISRC
            0x00, 0x00, 0x00, 0x00,
            0x00, 0x00,              // Sub-header
            0x00, 0x00,
            0x00, 0x00,
            0x00, 0x00,
            0x00, 0x00,
            0x00, 0x00,
            0x00, 0x00,
            0x00, 0x00
        )

        return sendCommand(
            ScsiCommands.createModeSelectDaoCommand(),
            modePage,
            Direction.OUT
        )
    }

    private fun readDiscInformation(): DiscInformation? {
        val data = ByteArray(34)

        val success = sendCommand(
            ScsiCommands.createReadDiscInfoCommand(),
            data,
            Direction.IN
        )

        if (!success) return null

        return parseDiscInformation(data)
    }

    private fun parseDiscInformation(data: ByteArray): DiscInformation {
        return DiscInformation(
            discStatus = (data[0].toInt() shr 0) and 0x03,
            stateOfLastSession = (data[0].toInt() shr 2) and 0x03,
            erasable = (data[0].toInt() and 0x10) != 0,
            firstTrackNumber = data[1].toInt() and 0xFF,
            numberOfSessions = data[2].toInt() and 0xFF,
            firstTrackInLastSession = data[3].toInt() and 0xFF,
            lastTrackInLastSession = data[4].toInt() and 0xFF,
            validDiscInformation = (data[5].toInt() and 0x80) != 0,
            unrestrictedUse = (data[5].toInt() and 0x40) != 0,
            damaged = (data[5].toInt() and 0x20) != 0,
            copy = (data[5].toInt() and 0x10) != 0,
            trackMode = data[5].toInt() and 0x0F,
            blank = (data[6].toInt() and 0x01) != 0,
            reservedTrack = (data[6].toInt() and 0x02) != 0,
            nextWritableAddress = readInt32(data, 8),
            freeBlocks = readInt32(data, 12),
            readCapacity = 0
        )
    }

    private fun reserveTrack(sectors: Long): Boolean {
        return sendCommand(
            ScsiCommands.createReserveTrackCommand(sectors),
            timeoutMs = 10000
        )
    }

    private fun writeSectors(lba: Int, data: ByteArray, sectorCount: Int): Boolean {
        return sendCommand(
            ScsiCommands.createWrite10Command(lba, sectorCount.toShort()),
            data.copyOf(sectorCount * SECTOR_SIZE),
            Direction.OUT,
            timeoutMs = 60000
        )
    }

    private fun readSectors(lba: Int, buffer: ByteArray, sectorCount: Int): Boolean {
        return sendCommand(
            ScsiCommands.createRead10Command(lba, sectorCount.toShort()),
            buffer,
            Direction.IN,
            timeoutMs = 60000
        )
    }

    private fun synchronizeCache(): Boolean {
        return sendCommand(
            ScsiCommands.createSynchronizeCacheCommand(),
            timeoutMs = 60000
        )
    }

    private fun closeTrack(): Boolean {
        return sendCommand(
            ScsiCommands.createCloseTrackSessionCommand(false),
            timeoutMs = 60000
        )
    }

    private fun closeSession(): Boolean {
        return sendCommand(
            ScsiCommands.createCloseTrackSessionCommand(true),
            timeoutMs = 120000  // 最终化可能需要更长时间
        )
    }

    private fun readInt32(data: ByteArray, offset: Int): Int {
        return ((data[offset].toInt() and 0xFF) shl 24) or
                ((data[offset + 1].toInt() and 0xFF) shl 16) or
                ((data[offset + 2].toInt() and 0xFF) shl 8) or
                (data[offset + 3].toInt() and 0xFF)
    }

    private fun formatSize(bytes: Long): String {
        return when {
            bytes >= 1024 * 1024 * 1024 -> "%.2f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
            bytes >= 1024 * 1024 -> "%.2f MB".format(bytes / (1024.0 * 1024.0))
            bytes >= 1024 -> "%.2f KB".format(bytes / 1024.0)
            else -> "$bytes B"
        }
    }

    private fun ByteArray.toHexString(): String {
        return joinToString("") { "%02x".format(it) }
    }

    /**
     * 关闭刻录机连接
     */
    fun close() {
        try {
            usbConnection.releaseInterface(usbInterface)
            usbConnection.close()
        } catch (e: Exception) {
            // Ignore
        }
    }
}
