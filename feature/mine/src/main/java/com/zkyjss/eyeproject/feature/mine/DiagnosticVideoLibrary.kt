package com.zkyjss.eyeproject.feature.mine

import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.net.Uri
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zkyjss.eyeproject.core.ui.component.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay

/** 全屏回看弹窗属于记录页；不直接读文件，也不依赖测试feature。 */
@Composable
internal fun DiagnosticVideoLibrary(viewModel: RecordsViewModel, sessionId: String? = null, onClose: () -> Unit) {
    val sessions by viewModel.diagnosticSessions.collectAsStateWithLifecycle()
    val colors = com.zkyjss.eyeproject.core.designsystem.theme.LocalEyeColors.current
    val typography = com.zkyjss.eyeproject.core.designsystem.theme.LocalEyeTypography.current
    val context=LocalContext.current
    var playing by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf<Pair<String,String>?>(null) }
    val export=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("video/mp4")) { uri ->
        val choice=selected
        if(uri!=null && choice!=null) viewModel.exportDiagnostic(choice.first,choice.second,uri) {
            Toast.makeText(context,if(it) "已导出" else "导出失败",Toast.LENGTH_SHORT).show()
        }
    }
    val dataExport=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/x-ndjson")) { uri ->
        val choice=selected
        if(uri!=null && choice!=null) viewModel.exportDiagnostic(choice.first,choice.second,uri) {
            Toast.makeText(context,if(it) "已导出" else "导出失败",Toast.LENGTH_SHORT).show()
        }
    }
    EyeFullScreenPage(onDismiss = onClose) {
        EyeSubPage("斜视诊断视频",onClose) {
            if(sessions.none { sessionId == null || it.id == sessionId }) EyeText("暂无斜视诊断视频", style = typography.body, color = colors.textSecondary)
            for(session in sessions.filter { sessionId == null || it.id == sessionId }) {
                EyeText(SimpleDateFormat("yyyy-MM-dd HH:mm:ss",Locale.CHINA).format(Date(session.createdAtMs)), style = typography.subtitle, color = colors.textPrimary)
                EyeText(when(session.state) {
                    "ready" -> "已保存 · ${session.frames} 帧"
                    "finishing" -> "正在收尾保存"
                    "recording" -> "录制中或上次未正常结束，可导出诊断数据"
                    else -> "未完整保存：${session.error ?: "没有可编码的画面"}"
                }, style = typography.bodySecondary, color = colors.textSecondary)
                val videoUri = session.videoUri
                if (videoUri != null) {
                    EyeButton("回看拼接视频",EyeButtonRole.PRIMARY,{ playing=session.id },Modifier.fillMaxWidth())
                    if (playing == session.id) key(session.id) { DiagnosticPlayback(videoUri) }
                    EyeButton("导出拼接视频",EyeButtonRole.SECONDARY,{ selected=session.id to "comparison.mp4"; export.launch("${session.id}-拼接.mp4") },Modifier.fillMaxWidth())
                    EyeButton("导出原视频",EyeButtonRole.SECONDARY,{ selected=session.id to "original.mp4"; export.launch("${session.id}-原视频.mp4") },Modifier.fillMaxWidth())
                }
                EyeButton("导出逐帧诊断数据",EyeButtonRole.SECONDARY,{ selected=session.id to "frames.jsonl"; dataExport.launch("${session.id}-逐帧.jsonl") },Modifier.fillMaxWidth())
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun DiagnosticPlayback(uri: Uri) {
    val context = LocalContext.current
    val colors = com.zkyjss.eyeproject.core.designsystem.theme.LocalEyeColors.current
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    var playback by remember { mutableStateOf<DiagnosticTexture?>(null) }
    var failed by remember { mutableStateOf(false) }
    var duration by remember { mutableIntStateOf(0) }
    var position by remember { mutableIntStateOf(0) }
    var playing by remember { mutableStateOf(false) }
    var scrubbing by remember { mutableFloatStateOf(-1f) }
    LaunchedEffect(playback) {
        val video = playback ?: return@LaunchedEffect
        while (true) {
            duration = video.duration()
            position = video.position()
            playing = video.isPlaying()
            delay(180L)
        }
    }
    fun transform(scale: Float, offset: Offset) {
        zoom = scale.coerceIn(1f, 6f)
        val limitX = size.width * (zoom - 1f) / 2f
        val limitY = size.height * (zoom - 1f) / 2f
        pan = Offset(offset.x.coerceIn(-limitX,limitX), offset.y.coerceIn(-limitY,limitY))
    }
    val gestures = rememberTransformableState { scale, offset, _ -> transform(zoom * scale, pan + offset) }
    Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clipToBounds().background(colors.black).onSizeChanged {
        size = it
        transform(zoom, pan)
    }.transformable(gestures, canPan = { zoom > 1f }).semantics {
        contentDescription = "拼接视频，双指缩放，放大后可拖动"
    }) {
        AndroidView(
            factory = { DiagnosticTexture(context, uri) { failed = true }.also { playback = it } },
            modifier = Modifier.fillMaxSize().graphicsLayer {
                scaleX = zoom
                scaleY = zoom
                translationX = pan.x
                translationY = pan.y
            },
            onRelease = { it.release() },
        )
    }
    if (failed) EyeText("无法播放此视频", style = com.zkyjss.eyeproject.core.designsystem.theme.LocalEyeTypography.current.body, color = colors.textSecondary)
    Slider(
        value = (if (scrubbing >= 0f) scrubbing else position.toFloat()).coerceIn(0f,duration.toFloat()),
        onValueChange = { scrubbing = it },
        onValueChangeFinished = {
            if (scrubbing >= 0f) {
                position = scrubbing.toInt()
                playback?.seek(position)
            }
            scrubbing = -1f
        },
        valueRange = 0f..duration.coerceAtLeast(1).toFloat(),
        enabled = duration > 0 && !failed,
        colors = SliderDefaults.colors(thumbColor = colors.primary, activeTrackColor = colors.primary),
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "播放进度" },
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        EyeButton(if (playing) "暂停" else "播放", EyeButtonRole.SECONDARY, {
            playback?.toggle()
            playing = playback?.isPlaying() == true
        }, enabled = duration > 0 && !failed)
        EyeButton("缩小", EyeButtonRole.SECONDARY, { transform(zoom / 1.5f, pan / 1.5f) }, enabled = zoom > 1f)
        EyeButton("放大", EyeButtonRole.SECONDARY, { transform(zoom * 1.5f, pan * 1.5f) }, enabled = zoom < 6f)
        EyeButton("复位", EyeButtonRole.SECONDARY, { transform(1f, Offset.Zero) }, enabled = zoom > 1f)
    }
}

/** TextureView 参与父布局的渲染变换，系统控制条独立保留。 */
private class DiagnosticTexture(context: android.content.Context, private val uri: Uri, private val onError: () -> Unit) :
    TextureView(context), TextureView.SurfaceTextureListener {
    private var player: MediaPlayer? = null
    private var prepared = false

    init { surfaceTextureListener = this }

    fun duration() = if (prepared) player?.duration ?: 0 else 0
    fun position() = if (prepared) player?.currentPosition ?: 0 else 0
    fun isPlaying() = prepared && player?.isPlaying == true

    fun seek(position: Int) {
        if (prepared) player?.seekTo(position)
    }

    fun toggle() {
        val video = player?.takeIf { prepared } ?: return
        if (video.isPlaying) video.pause()
        else {
            if (video.currentPosition >= video.duration - 80) video.seekTo(0)
            video.start()
        }
    }

    override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
        release()
        val media = MediaPlayer()
        player = media
        media.setOnPreparedListener { prepared = true; it.start() }
        media.setOnErrorListener { _, _, _ -> release(); onError(); true }
        runCatching {
            val surface = Surface(texture)
            try { media.setSurface(surface) } finally { surface.release() }
            media.setDataSource(context, uri)
            media.prepareAsync()
        }.onFailure { release(); onError() }
    }

    override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) = Unit
    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean { release(); return true }

    fun release() {
        prepared = false
        player?.release()
        player = null
    }
}
