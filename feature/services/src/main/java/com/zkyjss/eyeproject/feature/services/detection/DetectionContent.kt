package com.zkyjss.eyeproject.feature.services.detection

import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zkyjss.eyeproject.core.designsystem.theme.LocalEyeColors
import com.zkyjss.eyeproject.core.designsystem.theme.LocalEyeTypography
import com.zkyjss.eyeproject.core.designsystem.util.screenHorizontalPaddingLarge
import com.zkyjss.eyeproject.core.ui.component.EyeSectionCard
import com.zkyjss.eyeproject.core.ui.component.EyeText
import com.zkyjss.eyeproject.core.ui.component.TopBackBar
import java.util.Locale

/** 检测服务通用界面：摄像头预览 + 眼睛关键点标记 + 下方距离/眨眼次数。 */
@Composable
fun DetectionContent(
    title: String,
    description: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: DetectionViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val colors = LocalEyeColors.current
    val typography = LocalEyeTypography.current

    val previewView = remember {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }

    LaunchedEffect(Unit) { viewModel.start(lifecycleOwner, previewView) }
    DisposableEffect(Unit) {
        onDispose { viewModel.stop() }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.white)
            .screenHorizontalPaddingLarge()
    ) {
        TopBackBar(onBackClick = onBack)

        Column(modifier = Modifier.fillMaxWidth()) {
            EyeText(text = title, style = typography.subtitle, color = colors.textPrimary)
            Spacer(Modifier.height(4.dp))
//            EyeText(text = description, style = typography.body, color = colors.textSecondary)
        }
        Spacer(Modifier.height(12.dp))

        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.7f)
                    .aspectRatio(3f / 4f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(colors.cameraSurface),
            ) {
                AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
                EyeOverlay(
                    detections = state.detections,
                    sourceWidth = state.sourceWidth,
                    sourceHeight = state.sourceHeight,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
        Spacer(Modifier.height(16.dp))

        EyeSectionCard(title = "实时检测") {
            MetricRow(
                label = "用眼距离",
                value = formatDistance(state.distanceCm),
                valueColor = if (state.distanceCm > 0f) colors.success else colors.textSecondary
            )
            MetricRow(
                label = "眨眼次数",
                value = "${state.blinkCount} 次",
                valueColor = colors.warning
            )
            MetricRow(
                label = "人脸状态",
                value = if (state.faceDetected) "已识别" else "未识别",
                valueColor = if (state.faceDetected) colors.success else colors.textSecondary
            )
            if (state.error != null) {
                Spacer(Modifier.height(8.dp))
                EyeText(text = state.error.orEmpty(), style = typography.body, color = colors.danger)
            }
        }
    }
}

/** 单行指标：左标签 + 颜色圆点 + 右数值。 */
@Composable
private fun MetricRow(
    label: String,
    value: String,
    valueColor: Color,
    modifier: Modifier = Modifier,
) {
    val colors = LocalEyeColors.current
    val typography = LocalEyeTypography.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(28.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        EyeText(text = label, style = typography.body, color = colors.textPrimary)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(valueColor)
            )
            Spacer(Modifier.width(6.dp))
            EyeText(text = value, style = typography.body, color = valueColor)
        }
    }
}

private fun formatDistance(cm: Float): String =
    if (cm > 0f) String.format(Locale.US, "%.0f cm", cm) else "--"
