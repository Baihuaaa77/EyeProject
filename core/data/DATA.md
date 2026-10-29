# core:data

数据层：休息提醒偏好、云端账号与同意、自测记录的离线优先保存与同步、主动护眼账本与系统用屏统计、AI 综合建议。

本机记录导出支持 `RecordExportFormat.JSON`（默认，原始文件不改写）和 `TEXT`（共享 `LocalRecordEntry.reportText` 生成的 UTF-8 可读报告，时间按用户当前时区格式化并附 UTC 偏移）。均由系统文件选择器提供目标，不需要云端登录，不改动原记录与同步状态。

## 结构

| 类型 | 说明 |
|---|---|
| `ReminderPreferences` | SharedPreferences 存取提醒启用标记、间隔分钟与下次触发时刻；闹钟调度由 app 的 EyeCareReminderScheduler 实现 |
| `account/AccountRepository` | 接口 + `DefaultAccountRepository`：`accountState: Flow<AccountState>`；注册、登录、退出、分项同意、导出云端数据到用户选择的文件、注销云端账号 |
| `account/SessionStore` | 会话（令牌 + 同意缓存）整体以 Android Keystore 中不可导出的 AES-GCM 密钥加密后存 SharedPreferences；已排除出自动备份 |
| `account/AuthorizedCaller` | 带令牌调用：快过期先刷新、401 刷新后重试一次；刷新被拒清会话，离线则返回不可达以便稍后重试 |
| `record/ScreeningRecordRepository` | 接口 + `OfflineFirstScreeningRecordRepository`：`saveRecord` 先写本机文件（文件名与位置不变），再登记发件箱 `screening_outbox.json`；`syncStatus: Flow`；`syncNow` 使用共享 `RecordSyncEngine` 推送，首次同步时把升级前的历史记录补登为待上传；发件箱记录归属账号，换账号（含注销后重新注册）时全部换新编号重传；`requestSync` 供应用启动时补传积压记录；`localRecords: Flow` 为本机记录摘要（共享 `LocalRecordCatalog` 解析，保存后自动刷新），`exportRecord` 把一条记录写到用户选择的位置 |
| `explanation/ExplanationRepository` | 接口 + `DefaultExplanationRepository`：`explanation(fileName): Flow<AiExplanationState>`（不支持 / 需账号 / 需同意 / 可生成 / 生成中 / 完成 / 失败），`generate(fileName, agreeFirst)` 先记同意再请求；输入由共享 `Explanations.fromRecord` 抽取，解读按输入哈希缓存在 `ai_explanations.json`（离线、退出登录后仍可看） |
| `eyecare/EyeCareRepository` | 接口 + `OfflineFirstEyeCareRepository`：主动护眼账本 `ledger: Flow<LedgerData>`（共用 `EyeCareLedger`，本机 `eyecare.json`：实时检测会话、本应用使用秒数、每日感受打卡，保留 60 天）、`systemUsage: Flow<SystemUsage>`（使用情况访问状态 + 近 7 天每日用屏）、`environment()`（光线传感器 lux 与屏幕亮度，`callbackFlow`）；`refreshUsage` / `addLiveSession` / `checkIn` / `addAppSeconds` |
| `eyecare/ScreenUsage` | `SystemScreenUsageReader`：AppOps 检查「使用情况访问」，API 28+ 读亮屏 / 熄屏 / 解锁事件；`ScreenUsageAggregator` 把事件切成亮屏区间（间隔不足 60 秒合并），按天统计屏幕时间、解锁次数、最长连续、夜间（22–6 时）分钟与白天 ≥5 分钟的休息次数。统计只在本机进行 |
| `chat/ChatRepository` | 接口 + `DefaultChatRepository`：`state: Flow<ChatState>`（需要账号 / 需要同意 / 可以聊 + 本机记录 + 发送中 + 错误）、`send(text, context, agreeFirst)`、`clear()`；需「AI 对话」同意，开场的综合建议取自 `AdviceRepository.advice()` 的当前值，记录只存 `ai_chat.json`（最多 200 条） |
| `advice/AdviceRepository` | 接口 + `DefaultAdviceRepository`：`advice(requestJson): Flow<AiAdviceState>`、`generate(requestJson, agreeFirst)`；需「AI 综合建议」同意，按输入缓存在 `ai_advice.json`（最多 30 条） |
| `subject/SubjectRepository` | 接口 + `OfflineFirstSubjectRepository`：本人与家庭成员及其资料（原测试问卷），本机 `subjects.json` 为事实源，首次使用自动建立空白「本人」；`subjects: Flow`、`remoteIds: Flow`（本机 → 云端成员编号）；满足上传条件时 `sync()` 双向同步：「本人」绑定账号默认成员、成员用本机 UUID 在云端新建、资料版本旧于云端时以云端为准、孩子资料未获监护人同意时保持待传；换账号时全部重新待传 |
| `sync/SyncWorker` | WorkManager 一次性任务（需联网、指数退避、同名任务串接）；通过 Hilt EntryPoint 取仓库 |
| `di/DataModule` | Hilt 绑定（仓库与 `SyncScheduler`） |

## 上传条件

已配置服务器（`core:network`）+ 已登录 + 已授予 `HEALTH_DATA_SYNC` 同意，三者缺一不上传；保存与测试流程不受影响。
儿童记录（问卷年龄 < 14）还需监护人同意，未同意的记录保持待上传，不阻塞其他记录。记录不可变，因此没有双向合并。
记录按其中的 `subject.id` 归属到成员（SyncWorker 先同步成员再推送记录）；家庭成员尚未同步到云端时记录留待下次，不会错归到「本人」。

## 依赖

`api(core:network)`（含 `shared:sync` 契约）、`api(shared:screening)`（记录摘要解析）、WorkManager；被 app、feature:services、feature:mine 使用。
NIA 差异：同步 Worker 暂放本模块 `sync/`，出现第二个同步仓库时再拆 `sync:work`。

## 测试

`./gradlew.bat :core:data:testDebugUnitTest`（Robolectric；假网络数据源与明文 SecretBox，不用 mock 库）。
