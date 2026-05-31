package com.example.adaptivellm.model

import android.content.Context
import com.example.adaptivellm.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

sealed class DownloadState {
    data class Progress(
        val bytesDownloaded: Long,
        val totalBytes: Long,
        /** Label like "model" or "vision projector" */
        val label: String = "model",
    ) : DownloadState() {
        val percent: Float
            get() = if (totalBytes > 0) bytesDownloaded.toFloat() / totalBytes else 0f
    }
    data class Complete(val filePath: String) : DownloadState()
    data class Error(val message: String) : DownloadState()
}

class ModelDownloader(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    fun getLocalPath(variant: ModelVariant): String {
        return File(context.filesDir, variant.fileName).absolutePath
    }

    fun getMmProjPath(variant: ModelVariant): String? {
        val name = variant.mmProjFileName ?: return null
        return File(context.filesDir, name).absolutePath
    }

    fun isDownloaded(variant: ModelVariant): Boolean {
        val modelFile = File(getLocalPath(variant))
        if (!modelFile.exists() || modelFile.length() == 0L) return false

        // If VL model, check mmproj too
        val mmProj = variant.mmProjFileName
        if (mmProj != null) {
            val mmProjFile = File(context.filesDir, mmProj)
            if (!mmProjFile.exists() || mmProjFile.length() == 0L) return false
        }
        return true
    }

    /**
     * Downloads the model (and mmproj if VL) from HuggingFace with resume support.
     */
    fun download(variant: ModelVariant): Flow<DownloadState> = flow {
        // Download main model file
        var hasError = false
        downloadFile(
            repo = variant.huggingFaceRepo,
            fileName = variant.fileName,
            expectedSizeMb = variant.fileSizeMb,
            label = "model",
        ).collect { state ->
            emit(state)
            if (state is DownloadState.Error) hasError = true
        }
        if (hasError) return@flow

        // Download mmproj if present (VL models)
        val mmProj = variant.mmProjFileName
        if (mmProj != null) {
            downloadFile(
                repo = variant.huggingFaceRepo,
                fileName = mmProj,
                expectedSizeMb = 500,
                label = "vision projector",
            ).collect { state ->
                emit(state)
                if (state is DownloadState.Error) hasError = true
            }
            if (hasError) return@flow
        }

        emit(DownloadState.Complete(getLocalPath(variant)))
    }.flowOn(Dispatchers.IO)

    /**
     * Список хостов, с которых пытаемся скачать в порядке приоритета.
     *   1. `huggingface.co` — первичный (быстрее для не-РФ пользователей)
     *   2. `hf-mirror.com` — китайский reverse-proxy для HF, fallback на случай
     *      блокировки HF (актуально для РФ с 2024 — SNI-блок Роскомнадзора).
     *
     * Файлы на mirror'е идентичны HF (он проксирует), поэтому Range-resume между
     * хостами совместимый: если HF успел отдать N байт и упал, mirror продолжит
     * с offset=N без перекачки.
     *
     * Если в будущем потребуется свой mirror (Cloudflare R2 / Yandex Object Storage /
     * GitHub Releases) — добавить хост сюда, fallback-логика подхватит автоматически.
     */
    private val MIRROR_HOSTS = listOf("huggingface.co", "hf-mirror.com")

    private fun downloadFile(
        repo: String,
        fileName: String,
        expectedSizeMb: Long,
        label: String,
    ): Flow<DownloadState> = flow {
        val outFile = File(context.filesDir, fileName)
        val tempFile = File(context.filesDir, "$fileName.part")

        // Skip if already downloaded
        if (outFile.exists() && outFile.length() > 0) {
            emit(DownloadState.Progress(outFile.length(), outFile.length(), label))
            return@flow
        }

        // Check free space (host-independent)
        val freeSpaceMb = context.filesDir.freeSpace / (1024 * 1024)
        if (freeSpaceMb < expectedSizeMb * 1.1) {
            emit(DownloadState.Error(
                "Not enough space. Need $expectedSizeMb MB, have $freeSpaceMb MB free"
            ))
            return@flow
        }

        // Пробуем хосты по очереди. На connect-level ошибке (timeout/SSL/DNS) —
        // переключаемся на следующий. На HTTP-ошибке (4xx/5xx) — отдаём её сразу,
        // потому что mirror даст такой же ответ (mirror проксирует те же файлы).
        var lastConnectError: Throwable? = null

        for ((index, host) in MIRROR_HOSTS.withIndex()) {
            val url = "https://$host/$repo/resolve/main/$fileName"
            if (index > 0) {
                android.util.Log.i("ModelDownloader",
                    "Primary failed (${lastConnectError?.javaClass?.simpleName}), " +
                    "trying fallback host: $host")
            }

            try {
                // Resume support — учитываем уже скачанные байты (от любого предыдущего
                // успешного host'а — mirror обслужит тот же файл по offset)
                val existingBytes = if (tempFile.exists()) tempFile.length() else 0L
                val requestBuilder = Request.Builder().url(url)
                if (existingBytes > 0) {
                    requestBuilder.header("Range", "bytes=$existingBytes-")
                }

                val response = client.newCall(requestBuilder.build()).execute()

                if (!response.isSuccessful && response.code != 206) {
                    // HTTP-ошибка — не сетевая блокировка, mirror даст такой же.
                    emit(DownloadState.Error("HTTP ${response.code}: ${response.message}"))
                    response.close()
                    return@flow
                }

                val body = response.body ?: run {
                    emit(DownloadState.Error("Empty response body"))
                    response.close()
                    return@flow
                }

                val contentLength = body.contentLength()
                val totalBytes = if (response.code == 206) existingBytes + contentLength
                                  else contentLength
                val appendMode = response.code == 206

                FileOutputStream(tempFile, appendMode).use { fos ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(65536) // 64 KB buffer for faster downloads
                        var downloaded = if (appendMode) existingBytes else 0L
                        var lastEmitTime = System.currentTimeMillis()

                        while (true) {
                            val read = input.read(buffer)
                            if (read == -1) break
                            fos.write(buffer, 0, read)
                            downloaded += read

                            // Emit progress at most every 200ms to avoid UI thrashing
                            val now = System.currentTimeMillis()
                            if (now - lastEmitTime >= 200 || downloaded == totalBytes) {
                                emit(DownloadState.Progress(downloaded, totalBytes, label))
                                lastEmitTime = now
                            }
                        }
                    }
                }
                response.close()

                // Success — rename .part в финальное имя и выход
                tempFile.renameTo(outFile)
                return@flow

            } catch (e: java.io.IOException) {
                // Connection-level ошибка (timeout/SSL/DNS/reset). Пробуем следующий
                // хост. Если уже все перебрали — выйдем из цикла и emit ошибку.
                lastConnectError = e
            }
        }

        // Все хосты упали с connect-level ошибкой → пользователь видит локализованный
        // hint про VPN, в скобках техническая деталь для отладки.
        val techDetail = lastConnectError?.message ?: "unknown"
        val userMsg = context.getString(R.string.download_error_all_mirrors_failed)
        emit(DownloadState.Error("$label: $userMsg ($techDetail)"))
    }
}
