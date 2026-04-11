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

        startForeground(NOTIFICATION_ID, buildNotification("Preparing download...", 0))
        _state.value = DownloadState.Progress(0, 1, "model")

        scope.launch {
            downloader.download(variant).collect { state ->
                _state.value = state
                when (state) {
                    is DownloadState.Progress -> {
                        val percent = (state.percent * 100).toInt()
                        val mb = state.bytesDownloaded / (1024 * 1024)
                        val totalMb = state.totalBytes / (1024 * 1024)
                        updateNotification(
                            "Downloading ${state.label}: $mb / $totalMb MB",
                            percent,
                        )
                    }
                    is DownloadState.Complete -> {
                        showCompleteNotification()
                        stopSelf()
                    }
                    is DownloadState.Error -> {
                        showErrorNotification(state.message)
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
            .setContentTitle("Adaptive LLM")
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
            .setContentTitle("Adaptive LLM")
            .setContentText("Model downloaded successfully")
            .setAutoCancel(true)
            .build()
        nm.notify(NOTIFICATION_ID, notification)
    }

    private fun showErrorNotification(error: String) {
        val nm = getSystemService(NotificationManager::class.java)
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("Adaptive LLM")
            .setContentText("Download failed: $error")
            .setAutoCancel(true)
            .build()
        nm.notify(NOTIFICATION_ID, notification)
    }

    companion object {
        private const val CHANNEL_ID = "download_channel"
        private const val NOTIFICATION_ID = 1001
        const val EXTRA_FILE_NAME = "file_name"

        // Shared state so ViewModel can observe progress
        private val _state = MutableStateFlow<DownloadState?>(null)
        val state: StateFlow<DownloadState?> = _state.asStateFlow()

        fun start(context: Context, variant: ModelVariant) {
            _state.value = null
            val intent = Intent(context, DownloadService::class.java).apply {
                putExtra(EXTRA_FILE_NAME, variant.fileName)
            }
            context.startForegroundService(intent)
        }
    }
}
