package com.example.adaptivellm.model

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class DownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var downloader: ModelDownloader
    private var lastNotificationTime = 0L

    /**
     * Active download tracking — критично для предотвращения dual-write race
     * на tempFile. Без guard'а два `onStartCommand` (например при reopen после
     * Activity death) запускали два collector'а на один и тот же `Range`
     * запрос, которые писали в один tempFile в append mode → байты interleave,
     * GGUF corruption, garbage на инференсе.
     */
    private var activeJob: kotlinx.coroutines.Job? = null
    private var activeFileName: String? = null

    override fun onCreate() {
        super.onCreate()
        downloader = ModelDownloader(applicationContext)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val fileName = intent?.getStringExtra(EXTRA_FILE_NAME)
        val variant = fileName?.let { name ->
            ModelCatalog.variants.find { it.fileName == name }
        }

        if (variant == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        // Dedup: уже скачиваем тот же файл — повторный start игнорируем.
        // foreground notification остаётся, существующий collector продолжает
        // эмитить прогресс в shared _state, UI пересоберётся при подписке.
        if (activeJob?.isActive == true && activeFileName == variant.fileName) {
            return START_NOT_STICKY
        }
        // Переключение на другой файл — отменяем предыдущий job (его tempFile
        // останется на диске для будущего resume).
        activeJob?.cancel()

        activeFileName = variant.fileName
        _activeVariantFile.value = variant.fileName

        startForeground(NOTIFICATION_ID, buildNotification("Preparing download...", 0))
        _state.value = DownloadState.Progress(0, 1, "model")

        activeJob = scope.launch {
            downloader.download(variant).collect { state ->
                _state.value = state
                when (state) {
                    is DownloadState.Progress -> {
                        val now = System.currentTimeMillis()
                        if (now - lastNotificationTime >= 1000) {
                            lastNotificationTime = now
                            val percent = (state.percent * 100).toInt()
                            val mb = state.bytesDownloaded / (1024 * 1024)
                            val totalMb = state.totalBytes / (1024 * 1024)
                            updateNotification(
                                "Downloading ${state.label}: $mb / $totalMb MB",
                                percent,
                            )
                        }
                    }
                    is DownloadState.Complete -> {
                        showCompleteNotification()
                        activeFileName = null
                        _activeVariantFile.value = null
                        stopSelf()
                    }
                    is DownloadState.Error -> {
                        showErrorNotification(state.message)
                        activeFileName = null
                        _activeVariantFile.value = null
                        stopSelf()
                    }
                }
            }
        }

        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    // --- Notifications ---

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Model Download",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Shows download progress for AI models"
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String, progress: Int): Notification {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(getString(com.example.adaptivellm.R.string.app_name))
            .setContentText(text)
            .setProgress(100, progress, progress == 0)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun updateNotification(text: String, progress: Int) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(text, progress))
    }

    private fun showCompleteNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(getString(com.example.adaptivellm.R.string.app_name))
            .setContentText("Model downloaded successfully")
            .setAutoCancel(true)
            .build()
        nm.notify(NOTIFICATION_ID, notification)
    }

    private fun showErrorNotification(error: String) {
        val nm = getSystemService(NotificationManager::class.java)
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle(getString(com.example.adaptivellm.R.string.app_name))
            .setContentText("Download failed: $error")
            .setAutoCancel(true)
            .build()
        nm.notify(NOTIFICATION_ID, notification)
    }

    companion object {
        private const val CHANNEL_ID = "download_channel"
        private const val NOTIFICATION_ID = 1001
        const val EXTRA_FILE_NAME = "file_name"

        // Shared state so ViewModel can observe progress.
        private val _state = MutableStateFlow<DownloadState?>(null)
        val state: StateFlow<DownloadState?> = _state.asStateFlow()

        // Имя файла активного download'а (или null если ничего не качаем).
        // Нужен ViewModel'у в init для авто-resume UI на DownloadScreen
        // после Activity death — без этого юзер видит Setup и может ткнуть
        // Download повторно, что раньше создавало dual-write race.
        private val _activeVariantFile = MutableStateFlow<String?>(null)
        val activeVariantFile: StateFlow<String?> = _activeVariantFile.asStateFlow()

        fun start(context: Context, variant: ModelVariant) {
            // НЕ сбрасываем _state — если идёт active download того же файла,
            // onStartCommand сам это увидит и пропустит дубликат. Для случая
            // Complete/Error/null clearing делается атомарно внутри сервиса
            // когда стартует новый job.
            val intent = Intent(context, DownloadService::class.java).apply {
                putExtra(EXTRA_FILE_NAME, variant.fileName)
            }
            context.startForegroundService(intent)
        }
    }
}
