# core:data

数据层：休息提醒偏好、云端账号与同意、自测记录的离线优先保存与同步。

## 结构

| 类型 | 说明 |
|---|---|
| `ReminderPreferences` | SharedPreferences 存取提醒启用标记、间隔分钟与下次触发时刻；闹钟调度由 app 的 EyeCareReminderScheduler 实现 |
| `account/AccountRepository` | 接口 + `DefaultAccountRepository`：`accountState: Flow<AccountState>`；注册、登录、退出、分项同意、导出云端数据到用户选择的文件、注销云端账号 |
| `account/SessionStore` | 会话（令牌 + 同意缓存）整体以 Android Keystore 中不可导出的 AES-GCM 密钥加密后存 SharedPreferences；已排除出自动备份 |
| `account/AuthorizedCaller` | 带令牌调用：快过期先刷新、401 刷新后重试一次；刷新被拒清会话，离线则返回不可达以便稍后重试 |
| `record/ScreeningRecordRepository` | 接口 + `OfflineFirstScreeningRecordRepository`：`saveRecord` 先写本机文件（文件名与位置不变），再登记发件箱 `screening_outbox.json`；`syncStatus: Flow`；`syncNow` 使用共享 `RecordSyncEngine` 推送，首次同步时把升级前的历史记录补登为待上传；发件箱记录归属账号，换账号（含注销后重新注册）时全部换新编号重传；`requestSync` 供应用启动时补传积压记录 |
| `sync/SyncWorker` | WorkManager 一次性任务（需联网、指数退避、同名任务串接）；通过 Hilt EntryPoint 取仓库 |
| `di/DataModule` | Hilt 绑定（仓库与 `SyncScheduler`） |

## 上传条件

已配置服务器（`core:network`）+ 已登录 + 已授予 `HEALTH_DATA_SYNC` 同意，三者缺一不上传；保存与测试流程不受影响。
儿童记录（问卷年龄 < 14）还需监护人同意，未同意的记录保持待上传，不阻塞其他记录。记录不可变，因此没有双向合并。

## 依赖

`api(core:network)`（含 `shared:sync` 契约）、WorkManager；被 app、feature:services、feature:mine 使用。
NIA 差异：同步 Worker 暂放本模块 `sync/`，出现第二个同步仓库时再拆 `sync:work`。

## 测试

`./gradlew.bat :core:data:testDebugUnitTest`（Robolectric；假网络数据源与明文 SecretBox，不用 mock 库）。
