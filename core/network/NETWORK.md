# core:network

云端接口的唯一网络数据源（NIA `XNetworkDataSource`）。契约来自 `shared/sync`，服务端见 `backend/server`。

## 结构

| 类型 | 说明 |
|---|---|
| `EyeNetworkDataSource` | 接口：注册、登录、刷新、登出、账号、同意、上传记录、导出、注销；结果为 `NetworkResult`（成功 / `HttpError` 带稳定错误代码 / `Unreachable`） |
| `HttpEyeNetworkDataSource` | `HttpURLConnection` 实现，IO 线程执行；不记录请求体与令牌 |
| `di/NetworkModule` | Hilt 绑定 |

## 服务器地址

构建期常量 `BuildConfig.API_BASE_URL`，来源依次为 Gradle 属性 `-Peyeproject.apiBaseUrl=...`、根 `local.properties` 的 `eyeproject.apiBaseUrl=...`。
**缺省为空**：`isConfigured = false`，「我的」页显示「未配置云端服务」，任何数据都不会离开设备。正式地址待托管决策（设计文档 §9）。

- 模拟器连本机开发服务：`eyeproject.apiBaseUrl=http://10.0.2.2:8080`
- 明文 HTTP 只在 debug 构建允许（`app/src/debug/res/xml/network_security_config.xml`），release 只允许 HTTPS。

## 依赖

`api(shared:sync)`（DTO 对上层可见）；被 `core:data` 使用。令牌保存与刷新、重试策略不在本模块。
