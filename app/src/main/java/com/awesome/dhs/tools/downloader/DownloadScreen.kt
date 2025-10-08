package com.awesome.dhs.tools.downloader

import android.Manifest
import android.os.Build
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.awesome.dhs.tools.downloader.db.DownloadTaskEntity
import com.awesome.dhs.tools.downloader.model.DownloadStatus
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState
/**
 * FileName: DownloadScreen
 * Author: haosen
 * Date: 10/3/2025 7:55 AM
 * Description:
 **/


import java.util.concurrent.TimeUnit

@OptIn(ExperimentalMaterial3Api::class, ExperimentalPermissionsApi::class)
@Composable
fun DownloadScreen(viewModel: DownloadViewModel) {
// --- [NEW] 权限请求逻辑 ---
    val permissionsToRequest = mutableListOf<String>()
    // 为 Android 13+ 请求通知权限
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
    }
    // 为 Android 9 及以下请求外部存储写入权限
    if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
        permissionsToRequest.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
    }

    val permissionStates = rememberMultiplePermissionsState(permissions = permissionsToRequest)
    LaunchedEffect(Unit) {
        if (!permissionStates.allPermissionsGranted) {
            permissionStates.launchMultiplePermissionRequest()
        }
    }

    val tasks by viewModel.tasks.collectAsStateWithLifecycle()
    val isMultiSelectMode by viewModel.isMultiSelectMode.collectAsStateWithLifecycle()
    val selectedTaskIds by viewModel.selectedTaskIds.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            // 根据是否处于多选模式，显示不同的 TopAppBar
            if (isMultiSelectMode) {
                MultiSelectAppBar(
                    selectedCount = selectedTaskIds.size,
                    onClose = { viewModel.exitMultiSelectMode() },
                    onPause = { viewModel.performBatchPause() },
                    onResume = { viewModel.performBatchResume() },
                    onCancel = { viewModel.performBatchCancel() }
                )
            } else {
                TopAppBar(title = { Text("Download Manager Demo") })
            }
        },
        bottomBar = {
            ControlPanel(
                onAddSingle = { viewModel.addSingleDownload() },
                onAddBatch = { viewModel.addBatchDownloads() },
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(tasks, key = { it.id }) { task ->
                DownloadItem(
                    task = task,
                    isMultiSelectMode = isMultiSelectMode,
                    isSelected = selectedTaskIds.contains(task.id),
                    onItemClick = {
                        if (isMultiSelectMode) {
                            viewModel.toggleSelection(task.id)
                        }
                    },
                    onItemLongClick = {
                        viewModel.enterMultiSelectMode()
                        viewModel.toggleSelection(task.id)
                    },
                    onPause = { viewModel.pauseDownload(task.id) },
                    onResume = { viewModel.resumeDownload(task.id) },
                    onCancel = { viewModel.cancelDownload(task.id) }
                )
            }
        }
    }
}

@Composable
fun ControlPanel(onAddSingle: () -> Unit, onAddBatch: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        Button(onClick = onAddSingle) {
            Icon(Icons.Default.Add, contentDescription = "Add Single")
            Spacer(Modifier.size(ButtonDefaults.IconSpacing))
            Text("Add Single")
        }
        Button(onClick = onAddBatch) {
            Icon(Icons.Default.AddCircle, contentDescription = "Add Batch")
            Spacer(Modifier.size(ButtonDefaults.IconSpacing))
            Text("Add Batch")
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DownloadItem(
    task: DownloadTaskEntity,
    isMultiSelectMode: Boolean,
    isSelected: Boolean,
    onItemClick: () -> Unit,
    onItemLongClick: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit
) {
    Card(
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
        ), modifier = Modifier.combinedClickable(
            onClick = onItemClick,
            onLongClick = onItemLongClick
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                task.fileName,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(4.dp))
            Text("Status: ${task.status.name}", fontSize = 12.sp)
            Spacer(Modifier.height(8.dp))

            if (task.status == DownloadStatus.RUNNING || task.status == DownloadStatus.COMPLETED) {
                LinearProgressIndicator(
                    progress = if (task.totalBytes > 0) task.progress / 100f else 0f,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "${formatBytes(task.downloadedBytes)} / ${formatBytes(task.totalBytes)}",
                    fontSize = 12.sp,
                    modifier = Modifier.align(Alignment.End)
                )
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                if (!isMultiSelectMode) {
                    if (task.status == DownloadStatus.RUNNING) {
                        IconButton(onClick = onPause) {
                            Icon(
                                painterResource(R.drawable.svg_notification_pause),
                                contentDescription = "Pause"
                            )
                        }
                    }
                    if (task.status == DownloadStatus.PAUSED) {
                        IconButton(onClick = onResume) {
                            Icon(
                                painterResource(R.drawable.svg_notification_play),
                                contentDescription = "Resume"
                            )
                        }
                    }
                    if (task.status in listOf(
                            DownloadStatus.QUEUED,
                            DownloadStatus.RUNNING,
                            DownloadStatus.PAUSED,
                            DownloadStatus.FAILED
                        )
                    ) {
                        IconButton(onClick = onCancel) {
                            Icon(Icons.Default.Close, contentDescription = "Cancel")
                        }
                    }
                }
            }
        }
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
    if (digitGroups >= units.size) digitGroups = units.size - 1
    return String.format(
        "%.1f %s",
        bytes / Math.pow(1024.0, digitGroups.toDouble()),
        units[digitGroups]
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MultiSelectAppBar(
    selectedCount: Int,
    onClose: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit
) {
    TopAppBar(
        title = { Text("$selectedCount selected") },
        navigationIcon = {
            IconButton(onClick = onClose) {
                Icon(
                    painterResource(R.drawable.svg_notification_cancel),
                    contentDescription = "Close multi-select"
                )
            }
        },
        actions = {
            IconButton(onClick = onPause) {
                Icon(
                    painterResource(R.drawable.svg_notification_pause),
                    contentDescription = "Pause selected"
                )
            }
            IconButton(onClick = onResume) {
                Icon(
                    painterResource(R.drawable.svg_notification_play),
                    contentDescription = "Resume selected"
                )
            }
            IconButton(onClick = onCancel) {
                Icon(Icons.Default.Delete, contentDescription = "Cancel selected")
            }
        }
    )
}