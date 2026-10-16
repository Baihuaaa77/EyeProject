package com.zkyjss.eyeproject.core.network

import com.zkyjss.eyeproject.core.sync.LoginRequest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpEyeNetworkDataSourceTest {
    @Test
    fun unconfiguredSourceNeverLeavesDevice() = runBlocking {
        val source = HttpEyeNetworkDataSource("")
        assertFalse(source.isConfigured)
        assertEquals(NetworkResult.Unreachable("未配置云端服务"), source.login(LoginRequest("a@b.cn", "x")))
    }

    @Test
    fun unreachableHostIsReportedNotThrown() = runBlocking {
        // 保留端口 1 上没有服务，连接被拒绝
        val result = HttpEyeNetworkDataSource("http://127.0.0.1:1").login(LoginRequest("a@b.cn", "x"))
        assertTrue(result is NetworkResult.Unreachable)
    }
}
