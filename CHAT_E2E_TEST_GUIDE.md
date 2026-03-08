# Chat E2E 测试使用指南

**测试类**: `ChatE2ETest.kt`
**测试目的**: 测试用户通过Chat界面与AI交互的完整流程
**测试特点**: 真实模拟用户输入→AI处理→验证回复的完整链路

---

## 📋 测试设计

### 测试流程

每个测试用例都遵循统一的6步流程:

```
1. 📸 收集发送前的屏幕信息
   ↓
2. ⌨️  在输入框输入内容
   ↓
3. 📤 点击发送按钮
   ↓
4. ⏳ 等待10-20秒让AI处理
   ↓
5. 📸 收集AI回复后的屏幕信息
   ↓
6. ✅ 验证AI回复是否符合预期
```

### 核心特性

- ✅ **一次启动** - 使用`@BeforeClass`,应用只启动一次
- ✅ **顺序执行** - 使用`@FixMethodOrder`,按test01→test10顺序执行
- ✅ **灵活验证** - 支持关键词验证和自定义验证函数
- ✅ **详细日志** - 每个步骤都有清晰的日志输出
- ✅ **屏幕对比** - 通过前后屏幕对比提取AI回复

---

## 🧪 测试用例列表

### 1. test01_simpleGreeting - 简单问候
**输入**: "你好"
**验证**: AI回复包含问候语(你好/您好/hi/hello)
**用途**: 测试基础对话能力

### 2. test02_screenshotRequest - 截图请求
**输入**: "给我截图看看"
**验证**: AI回复提到截图相关内容
**用途**: 测试screenshot skill调用

### 3. test03_waitRequest - 等待请求
**输入**: "等待3秒"
**验证**: AI回复确认等待操作
**用途**: 测试wait skill调用

### 4. test04_homeNavigation - 返回主屏幕
**输入**: "回到主屏幕"
**验证**: AI回复确认导航操作
**用途**: 测试home skill调用
**注意**: 此测试会离开应用,测试后会自动返回

### 5. test05_sendNotification - 发送通知
**输入**: "发送一个通知,标题是'测试',内容是'这是测试通知'"
**验证**: AI回复确认通知发送
**用途**: 测试notification skill调用

### 6. test06_logMessage - 记录日志
**输入**: "记录一条日志:测试消息"
**验证**: AI回复确认日志记录
**用途**: 测试log skill调用

### 7. test07_multiStepTask - 复杂多步骤任务
**输入**: "先截图,然后等待2秒,再记录一条日志说'任务完成'"
**验证**: AI回复提到至少一个步骤
**用途**: 测试Agent的多步骤任务规划和执行能力
**等待时间**: 20秒(更长)

### 8. test08_queryCapabilities - 询问能力
**输入**: "你能做什么"
**验证**: AI回复至少提到2种能力
**用途**: 测试Agent自我介绍能力

### 9. test09_screenObservation - 屏幕观察
**输入**: "看看屏幕上有什么"
**验证**: AI回复描述屏幕内容
**用途**: 测试AI视觉理解能力
**等待时间**: 15秒

### 10. test10_errorHandling - 错误处理
**输入**: "asdfghjkl" (无意义输入)
**验证**: AI能处理无效输入(回复不为空或表示不理解)
**用途**: 测试错误处理和用户体验

---

## 🔧 运行测试

### 运行所有Chat测试
```bash
adb shell am instrument -w \
  -e class com.xiaomo.androidforclaw.e2e.ChatE2ETest \
  com.xiaomo.androidforclaw.debug.test/androidx.test.runner.AndroidJUnitRunner
```

### 运行单个测试
```bash
# 测试简单问候
adb shell am instrument -w \
  -e class com.xiaomo.androidforclaw.e2e.ChatE2ETest#test01_simpleGreeting \
  com.xiaomo.androidforclaw.debug.test/androidx.test.runner.AndroidJUnitRunner

# 测试截图请求
adb shell am instrument -w \
  -e class com.xiaomo.androidforclaw.e2e.ChatE2ETest#test02_screenshotRequest \
  com.xiaomo.androidforclaw.debug.test/androidx.test.runner.AndroidJUnitRunner
```

### 运行多个相关测试
```bash
# 测试Skills调用(test02-07)
adb shell am instrument -w \
  -e class com.xiaomo.androidforclaw.e2e.ChatE2ETest#test02_screenshotRequest,\
com.xiaomo.androidforclaw.e2e.ChatE2ETest#test03_waitRequest,\
com.xiaomo.androidforclaw.e2e.ChatE2ETest#test04_homeNavigation \
  com.xiaomo.androidforclaw.debug.test/androidx.test.runner.AndroidJUnitRunner
```

---

## 📝 添加新测试用例

### 步骤1: 定义测试用例

```kotlin
/**
 * 测试N: 你的测试名称 - "用户输入内容"
 * 验证AI能做什么
 */
@Test
fun testN_yourTestName() {
    val userInput = "你的测试输入"
    val expectedKeywords = listOf("关键词1", "关键词2")

    testChatInteraction(
        testName = "你的测试名称",
        userInput = userInput,
        expectedKeywords = expectedKeywords,
        verifyFunc = { response ->
            // 自定义验证逻辑
            assertTrue("验证描述", response.contains("期望内容"))
        }
    )
}
```

### 步骤2: 选择验证方式

**方式1: 关键词验证** (简单)
```kotlin
testChatInteraction(
    testName = "测试名称",
    userInput = "用户输入",
    expectedKeywords = listOf("关键词1", "关键词2")
    // 不提供verifyFunc,自动检查关键词
)
```

**方式2: 自定义验证** (灵活)
```kotlin
testChatInteraction(
    testName = "测试名称",
    userInput = "用户输入",
    expectedKeywords = listOf(), // 可以为空
    verifyFunc = { response ->
        // 你的验证逻辑
        val mentionedCount = keywords.count { response.contains(it) }
        assertTrue("应该提到至少2个关键词", mentionedCount >= 2)
    }
)
```

### 步骤3: 调整等待时间(可选)

```kotlin
testChatInteraction(
    testName = "测试名称",
    userInput = "用户输入",
    expectedKeywords = listOf("关键词"),
    waitTime = 20000L, // 20秒,适用于复杂任务
    verifyFunc = { ... }
)
```

---

## 🎯 测试用例设计原则

### 1. 输入设计原则

- ✅ **明确性**: 输入应该清晰表达意图
  - 好: "给我截图看看"
  - 差: "做点什么"

- ✅ **多样性**: 覆盖不同的表达方式
  - "截图" / "给我截图" / "帮我截个图"

- ✅ **复杂度**: 从简单到复杂递进
  - 简单: "你好"
  - 中等: "等待3秒"
  - 复杂: "先截图,然后等待2秒,再..."

### 2. 验证设计原则

- ✅ **关键词验证**: 适用于简单场景
  ```kotlin
  expectedKeywords = listOf("截图", "screenshot", "完成")
  ```

- ✅ **自定义验证**: 适用于复杂场景
  ```kotlin
  verifyFunc = { response ->
      val hasConfirmation = response.contains("完成") || response.contains("好的")
      val hasResult = response.contains("已保存")
      assertTrue("应该确认并给出结果", hasConfirmation && hasResult)
  }
  ```

- ✅ **容错性**: 允许多种正确答案
  ```kotlin
  // 接受任意一个关键词即可
  expectedKeywords.any { response.contains(it) }
  ```

### 3. 等待时间设计

| 任务类型 | 推荐等待时间 | 示例 |
|---------|------------|------|
| 简单对话 | 10秒 | "你好" |
| 单个Skill | 15秒 | "截图" |
| 多步骤任务 | 20-30秒 | "先截图,再等待..." |
| 视觉理解 | 15-20秒 | "看看屏幕上有什么" |

---

## 🔍 调试技巧

### 1. 查看详细日志

测试运行时会输出详细的步骤日志:

```
==================================================
🧪 测试: 简单问候
==================================================
📝 用户输入: "你好"

📸 步骤1: 收集发送前的屏幕信息
  ✓ 发送前状态: 12个文本元素

⌨️  步骤2: 输入内容
  ✓ 已输入: "你好"

📤 步骤3: 点击发送
  ✓ 消息已发送

⏳ 步骤4: 等待AI响应 (15秒)
  ✓ 等待完成

📸 步骤5: 收集AI回复后的屏幕信息
  ✓ 回复后状态: 15个文本元素

🔍 步骤6: 提取AI回复
  💬 AI回复: 你好!我是AndroidForClaw...

✅ 步骤7: 验证AI回复
  📋 期望关键词: [你好, 您好, hi, hello]
  ✓ 找到关键词: [你好]

✅ 测试通过: 简单问候
==================================================
```

### 2. 检查输入框是否找到

如果测试跳过,检查日志:
```
⌨️  步骤2: 输入内容
  ⚠️ 未找到输入框,跳过此测试
```

**解决方法**: 更新`findInputBox()`方法中的选择器

### 3. 检查AI是否回复

如果验证失败,检查提取的AI回复:
```
  💬 AI回复: (未检测到新回复)
```

**可能原因**:
- AI响应时间超过waitTime
- 屏幕信息对比算法未能识别新文本
- AI确实没有回复

### 4. 使用logcat查看完整日志

```bash
adb logcat -c  # 清空日志
# 运行测试...
adb logcat -d | grep "ChatE2E"  # 查看测试日志
```

---

## 📊 测试报告示例

运行完整测试套件后:

```
com.xiaomo.androidforclaw.e2e.ChatE2ETest:..........

Time: 187.523

OK (10 tests)
```

**解读**:
- 10个点(`.`) = 10个测试全部通过
- 总耗时: 187秒 ≈ 3分钟
- 平均每个测试: 18.7秒

---

## 🎨 自定义与扩展

### 扩展1: 添加屏幕截图验证

```kotlin
private fun testChatInteraction(...) {
    // ... 现有逻辑 ...

    // 步骤6.5: 如果是截图任务,验证截图文件
    if (userInput.contains("截图", ignoreCase = true)) {
        val screenshotExists = checkScreenshotFile()
        assertTrue("应该生成截图文件", screenshotExists)
    }
}

private fun checkScreenshotFile(): Boolean {
    val screenshotDir = File("/sdcard/.androidforclaw/screenshots")
    if (!screenshotDir.exists()) return false

    val recentFiles = screenshotDir.listFiles()
        ?.filter { it.lastModified() > System.currentTimeMillis() - 30000 }
    return !recentFiles.isNullOrEmpty()
}
```

### 扩展2: 测试性能指标

```kotlin
data class ScreenInfo(
    val timestamp: Long,
    val texts: List<String>,
    val textCount: Int
) {
    fun responseTime(other: ScreenInfo): Long {
        return other.timestamp - this.timestamp
    }
}

// 在验证中使用
val responseTime = afterScreenInfo.responseTime(beforeScreenInfo)
println("  ⏱️ AI响应时间: ${responseTime}ms")
assertTrue("响应时间应该小于20秒", responseTime < 20000)
```

### 扩展3: 测试不同语言

```kotlin
@Test
fun test11_englishGreeting() {
    testChatInteraction(
        testName = "English Greeting",
        userInput = "Hello",
        expectedKeywords = listOf("hello", "hi", "greetings")
    )
}

@Test
fun test12_multiLanguage() {
    testChatInteraction(
        testName = "Multi-language",
        userInput = "你好,hello,こんにちは",
        expectedKeywords = listOf("你好", "hello", "hi")
    )
}
```

---

## ✅ 最佳实践

1. **保持测试独立性**
   - 每个测试不依赖其他测试的结果
   - 除了test04(home)会离开应用外,其他测试都在应用内

2. **使用有意义的测试名称**
   - 格式: `testNN_功能描述`
   - 例: `test02_screenshotRequest`

3. **提供清晰的验证错误信息**
   ```kotlin
   assertTrue(
       "AI应该回复问候语,实际回复: $aiResponse",
       foundKeywords.isNotEmpty()
   )
   ```

4. **适当的等待时间**
   - 不要太短(AI没时间响应)
   - 不要太长(浪费测试时间)
   - 根据任务复杂度调整

5. **容错处理**
   - 输入框找不到→跳过测试
   - 发送按钮找不到→尝试按回车
   - AI未回复→记录但不崩溃

---

## 📚 相关文档

- [E2E_TEST_FINAL_REPORT.md](E2E_TEST_FINAL_REPORT.md) - 完整E2E测试报告
- [PROTOBUF_FIX_REPORT.md](PROTOBUF_FIX_REPORT.md) - Protobuf冲突修复
- [TESTS_OPTIMIZATION_REPORT.md](TESTS_OPTIMIZATION_REPORT.md) - 测试优化报告

---

**创建时间**: 2026-03-08
**测试框架**: JUnit4 + UiAutomator
**测试类型**: E2E (端到端)
**测试对象**: Chat界面 + AI Agent交互
