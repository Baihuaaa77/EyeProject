package com.zkyjss.eyeproject.core.ui.component

import android.graphics.drawable.Icon
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.zkyjss.eyeproject.core.designsystem.theme.EyeShapes
import com.zkyjss.eyeproject.core.designsystem.theme.LocalEyeColors
import com.zkyjss.eyeproject.core.designsystem.theme.LocalEyeTypography
import com.zkyjss.eyeproject.core.designsystem.util.clickableNoRipple
import com.zkyjss.eyeproject.core.ui.R

@Composable
fun EyeSelectCard(
    modifier: Modifier = Modifier,
    content: String,
    onClick: () -> Unit
) {
    val colors = LocalEyeColors.current
    val typography = LocalEyeTypography.current
    Row (
        modifier = modifier
            .fillMaxWidth()
            .clip(EyeShapes.panel)
            .background(colors.panelBackground)
            .border(width = 1.dp, color = colors.borderSubtle, shape = EyeShapes.panel)
            .height(72.dp)
            .padding(horizontal = 12.dp)
            .clickableNoRipple(onClick = onClick),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        EyeText(
            text = content,
            style = typography.subtitle,
            color = colors.textPrimary
        )

        EyeIcon(
            modifier = Modifier.size(32.dp),
            painter = painterResource(com.zkyjss.eyeproject.core.designsystem.R.drawable.ic_arrow_right),
            contentDescription = "",
            tint = colors.gray
        )
    }
}
