package com.example.adaptivellm.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.adaptivellm.R
import com.example.adaptivellm.model.DownloadState

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

        Text(
            model.displayName,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
        )

        Spacer(modifier = Modifier.height(32.dp))

        when (val s = state) {
            is DownloadState.Progress -> {
                Text(
                    stringResource(R.string.download_progress, s.label),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(modifier = Modifier.height(8.dp))

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
