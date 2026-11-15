package com.zkyjss.eyeproject.core.ui.component

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.zkyjss.eyeproject.core.designsystem.theme.*

data class EyeNavigationItem(val label: String, val icon: Int, val selectedIcon: Int)

/** 参考稿的杏桃色选中胶囊；路由仍由 app 持有。 */
@Composable
fun EyeNavigationDock(
    items: List<EyeNavigationItem>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (items.isEmpty()) return
    val c = LocalEyeColors.current
    val reduced = rememberReducedMotion()
    BoxWithConstraints(
        modifier.fillMaxWidth().height(60.dp).eyeSoftSurface(c, CircleShape).selectableGroup()
    ) {
        val width = (maxWidth - 12.dp) / items.size
        val x by
            animateDpAsState(
                6.dp + width * selectedIndex.coerceIn(items.indices),
                if (reduced) snap() else tween(EyeMotion.MEDIUM),
                label = "导航胶囊",
            )
        if (selectedIndex in items.indices)
            Box(
                Modifier.offset(x = x)
                    .padding(vertical = 6.dp)
                    .width(width)
                    .fillMaxHeight()
                    .clip(CircleShape)
                    .background(c.buttonBrush)
            )
        Row(Modifier.fillMaxSize().padding(6.dp)) {
            items.forEachIndexed { index, item ->
                val active = index == selectedIndex
                val tint = if (active) c.onPrimary else c.textSecondary
                Row(
                    Modifier.weight(1f)
                        .fillMaxHeight()
                        .selectable(active, role = Role.Tab, onClick = { onSelect(index) }),
                    horizontalArrangement =
                        Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    EyeIcon(painterResource(item.icon), null, Modifier.size(22.dp), tint = tint)
                    EyeText(item.label, style = LocalEyeTypography.current.body, color = tint)
                }
            }
        }
    }
}
