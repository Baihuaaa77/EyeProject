# GazeLab — 屏幕注视追踪对照实验室

独立实验应用，包名 `com.zkyjss.gazelab`，显示名称 `GazeLab`。iPhone 版对比 **MGazeNet、AFF-Net、iTracker、L2CS-Net、WebEyeTrack** 五种注视估计方法；Android 版保留原独立几何基线实验。

## iPhone 真机实验（v0.3.0）

1. 竖持手机，允许前置相机。顶部支持切换实时相机预览小窗（叠加人脸框、双眼框、闭眼提示、实时 FPS 与头姿欧拉角 Yaw/Pitch/Roll），每个模型均有独立推理开关与单帧耗时显示。
2. 点「九点标定」，依次盯住同心准星（琥珀外环准备 0.65 秒 → 青绿进度弧采样 1.8 秒，期间监测头姿稳定性，按采集时间戳归属尾帧防止低帧率丢帧）：
   - **MGazeNet**：输入 RGB，使用官方 258 维输出特征拟合原生 Multivariate Ridge Calibration（样本不足时自动回退 2D 仿射）；
   - **AFF-Net**：输入 BGR，使用紧致眼框并将 `rects` 像素坐标按 MobilePoG 竖屏训练尺度（360×640）缩放，逐目标中位数拟合低正则（0.002）二维仿射；
   - **iTracker**：输入 BGR，使用紧致眼框与中心采样的 `25×25` 人脸网格（有效值 `1/255`，对齐 MobilePoG `uint8 -> ToTensor()` 分布），逐目标中位数拟合低正则（0.002）二维仿射；
   - **L2CS-Net**：输入 RGB + ImageNet 归一化（带额头上下文扩展人脸框），逐目标中位数拟合低正则（0.002）二维仿射；
   - **WebEyeTrack**：每次标定重置人脸宽度与深度缓存，优先采用系统相机真实焦距，单点至少 3 帧取中位数代表帧，执行少样本仿射（0.002）与一步 Adam 预测头个性化。
3. 点「五点验证」，验证点覆盖四角边缘与偏心中心，每次切点重置 WebEyeTrack Kalman 滤波器以消除低帧率跨点拖尾。
4. **公平评估口径**：全部五个模型的主对比误差（中位数、P90、均值、成对中位数、抖动）统一使用**未滤波、未裁剪**坐标计算（WebEyeTrack 使用 `webEyeTrackUnfiltered`）；WebEyeTrack 额外记录经 Kalman 滤波与屏幕限幅后的对照指标。
5. 点右上角记录按钮可直接在 App 内查看每轮标定/验证的详细对比报告，并支持一键分享导出或删除 JSON 记录。

## 构建与模型注入

GitHub Actions 的「屏幕注视追踪 iOS IPA」编译 iOS 16+、arm64（MNN 启用 ARMv8.2 FP16 + 4 线程 CPU + Host Tensor 复用）的未签名空壳 IPA。

```powershell
$env:PYTHONPATH = "E:\MyProjects\AierEye\EyeProject\build\gaze-lab-python"
python experiments/GazeLab/ios/tools/prepare_models.py package `
  --shell <未签名空壳IPA> `
  --models build/gaze-lab-webeyetrack-native `
  --output build/gaze-lab-ios-build9-delivery/GazeLab-v0.3.0-build9-five-models-unsigned.ipa
```

## Android 原实验

```powershell
.\gradlew.bat -p shared :domain:jvmTest
.\gradlew.bat :gaze-lab:assembleDebug
```
