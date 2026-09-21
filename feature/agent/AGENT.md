# feature:agent

> 眼健康智能体对话页：当前为占位实现（发送后固定回复「开发中」），预留接入 LLM 的结构。

## 职责

- 对话 UI 与消息状态管理，后续对接真实智能体后端

## 结构

| 类型 | 说明 |
|---|---|
| `AgentMessage` / `AgentMessageRole` | 消息模型（USER / AGENT） |
| `AgentUiState` | 消息列表 + 输入框文本 |
| `AgentViewModel` | `@HiltViewModel`：`onInputChange` / `send()`（追加用户消息 + 占位回复） |
| `AgentScreen` | 对话页 UI（含 `TopBackBar` 返回） |
| `navigation/AgentNavigation` | `AgentRoute` + `navigateToAgent()` + `NavGraphBuilder.agentScreen(onBack)` |

## 依赖关系

- **依赖**：`core:designsystem`、`core:ui`
- **被依赖**：`app`（NavHost 挂 agentScreen）

## 注意事项

- 接入真实 LLM 时：网络与密钥不进本模块，走 core 层（新增 `core:network` / `core:datastore`）+ 注入接口
- 消息模型目前是模块内私有 data class；若多页面复用（如推送会话）再上移 `core:model`
