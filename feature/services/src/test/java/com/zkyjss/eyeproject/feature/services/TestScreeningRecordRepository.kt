package com.zkyjss.eyeproject.feature.services

import android.content.Context
import android.net.Uri
import com.zkyjss.eyeproject.core.data.record.RecordSyncStatus
import com.zkyjss.eyeproject.core.data.record.ScreeningRecordRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** 测试替身：与正式仓库一样写入应用私有目录，但不排队上传。 */
class TestScreeningRecordRepository(private val context: Context) : ScreeningRecordRepository {
    val saved = mutableListOf<String>()
    override val syncStatus: StateFlow<RecordSyncStatus> = MutableStateFlow(RecordSyncStatus())
    override val localRecords: StateFlow<List<LocalRecordEntry>> = MutableStateFlow(emptyList())
    override suspend fun exportRecord(fileName: String, target: Uri) = false
    override suspend fun saveRecord(name: String, json: String) {
        context.openFileOutput(name, Context.MODE_PRIVATE).bufferedWriter().use { it.write(json) }
        saved += name
    }
    override suspend fun syncNow() = null
    override fun requestSync() = Unit
}
