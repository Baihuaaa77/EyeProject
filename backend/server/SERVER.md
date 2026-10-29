# backend/server — 账号与测试记录服务

Ktor 3 API 服务：账号、家庭成员（被测者）及其健康资料、分项同意、测试记录同步、历史与对比、导出、注销、审计与保留期清理，以及 AI 解读与 AI 综合建议（知识库检索 + DeepSeek）。
设计依据与路线见 [`docs/后端与智能评估设计-2026-10-15.md`](../../docs/后端与智能评估设计-2026-10-15.md)。

## 运行与测试

```powershell
.\gradlew.bat -p backend/server test   # 27 项接口、安全与 AI 输出校验测试（内存 H2）
.\gradlew.bat -p backend/server run    # 开发模式：http://localhost:8080，数据在 backend/server/data/
```

本机若有 `E:\Android\gradle-cache\gradle.properties` 写死的旧代理端口导致下载失败，命令后加 `-Dhttp.proxyPort=<端口> -Dhttps.proxyPort=<端口>` 覆盖。

iOS 连本机服务：在「我的 → 右上角人像（账户）→ 服务器地址」（未登录时可改；退出登录立即生效，不等服务器）填电脑的局域网地址（如 `http://192.168.1.5:8080`），手机与电脑连同一 Wi-Fi；
首次连接时允许「本地网络」权限。客户端逻辑见 `shared/sync` 的 `client/CloudClient`。

安卓调试包连本机服务：在根 `local.properties` 写 `eyeproject.apiBaseUrl=http://10.0.2.2:8080`（模拟器）或局域网地址（真机），见 `core/network/NETWORK.md`。

## 结构

| 文件 | 职责 |
|---|---|
| `Application.kt` | 入口、插件（JSON、错误体、限速、HTTPS 强制、请求体上限、Bearer 认证、请求日志）、路由 |
| `ServerConfig.kt` | 环境变量配置；生产模式缺密钥拒绝启动 |
| `Database.kt` | HikariCP + Flyway 迁移 + 事务；SQL 全参数化 |
| `Security.kt` | PBKDF2 口令、不透明令牌、AES-256-GCM 原文加密（AAD 绑定记录与账号）、审计 IP 哈希 |
| `AuthService.kt` | 注册、登录、刷新令牌轮换与重用检测、登出 |
| `ProfileService.kt` | 账号、被测者及其健康资料（原测试问卷，AES-256-GCM 加密，AAD 绑定成员与账号）、同意（追加式）、导出、注销 |
| `RecordService.kt` | 幂等上传、同意与监护人检查、列表、详情、删除、历史、对比 |
| `AuditLog.kt` / `RetentionJob.kt` | 审计（不含健康内容）；每日清理过期令牌、审计与（可选）过期记录、30 天前的解读缓存 |
| `ExplanationService.kt` | AI 解读：校验输入 → 「AI 解读」同意 → 缓存 → 每日额度 → 检索知识库 → 调模型 → 校验输出（诊断措辞、与等级矛盾、输入中没有的数字）→ 不合格重试一次；提示词 `ExplanationPrompt` |
| `AdviceService.kt` | AI 综合建议：「AI 综合建议」同意 → 缓存（与解读同表，键前缀 `advice-a1`）→ 共用每日额度 → 检索知识库 → 调模型 → 校验输出（`AdviceOutput`，复用解读的禁用词与编造数字检查）→ 不合格重试一次；提示词 `AdvicePrompt` |
| `AdminService.kt` | 数据看板（`/admin/`，页面在 `resources/admin/`）：`EYE_ADMIN_PASSWORD` 口令登录（内存会话 8 小时）；统计与去标识记录只含最新一次同意 `RESEARCH_USE` 的账号，账号 / 被测者用审计盐 HMAC 的假名；按测试、等级、时间筛选与导出 CSV；不解密原文；登录、查看、导出写审计 |
| `ChatService.kt` | AI 对话（`POST /v1/chat`）：「AI 对话」同意 → 每日条数（`ai_chat_usage`，与解读 / 建议分开）→ 规则判断危险信号（`Chats.redFlag`）→ 检索知识库（`searchChat`）→ 系统提示 + 概况与知识 + 多轮对话调模型 → 校验（禁用词、长度、有危险信号时必须建议就医）→ 不合格重试一次；对话内容不入库、不缓存，审计只记次数与 URGENT / NORMAL；提示词 `ChatPrompt` |
| `KnowledgeBase.kt` | 读取 `resources/knowledge/eye-knowledge.md`，中文二元切词 BM25 检索，按等级固定带上「就医 / 不确定 / 数据无效」条目 |
| `LlmClient.kt` | 大模型端口与 `DeepSeekClient`（OpenAI 兼容 Chat Completions，JSON 输出、关闭思考；不记内容与密钥）。用 OkHttp：域名解析出多个地址时，连不上的地址 4 秒后自动换下一个（有的网络里 api.deepseek.com 的个别地址不通，JDK HttpClient 只连第一个地址会整次超时） |
| `resources/db/migration` | Flyway 脚本；PostgreSQL 与 H2（PostgreSQL 模式）通用写法 |

数据契约（DTO、记录摘要解析、错误代码）来自 `shared/sync`，与客户端是同一份代码。

## 环境变量

| 变量 | 默认（开发） | 生产要求 |
|---|---|---|
| `EYE_ENV` | `development` | `production` |
| `EYE_PORT` | 8080 | |
| `EYE_DB_URL` / `EYE_DB_USER` / `EYE_DB_PASSWORD` | 本地 H2 文件库 | 必填，PostgreSQL |
| `EYE_DATA_KEYS` | 内置开发密钥 | 必填：`k1:<32 字节 Base64>[,k2:...]` |
| `EYE_ACTIVE_KEY_ID` | 第一个 key | 轮换时指向新 key，旧 key 保留用于解密 |
| `EYE_AUDIT_SALT` | 固定开发盐 | 必填 |
| `EYE_TRUST_PROXY` | false | 位于反向代理后设 true |
| `EYE_REQUIRE_HTTPS` | 生产为 true | |
| `EYE_PBKDF2_ITERATIONS` | 600000 | |
| `EYE_AUTH_RATE_PER_MINUTE` | 20 | 每 IP 每分钟认证请求数 |
| `EYE_RECORD_RETENTION_DAYS` | 不过期 | 按研究方案确定 |
| `EYE_AUDIT_RETENTION_DAYS` | 1095 | |
| `DEEPSEEK_API_KEY`（或 `EYE_LLM_API_KEY`） | 读 `llm.properties` 的 `apiKey`；都没有则 AI 解读返回 `ai_unavailable` | 必填才开启 AI 解读 |
| `EYE_LLM_MODEL` / `EYE_LLM_BASE_URL` | `deepseek-v4-flash` / `https://api.deepseek.com` | |
| `EYE_LLM_DAILY_LIMIT` | 20 | 每账号每天调用模型次数（命中缓存不计） |
| `EYE_LLM_CHAT_DAILY_LIMIT` | 40 | 每账号每天和 AI 对话的条数（与上一项分开计） |
| `EYE_LLM_TIMEOUT_MS` / `EYE_LLM_DISABLE_THINKING` | 30000 / true | 换不支持 `thinking` 字段的模型时设 false |
| `EYE_ADMIN_PASSWORD` | 不设置：不开放数据看板（`/admin` 一律 404） | 设置则至少 12 位 |

## AI 解读（`POST /v1/explanations`）

本机试用：复制 `llm.properties.example` 为 `llm.properties`（已 gitignore）填入 DeepSeek API Key，或设环境变量后启动：

```powershell
$env:DEEPSEEK_API_KEY = "sk-..."; .\gradlew.bat -p backend/server run
```

- 输入 `ExplanationRequest`（`shared/sync` 的 `Explanations.fromRecord` 从本机记录抽取：等级、徽标、指标、依据、可靠性说明、年龄段、几项相关资料选项；不含姓名、账号、成员编号、逐帧数据）。
- 只接受干眼、老视、眼位三项（`RecordSummaries.EXPLAINABLE_KINDS`）；色觉自测记录可同步但不做 AI 解读。
- 风险等级只来自设备端规则；模型输出与等级矛盾、出现诊断性措辞或编造数字即判为不合格并重试一次，仍不合格返回 502。
- 需 `AI_EXPLANATION` 同意（403 `consent_required`）；未配置密钥 503 `ai_unavailable`；超额度 429 `ai_quota_exceeded`；每账号每分钟限 6 次。
- 缓存键 = 提示词版本 + 模型 + 知识库版本 + 输入哈希；改提示词时递增 `ExplanationService.PROMPT_VERSION`，改知识库时更新文件头「版本」。日志只记 token 数。
- 费用参考：每次约 1.5k 输入 + 0.3k 输出 token，按 V4 Flash 公开价（约 1 元 / 2 元每百万 token）约 0.002 元；以 DeepSeek 官网价格为准。
- 知识库条目为公开权威资料的转述，**上线前需眼科医生审核**（见知识库文件头）。迁移 `V3__ai_explanation.sql`。

## AI 对话（`POST /v1/chat`）

- 输入 `ChatRequest`（`shared/sync` 的 `Chat.kt`）：最近至多 12 条对话（用户每条 ≤ 300 字，最后一条必须是用户的提问；开场的综合建议以 assistant 身份放在最前），以及与综合建议同一份去标识概况 `AdviceRequest`（可空）。
- 需 `AI_CHAT` 同意（与 AI 综合建议分开：对话会发送用户自己打的字）；与解读、建议共用每分钟限速；每日条数 `EYE_LLM_CHAT_DAILY_LIMIT`；迁移 `V4__ai_chat_usage.sql`。
- 输出 `ChatReplyDto`：`reply`、`urgent`（提问里有眼痛、突然看不清等危险信号，由规则判断）、引用的参考资料、免责说明。对话记录只存在手机本机，服务器不保存。
- 费用参考：每条约 1.5–3k 输入 + 0.2k 输出 token，约 0.003–0.005 元。

## AI 综合建议（`POST /v1/advice`）

- 输入 `AdviceRequest`（`shared/sync`，由设备端共用 `EyeCareAdvice.request` 生成）：负荷等级与分数、主要原因、年龄段、职业、矫正方式、眼病史选项、
  近 7 天平均屏幕时间 / 最长连续 / 夜间使用天数 / 休息次数（仅安卓）、实时检测的眨眼频率 / 观看距离 / 头部偏转、环境光与亮度等级、各项自测风险等级、今日症状。
  不含姓名、账号、成员编号、具体日期、照片或视频。数据清单已经用户确认（2026-10-23）。
- 需 `AI_ADVICE` 同意（不触发记录同步）；与 AI 解读共用每日额度与每分钟限速；审计动作 `AI_ADVICE`。
- 输出：一句话总结、优先做的事、日常习惯、何时就医（有红旗症状或自测中 / 高风险时固定带上「就医」条目）、参考资料与免责说明；措辞不得诊断，等级只来自设备端。
- 知识库 2026-10-23 版新增 `gen-screen-breaks` / `gen-screen-distance` / `gen-night-screen` / `gen-fatigue` / `gen-child-screen` 与来源 `moe-myopia-2018`，**上线前需眼科医生审核**。

## 约束

- 越权一律返回 404；错误体 `{"error","message"}`，代码见 `ErrorCodes`。
- 记录不可变：同 id 同内容 200，不同内容 409。上传需要 `HEALTH_DATA_SYNC` 同意；儿童（关系为 CHILD、出生年月未满 14 岁、或问卷年龄未满 14 岁）还需该被测者的 `GUARDIAN` 同意。
- 被测者：`POST /v1/subjects` 可带客户端生成的 UUID（离线新建的成员与云端同号）；更新用 `PATCH` 或 `PUT /v1/subjects/{id}`（安卓 HttpURLConnection 不支持 PATCH）。
- 健康资料：`PUT /v1/subjects/{id}/profile`（`SubjectProfileInput`：键 → 答案 + 客户端修改时间），`GET /v1/subjects` 与导出返回解密后的资料。规则同记录：需 `HEALTH_DATA_SYNC`，儿童需该成员的 `GUARDIAN`；客户端修改时间早于云端已有版本返回 409，最多 64 项、值 ≤ 200 字。迁移 `V2__subject_profile.sql`。
- 日志不得打印请求体、令牌、邮箱。新增读取原文的接口必须写审计。
- 未部署：托管、TLS、KMS、短信登录均待决策（设计文档 §9），自建 Linux 服务器的部署步骤见 [`docs/后端部署说明.md`](../../docs/后端部署说明.md)；LLM 已按 §5.4 接入 DeepSeek，正式启用前需完成数据处理协议与知识库医学审核。
