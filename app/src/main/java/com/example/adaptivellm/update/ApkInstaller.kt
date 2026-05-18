package com.example.adaptivellm.update

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/** null = idle, 0..100 = downloading, -1 = installing */
object ApkInstaller {

    private const val TAG = "ApkInstaller"
    private const val APK_NAME = "adaptive-llm-update.apk"

    private val _progress = MutableStateFlow<Int?>(null)
    val progress: StateFlow<Int?> = _progress.asStateFlow()

    /**
     * OkHttp по умолчанию следует 302-redirect'ам. GitHub `browser_download_url`
     * отдаёт 302 на CDN — клиент проходит его прозрачно, нам redirect-танец
     * вручную делать не надо. Public репо → auth не требуется.
     */
    private val downloadClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.MINUTES)
        .build()

    /**
     * Скачивает APK по прямой CDN-ссылке (browser_download_url из release assets)
     * и запускает установку через FileProvider + ACTION_VIEW.
     */
    suspend fun downloadAndInstall(context: Context, apkUrl: String) {
        if (_progress.value != null) return // already downloading
        _progress.value = 0

        withContext(Dispatchers.IO) {
            try {
                val apkFile = File(context.cacheDir, "updates/$APK_NAME")
                apkFile.parentFile?.mkdirs()
                if (apkFile.exists()) apkFile.delete()

                Log.i(TAG, "Downloading APK from: $apkUrl")
                val request = Request.Builder().url(apkUrl).build()
                val response = downloadClient.newCall(request).execute()

                if (!response.isSuccessful) {
                    Log.e(TAG, "Download failed: ${response.code}")
                    _progress.value = null
                    return@withContext
                }

                val total = response.body!!.contentLength()
                downloadWithProgress(response.body!!.byteStream(), apkFile, total)

                Log.i(TAG, "APK downloaded: ${apkFile.length()} bytes")
                _progress.value = -1
                installApk(context, apkFile)
                _progress.value = null
            } catch (e: Exception) {
                Log.e(TAG, "Download failed", e)
                _progress.value = null
            }
        }
    }

    private fun downloadWithProgress(input: java.io.InputStream, file: File, totalBytes: Long) {
        file.outputStream().use { output ->
            val buffer = ByteArray(8192)
            var downloaded = 0L
            while (true) {
                val n = input.read(buffer)
                if (n == -1) break
                output.write(buffer, 0, n)
                downloaded += n
                if (totalBytes > 0) {
                    _progress.value = ((downloaded * 100) / totalBytes).toInt()
                }
            }
        }
        input.close()
    }

    private fun installApk(context: Context, apkFile: File) {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apkFile,
        )

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
