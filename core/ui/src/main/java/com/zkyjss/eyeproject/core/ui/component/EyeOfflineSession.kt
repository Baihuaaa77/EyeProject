package com.zkyjss.eyeproject.core.ui.component

import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import com.zkyjss.eyeproject.core.designsystem.theme.LocalEyeColors

enum class EyeOfflineSession(val page: String, val title: String) {
    GARDEN("garden", "干眼自测 · 试玩"),
    FIREFLY("firefly", "眼位自测 · 试玩"),
    COLOR("color", "色觉自测 · 试玩"),
    POST("post", "老视自测 · 试玩"),
    REST("rest", "转转眼睛"),
    BOOKING("booking", "智能挂号 · 演示"),
}

private const val SESSION_ENTRY = "file:///android_asset/EyeOffline/index.html"

/** 离线会话仅渲染随包页面，无账户、相机、记录或原生对象接口。 */
@Composable
fun EyeOfflineSessionPage(
    session: EyeOfflineSession,
    onClose: () -> Unit,
    voice: Boolean = false,
) {
    val background = LocalEyeColors.current.appBackground
    val close by rememberUpdatedState(onClose)
    // 让 WebView 从挂载起就使用稳定的全屏布局，避免入场过渡改变原生视图的绘制边界。
    EyeFullScreenPage(onClose, animateContent = false) {
        Column(Modifier.fillMaxSize().background(background).navigationBarsPadding()) {
            TopBackBar(title = session.title, noText = true, containerColor = background, onBackClick = onClose)
            AndroidView(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                factory = { context ->
                    WebView(context).apply {
                        // 离线网页单独使用软件绘制，规避部分设备的硬件合成裁切。
                        setLayerType(View.LAYER_TYPE_SOFTWARE, null)
                        setBackgroundColor(background.toArgb())
                        settings.javaScriptEnabled = true
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false
                        settings.blockNetworkLoads = true
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                if (request.isForMainFrame && request.url.toString() == "eye-session://close") close()
                                return true
                            }
                        }
                        loadUrl("$SESSION_ENTRY#page=${session.page}&voice=${if (voice) 1 else 0}&platform=android")
                    }
                },
                onRelease = { it.stopLoading(); it.destroy() },
            )
        }
    }
}
