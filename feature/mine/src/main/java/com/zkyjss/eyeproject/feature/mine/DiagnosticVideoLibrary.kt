package com.zkyjss.eyeproject.feature.mine

import android.widget.MediaController
import android.widget.VideoView
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zkyjss.eyeproject.core.ui.component.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 全屏回看弹窗属于记录页；不直接读文件，也不依赖测试feature。 */
@Composable
internal fun DiagnosticVideoLibrary(viewModel: RecordsViewModel, sessionId: String? = null, onClose: () -> Unit) {
    val sessions by viewModel.diagnosticSessions.collectAsStateWithLifecycle()
    val colors = com.zkyjss.eyeproject.core.designsystem.theme.LocalEyeColors.current
    val typography = com.zkyjss.eyeproject.core.designsystem.theme.LocalEyeTypography.current
    val context=LocalContext.current
    var playing by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf<Pair<String,String>?>(null) }
    var video by remember { mutableStateOf<VideoView?>(null) }
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
    DisposableEffect(Unit) { onDispose { video?.stopPlayback() } }
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
                if(session.videoUri!=null) {
                    EyeButton("回看拼接视频",EyeButtonRole.PRIMARY,{ playing=session.id },Modifier.fillMaxWidth())
                    if(playing==session.id) key(session.id) { AndroidView(factory={ c -> VideoView(c).also {
                        video?.stopPlayback(); video=it; it.setMediaController(MediaController(c)); it.setVideoURI(session.videoUri)
                        it.setOnPreparedListener { player -> player.isLooping=false; it.start() }
                    } },modifier=Modifier.fillMaxWidth().aspectRatio(16f/9f)) }
                    EyeButton("导出拼接视频",EyeButtonRole.SECONDARY,{ selected=session.id to "comparison.mp4"; export.launch("${session.id}-拼接.mp4") },Modifier.fillMaxWidth())
                    EyeButton("导出原视频",EyeButtonRole.SECONDARY,{ selected=session.id to "original.mp4"; export.launch("${session.id}-原视频.mp4") },Modifier.fillMaxWidth())
                }
                EyeButton("导出逐帧诊断数据",EyeButtonRole.SECONDARY,{ selected=session.id to "frames.jsonl"; dataExport.launch("${session.id}-逐帧.jsonl") },Modifier.fillMaxWidth())
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}
