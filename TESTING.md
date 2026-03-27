# 测试文档

## 测试结构

```
app/src/
├── test/                          # 单元测试（JVM）
│   └── java/com/enterprise/discburner/
│       ├── filesystem/
│       │   └── Iso9660GeneratorTest.kt    # ISO生成器测试
│       ├── usb/
│       │   └── ScsiCommandsTest.kt        # SCSI命令测试
│       ├── data/
│       │   └── AuditLoggerTest.kt         # 审计日志测试
│       ├── ui/
│       │   └── BurnViewModelTest.kt       # ViewModel测试
│       └── util/
│           └── ExtensionsTest.kt          # 工具函数测试
│
└── androidTest/                   # 集成测试（Android设备）
    └── java/com/enterprise/discburner/
        ├── ExampleInstrumentedTest.kt
        ├── usb/
│       │   └── MultiSessionDiscBurnerIntegrationTest.kt  # 刻录器集成测试
│       └── ui/
│           └── BurnScreenUiTest.kt          # UI测试
```

## 运行测试

### 单元测试

```bash
# 运行所有单元测试
./gradlew test

# 运行特定测试类
./gradlew test --tests "com.enterprise.discburner.filesystem.Iso9660GeneratorTest"

# 运行特定测试方法
./gradlew test --tests "com.enterprise.discburner.filesystem.Iso9660GeneratorTest.generateIso with single file creates valid ISO"
```

### 集成测试

```bash
# 连接Android设备后运行
./gradlew connectedAndroidTest

# 或
./gradlew connectedCheck
```

### 代码覆盖率

```bash
# 生成覆盖率报告
./gradlew jacocoTestReport

# 查看报告：app/build/reports/jacoco/html/index.html
```

## 测试说明

### 单元测试

#### Iso9660GeneratorTest
测试ISO 9660文件系统生成器的各项功能：
- 空文件列表处理
- 单文件刻录
- 多文件刻录
- 目录结构保留
- 大文件处理（1MB）
- 特殊文件名
- 卷标长度限制
- 空文件处理
- 不存在的文件错误处理
- 进度回调顺序

#### ScsiCommandsTest
测试SCSI命令构建：
- INQUIRY命令格式
- TEST UNIT READY命令
- READ CAPACITY命令
- WRITE(10)命令参数
- READ(10)命令格式
- 各种命令常量值验证

#### AuditLoggerTest
测试审计日志系统：
- 基本日志记录
- 日志格式验证
- 多事件记录
- 日志导出为ZIP
- 按日期过滤导出
- 特定会话日志获取
- 所有审计事件类型验证
- 复杂详情记录

#### BurnViewModelTest
测试ViewModel状态管理：
- 初始状态验证
- 设备连接状态
- 文件添加/移除
- ISO文件选择
- 卷标设置
- 写入模式切换
- 刻录进度更新
- 完成结果处理
- 日志记录和清除

#### ExtensionsTest
测试工具函数：
- 文件大小格式化（GB/MB/KB/B）
- 时长格式化（时:分:秒）
- ByteArray转Hex
- 文件SHA256计算
- ISO文件有效性检查

### 集成测试

#### MultiSessionDiscBurnerIntegrationTest
需要实际硬件的集成测试：
- **设备连接检测** - 验证USB刻录机识别
- **完整刻录流程** - 空白光盘DAO刻录
- **多会话追加** - TAO模式补刻
- **错误处理** - 无光盘、空间不足
- **刻录取消** - 中途取消清理
- **校验失败** - 数据完整性错误
- **DAO模式限制** - 不支持追加验证

### UI测试

#### BurnScreenUiTest
Compose界面测试：
- 初始界面显示
- 设备连接状态更新
- 文件选择列表
- 写入模式选择
- 进度显示
- 成功/失败结果显示
- 标签页切换
- 日志显示
- 按钮状态管理

## 模拟对象

### USB设备模拟

```kotlin
// 使用Mockito模拟USB设备
val mockDevice = mock(UsbDevice::class.java)
`when`(mockDevice.deviceName).thenReturn("Test Burner")
`when`(mockDevice.vendorId).thenReturn(0x13FD)
```

### 刻录器状态模拟

```kotlin
// 模拟光盘分析结果
val mockAnalysis = DiscAnalysisResult(
    discInfo = DiscInformation(/* ... */),
    sessions = emptyList(),
    tracks = emptyList(),
    canAppend = false,
    canBurnNew = true,
    recommendedMode = WriteMode.DAO
)
```

## 测试数据

### 测试文件生成

```kotlin
// 创建测试ISO文件
fun createTestIsoFile(size: Long): File {
    val file = File.createTempFile("test", ".iso")
    RandomAccessFile(file, "rw").use { raf ->
        raf.setLength(size)
        // 写入ISO签名
        raf.seek(2048 * 16 + 1)
        raf.write("CD001".toByteArray())
    }
    return file
}
```

### 测试目录结构

```
test-data/
├── single-file/
│   └── test.txt
├── multi-files/
│   ├── file1.txt
│   ├── file2.txt
│   └── file3.txt
├── nested/
│   ├── root.txt
│   └── subdir/
│       └── nested.txt
└── large/
    └── 1gb.bin
```

## 持续集成

### GitHub Actions配置

```yaml
name: Tests

on: [push, pull_request]

jobs:
  unit-tests:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v2
      - name: Set up JDK 17
        uses: actions/setup-java@v2
        with:
          java-version: '17'
          distribution: 'adopt'
      - name: Run unit tests
        run: ./gradlew test
      - name: Upload coverage
        uses: codecov/codecov-action@v2

  integration-tests:
    runs-on: macos-latest  # Android模拟器需要macOS
    steps:
      - uses: actions/checkout@v2
      - name: Set up JDK 17
        uses: actions/setup-java@v2
        with:
          java-version: '17'
          distribution: 'adopt'
      - name: Start emulator
        uses: reactivecircus/android-emulator-runner@v2
        with:
          api-level: 29
          script: ./gradlew connectedCheck
```

## 已知限制

1. **硬件依赖测试** - 集成测试需要实际USB刻录机，CI环境无法自动运行
2. **Android版本** - UI测试需要API 29+设备
3. **存储权限** - 部分测试需要WRITE_EXTERNAL_STORAGE权限

## 调试测试

### 查看测试日志

```bash
./gradlew test --info
./gradlew test --debug
```

### 生成测试报告

```bash
# 查看HTML报告
open app/build/reports/tests/test/index.html

# 查看XML报告
cat app/build/test-results/test/*.xml
```

## 添加新测试

### 单元测试模板

```kotlin
package com.enterprise.discburner.xxx

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NewFeatureTest {

    @Test
    fun `test description`() {
        // Arrange
        val input = ...

        // Act
        val result = ...

        // Assert
        assertEquals(expected, result)
    }
}
```

### 集成测试模板

```kotlin
package com.enterprise.discburner.xxx

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NewIntegrationTest {

    @Test
    fun `integration test description`() {
        // 需要设备/硬件的测试
    }
}
```
