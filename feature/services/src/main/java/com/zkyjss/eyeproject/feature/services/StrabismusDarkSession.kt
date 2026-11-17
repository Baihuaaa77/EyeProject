package com.zkyjss.eyeproject.feature.services

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.animation.core.withInfiniteAnimationFrameNanos
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
import kotlin.math.atan
import kotlin.math.tan

/** 正式采集中，黑屏上的实心白点同时作为视标与映光光源；提示固定在底部，不挤动视标。 */
@Composable
internal fun StrabismusDarkSession(s: ScreeningState, vm: ScreeningTestViewModel, onBack: () -> Unit,
                                  onSettings: () -> Unit, onHelp: () -> Unit, distance: @Composable () -> Unit) {
    val colors = LocalEyeColors.current
    val typography = LocalEyeTypography.current
    var asking by rememberSaveable { mutableStateOf(false) }
    BackHandler { asking = true }
    var origin by remember { mutableStateOf(Offset.Zero) }
    var clock by remember { mutableLongStateOf(0L) }
    LaunchedEffect(s.step, s.stabilityInterrupted) {
        if (s.step == ScreeningStep.PURSUIT && !s.stabilityInterrupted) while (true) withInfiniteAnimationFrameNanos { clock = it }
    }
    val density = LocalDensity.current
    var footerHeight by remember { mutableStateOf(230.dp) }
    val ppm = LocalContext.current.resources.displayMetrics.xdpi / 25.4f
    Box(Modifier.fillMaxSize().background(colors.black).navigationBarsPadding()) {
        Canvas(Modifier.fillMaxSize().statusBarsPadding().padding(bottom = footerHeight)
            .onGloballyPositioned { origin = it.positionInWindow() }) {
            val diameter = (if (s.step == ScreeningStep.PURSUIT) 10f else StrabismusProtocol.targetDiameterMm(s.fields["年龄"]?.toIntOrNull())) * ppm
            val radius = (diameter / 2f).coerceAtMost(minOf(size.width, size.height) / 4f)
            val point = when (s.step) {
                ScreeningStep.GAZE_CALIBRATION -> StrabismusGaze.calibrationTargets[s.gazeCalibrationIndex].let { Offset((size.width*it.x).coerceIn(radius,(size.width-radius).coerceAtLeast(radius)),(size.height*it.y).coerceIn(radius,(size.height-radius).coerceAtLeast(radius))) }
                ScreeningStep.PURSUIT -> {
                    val distanceMm = s.distanceCm.coerceAtLeast(10f) * 10f
                    val xMax = Math.toDegrees(atan(((size.width / 2f - radius).coerceAtLeast(0f) / ppm / distanceMm).toDouble())).toFloat()
                    val yMax = Math.toDegrees(atan(((size.height / 2f - radius).coerceAtLeast(0f) / ppm / distanceMm).toDouble())).toFloat()
                    vm.reportPursuitCapacity(xMax, yMax)
                    val scale = minOf(1f, xMax / 10f, yMax / 10f)
                    @Suppress("UNUSED_VARIABLE") val renderFrame = clock
                    val target = vm.engine.pursuitTargetAt(android.os.SystemClock.elapsedRealtime())
                    Offset(center.x + (tan(Math.toRadians((target.x * scale).toDouble())) * distanceMm * ppm).toFloat(),
                        center.y - (tan(Math.toRadians((target.y * scale).toDouble())) * distanceMm * ppm).toFloat())
                }
                else -> Offset(center.x, radius + 4.dp.toPx())
            }
            drawCircle(colors.white, radius, point)
            vm.reportGazeTarget(origin.x + point.x, origin.y + point.y)
        }
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().onSizeChanged { footerHeight = with(density) { it.height.toDp() } }.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            BaseEyeText(if (s.stabilityInterrupted) "本段已中断" else when (s.step) {
                ScreeningStep.GAZE_CALIBRATION -> "注视白点 · ${s.gazeCalibrationIndex + 1}/9"
                ScreeningStep.COVER -> if (s.coverHolding) "保持：${s.coverMarks.lastOrNull()?.label.orEmpty()}" else "下一步：${StrabismusProtocol.COVER_SEQUENCE.getOrNull(s.coverMarks.size)?.label.orEmpty()}"
                ScreeningStep.FAR_FIXATION -> "越过手机看6米外 · ${s.secondsLeft}秒"
                else -> "${s.secondsLeft}秒"
            }, style = typography.subtitle, color = colors.white)
            if (s.stabilityInterrupted) {
                BaseEyeText(s.stabilityReason.orEmpty(), style = typography.body, color = colors.tooClose)
                EyeButton(if (s.stabilityReady) "已稳定，重新开始本段" else "请回到原位并保持稳定", EyeButtonRole.PRIMARY,
                    vm::restartStableStage, Modifier.fillMaxWidth(), enabled = s.stabilityReady)
                EyeButton("重新开始整个测试",EyeButtonRole.SECONDARY,vm::restart,Modifier.fillMaxWidth())
            } else if (s.farWaiting) {
                EyeButton("已在看远处，开始", EyeButtonRole.PRIMARY, vm::startFarCountdown, Modifier.fillMaxWidth())
            } else if (s.gazeCalibrationFailed) {
                EyeButton("重试注视校准", EyeButtonRole.PRIMARY, vm::retryGazeCalibration, Modifier.fillMaxWidth())
            } else if (s.coverRetryAvailable) {
                EyeButton("重新识别本段", EyeButtonRole.SECONDARY, vm::retryCoverDetection, Modifier.fillMaxWidth())
            }
            distance()
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                EyeButton("退出", EyeButtonRole.SECONDARY, { asking = true })
                EyeButton("说明", EyeButtonRole.SECONDARY, onHelp)
                EyeButton("设置", EyeButtonRole.SECONDARY, onSettings)
            }
        }
        if (asking) TestExitDialog(onStay = { asking = false }, onExit = { asking = false; onBack() })
    }
}
