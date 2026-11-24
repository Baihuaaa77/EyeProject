package com.zkyjss.gazelab

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zkyjss.eyeproject.core.camera.CameraController
import com.zkyjss.eyeproject.core.designsystem.theme.EyeProjectTheme
import com.zkyjss.eyeproject.core.designsystem.theme.EyeShapes
import com.zkyjss.eyeproject.core.designsystem.theme.EyeSpacing
import com.zkyjss.eyeproject.core.designsystem.theme.EyeTheme
import com.zkyjss.eyeproject.core.domain.gaze.ScreenGazeState
import com.zkyjss.eyeproject.core.ui.component.EyeButton
import com.zkyjss.eyeproject.core.ui.component.EyeButtonRole
import com.zkyjss.eyeproject.core.ui.component.EyeText

internal class GazeActivity : ComponentActivity() {
    private val model by viewModels<GazeViewModel>()
    private lateinit var camera: CameraController
    private var binding: Int? = null
    private var resumed = false
    private var hasPermission by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        camera = CameraController(applicationContext)
        setContent {
            var requested by rememberSaveable { mutableStateOf(false) }
            val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
                refreshPermission()
            }
            val state by model.state.collectAsStateWithLifecycle()
            EyeProjectTheme {
                GazeScreen(
                    state = state,
                    permission = hasPermission,
                    onPermission = {
                        if (requested && !shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) {
                            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.fromParts("package", packageName, null)))
                        } else {
                            requested = true
                            permissions.launch(Manifest.permission.CAMERA)
                        }
                    },
                    onRetry = ::refreshPermission,
                    onSize = model::setCanvasSize,
                    onStart = model::startTargets,
                    onShown = model::markTargetShown,
                    onCancel = { model.cancelRun() },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        refreshPermission()
    }

    override fun onPause() {
        resumed = false
        binding?.let(camera::stopIfGeneration)
        binding = null
        model.stopCamera()
        super.onPause()
    }

    private fun refreshPermission() {
        hasPermission = checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        if (!resumed || !hasPermission || binding != null) return
        val session = model.beginCamera()
        binding = camera.start(
            lifecycleOwner = this,
            onFrame = { model.onFrame(session, it) },
            onError = {
                runOnUiThread {
                    if (model.cameraError(session)) {
                        binding?.let(camera::stopIfGeneration)
                        binding = null
                    }
                }
            },
            measureOptics = false,
        )
    }
}

@Composable
private fun GazeScreen(
    state: ScreenGazeState,
    permission: Boolean,
    onPermission: () -> Unit,
    onRetry: () -> Unit,
    onSize: (Int, Int) -> Unit,
    onStart: (Boolean) -> Unit,
    onShown: (Long, Int) -> Unit,
    onCancel: () -> Unit,
) {
    val colors = EyeTheme.colors
    val type = EyeTheme.typography
    val target = state.target
    LaunchedEffect(target?.session, target?.index) {
        if (target != null) {
            withFrameNanos { }
            onShown(target.session, target.index)
        }
    }
    BackHandler(target != null, onCancel)
    Box(Modifier.fillMaxSize().background(colors.appBackground).safeDrawingPadding()) {
        Box(Modifier.fillMaxSize().onSizeChanged { onSize(it.width, it.height) }) {
            if (target != null) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = EyeSpacing.gutter),
                    horizontalArrangement = Arrangement.spacedBy(EyeSpacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(EyeSpacing.xxs)) {
                        val action = if (target.validation) "误差验证" else "标定"
                        EyeText("$action ${target.index + 1}/${target.total}", style = type.body, color = colors.textPrimary)
                        EyeText(state.status, style = type.label, color = colors.textSecondary)
                    }
                    EyeButton("取消", EyeButtonRole.SECONDARY, onCancel)
                }
            } else {
                Column(
                    modifier = Modifier.align(Alignment.TopStart).padding(EyeSpacing.gutter),
                    verticalArrangement = Arrangement.spacedBy(EyeSpacing.xxs),
                ) {
                    EyeText("屏幕注视追踪", Modifier.semantics { heading() }, type.title, colors.textPrimary)
                    if (state.profileCount > 0) {
                        EyeText("本次已标定 ${state.profileCount} 种姿势", style = type.label, color = colors.textSecondary)
                    }
                }
                Column(
                    modifier = Modifier.align(Alignment.BottomCenter).widthIn(max = 540.dp).fillMaxWidth()
                        .padding(EyeSpacing.gutter)
                        .background(colors.panelBackground, EyeShapes.panel)
                        .verticalScroll(rememberScrollState())
                        .padding(EyeSpacing.cardPadding),
                    verticalArrangement = Arrangement.spacedBy(EyeSpacing.sm),
                ) {
                    EyeText(
                        if (permission) state.status else "允许使用前置相机后即可标定",
                        style = type.body, color = colors.textPrimary,
                    )
                    state.report?.let {
                        EyeText(it, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, type.label, colors.textSecondary)
                    }
                    when {
                        !permission -> EyeButton("开启相机", EyeButtonRole.PRIMARY, onPermission, Modifier.fillMaxWidth())
                        state.cameraError -> EyeButton("重试相机", EyeButtonRole.PRIMARY, onRetry, Modifier.fillMaxWidth())
                        else -> {
                            Row(horizontalArrangement = Arrangement.spacedBy(EyeSpacing.xs)) {
                                EyeButton(
                                    if (state.profileCount == 0) "开始标定" else "标定当前姿势",
                                    EyeButtonRole.PRIMARY, { onStart(false) }, Modifier.weight(1f), state.canCalibrate,
                                )
                                EyeButton("验证误差", EyeButtonRole.SECONDARY, { onStart(true) },
                                    Modifier.weight(1f), state.canValidate)
                            }
                        }
                    }
                }
            }
            val point = target?.point ?: state.point
            if (point != null) {
                Canvas(Modifier.fillMaxSize()) {
                    val center = Offset(point.x * size.width, point.y * size.height)
                    val radius = EyeSpacing.xs.toPx()
                    if (target != null) {
                        drawCircle(colors.primary, radius, center)
                        drawCircle(colors.primary, radius * 1.75f, center, style = Stroke(radius / 5))
                    } else {
                        drawCircle(colors.black, radius * 1.25f, center)
                        drawCircle(colors.white, radius, center, style = Stroke(radius / 3))
                        drawCircle(colors.primary, radius / 2, center)
                    }
                }
            }
        }
    }
}
