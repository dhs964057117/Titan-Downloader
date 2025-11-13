package com.awesome.dhs.tools.downloader.utils

import android.webkit.MimeTypeMap
import com.awesome.dhs.tools.downloader.model.DownloadRequest
import com.awesome.dhs.tools.downloader.model.ResolvedPath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.net.URL
import java.net.URLDecoder
import java.util.regex.Pattern

/**
 * FileName: AppDatabase
 * Author: haosen
 * Date: 10/3/2025 4:55 AM
 * Description:
 **/
internal object FileNameResolver {

    private const val MAX_FILENAME_LENGTH = 128
    private val ILLEGAL_CHARACTERS_REGEX = "[\\\\/:*?\"<>|]".toRegex()

    private val mutex = Mutex()

    /**
     * The main entry point. Resolves a unique, final, and sanitized path for a download request.
     * This method is thread-safe and handles filesystem race conditions by creating a placeholder file.
     *
     * @param request The user's download request.
     * @param globalFinalDir The default final directory from the global configuration.
     * @param client The OkHttpClient for making HEAD requests if needed.
     * @return A [ResolvedPath] object containing the guaranteed unique final path and file name.
     */
    suspend fun resolve(
        request: DownloadRequest,
        globalFinalDir: String,
        client: OkHttpClient,
    ): ResolvedPath =
        mutex.withLock { // Use a mutex to ensure atomicity and prevent race conditions

            val parentDir: String
            val rawName: String

            // Priority 1: A full file path is provided in the request.
            if (!request.filePath.isNullOrBlank()) {
                val userFile = File(request.filePath)
                // Use the parent directory from the provided path, or fallback to the global directory.
                parentDir = userFile.parent ?: globalFinalDir
                rawName = userFile.name
            } else {
                // Priority 2: No override path, so we auto-generate the filename.
                parentDir = globalFinalDir
                rawName = getRawFileName(request, client)
            }

            // Clean up the raw name (remove illegal chars, etc.).
            val sanitizedName = sanitize(rawName)

            // Check for collisions on the filesystem and create a placeholder to reserve the name.
            val availableName = findAndReserveAvailableFileName(parentDir, sanitizedName)

            // Construct the final, absolute path with the unique name.
            val finalPath = File(parentDir, availableName).absolutePath

            return@withLock ResolvedPath(finalPath = finalPath, fileName = availableName)
        }

    /**
     * Checks for an available filename, and upon finding one,
     * immediately creates a 0-byte placeholder file to reserve it.
     */
    @Throws(IOException::class)
    private suspend fun findAndReserveAvailableFileName(
        directory: String,
        fileName: String,
    ): String = withContext(Dispatchers.IO) {
        val targetDir = File(directory)
        // Attempt to create the directory. If it fails, we cannot proceed.
        if (!targetDir.exists() && !targetDir.mkdirs()) {
            throw IOException("Failed to create target directory: ${targetDir.absolutePath}")
        }

        val originalFile = File(targetDir, fileName)

        // 1. 检查原始文件名是否可用
        if (!originalFile.exists()) {
            try {
                originalFile.createNewFile()
                return@withContext fileName // Success, return the original name
            } catch (e: IOException) {
                // If creation fails (e.g., permission issue), we'll fall through to generate a new name.
                throw e
            }
        }

        // 2. 如果原始文件名已被占用或创建失败，开始生成新名称
        val nameWithoutExtension = fileName.substringBeforeLast('.')
        val extension = fileName.substringAfterLast('.', "")

        //  Use a finite loop instead of while(true)
        for (counter in 1..999) {
            val newFileName = if (extension.isNotEmpty()) {
                "${nameWithoutExtension}($counter).$extension"
            } else {
                "${nameWithoutExtension}($counter)"
            }

            val newFile = File(targetDir, newFileName)
            if (!newFile.exists()) {
                try {
                    newFile.createNewFile()
                    return@withContext newFileName // Found an available name and reserved it.
                } catch (e: IOException) {
                    // Failed to create this placeholder, the loop will try the next number.
                    throw e
                }
            }
        }

        //  If the loop finishes, it means we failed. Throw an exception.
        // This also satisfies the compiler that all paths are handled.
        throw IOException("Failed to find an available filename for '$fileName' after 999 attempts.")
    }

    private suspend fun getRawFileName(request: DownloadRequest, client: OkHttpClient): String {
        // ... (getRawFileName 内部逻辑保持不变, 它只负责生成“原始”文件名)
        // 1. 用户提供的 fileName (注意: 不是 filePath)
        if (request.fileName.isNotBlank()) {
            return request.fileName
        }
        // 2. 从 Content-Disposition
        val fileNameFromHeader =
            parseContentDisposition(fetchHeaders(request.url, request.headers, client))
        if (fileNameFromHeader != null) return fileNameFromHeader
        // 3. 从 URL Path
        val fileNameFromUrl = parseUrlPath(request.url)
        if (isFileNameReasonable(fileNameFromUrl)) return fileNameFromUrl!!
        // 4. 从 MimeType
        val fileNameFromMime =
            generateNameFromMimeType(fetchHeaders(request.url, request.headers, client))
        if (fileNameFromMime != null) return fileNameFromMime
        // 5. 时间戳
        return "${System.currentTimeMillis()}.bin"
    }

    // ... (isFileNameReasonable, parseUrlPath, etc. 保持不变) ...

    private fun sanitize(rawName: String): String {
        // 1. 剥离查询参数 (作为双重保险)
        val nameWithoutQuery = rawName.substringBefore('?')

        // 2. 替换非法字符
        var sanitizedName = nameWithoutQuery.replace(ILLEGAL_CHARACTERS_REGEX, "_")

        // 3. 截断过长的文件名 (逻辑保持不变)
        if (sanitizedName.length > MAX_FILENAME_LENGTH) {
            val extension = sanitizedName.substringAfterLast('.', "")
            val nameWithoutExtension = sanitizedName.substringBeforeLast('.')
            val extensionLength = if (extension.isNotEmpty()) extension.length + 1 else 0
            val availableLengthForName = (MAX_FILENAME_LENGTH - extensionLength).coerceAtLeast(0)
            val truncatedName = nameWithoutExtension.take(availableLengthForName)

            sanitizedName = if (extension.isNotEmpty()) {
                if (truncatedName.isNotEmpty()) "$truncatedName.$extension" else extension.take(
                    MAX_FILENAME_LENGTH
                )
            } else {
                truncatedName
            }
        }
        return sanitizedName.ifBlank { "${System.currentTimeMillis()}_sanitized.bin" }
    }

    private fun isFileNameReasonable(fileName: String?): Boolean {
        if (fileName.isNullOrBlank()) return false
        //  heuristics: a "reasonable" filename is not excessively long and has an extension.
        return fileName.length < MAX_FILENAME_LENGTH && fileName.contains('.')
    }

    private fun parseUrlPath(url: String): String? {
        return try {
            val urlWithoutQuery = url.substringBefore('?')
            val fileName = File(URL(urlWithoutQuery).path).name
            if (fileName.isNotBlank()) fileName else null
        } catch (e: Exception) {
            null
        }
    }

    private fun generateNameFromMimeType(response: Response?): String? {
        val contentType = response?.header("Content-Type") ?: return null
        val mimeType = contentType.substringBefore(';')
        val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType)
        return if (extension != null) {
            "${System.currentTimeMillis()}.$extension"
        } else {
            null
        }
    }

    private suspend fun fetchHeaders(
        url: String,
        headers: Map<String, String>,
        client: OkHttpClient,
    ): Response? = withContext(Dispatchers.IO) {
        try {
            val finalHeaders = headers.toMutableMap()
            finalHeaders.putIfAbsent("User-Agent", "Mozilla/5.0")
            val requestBuilder = Request.Builder().url(url).head()
            finalHeaders.forEach { (key, value) -> requestBuilder.addHeader(key, value) }
            client.newCall(requestBuilder.build()).execute()
        } catch (e: Exception) {
            null
        }
    }

    private fun parseContentDisposition(response: Response?): String? {
        val header = response?.header("Content-Disposition") ?: return null
        return try {
            val pattern = Pattern.compile("filename\\*?=(?:\"|UTF-8'')?([^;]+)")
            val matcher = pattern.matcher(header)
            if (matcher.find()) {
                matcher.group(1)?.let {
                    URLDecoder.decode(it.replace("\"", ""), "UTF-8")
                }
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    fun String?.getFileExtension(): String? {
        if (this == null) return null
        val extension = substringAfterLast('.', missingDelimiterValue = "")
        return if (extension.isNotEmpty() && extension != this) extension else null
    }

    fun String?.getMimeType(): String? {
        val extension = getFileExtension()
        val systemMimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
        // 如果系统没有找到，使用我们的备用映射
        return systemMimeType
    }
}