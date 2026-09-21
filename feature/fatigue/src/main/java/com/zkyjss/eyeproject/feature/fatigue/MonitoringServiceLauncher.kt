package com.zkyjss.eyeproject.feature.fatigue

/**
 * 启动/停止后台监测前台服务的抽象。由 app 层实现（feature 层不能反向依赖 app）。
 */
interface MonitoringServiceLauncher {
    fun start()
    fun stop()
}
