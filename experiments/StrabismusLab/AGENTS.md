# StrabismusLab — 独立斜视光码自测科研实验程序

本目录是面向 **MICCAI / CHI 科研实验** 的独立双端（Android / iOS）斜视自测程序工作区，与主 App（`app/`、`feature/home`、`feature/services`、`feature/mine`、`iosApp/`）的商业/甲方交付界面完全隔离。

---

## 一、科研目标与演进路线

1. **近期主攻目标（MICCAI）**：**基于手机屏幕主动光码视频的动态斜视定量筛查**。
   - 通过普通消费级手机前置摄像头 + 屏幕主动时域编码白点，在家庭光照、背窗反光、戴框镜及东亚深色虹膜下实现物理可证明的角膜映光分离与亚像素定位；
   - 覆盖完整四阶段临床流程：**看近光码映光（33 cm） → 看远越屏光码映光（6 m） → 动态交替遮盖再注视 → 平滑追踪（BSI）**；
   - 通过全面、可审计的测试后数据可视化与原始数据导出（JSON / JSONL / CSV / 诊断视频），支撑台架三棱镜诱导真值实验与医院 PACT 盲法队列验证。
2. **当前已落地的算法基线（v0.8-r080）**：
   - **虹膜搜索半径放宽至 `0.80f`**（80% 虹膜半径，覆盖 64% 面积并与六点眼睑多边形求交，避免中重度斜视患者的大偏心映光被旧版 `0.60f` 裁切）；
   - **光码识别完整对齐 `CornealCodeLab v0.8`**（位于双端共享 KMP 模块 `shared/screening/.../strabismuslab/CornealCodeV8.kt`）：
     - 64 码元平衡 6 位 m-序列，支持 `平衡随机明灭 / 曼彻斯特双相 / 稀疏短脉冲 / 常亮对照 / 全黑阴性对照`；
     - 训练段按亮/暗样本数分别归一的平衡差分种子聚类，抑制恒定窗面/镜片强反光；
     - 前向 + 后向双向关联融合，分歧帧降级为 `PREDICTED` 防止暗帧跳点；
     - 独立图像轨迹前半段自适应延迟拟合 + 后半段留出相关 + 全部 63 个错位码零分布显著性检验（判据：`trainR >= 0.35 && heldOutR >= 0.30 && heldOutR > maxWrongR + 0.15`）；
     - 曝光-延迟容差时间线审计（`EmissionTimeline` / `EmissionAudit`）与邻帧时序支持增强（`TemporalGlintSupport`）；
     - 近轴凸面镜光学斑径预算对比（`OpticalBudgetEstimate`）。
3. **后续计划优化方向（按优先级排队）**：
   - 3D 球面角膜镜几何换算（引入相机内参、屏幕白点-镜头物理离轴偏置与头姿 3D 旋转补偿，替代线性 `175.5` 换算）；
   - 去遮盖毫秒级首帧拐点回溯 + 遮盖瞬间对侧眼移动特征（同时区分显斜 Tropia、隐斜 Phoria、间歇性外斜视 IXT）；
   - 面向斜视与单眼遮盖场景的**左右眼解耦 2D Gaze 注视合规监督**（接入 `WebEyeTrack` 或平滑追踪相关解耦分支，绝不误杀斜视眼）；
   - 基于临床 PACT 队列的校准回归与保形预测拒判（Conformal Selective Screening）。

---

## 二、设计语言与交互约定（强约束）

- **审美定位**：精密临床光学实验室控制台（Clinical Optical Lab），**禁止引入主 App 的商业棕色风格、游戏化装饰、冗余二级说明与非科研入口**。
- **界面原则**：`Avoid unnecessary secondary labels and descriptions. Let the layout do the explaining.`
- **统一语义色彩令牌（双端严格对齐）**：
  - 画布背景：`#090C10`（非刺激页），正式光学采集舞台强制纯黑 `#000000`（保证 OLED 0% 灭灯与最高对比度）；
  - 表面与层级：`#12171F`（卡片基面）、`#1A212C`（浮层/图表容器）、`#242E3D`（1dp 仪器刻线边框）；
  - 文字：`#F1F5F9`（主文，对比度 > 14:1）、`#94A3B8`（次文，对比度 > 6.5:1）、`#64748B`（刻度/禁用）；
  - 语义编码色：冷光青蓝 `#38BDF8`（主 CTA / 左眼数据）、琥珀金 `#F59E0B`（右眼数据）、精密翠绿 `#22C55E`（光码通过 / 距离到位）、警示琥珀 `#FBBF24`（临界 / 不确定）、警戒珊瑚红 `#F43F5E`（高疑似 / 越界 / 拒判）。
- **视觉与可用性红线**：
  - 所有实时读数、坐标、相关性与棱镜度必须使用 **等宽数字（Tabular / Monospace）**；
  - 禁止使用 Emoji 作为结构图标（Android 用自定义矢量/绘制图标，iOS 用 SF Symbols）；
  - 触控热区 ≥ 48dp（Android）/ 44pt（iOS），严格遵守顶/底安全区与状态栏留白；
  - 所有测试后 Debug 可视化图表必须在端侧原生矢量绘制（Compose Canvas / SwiftUI Canvas），清晰可读且支持一键导出完整科研数据。

---

## 三、目录结构与双端边界

```
experiments/StrabismusLab/
  AGENTS.md                  本规范文件（科研目标、视觉约定与开发红线）
  README.md                  使用说明、协议字段与构建指引
  build.gradle.kts           Android 独立 App 模块（:strabismus-lab，applicationId = com.zkyjss.strabismuslab）
  src/main/                  Android Compose 实现（完全独立于主 App 的 feature:*）
    java/com/zkyjss/strabismuslab/
      LabTheme.kt            独立实验室设计令牌
      LabRepository.kt       本机科研记录持久化（JSON / JSONL / CSV）与文件分享导出
      LabViewModel.kt        相机采集、帧率/焦距桥接、诊断录制与状态管理
      LabActivity.kt         单 Activity 入口（控制台 / 黑屏采集舞台 / 4-Tab Debug 工作台 / 历史库）
      LabCharts.kt           原生矢量科研图表（双目虹膜映光靶图、光码对齐波形、63 错码柱图、时序轨迹）
  ios/                       iOS 独立 App 工程（XcodeGen + CocoaPods，bundleId = com.zkyjss.strabismuslab）
    project.yml              XcodeGen 配置，链接共享 KMP Framework EyeScreening
    Podfile                  MediaPipeTasksVision 依赖
    Sources/
      LabTheme.swift         与 Android 1:1 对齐的设计令牌
      StrabismusLabCamera.swift  原生 4K 前摄 + MediaPipe + 0.80f 科研光学桥接
      StrabismusLabStore.swift   状态驱动、60Hz 光码显示时钟、持久化与分享导出
      StrabismusLabApp.swift     控制台 / 黑屏采集舞台 / 4-Tab Debug 工作台 / 历史库
      StrabismusLabCharts.swift  SwiftUI Canvas 原生科研图表
```

### 与主仓库的严格隔离约定

1. **算法唯一事实源**：所有光学检测、光码解调、遮盖识别、指标计算与报告序列化代码均在 `shared/`（`shared/domain` 与 `shared/screening/.../strabismuslab/`），**Android 与 iOS 严格调用同一份 KMP 逻辑，禁止在某端私自改公式**。
2. **不污染主 App**：修改本科研程序时，不得改动 `app/`、`feature/home`、`feature/services`、`feature/mine`、`iosApp/` 的业务流程或视觉样式；对 `shared/` 或 `core:camera` 的任何增强必须保持默认参数向后兼容。
3. **数据导出完整可复算**：每次测试必须完整落盘 `session_summary.json`、`frames.jsonl`、`frames.csv`（以及可选诊断视频），确保任何时候都可以离线用 Python/Kotlin 重跑新算法做论文消融实验。

---

## 四、构建与验证命令

```powershell
# 1. 共享核心与 v0.8 光码算法单测（改 shared 后必跑）
.\gradlew.bat -p shared :model:jvmTest :domain:jvmTest :screening:jvmTest

# 2. Android 独立科研 App 编译与单测
.\gradlew.bat :strabismus-lab:assembleDebug :strabismus-lab:testDebugUnitTest

# 3. 验证未破坏主 App 编译
.\gradlew.bat :app:compileDebugKotlin
```

- iOS 工程通过 `experiments/StrabismusLab/ios/project.yml`（XcodeGen）与 `Podfile`（CocoaPods）在 macOS / GitHub Actions 构建，Windows 本地改动 Swift 后务必校验语法与双端字段一致性。通过云编译得到 ios-unsigned-ipa 在 iPhone 上进行测试。
- 交流、代码注释与科研文档一律使用**中文**。
