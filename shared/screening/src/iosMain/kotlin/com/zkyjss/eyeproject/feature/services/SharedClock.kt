@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package com.zkyjss.eyeproject.feature.services

import platform.Foundation.NSProcessInfo
import platform.Foundation.NSDate
import platform.Foundation.timeIntervalSince1970

actual object SharedClock {
    actual fun nowMs() = (NSProcessInfo.processInfo.systemUptime * 1000).toLong()
    actual fun epochMs() = (NSDate().timeIntervalSince1970 * 1000).toLong()
}
