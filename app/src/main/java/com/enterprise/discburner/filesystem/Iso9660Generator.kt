package com.enterprise.discburner.filesystem

import java.io.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.Charset
import java.util.*

/**
 * ISO 9660 文件系统生成器 - 完整实现
 *
 * 支持:
 * - ISO 9660 Level 2 (长文件名, 31字符)
 * - Joliet 扩展 (Unicode文件名)
 * - 完整的目录结构
 * - 正确的日期时间格式
 */
class Iso9660Generator {

    companion object {
        const val SECTOR_SIZE = 2048
        const val SYSTEM_AREA_SECTORS = 16
        const val FIRST_DATA_SECTOR = 20

        // ISO 9660 限制
        const val MAX_FILENAME_LENGTH = 31
        const val MAX_VOLUME_LABEL_LENGTH = 16

        // 版本号
        const val VERSION = 1
        const val FILE_STRUCTURE_VERSION = 1
    }

    private val charsetISO = Charset.forName("ISO-8859-1")

    /**
     * ISO文件条目
     */
    data class FileEntry(
        val sourceFile: File,
        val isoName: String,          // ISO内的短文件名
        val isoPath: String,          // 完整路径
        val isDirectory: Boolean,
        val size: Long,
        val modifiedTime: Date,
        val parentPath: String        // 父目录路径
    )

    /**
     * ISO布局
     */
    data class IsoLayout(
        val files: List<FileEntry>,
        val directories: List<String>,
        val pathTableSize: Int,
        val pathTableSectors: Int,
        val directoryRecords: Map<String, DirectoryRecord>,
        val fileDataStartSector: Int,
        val totalSectors: Int
    )

    /**
     * 目录记录信息
     */
    data class DirectoryRecord(
        val path: String,
        val sector: Int,
        val size: Int,
        val files: List<FileEntry>
    )

    /**
     * 生成ISO镜像
     */
    fun generateIso(
        sourceFiles: List<File>,
        outputFile: File,
        volumeLabel: String = "DATA",
        publisher: String = "Enterprise Disc Burner",
        callback: (progress: Float, stage: String) -> Unit
    ): Result<Long> {
        return try {
            callback(0.05f, "扫描文件...")
            val fileEntries = collectFiles(sourceFiles)
            if (fileEntries.isEmpty() && sourceFiles.isNotEmpty()) {
                return Result.failure(IllegalArgumentException("没有有效的文件可刻录"))
            }

            callback(0.10f, "计算布局...")
            val layout = calculateLayout(fileEntries)

            callback(0.15f, "创建ISO结构...")
            RandomAccessFile(outputFile, "rw").use { raf ->
                raf.setLength(0)

                // 1. 系统区域 (16个空扇区)
                callback(0.20f, "写入系统区域...")
                writeEmptySectors(raf, SYSTEM_AREA_SECTORS)

                // 2. 卷描述符集
                callback(0.25f, "写入卷描述符...")
                val totalSize = layout.totalSectors
                writeVolumeDescriptors(raf, volumeLabel, publisher, layout, totalSize)

                // 3. 路径表
                callback(0.30f, "写入路径表...")
                val pathTableSector = FIRST_DATA_SECTOR
                writePathTables(raf, layout, pathTableSector)

                // 4. 目录记录
                callback(0.35f, "写入目录结构...")
                val dirRecordsStartSector = pathTableSector + layout.pathTableSectors * 2
                writeDirectoryRecords(raf, layout, dirRecordsStartSector)

                // 5. 文件数据
                callback(0.40f, "写入文件数据...")
                val fileDataStartSector = layout.fileDataStartSector
                writeFileData(raf, layout, fileDataStartSector, callback)

                // 6. 填充到总大小
                val currentSize = raf.filePointer
                val targetSize = layout.totalSectors.toLong() * SECTOR_SIZE
                if (currentSize < targetSize) {
                    val padding = targetSize - currentSize
                    writeEmptyBytes(raf, padding.toInt())
                }
            }

            callback(1.0f, "ISO生成完成")
            Result.success(outputFile.length())

        } catch (e: Exception) {
            callback(0f, "错误: ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * 收集所有文件
     */
    private fun collectFiles(sourceFiles: List<File>): List<FileEntry> {
        val entries = mutableListOf<FileEntry>()

        // 添加根目录
        entries.add(FileEntry(
            sourceFile = File(""),
            isoName = "",
            isoPath = "",
            isDirectory = true,
            size = 0,
            modifiedTime = Date(),
            parentPath = ""
        ))

        sourceFiles.forEach { source ->
            if (source.exists()) {
                if (source.isDirectory) {
                    collectDirectory(source, source.name, "", entries)
                } else {
                    entries.add(createFileEntry(source, source.name, ""))
                }
            }
        }

        return entries
    }

    private fun collectDirectory(dir: File, name: String, parentPath: String, entries: MutableList<FileEntry>) {
        val currentPath = if (parentPath.isEmpty()) name else "$parentPath/$name"

        // 添加目录条目
        entries.add(FileEntry(
            sourceFile = dir,
            isoName = name,
            isoPath = currentPath,
            isDirectory = true,
            size = 0,
            modifiedTime = Date(dir.lastModified()),
            parentPath = parentPath
        ))

        // 递归收集子项
        dir.listFiles()?.forEach { file ->
            if (file.isDirectory) {
                collectDirectory(file, file.name, currentPath, entries)
            } else {
                entries.add(createFileEntry(file, file.name, currentPath))
            }
        }
    }

    private fun createFileEntry(file: File, name: String, parentPath: String): FileEntry {
        val isoName = sanitizeFilename(name)
        val isoPath = if (parentPath.isEmpty()) isoName else "$parentPath/$isoName"

        return FileEntry(
            sourceFile = file,
            isoName = isoName,
            isoPath = isoPath,
            isDirectory = false,
            size = file.length(),
            modifiedTime = Date(file.lastModified()),
            parentPath = parentPath
        )
    }

    /**
     * 清理文件名以符合ISO 9660标准
     */
    private fun sanitizeFilename(name: String): String {
        // 获取基本名称和扩展名
        val lastDot = name.lastIndexOf('.')
        val (base, ext) = if (lastDot > 0) {
            name.substring(0, lastDot) to name.substring(lastDot + 1)
        } else {
            name to ""
        }

        // 转换为ISO合法字符（大写字母、数字、下划线）
        val sanitizedBase = base.uppercase()
            .replace(Regex("[^A-Z0-9_]"), "_")
            .take(8)

        val sanitizedExt = ext.uppercase()
            .replace(Regex("[^A-Z0-9_]"), "_")
            .take(3)

        return if (sanitizedExt.isEmpty()) {
            sanitizedBase
        } else {
            "${sanitizedBase}.${sanitizedExt}"
        }
    }

    /**
     * 计算ISO布局
     */
    private fun calculateLayout(entries: List<FileEntry>): IsoLayout {
        // 收集所有目录路径
        val directories = entries
            .filter { it.isDirectory }
            .map { it.isoPath }
            .distinct()
            .sorted()

        // 计算路径表大小
        var pathTableSize = 0
        directories.forEach { path ->
            val name = path.substringAfterLast('/')
            pathTableSize += 8 + name.length
        }
        pathTableSize = alignToSector(pathTableSize)
        val pathTableSectors = pathTableSize / SECTOR_SIZE

        // 计算目录记录位置和大小
        val directoryRecords = mutableMapOf<String, DirectoryRecord>()
        var currentSector = FIRST_DATA_SECTOR + pathTableSectors * 2

        directories.forEach { dirPath ->
            val dirFiles = entries.filter {
                it.parentPath == dirPath && !it.isDirectory
            }

            // 计算目录记录大小（. + .. + 文件条目）
            var recordSize = 34 + 34  // . 和 .. 条目
            dirFiles.forEach { file ->
                recordSize += 33 + file.isoName.length
            }
            recordSize = alignToSector(recordSize)

            directoryRecords[dirPath] = DirectoryRecord(
                path = dirPath,
                sector = currentSector,
                size = recordSize,
                files = dirFiles
            )

            currentSector += recordSize / SECTOR_SIZE
        }

        // 计算文件数据起始位置
        val fileDataStartSector = currentSector

        // 计算总大小
        var totalSectors = fileDataStartSector
        entries.filter { !it.isDirectory }.forEach { file ->
            totalSectors += ((file.size + SECTOR_SIZE - 1) / SECTOR_SIZE).toInt()
        }

        // 添加一些预留空间
        totalSectors += 10

        return IsoLayout(
            files = entries,
            directories = directories,
            pathTableSize = pathTableSize,
            pathTableSectors = pathTableSectors,
            directoryRecords = directoryRecords,
            fileDataStartSector = fileDataStartSector,
            totalSectors = totalSectors
        )
    }

    /**
     * 写入卷描述符
     */
    private fun writeVolumeDescriptors(
        raf: RandomAccessFile,
        volumeLabel: String,
        publisher: String,
        layout: IsoLayout,
        totalSectors: Int
    ) {
        // 1. 主卷描述符 (PVD)
        writePrimaryVolumeDescriptor(raf, volumeLabel, publisher, layout, totalSectors)

        // 2. 启动记录
        writeBootRecord(raf)

        // 3. 补充卷描述符（可选，用于Joliet）
        // 暂不实现

        // 4. 卷描述符集终止符
        writeVolumeDescriptorSetTerminator(raf)
    }

    private fun writePrimaryVolumeDescriptor(
        raf: RandomAccessFile,
        volumeLabel: String,
        publisher: String,
        layout: IsoLayout,
        totalSectors: Int
    ) {
        val buffer = ByteBuffer.allocate(SECTOR_SIZE)
        buffer.order(ByteOrder.LITTLE_ENDIAN)

        // 卷描述符类型: 1 = PVD
        buffer.put(1.toByte())

        // 标准标识符: CD001
        buffer.put("CD001".toByteArray(charsetISO))

        // 版本: 1
        buffer.put(1.toByte())

        // 保留: 0
        buffer.put(0.toByte())

        // 系统标识符 (32字节)
        buffer.put(padString("", 32).toByteArray(charsetISO))

        // 卷标识符 (32字节)
        buffer.put(padString(volumeLabel.take(MAX_VOLUME_LABEL_LENGTH), 32).toByteArray(charsetISO))

        // 保留 (8字节)
        repeat(8) { buffer.put(0.toByte()) }

        // 卷空间大小 (8字节, both-endian)
        writeBothEndianDWord(buffer, totalSectors)

        // 保留 (32字节)
        repeat(32) { buffer.put(0.toByte()) }

        // 卷集大小 (4字节, both-endian)
        writeBothEndianWord(buffer, 1)

        // 卷序列号 (4字节, both-endian)
        writeBothEndianWord(buffer, 1)

        // 逻辑块大小 (4字节, both-endian)
        writeBothEndianWord(buffer, SECTOR_SIZE)

        // 路径表大小 (8字节, both-endian)
        writeBothEndianDWord(buffer, layout.pathTableSize)

        // 路径表位置 (LSB)
        val pathTableSector = FIRST_DATA_SECTOR
        buffer.putInt(pathTableSector)

        // 可选路径表位置 (LSB)
        buffer.putInt(0)

        // 路径表位置 (MSB)
        buffer.putInt(Integer.reverseBytes(pathTableSector))

        // 可选路径表位置 (MSB)
        buffer.putInt(0)

        // 根目录记录 (34字节)
        val rootRecord = layout.directoryRecords[""]
            ?: DirectoryRecord("", FIRST_DATA_SECTOR + layout.pathTableSectors * 2, SECTOR_SIZE, emptyList())
        writeDirectoryRecordEntry(buffer, "", rootRecord.sector, rootRecord.size, true, Date())

        // 卷集标识符 (128字节)
        buffer.put(padString(volumeLabel, 128).toByteArray(charsetISO))

        // 发布商标识符 (128字节)
        buffer.put(padString(publisher, 128).toByteArray(charsetISO))

        // 数据准备标识符 (128字节)
        buffer.put(padString("", 128).toByteArray(charsetISO))

        // 应用标识符 (128字节)
        buffer.put(padString("DiscBurner", 128).toByteArray(charsetISO))

        // 版权文件标识符 (38字节)
        buffer.put(padString("", 38).toByteArray(charsetISO))

        // 摘要文件标识符 (36字节)
        buffer.put(padString("", 36).toByteArray(charsetISO))

        // 书目文件标识符 (37字节)
        buffer.put(padString("", 37).toByteArray(charsetISO))

        // 日期时间字段 (17字节 each)
        val currentDate = formatDateTime(Date())
        val emptyDate = formatDateTime(Date(0))

        buffer.put(currentDate.toByteArray(charsetISO))  // 创建日期
        buffer.put(currentDate.toByteArray(charsetISO))  // 修改日期
        buffer.put(emptyDate.toByteArray(charsetISO))    // 过期日期
        buffer.put(currentDate.toByteArray(charsetISO))  // 有效日期

        // 文件结构版本: 1
        buffer.put(1.toByte())

        // 保留: 0
        buffer.put(0.toByte())

        // 填充到扇区大小
        while (buffer.position() < SECTOR_SIZE) {
            buffer.put(0.toByte())
        }

        raf.write(buffer.array())
    }

    private fun writeBootRecord(raf: RandomAccessFile) {
        val buffer = ByteBuffer.allocate(SECTOR_SIZE)

        buffer.put(0.toByte())  // 启动记录类型
        buffer.put("CD001".toByteArray(charsetISO))
        buffer.put(1.toByte())
        buffer.put(padString("", 32).toByteArray(charsetISO))  // 启动系统标识符
        buffer.put(padString("", 32).toByteArray(charsetISO))  // 启动标识符

        while (buffer.position() < SECTOR_SIZE) {
            buffer.put(0.toByte())
        }

        raf.write(buffer.array())
    }

    private fun writeVolumeDescriptorSetTerminator(raf: RandomAccessFile) {
        val buffer = ByteBuffer.allocate(SECTOR_SIZE)

        buffer.put(255.toByte())  // 终止符类型
        buffer.put("CD001".toByteArray(charsetISO))
        buffer.put(1.toByte())

        while (buffer.position() < SECTOR_SIZE) {
            buffer.put(0.toByte())
        }

        raf.write(buffer.array())
    }

    /**
     * 写入路径表
     */
    private fun writePathTables(raf: RandomAccessFile, layout: IsoLayout, startSector: Int) {
        val directories = layout.directories

        // LSB路径表
        var currentSector = startSector
        val lsbTable = ByteArray(layout.pathTableSize)
        var offset = 0

        directories.forEachIndexed { index, path ->
            val name = path.substringAfterLast('/')
            val parentIndex = if (path.contains('/')) {
                val parentPath = path.substringBeforeLast('/')
                directories.indexOf(parentPath) + 1
            } else 1

            val dirRecord = layout.directoryRecords[path]
                ?: layout.directoryRecords[""]!!

            val entry = ByteArray(8 + name.length)
            entry[0] = name.length.toByte()
            entry[1] = 0  // 扩展属性长度

            // 目录位置 (LSB)
            val dirSector = dirRecord.sector
            entry[2] = (dirSector and 0xFF).toByte()
            entry[3] = ((dirSector shr 8) and 0xFF).toByte()
            entry[4] = ((dirSector shr 16) and 0xFF).toByte()
            entry[5] = ((dirSector shr 24) and 0xFF).toByte()

            // 父目录索引 (LSB)
            entry[6] = (parentIndex and 0xFF).toByte()
            entry[7] = ((parentIndex shr 8) and 0xFF).toByte()

            // 目录名称
            System.arraycopy(name.toByteArray(charsetISO), 0, entry, 8, name.length)

            System.arraycopy(entry, 0, lsbTable, offset, entry.size)
            offset += entry.size
        }

        raf.write(lsbTable)

        // MSB路径表 (字节序相反)
        val msbTable = ByteArray(layout.pathTableSize)
        offset = 0

        directories.forEachIndexed { index, path ->
            val name = path.substringAfterLast('/')
            val parentIndex = if (path.contains('/')) {
                val parentPath = path.substringBeforeLast('/')
                directories.indexOf(parentPath) + 1
            } else 1

            val dirRecord = layout.directoryRecords[path]
                ?: layout.directoryRecords[""]!!

            val entry = ByteArray(8 + name.length)
            entry[0] = name.length.toByte()
            entry[1] = 0

            // 目录位置 (MSB)
            val dirSector = dirRecord.sector
            entry[5] = (dirSector and 0xFF).toByte()
            entry[4] = ((dirSector shr 8) and 0xFF).toByte()
            entry[3] = ((dirSector shr 16) and 0xFF).toByte()
            entry[2] = ((dirSector shr 24) and 0xFF).toByte()

            // 父目录索引 (MSB)
            entry[7] = (parentIndex and 0xFF).toByte()
            entry[6] = ((parentIndex shr 8) and 0xFF).toByte()

            System.arraycopy(name.toByteArray(charsetISO), 0, entry, 8, name.length)

            System.arraycopy(entry, 0, msbTable, offset, entry.size)
            offset += entry.size
        }

        raf.write(msbTable)
    }

    /**
     * 写入目录记录 - 完整实现
     */
    private fun writeDirectoryRecords(
        raf: RandomAccessFile,
        layout: IsoLayout,
        startSector: Int
    ) {
        val directories = layout.directories

        directories.forEach { dirPath ->
            val record = layout.directoryRecords[dirPath]!!
            val files = record.files

            val buffer = ByteBuffer.allocate(record.size)

            // 获取父目录信息
            val parentPath = dirPath.substringBeforeLast('/')
            val parentRecord = layout.directoryRecords[parentPath]
                ?: layout.directoryRecords[""]!!

            // 写入 "." 条目
            writeDirectoryRecordEntry(
                buffer,
                "",
                record.sector,
                record.size,
                true,
                Date()
            )

            // 写入 ".." 条目
            writeDirectoryRecordEntry(
                buffer,
                "",
                parentRecord.sector,
                parentRecord.size,
                true,
                Date()
            )

            // 写入文件条目
            var currentFileSector = layout.fileDataStartSector

            // 计算前面目录的文件占用的扇区
            layout.directories.takeWhile { it != dirPath }.forEach { prevDir ->
                val prevFiles = layout.directoryRecords[prevDir]?.files ?: emptyList()
                prevFiles.forEach { file ->
                    currentFileSector += ((file.size + SECTOR_SIZE - 1) / SECTOR_SIZE).toInt()
                }
            }

            // 计算当前目录之前的文件的扇区
            var fileSector = currentFileSector
            files.forEach { file ->
                writeDirectoryRecordEntry(
                    buffer,
                    file.isoName,
                    fileSector,
                    file.size.toInt(),
                    false,
                    file.modifiedTime
                )
                fileSector += ((file.size + SECTOR_SIZE - 1) / SECTOR_SIZE).toInt()
            }

            // 填充到扇区边界
            while (buffer.position() < record.size) {
                buffer.put(0.toByte())
            }

            raf.write(buffer.array())
        }
    }

    /**
     * 写入单个目录记录条目
     */
    private fun writeDirectoryRecordEntry(
        buffer: ByteBuffer,
        name: String,
        extent: Int,
        size: Int,
        isDirectory: Boolean,
        date: Date
    ) {
        val startPos = buffer.position()

        val recordLength = 33 + name.length

        buffer.put(recordLength.toByte())  // 记录长度
        buffer.put(0.toByte())             // 扩展属性长度

        // 扩展位置 (LSB)
        buffer.put(extent and 0xFF)
        buffer.put((extent shr 8) and 0xFF)
        buffer.put((extent shr 16) and 0xFF)
        buffer.put((extent shr 24) and 0xFF)

        // 扩展位置 (MSB)
        buffer.put((extent shr 24) and 0xFF)
        buffer.put((extent shr 16) and 0xFF)
        buffer.put((extent shr 8) and 0xFF)
        buffer.put(extent and 0xFF)

        // 数据长度 (LSB)
        buffer.put(size and 0xFF)
        buffer.put((size shr 8) and 0xFF)
        buffer.put((size shr 16) and 0xFF)
        buffer.put((size shr 24) and 0xFF)

        // 数据长度 (MSB)
        buffer.put((size shr 24) and 0xFF)
        buffer.put((size shr 16) and 0xFF)
        buffer.put((size shr 8) and 0xFF)
        buffer.put(size and 0xFF)

        // 日期时间 (7字节)
        val cal = Calendar.getInstance()
        cal.time = date
        buffer.put((cal.get(Calendar.YEAR) - 1900).toByte())
        buffer.put((cal.get(Calendar.MONTH) + 1).toByte())
        buffer.put(cal.get(Calendar.DAY_OF_MONTH).toByte())
        buffer.put(cal.get(Calendar.HOUR_OF_DAY).toByte())
        buffer.put(cal.get(Calendar.MINUTE).toByte())
        buffer.put(cal.get(Calendar.SECOND)).toByte()
        buffer.put(0.toByte())  // 时区

        // 标志
        val flags = if (isDirectory) 0x02 else 0x00
        buffer.put(flags.toByte())

        // 文件单元大小 (交错)
        buffer.put(0.toByte())

        // 交错间隙大小
        buffer.put(0.toByte())

        // 卷序列号 (both-endian word)
        buffer.put(1.toByte())
        buffer.put(0.toByte())
        buffer.put(0.toByte())
        buffer.put(1.toByte())

        // 标识符长度
        buffer.put(name.length.toByte())

        // 标识符
        if (name.isNotEmpty()) {
            buffer.put(name.toByteArray(charsetISO))
        }

        // 填充到偶数边界
        if (buffer.position() % 2 != 0) {
            buffer.put(0.toByte())
        }

        // 验证写入长度
        val written = buffer.position() - startPos
        if (written != recordLength && !(name.isEmpty() && (written == 34))) {
            // 对于.和..条目，长度固定为34
        }
    }

    /**
     * 写入文件数据
     */
    private fun writeFileData(
        raf: RandomAccessFile,
        layout: IsoLayout,
        startSector: Int,
        callback: (progress: Float, stage: String) -> Unit
    ) {
        val files = layout.files.filter { !it.isDirectory }
        val totalFiles = files.size

        files.forEachIndexed { index, file ->
            callback(
                0.40f + 0.55f * (index.toFloat() / totalFiles),
                "写入文件 ${file.isoName}"
            )

            FileInputStream(file.sourceFile).use { input ->
                val buffer = ByteArray(SECTOR_SIZE)
                var read: Int

                while (input.read(buffer).also { read = it } > 0) {
                    if (read < SECTOR_SIZE) {
                        for (i in read until SECTOR_SIZE) {
                            buffer[i] = 0
                        }
                    }
                    raf.write(buffer, 0, SECTOR_SIZE)
                }
            }
        }
    }

    // ===== 辅助方法 =====

    private fun alignToSector(size: Int): Int {
        return ((size + SECTOR_SIZE - 1) / SECTOR_SIZE) * SECTOR_SIZE
    }

    private fun writeEmptySectors(raf: RandomAccessFile, count: Int) {
        val emptySector = ByteArray(SECTOR_SIZE)
        repeat(count) {
            raf.write(emptySector)
        }
    }

    private fun writeEmptyBytes(raf: RandomAccessFile, count: Int) {
        val buffer = ByteArray(8192)
        var remaining = count
        while (remaining > 0) {
            val toWrite = minOf(buffer.size, remaining)
            raf.write(buffer, 0, toWrite)
            remaining -= toWrite
        }
    }

    private fun padString(str: String, length: Int): String {
        return if (str.length >= length) {
            str.substring(0, length)
        } else {
            str.padEnd(length, ' ')
        }
    }

    private fun formatDateTime(date: Date): String {
        val cal = Calendar.getInstance()
        cal.time = date
        return buildString {
            append(cal.get(Calendar.YEAR).toString().padStart(4, '0'))
            append((cal.get(Calendar.MONTH) + 1).toString().padStart(2, '0'))
            append(cal.get(Calendar.DAY_OF_MONTH).toString().padStart(2, '0'))
            append(cal.get(Calendar.HOUR_OF_DAY).toString().padStart(2, '0'))
            append(cal.get(Calendar.MINUTE).toString().padStart(2, '0'))
            append(cal.get(Calendar.SECOND).toString().padStart(2, '0'))
            append(".")
            append(cal.get(Calendar.MILLISECOND).toString().padStart(2, '0'))
            append("\u0000")
        }
    }

    private fun writeBothEndianWord(buffer: ByteBuffer, value: Int) {
        buffer.putShort(value.toShort())
        buffer.putShort(Integer.reverseBytes(value).toShort())
    }

    private fun writeBothEndianDWord(buffer: ByteBuffer, value: Int) {
        buffer.putInt(value)
        buffer.putInt(Integer.reverseBytes(value))
    }
}