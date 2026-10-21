package com.zkyjss.eyeproject.core.sync.client

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** 苹果端入口：Swift 无法直接构造协程作用域，由这里创建跑在主线程的客户端。 */
fun makeIosCloudClient(transport: HttpTransport, platform: CloudPlatform, onChange: () -> Unit): CloudClient =
    CloudClient(transport, platform, CoroutineScope(SupervisorJob() + Dispatchers.Main), onChange)
