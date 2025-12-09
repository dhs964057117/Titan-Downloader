package com.awesome.dhs.tools.downloader.strategy

import com.awesome.dhs.tools.downloader.DownloaderManager
import com.awesome.dhs.tools.downloader.db.DownloadTaskEntity
import com.awesome.dhs.tools.downloader.interfac.IDownloadStrategy
import com.awesome.dhs.tools.downloader.model.DownloadState
import com.awesome.dhs.tools.downloader.model.DownloadStatus
import com.awesome.dhs.tools.downloader.utils.FileNameResolver.getMimeType
import com.awesome.dhs.tools.downloader.utils.FileUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.also
import kotlin.apply
import kotlin.collections.forEach
import kotlin.collections.isNotEmpty
import kotlin.collections.set
import kotlin.io.use
import kotlin.run
import kotlin.text.toLong
import kotlin.to

/**
 * 动态小块分片下载策略（修复暂停误判失败问题）
 */
class HttpMultiDownloadStrategy : IDownloadStrategy {
    companion object {
        private const val TAG = "HttpMultiDownloadStrategy"
        private const val PROGRESS_UPDATE_INTERVAL = 1000L
        private const val CHUNK_SIZE = 1024 * 1024L
        private const val MAX_CHUNK_RETRY = 3
    }

    private val pendingChunks = ConcurrentLinkedQueue<LongRange>()
    private val completedBytes = AtomicLong(0)
    private var totalFileSize = 0L
    private val speedHistory = ConcurrentLinkedQueue<Pair<Long, Long>>()
    private val chunkRetryMap = ConcurrentHashMap<LongRange, Int>()
    // 新增：标记是否为主动暂停/取消（核心修复点）
    private val isPausedOrCanceled = AtomicBoolean(false)

    override fun download(
        task: DownloadTaskEntity,
        client: OkHttpClient,
        stateChecker: suspend () -> DownloadStatus?,
    ): Flow<DownloadState> = channelFlow {
        // 初始化
        pendingChunks.clear()
        completedBytes.set(0)
        speedHistory.clear()
        chunkRetryMap.clear()
        isPausedOrCanceled.set(false) // 重置暂停标记
        // 步骤1：获取文件总大小
        totalFileSize = if (task.totalBytes > 0) {
            task.totalBytes
        } else {
            getFileTotalSize(task.url, client) ?: run {
                send(DownloadState.Error("无法获取文件总大小"))
                return@channelFlow
            }
        }

        // 步骤2：初始化未下载区间池
        initPendingChunks(File(task.tempFilePath), totalFileSize)

        // 无未下载区间，直接返回成功
        if (pendingChunks.isEmpty()) {
            try {
                val finalPath = moveTempFileToFinal(task, totalFileSize)
                DownloaderManager.config.logger.d("HttpMultiDownloadStrategy", "文件已完整，直接完成：$finalPath")
                send(DownloadState.Success)
            } catch (e: Exception) {
                send(DownloadState.Error("文件移动失败：${e.message}", e))
            }
            return@channelFlow
        }

        // 步骤3：核心下载逻辑（重构为独立函数，便于异常处理）
        try {
            executeDownload(task, client, stateChecker, this) { state ->
                // 转发状态到 channelFlow
                send(state)
            }
        } catch (e: CancellationException) {
            // 捕获暂停/取消异常，标记状态并发送 Paused
            isPausedOrCanceled.set(true)
            DownloaderManager.config.logger.d("HttpMultiDownloadStrategy", "任务主动暂停/取消：${e.message}")
            send(DownloadState.Paused)
        } catch (e: Exception) {
            // 仅捕获真正的下载异常，发送 Error
            if (!isPausedOrCanceled.get()) { // 排除暂停/取消导致的异常
                DownloaderManager.config.logger.e(TAG, "下载异常", e)
                send(DownloadState.Error("下载失败：${e.message}", e))
            }
        }

    }.flowOn(Dispatchers.IO)
        .onCompletion {
            // 清理资源
            pendingChunks.clear()
            completedBytes.set(0)
            speedHistory.clear()
            chunkRetryMap.clear()
            isPausedOrCanceled.set(false)
        }

    /**
     * 独立的下载执行逻辑（核心修复：分离暂停/失败判断）
     */
    private suspend fun executeDownload(
        task: DownloadTaskEntity,
        client: OkHttpClient,
        stateChecker: suspend () -> DownloadStatus?,
        scope: CoroutineScope, // 新增：接收协程作用域
        onState: suspend (DownloadState) -> Unit
    ) {
        // 启动进度发射协程
        val progressJob = scope.launch(Dispatchers.IO) {
            while (isActive && completedBytes.get() < totalFileSize) {
                // 检查暂停/取消状态（提前判断，避免无效发射）
                val status = stateChecker.invoke()
                if (status == DownloadStatus.PAUSED || status == DownloadStatus.CANCELED) {
                    isPausedOrCanceled.set(true)
                    throw CancellationException(if (status == DownloadStatus.PAUSED) "任务暂停" else "任务取消")
                }

                // 发射进度
                val progress = calculateProgress(completedBytes.get(), totalFileSize)
                val speed = calculateDownloadSpeed()
                onState(
                    DownloadState.InProgress(
                        progress = progress,
                        downloadedBytes = completedBytes.get(),
                        totalBytes = totalFileSize,
                        speedBps = speed
                    )
                )

                delay(PROGRESS_UPDATE_INTERVAL)
            }
        }

        // 启动下载线程
        val downloadJobs = mutableListOf<Job>()
        repeat(DownloaderManager.config.downloadThreadCount) { threadIndex ->
            val job = scope.launch(Dispatchers.IO) {
                downloadWorker(threadIndex, task, client, stateChecker)
            }
            downloadJobs.add(job)
        }

        // 等待所有下载线程完成（若中途暂停，会抛出 CancellationException）
        downloadJobs.joinAll()
        progressJob.cancel()

        // 仅当不是暂停/取消时，才校验文件完整性
        if (!isPausedOrCanceled.get()) {
            if (completedBytes.get() == totalFileSize) {
                val finalPath = moveTempFileToFinal(task, totalFileSize)
                DownloaderManager.config.logger.d(TAG, "下载完成，最终路径：$finalPath")
                onState(DownloadState.Success)
            } else {
                // 真正的下载不完整（非暂停导致）
                onState(DownloadState.Error("文件下载不完整：${completedBytes.get()}/${totalFileSize}"))
            }
        }
    }

    /**
     * 单个下载线程逻辑（新增暂停检查）
     */
    private suspend fun downloadWorker(
        threadIndex: Int,
        task: DownloadTaskEntity,
        client: OkHttpClient,
        stateChecker: suspend () -> DownloadStatus?
    ) {
        val tempFile = File(task.tempFilePath)
        DownloaderManager.config.logger.d(TAG, "线程$threadIndex 启动，开始抢块下载")

        while (pendingChunks.isNotEmpty() && !isPausedOrCanceled.get()) { // 新增：检查暂停标记
            val chunkRange = pendingChunks.poll() ?: break
            val start = chunkRange.start
            val end = chunkRange.endInclusive

            // 双重检查：状态 + 原子标记
            val currentStatus = stateChecker.invoke()
            if (currentStatus == DownloadStatus.PAUSED || currentStatus == DownloadStatus.CANCELED) {
                isPausedOrCanceled.set(true)
                pendingChunks.offer(chunkRange) // 放回未下载小块
                DownloaderManager.config.logger.d(TAG, "线程$threadIndex 检测到暂停/取消，放回小块：$chunkRange")
                break
            }

            try {
                downloadSingleChunk(threadIndex, task, client, tempFile, start, end)
                val chunkSize = end - start + 1
                completedBytes.addAndGet(chunkSize)
                DownloaderManager.config.logger.d(TAG, "线程$threadIndex 完成小块：$chunkRange，已完成：${completedBytes.get()}/${totalFileSize}")
            } catch (e: Exception) {
                // 若已暂停，不再重试，直接退出
                if (isPausedOrCanceled.get()) break

                val retryCount = chunkRetryMap.getOrDefault(chunkRange, 0) + 1
                chunkRetryMap[chunkRange] = retryCount

                if (retryCount <= MAX_CHUNK_RETRY) {
                    pendingChunks.offer(chunkRange)
                    val delayTime = 1000L * retryCount
                    DownloaderManager.config.logger.d(TAG, "线程$threadIndex 小块$chunkRange 下载失败（第$retryCount 次），${delayTime}ms后重试：${e.message}")
                    delay(delayTime)
                } else {
                    val errorMsg = "线程$threadIndex 小块$chunkRange 下载失败（重试$MAX_CHUNK_RETRY 次）：${e.message}"
                    DownloaderManager.config.logger.e(TAG, errorMsg, e)
                    throw IOException(errorMsg, e)
                }
            }
        }

        DownloaderManager.config.logger.d(TAG, "线程$threadIndex 退出（暂停标记：${isPausedOrCanceled.get()}）")
    }

    // 以下方法无核心修改，仅保留完整代码
    private fun downloadSingleChunk(
        threadIndex: Int,
        task: DownloadTaskEntity,
        client: OkHttpClient,
        tempFile: File,
        start: Long,
        end: Long
    ) {
        val request = Request.Builder()
            .url(task.url)
            .header("Range", "bytes=$start-$end")
            .apply {
                task.headers.forEach { (key, value) ->
                    addHeader(key, value)
                }
            }
            .build()

        val response = client.newCall(request).execute()
        if (!response.isSuccessful && response.code != 206) {
            throw IOException("小块$start-$end 请求失败，响应码：${response.code}")
        }

        response.body?.byteStream()?.use { inputStream ->
            RandomAccessFile(tempFile, "rw").use { raf ->
                raf.seek(start)
                val buffer = ByteArray(8 * 1024)
                var bytesRead: Int
                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    raf.write(buffer, 0, bytesRead)
                }
            }
        }

        if (!isChunkValid(tempFile, start, end)) {
            throw IOException("小块$start-$end 数据验证失败")
        }
    }

    private fun initPendingChunks(tempFile: File, totalSize: Long) {
        DownloaderManager.config.logger.d(TAG, "初始化未下载区间池，文件总大小：$totalSize")

        if (!tempFile.exists()) {
            splitIntoChunks(0L, totalSize)
            DownloaderManager.config.logger.d(TAG, "全新下载，拆分出 ${pendingChunks.size} 个小块")
            return
        }

        val fileLength = tempFile.length()
        var current = 0L
        var downloadedChunks = 0
        var pendingChunksCount = 0

        while (current < totalSize) {
            val chunkEnd = kotlin.comparisons.minOf(current + CHUNK_SIZE - 1, totalSize - 1)
            val isChunkDownloaded = fileLength > chunkEnd && isChunkValid(tempFile, current, chunkEnd)

            if (isChunkDownloaded) {
                completedBytes.addAndGet(chunkEnd - current + 1)
                downloadedChunks++
            } else {
                pendingChunks.offer(current..chunkEnd)
                pendingChunksCount++
            }

            current = chunkEnd + 1
        }

        DownloaderManager.config.logger.d(TAG, "断点续传扫描完成：已下载$downloadedChunks 个小块，待下载$pendingChunksCount 个小块")
    }

    private fun splitIntoChunks(start: Long, end: Long) {
        var current = start
        while (current <= end) {
            val chunkEnd = kotlin.comparisons.minOf(current + CHUNK_SIZE - 1, end)
            pendingChunks.offer(current..chunkEnd)
            current = chunkEnd + 1
        }
    }

    private fun isChunkValid(file: File, start: Long, end: Long): Boolean {
        return try {
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(start)
                val buffer = ByteArray(1)
                raf.read(buffer) != -1
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun getFileTotalSize(url: String, client: OkHttpClient): Long? {
        return try {
            val request = Request.Builder()
                .url(url)
                .head()
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    DownloaderManager.config.logger.e(TAG, "获取文件大小失败，响应码：${response.code}")
                    return null
                }
                response.header("Content-Length")?.toLong()
            }
        } catch (e: Exception) {
            DownloaderManager.config.logger.e(TAG, "获取文件大小异常", e)
            null
        }
    }

    private fun calculateProgress(downloaded: Long, total: Long): Int {
        return if (total > 0) ((downloaded * 100) / total).toInt() else 0
    }

    private fun calculateDownloadSpeed(): Long {
        val currentTime = System.currentTimeMillis()
        val currentBytes = completedBytes.get()

        speedHistory.offer(currentTime to currentBytes)

        while (speedHistory.isNotEmpty() && currentTime - speedHistory.peek()!!.first > 5000) {
            speedHistory.poll()
        }

        if (speedHistory.size < 2) return 0

        val firstRecord = speedHistory.peek()!!
        val deltaTime = currentTime - firstRecord.first
        val deltaBytes = currentBytes - firstRecord.second

        return if (deltaTime > 0) (deltaBytes * 1000 / deltaTime) else 0
    }

    private suspend fun moveTempFileToFinal(task: DownloadTaskEntity, totalSize: Long): String {
        val tempFile = File(task.tempFilePath)
        if (tempFile.length() != totalSize) {
            throw IOException("临时文件大小不符：${tempFile.length()} vs $totalSize")
        }
        val mimeType = task.fileName.getMimeType() ?: "application/octet-stream"
        return FileUtil.moveToPublicDirectory(
            context = DownloaderManager.context!!,
            sourceFile = tempFile,
            finalPath = task.filePath,
            fileName = task.fileName,
            mimeType = mimeType
        )
    }
}