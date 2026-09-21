package com.zkyjss.eyeproject.feature.testmodel.standalone

import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zkyjss.eyeproject.core.designsystem.theme.LocalEyeColors
import com.zkyjss.eyeproject.core.designsystem.theme.LocalEyeTypography
import com.zkyjss.eyeproject.core.ui.component.EyeButton
import com.zkyjss.eyeproject.core.ui.component.EyeButtonRole
import com.zkyjss.eyeproject.core.ui.component.EyeText
import com.zkyjss.eyeproject.core.ui.component.TopBackBar
import java.util.Locale

/** 全替换眨眼测试页：BlazeFace 检测 + OCEC 分类（LiteRT，无 MediaPipe）。 */
@Composable
fun StandaloneBlinkScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: StandaloneBlinkViewModel = hiltViewModel()
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

    LaunchedEffect(Unit) {
        viewModel.startCamera(lifecycleOwner, previewView)
    }
    DisposableEffect(Unit) {
        onDispose { viewModel.stopCamera() }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.white),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        TopBackBar(onBackClick = onBack)

        Box(
            modifier = Modifier
                .weight(1f)
                .background(colors.cameraSurface)
        ) {
            AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())

            StandaloneOverlay(
                faceDetected = state.faceDetected,
                faceBox = state.faceBox,
                rightEyeBox = state.rightEyeBox,
                leftEyeBox = state.leftEyeBox,
                rightOpen = state.rightProb?.let { it >= 0.5f },
                leftOpen = state.leftProb?.let { it >= 0.5f },
                sourceWidth = state.sourceWidth,
                sourceHeight = state.sourceHeight,
                modifier = Modifier.fillMaxSize()
            )

            // 数据面板
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .background(colors.overlayScrim, shape = RoundedCornerShape(corner = CornerSize(2.dp)))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Column {
                    EyeText(
                        text = "BlazeFace+OCEC (无MediaPipe)",
                        style = typography.subtitle,
                        color = colors.success
                    )
                    EyeText(
                        text = probLine("L", state.leftProb),
                        style = typography.subtitle,
                        color = colors.success
                    )
                    EyeText(
                        text = probLine("R", state.rightProb),
                        style = typography.subtitle,
                        color = colors.success
                    )
                    EyeText(
                        text = "blink: ${state.blinkCount}",
                        style = typography.subtitle,
                        color = colors.success
                    )
                    EyeText(
                        text = String.format(Locale.US, "%.1f fps", state.fps),
                        style = typography.subtitle,
                        color = colors.success
                    )
                    EyeText(
                        text = String.format(
                            Locale.US,
                            "det %d ms · cls %d ms",
                            state.detectionLatencyMillis,
                            state.classificationLatencyMillis
                        ),
                        style = typography.subtitle,
                        color = colors.success
                    )
                    if (state.recording) {
                        EyeText(
                            text = "rec: 剩余 ${state.recordRemainS}s",
                            style = typography.subtitle,
                            color = colors.warning
                        )
                    }
                    if (state.savedPath != null) {
                        EyeText(
                            text = "saved: ${state.savedPath!!.substringAfterLast('/')}",
                            style = typography.body,
                            color = colors.success
                        )
                    }
                    if (state.error != null) {
                        EyeText(text = state.error!!, style = typography.body, color = colors.warning)
                    }
                }
            }

            // 控制按钮
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                EyeButton(
                    text = "重置计数",
                    role = EyeButtonRole.SECONDARY,
                    onClick = viewModel::resetBlinkCount,
                    enabled = !state.recording
                )
                EyeButton(
                    text = if (state.recording) "停止并保存" else "60秒检测",
                    role = if (state.recording) EyeButtonRole.DANGER else EyeButtonRole.SECONDARY,
                    onClick = {
                        if (state.recording) {
                            viewModel.stopRecording()
                        } else {
                            viewModel.startRecording()
                        }
                    }
                )
            }
        }
    }
}

/** 概率数据行：无值显示 nan。 */
private fun probLine(label: String, prob: Float?): String =
    if (prob == null) {
        "prob $label: nan"
    } else {
        String.format(Locale.US, "prob %s: %.3f", label, prob)
    }
