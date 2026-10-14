# core:data

数据层集中保存休息提醒的用户偏好。

`ReminderPreferences` 通过 SharedPreferences 存取启用标记、间隔分钟与下次触发时刻。
闹钟调度由 app 的 EyeCareReminderScheduler 实现；首页通过 ReminderController 获取和更新提醒状态。

依赖 Android Core；被 app 使用。自测 JSON 的记录与分析格式由 shared/screening 统一定义，平台负责写入本机文件。
