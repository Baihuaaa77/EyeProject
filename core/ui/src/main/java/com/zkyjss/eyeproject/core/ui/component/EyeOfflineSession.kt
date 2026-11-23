package com.zkyjss.eyeproject.core.ui.component

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PersistableBundle
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
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

enum class EyeOfflineSession(val page: String, val title: String) {
    GARDEN("garden", "干眼自测 · 试玩"),
    FIREFLY("firefly", "眼位自测 · 试玩"),
    COLOR("color", "色觉自测 · 试玩"),
    POST("post", "老视自测 · 试玩"),
    REST("rest", "转转眼睛"),
    BOOKING("booking", "智能挂号"),
}

private const val SESSION_ENTRY = "file:///android_asset/EyeOffline/index.html"

private const val SESSION_ACTION = "eye-session://"
private val officialBookingLinks = mapOf(
    "appointment" to "https://www.aierchina.com/yuyue/",
    "changsha-appointment" to "https://yuyuehz.aierchina.com/yuyue/pc?cdes=yuyue&cid=1&hid=1&pid=346",
    "directory" to "https://www.aierchina.com/zhaoyiyuan/",
    "doctor-lin" to "https://www.aierchina.com/index.php?c=show&id=144",
    "doctor-zeng" to "https://www.aierchina.com/mobile/jmjybzj/93.html",
)

/** 随包会话保持离线。挂号仅传入显示名，用户操作后才复制或打开固定的官方地址。 */
@Composable
fun EyeOfflineSessionPage(
    session: EyeOfflineSession,
    onClose: () -> Unit,
    voice: Boolean = false,
    bookingPatientNames: List<String> = emptyList(),
    prefillDryEye: Boolean = false,
) {
    val background = LocalEyeColors.current.appBackground
    val close by rememberUpdatedState(onClose)
    val patientNames by rememberUpdatedState(bookingPatientNames)
    val booking = session == EyeOfflineSession.BOOKING
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
                                val url = request.url.toString()
                                if (!request.isForMainFrame || !url.startsWith(SESSION_ACTION)) return true
                                when (val command = url.removePrefix(SESSION_ACTION)) {
                                    "close" -> close()
                                    else -> if (booking && view.url?.substringBefore('#') == SESSION_ENTRY) {
                                        view.handleBookingAction(command)
                                    }
                                }
                                return true
                            }

                            override fun onPageFinished(view: WebView, url: String) {
                                if (booking && url.substringBefore('#') == SESSION_ENTRY) view.setBookingPatientNames(patientNames)
                            }
                        }
                        val chief = if (booking && prefillDryEye) "&chief=surface" else ""
                        loadUrl("$SESSION_ENTRY#page=${session.page}&voice=${if (voice) 1 else 0}&platform=android$chief")
                    }
                },
                update = { view ->
                    if (booking && view.url?.substringBefore('#') == SESSION_ENTRY) view.setBookingPatientNames(patientNames)
                },
                onRelease = { it.stopLoading(); it.destroy() },
            )
        }
    }
}

private fun WebView.setBookingPatientNames(names: List<String>) {
    val values = JSONArray(names.take(30).map { it.take(40) })
    evaluateJavascript("window.bookingPage?.setPatients($values)", null)
}

private fun WebView.bookingStatus(message: String) {
    evaluateJavascript("window.bookingPage?.status(${JSONObject.quote(message)})", null)
}

private fun WebView.handleBookingAction(command: String) {
    if (command == "copy-draft") {
        evaluateJavascript("window.bookingPage?.copyText()") { json ->
            val draft = runCatching { JSONTokener(json).nextValue() as? String }.getOrNull()
            val clipboard = context.getSystemService(ClipboardManager::class.java)
            if (draft.isNullOrBlank() || draft.length > 2_500 || clipboard == null) {
                bookingStatus("暂时无法复制草稿。")
                return@evaluateJavascript
            }
            val clip = ClipData.newPlainText("预问诊草稿", draft)
            if (Build.VERSION.SDK_INT >= 33) {
                clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
            }
            val copied = runCatching { clipboard.setPrimaryClip(clip) }.isSuccess
            bookingStatus(if (copied) "已复制预问诊草稿。" else "暂时无法复制草稿。")
        }
        return
    }
    val url = officialBookingLinks[command] ?: return
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        .onFailure { bookingStatus("无法打开爱尔官网，请在浏览器访问 aierchina.com。") }
}
