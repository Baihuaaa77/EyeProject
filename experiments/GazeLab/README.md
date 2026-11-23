# 屏幕注视追踪

独立实验应用，包名 `com.zkyjss.gazelab`。iPhone 版对比 **MGazeNet、AFF-Net、iTracker、L2CS-Net**。Android 版仍使用原来的独立实验实现。

## iPhone 真机实验

1. 竖持手机，允许前置相机。四种形状及颜色分别对应四个模型，点模型名可以隐藏或显示该模型的落点。
2. 点「九点标定」，依次盯住白点。每个点先等待 0.65 秒，再记录 1.8 秒。参与的模型都处理同一批相机帧，并分别从同一批目标拟合二维仿射映射。没有有效样本的模型显示「未标定」。
3. 标定完成后看屏幕任意位置，观察几个落点的相对偏移和抖动。「原始点／插值」只控制显示。越界落点不显示，但验证误差仍使用原始数值。
4. 点「五点验证」，盯住和标定位置不同的五个点。验证时只显示目标。结束后每个模型一行显示逐帧落点误差中位数与 P90，单位为画布物理像素。
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

在已有 Python、MNN 3.4.1、PyTorch、ONNX、TensorFlow 和 NumPy 的环境使用 `ios/tools/prepare_models.py prepare`。它校验源模型和交付模型的输出一致性。再用 `ios/tools/prepare_models.py package` 把通过校验的 MNN 模型和带哈希的清单注入下载的未签名 IPA。最终包用现有 Sideloadly 签名并安装。模型权重用于本机研究对照，随产品发布前须另行核实使用许可。

### 另两个部署候选

- **WebEyeTrack** 可先用 iPhone Safari 跑 [官方演示](https://redforestai.github.io/WebEyeTrack/) 做交互对照。作者公开了 TF.js BlazeGaze 权重和端侧少样本校准，报告 iPhone 14 的模型推理约 2.4 ms。要加入同帧原生对照，需要一起复现眼部归一化、头姿/脸部空间尺度与校准，或者使用 WebKit 运行原管线并回传预测数值。 [代码](https://github.com/RedForestAI/WebEyeTrack) · [论文](https://arxiv.org/html/2508.19544v1)
- **UniGaze** 公开了 3D 权重，最小 B16 约 346 MB，仍需要相机内参、头姿估计与人脸归一化。适合先作为 PC 端研究对照。若做 iPhone 版，可先试 B16 的 Core ML 转换和量化，再测内存、发热与完整链路延迟。 [官方代码](https://github.com/ut-vision/UniGaze) · [权重](https://huggingface.co/UniGaze/UniGaze-models/tree/main)

## Android 原实验

```powershell
.\gradlew.bat -p shared :domain:jvmTest
.\gradlew.bat :gaze-lab:assembleDebug
```

APK 位于 `experiments/GazeLab/build/outputs/apk/debug/gaze-lab-debug.apk`。Android 版继续使用眼角局部坐标、虹膜位置与逐眼二维映射，按相对姿势选择标定。
