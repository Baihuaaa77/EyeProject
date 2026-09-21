package com.zkyjss.eyeproject.core.camera

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import dagger.hilt.android.qualifiers.ApplicationContext
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

    /** 前置相机焦距（像素），按当前分析图宽换算。 */
    fun focalLengthPx(analysisWidth: Int): Float {
        if (analysisWidth <= 0) return 0f
        return try {
            val focalMm = frontFocalMm() ?: return 0f
            val sensorWidthMm = frontSensorWidthMm() ?: return 0f
            if (sensorWidthMm <= 0f) return 0f
            focalMm * analysisWidth / sensorWidthMm
        } catch (_: Exception) {
            0f
        }
    }

    private fun frontFocalMm(): Float? {
        return try {
            val id = frontCameraId() ?: return null
            val characteristics = manager.getCameraCharacteristics(id)
            val focalLengths = characteristics.get(
                CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS
            ) ?: return null
            if (focalLengths.isEmpty()) null else focalLengths[0]
        } catch (_: Exception) {
            null
        }
    }

    private fun frontSensorWidthMm(): Float? {
        return try {
            val id = frontCameraId() ?: return null
            val characteristics = manager.getCameraCharacteristics(id)
            characteristics.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)?.width
        } catch (_: Exception) {
            null
        }
    }

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
