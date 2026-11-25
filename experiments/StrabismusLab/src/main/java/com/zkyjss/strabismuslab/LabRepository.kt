package com.zkyjss.strabismuslab

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.zkyjss.eyeproject.strabismuslab.LabSessionReport
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class LabSessionEntry(
    val sessionId: String,
    val directory: File,
    val report: LabSessionReport,
)

enum class LabExportFormat(val label: String, val fileName: String, val mime: String) {
    BUNDLE_ZIP("完整科研数据包 (ZIP)", "bundle.zip", "application/zip"),
    SUMMARY_JSON("完整报告 (JSON)", "session_summary.json", "application/json"),
    FRAMES_JSONL("逐帧日志 (JSONL)", "frames.jsonl", "application/x-ndjson"),
    FRAMES_CSV("逐帧表格 (CSV)", "frames.csv", "text/csv"),
}

/**
 * 科研会话本机持久化与多格式导出仓库。
 * 每次测试落盘 session_summary.json + frames.jsonl + frames.csv，支持一键打包 ZIP 分享供 Python 离线复算。
 */
class LabRepository(private val context: Context) {
    private val rootDir: File
        get() = File(context.filesDir, "strabismus_lab").apply { mkdirs() }

    fun save(report: LabSessionReport): LabSessionEntry {
        val dir = File(rootDir, sanitize(report.sessionId)).apply { mkdirs() }
        File(dir, "session_summary.json").writeText(report.toJson(pretty = true), Charsets.UTF_8)
        File(dir, "frames.jsonl").writeText(report.toFramesJsonl(), Charsets.UTF_8)
        File(dir, "frames.csv").writeText(report.toFramesCsv(), Charsets.UTF_8)
        return LabSessionEntry(report.sessionId, dir, report)
    }

    fun listAll(): List<LabSessionEntry> {
        val dirs = rootDir.listFiles()?.filter { it.isDirectory } ?: return emptyList()
        return dirs.mapNotNull { dir ->
            val summary = File(dir, "session_summary.json")
            if (!summary.exists()) return@mapNotNull null
            runCatching {
                val report = LabSessionReport.fromJson(summary.readText(Charsets.UTF_8))
                LabSessionEntry(report.sessionId, dir, report)
            }.getOrNull()
        }.sortedByDescending { it.report.createdAtMs }
    }

    fun delete(sessionId: String) {
        File(rootDir, sanitize(sessionId)).deleteRecursively()
    }

    fun shareExport(report: LabSessionReport, format: LabExportFormat): Intent {
        val dir = File(rootDir, sanitize(report.sessionId)).apply { mkdirs() }
        val jsonFile = File(dir, "session_summary.json").apply {
            if (!exists()) writeText(report.toJson(pretty = true), Charsets.UTF_8)
        }
        val jsonlFile = File(dir, "frames.jsonl").apply {
            if (!exists()) writeText(report.toFramesJsonl(), Charsets.UTF_8)
        }
        val csvFile = File(dir, "frames.csv").apply {
            if (!exists()) writeText(report.toFramesCsv(), Charsets.UTF_8)
        }

        val target = when (format) {
            LabExportFormat.SUMMARY_JSON -> jsonFile
            LabExportFormat.FRAMES_JSONL -> jsonlFile
            LabExportFormat.FRAMES_CSV -> csvFile
            LabExportFormat.BUNDLE_ZIP -> {
                val exportDir = File(context.cacheDir, "exports").apply { mkdirs() }
                val zipFile = File(exportDir, "${sanitize(report.sessionId)}_strabismus_lab.zip")
                ZipOutputStream(zipFile.outputStream().buffered()).use { zip ->
                    listOf(jsonFile, jsonlFile, csvFile).forEach { f ->
                        if (f.exists()) {
                            zip.putNextEntry(ZipEntry(f.name))
                            f.inputStream().use { it.copyTo(zip) }
                            zip.closeEntry()
                        }
                    }
                }
                zipFile
            }
        }

        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", target)
        return Intent(Intent.ACTION_SEND).apply {
            type = format.mime
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "StrabismusLab · ${report.sessionId}")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun sanitize(id: String): String =
        id.replace(Regex("[^a-zA-Z0-9._-]"), "_").ifBlank { "session" }
}
