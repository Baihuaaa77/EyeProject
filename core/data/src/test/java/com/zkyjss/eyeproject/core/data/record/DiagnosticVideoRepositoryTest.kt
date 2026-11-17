package com.zkyjss.eyeproject.core.data.record

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/** 回看只公开完成的视频；导出不能越过诊断目录或读取其他文件。 */
@RunWith(RobolectricTestRunner::class)
class DiagnosticVideoRepositoryTest {
    @Test fun readyMovieIsDiscoverableAndExportIsScoped() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val directory = File(context.filesDir, "strabismus-diagnostics/strabismus_3000_0").apply { mkdirs() }
        File(directory, "manifest.json").writeText("""{"createdAtMs":3000,"state":"ready","frames":12}""")
        val bytes = byteArrayOf(1, 2, 3, 4)
        File(directory, "comparison.mp4").writeBytes(bytes)
        val repo = LocalDiagnosticVideoRepository(context)
        val saved = repo.sessions.first().single { it.id == directory.name }
        assertNotNull(saved.videoUri)
        val target = File(context.cacheDir, "diagnostic-export.mp4")
        assertTrue(repo.export(directory.name, "comparison.mp4", Uri.fromFile(target)))
        assertArrayEquals(bytes, target.readBytes())
        assertFalse(repo.export("../strabismus_3000_0", "comparison.mp4", Uri.fromFile(target)))
        assertFalse(repo.export(directory.name, "../private.json", Uri.fromFile(target)))
        File(directory, "manifest.json").writeText("""{"createdAtMs":3000,"state":"recording","frames":0}""")
        assertNull(repo.sessions.first().single { it.id == directory.name }.videoUri)
    }
}
