package com.awesome.dhs.tools.downloader

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

data class FolderSelectionResult(
    val uri: Uri? = null,
    val path: String? = null,
    val success: Boolean = false,
    val error: String? = null,
)

@Composable
fun FolderSelectionScreen(
    onFolderSelectedForDownload: (Uri) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var selectionResult by remember { mutableStateOf<FolderSelectionResult?>(null) }
    var savedFolder by remember { mutableStateOf<FolderSelectionManager.SavedFolder?>(null) }
    var folderInfo by remember { mutableStateOf<FolderSelectionManager.FolderInfo?>(null) }

    // 加载保存的文件夹
    LaunchedEffect(Unit) {
        savedFolder = FolderSelectionManager.getSavedFolder(context)
        savedFolder?.let { folder ->
            folderInfo = FolderSelectionManager.getFolderInfo(context, folder.uri)
        }
    }

    // 当有新的选择结果时
    LaunchedEffect(selectionResult) {
        selectionResult?.let { result ->
            if (result.success && result.uri != null) {
                // 保存选择
                FolderSelectionManager.saveSelectedFolder(
                    context,
                    result.uri,
                    result.path ?: "Selected Folder"
                )
                savedFolder = FolderSelectionManager.SavedFolder(result.uri, result.path ?: "")
                folderInfo = FolderSelectionManager.getFolderInfo(context, result.uri)

                // 通知父组件（用于立即开始下载）
                onFolderSelectedForDownload(result.uri)
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        // 标题
        Text(
            text = "下载文件夹选择",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.fillMaxWidth()
        )

        // 当前选择的文件夹信息
        savedFolder?.let { folder ->
            CurrentFolderSection(
                folder = folder,
                folderInfo = folderInfo,
                onClearSelection = {
                    FolderSelectionManager.clearSavedFolder(context)
                    savedFolder = null
                    folderInfo = null
                },
                onChangeFolder = {
                    FolderSelectionManager.clearSavedFolder(context)
                    savedFolder = null
                    folderInfo = null
                    selectionResult = null // 重置以允许重新选择
                }
            )
        }

        // 文件夹选择器
        if (savedFolder == null) {
            FolderSelector(
                onFolderSelected = { result ->
                    selectionResult = result
                },
                modifier = Modifier.fillMaxWidth(),
                title = "选择下载文件夹",
                buttonText = "选择文件夹",
                true
            )
        }

        // 操作指南
        DownloadInstructions()
    }
}

@Composable
private fun CurrentFolderSection(
    folder: FolderSelectionManager.SavedFolder,
    folderInfo: FolderSelectionManager.FolderInfo?,
    onClearSelection: () -> Unit,
    onChangeFolder: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 标题和操作按钮
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "当前下载文件夹",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )

                Row {
                    IconButton(onClick = onChangeFolder) {
                        Icon(Icons.Default.Edit, contentDescription = "更改文件夹")
                    }
                    IconButton(onClick = onClearSelection) {
                        Icon(Icons.Default.Delete, contentDescription = "清除选择")
                    }
                }
            }

            // 文件夹路径
            Text(
                text = folder.displayPath,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.fillMaxWidth()
            )

            // 文件夹信息
            folderInfo?.let { info ->
                if (info.error == null) {
                    FolderInfoColumn(info = info)
                } else {
                    Text(
                        text = "无法访问文件夹: ${info.error}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }

            // 状态指示器
            val statusColor = if (folderInfo?.canWrite == true) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.error
            }

            Text(
                text = if (folderInfo?.canWrite == true) "✓ 可写入" else "✗ 无法写入",
                style = MaterialTheme.typography.labelMedium,
                color = statusColor
            )
        }
    }
}

@Composable
private fun FolderInfoColumn(info: FolderSelectionManager.FolderInfo) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = "文件夹: ${info.displayName}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = "文件夹Uri: ${info.uri}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = "文件数: ${info.fileCount}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun DownloadInstructions() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "使用说明",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface
            )

            Text(
                text = "• 选择文件夹后，所有下载文件将保存到该位置\n" +
                        "• 确保选择的文件夹有写入权限\n" +
                        "• 可以随时更改下载文件夹\n" +
                        "• 支持外部存储和云存储文件夹",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 18.sp
            )
        }
    }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderSelector(
    onFolderSelected: (FolderSelectionResult) -> Unit,
    modifier: Modifier = Modifier,
    title: String = "选择文件夹",
    buttonText: String = "选择文件夹",
    showSelectedPath: Boolean = true,
) {
    val context = LocalContext.current
    var selectedFolderUri by remember { mutableStateOf<Uri?>(null) }
    var selectedFolderPath by remember { mutableStateOf<String?>(null) }

    // 创建文件夹选择启动器
    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            try {
                // 获取持久化权限
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )

                selectedFolderUri = uri
                selectedFolderPath = getPathFromUri(context, uri)

                onFolderSelected(
                    FolderSelectionResult(
                        uri = uri,
                        path = selectedFolderPath,
                        success = true
                    )
                )
            } catch (e: Exception) {
                onFolderSelected(
                    FolderSelectionResult(
                        error = "无法访问该文件夹: ${e.message}",
                        success = false
                    )
                )
            }
        } else {
            onFolderSelected(FolderSelectionResult(success = false, error = "未选择文件夹"))
        }
    }

    Card(
        modifier = modifier,
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 标题
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )

            // 选择按钮
            Button(
                onClick = {
                    folderPickerLauncher.launch(null)
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(text = buttonText)
            }

            // 显示选中的路径
            if (showSelectedPath && selectedFolderPath != null) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "已选择文件夹:",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = selectedFolderPath ?: "未知路径",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }
    }
}

// 辅助函数：从 URI 获取路径
private fun getPathFromUri(context: android.content.Context, uri: Uri): String {
    return try {
        // 尝试从 DocumentFile 获取显示名称
        val documentFile = androidx.documentfile.provider.DocumentFile.fromTreeUri(context, uri)
        documentFile?.name ?: uri.toString()
    } catch (e: Exception) {
        uri.toString()
    }
}