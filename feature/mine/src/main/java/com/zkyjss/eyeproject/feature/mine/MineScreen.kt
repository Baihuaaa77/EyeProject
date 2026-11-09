package com.zkyjss.eyeproject.feature.mine

import com.zkyjss.eyeproject.core.designsystem.theme.eyeGlowBorder
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.IntrinsicSize
import com.zkyjss.eyeproject.core.ui.component.LocalEyeThemeToggle
import com.zkyjss.eyeproject.core.ui.component.EyeSwitchRow
import com.zkyjss.eyeproject.core.ui.component.eyeReading
import androidx.compose.foundation.background
import com.zkyjss.eyeproject.core.ui.component.eyeBackdrop
import com.zkyjss.eyeproject.core.ui.component.eyeGlass
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zkyjss.eyeproject.core.designsystem.theme.EyeShapes
import com.zkyjss.eyeproject.core.designsystem.theme.eyeSoftSurface
import com.zkyjss.eyeproject.core.designsystem.theme.LocalEyeColors
import com.zkyjss.eyeproject.core.designsystem.theme.LocalEyeTypography
import com.zkyjss.eyeproject.core.designsystem.util.getTopStatusBarPadding
import com.zkyjss.eyeproject.core.designsystem.util.screenHorizontalPaddingLarge
import com.zkyjss.eyeproject.core.ui.component.EyeGlyph
import com.zkyjss.eyeproject.core.ui.component.EyeIcon
import com.zkyjss.eyeproject.core.ui.component.EyeListGroup
import com.zkyjss.eyeproject.core.ui.component.EyeListRow
import com.zkyjss.eyeproject.core.ui.component.EyePageHeader
import com.zkyjss.eyeproject.core.ui.component.EyeText
import com.zkyjss.eyeproject.core.ui.component.EyeTileTone
import com.zkyjss.eyeproject.core.ui.component.rememberEyeHaptics
import com.zkyjss.eyeproject.feature.services.SubjectProfiles

/** 「我的」入口页的跳转，由 app 的 NavHost 接线。 */
data class MineNavigation(
    val onOpenSettings: () -> Unit = {},
    val onOpenAccount: () -> Unit = {},
    val onEditSubject: (String?) -> Unit = {},
    val onOpenRecords: () -> Unit = {},
    val onOpenSubjects: () -> Unit = {},
    val onOpenCloudSync: () -> Unit = {},
    val onOpenPermissions: () -> Unit = {},
    val onOpenTrend: () -> Unit = {},
)

/**
 * 「我的」入口页：顶部个人卡（称呼首字头像 + 本人称呼与档案完善度 + 账号一行），三格概览，
 * 下面是分组入口（健康档案 / 数据与隐私 / 通用），点进二级完整页面。不在本页堆放开关与按钮。与苹果端 MinePage 同一排版与文案。
 */
@Composable
fun MineScreen(navigation: MineNavigation, modifier: Modifier = Modifier, viewModel: MineViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    MineContent(state, navigation, modifier)
}

/** 无副作用的页面内容，供状态驱动及本地界面检查复用。 */
@Composable
internal fun MineContent(state: MineUiState, navigation: MineNavigation, modifier: Modifier = Modifier) {
    val colors = LocalEyeColors.current
    val ready = state as? MineUiState.Ready
    Column(
        modifier.fillMaxSize().background(colors.appBackground).verticalScroll(rememberScrollState()).eyeBackdrop(colors)
            .padding(top = getTopStatusBarPadding()).screenHorizontalPaddingLarge(),
        verticalArrangement = Arrangement.spacedBy(com.zkyjss.eyeproject.core.designsystem.theme.EyeSpacing.cardGap),
    ) {
        EyePageHeader("我的", navigation.onOpenSettings)
        ProfileCard(ready, navigation)
        StatsRow(ready, navigation)
        EyeListGroup(title = "健康档案") {
            EyeListRow("测试记录", navigation.onOpenRecords, glowBorder = false, glyph = EyeGlyph.DOCUMENT,
                value = ready?.recordCount?.let { if (it == 0) "暂无" else "$it 条" })
            EyeListRow("家庭成员与资料", navigation.onOpenSubjects, glowBorder = false, glyph = EyeGlyph.FAMILY, tone = EyeTileTone.VIOLET,
                value = ready?.memberCount?.let { "$it 人" })
            EyeListRow("用眼趋势", navigation.onOpenTrend, glowBorder = false, glyph = EyeGlyph.CHART, tone = EyeTileTone.VIOLET, divider = false)
        }
        EyeListGroup(title = "数据与隐私") {
            EyeListRow("云端同步", navigation.onOpenCloudSync, glowBorder = false, glyph = EyeGlyph.CLOUD, tone = EyeTileTone.BLUE,
                value = ready?.let { cloudSummary(it.cloudConfigured, it.email, it.healthDataSync) })
            EyeListRow("权限与通知", navigation.onOpenPermissions, glowBorder = false, glyph = EyeGlyph.SHIELD, tone = EyeTileTone.SUCCESS, divider = false)
        }
        Spacer(Modifier.height(20.dp))
    }
}

/** 个人卡：称呼首字头像 + 本人称呼、年龄与档案完善度；下方一行账号状态，点开账户页。 */
@Composable
private fun ProfileCard(state: MineUiState.Ready?, navigation: MineNavigation) {
    val colors = LocalEyeColors.current
    val typography = LocalEyeTypography.current
    val haptics = rememberEyeHaptics()
    val self = state?.self
    val complete = profileCompleteness(self)
    Column(Modifier.fillMaxWidth().eyeGlass(colors, EyeShapes.hero).eyeGlowBorder(colors, EyeShapes.hero)) {
        Row(
            Modifier.fillMaxWidth().clickable(role = Role.Button) { haptics.tap(); navigation.onEditSubject(self?.id) }
                .padding(start = 18.dp, end = 16.dp, top = 18.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(56.dp).eyeSoftSurface(colors, EyeShapes.medium, compact = true), contentAlignment = Alignment.Center) {
                EyeText((self?.nickname?.ifBlank { null } ?: "我").take(1), style = typography.title, color = colors.onPrimary)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                EyeText(self?.nickname?.ifBlank { null } ?: "我", style = typography.title, color = colors.textPrimary)
                val age = self?.let { SubjectProfiles.age(it.fields, System.currentTimeMillis()) }
                EyeText(listOfNotNull(age?.let { "$it 岁" }, if (complete >= 5) "档案已完善" else "档案完善 $complete/5").joinToString(" · "),
                    style = typography.body, color = colors.textSecondary)
            }
            EyeIcon(painterResource(com.zkyjss.eyeproject.core.designsystem.R.drawable.ic_arrow_right), null, Modifier.size(14.dp), tint = colors.primary)
        }
        Box(Modifier.padding(start = 16.dp).fillMaxWidth().height(0.5.dp).background(colors.borderSubtle))
        Row(
            Modifier.fillMaxWidth().clickable(role = Role.Button) { haptics.tap(); navigation.onOpenAccount() }
                .padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val email = state?.email
            EyeText(email ?: "未登录", Modifier.weight(1f), style = typography.body,
                color = if (email != null) colors.textPrimary else colors.textSecondary, maxLines = 1)
            EyeText(if (email != null) "账户" else "登录 / 注册", style = typography.body, color = colors.primary)
        }
    }
}

/** 三格概览：测试记录、家庭成员、已上传——一张卡里细竖线分隔，数字大、单位灰。 */
@Composable
private fun StatsRow(state: MineUiState.Ready?, navigation: MineNavigation) {
    val colors = LocalEyeColors.current
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).eyeGlass(colors, EyeShapes.panel)) {
        StatTile("测试记录", "${state?.recordCount ?: 0}", "条", Modifier.weight(1f), navigation.onOpenRecords)
        Box(Modifier.padding(vertical = 14.dp).width(0.5.dp).fillMaxHeight().background(colors.borderSubtle))
        StatTile("家庭成员", "${state?.memberCount ?: 0}", "人", Modifier.weight(1f), navigation.onOpenSubjects)
        Box(Modifier.padding(vertical = 14.dp).width(0.5.dp).fillMaxHeight().background(colors.borderSubtle))
        StatTile("已上传", "${state?.synced ?: 0}", "条", Modifier.weight(1f), navigation.onOpenCloudSync)
    }
}

@Composable
private fun StatTile(label: String, value: String, unit: String, modifier: Modifier, onClick: () -> Unit) {
    val colors = LocalEyeColors.current
    val typography = LocalEyeTypography.current
    val haptics = rememberEyeHaptics()
    Column(
        modifier.clickable(role = Role.Button) { haptics.tap(); onClick() }.padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        EyeText(eyeReading(value, unit), style = typography.title, color = colors.textPrimary, maxLines = 1)
        EyeText(label, style = typography.label, color = colors.textSecondary)
    }
}
