package com.zkyjss.eyeproject.feature.services

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.zkyjss.eyeproject.core.designsystem.theme.*
import com.zkyjss.eyeproject.core.ui.component.*
import com.zkyjss.eyeproject.core.ui.component.EyeText as BaseEyeText
import kotlinx.coroutines.delay

/** 正式采集中，黑屏上的实心白点同时作为视标与映光光源；提示固定在底部，不挤动视标。 */
@Composable
internal fun StrabismusDarkSession(s: ScreeningState, vm: ScreeningTestViewModel, onBack: () -> Unit,
                                  onSettings: () -> Unit, onHelp: () -> Unit, distance: @Composable () -> Unit) {
    val colors = LocalEyeColors.current
    val typography = LocalEyeTypography.current
    var asking by rememberSaveable { mutableStateOf(false) }
    BackHandler { asking = true }
    var origin by remember { mutableStateOf(Offset.Zero) }
    val density = LocalDensity.current
    var footerHeight by remember { mutableStateOf(230.dp) }
    val ppm = LocalContext.current.resources.displayMetrics.xdpi / 25.4f
    var lightChip by remember { mutableIntStateOf(-1) }
    LaunchedEffect(s.step) {
        while (true) {
            lightChip = vm.engine.reflectionChip()
            delay(40L)
        }
    }
    Box(Modifier.fillMaxSize().background(colors.black).navigationBarsPadding()) {
        Canvas(Modifier.fillMaxSize().statusBarsPadding().padding(bottom = footerHeight)
            .onGloballyPositioned { origin = it.positionInWindow() }) {
            val diameter = StrabismusProtocol.targetDiameterMm(s.fields["年龄"]?.toIntOrNull()) * ppm
            val radius = (diameter / 2f).coerceAtMost(minOf(size.width, size.height) / 4f)
            val point = Offset(center.x, radius + 4.dp.toPx())
            val lightOn = vm.engine.reflectionLightOn(lightChip)
            if (lightOn) drawCircle(colors.white, radius, point)
            vm.engine.reportReflectionLight(lightOn)
            vm.reportGazeTarget(origin.x + point.x, origin.y + point.y)
        }
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().onSizeChanged { footerHeight = with(density) { it.height.toDp() } }.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            BaseEyeText(if (s.stabilityInterrupted) "本段已中断" else when (s.step) {
                ScreeningStep.COVER -> if (s.reflectionPreparing) s.instruction else if (s.coverHolding) "保持：${s.coverMarks.lastOrNull()?.label.orEmpty()}" else "下一步：${StrabismusProtocol.COVER_SEQUENCE.getOrNull(s.coverMarks.size)?.label.orEmpty()}"
                ScreeningStep.FAR_FIXATION -> "越过手机看6米外 · ${s.secondsLeft}秒"
                else -> "${s.secondsLeft}秒"
            }, style = typography.subtitle, color = colors.white)
            val action = StrabismusProtocol.COVER_SEQUENCE.getOrNull(s.coverMarks.size - 1)
            if (s.step == ScreeningStep.COVER && s.coverHolding && action != null && action.holdMs > 0) {
                HoldTimer(s.coverMarks.size, action.holdMs, "保持当前动作", vm.engine.coverRemainingMs(), dark = true)
            }
            if (s.stabilityInterrupted) {
                BaseEyeText(s.stabilityReason.orEmpty(), style = typography.body, color = colors.tooClose)
                EyeButton(if (s.stabilityReady) "已稳定，重新开始本段" else "请回到原位并保持稳定", EyeButtonRole.PRIMARY,
                    vm::restartStableStage, Modifier.fillMaxWidth(), enabled = s.stabilityReady)
                EyeButton("重新开始整个测试",EyeButtonRole.SECONDARY,vm::restart,Modifier.fillMaxWidth())
            } else if (s.farWaiting) {
                EyeButton("已在看远处，开始", EyeButtonRole.PRIMARY, vm::startFarCountdown, Modifier.fillMaxWidth())
            } else if (s.coverRetryAvailable) {
                EyeButton("已做好动作，继续", EyeButtonRole.PRIMARY, vm::markCover, Modifier.fillMaxWidth())
                EyeButton("重新识别本段", EyeButtonRole.SECONDARY, vm::retryCoverDetection, Modifier.fillMaxWidth())
            }
            distance()
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                EyeButton("退出", EyeButtonRole.SECONDARY, { asking = true })
                EyeButton("说明", EyeButtonRole.SECONDARY, onHelp)
                EyeButton("设置", EyeButtonRole.SECONDARY, onSettings)
            }
        }
        if (asking) TestExitDialog(diagnosticSaved = true, onStay = { asking = false }, onExit = { asking = false; onBack() })
    }
}

/** 九点标定使用完整可用屏幕，底部只留距离状态和退出操作，不再被顶栏与进度条压缩。 */
@Composable
internal fun StrabismusCalibrationSession(s: ScreeningState, vm: ScreeningTestViewModel, onBack: () -> Unit,
                                          onSettings: () -> Unit) {
    val colors = LocalEyeColors.current
    val typography = LocalEyeTypography.current
    var asking by rememberSaveable { mutableStateOf(false) }
    var origin by remember { mutableStateOf(Offset.Zero) }
    var footerHeight by remember { mutableStateOf(150.dp) }
    val density = LocalDensity.current
    BackHandler { asking = true }
    Box(Modifier.fillMaxSize().background(colors.black).navigationBarsPadding()) {
        Canvas(Modifier.fillMaxSize().statusBarsPadding().padding(bottom = footerHeight)
            .onGloballyPositioned { origin = it.positionInWindow() }) {
            val target = StrabismusGaze.calibrationTargets.getOrElse(s.gazeCalibrationIndex) { StrabismusGaze.calibrationTargets.first() }
            val inset = 10.dp.toPx().coerceAtMost(minOf(size.width, size.height) / 2f)
            val point = Offset((size.width * target.x).coerceIn(inset, size.width - inset),
                (size.height * target.y).coerceIn(inset, size.height - inset))
            val dim = if (s.gazeCalibrationPaused) 0.35f else 1f
            drawCircle(colors.primary.copy(alpha = 0.18f * dim), radius = 16.dp.toPx(), center = point)
            drawCircle(colors.primary.copy(alpha = dim), radius = 8.dp.toPx(), center = point)
            vm.reportGazeTarget(origin.x + point.x, origin.y + point.y)
        }
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()
            .onSizeChanged { footerHeight = with(density) { it.height.toDp() } }
            .padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (s.gazeCalibrationFailed) {
                BaseEyeText(s.warning.orEmpty(), style = typography.body, color = colors.tooClose)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    EyeButton("继续测试", EyeButtonRole.PRIMARY, vm::continueWithoutCalibration, Modifier.weight(1f))
                    EyeButton("重试校准", EyeButtonRole.SECONDARY, vm::retryGazeCalibration, Modifier.weight(1f))
                }
            }
            DistanceBanner(distanceStatus(s.distanceCm, s.faceDetected, StrabismusProtocol.DISTANCE_CM, StrabismusProtocol.DISTANCE_GATE_CM), compact = true, singleLine = true)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                EyeButton("退出", EyeButtonRole.SECONDARY, { asking = true })
                BaseEyeText("注视校准 · ${s.gazeCalibrationIndex + 1}/${StrabismusGaze.calibrationTargets.size}", style = typography.body, color = colors.white)
                EyeButton("设置", EyeButtonRole.SECONDARY, onSettings)
            }
        }
        if (asking) TestExitDialog(diagnosticSaved = true, onStay = { asking = false }, onExit = { asking = false; onBack() })
    }
}
