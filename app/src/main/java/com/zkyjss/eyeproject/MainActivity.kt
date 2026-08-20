package com.zkyjss.eyeproject

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.zkyjss.eyeproject.core.designsystem.theme.EyeProjectTheme
import com.zkyjss.eyeproject.feature.home.HomeScreen
import com.zkyjss.eyeproject.feature.testmodel.TestModelScreen
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            EyeProjectTheme {
                var showTestModel by remember { mutableStateOf(false) }
                if (showTestModel) {
                    TestModelScreen(onBack = { showTestModel = false })
                } else {
                    HomeScreen(
                        onNavigateToTestModel = { showTestModel = true },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }
}
