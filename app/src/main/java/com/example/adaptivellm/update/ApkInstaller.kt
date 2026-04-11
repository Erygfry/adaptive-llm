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

    // Don't follow redirects automatically — we need to handle the 302 manually
    // because GitHub redirects to a different domain and OkHttp strips Authorization
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
        withContext(Dispatchers.IO) {
            try {
                val apkFile = File(context.cacheDir, "updates/$APK_NAME")
                apkFile.parentFile?.mkdirs()
                if (apkFile.exists()) apkFile.delete()

                // Step 1: Request with auth → GitHub returns 302 redirect
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
                        return@withContext
                    }
                    Log.i(TAG, "Redirected to: $location")
                    location
                } else if (redirectResponse.isSuccessful) {
                    // Direct download worked (unlikely but handle it)
                    redirectResponse.body!!.byteStream().use { input ->
                        apkFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    Log.i(TAG, "APK downloaded directly: ${apkFile.length()} bytes")
                    installApk(context, apkFile)
                    return@withContext
                } else {
                    Log.e(TAG, "Initial request failed: ${redirectResponse.code}")
                    redirectResponse.close()
                    return@withContext
                }

                // Step 2: Download from redirect URL without auth
                val downloadRequest = Request.Builder()
                    .url(downloadUrl)
                    .build()

                val response = downloadClient.newCall(downloadRequest).execute()

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
