package com.example.adaptivellm

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.adaptivellm.ui.AppScreen
import com.example.adaptivellm.ui.ChatScreen
import com.example.adaptivellm.ui.DownloadScreen
import com.example.adaptivellm.ui.MainViewModel
import com.example.adaptivellm.ui.SetupScreen
import com.example.adaptivellm.ui.theme.AdaptiveLLMTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestNotificationPermission()

        setContent {
            AdaptiveLLMTheme {
                val viewModel: MainViewModel = viewModel()
                val screen by viewModel.screen.collectAsState()

                when (screen) {
                    is AppScreen.Setup -> SetupScreen(viewModel)
                    is AppScreen.Download -> DownloadScreen(viewModel)
                    is AppScreen.Chat -> ChatScreen(viewModel)
                }
            }
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0
                )
            }
        }
    }
}
