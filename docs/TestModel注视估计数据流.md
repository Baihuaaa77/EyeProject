# TestModel 注视估计数据流（图片 → 人脸检测 → 注视估计 → 输出）

> 本文记录 `feature:testmodel` 页面的完整数据流，含每一步的数据类型、shape、归一化参数与解码公式。
> 路线：**Camera → 人脸检测（MediaPipe FaceDetector）→ 裁剪人脸 → LiteRT L2CS-Net → yaw/pitch**。

---

## 1. 完整数据流图

```
┌────────────────────────────────────────────────────────────────────────┐
│ ① CameraX ImageAnalysis（OUTPUT_IMAGE_FORMAT_RGBA_8888）               │
│    ImageProxy                                                          │
│    shape: W×H（取决于 AnalysisResolution，如 480×640）                  │
│    类型: android.media.ImageProxy                                      │
└──────────────────────────────────┬─────────────────────────────────────┘
                                   │ toBitmap()  plane[0].buffer → copyPixelsFromBuffer
                                   ▼
┌────────────────────────────────────────────────────────────────────────┐
│ ② Bitmap（未旋转）                                                     │
│    shape: W×H，ARGB_8888（每像素 4 字节 = 1 int）                       │
│    类型: android.graphics.Bitmap                                        │
└──────────────────────────────────┬─────────────────────────────────────┘
                                   │ uprightBitmap()  postRotate(rotationDegrees)
                                   ▼
┌────────────────────────────────────────────────────────────────────────┐
│ ③ Bitmap（旋转正立）                                                   │
│    shape: H×W（90°/270° 时宽高互换），ARGB_8888                         │
│    类型: android.graphics.Bitmap                                        │
│    ⚠️ 关键：与 FaceDetector 检测框同一坐标系（见 §3）                    │
└───────────────┬─────────────────────────────────────────────────────────┘
                │ 同一帧同时喂给下面两个分支
    ┌───────────┴───────────────────────────┐
    ▼                                       │
┌───────────────────────────────────────────┐│
│ FaceDetectorAnalyzer.analyze()            ││
│  内部再次: toArgbBitmap → rotateBitmap    ││
│   → BitmapImageBuilder → MPImage          ││
│  类型: com.google.mediapipe.framework.    ││
│        image.MPImage                      ││
└───────────────┬───────────────────────────┘│
                │ faceDetector.detect(mpImage) │
                ▼                             │
┌───────────────────────────────────────────┐│
│ FaceDetectorResult（blaze_face_short_range）││
│  .detections(): List<Detection>            ││
│  Detection.boundingBox(): Rect             ││
│    left/top/right/bottom（像素，正立图坐标）││
│  → FaceEyeDetection.faceBox:               ││
│      BoundingBox(left,top,right,bottom)    ││
└───────────────┬───────────────────────────┘│
                │ 用 faceBox（正立坐标）裁剪   │
                │ latestBitmap（正立图③）      │
                ▼
┌───────────────────────────────────────────┐
│ ④ Bitmap（人脸裁剪） cropBitmap()          │
│    shape: fw×fh（人脸框宽×高，不定，如 300×400）│
└───────────────┬───────────────────────────┘
                │ infer_use_android_api(croppedFace)
                │   bitmap.scale(448, 448)  ← 缩放到模型输入
                ▼
┌────────────────────────────────────────────────────────────────────────┐
│ ⑤ Bitmap 448×448，ARGB_8888                                          │
│    shape: 448×448×4                                                    │
└──────────────────────────────────┬─────────────────────────────────────┘
                                   │ 逐像素: RGB → [0,1] → ImageNet 归一化 → NCHW
                                   ▼
┌────────────────────────────────────────────────────────────────────────┐
│ ⑥ 输入张量 FloatArray                                                  │
│    shape: 1×3×448×448 = 602112（float32）                             │
│    layout: NCHW（R 平面 / G 平面 / B 平面）                             │
│    inputData[c][y][x] = (pixel[c]/255 − mean[c]) / std[c]              │
│    mean=[0.485, 0.456, 0.406]，std=[0.229, 0.224, 0.225]               │
└──────────────────────────────────┬─────────────────────────────────────┘
                                   │ inputBuffers[0].writeFloat(inputData)
                                   │ compiledModel.run(input, output)
                                   ▼
┌────────────────────────────────────────────────────────────────────────┐
│ ⑦ L2CS-Net（l2cs_net.tflite）推理                                     │
│    输入:  [1, 3, 448, 448]  float32 NCHW                              │
│    输出:  2 个 logits 张量                                             │
│      logits_yaw   → [1, 90]（90 bin）                                  │
│      logits_pitch → [1, 90]（90 bin）                                  │
└──────────────────────────────────┬─────────────────────────────────────┘
                                   │ outputBuffers[0].readFloat() → FloatArray(90)
                                   │ outputBuffers[1].readFloat() → FloatArray(90)
                                   ▼
┌────────────────────────────────────────────────────────────────────────┐
│ ⑧ softmax + 期望解码（见 §2 正确公式）                                 │
│    yawDeg   : Float ∈ [-180°, 180°]                                   │
│    pitchDeg : Float ∈ [-90°,   90°]                                   │
└──────────────────────────────────┬─────────────────────────────────────┘
                                   │
                                   ▼
┌────────────────────────────────────────────────────────────────────────┐
│ ⑨ InferenceResult(outputs = [FloatArray(2) = [yawDeg, pitchDeg]])     │
│    → uiState.yawDeg / pitchDeg                                         │
└────────────────────────────────────────────────────────────────────────┘
```

---

## 2. 解码公式（logits → 角度，⚠️ 正确版本）

L2CS-Net 的两个输出头都是 **90-bin logits**，需先 softmax 再求加权期望，且 **yaw 与 pitch 的 bin 步长不同**：

```
softmax(logits)[i] = exp(logits[i] − max) / Σⱼ exp(logits[j] − max)

yaw   = Σᵢ softmax(logits_yaw)[i]   × (i×4  − 180)     // 范围 [-180, 180]，每 bin 4°
pitch = Σᵢ softmax(logits_pitch)[i] × (i×2  −  90)     // 范围 [-90,   90]，每 bin 2°
```

Kotlin 参考实现：

```kotlin
private fun binsToDegrees(bins: FloatArray, start: Float, step: Float): Float {
    val max = bins.maxOrNull() ?: 0f
    var sum = 0.0
    var acc = 0.0
    for (i in bins.indices) {
        val e = exp(bins[i] - max)
        sum += e
        acc += e * (start + i * step)
    }
    return if (sum == 0.0) 0f else (acc / sum).toFloat()
}

// 调用
val yawDeg   = binsToDegrees(logits_yaw,   -180f, 4f)
val pitchDeg = binsToDegrees(logits_pitch,  -90f, 2f)
```

> ⚠️ 常见错误：yaw 和 pitch 都用 `×4 − 180` 会导致 **pitch 放大 2 倍且整体偏移**；
> 直接对 logits 加权（不 softmax）也会不准，除非 tflite 转换时已内置 softmax。

---

## 3. 坐标系对齐（踩过的坑）

`FaceDetectorAnalyzer` 是在「**旋转到正立**」的图上检测，`boundingBox()` 返回的是**正立图坐标**。
因此 `latestBitmap` 也必须用 `uprightBitmap()` 旋转到正立，否则 `cropBitmap` 会用正立坐标
裁剪**未旋转**的图，导致人脸区域错位、视线角度不明显。

前置摄像头 `rotationDegrees` 通常为 90/270，此时正立图的宽高互换。

---

## 4. 归一化参数（ImageNet）

| 通道 | mean | std |
|---|---|---|
| R | 0.485 | 0.229 |
| G | 0.456 | 0.224 |
| B | 0.406 | 0.225 |

先 `pixel/255` 归一化到 [0,1]，再 `(x − mean)/std`；layout 为 NCHW。

> 与 C++ 端 `PreprocessInputForGazeCpu` 的 0-255 尺度 mean/std（123.675/116.28/103.53）等价。

---

## 5. 两条推理路线对比

| | `infer_use_cpp_lib` | `infer_use_android_api` |
|---|---|---|
| 实现 | C++ JNI（`libgaze_native.so`） | Maven `com.google.ai.edge.litert:litert:2.1.1` Java API |
| 预处理/归一化 | C++ 端 `PreprocessInputForGazeCpu`（resize + 归一化 + CHW） | Kotlin 端手动 resize + 归一化 + NCHW |
| 输入 | 整张图 | 裁剪后的人脸 |
| 适用 | 无需先检测人脸 | 需先 FaceDetector 检测人脸框再裁剪 |
