package com.example.adaptivellm.update

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

object ApkInstaller {

    private const val TAG = "ApkInstaller"
    private const val APK_NAME = "adaptive-llm-update.apk"

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.MINUTES)
        .followRedirects(true)
        .build()

    /**
     * Download APK from GitHub (with auth for private repos) and launch install.
     */
    suspend fun downloadAndInstall(context: Context, apkUrl: String) {
        withContext(Dispatchers.IO) {
            try {
                val apkFile = File(context.cacheDir, "updates/$APK_NAME")
                apkFile.parentFile?.mkdirs()
                if (apkFile.exists()) apkFile.delete()

                // Use GitHub API accept header to get the actual binary
                val request = Request.Builder()
                    .url(apkUrl)
                    .header("Authorization", "Bearer ${UpdateChecker.TOKEN}")
                    .header("Accept", "application/octet-stream")
                    .build()

                Log.i(TAG, "Downloading APK from: $apkUrl")
                val response = client.newCall(request).execute()

                if (!response.isSuccessful) {
                    Log.e(TAG, "Download failed: ${response.code}")
                    return@withContext
                }

                response.body!!.byteStream().use { input ->
                    apkFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }

                Log.i(TAG, "APK downloaded: ${apkFile.length()} bytes")
                installApk(context, apkFile)
            } catch (e: Exception) {
                Log.e(TAG, "Download failed", e)
            }
        }
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
