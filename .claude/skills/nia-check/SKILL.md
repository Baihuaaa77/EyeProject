---
name: nia-check
description: 审查代码是否符合 NIA 项目规范时使用。对当前未提交改动（git 工作区）或用户指定的 diff 跑依赖铁律、DI 装配、UI 状态、导航、持久化、JNI 一致性检查。无参数时默认审查整个工作区。
argument-hint: [可选：PR/分支/路径，默认当前工作区]
---

# NIA 合规审查

审查基准：`docs/NIA项目开发规范.md`（读一遍 §0~§7 再开始）。默认对象：`git status` + `git diff` 的未提交改动；用户给了 PR/分支/路径则审那个范围。

## 审查流程

1. **圈定范围**：`git status --short` / `git diff` 列出改动文件（排除 `build/`、`.cxx/`、`.idea/`）。

2. **依赖铁律**（读改动涉及的 `build.gradle.kts`）：
   - feature → feature 依赖？core → feature/app 依赖？→ 高危，必须重构
   - `api` vs `implementation` 用得对不对（对外暴露的基础设施才用 api）
   - app 之外是否有模块依赖 `:app`

3. **DI 装配位置**：
   - 新增 `@Binds`/`@Provides` 是否在**提供实现的模块**内（core/data → `di/DataModule`、core/notifications → `di/NotifierModule`）
   - `app/di/AppModule` 只允许出现「接口在 domain/feature、实现只能在 app」的绑定（端口模式）

4. **UI 层形态**：
   - ViewModel 是否 `@HiltViewModel` + `StateFlow<XUiState>` 单向数据流；事件是否 lambda 上抛
   - UiState 是否覆盖所有状态；Screen 是否只渲染 + 调 lambda（无业务逻辑）
   - 组件/Screen 里是否写死 `Color(0x…)` / 字号（应读 designsystem 令牌）

5. **数据层**：
   - Repository 新增**读**方法是否返回 Flow（出现快照 getter 即违规）
   - **写**方法是否 suspend；层内模型是否泄漏到公开 API
   - JSON 持久化键名（`JsonMonitoringRepository`）与 `core/model` 字段是否同步

6. **导航**：新页面是否三件套齐全（XRoute + navigateToX + NavGraphBuilder 扩展）；NavHost 接线在 app；有无 feature 直接 import 其他 feature 类型

7. **JNI 布局一致性**（涉及 `feature/fatigue/src/main/cpp/` 或 JNI 桥时）：
   `model.cpp` 输出布局、`EyeHealthNative` kdoc、`FatigueRisk.fromOutput` 解析——**三处必须一致**
   （当前权威：double[27] / String[8]；历史上 EyeHealthNative 的 kdoc 曾写 29/9 属过时）

8. **残留检查**：占位死文件、空 manifest、重复版本定义（build-logic 与 `libs.versions.toml` 双份）。

## 输出格式

- 每条发现：`文件:行` + 违规描述 + 规范出处（§节号）+ 修复建议，按严重度排序
- 高危（依赖铁律、DI 位置）单独标出
- 全部通过时明确说「通过，无违规」，并给一句改动范围是否符合分层的确认
- 只审查不修改；用户要求修复时再动手
