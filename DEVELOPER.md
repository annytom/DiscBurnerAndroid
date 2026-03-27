# 开发者文档

## 项目结构详解

```
app/src/main/java/com/enterprise/discburner/
├── data/                          # 数据层
│   ├── AuditLogger.kt            # 审计日志核心
│   ├── AuditEvent.kt             # 事件类型定义（内联在AuditLogger.kt中）
│   ├── AuditCode.kt              # 错误代码枚举（内联在AuditLogger.kt中）
│   ├── BurnResult.kt             # 刻录结果密封类（内联在AuditLogger.kt中）
│   └── VerifyResult.kt           # 校验结果密封类（内联在DiscBurner.kt中）
│
├── usb/                          # USB/SCSI协议层
│   ├── ScsiCommands.kt           # SCSI命令常量与构建器
│   ├── DiscBurner.kt             # DAO刻录器实现
│   ├── UsbBurnerManager.kt       # USB设备管理
│   └── BurnStage.kt              # 刻录阶段枚举（内联在DiscBurner.kt中）
│
├── service/                      # 服务层
│   └── BurnService.kt            # 前台刻录服务
│
├── ui/                          # 界面层
│   ├── MainActivity.kt           # 主界面（Compose）
│   ├── BurnViewModel.kt          # 状态管理
│   └── theme/                    # Material主题
│       ├── Theme.kt
│       ├── Color.kt
│       └── Type.kt
│
└── util/                        # 工具类
    └── Extensions.kt             # 扩展函数
```

## 核心类说明

### DaoDiscBurner

DAO模式刻录的核心类，封装完整的刻录流程。

```kotlin
// 使用示例
val burner = DaoDiscBurner(connection, intf, epIn, epOut, auditLogger)

val result = burner.burnIsoDao(isoFile) { stage, progress ->
    // 更新UI进度
}

when (result) {
    is BurnResult.Success -> { /* 成功处理 */ }
    is BurnResult.Failure -> { /* 失败处理 */ }
}
```

### 关键方法

| 方法 | 说明 | 耗时 |
|------|------|------|
| `burnIsoDao()` | 完整刻录流程 | 取决于光盘容量 |
| `sendCommand()` | 发送SCSI命令 | <1s |
| `buildCbw()` | 构建CBW包 | <1ms |
| `verifyDiscData()` | 校验光盘数据 | 约为刻录时间的一半 |

### 状态机

```
[IDLE] → [DEVICE_PREP] → [DISC_CHECK] → [LEAD_IN]
  ↑                                           ↓
[ERROR] ← [VERIFY_FAILED] ← [VERIFYING] ← [WRITING_DATA]
                                              ↓
                                         [LEAD_OUT]
                                              ↓
                                         [FINALIZING]
                                              ↓
                                         [COMPLETED]
```

## SCSI命令参考

### CBW格式 (31 bytes)

```
Offset  Size  Description
0       4     dCBWSignature: "USBC" (0x43425355)
4       4     dCBWTag: 命令标识
8       4     dCBWDataTransferLength: 数据传输长度
12      1     bmCBWFlags: 0x80=IN, 0x00=OUT
13      1     bCBWLUN: 逻辑单元号
14      1     bCBWCBLength: CDB长度 (1-16)
15      16    CBWCB: SCSI命令块
```

### 常用SCSI命令

```kotlin
// INQUIRY - 设备识别
val inquiry = byteArrayOf(
    0x12,       // Operation Code
    0x00,       // EVPD
    0x00,       // Page Code
    0x00,       // Reserved
    0x24,       // Allocation Length (36 bytes)
    0x00        // Control
)

// WRITE(10) - 写入数据
val write10 = byteArrayOf(
    0x2A,       // Operation Code
    0x00,       // Flags
    lba3, lba2, lba1, lba0,  // LBA (big-endian)
    0x00,       // Group Number
    len1, len0, // Transfer Length
    0x00        // Control
)
```

## 调试技巧

### 启用详细日志

```kotlin
// 在 DaoDiscBurner.kt 中添加
private fun logScsiCommand(command: ByteArray, direction: String) {
    Log.d(TAG, "SCSI [$direction]: ${command.toHex()}")
}

// 在 sendCommand() 中调用
logScsiCommand(command, if (direction == Direction.IN) "IN" else "OUT")
```

### USB抓包分析

使用 Android Studio 的 Device File Explorer 查看：
```
/data/data/com.enterprise.discburner/cache/usb_logs.txt
```

或使用外部USB分析仪抓取SCSI命令流。

## 扩展开发

### 添加新刻录机支持

1. 获取设备VID/PID
   ```bash
   adb shell lsusb
   ```

2. 更新 device_filter.xml
   ```xml
   <usb-device vendor-id="XXXX" product-id="YYYY" />
   ```

3. 测试设备检测
   - 连接设备
   - 查看Logcat: "找到Mass Storage设备"

### 自定义ISO来源

修改 `MainActivity.kt` 中的 `scanIsoFiles()`:

```kotlin
// 添加自定义目录
val customDir = File("/storage/emulated/0/CustomPath")
customDir.listFiles { f -> f.extension == "iso" }?.let { isoFiles.addAll(it) }
```

### 添加刻录速度控制

在 `DaoDiscBurner.kt` 中修改 `sendModeSelectDao()`:

```kotlin
// 添加写速度页
val speedPage = byteArrayOf(
    0x2A,       // Page Code = CD Capabilities
    0x14,       // Page Length = 20
    // ... 速度参数
)
```

## 性能优化

### 缓冲区大小选择

| 缓冲区大小 | 适用场景 | 内存占用 |
|-----------|---------|---------|
| 32KB | 低端设备 | 低 |
| 64KB | 通用（推荐） | 中 |
| 128KB | 高端设备 | 高 |
| 256KB | 服务器级设备 | 很高 |

修改位置：`DaoDiscBurner.kt` 中 `BUFFER_SIZE` 常量。

### 校验优化

- 校验时可降低读取批次大小，避免内存压力
- 考虑使用 `MappedByteBuffer` 处理大文件

## 测试清单

### 功能测试

- [ ] USB设备检测
- [ ] 权限请求流程
- [ ] ISO文件扫描
- [ ] DAO模式刻录
- [ ] SHA256校验
- [ ] 审计日志记录
- [ ] 日志导出功能
- [ ] 前台服务保活
- [ ] 刻录取消功能

### 兼容性测试

- [ ] Android 10 (API 29)
- [ ] Android 11 (API 30)
- [ ] Android 12 (API 31)
- [ ] Android 13 (API 33)
- [ ] Android 14 (API 34)

### 硬件测试

- [ ] DVD-R 4.7GB
- [ ] DVD+R 4.7GB
- [ ] DVD-RW 4.7GB
- [ ] DVD+R DL 8.5GB
- [ ] 不同品牌光盘

## 发布检查

```bash
# 1. 运行测试
./gradlew test

# 2. 构建Release APK
./gradlew assembleRelease

# 3. 签名APK
jarsigner -verbose -sigalg SHA1withRSA -digestalg SHA1 \
    -keystore my-release-key.keystore \
    app-release-unsigned.apk alias_name

# 4. 对齐优化
zipalign -v 4 app-release-unsigned.apk DiscBurner-v1.0.0.apk
```
