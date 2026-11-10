package com.zkyjss.eyeproject.feature.services

import com.zkyjss.eyeproject.core.domain.*
import com.zkyjss.eyeproject.core.model.*
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.random.Random
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*

/** 冻结结果快照并在应用级作用域完成持久化。 */
internal class ScreeningResultWriter(private val host: ScreeningEngine) {
    fun finish() {
        with(host) {
            timer?.cancel()
            val s = _state.value
            val assessment = if (s.kind == ScreeningKind.PRESBYOPIA) ScreeningAssessmentModel.presbyopia(s)
            else ScreeningAssessmentModel.strabismus(s, samples, qualityEligibleFrames, qualityEligibleValidFrames)
            val metrics = if (s.kind == ScreeningKind.PRESBYOPIA) PresbyopiaMetrics.summarize(s.answers, s.nearPoints, s.curvePoints, s.nearResolutionLimitedEyes, s.flipperIncomplete)
            else StrabismusMetrics.summarize(samples, s.coverMarks, totalFrames, validFrames,
                qualityEligibleFrames, qualityEligibleValidFrames, s.pursuitMaxDeg)
            val result = assessment.displayLines() + metrics
            val capturedSamples = samples.toList()
            val capturedCalibration = gazeCalibration.toList()
            val capturedTotal = totalFrames
            val capturedValid = validFrames
            val capturedQualityEligible = qualityEligibleFrames
            val capturedQualityValid = qualityEligibleValidFrames
            // 下面在后台线程写记录：相机停下前排队的帧仍会在主线程往这些列表里追加，必须先拷贝，
            // 否则遍历时被修改会抛异常、整条记录保存失败
            val capturedContrasts = opticalContrasts.map(Float::toDouble)
            val capturedIrisPx = opticalIrisPx.map(Float::toDouble)
            val capturedIrisReferenceFrames = opticalIrisReferenceFrames
            _state.update { it.copy(step = ScreeningStep.RESULT, result = result,
                riskLevel = assessment.riskLevel, riskScope = assessment.riskScope, secondsLeft = 0,
                category = assessment.category, findings = assessment.findings, limitations = assessment.limitations,
                features = assessment.features, evidence = assessment.evidence) }
            val resultState = _state.value
            stopCamera()
            recordScope.launch(recordDispatcher) {
                runCatching {
                    val data = RecordObject().apply {
                        put("protocolVersion", if (s.kind == ScreeningKind.STRABISMUS) 7 else 4)
                        if (s.kind == ScreeningKind.STRABISMUS) {
                            put("gazeCalibrated", s.gazeCalibrated)
                            put("gazeCalibration", RecordArray().apply { capturedCalibration.forEach { c -> put(RecordObject().apply {
                                put("index", c.index); put("targetX", c.target.x.toDouble()); put("targetY", c.target.y.toDouble())
                                put("leftX", c.left?.x?.toDouble() ?: RecordObject.NULL); put("leftY", c.left?.y?.toDouble() ?: RecordObject.NULL)
                                put("rightX", c.right?.x?.toDouble() ?: RecordObject.NULL); put("rightY", c.right?.y?.toDouble() ?: RecordObject.NULL)
                            }) } })
                        }
                        put("kind", s.kind.name)
                        put("assessment", RecordObject().apply {
                            put("modelVersion", assessment.version)
                            put("quality", assessment.quality)
                            put("riskLevel", assessment.riskLevel.name)
                            put("riskScope", assessment.riskScope)
                            put("category", assessment.category)
                            put("features", RecordObject().apply { assessment.features.forEach { (key, value) ->
                                put(key, value?.takeIf(Double::isFinite) ?: RecordObject.NULL)
                            } })
                            put("findings", RecordArray(assessment.findings))
                            put("limitations", RecordArray(assessment.limitations))
                            put("evidence", assessment.evidence.toRecordArray())
                        })
                        put("mode", s.mode)
                        s.presbyopiaPath?.let { put("presbyopiaPath", it.code) }
                        put("glassesConfirmed", s.glassesConfirmed)
                        put("flipperIncomplete", s.flipperIncomplete ?: RecordObject.NULL)
                        put("profile", RecordObject(s.fields))
                        s.subject?.let { put("subject", it.toRecordObject()) }
                        put("profileSkipped", s.profileSkipped)
                        put("ambientLux", s.lux?.toDouble() ?: RecordObject.NULL)
                        put("lightSensorAvailable", s.lightSensorAvailable ?: RecordObject.NULL)
                        put("cameraBrightness", s.cameraBrightness?.toDouble() ?: RecordObject.NULL)
                        put("cameraFrames", s.cameraFrames)
                        put("opticalValidFrames", s.opticalValidFrames)
                        put("opticalIrisReferenceFrames", capturedIrisReferenceFrames)
                        put("opticalRejectionCounts", RecordObject(s.opticalRejectionCounts))
                        // 排查用：瞳孔对比度门槛为 12（灰度级），虹膜直径不足 12 像素时直接拒绝。
                        put("opticalPupilContrastMedian", ScreeningAssessmentModel.median(capturedContrasts) ?: RecordObject.NULL)
                        put("opticalIrisDiameterMedianPx", ScreeningAssessmentModel.median(capturedIrisPx) ?: RecordObject.NULL)
                        put("nearResolutionLimitedEyes", RecordArray(s.nearResolutionLimitedEyes.map { it.name }))
                        put("invalidTrialCount", s.invalidTrialCount)
                        put("lastDistanceCm", s.distanceCm.toDouble())
                        val display = platform.display
                        put("displayWidthPx", display.widthPixels)
                        put("displayHeightPx", display.heightPixels)
                        put("displayXdpi", display.xdpi.toDouble())
                        put("displayYdpi", display.ydpi.toDouble())
                        put("platform", platform.platformName)
                        put("distanceSource", platform.distanceSource)
                        put("displaySizeSource", platform.displaySizeSource)
                        put("totalFrames", capturedTotal)
                        put("validFrames", capturedValid)
                        put("qualityEligibleFrames", capturedQualityEligible)
                        put("qualityEligibleValidFrames", capturedQualityValid)
                        put("answers", RecordArray().apply { s.answers.forEach { a -> put(RecordObject().apply {
                            put("step", a.step.name); put("distanceCm", a.distanceCm.toDouble()); put("level", a.level.toDouble())
                            put("expectedDirection", a.expectedDirection); put("selectedDirection", a.selectedDirection)
                            put("correct", a.correct); put("latencyMs", a.latencyMs)
                            put("optotypePx", a.optotypePx.toDouble())
                            put("eye", a.eye.name); put("presentedAtElapsedRealtimeMs", a.presentedAtMs)
                            put("movementMs", a.movementMs ?: RecordObject.NULL)
                            put("clarityMs", a.clarityMs ?: RecordObject.NULL)
                            put("distanceSpreadCm", a.distanceSpreadCm.toDouble())
                            put("answerDistanceCm", a.answerDistanceCm.toDouble()); put("byVoice", a.byVoice)
                        }) } })
                        put("nearPoints", RecordArray().apply { s.nearPoints.forEach { p -> put(RecordObject().apply {
                            put("distanceCm", p.distanceCm.toDouble())
                            put("speedCmPerSec", p.speedCmPerSec?.toDouble() ?: RecordObject.NULL)
                            put("startCm", p.startCm?.toDouble() ?: RecordObject.NULL)
                            put("censored", p.censored); put("eye", p.eye.name)
                            put("trace", RecordArray(p.trace.map { (t, d) -> RecordArray(listOf(t.toDouble(), d.toDouble())) }))
                        }) } })
                        put("curvePoints", RecordArray().apply { s.curvePoints.forEach { c -> put(RecordObject().apply {
                            put("distanceCm", c.distanceCm.toDouble())
                            put("thresholdDecimal", c.thresholdDecimal?.toDouble() ?: RecordObject.NULL)
                            put("resolutionLimited", c.resolutionLimited)
                        }) } })
                        put("pursuitMaxDeg", s.pursuitMaxDeg?.let { RecordArray(listOf(it.first.toDouble(), it.second.toDouble())) } ?: RecordObject.NULL)
                        put("coverMarks", RecordArray().apply { s.coverMarks.forEach { mark -> put(RecordObject().apply {
                            put("label", mark.label); put("elapsedRealtimeMs", mark.timeMs)
                            put("source", mark.source); put("confirmedAtMs", mark.confirmedAtMs)
                            put("uncovered", mark.uncovered?.name ?: RecordObject.NULL); put("covered", mark.covered?.name ?: RecordObject.NULL)
                        }) } })
                        put("eyeSamples", RecordArray().apply { capturedSamples.forEach { sample -> put(RecordObject().apply {
                            put("elapsedRealtimeMs", sample.timeMs); put("step", sample.step.name)
                            put("leftX", sample.leftX.takeIf(Float::isFinite)?.toDouble() ?: RecordObject.NULL)
                            put("leftY", sample.leftY.takeIf(Float::isFinite)?.toDouble() ?: RecordObject.NULL)
                            put("rightX", sample.rightX.takeIf(Float::isFinite)?.toDouble() ?: RecordObject.NULL)
                            put("rightY", sample.rightY.takeIf(Float::isFinite)?.toDouble() ?: RecordObject.NULL)
                            put("distanceCm", sample.distanceCm.toDouble())
                            put("targetX", sample.targetX.toDouble()); put("targetY", sample.targetY.toDouble())
                            put("targetScreenX", sample.targetScreenX?.toDouble() ?: RecordObject.NULL)
                            put("targetScreenY", sample.targetScreenY?.toDouble() ?: RecordObject.NULL)
                            put("leftGazeX", sample.leftGazeX?.toDouble() ?: RecordObject.NULL); put("leftGazeY", sample.leftGazeY?.toDouble() ?: RecordObject.NULL)
                            put("rightGazeX", sample.rightGazeX?.toDouble() ?: RecordObject.NULL); put("rightGazeY", sample.rightGazeY?.toDouble() ?: RecordObject.NULL)
                            put("gazeOnTarget", sample.gazeOnTarget ?: RecordObject.NULL)
                            put("leftOpticalX", sample.leftOpticalX?.toDouble() ?: RecordObject.NULL)
                            put("leftOpticalY", sample.leftOpticalY?.toDouble() ?: RecordObject.NULL)
                            put("rightOpticalX", sample.rightOpticalX?.toDouble() ?: RecordObject.NULL)
                            put("rightOpticalY", sample.rightOpticalY?.toDouble() ?: RecordObject.NULL)
                            put("headYawDeg", sample.headYawDeg?.toDouble() ?: RecordObject.NULL)
                            put("headPitchDeg", sample.headPitchDeg?.toDouble() ?: RecordObject.NULL)
                            put("headRollDeg", sample.headRollDeg?.toDouble() ?: RecordObject.NULL)
                            put("leftPupilX", sample.leftPupilX?.toDouble() ?: RecordObject.NULL)
                            put("leftPupilY", sample.leftPupilY?.toDouble() ?: RecordObject.NULL)
                            put("leftReflexX", sample.leftReflexX?.toDouble() ?: RecordObject.NULL)
                            put("leftReflexY", sample.leftReflexY?.toDouble() ?: RecordObject.NULL)
                            put("rightPupilX", sample.rightPupilX?.toDouble() ?: RecordObject.NULL)
                            put("rightPupilY", sample.rightPupilY?.toDouble() ?: RecordObject.NULL)
                            put("rightReflexX", sample.rightReflexX?.toDouble() ?: RecordObject.NULL)
                            put("rightReflexY", sample.rightReflexY?.toDouble() ?: RecordObject.NULL)
                            put("leftNasal", sample.leftNasal?.toDouble() ?: RecordObject.NULL)
                            put("rightNasal", sample.rightNasal?.toDouble() ?: RecordObject.NULL)
                            put("leftVertical", sample.leftVertical?.toDouble() ?: RecordObject.NULL)
                            put("rightVertical", sample.rightVertical?.toDouble() ?: RecordObject.NULL)
                            put("pursuitSegment", sample.pursuitSegment)
                        }) } })
                        put("summary", RecordArray(result))
                    }
                    val history = platform.compareHistory(s, assessment)
                    _state.update { if (it.step == ScreeningStep.RESULT && it.result === resultState.result) it.copy(history = history) else it }
                    ResultReports.screening(resultState.copy(history = history))?.let { data.put("report", it.toRecordObject()) }
                    val name = "${s.kind.name.lowercase()}_${platform.epochMs()}.json"
                    val json = data.toString()
                    if (_state.value.result === resultState.result) lastRecordJson = json
                    platform.saveRecord(name, json)
                    name
                }.onSuccess { name -> _state.update { if (it.step == ScreeningStep.RESULT && it.result === resultState.result) it.copy(savedFile = name) else it } }
                    .onFailure { error -> _state.update { if (it.step == ScreeningStep.RESULT && it.result === resultState.result) it.copy(saveError = saveFailureText(error)) else it } }
            }
        }
    }
}
