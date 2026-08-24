package com.zkyjss.eyeproject.feature.testmodel

/**
 * JNI 桥：对应 cpp/jni_bridge.cpp 里的
 * Java_com_zkyjss_eyeproject_feature_testmodel_GazeNative_run。
 */
object GazeNative {

    init {
        System.loadLibrary("gaze_native")
    }

    /**
     * 运行 L2CS-Net 注视估计。
     * @param pixels RGB 字节数组（width*height*3，RGB 顺序，值 0..255）
     * @return FloatArray[2] = [yaw, pitch]（度）
     */
    external fun run(pixels: ByteArray, width: Int, height: Int, modelPath: String, shadersDir: String): FloatArray
}
