package com.zkyjss.eyeproject.core.ui.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import com.zkyjss.eyeproject.core.designsystem.theme.*

/** 纯展示项；路由、选中状态和切换动作由 app 持有。 */
data class EyeNavigationItem(val label: String, val icon: Int, val selectedIcon: Int)

/** 浮动玻璃底栏与滑动选中镜片。保持原有高度、图标、名称和 Tab 语义。 */
@Composable
fun EyeNavigationDock(items: List<EyeNavigationItem>, selectedIndex: Int, onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier) {
    if (items.isEmpty()) return
    val colors = LocalEyeColors.current
    val reduced = rememberReducedMotion()
    BoxWithConstraints(modifier.fillMaxWidth().height(EyeLiquidGlass.dockHeight)
        .eyeLiquidGlass(colors, CircleShape).selectableGroup()) {
        val width = (maxWidth - EyeLiquidGlass.dockInset * 2 - EyeLiquidGlass.dockGap * (items.size - 1)) / items.size
        val x by animateDpAsState(EyeLiquidGlass.dockInset + (width + EyeLiquidGlass.dockGap) * selectedIndex.coerceIn(items.indices),
            if (reduced) snap() else tween(EyeMotion.MEDIUM), label = "玻璃选中镜片")
        if (selectedIndex in items.indices) Box(Modifier.offset(x = x).padding(vertical = EyeLiquidGlass.dockInset)
            .width(width).fillMaxHeight().eyeLiquidGlass(colors, CircleShape, colors.brandStart))
        Row(Modifier.fillMaxSize().padding(EyeLiquidGlass.dockInset), horizontalArrangement = Arrangement.spacedBy(EyeLiquidGlass.dockGap)) {
            items.forEachIndexed { index, item ->
                val active = index == selectedIndex
                val tint by animateColorAsState(if (active) (if (colors.isDark) colors.dataCyan else colors.primaryDark) else colors.textSecondary,
                    tween(if (reduced) 0 else EyeMotion.MEDIUM), label = "导航色")
                Column(Modifier.weight(1f).fillMaxHeight().selectable(active, role = Role.Tab, onClick = { onSelect(index) }),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    EyeIcon(painterResource(if (active) item.selectedIcon else item.icon), null,
                        Modifier.size(EyeLiquidGlass.dockIconSize), tint = tint)
                    EyeText(item.label, style = LocalEyeTypography.current.tab, color = colors.textPrimary)
                }
            }
        }
    }
}
