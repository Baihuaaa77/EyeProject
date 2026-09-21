package com.zkyjss.eyeproject.feature.testmodel.blink

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

@Composable
fun BlinkTestScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: BlinkTestViewModel = hiltViewModel()
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
        // 返回栏
        TopBackBar(
            onBackClick = onBack
        )

        // 预览
        Box(
            modifier = Modifier
                .weight(1f)
                .background(colors.cameraSurface)
        ) {
            // 相机预览
            AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())

            // 眼部关键点覆盖层
            BlinkOverlay(
                faceDetected = state.faceDetected,
                faceBox = state.faceBox,
                leftEye = state.leftEye,
                rightEye = state.rightEye,
                eyeClosed = state.eyeClosed,
                sourceWidth = state.sourceWidth,
                sourceHeight = state.sourceHeight,
                modifier = Modifier.fillMaxSize()
            )

            // 数据
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .background(colors.overlayScrim, shape = RoundedCornerShape(corner = CornerSize(2.dp)))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Column {
                    // 模型名称
                    EyeText(text = "FaceLandmarker (478点)", style = typography.subtitle, color = colors.success)
                    // 双眼与平均 EAR
                    EyeText(text = earLine("L", state.earLeft), style = typography.subtitle, color = colors.success)
                    EyeText(text = earLine("R", state.earRight), style = typography.subtitle, color = colors.success)
                    EyeText(text = earLine("avg", state.earAverage), style = typography.subtitle, color = colors.success)
                    // 眼部状态与眨眼次数
                    EyeText(
                        text = when {
                            !state.faceDetected -> "state: nan"
                            state.eyeClosed -> "state: 闭眼"
                            else -> "state: 睁眼"
                        },
                        style = typography.subtitle,
                        color = if (state.eyeClosed) colors.warning else colors.success
                    )
                    EyeText(
                        text = "blink: ${state.blinkCount}",
                        style = typography.subtitle,
                        color = colors.success
                    )
                    // 帧率与耗时
                    EyeText(
                        text = String.format(Locale.US, "%.1f fps", state.fps),
                        style = typography.subtitle,
                        color = colors.success
                    )
                    EyeText(
                        text = String.format(Locale.US, "det %d ms", state.detectionLatencyMillis),
                        style = typography.subtitle,
                        color = colors.success
                    )
                    // 60s 检测录制进度与保存结果
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
                    // 报错信息
                    if (state.error != null) {
                        EyeText(text = state.error!!, style = typography.body, color = colors.warning)
                    }
                }
            }

            // 控制按钮：重置计数 + 60s 检测
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

/** EAR 数据行：无值时显示 nan（与 gaze 页一致）。 */
private fun earLine(label: String, ear: Float?): String =
    if (ear == null) {
        "ear $label: nan"
    } else {
        String.format(Locale.US, "ear %s: %.3f", label, ear)
    }
