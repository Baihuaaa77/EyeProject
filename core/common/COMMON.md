# core:common

> 通用纯函数工具：时间与光照的格式化，零业务、零 Android 依赖（仅 JDK）。

## 职责

- 提供各 feature 界面直接可用的展示层格式化函数

## 对外 API

| 函数 | 说明 |
|---|---|
| `formatDuration(millis: Long): String` | 毫秒时长 → `"1h 05m"` / `"3m 20s"` / `"45s"` |
| `formatTime(millis: Long): String` | 时间戳 → 短时间文本 |
| `formatLux(lux: Float): String` | 光照 → `"320.5 lx"`（<100 保留 1 位小数） |
| `luxDetail(lux: Float): String` | lux → `"环境偏暗"` / `"光线正常"` / `"环境过亮"`（阈值 50 / 1000） |

## 依赖关系

- **依赖**：无
- **被依赖**：`feature:home`

## 注意事项

- 新增工具函数请保持纯函数（无 Context、无状态），依赖 Android 的工具放对应 core 模块
