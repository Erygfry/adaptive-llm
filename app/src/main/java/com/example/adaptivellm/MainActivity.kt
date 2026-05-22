package com.example.adaptivellm

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.LocaleList
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.adaptivellm.ui.AppScreen
import com.example.adaptivellm.ui.ChatListScreen
import com.example.adaptivellm.ui.ChatScreen
import com.example.adaptivellm.ui.DownloadScreen
import com.example.adaptivellm.ui.FactsMemoryScreen
import com.example.adaptivellm.ui.MainViewModel
import com.example.adaptivellm.ui.SettingsScreen
import com.example.adaptivellm.ui.SetupScreen
import com.example.adaptivellm.ui.theme.AdaptiveLLMTheme

class MainActivity : ComponentActivity() {

    /**
     * Применяем выбранную локаль к Configuration ДО того как Activity загружает
     * ресурсы. На ComponentActivity (не AppCompatActivity) auto-mechanism
     * AppCompatDelegate не работает на Android < 13, а на Android 13+ есть
     * timing-issue: AppCompatDelegate.getApplicationLocales() возвращает empty
     * пока appcompat startup initializer не отработал.
     *
     * Решение: читаем выбор напрямую из SharedPreferences. SettingsRepository
     * параллельно вызывает AppCompatDelegate.setApplicationLocales — для
     * Android 13+ system locale picker (Settings → Apps → LocaLLM → Language).
     */
    override fun attachBaseContext(newBase: Context) {
        val prefs = newBase.getSharedPreferences("user_settings", Context.MODE_PRIVATE)
        val langName = prefs.getString("app_language", "SYSTEM") ?: "SYSTEM"
        val tag = when (langName) {
            "EN" -> "en"
            "RU" -> "ru"
            "ES" -> "es"
            "FR" -> "fr"
            "DE" -> "de"
            "ZH" -> "zh"
            else -> null  // SYSTEM или неизвестное значение → используем системную локаль
        }
        if (tag == null) {
            super.attachBaseContext(newBase)
            return
        }
        val locale = java.util.Locale.forLanguageTag(tag)
        java.util.Locale.setDefault(locale)
        val config = Configuration(newBase.resources.configuration)
        config.setLocale(locale)
        config.setLocales(LocaleList(locale))
        android.util.Log.i("MainActivity",
            "attachBaseContext: app_language=$langName → locale=$tag")
        super.attachBaseContext(newBase.createConfigurationContext(config))
    }

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
                    is AppScreen.ChatList -> ChatListScreen(viewModel)
                    is AppScreen.Chat -> ChatScreen(viewModel)
                    is AppScreen.FactsMemory -> FactsMemoryScreen(viewModel)
                    is AppScreen.Settings -> SettingsScreen(viewModel)
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
