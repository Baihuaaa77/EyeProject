package com.zkyjss.eyeproject.feature.testmodel

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zkyjss.eyeproject.core.ui.component.EyeButton
import com.zkyjss.eyeproject.core.ui.component.EyeButtonRole
import com.zkyjss.eyeproject.core.ui.component.EyeMetricCard
import com.zkyjss.eyeproject.core.ui.component.EyeSectionCard
import com.zkyjss.eyeproject.core.ui.component.EyeText
import com.zkyjss.eyeproject.core.designsystem.theme.EyeShapes
import com.zkyjss.eyeproject.core.designsystem.theme.LocalEyeColors
import com.zkyjss.eyeproject.core.designsystem.theme.LocalEyeTypography
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
            .background(colors.appBackground)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            EyeButton(
                text = "← 返回",
                role = EyeButtonRole.SECONDARY,
                onClick = onBack
            )
            Spacer(Modifier.padding(8.dp))
            EyeText(
                text = "模型测试（L2CS-Net 注视估计）",
                style = typography.title,
                color = colors.textPrimary
            )
        }

        // 预览
        EyeSectionCard(title = "摄像头预览（请对准人脸）") {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(4f / 3f)
                    .clip(EyeShapes.card)
                    .background(colors.cameraSurface)
            ) {
                AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
            }
        }

        // 错误 / 模型缺失提示
        if (state.modelError != null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(EyeShapes.medium)
                    .background(colors.warningSoft)
                    .padding(12.dp)
            ) {
                EyeText(text = state.modelError!!, style = typography.body, color = colors.warning)
            }
        }

        // 模型信息
        EyeSectionCard(title = "模型信息") {
            val info = state.modelInfo
            if (info == null) {
                EyeText(text = "尚未加载模型", style = typography.body, color = colors.textSecondary)
            } else {
                EyeText(
                    text = "名称：${info.name}",
                    style = typography.body,
                    color = colors.textPrimary
                )
                EyeText(
                    text = "输入形状：[${info.inputShape.joinToString(", ")}]",
                    style = typography.body,
                    color = colors.textPrimary
                )
                info.outputShapes.forEachIndexed { index, shape ->
                    EyeText(
                        text = "输出[$index] 形状：[${shape.joinToString(", ")}]",
                        style = typography.body,
                        color = colors.textPrimary
                    )
                }
            }
        }

        // 推理结果
        EyeSectionCard(title = "推理结果") {
            val result = state.latestInference
            if (result == null) {
                EyeText(text = "等待推理…", style = typography.body, color = colors.textSecondary)
            } else {
                val gazeText = if (state.yawDeg != null && state.pitchDeg != null) {
                    String.format(Locale.US, "%.1f° / %.1f°", state.yawDeg, state.pitchDeg)
                } else {
                    "--"
                }
                Row(modifier = Modifier.fillMaxWidth()) {
                    EyeMetricCard(
                        label = "注视角度 yaw/pitch",
                        value = gazeText,
                        detail = "softmax 期望解码",
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.padding(5.dp))
                    EyeMetricCard(
                        label = "延迟",
                        value = "${result.latencyMs} ms",
                        detail = "单次推理耗时",
                        modifier = Modifier.weight(1f)
                    )
                }
                Spacer(Modifier.height(8.dp))
                EyeMetricCard(
                    label = "帧率",
                    value = String.format(Locale.US, "%.1f", state.fps),
                    detail = "推理帧率（每 3 帧一次）",
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                EyeText(
                    text = "原始输出：${result.outputs.map { arr -> "[${arr.take(6).joinToString(", ") { String.format(Locale.US, "%.2f", it) }}...]" }.joinToString("  ")}",
                    style = typography.bodySecondary,
                    color = colors.textSecondary,
                    fontWeight = FontWeight.Normal
                )
            }
        }
    }
}
