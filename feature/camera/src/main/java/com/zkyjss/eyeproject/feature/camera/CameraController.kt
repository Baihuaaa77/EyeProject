package com.zkyjss.eyeproject.feature.camera

import android.content.Context
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.zkyjss.eyeproject.core.model.AnalysisResolution
import com.zkyjss.eyeproject.core.model.FrameResult
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** CameraX 前置摄像头控制器，把 MediaPipe 分析结果通过 SharedFlow 对外暴露。 */
@Singleton
class CameraController @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private var cameraProvider: ProcessCameraProvider? = null
    private var analyzer: FaceLandmarkerAnalyzer? = null
    private var analysisExecutor: ExecutorService? = null

    private val _resultFlow = MutableSharedFlow<FrameResult>(extraBufferCapacity = 1)
    val resultFlow: SharedFlow<FrameResult> = _resultFlow.asSharedFlow()

    @Volatile
    var isRunning: Boolean = false
        private set

    fun start(
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView,
        resolution: AnalysisResolution,
        onError: (Throwable) -> Unit
    ) {
        stop()

        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener(
            {
                try {
                    val provider = providerFuture.get()
                    val preview = Preview.Builder().build().also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }
                    val executor = Executors.newSingleThreadExecutor()
                    val eyeAnalyzer = FaceLandmarkerAnalyzer(
                        context = context,
                        onResult = { result -> _resultFlow.tryEmit(result) },
                        onError = onError
                    )
                    val imageAnalysis = ImageAnalysis.Builder()
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                        .setTargetResolution(Size(resolution.width, resolution.height))
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                        .also { it.setAnalyzer(executor, eyeAnalyzer) }

                    provider.unbindAll()
                    provider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_FRONT_CAMERA,
                        preview,
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
