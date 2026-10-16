package com.zkyjss.eyeproject.feature.services

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import androidx.lifecycle.LifecycleOwner
import com.zkyjss.eyeproject.core.camera.CameraController
import com.zkyjss.eyeproject.core.camera.CameraIntrinsicsProvider
import com.zkyjss.eyeproject.core.data.record.ScreeningRecordRepository
import com.zkyjss.eyeproject.core.model.FaceLandmarkFrame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 安卓系统适配，保留首页与测试页共用相机的所有权机制。 */
internal class AndroidScreeningPlatform(
    private val context: Context,
    private val camera: CameraController,
    private val intrinsics: CameraIntrinsicsProvider,
    private val records: ScreeningRecordRepository,
) : ScreeningPlatform, SensorEventListener {
    val feedbackSession = ScreeningFeedbackSession()
    override fun feedback(cue: ScreeningFeedbackCue) = feedbackSession.offer(cue)
    var owner: LifecycleOwner? = null
    private val sensors = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private var generation: Int? = null
    private var frameObserver: ((FaceLandmarkFrame) -> Unit)? = null
    private var errorObserver: ((Throwable) -> Unit)? = null
    private var lightObserver: ((Float) -> Unit)? = null
    override val display: DisplayGeometry get() = context.resources.displayMetrics.let {
        DisplayGeometry(it.widthPixels, it.heightPixels, it.xdpi, it.ydpi)
    }
    override fun nowMs() = SystemClock.elapsedRealtime()
    override fun epochMs() = System.currentTimeMillis()
    override fun cameraGeometry(width: Int, height: Int) = CameraGeometry(width, height,
        intrinsics.focalLengthPx(width, height), intrinsics.calibrationVersion())
    override fun startCamera(onFrame: (FaceLandmarkFrame) -> Unit, onError: (Throwable) -> Unit) {
        val lifecycleOwner = owner ?: run { onError(IllegalStateException("测试页尚未进入前台")); return }
        frameObserver = onFrame; errorObserver = onError
        camera.addFrameObserver(onFrame); camera.addErrorObserver(onError)
        if (!camera.isRequested) generation = camera.start(lifecycleOwner, previewView = null, onFrame = {}, onError = {})
    }
    override fun stopCamera() {
        frameObserver?.let(camera::removeFrameObserver)
        errorObserver?.let(camera::removeErrorObserver)
        generation?.let(camera::stopIfGeneration)
        frameObserver = null; errorObserver = null; generation = null
        sensors.unregisterListener(this); lightObserver = null
    }
    override fun startLight(onLux: (Float) -> Unit): Boolean {
        lightObserver = onLux
        val sensor = sensors.getDefaultSensor(Sensor.TYPE_LIGHT) ?: return false
        return sensors.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL)
    }
    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type == Sensor.TYPE_LIGHT) event.values.firstOrNull()?.let { lightObserver?.invoke(it) }
    }
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    /** 经仓库落盘（文件名与位置不变，「与上次对比」照旧读取）并按同意状态排队上传。 */
    override suspend fun saveRecord(name: String, json: String) = records.saveRecord(name, json)
    override suspend fun compareHistory(state: ScreeningState, assessment: ScreeningAssessment) =
        withContext(Dispatchers.IO) { ScreeningHistory.compare(context, state, assessment) }
}
