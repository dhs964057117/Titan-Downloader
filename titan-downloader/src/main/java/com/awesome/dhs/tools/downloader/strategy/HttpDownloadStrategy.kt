package com.awesome.dhs.tools.downloader.strategy


/**
 * FileName: HttpDownloadStrategy
 * Author: haosen
 * Date: 10/3/2025 4:44 AM
 * Description:
 **/
import com.awesome.dhs.tools.downloader.DownloaderManager
import com.awesome.dhs.tools.downloader.core.DownloadDispatcher.Companion.TAG
import com.awesome.dhs.tools.downloader.db.DownloadTaskEntity
import com.awesome.dhs.tools.downloader.interfac.IDownloadStrategy
import com.awesome.dhs.tools.downloader.model.DownloadState
import com.awesome.dhs.tools.downloader.model.DownloadStatus
import com.awesome.dhs.tools.downloader.utils.FileUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import kotlin.coroutines.cancellation.CancellationException

class HttpDownloadStrategy : IDownloadStrategy {
    companion object {
        // How often to update progress and speed, in milliseconds.
        private const val PROGRESS_UPDATE_INTERVAL = 1000L
    }

    override fun download(
        task: DownloadTaskEntity,
        client: OkHttpClient,
        stateChecker: suspend () -> DownloadStatus?
    ): Flow<DownloadState> = flow {
        val tempFile = File(task.tempFilePath)
        val downloadedBytes = if (tempFile.exists()) tempFile.length() else 0L

        // 1. 构建支持断点续传的请求
        val request = Request.Builder()
            .url(task.url)
            .header("Range", "bytes=$downloadedBytes-")
            .apply { task.headers.forEach { (key, value) -> addHeader(key, value) } }
            .build()

        val response = try {
            client.newCall(request).execute()
        } catch (e: IOException) {
            emit(DownloadState.Error("Network error: ${e.message}", e))
            return@flow
        }

        if (!response.isSuccessful && response.code != 206) { // 206 Partial Content
            emit(DownloadState.Error("Unexpected response code: ${response.code}"))
            return@flow
        }

        // 2. 获取文件总大小
        val totalBytes =
            response.header("Content-Length")?.toLongOrNull()?.let { it + downloadedBytes }
                ?: (task.totalBytes.takeIf { it > 0 } ?: -1L)

        val mimeType = response.header("Content-Type")
        if (totalBytes == -1L && downloadedBytes == 0L) {
            emit(DownloadState.Error("Could not determine file size."))
            return@flow
        }

        var currentBytes = downloadedBytes
        var lastProgressUpdateTime = System.currentTimeMillis()
        var lastDownloadedBytesForSpeed = downloadedBytes
        emit(
            DownloadState.InProgress(
                calculateProgress(currentBytes, totalBytes),
                currentBytes,
                totalBytes, 0
            )
        )

        // 3. 将数据写入临时文件
        response.body?.byteStream().use { input ->
            RandomAccessFile(tempFile, "rw").use { output ->
                output.seek(downloadedBytes) // 移动到文件末尾
                val buffer = ByteArray(8 * 1024)
                var bytesRead: Int
                while (input?.read(buffer).also { bytesRead = it ?: -1 } != -1) {
                    output.write(buffer, 0, bytesRead)
                    currentBytes += bytesRead
                    val currentTime = System.currentTimeMillis()
                    val deltaTime = currentTime - lastProgressUpdateTime
                    if (deltaTime >= PROGRESS_UPDATE_INTERVAL) {
                        val deltaBytes = currentBytes - lastDownloadedBytesForSpeed
                        val speedBps = if (deltaTime > 0) (deltaBytes * 1000 / deltaTime) else 0
                        // 节流，避免过于频繁地发射状态
                        emit(
                            DownloadState.InProgress(
                                calculateProgress(currentBytes, totalBytes),
                                currentBytes,
                                totalBytes, speedBps
                            )
                        )
                        lastProgressUpdateTime = currentTime
                        lastDownloadedBytesForSpeed = currentBytes
                    }
                    // [NEW] 从外部获取当前任务状态
                    val currentStatus = stateChecker.invoke()

                    if (currentStatus == DownloadStatus.PAUSED) {
                        // 感知到暂停指令，优雅地退出
                        emit(DownloadState.Paused) // 定义一个新的 Paused 状态
                        return@flow
                    }
                    if (currentStatus == DownloadStatus.CANCELED) {
                        // 感知到取消指令，抛出异常以终止流程
                        throw CancellationException("Task was cancelled by user.")
                    }
                }
            }
        }

        try {
            val finalAbsolutePath = FileUtil.moveToPublicDirectory(
                context = DownloaderManager.context!!,
                sourceFile = tempFile,
                finalPath = task.filePath,
                fileName = task.fileName,
                mimeType = mimeType
            )
            emit(DownloadState.Success)
            DownloaderManager.config.logger.d(TAG, "finalAbsolutePath:$finalAbsolutePath")
        } catch (e: Exception) {
            emit(
                DownloadState.Error(
                    "Failed to move temp file to final destination: ${e.message}",
                    e
                )
            )
        }

    }.flowOn(Dispatchers.IO) // 确保所有 IO 操作都在 IO 线程

    private fun calculateProgress(downloaded: Long, total: Long): Int {
        return if (total > 0) ((downloaded * 100) / total).toInt() else 0
    }
}