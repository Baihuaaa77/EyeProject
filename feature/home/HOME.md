# feature:home

首页展示品牌、日期、休息提醒与模型测试入口。提醒开关、间隔和权限申请沿用原有流程。

- HomeScreen / HomeContent：页面入口与无副作用呈现。
- HomeViewModel / HomeUiState：提醒状态与一次性通知、精确闹钟权限事件。
- navigation/HomeNavigation：HomeRoute、navigateToHome、homeScreen。

依赖 core:common、core:domain、core:designsystem、core:ui；不持有相机、不采集后台数据。导航回调由 app 装配。
