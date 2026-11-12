package com.zkyjss.eyeproject.core.designsystem.theme

import androidx.compose.ui.unit.dp

/**
 * 间距令牌（4 / 8dp 节奏，按 ui-ux-pro-max「8dp spacing rhythm」「section spacing hierarchy」）：
 * 元素内 [xs]/[sm] → 卡片内 [md] → 卡片之间 [lg] → 分区之间 [xxl]。页面左右留白统一 [gutter]。
 * 与苹果端 EyeSpacing 一一对应。
 */
object EyeSpacing {
    val xxs = 4.dp
    val xs = 8.dp
    val sm = 12.dp
    val md = 16.dp
    val lg = 20.dp
    val xl = 24.dp
    val xxl = 32.dp

    /** 页面左右留白。 */
    val gutter = 20.dp
    /** 普通卡片内边距。 */
    val cardPadding = 20.dp
    /** 主卡内边距（更宽松，突出层级）。 */
    val heroPadding = 20.dp
    /** 卡片之间的间距（分区标题上方另留 [sectionGap]）。 */
    val cardGap = 12.dp
    /** 分区标题与上一块内容之间。 */
    val sectionGap = 24.dp
    val itemGap = 12.dp
}
