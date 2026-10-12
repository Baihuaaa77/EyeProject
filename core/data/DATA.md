# core:data

> 数据层：环境光照 / 屏幕亮度读取、监测数据 JSON 持久化、色温偏好存储，以及数据层 DI 装配。

## 职责

- 传感器与系统设置读取（光照、亮度）
- 监测采样的持久化仓储（接口 + 实现 + DI 绑定同模块，NIA 规范）
- 用户偏好持久化（色温模式、护眼提醒）——「偏好存哪」全项目只有这一层

## 对外 API

| 类型 | 说明 |
|---|---|
| `MonitoringRepository` | 接口：`samples: StateFlow<List<MonitoringSample>>`、`addSample` / `flush` / `clear` |
| `JsonMonitoringRepository` | JSON 文件实现（`@Singleton`，内存 + 落盘同步） |
| `AmbientLightMonitor` | 光线传感器 → `luxFlow(): Flow<Float>`（`callbackFlow`，收集时注册、取消时注销） |
| `ScreenBrightnessReader` | 读 `Settings.System`：`BrightnessReading(percent, raw, automatic)`，失败字段为 null |
| `ColorTemperaturePreferences` | SharedPreferences 存取 `ColorTemperatureMode` |
| `ReminderPreferences` | SharedPreferences 存取护眼提醒：启用标记 / 间隔分钟 / 下次触发时刻；闹钟调度仍在 app 的 `EyeCareReminderScheduler` |
| `di/DataModule` | Hilt 绑定：`JsonMonitoringRepository → MonitoringRepository` |

## 持久化格式

文件：`filesDir/monitoring_samples.json`，每采一条（30 秒）立即写盘，滚动保留最近 2 天：

```json
{
  "version": 1,
  "samples": [
    {"ts": 1760000000000, "lux": 320.5, "brightness": 50,
     "distance": 38.2, "duration": 12.5, "blink": 7}
  ]
}
```

## 依赖关系

- **依赖**：`core:model`；`eyeproject.android.hilt` 约定插件
- **被依赖**：`app`、`feature:fatigue`

## 注意事项

- `JsonMonitoringRepository` 的读写全部 `@Synchronized` 且在调用线程执行（写盘是同步 IO），不要在主线程调用 `addSample` / `flush`
- 落盘失败不致命（内存中仍有数据），`load()` 解析失败返回空列表
- 采样间隔（30s）与保留天数（2 天）的常量在 `JsonMonitoringRepository.Companion`；仓库只管真实数据，不做任何数据捏造
