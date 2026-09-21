# Issue tracker：本地 Markdown

本仓库的 issue 与 spec 以 Markdown 文件形式存放在 `.scratch/` 下。

## 约定

- 每个功能一个目录：`.scratch/<feature-slug>/`
- spec 为 `.scratch/<feature-slug>/spec.md`
- 实现 issue 一票一文件：`.scratch/<feature-slug>/issues/<NN>-<slug>.md`，从 `01` 起编号，禁止合并成单个 tickets 文件
- triage 状态记录在 issue 文件顶部附近的 `Status:` 行（角色字符串见 `triage-labels.md`）
- 评论与讨论历史追加到文件底部 `## Comments` 标题下

## 当 skill 说 "publish to the issue tracker"

在 `.scratch/<feature-slug>/` 下新建文件（必要时先建目录）。

## 当 skill 说 "fetch the relevant ticket"

读取被引用路径的文件。用户通常会直接给出路径或 issue 编号。

## Wayfinding 操作

供 `/wayfinder` 使用。**map** 是一个文件，每个 ticket 一个 **child** 文件。

- **Map**：`.scratch/<effort>/map.md`（正文为 Notes / Decisions-so-far / Fog）
- **Child ticket**：`.scratch/<effort>/issues/NN-<slug>.md`，从 `01` 起编号，正文记录问题；`Type:` 行记录类型（`research`/`prototype`/`grilling`/`task`）；`Status:` 行记录 `claimed`/`resolved`
- **Blocking**：顶部附近一行 `Blocked by: NN, NN`；其列出的文件全部 `resolved` 时解除阻塞
- **Frontier**：扫描打开、未阻塞、未被认领的文件；编号最小者优先
- **Claim**：开始任何工作前先设 `Status: claimed` 并保存
- **Resolve**：在 `## Answer` 标题下追加答案，设 `Status: resolved`，并把上下文指针（要点 + 链接）追加到 map.md 的 Decisions-so-far
