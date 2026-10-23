package com.zkyjss.eyeproject.feature.mine

import android.Manifest
import android.app.AlarmManager
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Process
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.zkyjss.eyeproject.core.designsystem.theme.LocalEyeColors
import com.zkyjss.eyeproject.core.ui.component.EyeGlyph
import com.zkyjss.eyeproject.core.ui.component.EyeListGroup
import com.zkyjss.eyeproject.core.ui.component.EyeListRow
import com.zkyjss.eyeproject.core.ui.component.EyeSubPage
import com.zkyjss.eyeproject.core.ui.component.EyeTileTone

/** 各项权限当前是否已允许；null 表示本机系统版本没有这项开关。 */
internal data class PermissionStatus(
    val camera: Boolean = false,
    val microphone: Boolean = false,
    val notifications: Boolean = false,
    val exactAlarm: Boolean? = null,
    val usageAccess: Boolean = false,
)

/** 权限要做什么、去哪里改。 */
internal enum class PermissionItem { CAMERA, MICROPHONE, NOTIFICATIONS, EXACT_ALARM, USAGE_ACCESS, APP }

/**
 * 「我的 → 权限与通知」完整页面：逐项显示用途与是否已允许，点一下去系统设置对应位置；回到本页自动刷新。
 * 苹果端只有相机、麦克风、语音识别与通知四项（没有用屏统计与精确闹钟）。
 */
@Composable
fun PermissionsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var status by remember { mutableStateOf(readPermissions(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { status = readPermissions(context) }
    PermissionsContent(status, onBack) { item -> runCatching { context.startActivity(settingsIntent(context, item)) } }
}

@Composable
internal fun PermissionsContent(status: PermissionStatus, onBack: () -> Unit, onOpen: (PermissionItem) -> Unit) {
    val colors = LocalEyeColors.current
    fun value(granted: Boolean) = if (granted) "已允许" else "未允许"
    fun tint(granted: Boolean) = if (granted) colors.success else colors.warning
    EyeSubPage("权限与通知", onBack) {
        EyeListGroup(title = "测试与检测") {
            EyeListRow("相机", { onOpen(PermissionItem.CAMERA) }, glyph = EyeGlyph.CAMERA, subtitle = "检测眼部关键点、眨眼与距离",
                value = value(status.camera), valueColor = tint(status.camera))
            EyeListRow("麦克风", { onOpen(PermissionItem.MICROPHONE) }, glyph = EyeGlyph.MIC, tone = EyeTileTone.SUCCESS, subtitle = "测试中用语音作答",
                value = value(status.microphone), valueColor = tint(status.microphone), divider = false)
        }
        EyeListGroup(title = "主动护眼") {
            EyeListRow("通知", { onOpen(PermissionItem.NOTIFICATIONS) }, glyph = EyeGlyph.BELL, tone = EyeTileTone.ACCENT, subtitle = "休息提醒",
                value = value(status.notifications), valueColor = tint(status.notifications))
            status.exactAlarm?.let { granted ->
                EyeListRow("准时提醒", { onOpen(PermissionItem.EXACT_ALARM) }, glyph = EyeGlyph.CLOCK, tone = EyeTileTone.ACCENT,
                    subtitle = "按设定间隔准时提醒休息", value = value(granted), valueColor = tint(granted))
            }
            EyeListRow("用屏统计", { onOpen(PermissionItem.USAGE_ACCESS) }, glyph = EyeGlyph.HOURGLASS, tone = EyeTileTone.BLUE,
                subtitle = "每天用屏多久、连续用了多久，只在本机统计", value = value(status.usageAccess), valueColor = tint(status.usageAccess), divider = false)
        }
        EyeListGroup {
            EyeListRow("打开系统应用设置", { onOpen(PermissionItem.APP) }, glyph = EyeGlyph.GEAR, tone = EyeTileTone.NEUTRAL, divider = false)
        }
    }
}

internal fun readPermissions(context: Context): PermissionStatus {
    fun granted(permission: String) = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    val ops = context.getSystemService(AppOpsManager::class.java)
    val usage = ops != null && (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
    else @Suppress("DEPRECATION") ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)) == AppOpsManager.MODE_ALLOWED
    return PermissionStatus(
        camera = granted(Manifest.permission.CAMERA),
        microphone = granted(Manifest.permission.RECORD_AUDIO),
        notifications = NotificationManagerCompat.from(context).areNotificationsEnabled(),
        exactAlarm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) context.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() == true else null,
        usageAccess = usage,
    )
}

private fun settingsIntent(context: Context, item: PermissionItem): Intent {
    val app = Uri.parse("package:${context.packageName}")
    return when (item) {
        PermissionItem.NOTIFICATIONS -> Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        PermissionItem.EXACT_ALARM -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, app)
            else Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, app)
        PermissionItem.USAGE_ACCESS -> Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
        else -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, app)
    }
}
