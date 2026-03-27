# Changelog

所有版本更新记录遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.0.0/) 规范。

## [2.0.0] - 2026-03-27

### 新增（阶段2完成）
- Room数据库持久化审计日志
- 刻录队列管理（多任务排队、优先级、自动/手动模式）
- 队列管理UI界面（QueueScreen.kt）
- 历史记录UI界面（HistoryScreen.kt）
- SCSI命令重试机制（指数退避、可重试错误识别）
- 审计日志数字签名（HMAC-SHA256防篡改）
- 刻录速度控制（支持4x/8x/16x/24x/32x/48x/52x）
- 刻录会话完整记录（开始/完成/失败状态）
- 设备信息管理（使用统计）
- 刻录文件哈希追踪
- 增强的日志导出（数据库JSON+文本ZIP双格式）
- 应用图标资源（ic_launcher_foreground/background）

### 改进
- 审计日志系统完全重写（EnhancedAuditLogger）
- BurnService支持队列管理
- ISO 9660生成器完整实现
- 主题配置完善（状态栏/导航栏颜色）
- 完整单元测试覆盖（18+新测试用例）

### UI
- 新增队列管理界面（标签页：待处理/进行中/已完成/失败）
- 新增历史记录界面（搜索、筛选、统计）
- 自动处理开关控制
- 任务优先级可视化标识
- 刻录统计概览卡片

### 测试
- EnhancedAuditLoggerTest（数字签名、篡改检测、导出）
- ScsiRetryManagerTest（重试机制、指数退避）
- BurnQueueManagerTest（队列操作、并发安全）

### 架构
- 新增Room数据库模块（Entities、Dao、Database）
- 新增Repository层
- 新增ScsiRetryManager
- 新增BurnQueueManager
- 新增ui/screens包（QueueScreen、HistoryScreen）

## [1.3.0] - 2026-03-27

### 新增
- UI 屏幕组件（HistoryScreen, QueueScreen）
- 代码审查报告（CODE_REVIEW_REPORT.md）
- 启动器图标资源
- EnhancedAuditLogger 单元测试
- ScsiRetryManager 单元测试

### 改进
- 更新数据库 DAO 接口
- 优化多区段刻录器
- 更新主题样式
- 完善文档（README, PHASE2_SUMMARY）

## [1.2.0] - 2026-03-27

### 新增
- Room 数据库持久化（审计日志、刻录会话、设备信息）
- SCSI 命令重试机制（指数退避策略，最大3次重试）
- 刻录队列管理器（多任务排队、优先级管理、批量刻录）
- 增强审计日志系统（数字签名防篡改、双存储模式）
- 设备信息追踪和统计功能

### 改进
- BurnService 支持队列模式刻录
- 数据库支持按时间范围查询和搜索
- 自动同步标记（预留远程同步接口）

### 架构
- 添加 Room 数据库依赖
- 添加 Kotlinx Serialization 依赖
- 添加 Security Crypto 依赖

## [1.1.0] - 2026-03-27

### 新增
- ISO9660 文件系统镜像生成器
- 多区段光盘刻录支持 (MultiSession)
- 完整的单元测试和仪器测试框架
- Phase 1 修复文档 (PHASE1_FIXES.md)
- UI 图标资源 (ic_disc, ic_error)

### 改进
- 优化刻录服务架构
- 增强 ViewModel UI 状态管理
- 改进 SCSI 命令处理
- 完善项目文档和开发者指南

### 测试
- 添加 AuditLogger 单元测试
- 添加 Iso9660Generator 测试套件
- 添加 BurnViewModel 测试
- 添加 SCSI 命令测试
- 添加 UI 屏幕测试

## [1.0.0] - 2024-03-27

### 新增
- DAO模式光盘刻录（Disc At Once）
- SHA256自动校验功能
- 完整审计日志系统
- USB刻录机自动检测
- 前台服务保活机制
- Material Design 3 界面
- 审计日志ZIP导出
- 多设备芯片支持

### 技术特性
- Kotlin Coroutines + Flow 响应式编程
- Jetpack Compose UI框架
- USB Host API + SCSI命令集
- Android 10+ (API 29+) 支持

### 安全
- 数据完整性校验
- 结构化审计日志
- 错误码标准化

## [Unreleased]

### 计划中
- 刻录速度调节
- 二维码标签生成
- 远程日志同步
- UDF文件系统支持
- 蓝光光盘支持
