# models/ — standalone 眨眼实验的 PC 端模型与数据副本

| 文件 | 大小 | 来源（下载地址） | 用途 |
|---|---|---|---|
| `blazeface_sr.tflite` | 229,746 B | https://storage.googleapis.com/mediapipe-models/face_detector/blaze_face_short_range/float16/latest/blaze_face_short_range.tflite | 人脸检测（128×128，解码规格见实验报告 §数据流图） |
| `ocec_p.onnx` | 115,097 B | https://github.com/PINTO0309/OCEC/releases/download/onnx/ocec_p.onnx | 睁闭眼分类（PC 端评估用基准） |
| `ocec_p_float32.tflite` | 115,084 B | onnx2tf 转换（与 onnx 数值零差异） | LiteRT 部署 |
| `ocec_p_float16.tflite` | 65,792 B | 同上 | 精度略换体积 |
| `yunet.onnx` | 232,589 B | https://github.com/opencv/opencv_zoo （models/face_detection_yunet/face_detection_yunet_2023mar.onnx） | 备选：脸框+5 点（MIT），未接入 |
| `cew_shard_{001,002,900,1200}.parquet` | 3.3MB×4 | https://huggingface.co/datasets/MichalMlodawski/closed-open-eyes/resolve/main/data/dataset_XXX.parquet | 数据集评估样本（001/002=closed，900/1200=open） |

未保存的大文件（可按地址重下）：
- `deimv2_dinov3_s_wholebody34_1750query_n_batch_640x640.onnx`（40.5MB）：
  https://github.com/PINTO0309/OCEC/releases/download/onnx/deimv2_dinov3_s_wholebody34_1750query_n_batch_640x640.onnx
- 完整数据集：1266 个分片 ≈ 4.2GB（同名路径遍历）

Android 端正式模型在 `feature/testmodel/src/main/assets/`（blaze_face_short_range.tflite + ocec_p.tflite）。
