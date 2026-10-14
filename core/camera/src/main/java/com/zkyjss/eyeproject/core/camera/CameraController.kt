package com.zkyjss.eyeproject.core.camera

import android.content.Context
import android.os.Build
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.UseCase
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.zkyjss.eyeproject.core.model.FaceLandmarkFrame
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.CopyOnWriteArrayList
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 前置摄像头分析控制器：**全项目唯一的 CameraX 绑定点**。
 *
 * 调用方只提供生命周期、预览容器（无预览时传 `null`）与业务回调，不再各自拼
 * `ProcessCameraProvider` / `Executor` / `Preview` / `ImageAnalysis` / `bindToLifecycle`。
 * 逐帧的人脸关键点经 [start] 的 `onFrame` 交给调用方，由调用方决定业务语义
 * （检测页画框、干眼页做门禁与测距），
 * 所以只收仪式、不收业务。
 *
 * **线程约束**：`onFrame` 在**分析线程**上调用，回调里只能写 `@Volatile` 字段或转投协程；
 * 不要在回调里直接更新 UI 状态（见项目「相机管线线程模式」约定）。
 * `onError` 与 `start`/`stop` 都在主线程。
 */
@Singleton
class CameraController @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private var cameraProvider: ProcessCameraProvider? = null
    private var analyzer: FaceLandmarkerAnalyzer? = null
    private var analysisExecutor: ExecutorService? = null
    private val frameObservers = CopyOnWriteArrayList<(FaceLandmarkFrame) -> Unit>()
    private val errorObservers = CopyOnWriteArrayList<(Throwable) -> Unit>()

    /**
     * 递增代号：`ProcessCameraProvider.getInstance` 是异步的，回调回来时如果期间又
     * [start] / [stop] 过（界面快速进出就是这样），这一代就作废，直接放弃绑定，
     * 免得把相机绑到一个已经销毁的生命周期上、并漏掉一个 Executor。
     */
    @Volatile
    private var generation = 0

    @Volatile
    var isRunning: Boolean = false
        private set

    /** 异步绑定期间也为 true，避免另一个页面重复抢占相机。 */
    @Volatile
    var isRequested: Boolean = false
        private set

    fun addFrameObserver(observer: (FaceLandmarkFrame) -> Unit) { frameObservers.addIfAbsent(observer) }
    fun removeFrameObserver(observer: (FaceLandmarkFrame) -> Unit) { frameObservers.remove(observer) }
    fun addErrorObserver(observer: (Throwable) -> Unit) { errorObservers.addIfAbsent(observer) }
    fun removeErrorObserver(observer: (Throwable) -> Unit) { errorObservers.remove(observer) }

    /**
     * 启动相机，重复调用（在上一代尚未就绪时也算）只有最后一次生效。
     *
     * @param previewView 传 `null` 表示只分析帧、不展示预览；否则把画面接到该容器
     * @param onFrame 每帧人脸关键点，**在分析线程回调**
     * @param onError 启动或分析失败，在主线程回调
     */
    fun start(
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView? = null,
        onFrame: (FaceLandmarkFrame) -> Unit,
        onError: (Throwable) -> Unit
    ): Int {
        val myGeneration = ++generation
        releaseCamera()
        isRequested = true

        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener(
            {
                if (myGeneration != generation) return@addListener
                var openingExecutor: ExecutorService? = null
                var openingAnalyzer: FaceLandmarkerAnalyzer? = null
                try {
                    val provider = providerFuture.get()
                    val executor = Executors.newSingleThreadExecutor().also { openingExecutor = it }
                    val eyeAnalyzer = FaceLandmarkerAnalyzer(
                        context = context,
                        onResult = { frame ->
                            runCatching { onFrame(frame) }
                            frameObservers.forEach { observer -> runCatching { observer(frame) } }
                        },
                        onError = { throwable ->
                            onError(throwable)
                            errorObservers.forEach { observer -> runCatching { observer(throwable) } }
                        }
                    ).also { openingAnalyzer = it }
                    val emulator = Build.HARDWARE == "ranchu" || Build.HARDWARE == "goldfish"
                    val analysisResolution = if (emulator) Size(640, 480) else Size(1280, 720)
                    val analysis = ImageAnalysis.Builder()
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST) // 只保留最新帧，旧的没处理完直接丢弃
                        .setResolutionSelector(ResolutionSelector.Builder()
                            .setResolutionStrategy(ResolutionStrategy(analysisResolution,
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                            .build())
                        .build()
                        .also { it.setAnalyzer(executor, eyeAnalyzer) }

                    provider.unbindAll()
                    provider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_FRONT_CAMERA,
                        *buildUseCases(previewView, analysis)
                    )

                    analyzer = eyeAnalyzer
                    analysisExecutor = executor
                    cameraProvider = provider
                    isRunning = true
                } catch (throwable: Throwable) {
                    openingAnalyzer?.close()
                    openingExecutor?.shutdown()
                    isRunning = false
                    isRequested = false
                    ContextCompat.getMainExecutor(context).execute {
                        onError(throwable)
                        errorObservers.forEach { observer -> runCatching { observer(throwable) } }
                    }
                }
            },
            ContextCompat.getMainExecutor(context)
        )
        return myGeneration
    }

    /** 仅启动该相机绑定的调用方可以停止它；后续调用方接管后旧句柄无效。 */
    fun stopIfGeneration(expectedGeneration: Int) {
        if (generation == expectedGeneration) stop()
    }

    fun stop() {
        generation++
        releaseCamera()
    }

    /** 预览可缺省：只有在需要把画面显示出来时才绑定 `Preview`。 */
    private fun buildUseCases(previewView: PreviewView?, analysis: ImageAnalysis): Array<UseCase> =
        buildList {
            previewView?.let { view ->
                add(
                    Preview.Builder()
                        .build()
                        .also { it.setSurfaceProvider(view.surfaceProvider) }
                )
            }
            add(analysis)
        }.toTypedArray()

    private fun releaseCamera() {
        cameraProvider?.unbindAll()
        cameraProvider = null
        analyzer?.close()
        analyzer = null
        analysisExecutor?.shutdown()
        analysisExecutor = null
        isRunning = false
        isRequested = false
    }
}
