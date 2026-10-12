package com.zkyjss.eyeproject.core.camera

import android.content.Context
import android.content.pm.ApplicationInfo
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 从 Camera2 特性读取前置相机的焦距，用于 Depth-from-Iris 测距。
 *
 * 焦距（像素） = 物理焦距(mm) × 分析图宽(px) / 传感器物理宽(mm)
 * （假设分析图为传感器全视野等比缩放；若 CameraX 发生裁切会引入近似误差）
 */
@Singleton
class CameraIntrinsicsProvider @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val manager =
        context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val debugInput = File(context.filesDir, "camera_debug_input.jpg")
    private val debugFocalScale = File(context.filesDir, "camera_debug_focal_scale.txt")

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

    /** 旋转矫正后的分析图几何；CameraX 的额外裁切尚未由相机厂商保证。 */
    fun geometry(imageWidth: Int, imageHeight: Int): Geometry? {
        if (imageWidth <= 0 || imageHeight <= 0) return null
        return try {
            val id = frontCameraId() ?: return null
            val characteristics = manager.getCameraCharacteristics(id)
            val focal = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull() ?: return null
            val sensor = characteristics.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE) ?: return null
            if (focal <= 0f || sensor.width <= 0f || sensor.height <= 0f) return null
            // 竖屏图像的长边来自传感器横向；旧版按竖屏图像宽边计算会低估焦距和距离。
            val virtualFocalPx = focal * maxOf(imageWidth, imageHeight) / sensor.width
            val usingHostCamera = usesHostCamera()
            val scale = if (usingHostCamera) readDebugFocalScale() else 1f
            val focalPx = virtualFocalPx * scale
            Geometry(id, imageWidth, imageHeight, focal, sensor.width, sensor.height,
                focalPx, imageWidth / 2f, imageHeight / 2f,
                if (usingHostCamera) "电脑摄像头画面；虚拟焦距乘以人工标定系数 $scale" else
                    "Camera2 物理焦距和传感器尺寸；主点取图像中心；CameraX 裁切待实物校准")
        } catch (_: Exception) { null }
    }

    fun focalLengthPx(imageWidth: Int, imageHeight: Int): Float =
        geometry(imageWidth, imageHeight)?.focalLengthPx ?: 0f

    /** 供缓存测距参数的页面在电脑摄像头重新标定后刷新。 */
    fun calibrationVersion(): Long = if (usesHostCamera()) debugFocalScale.lastModified() else 0L

    private fun usesHostCamera(): Boolean {
        val emulator = Build.HARDWARE == "ranchu" || Build.HARDWARE == "goldfish"
        val debuggable = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        return emulator && debuggable && debugInput.exists() &&
            System.currentTimeMillis() - debugInput.lastModified() <= 2_000L
    }

    private fun readDebugFocalScale(): Float = runCatching {
        debugFocalScale.readText().trim().toFloat().takeIf { it.isFinite() && it in 0.2f..5f }
    }.getOrNull() ?: 1f

    private fun frontCameraId(): String? {
        return try {
            manager.cameraIdList.firstOrNull { id ->
                manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) ==
                    CameraCharacteristics.LENS_FACING_FRONT
            }
        } catch (_: Exception) {
            null
        }
    }
}
