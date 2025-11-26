package com.awesome.dhs.tools.downloader

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile

object FolderSelectionManager {

    private const val PREFS_NAME = "folder_selection_prefs"
    private const val KEY_SELECTED_FOLDER_URI = "selected_folder_uri"
    private const val KEY_SELECTED_FOLDER_PATH = "selected_folder_path"

    /**
     * 保存选择的文件夹信息
     */
    fun saveSelectedFolder(context: Context, uri: Uri, displayPath: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(KEY_SELECTED_FOLDER_URI, uri.toString())
            .putString(KEY_SELECTED_FOLDER_PATH, displayPath)
            .apply()

        // 获取持久化权限
        takePersistableUriPermission(context, uri)
    }

    /**
     * 获取保存的文件夹信息
     */
    fun getSavedFolder(context: Context): SavedFolder? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val uriString = prefs.getString(KEY_SELECTED_FOLDER_URI, null)
        val displayPath = prefs.getString(KEY_SELECTED_FOLDER_PATH, null)

        return if (uriString != null && displayPath != null) {
            SavedFolder(Uri.parse(uriString), displayPath)
        } else {
            null
        }
    }

    /**
     * 清除保存的文件夹信息
     */
    fun clearSavedFolder(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val oldUri = prefs.getString(KEY_SELECTED_FOLDER_URI, null)

        prefs.edit()
            .remove(KEY_SELECTED_FOLDER_URI)
            .remove(KEY_SELECTED_FOLDER_PATH)
            .apply()

        // 释放旧权限
        oldUri?.let { uriString ->
            releaseUriPermission(context, Uri.parse(uriString))
        }
    }

    /**
     * 获取持久化 URI 权限
     */
    private fun takePersistableUriPermission(context: Context, uri: Uri) {
        try {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (e: SecurityException) {
            Log.w("FolderSelection", "Cannot take persistable permission for: $uri", e)
        }
    }

    /**
     * 释放 URI 权限
     */
    private fun releaseUriPermission(context: Context, uri: Uri) {
        try {
            context.contentResolver.releasePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (e: Exception) {
            Log.w("FolderSelection", "Failed to release permission", e)
        }
    }

    /**
     * 检查文件夹是否可写
     */
    fun isFolderWritable(context: Context, folderUri: Uri): Boolean {
        return try {
            // 尝试创建一个测试文件来验证写入权限
            val folderDoc = DocumentFile.fromTreeUri(context, folderUri)
            val testFile = folderDoc?.createFile("text/plain", ".write_test")
            val result = testFile != null

            // 删除测试文件
            testFile?.delete()
            result
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 获取文件夹的可用空间信息（如果支持）
     */
    fun getFolderInfo(context: Context, folderUri: Uri): FolderInfo {
        return try {
            val folderDoc = DocumentFile.fromTreeUri(context, folderUri)
            val files = folderDoc?.listFiles() ?: emptyArray()

            FolderInfo(
                uri = folderUri,
                fileCount = files.size,
                canWrite = isFolderWritable(context, folderUri),
                displayName = folderDoc?.name ?: "Unknown"
            )
        } catch (e: Exception) {
            FolderInfo(error = e.message ?: "Unknown error")
        }
    }

    data class SavedFolder(val uri: Uri, val displayPath: String)
    data class FolderInfo(
        val uri: Uri? = null,
        val fileCount: Int = 0,
        val canWrite: Boolean = false,
        val displayName: String = "Unknown",
        val error: String? = null,
    )
}