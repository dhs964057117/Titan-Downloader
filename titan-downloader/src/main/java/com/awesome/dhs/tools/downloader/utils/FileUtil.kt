package com.awesome.dhs.tools.downloader.utils

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.IOException


/**
 * FileName: FileUtil
 * Author: haosen
 * Date: 10/7/2025 8:21 AM
 * Description:
 **/
object FileUtil {

    /**
     * Moves a source file to the appropriate public downloads directory based on the Android version.
     *
     * @param context The application context.
     * @param sourceFile The temporary file that has been completely downloaded.
     * @param finalPath The target file path for legacy systems.
     * @param fileName The display name for the file in the MediaStore.
     * @param mimeType Optional MIME type for the file.
     * @return The final, user-visible path or name of the saved file.
     */
    suspend fun moveToPublicDirectory(
        context: Context,
        sourceFile: File,
        finalPath: String,
        fileName: String,
        mimeType: String? = null
    ): String = withContext(Dispatchers.IO) {
        // 在移动之前，先找到并删除对应的隐藏占位文件
        val placeholderFile = File(File(finalPath).parent, fileName)
        if (placeholderFile.exists()) {
            placeholderFile.delete()
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 10+ -> Use MediaStore API
            copyToPublicDownloads(context, sourceFile, fileName, mimeType)
        } else {
            // Android 9 and below -> Use legacy File API
            moveToPublicLegacy(sourceFile, finalPath)
        }
    }

    /**
     * For Android 10+
     * Copies a file to the public Downloads collection using MediaStore.
     * @return The final path of the new file.
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    @Throws(IOException::class)
    private fun copyToPublicDownloads(
        context: Context,
        sourceFile: File,
        displayName: String,
        mimeType: String? = null
    ): String {
        val contentResolver = context.contentResolver
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            // 你可以根据文件类型设置 MIME_TYPE
             put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        }

        val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
            ?: throw IOException("Failed to create new MediaStore entry.")

        contentResolver.openOutputStream(uri)?.use { outputStream ->
            FileInputStream(sourceFile).use { inputStream ->
                inputStream.copyTo(outputStream)
            }
        }
        return displayName // 在 MediaStore 中，我们通常只关心文件名
    }

    /**
     * For Android 9 and below.
     * Moves a file using the traditional File API.
     * @return The final path of the new file.
     */
    @Throws(IOException::class)
    private fun moveToPublicLegacy(sourceFile: File, finalPath: String): String {
        val finalFile = File(finalPath)
        finalFile.parentFile?.mkdirs()

        if (!sourceFile.renameTo(finalFile)) {
            // Fallback to copy+delete
            sourceFile.copyTo(finalFile, overwrite = true)
        }
        return finalFile.absolutePath
    }
}