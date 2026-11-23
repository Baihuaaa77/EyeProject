MediaPipe Face Landmarker 模型文件
================================

请将 face_landmarker.task 下载到本目录（feature/camera/src/main/assets/），
代码中 FaceLandmarkerAnalyzer 以资产名 "face_landmarker.task" 加载。

官方下载地址（约 3.7 MB）：
https://storage.googleapis.com/mediapipe-models/face_landmarker/face_landmarker/float16/1/face_landmarker.task

说明：
- 该模型输出 478 个密集人脸关键点（含虹膜 468/473）、52 个 blendshape 与 4x4 变换矩阵。
- 使用 float16 量化版，端侧 GPU/CPU 推理，无需运行时 Google 服务。
- 若需要 int8 或 float32 版本，参见官方模型页：
  https://developers.google.com/edge/mediapipe/solutions/vision/face_landmarker#models

MediaPipe Hand Landmarker 模型文件（安卓视标手势作答）
=====================================================
hand_landmarker.task，float16 第 1 版，应用打包时随附，只在本机分析前摄画面。
官方来源：https://storage.googleapis.com/mediapipe-models/hand_landmarker/hand_landmarker/float16/1/hand_landmarker.task
SHA-256：fbc2a30080c3c557093b5ddfc334698132eb341044ccee322ccf8bcf3607cde1
