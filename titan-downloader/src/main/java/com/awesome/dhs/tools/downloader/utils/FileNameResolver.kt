package com.awesome.dhs.tools.downloader.utils

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.annotation.RequiresApi
import androidx.documentfile.provider.DocumentFile
import com.awesome.dhs.tools.downloader.model.DownloadRequest
import com.awesome.dhs.tools.downloader.model.ResolvedPath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.net.URLDecoder
import java.util.UUID
import java.util.regex.Pattern
import androidx.core.net.toUri
import com.awesome.dhs.tools.downloader.Downloader
import com.awesome.dhs.tools.downloader.utils.FileUtil.getMimeType

/**
 * FileName: AppDatabase
 * Author: haosen
 * Date: 10/3/2025 4:55 AM
 * Description:
 **/
internal object FileNameResolver {

    private const val MAX_FILENAME_LENGTH = 128
    private val ILLEGAL_CHARACTERS_REGEX = "[\\\\/:*?\"<>|]".toRegex()
    private const val TAG = "FileNameResolver"
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
        context: Context,
        request: DownloadRequest,
        globalFinalDir: String,
        globalTempDir: String,
        client: OkHttpClient,
    ): ResolvedPath =
        mutex.withLock { // Use a mutex to ensure atomicity and prevent race conditions
            withContext(Dispatchers.IO) {
                // 1. 解析文件名 (完善的优先级逻辑)
                val expectedName = resolveRawFileName(request, client)
                val mimeType = expectedName.getMimeType()

                // 准备临时目录
                val tempDir = File(globalTempDir)
                if (!tempDir.exists()) tempDir.mkdirs()
                val tempFile = File(tempDir, "dl-temp-${UUID.randomUUID()}.tmp")

                // 2. 确定目标路径源
                val targetPathSource = if (!request.filePath.isNullOrBlank()) {
                    if (isContentUri(request.filePath)) request.filePath else {
                        val file = File(request.filePath)
                        if (file.isDirectory) request.filePath else file.parentFile?.absolutePath
                            ?: request.filePath
                    }
                } else {
                    globalFinalDir
                }

                // 确定子目录 (默认为包名)
                // 如果用户在 Legacy 模式下提供了具体路径，此逻辑主要用于 Q+ 的 relativePath 构建
                val rawSubDir = ""
//                    context.packageName.takeIf { it.isNotBlank() } ?: "Titan-downloader"
                val finalName = sanitizeFileName(expectedName)

                when {
                    // DocumentTree URI (通过 SAF 选择的文件夹)
                    isDocumentTreeUri(targetPathSource) -> {
                        allocateDocumentTreeFile(
                            context,
                            targetPathSource,
                            finalName,
                            tempFile.absolutePath,
                            mimeType
                        )
                    }
                    // MediaStore URI (Android Q+)
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && isContentUri(targetPathSource) -> {
                        val (uri, relativePath) = resolveMediaStoreUriAndPath(
                            targetPathSource,
                            rawSubDir,
                            mimeType
                        )
                        val (uriString, realName) = allocateAndReserveMediaStore(
                            context, finalName, uri, relativePath, mimeType
                        )
                        ResolvedPath(uriString, tempFile.absolutePath, realName, relativePath)
                    }
                    // Legacy 物理路径
                    else -> {
                        val targetParentDir = if (!request.filePath.isNullOrBlank()) {
                            val f = File(request.filePath)
                            if (request.filePath.endsWith(File.separator) || (f.exists() && f.isDirectory)) {
                                request.filePath
                            } else {
                                f.parent ?: "$globalFinalDir${File.separator}$rawSubDir"
                            }
                        } else {
                            "$globalFinalDir${File.separator}$rawSubDir"
                        }
                        allocateLegacy(targetParentDir, finalName, tempFile.absolutePath)
                    }
                }
            }
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

    /**
     * 文件名解析核心逻辑
     * 优先级：Request Name -> URL Name -> Network Header -> Fallback
     */
    private fun resolveRawFileName(request: DownloadRequest, client: OkHttpClient): String {
        // ... (getRawFileName 内部逻辑保持不变, 它只负责生成“原始”文件名)
        // 1. 用户提供的 fileName (注意: 不是 filePath)
        if (request.fileName.isNotBlank()) {
            return request.fileName
        }

        // 2. 尝试从 URL 中解析名字
        // 只有当 URL 解析出的名字非空且包含扩展名时，才视为有效
        val urlName = parseNameFromUrl(request.url)
        if (isValidFileName(urlName)) {
            return urlName
        }

        // 3. 尝试从 Network Header 解析 (GET/HEAD 请求)
        val headerName = fetchNameFromNetwork(request, client)
        if (!headerName.isNullOrBlank()) {
            return headerName
        }

        // 4. 兜底：默认 时间戳.bin
        return "${System.currentTimeMillis()}.bin"
    }

    private fun sanitizeFileName(rawName: String): String {
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

    /**
     * 处理 DocumentTree URI (SAF 选择的文件夹)
     */
    private fun allocateDocumentTreeFile(
        context: Context,
        documentTreeUri: String,
        filename: String,
        tempPath: String,
        mimeType: String?,
    ): ResolvedPath {
        val uri = documentTreeUri.toUri()
        val folder = DocumentFile.fromTreeUri(context, uri)
            ?: throw IOException("Cannot access DocumentTree URI: $documentTreeUri")

        // 检查文件夹是否可写
        if (!folder.canWrite()) {
            throw IOException("No write permission for folder: ${folder.name}")
        }

        val sanitizedName = sanitizeFileName(filename)
        val nameWithoutExt = sanitizedName.substringBeforeLast('.')
        val ext = sanitizedName.substringAfterLast('.', "")
        val actualMimeType = mimeType ?: ext.getMimeType()

        // 解析文件名冲突
        var finalName = sanitizedName
        var counter = 1
        while (folder.findFile(finalName) != null && counter <= 1000) {
            finalName = if (ext.isNotEmpty()) {
                "$nameWithoutExt($counter).$ext"
            } else {
                "$nameWithoutExt($counter)"
            }
            counter++
        }

        // 创建文件（这里只是预分配，实际写入在下载完成后）
        val fileDoc = folder.createFile(actualMimeType, finalName)
            ?: throw IOException("Failed to create file in DocumentTree folder: $finalName")

        return ResolvedPath(
            filePath = fileDoc.uri.toString(),
            tempPath = tempPath,
            fileName = finalName,
        )
    }

    /**
     * 检查是否为 DocumentTree URI
     */
    fun isDocumentTreeUri(path: String?): Boolean {
        if (path.isNullOrBlank()) return false
        return try {
            val uri = path.toUri()
            DocumentsContract.isTreeUri(uri)
        } catch (e: Exception) {
            Downloader.config.logger.e(TAG, "$e")
            false
        }
    }

    /**
     * 解析 MediaStore URI 和相对路径
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun resolveMediaStoreUriAndPath(
        contentUri: String,
        subDir: String,
        mimeType: String?,
    ): Pair<Uri, String> {
        val uri = contentUri.toUri()

        // 查找对应的 MediaStore 配置
        val config = findMediaStoreConfig(uri) ?: let {
            // 根据 MIME 类型选择默认配置
            when {
                mimeType?.startsWith("image/") == true -> getMediaStoreConfig(MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
                mimeType?.startsWith("video/") == true -> getMediaStoreConfig(MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
                mimeType?.startsWith("audio/") == true -> getMediaStoreConfig(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI)
                else -> getMediaStoreConfig(MediaStore.Downloads.EXTERNAL_CONTENT_URI)
            }
        }

        // 构建相对路径
        val relativePath = if (subDir.isNotBlank()) {
            "${config.rootDirectory}/$subDir"
        } else {
            config.rootDirectory
        }

        return Pair(config.uri, relativePath)
    }

    /**
     * MediaStore 配置数据类
     */
    private data class MediaStoreConfig(
        val uri: Uri,
        val rootDirectory: String,
    )

    /**
     * 获取 MediaStore 配置
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun getMediaStoreConfig(uri: Uri): MediaStoreConfig {
        return when (uri) {
            MediaStore.Downloads.EXTERNAL_CONTENT_URI ->
                MediaStoreConfig(uri, "Download")

            MediaStore.Images.Media.EXTERNAL_CONTENT_URI ->
                MediaStoreConfig(uri, "Pictures")

            MediaStore.Video.Media.EXTERNAL_CONTENT_URI ->
                MediaStoreConfig(uri, "Movies")

            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI ->
                MediaStoreConfig(uri, "Music")

            MediaStore.Files.getContentUri("external") ->
                MediaStoreConfig(uri, "")

            else ->
                MediaStoreConfig(MediaStore.Downloads.EXTERNAL_CONTENT_URI, "Download")
        }
    }

    /**
     * 查找匹配的 MediaStore 配置
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun findMediaStoreConfig(uri: Uri): MediaStoreConfig? {
        val configs = listOf(
            getMediaStoreConfig(MediaStore.Downloads.EXTERNAL_CONTENT_URI),
            getMediaStoreConfig(MediaStore.Images.Media.EXTERNAL_CONTENT_URI),
            getMediaStoreConfig(MediaStore.Video.Media.EXTERNAL_CONTENT_URI),
            getMediaStoreConfig(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI),
            getMediaStoreConfig(MediaStore.Files.getContentUri("external"))
        )

        return configs.find { config ->
            uri.toString().startsWith(config.uri.toString(), ignoreCase = true)
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun allocateAndReserveMediaStore(
        context: Context,
        filename: String,
        baseUri: Uri,
        targetRelativePath: String,
        mimeType: String?,
    ): Pair<String, String> {
        val resolver = context.contentResolver

        // 1. 手动计算一个干净的名字
        val candidateName =
            resolveMediaStoreConflictName(context, filename, baseUri, targetRelativePath)

        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, candidateName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, targetRelativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 0)
        }

        // 2. 插入占位
        val uri = resolver.insert(baseUri, contentValues)
            ?: throw IOException("Failed to allocate MediaStore entry for: $baseUri, path: $targetRelativePath")

        // 3. 再次确认真实名字
        var finalRealName = candidateName
        try {
            resolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        finalRealName = cursor.getString(0)
                    }
                }
        } catch (e: Exception) {
            Downloader.config.logger.e(TAG, "$e")
        }

        return Pair(uri.toString(), finalRealName)
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun resolveMediaStoreConflictName(
        context: Context,
        filename: String,
        baseUri: Uri,
        targetRelativePath: String,
    ): String {
        val resolver = context.contentResolver
        val sanitizedName = sanitizeFileName(filename)
        val nameWithoutExt = sanitizedName.substringBeforeLast('.')
        val ext = sanitizedName.substringAfterLast('.', "")
        val extensionWithDot = if (ext.isNotEmpty()) ".$ext" else ""

        var currentName = sanitizedName
        var counter = 1

        while (counter <= 1000) {
            val selection =
                "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns.RELATIVE_PATH} = ?"
            val selectionArgs = arrayOf(currentName, targetRelativePath)

            var exists = false
            try {
                resolver.query(
                    baseUri,
                    arrayOf(MediaStore.MediaColumns._ID),
                    selection,
                    selectionArgs,
                    null
                )?.use { cursor ->
                    exists = cursor.count > 0
                }
            } catch (e: Exception) {
                Downloader.config.logger.e(TAG, "$e")
                exists = true
            }

            if (!exists) return currentName

            currentName = "$nameWithoutExt($counter)$extensionWithDot"
            counter++
        }
        return currentName
    }

    private suspend fun allocateLegacy(
        parentDirInfo: String,
        filename: String,
        tempPath: String,
    ): ResolvedPath = withContext(Dispatchers.IO) {
        val parentDir = File(parentDirInfo)
        if (!parentDir.exists()) parentDir.mkdirs()

        val sanitizedName = sanitizeFileName(filename)
        val nameWithoutExt = sanitizedName.substringBeforeLast('.')
        val ext = sanitizedName.substringAfterLast('.', "")

        var finalFile = File(parentDir, sanitizedName)
        var counter = 1
        while (finalFile.exists() && counter <= 1000) {
            val newName =
                if (ext.isNotEmpty()) "$nameWithoutExt($counter).$ext" else "$nameWithoutExt($counter)"
            finalFile = File(parentDir, newName)
            counter++
        }

        if (!finalFile.createNewFile()) {
            throw IOException("Failed to create placeholder file: ${finalFile.absolutePath}")
        }

        ResolvedPath(finalFile.absolutePath, tempPath, finalFile.name, null)
    }


    /**
     * 发起轻量级请求获取文件名
     */
    private fun fetchNameFromNetwork(request: DownloadRequest, client: OkHttpClient): String? {
        try {
            // 优先使用 HEAD 请求以节省流量
            val reqBuilder = Request.Builder().url(request.url).head()
            request.headers.forEach { (k, v) -> reqBuilder.addHeader(k, v) }

            client.newCall(reqBuilder.build()).execute().use { response ->
                if (response.isSuccessful) {
                    // A. 尝试 Content-Disposition
                    val disposition = response.header("Content-Disposition")
                    val dispositionName = parseContentDisposition(disposition)
                    if (!dispositionName.isNullOrBlank()) return dispositionName

                    // B. 尝试 Content-Type 推断后缀
                    val contentType = response.header("Content-Type")
                    val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(contentType)
                    if (!ext.isNullOrBlank()) {
                        return "${System.currentTimeMillis()}.$ext"
                    }
                }
            }
        } catch (e: Exception) {
            // 如果 HEAD 失败或网络异常，忽略，走兜底逻辑
            // 这里也可以考虑 fallback 到 GET 请求 (Range: bytes=0-0)，但通常 HEAD 足够
            Downloader.config.logger.e(TAG, "$e")
        }
        return null
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun allocateAndReserveMediaStore(
        context: Context,
        filename: String,
        targetRelativePath: String,
        mimeType: String?,
    ): Pair<String, String> {
        val resolver = context.contentResolver
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI

        // 1. 手动计算一个干净的名字
        val candidateName = resolveMediaStoreConflictName(context, filename, targetRelativePath)

        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, candidateName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, targetRelativePath)
            // 关键：直接设为 0 (Published)，强制占位，防止并发冲突
            put(MediaStore.MediaColumns.IS_PENDING, 0)
        }

        // 2. 插入占位
        val uri = resolver.insert(collection, contentValues)
            ?: throw IOException("Failed to allocate MediaStore entry")

        // 3. 再次确认真实名字
        var finalRealName = candidateName
        try {
            resolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        finalRealName = cursor.getString(0)
                    }
                }
        } catch (e: Exception) {
            Downloader.config.logger.e(TAG, "$e")
        }

        return Pair(uri.toString(), finalRealName)
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun resolveMediaStoreConflictName(
        context: Context,
        filename: String,
        targetRelativePath: String,
    ): String {
        val resolver = context.contentResolver
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val sanitizedName = sanitizeFileName(filename)
        val nameWithoutExt = sanitizedName.substringBeforeLast('.')
        val ext = sanitizedName.substringAfterLast('.', "")
        val extensionWithDot = if (ext.isNotEmpty()) ".$ext" else ""

        var currentName = sanitizedName
        var counter = 1

        while (true) {
            // 纯数据库查重，不进行物理文件检查，避免误删有效文件
            val selection =
                "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?"
            val selectionArgs = arrayOf(currentName, "$targetRelativePath%")

            var exists = false
            try {
                resolver.query(
                    collection,
                    arrayOf(MediaStore.MediaColumns._ID),
                    selection,
                    selectionArgs,
                    null
                )?.use { cursor ->
                    if (cursor.count > 0) exists = true
                }
            } catch (e: Exception) {
                Downloader.config.logger.e(TAG, "$e")
                exists = true
            }

            if (!exists) return currentName

            currentName = "$nameWithoutExt($counter)$extensionWithDot"
            counter++
            if (counter > 1000) break
        }
        return currentName
    }

    private fun isContentUri(path: String): Boolean {
        return path.startsWith("content://", ignoreCase = true)
    }

    private fun isValidFileName(name: String?): Boolean {
        return !name.isNullOrBlank() && name.contains(".") // 简单规则：非空且有后缀
    }

    private fun parseNameFromUrl(url: String): String {
        return try {
            val decodedUrl = URLDecoder.decode(url, "UTF-8")
            File(decodedUrl.substringBefore('?')).name
        } catch (e: Exception) {
            Downloader.config.logger.e(TAG, "$e")
            ""
        }
    }

    private fun parseContentDisposition(disposition: String?): String? {
        if (disposition == null) return null
        try {
            val patternUtf8 =
                Pattern.compile("filename\\*=\\s*UTF-8''([^;]+)", Pattern.CASE_INSENSITIVE)
            val matcherUtf8 = patternUtf8.matcher(disposition)
            if (matcherUtf8.find()) return URLDecoder.decode(matcherUtf8.group(1), "UTF-8")
            val patternNormal =
                Pattern.compile("filename\\s*=\\s*\"?([^;\"]+)\"?", Pattern.CASE_INSENSITIVE)
            val matcherNormal = patternNormal.matcher(disposition)
            if (matcherNormal.find()) return matcherNormal.group(1)
        } catch (e: Exception) {
            Downloader.config.logger.e(TAG, "$e")
        }
        return null
    }
}