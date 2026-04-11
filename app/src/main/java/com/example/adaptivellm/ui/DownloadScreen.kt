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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.adaptivellm.model.DownloadState

@Composable
fun DownloadScreen(viewModel: MainViewModel) {
    val state by viewModel.downloadState.collectAsState()
    val model by viewModel.selectedModel.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            "Downloading Model",
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
                    "Downloading ${s.label}...",
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
            }

            is DownloadState.Error -> {
                Text(
                    "Error: ${s.message}",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.error,
                )

                Spacer(modifier = Modifier.height(16.dp))

                Button(onClick = { viewModel.startDownload() }) {
                    Text("Retry")
                }

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedButton(onClick = { viewModel.navigateTo(AppScreen.Setup) }) {
                    Text("Back to Setup")
                }
            }

            is DownloadState.Complete -> {
                Text(
                    "Download complete!",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            null -> {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Spacer(modifier = Modifier.height(16.dp))
                Text("Preparing...", style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}
