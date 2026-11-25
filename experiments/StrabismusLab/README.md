# StrabismusLab — 独立斜视光码自测科研程序（Android / iOS）

面向眼科科研探索与临床数据采集的独立双端斜视自测应用（Android `com.zkyjss.strabismuslab` / iOS `com.zkyjss.strabismuslab`），与主 App 的业务外壳完全隔离。

## 核心能力

1. **科研光学基线（v0.8-r080）**
   - 虹膜亮点搜索半径放宽至 **`0.80f`**（覆盖 64% 虹膜面积并与眼睑多边形求交），保留中重度斜视的大偏心角膜映光；
   - 完整集成 `CornealCodeLab v0.8` 屏幕主动光码算法（64 码元平衡 m-序列 / 曼彻斯特双相 / 稀疏短脉冲 / 常亮对照 / 全黑对照、平衡亮暗差分种子聚类、双向关联融合、延迟自适应拟合、63 错位码零分布检验与亮灭发射审计）。
2. **极简科研实验控制台**
   - 实时前置相机光学取景仪：叠加虹膜外圈、`0.80R` 虚线搜索边界、候选亮点十字准星及实时 HUD（距离、FPS、头姿、左右眼虹膜像素与可见性对比度）；
   - 受试者匿名编号、戴镜状态、光照标签与临床 PACT 备注录入；
   - 编码模式、搜索半径、码元时长、白点物理尺寸与四阶段流程自由开关（支持单阶段快速 Debug）。
3. **测试后 4-Tab 全景数据可视化与 Debug 工作台**
   - **总览与眼位**：看近/看远 HDE & VDE（含 95% CI）、去遮盖中位再注视与噪声底、追踪 BSI，以及**双目虹膜归一化角膜映光靶图**；
   - **光码验证（v0.8）**：左/右眼 `trainR / heldOutR / maxWrongR / lagMs`、**采样信号 vs 对齐光码时域波形图**、**63 错位码零分布柱图**、**亮灭发射审计表**与**近轴光学斑径预算**；
   - **时序波形（Debug）**：HDE/VDE 随时间曲线、距离与头姿曲线、可见性对比度与遮盖时间轴、拒判原因分布条形图；
   - **逐帧检视与导出**：按阶段/状态筛选逐帧明细，一键导出 `session_summary.json`、`frames.jsonl`、`frames.csv` 及诊断视频。

## 快速构建

```powershell
# 共享算法单元测试
.\gradlew.bat -p shared :model:jvmTest :domain:jvmTest :screening:jvmTest

# 构建 Android 独立科研 APK
.\gradlew.bat :strabismus-lab:assembleDebug

# 安装到真机
adb install -r experiments\StrabismusLab\build\outputs\apk\debug\strabismus-lab-debug.apk
```

iOS 端在 macOS 上进入 `experiments/StrabismusLab/ios/`：

```bash
xcodegen generate
pod install
open StrabismusLab.xcworkspace
```

详细开发红线、设计令牌与算法演进规划见 [AGENTS.md](AGENTS.md)。
