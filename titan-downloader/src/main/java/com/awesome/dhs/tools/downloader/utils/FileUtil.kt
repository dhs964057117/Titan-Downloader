package com.awesome.dhs.tools.downloader.utils

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.annotation.RequiresApi
import com.awesome.dhs.tools.downloader.core.DownloadDispatcher.Companion.TAG
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import com.awesome.dhs.tools.downloader.Downloader
import com.awesome.dhs.tools.downloader.utils.FileNameResolver.isDocumentTreeUri
import java.io.FileInputStream


/**
 * FileName: FileUtil
 * Author: haosen
 * Date: 10/7/2025 8:21 AM
 * Description:
 **/
internal object FileUtil {

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
        mimeType: String? = fileName.getMimeType(),
    ): String = withContext(Dispatchers.IO) {
        when {
            // DocumentTree URI (SAF 选择的文件夹)
            isDocumentTreeUri(finalPath) -> {
                writeToDocumentTreeUri(context, sourceFile, finalPath.toUri())
            }
            // 预分配的 MediaStore URI
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && finalPath.startsWith("content://") -> {
                writeToPreAllocatedUri(context, sourceFile, finalPath.toUri())
            }
//                // 未预分配的 MediaStore URI (fallback)
//                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> {
//                    val targetPath =
//                        relativePath ?: "${Environment.DIRECTORY_DOWNLOADS}${File.separator}"
//                    copyToPublicDownloads(context, sourceFile, fileName, mimeType, targetPath)
//                }
            // Legacy 文件路径
            else -> {
                moveToPublicLegacy(sourceFile, finalPath)
            }
        }
    }

    /**
     * 写入 DocumentTree URI (SAF 选择的文件夹)
     * @param sourceFile 临时文件
     * @param targetUri DocumentTree URI (SAF 选择的文件夹)
     */
    private fun writeToDocumentTreeUri(
        context: Context,
        sourceFile: File,
        targetUri: Uri,
    ): String {
        return try {
            // 获取 DocumentFile 对象
            val documentFile = DocumentFile.fromSingleUri(context, targetUri)
                ?: throw IOException("Cannot access document: $targetUri")

            // 检查写入权限
            if (!documentFile.canWrite()) {
                throw IOException("No write permission for document: ${documentFile.name}")
            }

            // 写入数据
            context.contentResolver.openOutputStream(targetUri)?.use { outputStream ->
                FileInputStream(sourceFile).use { inputStream ->
                    inputStream.copyTo(outputStream)
                }
            } ?: throw IOException("Failed to open output stream for uri: $targetUri")

            targetUri.toString()
        } catch (e: Exception) {
            throw IOException("Failed to write to DocumentTree URI: ${e.message}", e)
        }
    }

    /**
     * 将下载完成的临时文件写入到预处理的mediaStore Uri中
     * @param sourceFile 临时文件
     * @param targetUri 预处理的mediaStore Uri
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun writeToPreAllocatedUri(
        context: Context,
        sourceFile: File,
        targetUri: Uri,
    ): String {
        val contentResolver = context.contentResolver
        try {
            // 1. 写入数据到已存在的 Uri
            contentResolver.openOutputStream(targetUri)?.use { outputStream ->
                FileInputStream(sourceFile).use { inputStream ->
                    inputStream.copyTo(outputStream)
                }
            } ?: throw IOException("Failed to open output stream for uri: $targetUri")

            // 2. 解除 Pending 状态 (发布文件) - 仅对 MediaStore URI 有效
            if (isMediaStoreUri(targetUri)) {
                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                }
                contentResolver.update(targetUri, contentValues, null, null)
            }

            return targetUri.toString()
        } catch (e: Exception) {
            // 如果写入失败，删除这个占位 Uri（仅对 MediaStore URI 有效）
            if (isMediaStoreUri(targetUri)) {
                try {
                    contentResolver.delete(targetUri, null, null)
                } catch (ignored: Exception) {
                    throw IOException("Failed to write to pre-allocated URI: ${ignored.message}")
                }
            }
            throw IOException("Failed to write to pre-allocated URI: ${e.message}", e)
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
        mimeType: String?,
        relativePath: String,
    ): String {
        val contentResolver = context.contentResolver
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType ?: "application/octet-stream")
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }

        val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
            ?: throw IOException("Failed to create new MediaStore entry.")

        try {
            contentResolver.openOutputStream(uri)?.use { outputStream ->
                FileInputStream(sourceFile).use { inputStream ->
                    inputStream.copyTo(outputStream)
                }
            }

            contentValues.clear()
            contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
            contentResolver.update(uri, contentValues, null, null)

            return uri.toString()
        } catch (e: Exception) {
            try {
                contentResolver.delete(uri, null, null)
            } catch (ignored: Exception) {
                throw IOException("Failed to copy to MediaStore", ignored)
            }
            throw IOException("Failed to copy to MediaStore", e)
        }
    }

    /**
     * For Android 9 and below.
     * Moves a file using the traditional File API.
     * @return The final path of the new file.
     */
    @Throws(IOException::class)
    private fun moveToPublicLegacy(sourceFile: File, finalPath: String): String {
        val finalFile = File(finalPath)

        if (finalFile.exists()) {
            finalFile.delete()
        }

        finalFile.parentFile?.mkdirs()

        if (sourceFile.renameTo(finalFile)) {
            return finalFile.absolutePath
        }

        sourceFile.copyTo(finalFile, overwrite = true)
        sourceFile.delete()
        return finalFile.absolutePath
    }

    /**
     * 检查是否为 MediaStore URI
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun isMediaStoreUri(uri: Uri): Boolean {
        return uri.toString().startsWith(MediaStore.Files.getContentUri("external").toString()) ||
                uri.toString().startsWith(MediaStore.Downloads.EXTERNAL_CONTENT_URI.toString()) ||
                uri.toString()
                    .startsWith(MediaStore.Images.Media.EXTERNAL_CONTENT_URI.toString()) ||
                uri.toString().startsWith(MediaStore.Video.Media.EXTERNAL_CONTENT_URI.toString()) ||
                uri.toString().startsWith(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI.toString())
    }

    /**
     * 通用文件写入方法 - 支持所有 URI 类型
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    fun writeBinaryDataToUri(
        context: Context,
        data: ByteArray,
        targetUri: Uri,
        mimeType: String? = null,
    ): String {
        return try {
            when {
                // DocumentTree URI
                DocumentsContract.isTreeUri(targetUri) -> {
                    writeToDocumentTreeUri(context, data, targetUri)
                }
                // MediaStore URI
                isMediaStoreUri(targetUri) -> {
                    writeToMediaStoreUri(context, data, targetUri, mimeType)
                }
                // 其他 Content URI
                else -> {
                    writeToGenericContentUri(context, data, targetUri)
                }
            }
        } catch (e: Exception) {
            throw IOException("Failed to write binary data to URI: ${e.message}", e)
        }
    }

    /**
     * 写入二进制数据到 DocumentTree URI
     */
    private fun writeToDocumentTreeUri(
        context: Context,
        data: ByteArray,
        targetUri: Uri,
    ): String {
        val documentFile = DocumentFile.fromSingleUri(context, targetUri)
            ?: throw IOException("Cannot access document: $targetUri")

        if (!documentFile.canWrite()) {
            throw IOException("No write permission for document: ${documentFile.name}")
        }

        context.contentResolver.openOutputStream(targetUri)?.use { outputStream ->
            outputStream.write(data)
        } ?: throw IOException("Failed to open output stream for uri: $targetUri")

        return targetUri.toString()
    }

    /**
     * 写入二进制数据到 MediaStore URI
     */
    private fun writeToMediaStoreUri(
        context: Context,
        data: ByteArray,
        targetUri: Uri,
        mimeType: String?,
    ): String {
        val contentResolver = context.contentResolver

        // 如果是新创建的 URI，可能需要设置 MIME 类型
        if (mimeType != null) {
            try {
                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                }
                contentResolver.update(targetUri, contentValues, null, null)
            } catch (e: Exception) {
                throw Exception("Failed to writeToMediaStoreUri for uri: $targetUri, $e")
                // 忽略更新 MIME 类型失败的情况
            }
        }

        contentResolver.openOutputStream(targetUri)?.use { outputStream ->
            outputStream.write(data)
        } ?: throw IOException("Failed to open output stream for uri: $targetUri")

        // 发布文件（解除 Pending 状态）
        try {
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }
            contentResolver.update(targetUri, contentValues, null, null)
        } catch (e: Exception) {
            throw Exception("Failed to writeToMediaStoreUri for uri: $targetUri, $e")
            // 忽略更新状态失败的情况
        }

        return targetUri.toString()
    }

    /**
     * 写入二进制数据到通用 Content URI
     */
    private fun writeToGenericContentUri(
        context: Context,
        data: ByteArray,
        targetUri: Uri,
    ): String {
        context.contentResolver.openOutputStream(targetUri)?.use { outputStream ->
            outputStream.write(data)
        } ?: throw IOException("Failed to open output stream for uri: $targetUri")

        return targetUri.toString()
    }

    /**
     * 字符串写入方法
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    fun writeBinaryStringToUri(
        context: Context,
        binaryData: String, // 假设这是Base64编码的字符串
        targetUri: Uri,
    ): String {
        val bytes = binaryData.toByteArray(Charsets.UTF_8)
        return writeBinaryDataToUri(context, bytes, targetUri, "text/plain")
    }

    /**
     * 安全地从 URI 读取数据
     */
    fun readPlaceholderFromUriSafely(
        context: Context,
        uri: Uri,
        maxReadSize: Int = 128, // 最多读取的字节数
    ): String? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                val buffer = ByteArray(maxReadSize)
                val bytesRead = inputStream.read(buffer)

                if (bytesRead == -1) {
                    null
                } else {
                    String(buffer, 0, bytesRead, Charsets.UTF_8)
                }
            }
        } catch (e: Exception) {
            throw Exception("Failed to readPlaceholderFromUriSafely for uri: $uri, $e")
        }
    }

    /**
     * 检查文件是否包含特定的占位符文本
     */
    fun isPlaceholderFile(
        context: Context,
        uri: Uri,
    ): Boolean {
        val content = readPlaceholderFromUriSafely(context, uri, 50)
        return content == "download_placeholder_temp_file"
    }

    fun deleteFile(context: Context, path: String) {
        try {
            if (isDocumentTreeUri(path)) {
                DocumentFile.fromTreeUri(context, path.toUri())?.delete()
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && path.startsWith("content://")) {
                val resolver = context.contentResolver
                resolver.delete(path.toUri(), null, null)
            } else {
                File(path).delete()
            }
        } catch (e: Exception) {
            Downloader.config.logger.e(TAG, "Failed to deleteFile $e")
        }
    }

    fun String?.getMimeType(): String {
        val extension = this?.substringAfterLast('.', "")?.lowercase()
        return when (extension) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "mp4" -> "video/mp4"
            "avi" -> "video/x-msvideo"
            "mov" -> "video/quicktime"
            "mp3" -> "audio/mpeg"
            "wav" -> "audio/wav"
            "pdf" -> "application/pdf"
            "doc", "docx" -> "application/msword"
            "xls", "xlsx" -> "application/vnd.ms-excel"
            "zip" -> "application/zip"
            "txt" -> "text/plain"
            else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
                ?: "application/octet-stream"
        }
    }
}