package com.example.adaptivellm.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import com.example.adaptivellm.R
import com.example.adaptivellm.model.DownloadState
import kotlin.math.PI
import kotlin.math.sin

@Composable
fun DownloadScreen(viewModel: MainViewModel) {
    val state by viewModel.downloadState.collectAsState()
    val model by viewModel.selectedModel.collectAsState()

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            stringResource(R.string.download_title),
            style = MaterialTheme.typography.headlineMedium,
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Variant name (Lite / Standard / Quality) с «дышащей» alpha-волной по
        // буквам — мягкий sin-сдвиг фазы (см. WaveText). Заменяет статичный текст,
        // даёт сигнал «идёт работа» без отдельной строки прогресса.
        WaveText(
            text = model.displayName,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
        )

        Spacer(modifier = Modifier.height(32.dp))

        when (val s = state) {
            is DownloadState.Progress -> {
                // Раньше тут была дублирующая строка "Скачивание model..." —
                // убрана, потому что (1) повторяет хедер и (2) label был
                // нелокализованным ("model"/"vision projector" хардкод из
                // ModelDownloader). MB/% ниже даёт всю нужную информацию.
                LinearProgressIndicator(
                    progress = { s.percent },
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(modifier = Modifier.height(16.dp))

                val downloadedMb = s.bytesDownloaded / (1024 * 1024)
                val totalMb = s.totalBytes / (1024 * 1024)
                Text(
                    "$downloadedMb / $totalMb MB (${(s.percent * 100).toInt()}%)",
                    style = MaterialTheme.typography.bodyLarge,
                )

                Spacer(modifier = Modifier.height(24.dp))

                OutlinedButton(onClick = { viewModel.cancelDownload() }) {
                    Text(stringResource(R.string.download_cancel))
                }
            }

            null -> {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Spacer(modifier = Modifier.height(16.dp))
                Text(stringResource(R.string.download_preparing), style = MaterialTheme.typography.bodyLarge)

                Spacer(modifier = Modifier.height(24.dp))

                OutlinedButton(onClick = { viewModel.cancelDownload() }) {
                    Text(stringResource(R.string.download_cancel))
                }
            }

            is DownloadState.Error -> {
                Text(
                    stringResource(R.string.download_error, s.message),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.error,
                )

                Spacer(modifier = Modifier.height(16.dp))

                Button(onClick = { viewModel.startDownload() }) {
                    Text(stringResource(R.string.download_retry))
                }

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedButton(onClick = { viewModel.navigateTo(AppScreen.Setup) }) {
                    Text(stringResource(R.string.download_back_to_setup))
                }
            }

            is DownloadState.Complete -> {
                Text(
                    stringResource(R.string.download_complete),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
    }
}

/**
 * «Дышащий» текст: каждая буква пульсирует alpha по sin'у со сдвигом фазы
 * по индексу, поэтому волна катится слева направо. Период ~2.4с, диапазон
 * alpha [0.4, 1.0].
 */
@Composable
private fun WaveText(
    text: String,
    style: TextStyle,
    color: Color,
) {
    val infinite = rememberInfiniteTransition(label = "wave-text")
    val phase by infinite.animateFloat(
        initialValue = 0f,
        targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "wave-text-phase",
    )
    val PHASE_PER_CHAR = 0.55f
    Row {
        text.forEachIndexed { i, ch ->
            val perChar = phase + i * PHASE_PER_CHAR
            val alpha = 0.4f + 0.6f * ((sin(perChar) + 1f) / 2f)
            Text(
                text = ch.toString(),
                style = style,
                color = color.copy(alpha = alpha),
            )
        }
    }
}
