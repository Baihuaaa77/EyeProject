# core:testing

> 测试工具模块：各模块单测共享的 JUnit 规则与工具，以 `testImplementation` 引用。

## 职责

- 提供 ViewModel/协程单测的基础设施

## 对外 API

| 类型 | 说明 |
|---|---|
| `MainDispatcherRule` | JUnit4 `TestWatcher` 规则：测试前 `Dispatchers.setMain(testDispatcher)`，结束后 `resetMain()`。默认 `UnconfinedTestDispatcher`，可传入自定义 `TestDispatcher` |

用法示例：

```kotlin
@get:Rule
val mainDispatcherRule = MainDispatcherRule()
```

## 依赖关系

- **依赖**：`kotlinx-coroutines-test`、`junit`（均以 `api` 暴露，消费方无需重复声明）
- **被依赖**：暂无消费方 —— `BlinkDetector` / `DepthFromIris` / ViewModel 等单测补齐时从这里开始

## 注意事项

- 只放测试基础设施，不放业务测试数据；以 `testImplementation(project(":core:testing"))` 引用，不要打进 release
