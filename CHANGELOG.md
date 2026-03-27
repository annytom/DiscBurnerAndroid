# Changelog

所有版本更新记录遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.0.0/) 规范。

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

## [Unreleased]

### 计划中
- 多光盘连续刻录
- 刻录速度调节
- 二维码标签生成
- 远程日志同步
