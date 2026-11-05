package com.zkyjss.eyeproject.core.data

import androidx.test.core.app.ApplicationProvider
import com.zkyjss.eyeproject.core.data.live.SharedPrefsLiveEyePreferencesRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LiveEyePreferencesRepositoryTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test fun landmarksOverlayIsOffByDefaultAndSurvivesRestart() = runTest {
        val repository = SharedPrefsLiveEyePreferencesRepository(context)
        assertFalse(repository.showLandmarks.first())
        repository.setShowLandmarks(true)
        assertTrue(repository.showLandmarks.first())
        // 新实例相当于重启应用：从落盘的偏好读回
        assertTrue(SharedPrefsLiveEyePreferencesRepository(context).showLandmarks.first())
        repository.setShowLandmarks(false)
        assertFalse(SharedPrefsLiveEyePreferencesRepository(context).showLandmarks.first())
    }
}
