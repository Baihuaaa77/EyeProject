# core:domain

源码已迁到 `shared/domain/src/commonMain/kotlin`，本安卓模块转发 KMP JVM 算法。眼区灰度算法与 478 点几何转换也由该共享模块提供。详见 `shared/SHARED.md`。

> 领域层：纯算法（眨眼检测、虹膜测距）+ 由 app 层实现的系统控制接口（提醒）。

## 职责

- 承载可独立测试的核心算法：EAR 眨眼检测、Depth-from-Iris 测距
- 定义需要系统权限/系统服务的能力接口，实现下沉到 app 层（Android 组件只能活在 app）

## 对外 API

### 算法（无状态或自持状态，可直接单测）

| 类型 | 说明 |
|---|---|
| `BlinkDetector` | EAR 眨眼检测：EAR = (‖p2−p6‖+‖p3−p5‖)/(2‖p1−p4‖)，阈值 0.23、连续 3 帧判闭眼，回升记一次眨眼。`update(ear)` 逐帧驱动、`blinkCount` 累计、`reset()` 每采样区间清零、`interrupt()` 跟踪中断时只清闭眼状态；伴生对象提供 `averageEar` / `ear` |
| `DepthFromIris` | object：距离(cm) = 虹膜物理直径 11.7mm × 焦距(px) / 虹膜像素直径(px) / 10 |

### 系统能力接口（实现在 app 层，经 Hilt 绑定）

| 接口 | 实现位置 | 能力 |
|---|---|---|
| `ReminderController` | `app/di/ReminderControllerImpl` | 定时护眼提醒的开关、间隔、精确闹钟调度 |

## 依赖关系

- **依赖**：`core:model`
- **被依赖**：`app`、`feature:home`、`feature:services`

## 注意事项

- 本模块**不应用 Hilt 约定插件**（纯逻辑 + 接口），需要注入的类型由消费方/装配方处理
- `BlinkDetector` 是有状态对象：每个监测会话持有独立实例，采样区间结束必须 `reset()`
- 新增"接口在 domain、实现在 app"的能力时，绑定写在 `app/di/AppModule`
