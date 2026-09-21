# OCEC 全替换管线（DEIMv2 检测 + OCEC 分类）实现路线调研

- 日期：2026-09-10 | 目标：testmodel 新增独立眨眼检测页，完全不依赖 MediaPipe（对照现有 blink 页）
- 验证方式：下载真实模型文件 + onnxruntime 实测（非纸面调研）；项目位置 `C:\Users\djj\xd-workspace\projects\OCEC`（PINTO0309/OCEC，MIT）

---

## 1. 管线结构（已在 PC 上端到端跑通）

```
相机帧(BGR) ──原尺寸直喂──▶ DEIMv2-Wholebody34 检测器
                             输入 [N,3,H,W] 全动态（BGR 0-255 float32，无 resize/无 mean-std）
                             输出 [N,1750,6] = [label, x1,y1,x2,y2, score]（xyxy 归一化 0-1）
                             │ 过滤 label==17(eye) && score>th
                             ▼
                        eye 框（按 x 排序分左右眼）
                             │ 裁剪（demo 用 0 margin）→ BGR转RGB → resize 24×40 → /255
                             ▼
                          OCEC 分类器（输入 [N,3,24,40]，输出 prob_open [N]）
                             │ prob>=0.5 → Open
                             ▼
                     双眼状态 → 3 帧连续判定（复用现有 BlinkDetector 逻辑）→ 眨眼计数
```

**实测验证**（PC CPU，onnxruntime，样例图 sample1.png 闭眼人像）：
- DEIMv2-S 检出 13 个部位类（body 0.90 / face 0.79 / **eye 0.76** 等），眼框 (165,77)-(181,91)
- OCEC `prob_open=0.000 → Closed` —— 与图像实际（闭眼）一致 ✓
- 分辨率无关性实测：640×640 / 480×640 / 240×320 / 252×320 全部可跑，输出形状恒定

## 2. 模型下载

### OCEC 分类器（GitHub releases 直链，全部已验证可达）
| 变体 | 大小 | F1 | URL |
|---|---|---|---|
| P | 115,097 B | 0.9924 | https://github.com/PINTO0309/OCEC/releases/download/onnx/ocec_p.onnx |
| N | 176,580 B | 0.9933 | …/ocec_n.onnx |
| S | 494,914 B | 0.9943 | …/ocec_s.onnx |
| C | 875,566 B | 0.9947 | …/ocec_c.onnx |
| M | 1,693,332 B | 0.9949 | …/ocec_m.onnx |
| L | 6,372,200 B | 0.9954 | …/ocec_l.onnx |

**推荐 P 或 N**：F1 已 0.99+，体积/延迟最优；实测 P 单帧 **0.2ms**（PC CPU）。

### DEIMv2-Wholebody34 检测器（关键缺口在这里）
| 变体 | 骨干 | 输入 | 来源 | 状态 |
|---|---|---|---|---|
| S-1750query | DINOv3-S ViT | 640×640（实际全动态） | OCEC release 直链 **40.5MB** | ✅ 已下载验证 |
| X-680query | DINOv3-X ViT | 同上 | OCEC release 直链 **203MB** | 太大，不用 |
| 全变体集合（含 Atto/Pico/N 的 HGNetV2 版） | HGNetV2 CNN | 320/640 | zoo 472 `resources.tar.gz`（Wasabi S3） | ⚠️ tar 首文件 206MB 无法部分列目录，需整包下载（估 >1GB） |
| Atto/Femto/Pico/N 权重 | HGNetV2 | — | PINTO0309/DEIMv2 release（`deimv2_hgnetv2_atto_coco.pth` 2.2MB 等） | ⚠️ 仅有 coco 版 pth，**wholebody34 的 HGNetV2 权重是否发布待确认** |

**实测警告**：DEIMv2-S 在 PC CPU 上 **~500-620ms/帧**，且与输入分辨率几乎无关（240×320 与 640×640 同级）——瓶颈是 1750 query 的 transformer 解码器，不是骨干网络。**S/X 变体在手机上不可实时**；可实时的是 HGNetV2 轻量变体（Atto 全流程 2.2MB 级）。

## 3. 运行时（网络）选型

| 方案 | 优点 | 缺点 | 结论 |
|---|---|---|---|
| **LiteRT（首选）** | testmodel 已有 `litert 2.1.1` 依赖零新增；OCEC 仓库 demo 原生支持 `ai_edge_litert` 运行时（作者设计即考虑）；GPU/NPU delegate 可用 | 需把 ONNX 转成 .tflite；DEIMv2（ViT+DETR 解码器+动态尺寸）转换有风险，需固定输入尺寸重导 | **OCEC 必走 LiteRT；DEIMv2 尝试，失败再降级** |
| ONNX Runtime Mobile | 现成 ONNX 直接用，零转换 | 新增依赖（每 ABI +15~25MB）；DEIMv2 动态尺寸是 ORT 强项 | 备选 |

## 4. 格式转化

1. **OCEC → LiteRT**（低风险）：`onnx2tf -i ocec_p.onnx -on prob_open`——纯 Conv/Pool/FC 图，预期无痛；转后用 TFLite benchmark 实测。也可直接问 zoo 要现成 tflite（OCEC 仓库 demo 支持 tflite runtime，可能存在现成转换版）。
2. **DEIMv2 → LiteRT**（中风险）：
   - 必须先固定尺寸重导出（TFLite 不接受全动态 H×W）：用 PINTO0309/DEIMv2 的导出脚本以 `320×320`（Atto/Pico）固定形状导出 ONNX，再 onnx2tf
   - 风险点：DINOv3 注意力 + DETR 解码器的算子兼容性；1750 query 输出张量较大但形状静态
   - 备选：INT8 量化（QC/PTQ）进一步压缩——分类器/检测器分离后可分别量化
3. zoo 472 tarball 里可能已有现成 tflite（未确认）——**动手前先整包下载确认**，能省整个转换环节

## 5. 集成设计（testmodel 新页 `standalone/`，零 core 依赖，模块约定不变）

```
standalone/
├── StandaloneBlinkAnalyzer.kt   # ImageAnalysis.Analyzer：RGBA→旋转→缩放→DEIMv2→眼框→裁剪→OCEC→prob
├── StandaloneBlinkViewModel.kt  # 双 LiteRT Interpreter 生命周期 + 逐帧状态机
├── StandaloneBlinkUiState.kt    # detMs/clsMs/fps/双眼 prob/眨眼数
├── StandaloneBlinkScreen.kt     # 眼框+Open/Closed 覆盖层 + 数据面板 + 60s 录制（复用 blink 页模式）
└── navigation/StandaloneBlinkNavigation.kt   # 三件套 + TestModelScreen 加入口
assets: deimv2_atto_320.tflite（目标）、ocec_p.tflite
```

关键实现点：
- 预处理：检测器 BGR 0-255 CHW（**无归一化**，与常见模型不同，别照抄模板）；OCEC RGB /255
- 后处理：[1,1750,6] 按 score 过滤 + label==17 + xyxy×图宽高，左右眼按 x 排序
- 检测帧率占空比：检测器不必逐帧——demo 自带 IoU tracker（max_age=30），人眼健康场景基本静止，可 1-2Hz 检测 + 分类器 30fps；首版先逐帧实测再优化
- 输出给 UI：无 478 点覆盖层，改为眼框 + Open/Closed 状态色块
- 60s 录制格式沿用 `t_s,prob_left,prob_right,prob_min,latency_det_ms,latency_cls_ms,fps`

## 6. 分阶段执行建议

- **阶段 1（管线验证，先做）**：用现成 S-ONNX + OCEC-P，先在 PC 用视频/摄像头验证眼框召回与分类精度的真实水平（换多段真实手机自拍视频）；同时下载 zoo 472 tarball 确认轻量变体与 tflite 是否现成
- **阶段 2（Android 页面）**：OCEC-P 转 LiteRT + S-ONNX 暂以 ORT/LiteRT 低频跑（1-2Hz+tracker）——功能先通，速度不达标明确记录
- **阶段 3（实时化）**：拿 Atto/Pico wholebody34 导出 ONNX（zoo tarball 或 DEIMv2 repo 导出脚本 + pth）→ onnx2tf → LiteRT GPU delegate → 用 60s 检测页与 blink 页同台对比 fps/误判率

## 7. 风险清单

| 风险 | 影响 | 缓解 |
|---|---|---|
| S/X 检测器手机不可实时（PC CPU 已 500ms+） | 页面卡 | 阶段 3 的 HGNetV2 变体；检测降频 + tracker |
| wholebody34 的 HGNetV2 权重可能未发布 | 阶段 3 卡壳 | 先整包下载 tarball 确认；不行则用 coco 版微调或等发布 |
| DEIMv2 onnx2tf 转换失败 | 停留在 ORT | 固定尺寸重导出；退而 ORT Mobile |
| 域差：OCEC F1 0.99 ≠ 我们相机分布 | 误判 | 用 60s 录制存眼片做自建验证集（AmbientEye 教训） |
| eye 类 AP 本身偏低（0.13-0.42 各变体） | 眼框召回 | 检测阈值调低 + tracker 补帧；对比 MediaPipe 眼框作对照 |

---

## 8. 实施与验证记录（2026-09-11 更新）

### 8.1 路线修订：DEIMv2 → BlazeFace
DEIMv2-S PC CPU 实测 ~550ms/帧且与分辨率无关（1750 query 解码器瓶颈），手机不可行。
最终栈改为 **BlazeFace short-range（229KB，128×128，Pixel 9 Pro GPU 3.61ms）+ OCEC-P（115KB tflite）**，
双模型合计 <400KB，全 MIT/Apache，LiteRT 单运行时。YuNet（232KB，MIT，脸框+5点）为备选对照。

### 8.2 BlazeFace 解码规格（已实证对齐官方解码，残差 <0.003 归一化）
- 输入 128×128 letterbox（保比例 BORDER_ZERO）、RGB、[-1,1]、NHWC
- 896 anchors：4 层 strides [8,16,16,16]，同 stride 合并每格多 anchor；中心 (x+0.5)/grid；
  **fixed_anchor_size=true → 尺寸恒 1.0**（来源：对齐官方 FaceDetector 输出反解确认，源码语义与此有出入）
- 解码：`center = anchor_c + raw[0..1]/128`，`w/h = raw[2..3]/128`（线性无 exp，reverse_output_order x 优先），
  `kpt_c = anchor_c + raw[4+2k]/128`（6 点含双眼），`score = sigmoid`
- NMS：加权，IoU 0.3（单脸场景取 argmax 即可）

### 8.3 真机落地
testmodel `standalone/` 页已实现（BlazeOCECAnalyzer + VM/UiState/Screen/Overlay + 导航三件套），
模型在 assets（blaze_face_short_range.tflite + ocec_p.tflite，onnx2tf 转换与 ONNX 零数值差异）。
调试设施：异常分段上报（面板+logcat）、录制期眼片采样（`files/crops/*.png`，文件名带设备端概率）。

### 8.4 真机实测发现与数据集验证
- 真机首测：用户睁眼 30s 内 13.9% 帧误判闭眼（prob_avg mean 仅 0.744），3 帧滤波后仍 **8 次/30s 假眨眼**
- 排除法：50 张设备眼片经 PC 端同一 ONNX 重分类，与设备端相关系数 0.926 → **预处理无 bug**
- 数据集验证（MichalMlodawski/closed-open-eyes，ODC-By，即 OCEC 训练源数据，parquet 分片 3.3MB×1266，
  自带左右眼 GT 框）：GT 眼框下 **accuracy 99.38% / F1 0.9938**（open mean 0.996 / closed mean 0.019），
  与论文 P 变体 F1 0.9924 吻合 → **PC 管线与预处理口径正确**
- **最终定论：模型对「戴眼镜 + 手机前置相机高清眼片」域外失效，微调是充分解**
  （模型容量已证足够：分布内近乎完美）。路径：设备采样（功能已就位）→ 打标 → OCEC 仓库训练管线微调
  P 变体 → onnx2tf → 替换 assets 复测。训练集侧可混入本数据集样本防遗忘。

### 8.5 几何最终形态与分割模型假设检验（2026-09-11）
- 眼框几何三版迭代：宽松(0.32+25%m) 误判 13.9% → 过紧(0.18) 9.4% → **人体测量学(0.24/0.11+dy0.005) 0%**。
  依据：睑裂宽≈0.21 面宽（五眼法）、睑裂高≈1/3 裂宽；60s 真机复测零误判零假阳性，fps 25.1、det 4.2ms+cls 2.3ms。
- 45° 仰头困难样本揭示第二层问题：BlazeFace 眼点姿态漂移（左眼点滑到鼻梁），YuNet 同样漂。
- 分割模型假设检验（用户假设「有合适的分割模型分出眼眶」）：SegFace-MobileNetV3（AAAI 2025，MIT，
  CelebAMask 87.91 F1，权重 HF 现成）在仰头闭眼图上 **eye 类像素为零**（nose/lips/ears 正常）——
  SOTA 解析器零样本同样失效于极端姿态，与关键点漂移同病根（正脸对齐训练分布）。
- **定论**：极端姿态鲁棒性只能靠自采数据微调（OCEC 微调须加平移/缩放/遮挡增强）；分割模型
  （SegFace/SAM2）定位为微调数据的**离线自动标注器**，非运行时组件。BiSeNet 系（2018）被 SegFace 取代；
  YOLO11-seg 微调路线因 AGPL 排除商用。

---
验证产物：`/tmp/deimv2_s.onnx`（40.5MB）、`/tmp/ocec_p.onnx`（115KB）已下载可复用；验证脚本见会话记录。
