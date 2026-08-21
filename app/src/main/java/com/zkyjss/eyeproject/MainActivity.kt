package com.zkyjss.eyeproject

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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

        // 完整的 edge-to-edge：内容延伸到状态栏/导航栏，并自动处理图标颜色与透明
        enableEdgeToEdge()

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
