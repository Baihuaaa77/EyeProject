# backend/server — 账号与测试记录服务

Ktor 3 API 服务：账号、家庭成员（被测者）及其健康资料、分项同意、测试记录同步、历史与对比、导出、注销、审计与保留期清理。
设计依据与路线见 [`docs/后端与智能评估设计-2026-10-15.md`](../../docs/后端与智能评估设计-2026-10-15.md)。

## 运行与测试

```powershell
.\gradlew.bat -p backend/server test   # 16 项接口与安全测试（内存 H2）
.\gradlew.bat -p backend/server run    # 开发模式：http://localhost:8080，数据在 backend/server/data/
```

本机若有 `E:\Android\gradle-cache\gradle.properties` 写死的旧代理端口导致下载失败，命令后加 `-Dhttp.proxyPort=<端口> -Dhttps.proxyPort=<端口>` 覆盖。

iOS 连本机服务：在「我的 → 云端同步与数据 → 服务器地址」填电脑的局域网地址（如 `http://192.168.1.5:8080`），手机与电脑连同一 Wi-Fi；
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
| `AuditLog.kt` / `RetentionJob.kt` | 审计（不含健康内容）；每日清理过期令牌、审计与（可选）过期记录 |
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

## 约束

- 越权一律返回 404；错误体 `{"error","message"}`，代码见 `ErrorCodes`。
- 记录不可变：同 id 同内容 200，不同内容 409。上传需要 `HEALTH_DATA_SYNC` 同意；儿童（关系为 CHILD、出生年月未满 14 岁、或问卷年龄未满 14 岁）还需该被测者的 `GUARDIAN` 同意。
- 被测者：`POST /v1/subjects` 可带客户端生成的 UUID（离线新建的成员与云端同号）；更新用 `PATCH` 或 `PUT /v1/subjects/{id}`（安卓 HttpURLConnection 不支持 PATCH）。
- 健康资料：`PUT /v1/subjects/{id}/profile`（`SubjectProfileInput`：键 → 答案 + 客户端修改时间），`GET /v1/subjects` 与导出返回解密后的资料。规则同记录：需 `HEALTH_DATA_SYNC`，儿童需该成员的 `GUARDIAN`；客户端修改时间早于云端已有版本返回 409，最多 64 项、值 ≤ 200 字。迁移 `V2__subject_profile.sql`。
- 日志不得打印请求体、令牌、邮箱。新增读取原文的接口必须写审计。
- 未部署：托管、TLS、KMS、短信登录、LLM 均待决策（设计文档 §9）。
