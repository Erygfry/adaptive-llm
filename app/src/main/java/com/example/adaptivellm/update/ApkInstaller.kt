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

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.MINUTES)
        .followRedirects(false)
        .build()

    private val downloadClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.MINUTES)
        .build()

    /**
     * Download APK from GitHub (with auth for private repos) and launch install.
     */
    suspend fun downloadAndInstall(context: Context, apkUrl: String) {
        if (_progress.value != null) return // already downloading
        _progress.value = 0

        withContext(Dispatchers.IO) {
            try {
                val apkFile = File(context.cacheDir, "updates/$APK_NAME")
                apkFile.parentFile?.mkdirs()
                if (apkFile.exists()) apkFile.delete()

                val request = Request.Builder()
                    .url(apkUrl)
                    .header("Authorization", "Bearer ${UpdateChecker.TOKEN}")
                    .header("Accept", "application/octet-stream")
                    .build()

                Log.i(TAG, "Requesting APK redirect from: $apkUrl")
                val redirectResponse = client.newCall(request).execute()

                val downloadUrl = if (redirectResponse.code == 302) {
                    val location = redirectResponse.header("Location")
                    redirectResponse.close()
                    if (location == null) {
                        Log.e(TAG, "302 but no Location header")
                        _progress.value = null
                        return@withContext
                    }
                    Log.i(TAG, "Redirected to: $location")
                    location
                } else if (redirectResponse.isSuccessful) {
                    val total = redirectResponse.body!!.contentLength()
                    downloadWithProgress(redirectResponse.body!!.byteStream(), apkFile, total)
                    Log.i(TAG, "APK downloaded directly: ${apkFile.length()} bytes")
                    _progress.value = -1
                    installApk(context, apkFile)
                    _progress.value = null
                    return@withContext
                } else {
                    Log.e(TAG, "Initial request failed: ${redirectResponse.code}")
                    redirectResponse.close()
                    _progress.value = null
                    return@withContext
                }

                val downloadRequest = Request.Builder()
                    .url(downloadUrl)
                    .build()

                val response = downloadClient.newCall(downloadRequest).execute()

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
