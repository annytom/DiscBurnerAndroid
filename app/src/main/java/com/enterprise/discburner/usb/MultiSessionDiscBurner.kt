package com.enterprise.discburner.usb

import android.hardware.usb.*
import com.enterprise.discburner.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.security.MessageDigest
import kotlin.math.min

/**
 * 刻录阶段
 */
enum class BurnStage(val description: String) {
    IDLE("空闲"),
    DEVICE_PREP("设备准备"),
    DISC_CHECK("光盘检查"),
    SESSION_ANALYSIS("分析现有会话"),
    LEAD_IN("写入Lead-in"),
    WRITING_DATA("写入数据"),
    LEAD_OUT("写入Lead-out"),
    FINALIZING("关闭会话"),
    VERIFYING("校验数据"),
    COMPLETED("完成"),
    ERROR("错误")
}

/**
 * 写入选项
 */
data class WriteOptions(
    val writeMode: WriteMode = WriteMode.TAO,
    val writeSpeed: Int = 0,  // 0 = 最大速度, 4, 8, 16, 24, 32, 40, 48, 52 (x speed)
    val closeSession: Boolean = false,  // 是否关闭当前会话（用于多会话的最后一步）
    val closeDisc: Boolean = false,      // 是否关闭光盘（不可再追加）
    val verifyAfterBurn: Boolean = true,
    val sessionName: String? = null      // 会话名称（审计用）
)

/**
 * 写入模式
 */
enum class WriteMode(val description: String) {
    DAO("整盘刻录（DAO）"),
    TAO("轨道刻录（TAO）"),
    SAO("会话刻录（SAO）");

    fun toScsiCode(): Byte = when (this) {
        DAO -> 0x01
        TAO -> 0x00
        SAO -> 0x03
    }
}

/**
 * 会话信息
 */
data class SessionInfo(
    val sessionNumber: Int,
    val startTrack: Int,
    val endTrack: Int,
    val startLba: Int,
    val sizeSectors: Long,
    val isClosed: Boolean,
    val isEmpty: Boolean
)

/**
 * 轨道信息
 */
data class TrackInfo(
    val trackNumber: Int,
    val sessionNumber: Int,
    val startLba: Int,
    val sizeSectors: Long,
    val isDataTrack: Boolean,
    val isCopyAllowed: Boolean,
    val damage: Boolean,
    val blank: Boolean,
    val reserved: Boolean,
    val nextWritableAddress: Int,
    val freeBlocks: Int
)

/**
 * 光盘信息
 */
data class DiscInformation(
    val discStatus: Int,           // 0=空白, 1=追加, 2=完整, 3=其他
    val stateOfLastSession: Int,   // 0=空, 1=未完成, 2=保留/损坏, 3=完整
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
        get() = discStatus == 1 || discStatus == 0

    val isClosed: Boolean
        get() = discStatus == 2

    val remainingSectors: Long
        get() = freeBlocks.toLong()

    fun canAppend(): Boolean {
        return appendable && !isClosed && !damaged
    }
}

/**
 * 多会话光盘刻录器
 * 支持DAO/TAO/SAO模式、多会话追加刻录
 */
class MultiSessionDiscBurner(
    private val usbConnection: UsbDeviceConnection,
    private val usbInterface: UsbInterface,
    private val endpointIn: UsbEndpoint,
    private val endpointOut: UsbEndpoint,
    private val auditLogger: AuditLogger
) {
    private val TAG = "MultiSessionBurner"

    private val bufferSize = 64 * 1024  // 64KB缓冲区
    private val sectorSize = ScsiCommands.SECTOR_SIZE

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
     * 分析光盘状态
     * 获取现有会话信息，用于判断是否可以追加
     */
    suspend fun analyzeDisc(): Result<DiscAnalysisResult> = withContext(Dispatchers.IO) {
        try {
            // 检查设备就绪
            if (!testUnitReady()) {
                return@withContext Result.failure(Exception("设备未就绪"))
            }

            // 读取光盘基本信息
            val discInfo = readDiscInformation()
                ?: return@withContext Result.failure(Exception("无法读取光盘信息"))

            // 读取会话信息
            val sessions = mutableListOf<SessionInfo>()
            for (i in 1..discInfo.numberOfSessions) {
                readSessionInfo(i)?.let { sessions.add(it) }
            }

            // 读取轨道信息
            val tracks = mutableListOf<TrackInfo>()
            for (i in discInfo.firstTrackNumber..discInfo.lastTrackInLastSession) {
                readTrackInfo(i)?.let { tracks.add(it) }
            }

            Result.success(DiscAnalysisResult(
                discInfo = discInfo,
                sessions = sessions,
                tracks = tracks,
                canAppend = discInfo.canAppend() && sessions.isNotEmpty(),
                canBurnNew = discInfo.blank || discInfo.canAppend(),
                recommendedMode = if (discInfo.blank) WriteMode.DAO else WriteMode.TAO
            ))

        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 刻录ISO镜像（单会话或多会话）
     */
    suspend fun burnIso(
        isoFile: File,
        options: WriteOptions = WriteOptions(),
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
                "writeMode" to options.writeMode.name,
                "closeSession" to options.closeSession,
                "closeDisc" to options.closeDisc
            )
        )

        try {
            // ===== Phase 1: 设备与光盘验证 =====
            _stage.value = BurnStage.DEVICE_PREP
            onProgress(BurnStage.DEVICE_PREP, 0.05f)

            if (!testUnitReady()) {
                return@withContext BurnResult.Failure(
                    "设备未就绪，请检查刻录机连接",
                    AuditCode.MODE_SELECT_FAILED
                )
            }

            // 分析光盘状态
            val discInfo = readDiscInformation()
                ?: return@withContext BurnResult.Failure(
                    "无法读取光盘信息",
                    AuditCode.DISC_READ_FAILED
                )

            // 检查光盘是否可写
            if (discInfo.isClosed) {
                return@withContext BurnResult.Failure(
                    "光盘已关闭，无法追加数据",
                    AuditCode.DISC_NOT_BLANK
                )
            }

            if (discInfo.damaged) {
                return@withContext BurnResult.Failure(
                    "光盘损坏",
                    AuditCode.DISC_READ_FAILED
                )
            }

            // ===== Phase 2: 分析现有会话（多会话模式）=====
            var existingSessions = 0
            var startLba = discInfo.nextWritableAddress

            if (!discInfo.blank && options.writeMode != WriteMode.DAO) {
                _stage.value = BurnStage.SESSION_ANALYSIS
                onProgress(BurnStage.SESSION_ANALYSIS, 0.08f)

                val analysis = analyzeDisc().getOrNull()
                existingSessions = analysis?.sessions?.size ?: 0

                if (existingSessions > 0) {
                    // 多会话模式：获取最后一个轨道的信息
                    val lastTrack = analysis?.tracks?.lastOrNull()
                    if (lastTrack != null) {
                        startLba = lastTrack.nextWritableAddress
                        auditLogger.log(
                            AuditEvent.DEVICE_CONNECTED, sessionId,
                            mapOf(
                                "existingSessions" to existingSessions,
                                "existingTracks" to analysis.tracks.size,
                                "appendStartLba" to startLba,
                                "lastSessionClosed" to analysis.sessions.lastOrNull()?.isClosed
                            )
                        )
                    }
                }
            }

            // 计算所需空间
            val fileSize = isoFile.length()
            val requiredSectors = (fileSize + sectorSize - 1) / sectorSize

            if (requiredSectors > discInfo.remainingSectors) {
                return@withContext BurnResult.Failure(
                    "空间不足: 需要${requiredSectors}扇区, 剩余${discInfo.remainingSectors}扇区",
                    AuditCode.INSUFFICIENT_SPACE
                )
            }

            // ===== Phase 3: 设置写入模式 =====
            _stage.value = BurnStage.DEVICE_PREP

            // 根据光盘状态和选项选择写入模式
            val effectiveMode = when {
                discInfo.blank -> options.writeMode  // 空白盘使用用户指定模式
                else -> WriteMode.TAO  // 已有数据的盘必须使用TAO追加
            }

            if (!sendModeSelect(effectiveMode, existingSessions > 0)) {
                return@withContext BurnResult.Failure(
                    "无法设置${effectiveMode.description}",
                    AuditCode.MODE_SELECT_FAILED
                )
            }

            // ===== Phase 4: 预留轨道（TAO/SAO模式）=====
            _stage.value = BurnStage.LEAD_IN
            onProgress(BurnStage.LEAD_IN, 0.10f)

            if (effectiveMode == WriteMode.TAO || effectiveMode == WriteMode.SAO) {
                if (!reserveTrack(requiredSectors)) {
                    return@withContext BurnResult.Failure(
                        "无法预留轨道空间",
                        AuditCode.RESERVE_TRACK_FAILED
                    )
                }
            }

            // ===== Phase 5: 数据写入 =====
            _stage.value = BurnStage.WRITING_DATA

            val sha256Digest = MessageDigest.getInstance("SHA-256")
            var bytesWritten = 0L
            var sectorsWritten = 0L
            val lbaStart = startLba

            isoFile.inputStream().use { input ->
                val buffer = ByteArray(bufferSize)

                while (isActive) {
                    val read = input.read(buffer)
                    if (read <= 0) break

                    sha256Digest.update(buffer, 0, read)

                    // 2KB扇区对齐
                    val alignedSize = if (read % sectorSize == 0) {
                        read
                    } else {
                        ((read / sectorSize) + 1) * sectorSize
                    }

                    if (alignedSize > read) {
                        for (i in read until alignedSize) {
                            buffer[i] = 0
                        }
                    }

                    val sector = lbaStart + sectorsWritten
                    val sectorsToWrite = alignedSize / sectorSize

                    if (!writeSectors(sector.toInt(), buffer, sectorsToWrite)) {
                        auditLogger.log(AuditEvent.BURN_FAILED, sessionId,
                            mapOf(
                                "code" to AuditCode.WRITE_ERROR,
                                "sector" to sector,
                                "sectorsWritten" to sectorsWritten
                            ))
                        return@withContext BurnResult.Failure(
                            "写入失败于扇区$sector",
                            AuditCode.WRITE_ERROR
                        )
                    }

                    bytesWritten += read
                    sectorsWritten += sectorsToWrite

                    val progress = 0.10f + 0.40f * (sectorsWritten.toFloat() / requiredSectors)
                    onProgress(BurnStage.WRITING_DATA, progress)
                }
            }

            val sourceHash = sha256Digest.digest().toHexString()

            // ===== Phase 6: 关闭轨道 =====
            _stage.value = BurnStage.LEAD_OUT
            onProgress(BurnStage.LEAD_OUT, 0.50f)

            if (!synchronizeCache()) {
                auditLogger.log(AuditEvent.BURN_FAILED, sessionId,
                    mapOf("code" to AuditCode.CLOSE_SESSION_FAILED, "step" to "sync"))
            }

            // 关闭当前轨道
            if (!closeTrack()) {
                auditLogger.log(AuditEvent.BURN_FAILED, sessionId,
                    mapOf("code" to AuditCode.CLOSE_SESSION_FAILED))
                return@withContext BurnResult.Failure(
                    "无法关闭轨道",
                    AuditCode.CLOSE_SESSION_FAILED
                )
            }

            // ===== Phase 7: 关闭会话（可选）=====
            if (options.closeSession || options.closeDisc) {
                _stage.value = BurnStage.FINALIZING
                onProgress(BurnStage.FINALIZING, 0.55f)

                if (!closeSession()) {
                    auditLogger.log(AuditEvent.BURN_FAILED, sessionId,
                        mapOf("code" to AuditCode.FINALIZE_FAILED))
                    return@withContext BurnResult.Failure(
                        "无法关闭会话",
                        AuditCode.FINALIZE_FAILED
                    )
                }

                // 如果需要关闭光盘（导入区）
                if (options.closeDisc) {
                    if (!closeDisc()) {
                        return@withContext BurnResult.Failure(
                            "无法关闭光盘",
                            AuditCode.FINALIZE_FAILED
                        )
                    }
                }
            }

            auditLogger.log(AuditEvent.BURN_COMPLETED, sessionId,
                mapOf(
                    "writeMode" to effectiveMode.name,
                    "sectorsWritten" to sectorsWritten,
                    "bytesWritten" to bytesWritten,
                    "sourceHash" to sourceHash,
                    "sessionClosed" to options.closeSession,
                    "discClosed" to options.closeDisc
                )
            )

            // ===== Phase 8: 自动校验 =====
            val verifyResult = if (options.verifyAfterBurn) {
                _stage.value = BurnStage.VERIFYING
                auditLogger.log(AuditEvent.VERIFY_STARTED, sessionId,
                    mapOf("expectedSectors" to sectorsWritten))

                verifyDiscData(lbaStart, sectorsWritten, sourceHash) { vProgress ->
                    onProgress(BurnStage.VERIFYING, 0.55f + 0.45f * vProgress)
                }
            } else {
                VerifyResult.Success(sourceHash, sectorsWritten.toInt())
            }

            return@withContext when (verifyResult) {
                is VerifyResult.Success -> {
                    auditLogger.log(AuditEvent.VERIFY_PASSED, sessionId,
                        mapOf(
                            "verifiedHash" to verifyResult.hash,
                            "sectorsRead" to verifyResult.sectorsRead
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
                            "sector" to verifyResult.failedAtSector
                        ))

                    _stage.value = BurnStage.ERROR

                    BurnResult.Failure(
                        "数据校验失败: ${verifyResult.error}",
                        AuditCode.VERIFY_MISMATCH,
                        recoverable = false,
                        sessionId = sessionId
                    )
                }
            }

        } catch (e: Exception) {
            auditLogger.log(AuditEvent.EXCEPTION, sessionId,
                mapOf("error" to e.message))

            _stage.value = BurnStage.ERROR

            BurnResult.Failure(
                "刻录过程中发生异常: ${e.message}",
                AuditCode.EXCEPTION
            )
        }
    }

    /**
     * 读取会话信息
     */
    private fun readSessionInfo(sessionNumber: Int): SessionInfo? {
        // 使用READ TRACK INFORMATION获取会话相关信息
        val data = ByteArray(48)
        val command = byteArrayOf(
            0x52,  // READ TRACK INFORMATION
            0x01,  // Address Type = Session Number
            0x00,
            sessionNumber.toByte(),
            0x00, 0x00,  // Reserved
            0x00, 0x30,  // Allocation Length = 48
            0x00   // Control
        )

        return if (sendCommand(command, data, Direction.IN)) {
            parseSessionInfo(data, sessionNumber)
        } else null
    }

    private fun parseSessionInfo(data: ByteArray, sessionNumber: Int): SessionInfo {
        return SessionInfo(
            sessionNumber = sessionNumber,
            startTrack = data[2].toInt() and 0xFF,
            endTrack = data[3].toInt() and 0xFF,
            startLba = readInt32(data, 8),
            sizeSectors = readInt32(data, 24).toLong(),
            isClosed = (data[5].toInt() and 0x40) != 0,
            isEmpty = (data[5].toInt() and 0x01) != 0
        )
    }

    /**
     * 读取轨道信息
     */
    private fun readTrackInfo(trackNumber: Int): TrackInfo? {
        val data = ByteArray(36)
        val command = byteArrayOf(
            0x52,  // READ TRACK INFORMATION
            0x00,  // Address Type = LBA
            0x00, 0x00, 0x00, 0x00,  // LBA = 0 (当前)
            0x00, 0x24,  // Allocation Length = 36
            0x00   // Control
        )

        return if (sendCommand(command, data, Direction.IN)) {
            parseTrackInfo(data)
        } else null
    }

    private fun parseTrackInfo(data: ByteArray): TrackInfo {
        return TrackInfo(
            trackNumber = data[0].toInt() and 0xFF,
            sessionNumber = data[1].toInt() and 0xFF,
            startLba = readInt32(data, 8),
            sizeSectors = readInt32(data, 24).toLong(),
            isDataTrack = (data[6].toInt() and 0x40) != 0,
            isCopyAllowed = (data[6].toInt() and 0x02) != 0,
            damage = (data[7].toInt() and 0x20) != 0,
            blank = (data[7].toInt() and 0x01) != 0,
            reserved = (data[7].toInt() and 0x02) != 0,
            nextWritableAddress = readInt32(data, 12),
            freeBlocks = readInt32(data, 16)
        )
    }

    /**
     * 设置写入模式
     */
    private fun sendModeSelect(mode: WriteMode, multiSession: Boolean): Boolean {
        val modePage = byteArrayOf(
            // Mode Parameter Header (8 bytes)
            0x00,       // Reserved
            0x00,       // Medium Type
            0x00,       // Device-Specific Parameter
            0x08,       // Block Descriptor Length

            // Block Descriptor (8 bytes)
            0x00, 0x00, 0x00, 0x00,  // Number of Blocks
            0x00, 0x00, 0x08, 0x00,  // Block Length = 2048

            // Write Parameters Page (0x05)
            0x05,       // Page Code
            0x32,       // Page Length = 50
            0x00, 0x00, // Reserved
            mode.toScsiCode(),  // Write Type
            (if (multiSession) 0x01 else 0x00).toByte(),  // Multi-session
            0x08,       // Data Block Type = Mode 1
            0x00,       // Session Format
            0x00, 0x00, 0x00, 0x00,  // Reserved
            0x00, 0x00,              // Packet Size
            0x00, 0x00,              // Audio Pause
            0x00, 0x00, 0x00, 0x00,  // MCN
            0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00,  // ISRC
            0x00, 0x00,
            0x00, 0x00,              // Sub-header
            0x00, 0x00,
            0x00, 0x00,
            0x00, 0x00,
            0x00, 0x00,
            0x00, 0x00,
            0x00, 0x00,
            0x00, 0x00
        )

        val command = byteArrayOf(
            0x15,  // MODE SELECT (6)
            0x10,  // PF = 1
            0x00,  // Reserved
            0x00,  // Reserved
            modePage.size.toByte(),
            0x00   // Control
        )

        return sendCommand(command, modePage, Direction.OUT)
    }

    // ===== 基础命令 =====

    private fun testUnitReady(): Boolean {
        return sendCommand(ScsiCommands.createTestUnitReadyCommand())
    }

    private fun readDiscInformation(): DiscInformation? {
        val data = ByteArray(34)
        val success = sendCommand(
            ScsiCommands.createReadDiscInfoCommand(),
            data,
            Direction.IN
        )
        return if (success) parseDiscInformation(data) else null
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
            data.copyOf(sectorCount * sectorSize),
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
            timeoutMs = 120000
        )
    }

    private fun closeDisc(): Boolean {
        // 关闭导入区，光盘不可再追加
        val command = byteArrayOf(
            0x5B,  // CLOSE TRACK/SESSION
            0x02,  // Close Session (Finalization)
            0x00, 0x00,
            0x00, 0x00, 0x00, 0x00,
            0x00, 0x00,
            0x00   // Control
        )
        return sendCommand(command, timeoutMs = 180000)
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
        val buffer = ByteArray(bufferSize)

        try {
            while (sectorsRead < sectorsToVerify && isActive) {
                val sectorsInBatch = min(
                    buffer.size / sectorSize,
                    (sectorsToVerify - sectorsRead).toInt()
                )

                if (!readSectors(currentLba, buffer, sectorsInBatch)) {
                    return@withContext VerifyResult.Failure(
                        "读取失败",
                        currentLba
                    )
                }

                val bytesToHash = if (sectorsRead + sectorsInBatch >= sectorsToVerify) {
                    val remainingSectors = (sectorsToVerify - sectorsRead).toInt()
                    remainingSectors * sectorSize
                } else {
                    sectorsInBatch * sectorSize
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
                    "哈希不匹配",
                    -1
                )
            }

        } catch (e: Exception) {
            VerifyResult.Failure("校验异常: ${e.message}", currentLba)
        }
    }

    // ===== 工具方法 =====

    private fun sendCommand(
        command: ByteArray,
        dataTransfer: ByteArray? = null,
        dataDirection: Direction = Direction.NONE,
        timeoutMs: Int = 30000
    ): Boolean {
        val cbw = buildCbw(command, dataTransfer?.size ?: 0, dataDirection)

        val cbwSent = usbConnection.bulkTransfer(
            endpointOut, cbw, cbw.size, 5000
        )
        if (cbwSent != cbw.size) return false

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

        val csw = ByteArray(13)
        val cswReceived = usbConnection.bulkTransfer(
            endpointIn, csw, csw.size, 5000
        )

        return cswReceived == 13 && csw[12] == 0x00.toByte()
    }

    private fun buildCbw(
        command: ByteArray,
        dataLength: Int,
        direction: Direction
    ): ByteArray {
        val cbw = ByteArray(31)

        cbw[0] = 0x55.toByte()
        cbw[1] = 0x53.toByte()
        cbw[2] = 0x42.toByte()
        cbw[3] = 0x43.toByte()

        val tag = System.currentTimeMillis().toInt()
        cbw[4] = (tag and 0xFF).toByte()
        cbw[5] = ((tag shr 8) and 0xFF).toByte()
        cbw[6] = ((tag shr 16) and 0xFF).toByte()
        cbw[7] = ((tag shr 24) and 0xFF).toByte()

        cbw[8] = (dataLength and 0xFF).toByte()
        cbw[9] = ((dataLength shr 8) and 0xFF).toByte()
        cbw[10] = ((dataLength shr 16) and 0xFF).toByte()
        cbw[11] = ((dataLength shr 24) and 0xFF).toByte()

        cbw[12] = if (direction == Direction.IN) 0x80.toByte() else 0x00.toByte()
        cbw[13] = 0x00.toByte()
        cbw[14] = command.size.toByte()

        command.copyInto(cbw, 15)

        return cbw
    }

    private fun readInt32(data: ByteArray, offset: Int): Int {
        return ((data[offset].toInt() and 0xFF) shl 24) or
                ((data[offset + 1].toInt() and 0xFF) shl 16) or
                ((data[offset + 2].toInt() and 0xFF) shl 8) or
                (data[offset + 3].toInt() and 0xFF)
    }

    private fun ByteArray.toHexString(): String {
        return joinToString("") { "%02x".format(it) }
    }

    fun close() {
        try {
            usbConnection.releaseInterface(usbInterface)
            usbConnection.close()
        } catch (e: Exception) {
            // Ignore
        }
    }
}

/**
 * 光盘分析结果
 */
data class DiscAnalysisResult(
    val discInfo: DiscInformation,
    val sessions: List<SessionInfo>,
    val tracks: List<TrackInfo>,
    val canAppend: Boolean,
    val canBurnNew: Boolean,
    val recommendedMode: WriteMode
)
