package com.awesome.dhs.tools.downloader

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProgressIndicatorDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.awesome.dhs.tools.downloader.db.DownloadTaskEntity
import com.awesome.dhs.tools.downloader.model.DownloadStatus
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import java.util.Locale

/**
 * FileName: DownloadScreen
 * Author: haosen
 * Date: 10/3/2025 7:55 AM
 * Description:
 **/

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
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        if (!permissionStates.allPermissionsGranted) {
            permissionStates.launchMultiplePermissionRequest()
        }
    }
    LaunchedEffect(Unit) {
        val uri = FolderSelectionManager.getSavedFolder(context = context)?.uri
        val finalFolder = uri?.toString() ?: Environment.getExternalStoragePublicDirectory(
            Environment.DIRECTORY_DOWNLOADS
        ).absolutePath
        Downloader.updateConfig { config -> config.copy(finalDirectory = finalFolder) }
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
            contentPadding = PaddingValues(horizontal = 16.dp),
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
                        } else {
                            if (task.status == DownloadStatus.COMPLETED) {
                                FileOpenerUtil.openFile(context, task.filePath)
                            }
                        }
                    },
                    onItemLongClick = {
                        viewModel.enterMultiSelectMode()
                        viewModel.toggleSelection(task.id)
                    },
                    onPause = { viewModel.pauseDownload(task.id) },
                    onResume = { viewModel.resumeDownload(task.id) },
                    onCancel = { viewModel.cancelDownload(task.id) },
                    onDelete = { viewModel.deleteDownload(task.id) }
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
    onCancel: () -> Unit,
    onDelete: () -> Unit,
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
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
            Text(
                task.fileName,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Status: ${task.status.name}", fontSize = 12.sp)
                if (task.status == DownloadStatus.RUNNING) {
                    Text(
                        "${formatBytes(task.speedBps)}/S",
                        fontSize = 12.sp,
                    )
                }
            }

            if (task.status == DownloadStatus.RUNNING || task.status == DownloadStatus.COMPLETED || task.status == DownloadStatus.PAUSED) {
                LinearProgressIndicator(
                    progress = { if (task.totalBytes > 0) task.progress / 100f else 0f },
                    modifier = Modifier.fillMaxWidth(),
                    color = ProgressIndicatorDefaults.linearColor,
                    trackColor = ProgressIndicatorDefaults.linearTrackColor,
                    strokeCap = ProgressIndicatorDefaults.LinearStrokeCap,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (!isMultiSelectMode) {
                    Text(
                        "${formatBytes(task.downloadedBytes)} / ${formatBytes(task.totalBytes)}",
                        fontSize = 12.sp,
                    )
                    if (task.status == DownloadStatus.RUNNING || task.status == DownloadStatus.QUEUED) {
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
                        IconButton(onClick = onDelete) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete")
                        }
                    }
                    if (task.status == DownloadStatus.CANCELED || task.status == DownloadStatus.COMPLETED) {
                        IconButton(onClick = onDelete) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete")
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
    onCancel: () -> Unit,
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

fun openFile(context: Context, path: String) {
    // 获取文件的 MIME 类型
    val mimeType = getMimeType(path)
    // 创建 Intent
    val intent = Intent(Intent.ACTION_VIEW)
    // 从 Android 7.0 (API 24) 开始需要使用 FileProvider
    val fileUri: Uri?
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && path.startsWith("content://", true)) {
        fileUri = path.toUri()
    } else {
        fileUri = path.toUri()
    }

    intent.setDataAndType(fileUri, mimeType)


    // 添加读取权限
    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

    try {
        context.startActivity(Intent.createChooser(intent, "选择应用打开文件"))
    } catch (e: ActivityNotFoundException) {
        // 没有应用可以处理该文件
        Toast.makeText(context, "没有找到可以打开此文件的应用", Toast.LENGTH_SHORT).show()
    }
}

// 获取 MIME 类型的方法
fun getMimeType(filePath: String): String? {
    val extension = filePath.substring(filePath.lastIndexOf(".") + 1)
    return MimeTypeMap.getSingleton()
        .getMimeTypeFromExtension(extension.lowercase(Locale.getDefault()))
}