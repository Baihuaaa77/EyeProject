package com.zkyjss.eyeproject.feature.services

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** 应用进程内的记录写入作用域；离开测试页只停止采集，不取消已经生成的结果落盘。 */
internal object ScreeningPersistence {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
}
