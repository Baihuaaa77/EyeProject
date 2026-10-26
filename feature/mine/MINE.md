# feature:mine

「我的」= 入口页 + 二级完整页面（不用底部弹窗）。与苹果端 `EyeMine.swift` / `EyeCloud.swift` / `EyeSubjects.swift` 同一排版与文案。

界面原则（2026-10-23）：入口页不堆开关与按钮；说明文字收进标题旁的「?」（`EyeHelpButton`），不写括号小字。操作有轻触觉（`core:ui` 的 `EyeHaptics`，苹果端 `EyeHaptics.swift` 同一分级）。

- `MineScreen` / `MineContent` / `MineViewModel`（`MineUiState`）：标题行（右上角账户头像 + 系统设置齿轮，`EyePageHeader`）、个人卡（会动的小瞳 + 本人称呼、年龄与档案完善度 + 账号一行）、
  三格概览（测试记录 / 家庭成员 / 已上传），分组入口：健康档案（测试记录、家庭成员与资料、用眼趋势报告）、数据与隐私（云端同步、权限与通知）、通用（系统设置）。
  跳转经 `MineNavigation` 由 app 接线（趋势页与系统设置属于其他 feature，不直接依赖）。
- `AccountScreen` / `AccountViewModel`：右上角人像按钮进入的账户页：账号概况、登录 / 注册（注册前「?」里是隐私要点 `DATA_HANDLING`）、退出登录、注销账号（二次确认）、服务器地址（仅未登录时可改，换地址会退出登录）。
- `CloudSyncScreen` / `CloudSyncViewModel`（`CloudSyncUiState`）：同步状态与立即同步、分项同意（云端保存、监护人、研究用途、AI 解读、**AI 综合建议**，每项旁「?」为 `ConsentHelp` 文案，两端一致）、导出云端数据、数据如何处理。
  AI 综合建议的同意文案逐项列出发送的数据（年龄段、职业、戴镜方式、既往眼病、近 7 天用屏与休息概况、眨眼与距离、环境光与亮度、各测试等级、今日负荷与原因、记录的感受），不发送姓名、账号、具体日期、照片或视频。
- `RecordsScreen` / `RecordsViewModel`：测试记录卡片列表，数据来自 `ScreeningRecordRepository.localRecords`（共享 `LocalRecordCatalog` 解析，苹果端同一份）；每张卡测试名、「时间 · 成员」、结论徽标，
  点开显示当时建议与要点、AI 解读卡片、按组展开的关键指标 / 判断依据 / 数据可靠性 / 测试时资料 / 设备信息，底部「导出报告」（UTF-8 TXT）与「导出原始数据」（JSON 原样）。空状态为思考中的小瞳。
  新记录展示保存时的报告快照和规则版本，旧记录只读已保存数值，均不重新评估历史风险。
- `SubjectsScreen`：家庭成员列表（头像字、称呼、关系 · 年龄，未填资料时加「资料待完善」），点开进入 `SubjectEditScreen`；末行「添加家庭成员」。
- `SubjectEditScreen` / `SubjectEditViewModel`：路由 `SubjectEditRoute(id)`（id 为空即新建）；称呼、关系 + 共享 `SubjectProfiles.sections` 分组题目（`EyeChoiceList`），追问随答案出现；
  可删除家庭成员（「本人」不可删）。测试开始页缺资料时也由 app 导航到这里。选填验光记录、常用眼药水 / 隐形眼镜 / 家族史（只放长期信息；症状、用药等随时变化的问题改为每次测试前现问，见共用 `PreTestCheck`），校验由共享 `HealthProfileFields` / `SubjectProfiles.validationError` 定义。
- `PermissionsScreen`：相机、麦克风、通知、精确闹钟、用屏统计（使用情况访问）逐项显示用途与是否已允许，点一下去系统对应设置页；回到本页自动刷新。
- navigation/MineNavigation：`MineRoute`、`SubjectEditRoute`、`AccountRoute`、`RecordsRoute`、`SubjectsRoute`、`CloudSyncRoute`、`PermissionsRoute`；
  `mineScreen(navigation)`、`subjectEditScreen(onBack)`、`mineSubScreens(onBack, onOpenAccount, onOpenCloudSync, onEditSubject)`。

依赖 core:designsystem、core:ui、core:data；由 app 导航接入。云端设计见 `docs/后端与智能评估设计-2026-10-15.md`。

本地 Compose 测试：`./gradlew.bat :feature:mine:testDebugUnitTest`；截图在 `feature/mine/build/reports/ui/` 并同步到 `docs/界面截图-2026-10-23/安卓/`。
