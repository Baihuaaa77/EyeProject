package com.zkyjss.eyeproject.feature.mine

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.zkyjss.eyeproject.core.designsystem.theme.LocalEyeColors
import com.zkyjss.eyeproject.core.designsystem.theme.LocalEyeTypography
import com.zkyjss.eyeproject.core.designsystem.util.getTopStatusBarPadding
import com.zkyjss.eyeproject.core.designsystem.util.screenHorizontalPaddingLarge
import com.zkyjss.eyeproject.core.ui.component.EyeBrandLockup
import com.zkyjss.eyeproject.core.ui.component.EyeSectionCard
import com.zkyjss.eyeproject.core.ui.component.EyeSelectCard
import com.zkyjss.eyeproject.core.ui.component.EyeText

/** 「我的」页：系统权限入口、关于与免责声明。 */
@Composable
fun MineScreen(
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val versionName = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }
            .getOrNull().orEmpty()
    }
    MineContent(
        versionName = versionName,
        modifier = modifier,
        onOpenAppSettings = {
            context.startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:${context.packageName}")
                )
            )
        },
    )
}

/** 无副作用的页面内容，供状态驱动及本地界面检查复用。 */
@Composable
internal fun MineContent(
    versionName: String,
    onOpenAppSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalEyeColors.current
    val typography = LocalEyeTypography.current
    Column(
        modifier
            .fillMaxSize()
            .background(colors.appBackground)
            .verticalScroll(rememberScrollState())
            .padding(top = getTopStatusBarPadding())
            .screenHorizontalPaddingLarge(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        EyeText("我的", style = typography.headline, color = colors.textPrimary, modifier = Modifier.padding(vertical = 16.dp))

        EyeSelectCard(content = "权限与通知", onClick = onOpenAppSettings)

        EyeSectionCard(title = "关于") {
            EyeBrandLockup()
            Spacer(Modifier.height(12.dp))
            if (versionName.isNotEmpty()) {
                EyeText("版本 $versionName", style = typography.bodySecondary, color = colors.textSecondary)
                Spacer(Modifier.height(4.dp))
            }
            EyeText(
                "本应用的评估与自测结果仅供日常用眼参考，不能替代专业眼科检查。如有眼部不适，请及时就医。",
                style = typography.bodySecondary,
                color = colors.textSecondary
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}
