package com.zkyjss.eyeproject.core.camera

import android.content.Context
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.zkyjss.eyeproject.core.model.FaceLandmarkFrame
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * 前置摄像头「无预览」分析控制器：仅绑定 ImageAnalysis + FaceLandmarker，
 * 把逐帧 [FaceLandmarkFrame] 通过 SharedFlow 对外暴露。供前台服务/监测引擎后台使用。
 */
@Singleton
class CameraController @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private var cameraProvider: ProcessCameraProvider? = null
    private var analyzer: FaceLandmarkerAnalyzer? = null
    private var analysisExecutor: ExecutorService? = null

    private val _frameFlow = MutableSharedFlow<FaceLandmarkFrame>(extraBufferCapacity = 1)
    val frameFlow: SharedFlow<FaceLandmarkFrame> = _frameFlow.asSharedFlow()

    @Volatile
    var isRunning: Boolean = false
        private set

    fun start(
        lifecycleOwner: LifecycleOwner,
        onError: (Throwable) -> Unit
    ) {
        stop()

        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener(
            {
                try {
                    val provider = providerFuture.get()
                    val executor = Executors.newSingleThreadExecutor()
                    val eyeAnalyzer = FaceLandmarkerAnalyzer(
                        context = context,
                        onResult = { frame -> _frameFlow.tryEmit(frame) },
                        onError = onError
                    )
                    val imageAnalysis = ImageAnalysis.Builder()
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST) // 只保留最新帧，旧的没处理完直接丢弃
                        .build()
                        .also { it.setAnalyzer(executor, eyeAnalyzer) }

                    provider.unbindAll()
                    provider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_FRONT_CAMERA,
                        imageAnalysis
                    )

                    analyzer = eyeAnalyzer
                    analysisExecutor = executor
                    cameraProvider = provider
                    isRunning = true
                } catch (throwable: Throwable) {
                    isRunning = false
                    ContextCompat.getMainExecutor(context).execute {
                        onError(throwable)
                    }
                }
            },
            ContextCompat.getMainExecutor(context)
        )
    }

    fun stop() {
        cameraProvider?.unbindAll()
        cameraProvider = null
        analyzer?.close()
        analyzer = null
        analysisExecutor?.shutdown()
        analysisExecutor = null
        isRunning = false
    }
}
