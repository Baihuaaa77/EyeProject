# core:camera

> 前置摄像头采集 + MediaPipe FaceLandmarker 逐帧关键点分析的基础能力模块；**全项目唯一的 CameraX 绑定点**，预览可选（后台监测不需要，界面需要）。

## 职责

- 绑定前置相机（CameraX：`ImageAnalysis` 必选、`Preview` 可选），逐帧结果通过 `start()` 的 `onFrame` 回调交给调用方（**分析线程回调**）
- 基于 MediaPipe FaceLandmarker（478 点）提取人脸框、双眼 EAR 6 点、虹膜 5 点、面部变换矩阵及 FPS/延迟；眼区图像另产生瞳孔中心和角膜反光候选点及质量拒判原因
- 从 Camera2 特性读取前置相机焦距与传感器物理尺寸，按旋转后图像长边估算分析图焦距；斜视测试还要求实物距离校正

## 对外 API

| 类型 | 说明 |
|---|---|
| `CameraController` | 相机控制器（`@Singleton`）：`start(lifecycleOwner, previewView = null, onFrame, onError)` / `stop()` / `isRunning`。启动是异步的，内部按「代号」作废过期的启动请求，调用方不必关心 `ProcessCameraProvider` 的异步性 |
| `CameraIntrinsicsProvider` | 读取前置相机焦距：`focalLengthPx(analysisWidth): Float`（失败返回 0） |
| `FaceLandmarkerAnalyzer` | `ImageAnalysis.Analyzer` 实现，由 `CameraController` 内部创建，业务层不直接接触 |

## 数据流

```mermaid
flowchart TD
    A["CameraX ImageAnalysis<br>（RGBA_8888 · KEEP_ONLY_LATEST）"] --> B["FaceLandmarkerAnalyzer.analyze()<br>每 2 帧取 1 帧（DETECTION_FRAME_INTERVAL = 2）"]
    B --> C["旋转矫正 → Bitmap"]
    C --> D["MediaPipe detectAsync<br>（LIVE_STREAM · 时间戳 ns→ms）"]
    D --> E["ResultListener 回调<br>组装 FaceLandmarkFrame（含 FPS / 检测延迟）"]
    E --> F["CameraController 调用 start() 传入的 onFrame（分析线程）"]
    F --> G["业务层：检测页画框 / 干眼页门禁测距 / 监测引擎 30s 采样"]
```

## 共享 FaceLandmarker 设计

`FaceLandmarker` 实例初始化开销大（加载 478 点模型），且 `LIVE_STREAM` 模式下回调经全局回调列表分发，因此所有 Analyzer 实例共享一个 FaceLandmarker 单例；Analyzer 的 `close()` 只注销自己的回调，不销毁引擎（引擎生命周期 = 进程）。

```classDiagram
class FaceLandmarkerAnalyzer {
    -FaceLandmarker faceLandmarker
    -(FaceLandmarkerResult)->Unit resultHandler
    -PendingFrame pendingFrame
    +analyze(ImageProxy)
    +handleResult(FaceLandmarkerResult)
    +close()
}

class SharedFaceLandmarker {
    -FaceLandmarker instance$
    +get(Context) FaceLandmarker$
}

class handlers {
    <<companion>>
    -CopyOnWriteArrayList~回调~ handlers$
    +add()
    +remove()
    +forEach()
}

class FaceLandmarker {
    <<单例>>
    +detectAsync()
    +createFromOptions()
}

FaceLandmarkerAnalyzer --> SharedFaceLandmarker : 获取
FaceLandmarkerAnalyzer --> handlers : 注册/移除回调
SharedFaceLandmarker --> FaceLandmarker : 持有
handlers --> FaceLandmarkerAnalyzer : 回调执行（通过 resultHandler）
```

```sequenceDiagram
participant A as Analyzer A
participant H as handlers 全局列表
participant FL as FaceLandmarker<br>(单例)
participant MediaPipe as MediaPipe<br>引擎

Note over A,MediaPipe: 1️⃣ 初始化阶段

A->>H: init { handlers.add(resultHandler) }
Note over H: 列表现在包含: [A的回调]
A->>FL: get(context)
FL-->>A: 返回共享实例

Note over A,MediaPipe: 2️⃣ 正常运行阶段（每帧循环）

loop 每一帧
    A->>A: analyze(imageProxy)
    A->>MediaPipe: detectAsync(mpImage, timestamp)
    MediaPipe-->>FL: 检测完成，触发 ResultListener
    FL->>H: handlers.forEach { it(result) }
    H->>A: 调用 A 的 resultHandler
    A->>A: handleResult(result)
end

Note over A,MediaPipe: 3️⃣ 清理阶段

A->>H: close() { handlers.remove(resultHandler) }
Note over H: 列表现在为空
```

## 依赖关系

- **依赖**：`core:model`（FaceLandmarkFrame 等）；CameraX、MediaPipe tasks-vision
- **被依赖**：`feature:fatigue`（FatigueMonitor）、`feature:services`（检测页）、`app`（Hilt 可见性）
- 模型资产：`src/main/assets/face_landmarker.task` 随本模块打包

## 注意事项

- **`onFrame` 在分析线程执行**：回调里只能写 `@Volatile` 字段或转投协程，不要直接更新 UI 状态（见 CLAUDE.md 的相机管线线程模式）
- **`CameraController` 是 `@Singleton`，一个进程只有一路相机**：检测页可通过帧观察者订阅后台监测正在使用的相机，退出时只移除自己的订阅；主动重新绑定的其他页面仍需处理相机所有权
- 时间戳：CameraX 给纳秒，`detectAsync` 期望毫秒，代码里做 `/1_000_000` 换算
- `ImageAnalysis` 使用 `STRATEGY_KEEP_ONLY_LATEST`：旧帧未处理完直接丢弃，分析不阻塞相机
- `toArgbBitmap` 会处理 `rowStride ≠ pixelStride × width` 的行填充；旋转在送入 MediaPipe 前完成
- `CameraIntrinsicsProvider` 假设分析图长边覆盖传感器横向全视野，主点近似图像中心；CameraX 裁切和机型差异必须经过实物校正，不能仅靠 Camera2 元数据换算斜视棱镜度
- tasks-vision 预编译 `.so` 仅 `arm64-v8a`，模块需保持 `abiFilters` 一致
