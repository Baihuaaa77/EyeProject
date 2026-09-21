package com.zkyjss.eyeproject.feature.services

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.camera.view.PreviewView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberBottom
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberStart
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberColumnCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.compose.common.ProvideVicoTheme
import com.patrykandpatrick.vico.compose.m3.common.rememberM3VicoTheme
import com.patrykandpatrick.vico.core.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.core.cartesian.axis.VerticalAxis
import com.patrykandpatrick.vico.core.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.core.cartesian.data.CartesianValueFormatter
import com.patrykandpatrick.vico.core.cartesian.data.columnSeries
import com.patrykandpatrick.vico.core.cartesian.data.lineSeries
import com.zkyjss.eyeproject.core.designsystem.theme.LocalEyeColors
import com.zkyjss.eyeproject.core.designsystem.theme.LocalEyeTypography
import com.zkyjss.eyeproject.core.designsystem.util.screenHorizontalPaddingLarge
import com.zkyjss.eyeproject.core.ui.component.EyeSectionCard
import com.zkyjss.eyeproject.core.ui.component.EyeText
import com.zkyjss.eyeproject.core.ui.component.TopBackBar

/** 干眼检测页：阶段 1 连续眨眼 60s + 阶段 2 保持睁眼 20s，记录眨眼时间分布与距离。 */
@Composable
fun DryEyeScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: DryEyeTestViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current
    val previewView = remember {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }

    // 准备阶段启动摄像头：人眼检测 + 距离门禁 + 预览
    LaunchedEffect(state.phase) {
        if (state.phase == DryEyePhase.Setup) {
            viewModel.startMonitoring(lifecycleOwner, previewView)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(LocalEyeColors.current.appBackground)
            .screenHorizontalPaddingLarge()
    ) {
        TopBackBar(onBackClick = onBack)

        if (state.phase == DryEyePhase.Result) {
            // 结果页：无预览
            ResultContent(
                state = state,
                onRestart = viewModel::backToSetup,
            )
        } else {
            // 预览框：跨阶段常驻组合树（AndroidView 不销毁，surface 不重建）
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                PreviewBox(previewView = previewView)
            }
            Spacer(Modifier.height(12.dp))

            when (state.phase) {
                DryEyePhase.Setup -> SetupGate(
                    state = state,
                    onStart = viewModel::startTest,
                )
                DryEyePhase.Countdown -> CountdownContent(
                    instruction = state.instruction,
                    seconds = state.remainingSeconds,
                )
                DryEyePhase.Blink60, DryEyePhase.Open20 -> TestingContent(state = state)
                DryEyePhase.Result -> {}
            }
        }
    }
}

/** 主操作按钮：primary 底色（首页上半配色），不使用黄色。 */
@Composable
private fun PrimaryAction(
    text: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalEyeColors.current
    Box(
        modifier = modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.45f)
            .clip(RoundedCornerShape(14.dp))
            .background(colors.primary)
            .clickable(enabled = enabled) { onClick() }
            .padding(vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        EyeText(text = text, style = LocalEyeTypography.current.button, color = colors.textInverse)
    }
}

/** 准备页内容：说明 + 门禁 + 注意事项 + 开始按钮（预览框已在外层）。 */
@Composable
private fun SetupGate(
    state: DryEyeTestUiState,
    onStart: () -> Unit,
) {
    val colors = LocalEyeColors.current
    val typography = LocalEyeTypography.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        EyeSectionCard {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                EyeText(text = "干眼检测", style = typography.title, color = colors.textPrimary)
                EyeText(
                    text = "共两个阶段：第 1 阶段自然眨眼 60 秒，观察眨眼的时间分布；第 2 阶段保持睁眼 20 秒，尽量不眨眼。",
                    style = typography.body,
                    color = colors.textSecondary,
                )
            }
        }

        EyeSectionCard(title = "开始前检测") {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                GateRow(
                    label = "人眼检测",
                    value = if (state.faceDetected) "已检测到双眼" else "未检测到人眼，请正对屏幕",
                    ok = state.faceDetected,
                )
                GateRow(
                    label = "观测距离",
                    value = when {
                        state.distanceCm <= 0f -> "检测中…"
                        state.distanceCm < DryEyeTestViewModel.MIN_TEST_DISTANCE_CM ->
                            "${formatCm(state.distanceCm)}，未达到 ${DryEyeTestViewModel.MIN_TEST_DISTANCE_CM.toInt()}cm"
                        else -> "${formatCm(state.distanceCm)}，距离合适"
                    },
                    ok = state.faceDetected && state.distanceCm >= DryEyeTestViewModel.MIN_TEST_DISTANCE_CM,
                )
            }
        }

        EyeSectionCard(title = "注意事项") {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                EyeText(text = "· 保持正对屏幕，检测过程中不要移动手机", style = typography.body, color = colors.textSecondary)
                EyeText(text = "· 阶段 1 请自然眨眼，阶段 2 请尽量保持睁眼", style = typography.body, color = colors.textSecondary)
                EyeText(text = "· 本检测为健康参考，不构成医疗诊断", style = typography.body, color = colors.textSecondary)
            }
        }

        PrimaryAction(
            text = if (state.gateReady) "开始检测" else "满足条件后开始检测",
            enabled = state.gateReady,
            onClick = onStart,
        )

        if (state.error != null) {
            EyeText(text = "错误：${state.error}", style = typography.body, color = colors.danger)
        }
    }
}

/** 门禁状态行：label + 状态值（✓ 绿 / ✗ 红）。 */
@Composable
private fun GateRow(label: String, value: String, ok: Boolean) {
    val colors = LocalEyeColors.current
    val typography = LocalEyeTypography.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        EyeText(text = label, style = typography.body, color = colors.textPrimary)
        EyeText(
            text = if (ok) "✓ $value" else "✗ $value",
            style = typography.body,
            color = if (ok) colors.success else colors.danger,
        )
    }
}

@Composable
private fun CountdownContent(instruction: String, seconds: Int) {
    val colors = LocalEyeColors.current
    val typography = LocalEyeTypography.current
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(24.dp))
        EyeText(text = "$seconds", style = typography.headline, color = colors.primary)
        Spacer(Modifier.height(12.dp))
        EyeText(text = instruction, style = typography.subtitle, color = colors.textPrimary)
        Spacer(Modifier.height(8.dp))
        EyeText(text = "请保持正对屏幕", style = typography.body, color = colors.textSecondary)
    }
}

/** 检测中内容：进度 + 剩余 + 眨眼计数 + 指令（预览框已在外层）。 */
@Composable
private fun TestingContent(state: DryEyeTestUiState) {
    val colors = LocalEyeColors.current
    val typography = LocalEyeTypography.current

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(8.dp))
        LinearProgressIndicator(
            progress = { state.progressFraction },
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp)),
            color = colors.primary,
            trackColor = colors.neutralSoft,
        )
        Spacer(Modifier.height(20.dp))

        EyeText(
            text = "剩余 ${state.remainingSeconds} s",
            style = typography.headline,
            color = colors.primary,
        )
        Spacer(Modifier.height(12.dp))
        EyeText(
            text = "已记录眨眼 ${if (state.phase == DryEyePhase.Blink60) state.phase1BlinkCount else state.phase2BlinkCount} 次",
            style = typography.subtitle,
            color = colors.success,
        )
        Spacer(Modifier.height(8.dp))
        EyeText(text = state.instruction, style = typography.body, color = colors.textSecondary)
    }
}

/** 相机预览框：detection 页同款尺寸（0.7 宽、3:4、圆角）。 */
@Composable
private fun PreviewBox(previewView: PreviewView, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth(0.7f)
            .aspectRatio(3f / 4f)
            .clip(RoundedCornerShape(12.dp))
            .background(LocalEyeColors.current.cameraSurface),
    ) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
    }
}

@Composable
private fun ResultContent(
    state: DryEyeTestUiState,
    onRestart: () -> Unit,
) {
    val typography = LocalEyeTypography.current
    val colors = LocalEyeColors.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        EyeText(text = "检测结果", style = typography.title, color = colors.textPrimary)

        // ---- 阶段 1 ----
        EyeSectionCard(title = "阶段 1 · 连续眨眼 60 秒") {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                EyeText(
                    text = "眨眼总数：${state.phase1BlinkCount} 次",
                    style = typography.body,
                    color = colors.textPrimary,
                )
                EyeText(
                    text = "平均眨眼间隔：${avgGapText(state.phase1Blinks)}",
                    style = typography.body,
                    color = colors.textSecondary,
                )
            }
        }
        EyeSectionCard(title = "阶段 1 · 眨眼时间分布（每 5 秒）") {
            BlinkDistributionChart(
                blinks = state.phase1Blinks,
                phaseSeconds = DRY_EYE_PHASE1_SECONDS,
                bucketSeconds = 5,
            )
        }

        // ---- 阶段 2 ----
        EyeSectionCard(title = "阶段 2 · 保持睁眼 20 秒") {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                EyeText(
                    text = "眨眼次数：${state.phase2BlinkCount} 次${if (state.phase2BlinkCount == 0) "（未眨眼）" else ""}",
                    style = typography.body,
                    color = colors.textPrimary,
                )
                EyeText(
                    text = "保持睁眼期间的眨眼次数越少，泪膜维持能力越好",
                    style = typography.body,
                    color = colors.textSecondary,
                )
            }
        }
        EyeSectionCard(title = "阶段 2 · 眨眼时间分布（每 5 秒）") {
            BlinkDistributionChart(
                blinks = state.phase2Blinks,
                phaseSeconds = DRY_EYE_PHASE2_SECONDS,
                bucketSeconds = 5,
            )
        }

        // ---- 距离 ----
        EyeSectionCard(title = "观测距离变化") {
            DistanceChart(samples = state.distanceSamples)
        }

        PrimaryAction(text = "重新检测", enabled = true, onClick = onRestart)
    }
}

/** 眨眼时间分布：按桶计数柱状图。 */
@Composable
private fun BlinkDistributionChart(blinks: List<Float>, phaseSeconds: Int, bucketSeconds: Int) {
    val colors = LocalEyeColors.current
    val modelProducer = remember { CartesianChartModelProducer() }

    LaunchedEffect(blinks, phaseSeconds, bucketSeconds) {
        val bucketCount = (phaseSeconds + bucketSeconds - 1) / bucketSeconds
        val counts = IntArray(bucketCount)
        blinks.forEach { t ->
            val idx = (t / bucketSeconds).toInt().coerceIn(0, bucketCount - 1)
            counts[idx]++
        }
        modelProducer.runTransaction {
            columnSeries {
                series(
                    x = (0 until bucketCount).map { (it * bucketSeconds).toDouble() },
                    y = counts.map { it.toDouble() },
                )
            }
        }
    }

    ProvideVicoTheme(
        rememberM3VicoTheme(lineColor = colors.primary, textColor = colors.textSecondary)
    ) {
        CartesianChartHost(
            chart = rememberCartesianChart(
                rememberColumnCartesianLayer(),
                startAxis = VerticalAxis.rememberStart(
                    valueFormatter = CartesianValueFormatter { _, value, _ -> "${value.toInt()}次" }
                ),
                bottomAxis = HorizontalAxis.rememberBottom(
                    valueFormatter = CartesianValueFormatter { _, value, _ -> "${value.toInt()}s" }
                ),
                getXStep = { 5.0 },
            ),
            modelProducer = modelProducer,
            modifier = Modifier
                .fillMaxWidth()
                .height(160.dp),
        )
    }
}

/** 观测距离曲线（全程）。 */
@Composable
private fun DistanceChart(samples: List<DryEyeDistanceSample>) {
    if (samples.size < 2) return
    val colors = LocalEyeColors.current
    val modelProducer = remember { CartesianChartModelProducer() }

    LaunchedEffect(samples) {
        modelProducer.runTransaction {
            lineSeries {
                series(
                    x = samples.map { it.tSec.toDouble() },
                    y = samples.map { it.cm.toDouble() },
                )
            }
        }
    }

    ProvideVicoTheme(
        rememberM3VicoTheme(lineColor = colors.primary, textColor = colors.textSecondary)
    ) {
        CartesianChartHost(
            chart = rememberCartesianChart(
                rememberLineCartesianLayer(),
                startAxis = VerticalAxis.rememberStart(
                    valueFormatter = CartesianValueFormatter { _, value, _ -> "${value.toInt()}cm" }
                ),
                bottomAxis = HorizontalAxis.rememberBottom(
                    valueFormatter = CartesianValueFormatter { _, value, _ -> "${value.toInt()}s" }
                ),
            ),
            modelProducer = modelProducer,
            modifier = Modifier
                .fillMaxWidth()
                .height(160.dp),
        )
    }
}

private fun avgGapText(blinks: List<Float>): String {
    if (blinks.size < 2) return "--"
    val sorted = blinks.sorted()
    val gaps = sorted.zipWithNext { a, b -> b - a }
    return String.format(java.util.Locale.US, "%.1f 秒", gaps.average().toFloat())
}

private fun formatCm(cm: Float): String =
    if (cm > 0f) String.format(java.util.Locale.US, "%.0f cm", cm) else "--"

private const val DRY_EYE_PHASE1_SECONDS = 60
private const val DRY_EYE_PHASE2_SECONDS = 20
