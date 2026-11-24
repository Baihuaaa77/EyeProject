# 屏幕注视追踪

独立实验应用，包名 `com.zkyjss.gazelab`。iPhone 版对比 **MGazeNet、AFF-Net、iTracker、L2CS-Net、WebEyeTrack**。Android 版仍使用原来的独立实验实现。

## iPhone 真机实验

1. 竖持手机，允许前置相机。每个模型有独立的推理开关。关闭某个模型会清除它的落点并跳过后续推理。五种形状及颜色分别对应五个模型，星形代表 WebEyeTrack。
2. 点「九点标定」，依次盯住白点。每个点先等待 0.65 秒，再记录 1.8 秒。启用的模型都处理同一批相机帧。WebEyeTrack 用每点一帧做少样本个性化，其他模型用每点的预测中位数拟合二维仿射映射。首次标定样本不足的模型显示「未标定」。
3. 每个启用的模型算完就更新自己的落点。WebEyeTrack 经过算法自身的 Kalman 滤波和屏幕限幅，其他模型的越界落点不显示，验证误差仍使用未裁剪的坐标。
4. 点「五点验证」，盯住和标定位置不同的五个点。实心白点是注视目标，已标定模型的估计点以各自颜色和空心形状实时显示。结束后每个模型一行显示逐帧落点误差中位数与 P90，单位为画布物理像素。
5. 从右上角「实验记录」导出 JSON，也可从 iPhone 的「文件」或电脑 iTunes 的文件共享读取。完成和主动中断的标定、验证均保存。验证文件包含各模型的标定 ID 和标定系数，可和原标定文件一起复核。

一次标定对应当前设备与用户，模型再次启用时可继续使用本次运行中已有的标定。L2CS-Net 的原始输出是 yaw/pitch，此版用点位标定把角度映射到屏幕坐标，没有测量相机与屏幕的外参。相机按可处理的速度送帧，每帧先做人脸检测，再串行运行启用的模型。处理繁忙时丢弃迟到帧，记录中的 `actualFrameRateHz` 为实测分析频率。图片只在内存里处理。

日志记录机型、系统和构建号、画布/相机分辨率、模型来源与 SHA-256、目标坐标、时间、相机焦距估计、头姿角、人脸框、双眼间距、逐模型原始输出、失败原因及耗时。验证还记录误差分位数、有效帧/目标数、各已标定模型共同有效帧的误差中位数、注视抖动及推理耗时。不同机型的物理像素不能直接横比，可用 JSON 里的画布尺寸换算为对角线百分比。

## 构建与模型来源

GitHub Actions 的「屏幕注视追踪 iOS IPA」编译 iOS 16+、arm64 的未签名程序。研究权重从发布者处取得，在本机转换验证并注入；公共构建工件只包含程序和 MediaPipe 人脸模型。

| 方法          | 此次使用的权重                                                                                                                             |
| ----------- | ----------------------------------------------------------------------------------------------------------------------------------- |
| MGazeNet    | [GazeFollower](https://github.com/GanchengZhu/GazeFollower/tree/1e722b8e460086ee847bdecc19225bf48daff7da) 的公开 7M 版 `base.mnn`       |
| AFF-Net     | [MobilePoG](https://github.com/ZhaoYujie2002/MobilePoG/tree/66854a8f9b87adce25b3210d05d0e47ea947b2e2) 的 `affnet_dmpg.pth`           |
| iTracker    | 同一 MobilePoG 仓库的 `itracker_gc.pth`                                                                                                  |
| L2CS-Net    | 项目 Android 实验资产 `feature/testmodel/src/main/assets/l2cs_net.tflite`                                                                 |
| WebEyeTrack | [上游 75fbd2f 的浏览器权重](https://github.com/RedForestAI/WebEyeTrack/tree/75fbd2f5f784f2eb3a39675a8dcbf1b01c697f1c/docs/public/web)，冻结编码器转 MNN，原始三层预测头原生运行 |

在已有 Python、MNN 3.4.1、PyTorch、ONNX、TensorFlow 和 NumPy 的环境使用 `ios/tools/prepare_models.py prepare`。它校验源模型和交付模型的输出一致性。再用 `ios/tools/prepare_models.py package` 把通过校验的 MNN 模型和带哈希的清单注入下载的未签名 IPA。最终包用现有 Sideloadly 签名并安装。模型权重用于本机研究对照，随产品发布前须另行核实使用许可。

### WebEyeTrack 原生对照

原生管线使用与官方浏览器版字节一致的 MediaPipe Face Landmarker 模型、透视校正的 512×128 RGB 眼区、头姿向量和基于虹膜尺度与径向投影的三维人脸原点。闭眼过滤沿用 GazeLab 的像素坐标眼纵横比，避免竖屏逐轴归一化额外压低开眼时的纵横比。官方冻结的 BlazeGaze 编码器输出 512 维特征，拼接头姿和三维原点后进入 16 → 16 → 2 的预测头。

九点标定为每个有效目标选一帧，至少四点才拟合仿射。按浏览器 `adapt` 默认值执行一步 Adam，只更新预测头，学习率 `1e-5`，β₁ `0.85`，β₂ `0.9`，ε `1e-8`。实时输出依次经由预测头、个性化仿射、Kalman 和屏幕坐标限幅。每次九点标定从官方头权重开始，原生相机使用 MediaPipe Video 模式，共用现有实验流程。验证时只读取已拟合的参数。 [上游代码](https://github.com/RedForestAI/WebEyeTrack/tree/75fbd2f5f784f2eb3a39675a8dcbf1b01c697f1c) · [论文](https://arxiv.org/html/2508.19544v1)

WebEyeTrack 标定失败时，界面和记录显示有效点数及主要失败原因。验证记录包含预测头权重、仿射参数、样本数、训练损失、未滤波屏幕点和最终显示点。精度用最终显示点计算。标定特征只保留在内存中。

转换时用 `--webe-template` 指定上游 `blazegaze_mpiifacegaze.keras` 结构模板，`--webe-topology` 指定浏览器 `model.json`，`--webe-weights` 指定 `group1-shard1of1.bin`。脚本校验张量名称、形状和分片长度，验证拆分后组合输出与原模型等价，并为 `.mnn` 编码器和 `.head.json` 预测头分别记录 SHA-256。

**UniGaze** 最小公开 B16 权重约 346 MB，输入需要相机内参、头姿和人脸归一化。iPhone 部署需要先验证 Core ML 转换、量化后的精度、内存和延迟。 [官方代码](https://github.com/ut-vision/UniGaze) · [权重](https://huggingface.co/UniGaze/UniGaze-models/tree/main)

## Android 原实验

```powershell
.\gradlew.bat -p shared :domain:jvmTest
.\gradlew.bat :gaze-lab:assembleDebug
```

APK 位于 `experiments/GazeLab/build/outputs/apk/debug/gaze-lab-debug.apk`。Android 版继续使用眼角局部坐标、虹膜位置与逐眼二维映射，按相对姿势选择标定。
