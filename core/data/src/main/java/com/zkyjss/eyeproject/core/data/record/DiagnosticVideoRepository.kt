package com.zkyjss.eyeproject.core.data.record

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

data class DiagnosticVideoSession(val id: String, val createdAtMs: Long, val state: String, val frames: Int,
    val error: String?, val videoUri: Uri?)

/** 只读取诊断目录的短清单；原视频与逐帧文件绝不进入云同步仓库。 */
interface DiagnosticVideoRepository {
    val sessions: Flow<List<DiagnosticVideoSession>>
    suspend fun export(sessionId: String, fileName: String, target: Uri): Boolean
}

@Singleton
internal class LocalDiagnosticVideoRepository @Inject constructor(@ApplicationContext private val context: Context) : DiagnosticVideoRepository {
    private val root=File(context.filesDir,"strabismus-diagnostics")
    override val sessions: Flow<List<DiagnosticVideoSession>> = flow {
        val cache=mutableMapOf<String,Pair<Long,DiagnosticVideoSession>>()
        while(currentCoroutineContext().isActive) {
            val directories=root.listFiles().orEmpty().filter { it.isDirectory && ID.matches(it.name) }
            val alive=directories.mapTo(mutableSetOf()) { it.name }; cache.keys.retainAll(alive)
            for(directory in directories) runCatching {
                val manifest=File(directory,"manifest.json"); val modified=manifest.lastModified()
                if(cache[directory.name]?.first==modified) return@runCatching
                val row=JSONObject(manifest.readText())
                val state=row.optString("state","interrupted"); val video=File(directory,"comparison.mp4")
                cache[directory.name]=modified to DiagnosticVideoSession(directory.name,row.optLong("createdAtMs"),state,
                    row.optInt("frames"),row.optString("error").takeIf { it.isNotBlank() },
                    Uri.fromFile(video).takeIf { state=="ready" && video.isFile })
            }
            emit(cache.values.map { it.second }.sortedByDescending { it.createdAtMs })
            delay(750)
        }
    }.distinctUntilChanged().flowOn(Dispatchers.IO)

    override suspend fun export(sessionId: String,fileName: String,target: Uri): Boolean = withContext(Dispatchers.IO) {
        if(!ID.matches(sessionId) || fileName !in FILES) return@withContext false
        runCatching {
            File(root,"$sessionId/$fileName").inputStream().use { input ->
                requireNotNull(context.contentResolver.openOutputStream(target,"wt")).use { input.copyTo(it) }
            }
        }.isSuccess
    }
    private companion object {
        val ID=Regex("^strabismus_\\d+_\\d+$")
        val FILES=setOf("comparison.mp4","original.mp4","frames.jsonl","manifest.json")
    }
}
