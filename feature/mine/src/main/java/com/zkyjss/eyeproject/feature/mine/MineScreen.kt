package com.zkyjss.eyeproject.feature.mine

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.zkyjss.eyeproject.core.designsystem.theme.LocalEyeColors
import com.zkyjss.eyeproject.core.designsystem.theme.LocalEyeTypography
import com.zkyjss.eyeproject.core.designsystem.util.getTopStatusBarPadding
import com.zkyjss.eyeproject.core.designsystem.util.screenHorizontalPaddingLarge
import com.zkyjss.eyeproject.core.ui.component.EyeBrandLockup
import com.zkyjss.eyeproject.core.ui.component.EyeSectionCard
import com.zkyjss.eyeproject.core.ui.component.EyeSelectCard
import com.zkyjss.eyeproject.core.ui.component.EyeText
import com.zkyjss.eyeproject.core.ui.component.rememberEyeHaptics
import androidx.compose.runtime.LaunchedEffect
import com.zkyjss.eyeproject.core.ui.component.AiExplanationCard
import com.zkyjss.eyeproject.feature.services.SubjectProfile
import com.zkyjss.eyeproject.core.data.record.RecordExportFormat

/** 「我的」页：家庭成员、测试记录、云端同步、权限与通知、关于（与苹果端同一顺序与文案）。 */
@Composable
fun MineScreen(
    onEditSubject: (String?) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: MineViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val cloudState by viewModel.cloudState.collectAsStateWithLifecycle()
    val records by viewModel.localRecords.collectAsStateWithLifecycle()
    val subjects by viewModel.subjects.collectAsStateWithLifecycle()
    val serverUrl by viewModel.serverUrl.collectAsStateWithLifecycle()
    val outcome by viewModel.outcome.collectAsStateWithLifecycle()
    val haptics = rememberEyeHaptics()
    // 登录、同意、同步等操作完成：成功轻确认、失败短促提醒（与苹果端一致）
    LaunchedEffect(outcome?.id) { outcome?.let { if (it.success) haptics.success() else haptics.failure() } }
    var recordsOpen by rememberSaveable { mutableStateOf(false) }
    var exporting by rememberSaveable { mutableStateOf<String?>(null) }
    val recordExport = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val file = exporting
        if (uri != null && file != null) viewModel.exportRecord(file, uri) { ok ->
            if (ok) haptics.success() else haptics.failure()
            Toast.makeText(context, if (ok) "已导出测试记录" else "导出失败，请换个位置再试", Toast.LENGTH_SHORT).show()
        }
    }
    val reportExport = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        val file = exporting
        if (uri != null && file != null) viewModel.exportRecord(file, uri, RecordExportFormat.TEXT) { ok ->
            if (ok) haptics.success() else haptics.failure()
            Toast.makeText(context, if (ok) "已导出文字报告" else "导出失败，请换个位置再试", Toast.LENGTH_SHORT).show()
        }
    }
    if (recordsOpen) LocalRecordsSheet(records, onExport = { exporting = it.fileName; recordExport.launch(it.fileName) },
        onExportReport = { exporting = it.fileName; reportExport.launch(it.fileName.removeSuffix(".json") + "_报告.txt") },
        onDismiss = { recordsOpen = false },
        aiCard = { record ->
            val card by remember(record.fileName) { viewModel.explanation(record.fileName) }.collectAsStateWithLifecycle()
            card?.let {
                AiExplanationCard(it, onGenerate = { viewModel.explain(record.fileName) },
                    onAgree = { viewModel.explain(record.fileName, agreeFirst = true) }, embedded = true)
            }
        })
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.let(viewModel::exportTo)
    }
    val versionName = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }
            .getOrNull().orEmpty()
    }
    MineContent(
        versionName = versionName,
        cloudState = cloudState,
        cloudActions = CloudSyncActions(
            signIn = viewModel::signIn,
            register = viewModel::register,
            signOut = viewModel::signOut,
            setConsent = viewModel::setConsent,
            syncNow = viewModel::syncNow,
            export = { exportLauncher.launch("eyeproject-cloud-export.json") },
            deleteCloudAccount = viewModel::deleteCloudAccount,
            setServer = viewModel::setServer,
        ),
        serverUrl = serverUrl,
        recordCount = records.size,
        modifier = modifier,
        subjects = subjects,
        onEditSubject = onEditSubject,
        onOpenRecords = { haptics.tap(); recordsOpen = true },
        onOpenAppSettings = {
            haptics.tap()
            context.startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:${context.packageName}")
                )
            )
        },
    )
}

/** 无副作用的页面内容，供状态驱动及本地界面检查复用。 */
@Composable
internal fun MineContent(
    versionName: String,
    onOpenAppSettings: () -> Unit,
    onOpenRecords: () -> Unit = {},
    subjects: List<SubjectProfile> = emptyList(),
    onEditSubject: (String?) -> Unit = {},
    cloudState: CloudSyncUiState = CloudSyncUiState.NotConfigured(),
    cloudActions: CloudSyncActions = CloudSyncActions(),
    modifier: Modifier = Modifier,
    serverUrl: String = "",
    recordCount: Int = 0,
) {
    val colors = LocalEyeColors.current
    val typography = LocalEyeTypography.current
    Column(
        modifier
            .fillMaxSize()
            .background(colors.appBackground)
            .verticalScroll(rememberScrollState())
            .padding(top = getTopStatusBarPadding())
            .screenHorizontalPaddingLarge(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        EyeText("我的", style = typography.headline, color = colors.textPrimary, modifier = Modifier.padding(vertical = 16.dp))

        SubjectsSection(subjects, onEditSubject)

        EyeSelectCard(content = "测试记录", subtitle = if (recordCount == 0) "暂无记录" else "共 $recordCount 条", onClick = onOpenRecords)

        CloudSyncSection(cloudState, cloudActions, serverUrl)

        EyeSelectCard(content = "权限与通知", onClick = onOpenAppSettings)

        EyeSectionCard {
            EyeBrandLockup()
            Spacer(Modifier.height(12.dp))
            EyeText(listOfNotNull(versionName.takeIf { it.isNotEmpty() }?.let { "版本 $it" }, "结果仅供参考，不能替代眼科检查")
                .joinToString(" · "), style = typography.bodySecondary, color = colors.textSecondary)
        }
        Spacer(Modifier.height(24.dp))
    }
}
