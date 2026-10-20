from pathlib import Path
from html import escape
import math
from PIL import Image, ImageDraw, ImageFont

OUT = Path(__file__).parent
W, H = 3000, 2190
im = Image.new("RGB", (W, H), "#f4f7fc")
d = ImageDraw.Draw(im)
svg = [f'<svg xmlns="http://www.w3.org/2000/svg" width="{W}" height="{H}" viewBox="0 0 {W} {H}">',
       '<rect width="100%" height="100%" fill="#f4f7fc"/>']
INK, MUTED = "#172b46", "#52657e"
BLUE, TEAL, PURPLE, AMBER = "#2563eb", "#087f8c", "#7050b2", "#a66a0a"
fonts = {}

def font(n, bold=False):
    key = (n, bold)
    if key not in fonts:
        fonts[key] = ImageFont.truetype("C:/Windows/Fonts/msyhbd.ttc" if bold else "C:/Windows/Fonts/msyh.ttc", n)
    return fonts[key]

def text(x, y, s, size=26, color=INK, bold=False, maxw=None):
    f = font(size, bold)
    if maxw is not None:
        assert d.textlength(s, font=f) <= maxw, (s, d.textlength(s, font=f), maxw)
    d.text((x,y), s, font=f, fill=color, anchor="lt")
    svg.append(f'<text x="{x}" y="{y}" fill="{color}" font-family="Microsoft YaHei, Noto Sans CJK SC, sans-serif" font-size="{size}" font-weight="{700 if bold else 400}" dominant-baseline="text-before-edge">{escape(s)}</text>')

def rect(x,y,w,h,fill="#ffffff",stroke="#dce4f0",r=18,width=2):
    d.rounded_rectangle((x,y,x+w,y+h),radius=r,fill=fill,outline=stroke,width=width)
    svg.append(f'<rect x="{x}" y="{y}" width="{w}" height="{h}" rx="{r}" fill="{fill}" stroke="{stroke}" stroke-width="{width}"/>')

def line(points,color="#8192ab",width=3,arrow=True):
    d.line(points, fill=color, width=width)
    svg.append(f'<polyline points="{" ".join(f"{x},{y}" for x,y in points)}" fill="none" stroke="{color}" stroke-width="{width}" stroke-linejoin="round"/>')
    if arrow:
        x,y=points[-1]; px,py=points[-2]
        a=math.atan2(y-py,x-px)
        pts=[(x,y),(x-14*math.cos(a)+7*math.sin(a),y-14*math.sin(a)-7*math.cos(a)),(x-14*math.cos(a)-7*math.sin(a),y-14*math.sin(a)+7*math.cos(a))]
        d.polygon(pts,fill=color)
        svg.append(f'<polygon points="{" ".join(f"{x},{y}" for x,y in pts)}" fill="{color}"/>')

def panel(x,y,w,h,title,sub,color):
    rect(x,y,w,h,stroke="#d3ddeb",r=25)
    rect(x+24,y+26,8,48,fill=color,stroke=color,r=4)
    text(x+48,y+24,title,34,color,True,w-80)
    text(x+48,y+74,sub,23,MUTED,maxw=w-80)

def card(x,y,w,h,title,lines,color=BLUE,fill="#f8faff"):
    rect(x,y,w,h,fill=fill,stroke="#dce4f0",r=14)
    text(x+22,y+17,title,27,color,True,w-44)
    yy=y+62
    for s in lines:
        text(x+22,yy,s,24,INK,maxw=w-44)
        yy+=35
    assert yy-10 <= y+h, (title, yy, y+h)

text(50,38,"EyeProject · 眼健康助手",52,INK,True)
text(50,110,"系统架构与模块技术栈",37,INK,True)
text(1970,55,"代码现状  /  2026-10-20",28,MUTED,True)
text(1970,104,"原生双端 · 共享评估核心 · 可选云端同步",26,MUTED)

# Runtime request line uses the outside gutter, avoiding shared-library edges.
line([(1062,1470),(1120,1470),(1120,1685),(1880,1685),(1880,379),(1938,379)],BLUE,5)
text(1150,1645,"Android → REST /v1 · JSON · Bearer",27,BLUE,True)

panel(50,200,1040,1410,"01  Android 前端","Kotlin 2.2.10 · Compose BOM 2026.02.01 · Hilt 2.59.2",BLUE)
card(78,323,984,130,"app  应用入口与导航",[
    "Jetpack Compose / Material 3 · Navigation Compose 2.10.0",
    "Hilt 注入 · ViewModel + StateFlow · Coroutines 1.9.0"],BLUE)
line([(570,453),(570,483)],BLUE)
card(78,483,480,170,"feature:home  首页",[
    "Compose · HomeViewModel",
    "品牌、休息提醒、模型测试入口",
    "提醒调度：app / AlarmManager"],BLUE)
card(582,483,480,170,"feature:services  三项自测",[
    "Compose · ViewModel · Vico 2.1.3",
    "干眼 / 老视 / 斜视 · 报告与导出",
    "Vosk 0.3.75 · 系统识别 / TTS"],BLUE)
card(78,675,480,170,"feature:mine  我的与档案",[
    "Compose · ViewModel · Flow",
    "家庭成员、健康资料、历史报告",
    "登录、分项同意、云端同步"],BLUE)
card(582,675,480,170,"feature:testmodel  实验页",[
    "CameraX · MediaPipe · L2CS-Net",
    "LiteRT 2.1.1 · C++20 / JNI",
    "CMake / NDK · 注视估计实验"],BLUE)
card(78,867,480,135,"feature:agent  智能体占位",[
    "Compose · ViewModel / StateFlow",
    "固定回复「开发中」· 尚未接入 LLM"],AMBER,"#fffaf0")
card(582,867,480,135,"公共界面与工具",[
    "core:designsystem / ui：主题与组件",
    "core:common：格式化等通用工具"],BLUE)
card(78,1024,480,170,"core:camera  视觉采集",[
    "CameraX 1.4.1 · Camera2",
    "MediaPipe Tasks Vision 1.0.0",
    "Face Landmarker · 478 点 / 眼区"],BLUE)
card(582,1024,480,170,"领域接口与系统通知",[
    "core:model / domain → shared 转发",
    "core:notifications：系统通知",
    "app：AlarmManager / Receiver"],BLUE)
card(78,1216,984,170,"core:data  离线优先仓储",[
    "Account / Subject / ScreeningRecord Repository · Coroutines / Flow",
    "本机 JSON 记录、subjects.json、同步发件箱 · WorkManager 2.11.2",
    "SharedPreferences 保存偏好；会话使用 Android Keystore + AES-GCM"],BLUE)
card(78,1408,984,170,"core:network  HTTP 适配",[
    "HttpURLConnection · kotlinx.serialization JSON · shared:sync DTO",
    "AuthorizedCaller（core:data）负责令牌刷新与 401 重试",
    "配置服务地址 + 登录 + 同意后同步；未配置时仍可本机测试和保存"],BLUE)

panel(1150,200,700,810,"02  跨平台共享核心","shared/ · Kotlin Multiplatform 2.4.21",TEAL)
card(1178,323,644,135,"shared:model / domain",[
    "纯 Kotlin 数据模型、眨眼、虹膜测距",
    "眼区光学算法、关键点几何与平台端口"],TEAL,"#f2faf9")
card(1178,480,644,240,"shared:screening",[
    "干眼 / 老视 / 斜视状态机与测试协议",
    "质量门控、规则分级、报告快照",
    "健康资料校验、历史记录解析、反馈事件",
    "Coroutines 1.9.0 · Serialization 1.9.0",
    "Android：JVM 包；iOS：EyeScreening"],TEAL,"#f2faf9")
card(1178,742,644,235,"shared:sync",[
    "v1 DTO / 错误码 / JSON 契约",
    "RecordSummaries · SHA-256",
    "RecordSyncEngine · Outbox / Remote",
    "Android 与 Ktor 共用；iOS 尚未导出",
    "这是编译依赖，不是独立网络服务"],TEAL,"#f2faf9")
line([(1090,586),(1150,586)],TEAL,4)
text(1100,548,"①",25,TEAL,True)
line([(1910,856),(1850,856)],TEAL,4)
text(1862,817,"②",25,TEAL,True)

panel(1150,1100,700,510,"03  iOS 前端","iosApp/ · Swift 5 配置 · iOS 16+",PURPLE)
card(1178,1223,644,350,"SwiftUI + 原生平台能力",[
    "首页 / 服务 / 我的 · 自测与家庭成员资料",
    "AVFoundation · MediaPipeTasksVision 1.0.0",
    "Speech / AVSpeechSynthesizer · 中文语音",
    "Core Haptics · AVAudioPlayer · 交互反馈",
    "UserNotifications · 休息提醒",
    "FileManager / JSON · UserDefaults · 导出",
    "EyeScreening.framework 调用共享规则",
    "当前为本机数据链路；尚未接入云端同步"],PURPLE,"#faf7ff")
line([(1500,1100),(1500,1010)],PURPLE,4)
text(1522,1030,"③  静态 Framework 调用",24,PURPLE,True)

panel(1910,200,1040,1410,"04  后端服务","backend/server · Kotlin 2.4.21 / JVM 17 · Ktor 3.6.0",TEAL)
card(1938,323,984,170,"Application.kt  API 入口",[
    "Netty · Ktor Routing · ContentNegotiation / kotlinx.serialization",
    "REST /v1：auth / me / subjects / records",
    "Bearer 认证 · 限流 · 错误响应 · 请求体限制 · HTTPS 强制配置"],TEAL,"#f2faf9")
line([(2430,493),(2430,523)],TEAL)
card(1938,523,480,170,"AuthService  账号认证",[
    "注册、登录、登出",
    "不透明令牌 · 刷新轮换 / 重用检测",
    "PBKDF2 口令哈希"],TEAL,"#f2faf9")
card(2442,523,480,170,"ProfileService  成员与资料",[
    "账号 / 家庭成员 / 健康资料",
    "分项同意与监护人约束",
    "数据导出、账号注销"],TEAL,"#f2faf9")
card(1938,715,480,170,"RecordService  测试记录",[
    "幂等上传、列表、详情、删除",
    "历史与同病种对比 API",
    "shared:sync 摘要解析与契约"],TEAL,"#f2faf9")
card(2442,715,480,170,"安全、审计与保留期",[
    "Security：AES-256-GCM / AAD",
    "AuditLog · Logback 1.6.5",
    "RetentionJob · 协程定时清理"],TEAL,"#f2faf9")
line([(2430,907),(2430,949)],TEAL)
card(1938,949,984,170,"Database.kt  数据访问",[
    "JDBC 参数化 SQL / 事务 · Coroutines Dispatchers.IO",
    "HikariCP 7.1.0 连接池 · Flyway 13.10.0 版本迁移",
    "V1：核心表结构；V2：成员健康资料"],TEAL,"#f2faf9")
line([(2430,1119),(2430,1149)],TEAL)
card(1938,1149,480,170,"开发与测试存储",[
    "H2 2.5.252",
    "开发：本地文件数据库",
    "测试：内存库 / PostgreSQL 模式"],TEAL,"#f2faf9")
card(2442,1149,480,170,"生产数据库配置",[
    "PostgreSQL · JDBC 42.7.14",
    "账号、成员、同意、记录、审计",
    "健康资料 / 记录原文加密入库"],TEAL,"#f2faf9")
card(1938,1341,984,237,"接口与部署边界",[
    "同一 Ktor 进程内按职责分层；以上 Service 并非独立微服务",
    "记录历史 / 对比 API 已实现；当前 Android 网络层尚未接入这些查询",
    "生产模式要求 PostgreSQL、数据密钥和审计盐等配置",
    "代码与配置已核对；此图不代表生产服务已经部署",
    "外部托管、TLS 终止与 KMS 的实际部署未在本次验证"],AMBER,"#fffaf0")

panel(50,1740,1040,300,"05  离线研究工具","backend/screening_model.py · Python 标准库",AMBER)
text(80,1860,"记录 JSON + 临床标签 CSV  →  逻辑回归训练 / 留出评估",26,INK,maxw=980)
text(80,1910,"输出模型 JSON / 研究分数；未接入 Ktor 在线推理链路",26,INK,maxw=980)
text(80,1960,"当前手机评估由 shared:screening 的规则与质量门控完成",26,INK,maxw=980)

panel(1150,1740,1800,300,"阅读说明与构建基座","实线：调用或处理方向；编号箭头：共享库依赖（非网络请求）",INK)
text(1180,1860,"① Android 复用共享逻辑    ② Ktor 复用 shared:sync    ③ iOS 复用 model / domain / screening",26,INK,maxw=1740)
text(1180,1910,"Android：Gradle 9.5 / AGP 9.3.0 / KSP 2.2.10-2.0.2；iOS：XcodeGen / CocoaPods / Xcode 26.4",26,INK,maxw=1740)
text(1180,1960,"范围：当前主工程与未提交改动；newpart 的 FastAPI / MySQL 属于独立参考工程，未计入主系统。",26,INK,maxw=1740)

text(50,2092,"核对依据：模块构建文件、Android / Swift 源码、Ktor 路由与 SQL 迁移；版本表示仓库声明值。",25,MUTED)
text(50,2133,"整体结构：原生前端 → 本机共享规则与离线数据 → 按条件同步到账号与记录服务",25,MUTED)

svg.append("</svg>")
(OUT/"EyeProject-architecture.svg").write_text("\n".join(svg),encoding="utf-8")
im.save(OUT/"EyeProject-architecture.png")
im.resize((1500,1095),Image.Resampling.LANCZOS).save(OUT/"preview.png")
print("Created SVG and PNG; all text checked against card width and height.")
