# SCSI命令速查表

## 命令分类

### 基本命令 (SPC)

| 命令 | 代码 | 功能 | 使用场景 |
|------|------|------|----------|
| TEST UNIT READY | 0x00 | 检查设备就绪 | 初始化、轮询 |
| INQUIRY | 0x12 | 查询设备信息 | 设备识别 |
| REQUEST SENSE | 0x03 | 获取错误详情 | 错误处理 |
| MODE SELECT | 0x15 | 设置设备参数 | DAO模式配置 |
| MODE SENSE | 0x1A | 读取设备参数 | 状态查询 |

### 读写命令 (SBC)

| 命令 | 代码 | 功能 | 参数 |
|------|------|------|------|
| READ CAPACITY(10) | 0x25 | 读取容量 | - |
| READ(10) | 0x28 | 读取扇区 | LBA, 长度 |
| WRITE(10) | 0x2A | 写入扇区 | LBA, 长度 |
| SYNCHRONIZE CACHE | 0x35 | 刷新缓存 | - |

### 光盘命令 (MMC)

| 命令 | 代码 | 功能 | 使用场景 |
|------|------|------|----------|
| READ DISC INFORMATION | 0x51 | 读取光盘信息 | 刻录前检查 |
| READ TRACK INFORMATION | 0x52 | 读取轨道信息 | 多轨道管理 |
| RESERVE TRACK | 0x53 | 预留轨道空间 | DAO模式 |
| CLOSE TRACK/SESSION | 0x5B | 关闭轨道/会话 | 刻录完成 |
| BLANK | 0xA1 | 擦除光盘 | 可重写光盘 |

## 命令格式详解

### CBW (Command Block Wrapper)

```
Byte  Offset  Description
─────────────────────────────────────────
0-3    0       Signature: 0x43425355 ("USBC")
4-7    4       Tag: 命令标识
8-11   8       Data Transfer Length
12     12      Flags: 0x80=IN, 0x00=OUT
13     13      LUN: 逻辑单元号 (通常为0)
14     14      CB Length: CDB长度
15-30  15      Command Block (CDB)
```

### CSW (Command Status Wrapper)

```
Byte  Offset  Description
─────────────────────────────────────────
0-3   0       Signature: 0x53425355 ("USBS")
4-7   4       Tag: 匹配的CBW Tag
8-11  8       Data Residue
12    12      Status: 0=OK, 1=Failed, 2=Phase Error
```

## 常用CDB格式

### WRITE(10) - 写入数据

```
Byte  Value    Description
─────────────────────────────────────────
0     0x2A     Operation Code
1     0x00     Flags (FUA=bit3)
2-5   LBA      Logical Block Address (Big Endian)
6     0x00     Group Number
7-8   Length   Transfer Length (sectors)
9     0x00     Control
```

### READ DISC INFORMATION

```
Byte  Value    Description
─────────────────────────────────────────
0     0x51     Operation Code
1     0x00     Data Type
2-6   0x00     Reserved
7-8   0x0022   Allocation Length (34 bytes)
9     0x00     Control
```

返回数据结构：

```
Byte  Description
─────────────────────────────────────────
0     Disc Information Length
1     Disc Status (0=空白, 1=追加, 2=完整, 3=其他)
2     State of Last Session
3     Number of Sessions
4     First Track Number
5     Last Track Number
6     Flags (Erasable, Copy, etc.)
7-9   Reserved
10-13 Next Writable Address
14-17 Free Blocks
```

## 写模式设置

### DAO模式参数页

```kotlin
val writeParamsPage = byteArrayOf(
    0x05,       // Page Code = Write Parameters
    0x32,       // Page Length = 50
    0x00, 0x00, // Reserved

    // Byte 4: Write Type & Multi-session
    0x01,       // Write Type = 1 (DAO)
                // Multi-session = 0 (无多会话)

    // Byte 5: Track Mode
    0x00,       // Track Mode = 0 (Mode 1)

    // Byte 6: Data Block Type
    0x08,       // Data Block Type = 8 (Mode 1)

    // ... 其他参数
)
```

## 错误处理

### Sense Key 值

| Key | 名称 | 描述 |
|-----|------|------|
| 0x00 | NO SENSE | 无错误 |
| 0x01 | RECOVERED ERROR | 已恢复的错误 |
| 0x02 | NOT READY | 设备未就绪 |
| 0x03 | MEDIUM ERROR | 介质错误 |
| 0x04 | HARDWARE ERROR | 硬件错误 |
| 0x05 | ILLEGAL REQUEST | 非法请求 |
| 0x06 | UNIT ATTENTION | 设备状态变化 |
| 0x07 | DATA PROTECT | 写保护 |

### 常见 ASC/ASCQ

| ASC | ASCQ | 描述 |
|-----|------|------|
| 0x04 | 0x01 | 正在初始化 |
| 0x0C | 0x09 | 写错误 - 无损链接 |
| 0x11 | 0x00 | 不可恢复的读错误 |
| 0x27 | 0x00 | 写保护 |
| 0x28 | 0x00 | 检测到空白光盘 |
| 0x30 | 0x05 | 不兼容介质 |
| 0x3A | 0x00 | 介质未加载 |
| 0x3E | 0x02 | 超时 |
| 0x51 | 0x00 | 擦除失败 |
| 0x57 | 0x00 | 无法替换RZONE |
| 0x5D | 0x00 | 失败预测阈值超出 |
| 0x72 | 0x03 | 会话固定失败 |
| 0x72 | 0x04 | 轨道固定失败 |

## USB端点类型

| 类型 | 地址 | 用途 |
|------|------|------|
| Bulk OUT | 0x01 | 发送命令/数据到设备 |
| Bulk IN  | 0x82 | 从设备接收数据/状态 |

## 传输超时建议

| 操作 | 建议超时 |
|------|----------|
| CBW发送 | 5秒 |
| 数据写入 | 30-60秒 |
| 数据读取 | 30-60秒 |
| CSW接收 | 5秒 |
| 缓存同步 | 60秒 |
| 关闭会话 | 120秒 |

## 参考文档

- [SCSI Primary Commands - 4 (SPC-4)](http://www.t10.org/cgi-bin/ac.pl?t=f&f=spc4r36.pdf)
- [SCSI Multimedia Commands - 5 (MMC-5)](http://www.t10.org/cgi-bin/ac.pl?t=f&f=mmc5r02.pdf)
- [USB Mass Storage Class - Bulk Only Transport](https://www.usb.org/sites/default/files/usbmassbulk_10.pdf)
