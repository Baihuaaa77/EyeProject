@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package com.zkyjss.eyeproject.core.domain

import platform.Foundation.NSRecursiveLock

internal actual class DomainLock actual constructor() {
    private val lock = NSRecursiveLock()
    actual fun <T> withLock(block: () -> T): T {
        lock.lock()
        return try { block() } finally { lock.unlock() }
    }
}
