L2CS-Net 注视估计模型
====================

请把 l2cs_net.tflite 放到本目录（feature/testmodel/src/main/assets/），
TestModelViewModel 会以资产名 "l2cs_net.tflite" 加载。

模型要求：
- 输入：[1, 224, 224, 3] float32（RGB 人脸裁剪图，归一化 [0,1]）
- 输出：yaw / pitch 两个 90-bin 分类头（或单头 [1,180]）
  - yaw  ∈ [-180°, 180°]，每 bin 4°
  - pitch ∈ [-90°, 90°]，每 bin 2°

转换方法见 docs/L2CS-Net从C++计算图到应用.md 第 2 节（ai-edge-torch 把 PyTorch 转 TFLite）。
