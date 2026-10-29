package com.zkyjss.eyeproject

import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import com.zkyjss.eyeproject.core.data.account.AccountRepository
import com.zkyjss.eyeproject.core.data.eyecare.EyeCareRepository
import com.zkyjss.eyeproject.core.designsystem.theme.EyeProjectTheme
import com.zkyjss.eyeproject.core.ui.component.EyeAccountBadge
import com.zkyjss.eyeproject.core.ui.component.EyeSplash
import com.zkyjss.eyeproject.core.ui.component.LocalEyeAccountBadge
import com.zkyjss.eyeproject.feature.mine.WelcomeScreen
import com.zkyjss.eyeproject.ui.EyeProjectNavHost
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var accounts: AccountRepository
    @Inject lateinit var eyeCare: EyeCareRepository
    private var foregroundSince = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 完整的 edge-to-edge：内容延伸到状态栏/导航栏，并自动处理图标颜色与透明
        enableEdgeToEdge()
        // 冷启动才播启动动画；旋转、进程恢复不再播
        val coldStart = savedInstanceState == null

        setContent {
            EyeProjectTheme {
                // 右上角账户按钮：登录后显示邮箱首字母
                val badge by remember {
                    accounts.accountState.map { EyeAccountBadge(it.email != null, it.email.orEmpty().take(1)) }
                }.collectAsState(EyeAccountBadge())
                var splash by rememberSaveable { mutableStateOf(coldStart) }
                // 连着云端服务却没登录：冷启动的启动动画之后先提示登录 / 注册（可跳过；主动清空服务器地址、只在本机用的不打扰）
                val account by remember { accounts.accountState }.collectAsState(null)
                var welcome by rememberSaveable { mutableStateOf(false) }
                CompositionLocalProvider(LocalEyeAccountBadge provides badge) {
                    Box(Modifier.fillMaxSize()) {
                        // 首页与启动动画同时创建：动画只盖在上面，不推迟首页加载
                        EyeProjectNavHost(modifier = Modifier.fillMaxSize())
                        if (splash) EyeSplash(onFinished = {
                            splash = false
                            welcome = account?.let { it.cloudConfigured && it.email == null } == true
                        })
                        if (welcome && !splash) WelcomeScreen(onDone = { welcome = false })
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        foregroundSince = SystemClock.elapsedRealtime()
    }

    /** 本应用使用时长计入主动护眼账本（没有系统用屏统计时作为参考）。 */
    override fun onStop() {
        super.onStop()
        val seconds = (SystemClock.elapsedRealtime() - foregroundSince) / 1000
        if (foregroundSince > 0 && seconds > 0) lifecycleScope.launch { eyeCare.addAppSeconds(seconds) }
    }
}
