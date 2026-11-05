package com.zkyjss.eyeproject.core.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.zkyjss.eyeproject.core.data.theme.DefaultThemeRepository
import com.zkyjss.eyeproject.core.data.theme.ThemeMode
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** 白天 / 夜晚偏好：默认跟随系统；选过之后保存，下次启动第一帧就是所选。 */
@RunWith(RobolectricTestRunner::class)
class ThemeRepositoryTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test fun defaultsToSystemAndPersistsChoice() = runTest {
        val repository = DefaultThemeRepository(context)
        assertEquals(ThemeMode.SYSTEM, repository.themeMode.value)
        repository.setThemeMode(ThemeMode.DARK)
        assertEquals(ThemeMode.DARK, repository.themeMode.value)
        // 重新创建（相当于下次启动）仍是夜晚；存的取值与苹果端一致
        assertEquals(ThemeMode.DARK, DefaultThemeRepository(context).themeMode.value)
        assertEquals("dark", context.getSharedPreferences("eye_theme", Context.MODE_PRIVATE).getString("mode", null))
        repository.setThemeMode(ThemeMode.LIGHT)
        assertEquals(ThemeMode.LIGHT, DefaultThemeRepository(context).themeMode.value)
    }
}
