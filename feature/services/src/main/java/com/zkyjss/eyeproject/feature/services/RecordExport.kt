package com.zkyjss.eyeproject.feature.services

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 导出本次测试记录（完整 JSON：问卷、逐题作答、逐帧数据、特征与判定依据）。
 * 用系统「另存为」由用户选择位置，不需要存储权限，也不经过任何网络。
 * 返回 null 表示记录尚未保存完成，此时不显示导出按钮。
 */
@Composable
internal fun rememberRecordExport(fileName: String?, json: () -> String?): (() -> Unit)? {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val text = json() ?: return@rememberLauncherForActivityResult
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) } != null }.getOrDefault(false)
            }
            Toast.makeText(context, if (ok) "已导出本次测试数据" else "导出失败，请换个位置再试", Toast.LENGTH_SHORT).show()
        }
    }
    if (fileName == null) return null
    return { launcher.launch(fileName) }
}
