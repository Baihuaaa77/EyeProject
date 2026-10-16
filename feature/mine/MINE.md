# feature:mine

「我的」页：云端同步与数据、系统权限入口、版本、品牌和自测免责声明。

- `MineScreen` 获取应用版本、收集 `MineViewModel.cloudState`，导出用系统「创建文件」选择保存位置。
- `MineViewModel` 合并 `AccountRepository.accountState` 与 `ScreeningRecordRepository.syncStatus` 为 `CloudSyncUiState`（Loading / NotConfigured / SignedOut / SignedIn），操作结果以一句中文提示显示。
- `CloudSyncSection`：未配置服务器时只说明「记录只在本机」；未登录显示邮箱 + 密码表单（注册需勾选隐私政策）；已登录显示三项分项同意（云端存储、监护人、可选研究用途）、上传统计、立即同步、导出云端数据、退出登录、注销云端账号（二次确认）。
- `MineContent` 是无副作用的页面内容。
- navigation/MineNavigation 提供 MineRoute、navigateToMine 与 mineScreen。

依赖 core:designsystem、core:ui、core:data；由 app 导航接入。云端设计见 `docs/后端与智能评估设计-2026-10-15.md`。
