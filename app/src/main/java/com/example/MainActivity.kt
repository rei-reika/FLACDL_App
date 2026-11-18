package com.example

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.example.di.TesterAppContainer
import com.example.ui.navigation.TesterAppRoot
import com.example.ui.theme.FlacdlTesterTheme

class MainActivity : ComponentActivity() {

    private lateinit var appContainer: TesterAppContainer

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        appContainer = TesterAppContainer(applicationContext)

        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
        )

        setContent {
            FlacdlTesterTheme {
                TesterAppRoot(container = appContainer)
            }
        }
    }
}
