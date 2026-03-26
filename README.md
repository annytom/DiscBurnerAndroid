# 企业光盘刻录 App - DiscBurner

面向政府机构/银行的企业级Android光盘刻录应用，支持DAO模式刻录、SHA256自动校验和完整审计日志。

## 特性

- **DAO模式刻录** - Disc At Once模式确保光盘完整性
- **自动校验** - 刻录后SHA256校验，确保数据一致性
- **审计日志** - 完整的操作记录，支持法规遵从
- **前台服务** - 刻录过程保活，防止被系统杀死
- **USB即插即用** - 自动检测USB刻录机

## 技术栈

- Kotlin
- Jetpack Compose
- USB Host API
- SCSI/MMC命令集
- Coroutines + Flow

## 项目结构

```
app/src/main/java/com/enterprise/discburner/
├── data/           # 数据层（审计日志、结果类）
├── usb/            # USB/SCSI协议层
├── service/        # 刻录服务
├── ui/             # 界面层
└── util/           # 工具类
```

## 支持的刻录机

应用内置了常见USB-SATA桥接芯片的VID列表，支持市面上大多数USB外置刻录机：
- Initio, JMicron, MediaTek, ASMedia 等芯片方案
- LG, Lite-On, ASUS, Pioneer, Plextor 等品牌

## 使用方法

1. 连接USB刻录机到手机/平板
2. 应用自动检测设备并请求权限
3. 选择指定目录中的ISO文件
4. 点击"开始刻录"
5. 等待刻录和校验完成
6. 导出审计日志（如需）

## 审计日志

日志保存在：`/Android/data/com.enterprise.discburner/files/audit_logs/`

可通过"导出日志"功能导出为ZIP文件。

## 注意事项

- 需要Android 10+ (API 29+)
- 需要USB Host功能
- 需要存储权限（MANAGE_EXTERNAL_STORAGE）
- 刻录期间保持设备唤醒

## 许可证

内部使用
