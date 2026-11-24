# 屏幕注视追踪

独立实验应用，包名 `com.zkyjss.gazelab`。iPhone 版对比 **MGazeNet、AFF-Net、iTracker、L2CS-Net、WebEyeTrack**。Android 版仍使用原来的独立实验实现。

## iPhone 真机实验

1. 竖持手机，允许前置相机。五种形状及颜色分别对应五个模型，星形代表 WebEyeTrack。点模型名可以隐藏或显示该模型的落点。
2. 点「九点标定」，依次盯住白点。每个点先等待 0.65 秒，再记录 1.8 秒。参与的模型都处理同一批相机帧，并分别从同一批目标拟合二维仿射映射。没有有效样本的模型显示「未标定」。
3. 标定完成后看屏幕任意位置，观察几个落点的相对偏移和抖动。「原始点／插值」只控制显示。越界落点不显示，但验证误差仍使用原始数值。
4. 点「五点验证」，盯住和标定位置不同的五个点。实心白点是注视目标，已标定模型的估计点以各自颜色和空心形状实时显示。结束后每个模型一行显示逐帧落点误差中位数与 P90，单位为画布物理像素。
5. 从右上角「实验记录」导出 JSON，也可从 iPhone 的「文件」或电脑 iTunes 的文件共享读取。完成和主动中断的标定、验证均保存。验证文件包含标定 ID 和标定系数，可和原标定文件一起复核。

一次标定对应当前设备与用户。L2CS-Net 的原始输出是 yaw/pitch，此版用点位标定把角度映射到屏幕坐标，没有测量相机与屏幕的外参。设定采样上限约 6 帧/秒，实测处理速度以日志为准。图片只在内存里处理。

日志记录机型、系统和构建号、画布/相机分辨率、模型来源与 SHA-256、目标坐标、时间、相机焦距估计、头姿角、人脸框、双眼间距、逐模型原始输出、失败原因及耗时。验证还记录误差分位数、有效帧/目标数、各已标定模型共同有效帧的误差中位数、注视抖动及推理耗时。不同机型的物理像素不能直接横比，可用 JSON 里的画布尺寸换算为对角线百分比。

## 构建与模型来源

GitHub Actions 的「屏幕注视追踪 iOS IPA」编译 iOS 16+、arm64 的未签名程序。研究权重从发布者处取得，在本机转换验证并注入；公共构建工件只包含程序和 MediaPipe 人脸模型。

| 方法 | 此次使用的权重 |
|---|---|
| MGazeNet | [GazeFollower](https://github.com/GanchengZhu/GazeFollower/tree/1e722b8e460086ee847bdecc19225bf48daff7da) 的公开 7M 版 `base.mnn` |
| AFF-Net | [MobilePoG](https://github.com/ZhaoYujie2002/MobilePoG/tree/66854a8f9b87adce25b3210d05d0e47ea947b2e2) 的 `affnet_dmpg.pth` |
| iTracker | 同一 MobilePoG 仓库的 `itracker_gc.pth` |
| L2CS-Net | 项目 Android 实验资产 `feature/testmodel/src/main/assets/l2cs_net.tflite` |
| WebEyeTrack | [上游 75fbd2f 的浏览器权重](https://github.com/RedForestAI/WebEyeTrack/tree/75fbd2f5f784f2eb3a39675a8dcbf1b01c697f1c/docs/public/web) 转 MNN |

在已有 Python、MNN 3.4.1、PyTorch、ONNX、TensorFlow 和 NumPy 的环境使用 `ios/tools/prepare_models.py prepare`。它校验源模型和交付模型的输出一致性。再用 `ios/tools/prepare_models.py package` 把通过校验的 MNN 模型和带哈希的清单注入下载的未签名 IPA。最终包用现有 Sideloadly 签名并安装。模型权重用于本机研究对照，随产品发布前须另行核实使用许可。

### WebEyeTrack 原生对照

BlazeGaze 使用作者浏览器版的权重。iPhone 从同一帧提取透视校正的 512×128 RGB 眼区、头姿向量和基于虹膜尺度及径向投影的三维人脸原点。每帧记录后两项和脸宽估计。对照采用 GazeLab 的统一九点仿射标定和显示插值，便于比较基础预测。作者的浏览器端个性化与 Kalman 滤波可以在 [官方演示](https://redforestai.github.io/WebEyeTrack/) 单独体验。 [上游代码](https://github.com/RedForestAI/WebEyeTrack) · [论文](https://arxiv.org/html/2508.19544v1)

转换时用 `--webe-template` 指定上游 `blazegaze_mpiifacegaze.keras` 结构模板，`--webe-topology` 指定浏览器 `model.json`，`--webe-weights` 指定 `group1-shard1of1.bin`。脚本校验张量名称、形状、分片长度及 MNN 数值一致性。

**UniGaze** 最小公开 B16 权重约 346 MB，输入需要相机内参、头姿和人脸归一化。iPhone 部署需要先验证 Core ML 转换、量化后的精度、内存和延迟。 [官方代码](https://github.com/ut-vision/UniGaze) · [权重](https://huggingface.co/UniGaze/UniGaze-models/tree/main)

## Android 原实验

```powershell
.\gradlew.bat -p shared :domain:jvmTest
.\gradlew.bat :gaze-lab:assembleDebug
```

APK 位于 `experiments/GazeLab/build/outputs/apk/debug/gaze-lab-debug.apk`。Android 版继续使用眼角局部坐标、虹膜位置与逐眼二维映射，按相对姿势选择标定。
