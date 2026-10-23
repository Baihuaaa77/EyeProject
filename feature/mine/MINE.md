# feature:mine

「我的」页：标题行（右上角常驻系统设置齿轮，`EyePageHeader`）、家庭成员、测试记录、云端同步、权限与通知、关于（品牌 + 版本 · 免责一句）。与苹果端同一顺序与文案。

界面原则（2026-10-21）：每张卡只留标题和必要内容，不放解释性小字；次要说明收进可展开项。操作有轻触觉（`core:ui` 的 `EyeHaptics`：点按 / 开关 / 成功 / 失败，苹果端 `EyeHaptics.swift` 同一分级）。

- `SubjectsSection`：「家庭成员」卡，每人一行（头像字、称呼、关系 · 年龄，未填资料时加「资料待完善」），点开进入 `SubjectEditScreen`；末行「+ 添加家庭成员」。原先每项测试开头的问卷都在这里填写一次，测试时只选人。
- `SubjectEditScreen` / `SubjectEditViewModel`：路由 `SubjectEditRoute(id)`（id 为空即新建）；称呼、关系 + 共享 `SubjectProfiles.sections` 分组题目（`core:ui` 的 `EyeChoiceList`），追问随答案出现；填了就必须有效，隐藏的追问不保存；可删除家庭成员（「本人」不可删）。测试开始页缺资料时也由 app 导航到这里，保存后返回测试。
  - 新增选填的验光日期、左右眼裸眼/矫正小数视力、球镜/柱镜（D）及轴位，以及近期眼药水名称/频次/日期、隐形眼镜类型/周期/时长、家族眼病史、过敏史和补充说明。保留原字段键、成员编号和测试必填项。输入类型、长度、数值与日期校验由共享 `Question` / `HealthProfileFields` / `SubjectProfiles.validationError` 定义，支持负数度数和中文文本。

- `LocalRecordsSheet`：「测试记录」底板（入口卡副标题显示「共 N 条」），数据来自 `ScreeningRecordRepository.localRecords`（由共享 `LocalRecordCatalog` 解析，苹果端同一份）；每行测试名、「时间 · 成员」、结论徽标。点开显示当时建议与要点，按组展开关键指标、判断依据、数据可靠性、测试时资料和设备信息。要点下方嵌 AI 解读卡片（`MineViewModel.explanation(fileName)`，已生成的解读本机可看）。底部并排「导出报告」（UTF-8 TXT）与「导出原始数据」（JSON 原样）。新记录展示保存时的报告快照和规则版本，旧记录只读已保存数值，缺失显示未记录，均不重新评估历史风险。

- `MineScreen` 获取应用版本、收集 `MineViewModel.cloudState`，导出用系统「创建文件」选择保存位置。
- `MineViewModel` 合并 `AccountRepository.accountState` 与 `ScreeningRecordRepository.syncStatus` 为 `CloudSyncUiState`（Loading / NotConfigured / SignedOut / SignedIn），操作结果以一句中文提示显示，并经 `outcome`（递增序号 + 成败）触发成功 / 失败振动。
- `CloudSyncSection`：标题「云端同步」+ 状态徽标（未配置 / 未登录 / 已开启 / 仅本机）。未配置时一句「未连接服务器，数据只保存在本机」+ 服务器地址输入；未登录为邮箱、密码、「同意隐私政策」开关（注册需要）、登录 / 注册；已登录为四项同意开关（云端保存、监护人、研究用途、AI 解读）、上传统计、立即同步 / 导出数据 / 退出登录 / 注销账号（二次确认）。数据处理要点 `DATA_HANDLING`（两端同一文案）与服务器地址（仅未登录时可改）收在「数据如何处理 · 服务器设置」里。服务器地址运行期保存（见 `core/network/NETWORK.md`），换地址会退出登录。
- `MineContent` 是无副作用的页面内容。
- navigation/MineNavigation 提供 MineRoute、navigateToMine 与 mineScreen。

依赖 core:designsystem、core:ui、core:data；由 app 导航接入。云端设计见 `docs/后端与智能评估设计-2026-10-15.md`。

本地 Compose 测试：`./gradlew.bat :feature:mine:testDebugUnitTest`；图形检查输出在 `feature/mine/build/reports/ui/`，覆盖历史报告导出入口、负数验光数据和中文病史显示。
