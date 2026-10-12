package com.zkyjss.eyeproject.core.camera

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.tan

/**
 * 前置相机焦距（像素），用于 Depth-from-Iris 测距：距离 = 虹膜直径(mm) × 焦距(px) / 虹膜像素直径。
 *
 * - 真机：焦距(px) = 物理焦距(mm) × 分析图长边(px) / 传感器物理宽(mm)（假设分析图为传感器全视野等比缩放；
 *   CameraX 若发生裁切会引入近似误差，需实物校准）。Camera2 参数只读取一次并缓存。
 * - Debug 模拟器 + 电脑摄像头输入：模拟器上报的是虚构的镜头参数，与电脑摄像头毫无关系，
 *   因此改按电脑摄像头的固定水平视场角估算：焦距(px) = (图像长边/2) / tan(HFOV/2)。
 *   16:9 画面取笔记本摄像头常见的 65°；4:3 画面通常是从 16:9 传感器左右裁切得到，视场角约 51°。
 *   这是不需标定的固定估计，只用于模拟器联调，真机不走这条路径。
 */
@Singleton
class CameraIntrinsicsProvider @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    /** 前摄 Camera2 参数只需读取一次（以前每帧都跨进程查询）。 */
    private val lens: Lens? by lazy { readFrontLens() }

    private data class Lens(val cameraId: String, val focalMm: Float, val sensorWidthMm: Float, val sensorHeightMm: Float)

    data class Geometry(
        val cameraId: String,
        val imageWidth: Int,
        val imageHeight: Int,
        val focalLengthMm: Float,
        val sensorWidthMm: Float,
        val sensorHeightMm: Float,
        val focalLengthPx: Float,
        val principalX: Float,
        val principalY: Float,
        val approximation: String,
    )

    /** 旋转矫正后的分析图几何。 */
    fun geometry(imageWidth: Int, imageHeight: Int): Geometry? {
        if (imageWidth <= 0 || imageHeight <= 0) return null
        val longSide = maxOf(imageWidth, imageHeight)
        if (usesHostCamera()) {
            val hfov = hostHfovDeg(imageWidth, imageHeight)
            val focalPx = (longSide / 2f) / tan(Math.toRadians(hfov / 2.0)).toFloat()
            return Geometry("host", imageWidth, imageHeight, 0f, 0f, 0f, focalPx,
                imageWidth / 2f, imageHeight / 2f,
                "电脑摄像头画面；按固定水平视场角 ${hfov}° 估算焦距（未标定）")
        }
        val l = lens ?: return null
        // 竖屏图像的长边来自传感器横向；按短边计算会低估焦距和距离。
        val focalPx = l.focalMm * longSide / l.sensorWidthMm
        return Geometry(l.cameraId, imageWidth, imageHeight, l.focalMm, l.sensorWidthMm, l.sensorHeightMm,
            focalPx, imageWidth / 2f, imageHeight / 2f,
            "Camera2 物理焦距和传感器尺寸；主点取图像中心；CameraX 裁切待实物校准")
    }

    fun focalLengthPx(imageWidth: Int, imageHeight: Int): Float =
        geometry(imageWidth, imageHeight)?.focalLengthPx ?: 0f

    /** 供缓存测距参数的页面在输入源（模拟器相机 / 电脑摄像头）切换后刷新。 */
    fun calibrationVersion(): Long = if (usesHostCamera()) 1L else 0L

    private fun usesHostCamera(): Boolean = DebugCameraFeed.supported(context) && DebugCameraFeed.active()

    /** 4:3 画面视为 16:9 传感器左右裁切：tan(HFOV/2) 按 3/4 缩小。 */
    private fun hostHfovDeg(width: Int, height: Int): Double {
        val aspect = maxOf(width, height).toDouble() / minOf(width, height)
        return if (aspect < 1.5) HOST_HFOV_4_3_DEG else HOST_HFOV_DEG
    }

    private fun readFrontLens(): Lens? = try {
        manager.cameraIdList.firstNotNullOfOrNull { id ->
            val c = manager.getCameraCharacteristics(id)
            if (c.get(CameraCharacteristics.LENS_FACING) != CameraCharacteristics.LENS_FACING_FRONT) return@firstNotNullOfOrNull null
            val focal = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull() ?: return@firstNotNullOfOrNull null
            val sensor = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE) ?: return@firstNotNullOfOrNull null
            if (focal <= 0f || sensor.width <= 0f || sensor.height <= 0f) null
            else Lens(id, focal, sensor.width, sensor.height)
        }
    } catch (_: Exception) {
        null
    }

    companion object {
        /** 电脑摄像头 16:9 画面的水平视场角（度）。笔记本内置摄像头多在 60–70°。 */
        const val HOST_HFOV_DEG = 65.0
        /** 4:3 画面（从 16:9 传感器裁切）对应的水平视场角：2·atan(0.75·tan(32.5°)) ≈ 51°。 */
        const val HOST_HFOV_4_3_DEG = 51.1
    }
}
