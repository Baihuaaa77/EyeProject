package com.zkyjss.eyeproject.feature.home

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.flow

/** 订阅期间对齐整分钟刷新日期和时段规则；恢复订阅立即读取本地时钟。 */
internal fun homeClockTicks(nowMillis: () -> Long = System::currentTimeMillis) = flow {
    while (currentCoroutineContext().isActive) {
        val now = nowMillis()
        emit(now)
        delay(60_000L - Math.floorMod(now, 60_000L))
    }
}
