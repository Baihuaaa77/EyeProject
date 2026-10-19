# feature:mine

「我的」页：家庭成员与资料、本机测试记录、云端同步与数据、系统权限入口、版本、品牌和自测免责声明（与苹果端同一顺序）。

- `SubjectsSection`：本人与家庭成员列表（称呼 · 关系 · 年龄 · 已填几项），点开进入 `SubjectEditScreen`；「添加家庭成员」。原先每项测试开头的问卷都在这里填写一次，测试时只选人。
- `SubjectEditScreen` / `SubjectEditViewModel`：路由 `SubjectEditRoute(id)`（id 为空即新建）；称呼、关系 + 共享 `SubjectProfiles.sections` 分组题目（`core:ui` 的 `EyeChoiceList`），追问随答案出现；填了就必须有效，隐藏的追问不保存；可删除家庭成员（「本人」不可删）。测试开始页缺资料时也由 app 导航到这里，保存后返回测试。

- `LocalRecordsSheet`：本机测试记录底板，数据来自 `ScreeningRecordRepository.localRecords`（摘要由共享 `LocalRecordCatalog` 解析，苹果端同一份）；每行测试名、时间、结论徽标，点开看要点并可「导出完整记录」（系统创建文件，`exportRecord` 写入）。

- `MineScreen` 获取应用版本、收集 `MineViewModel.cloudState`，导出用系统「创建文件」选择保存位置。
- `MineViewModel` 合并 `AccountRepository.accountState` 与 `ScreeningRecordRepository.syncStatus` 为 `CloudSyncUiState`（Loading / NotConfigured / SignedOut / SignedIn），操作结果以一句中文提示显示。
- `CloudSyncSection`：未配置服务器时只说明「记录只在本机」；未登录显示邮箱 + 密码表单（注册需勾选隐私政策）；已登录显示三项分项同意（云端存储、监护人、可选研究用途）、上传统计、立即同步、导出云端数据、退出登录、注销云端账号（二次确认）。
- `MineContent` 是无副作用的页面内容。
- navigation/MineNavigation 提供 MineRoute、navigateToMine 与 mineScreen。

依赖 core:designsystem、core:ui、core:data；由 app 导航接入。云端设计见 `docs/后端与智能评估设计-2026-10-15.md`。
