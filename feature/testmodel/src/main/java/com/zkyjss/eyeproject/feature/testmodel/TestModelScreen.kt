package com.zkyjss.eyeproject.feature.testmodel

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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zkyjss.eyeproject.core.designsystem.theme.LocalEyeColors
import com.zkyjss.eyeproject.core.designsystem.theme.LocalEyeTypography
import com.zkyjss.eyeproject.core.ui.component.EyeSwitch
import com.zkyjss.eyeproject.core.ui.component.EyeText
import com.zkyjss.eyeproject.core.ui.component.TopBackBar
import java.util.Locale

@Composable
fun TestModelScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: TestModelViewModel = hiltViewModel()
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

            // 视线箭头
            GazeOverlay(
                yawDeg = state.yawDeg,
                pitchDeg = state.pitchDeg,
                faceBox = state.faceBox,
                sourceWidth = state.sourceWidth,
                sourceHeight = state.sourceHeight,
                faceDetected = state.faceDetected,
                modifier = Modifier.fillMaxSize()
            )

            // 数据
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .background(Color(0x99000000), shape = RoundedCornerShape(corner = CornerSize(2.dp)))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Column(
                    modifier = Modifier
                ) {
                    // 模型名称
                    EyeText(text = state.modelInfo?.name ?: "nan", style = typography.subtitle, color = Color(0xFF33CC66))
                    // 切换实现
                    Row(
                        modifier = Modifier,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        EyeText(
                            text = "use_android_api",
                            style = typography.subtitle,
                            color = Color(0xFF33CC66)
                        )
                        EyeSwitch(
                            checked = state.useAndroidApi,
                            onCheckedChange = {
                                viewModel.changeInfer(!state.useAndroidApi)
                            },
                        )
                    }
                    // 偏航角
                    EyeText(
                        text = if (state.faceDetected && state.yawDeg != null) {
                            String.format(Locale.US, "yaw: %.1f°", state.yawDeg)
                        } else {
                            "yaw: nan"
                        },
                        style = typography.subtitle,
                        color = Color(0xFF33CC66)
                    )
                    // 俯仰角
                    EyeText(
                        text = if (state.faceDetected && state.pitchDeg != null) {
                            String.format(Locale.US, "pitch: %.1f°", state.pitchDeg)
                        } else {
                            "pitch: nan"
                        },
                        style = typography.subtitle,
                        color = Color(0xFF33CC66)
                    )
                    // 帧率
                    EyeText(
                        text = String.format(Locale.US, "%.1f fps", state.fps),
                        style = typography.subtitle,
                        color = Color(0xFF33CC66)
                    )
                    // 两阶段耗时：检测 + 推理
                    EyeText(
                        text = String.format(
                            Locale.US,
                            "det %d ms · infer %d ms",
                            state.detectionLatencyMillis,
                            state.latestInference?.latencyMs ?: 0
                        ),
                        style = typography.subtitle,
                        color = Color(0xFF33CC66)
                    )
                    // 报错信息
                    if (state.modelError != null) {
                        EyeText(text = state.modelError!!, style = typography.body, color = colors.warning)
                    }
                }
            }
        }
    }
}