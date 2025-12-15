package com.awesome.dhs.tools.downloader.strategy

import com.awesome.dhs.tools.downloader.DownloaderManager
import com.awesome.dhs.tools.downloader.db.DownloadTaskEntity
import com.awesome.dhs.tools.downloader.interfac.IDownloadStrategy
import com.awesome.dhs.tools.downloader.model.DownloadState
import com.awesome.dhs.tools.downloader.model.DownloadStatus
import com.awesome.dhs.tools.downloader.utils.FileNameResolver.getMimeType
import com.awesome.dhs.tools.downloader.utils.FileUtil
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
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
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * 动态小块分片下载策略（基于Chunk数据类的断点续传 + 预分配文件大小）
 * 核心：移除文件扫描逻辑，使用类型安全的Chunk类管理分片，仅依赖.config文件断点续传
 */
class HttpMultiDownloadStrategy : IDownloadStrategy {
    companion object {
        private const val TAG = "HttpMultiDownloadStrategy"
        private const val PROGRESS_UPDATE_INTERVAL = 1000L
        private const val CHUNK_SIZE = 1024 * 1024L
        private const val MAX_CHUNK_RETRY = 3
        private const val CONFIG_SUFFIX = ".download.config" // 断点配置文件后缀
    }

    /**
     * 分片信息模型（类型安全，替代原LongRange和字符串解析）
     */
    data class Chunk(
        @SerializedName("start") val start: Long, // 分片起始字节
        @SerializedName("end") val end: Long      // 分片结束字节
    ) {
        // 辅助方法：计算分片大小
        val size: Long get() = end - start + 1

        // 辅助方法：校验分片有效性（防止非法区间）
        fun isValid(totalFileSize: Long): Boolean {
            return start >= 0 && end < totalFileSize && end >= start
        }

        // 兼容日志/调试：转为字符串格式 "start-end"
        override fun toString(): String {
            return "$start-$end"
        }
    }

    // 断点配置模型（使用Chunk数据类）
    private data class DownloadConfig(
        @SerializedName("version") val version: Int = 1, // 配置版本（便于后续升级兼容）
        @SerializedName("totalSize") val totalSize: Long,
        @SerializedName("completedChunks") val completedChunks: List<Chunk>
    )

    private val gson = Gson()
    // 待下载分片队列（类型改为Chunk）
    private val pendingChunks = ConcurrentLinkedQueue<Chunk>()
    // 已完成字节数
    private val completedBytes = AtomicLong(0)
    // 文件总大小
    private var totalFileSize = 0L
    // 下载速度计算
    private val speedHistory = ConcurrentLinkedQueue<Pair<Long, Long>>()
    // 分片重试次数（Key改为Chunk）
    private val chunkRetryMap = ConcurrentHashMap<Chunk, Int>()
    // 主动暂停/取消标记
    private val isPausedOrCanceled = AtomicBoolean(false)

    override fun download(
        task: DownloadTaskEntity,
        client: OkHttpClient,
        stateChecker: suspend () -> DownloadStatus?,
    ): Flow<DownloadState> = channelFlow {
        // 初始化资源
        resetState()

        val tempFile = File(task.tempFilePath)
        val configFile = File("${task.tempFilePath}$CONFIG_SUFFIX")

        // 步骤1：获取文件总大小（优先用任务缓存，否则请求头获取）
        totalFileSize = task.totalBytes.takeIf { it > 0 } ?: getFileTotalSize(task.url, client) ?: run {
            send(DownloadState.Error("无法获取文件总大小，下载终止"))
            return@channelFlow
        }

        // 步骤2：预分配文件大小（核心：解决文件长度误判问题）
        if (!preAllocateFile(tempFile, totalFileSize)) {
            send(DownloadState.Error("文件预分配大小失败，下载终止"))
            return@channelFlow
        }

        // 步骤3：加载断点配置（仅依赖.config文件，无降级逻辑）
        val hasValidConfig = loadDownloadConfig(configFile)

        // 步骤4：初始化待下载分片（有配置则恢复，无则全新拆分）
        if (!hasValidConfig) {
            initNewDownloadChunks()
        }

        // 无待下载分片 → 直接完成
        if (pendingChunks.isEmpty()) {
            try {
                val finalPath = moveTempFileToFinal(task)
                configFile.delete() // 下载完成，删除配置文件
                send(DownloadState.Success)
                DownloaderManager.config.logger.d(TAG, "文件已完整，直接完成：$finalPath")
                return@channelFlow
            } catch (e: Exception) {
                send(DownloadState.Error("文件移动失败：${e.message}", e))
                return@channelFlow
            }
        }

        // 步骤5：核心下载逻辑
        try {
            executeDownload(task, client, stateChecker, this, configFile) { state ->
                send(state)
            }
        } catch (e: CancellationException) {
            // 暂停/取消：保存配置 + 标记状态
            isPausedOrCanceled.set(true)
            saveDownloadConfig(configFile)
            DownloaderManager.config.logger.d(TAG, "任务主动暂停/取消：${e.message}")
            send(DownloadState.Paused)
        } catch (e: Exception) {
            if (!isPausedOrCanceled.get()) {
                // 下载失败：保存配置（便于重试）
                saveDownloadConfig(configFile)
                DownloaderManager.config.logger.e(TAG, "下载异常", e)
                send(DownloadState.Error("下载失败：${e.message}", e))
            }
        }

    }.flowOn(Dispatchers.IO)
        .onCompletion {
            // 清理资源
            resetState()
        }

    /**
     * 重置状态（复用/清理时调用）
     */
    private fun resetState() {
        pendingChunks.clear()
        completedBytes.set(0)
        speedHistory.clear()
        chunkRetryMap.clear()
        isPausedOrCanceled.set(false)
    }

    /**
     * 预分配文件大小（下载前强制占位）
     * @return true：成功；false：失败
     */
    private fun preAllocateFile(tempFile: File, totalSize: Long): Boolean {
        return try {
            // 父目录不存在则创建
            tempFile.parentFile?.mkdirs()

            RandomAccessFile(tempFile, "rw").use { raf ->
                raf.setLength(totalSize) // 强制设置文件总大小
                raf.fd.sync() // 刷盘确保生效
            }
            DownloaderManager.config.logger.d(TAG, "文件预分配大小成功：${tempFile.path}，大小：$totalSize 字节")
            true
        } catch (e: Exception) {
            DownloaderManager.config.logger.e(TAG, "文件预分配大小失败", e)
            false
        }
    }

    /**
     * 加载断点配置文件
     * @return true：加载成功（有有效配置）；false：无配置/配置无效
     */
    private fun loadDownloadConfig(configFile: File): Boolean {
        if (!configFile.exists()) {
            DownloaderManager.config.logger.d(TAG, "无.config文件，执行全新下载")
            return false
        }

        return try {
            // 读取并解析配置文件
            val configContent = configFile.readText(StandardCharsets.UTF_8)
            val downloadConfig = gson.fromJson(configContent, DownloadConfig::class.java)

            // 校验配置有效性（总大小必须匹配）
            if (downloadConfig.totalSize != totalFileSize) {
                DownloaderManager.config.logger.d(TAG, "配置文件总大小不匹配，废弃：配置=${downloadConfig.totalSize}，实际=$totalFileSize")
                configFile.delete()
                return false
            }

            // 恢复已完成分片（过滤无效分片）
            val completedChunks = mutableListOf<Chunk>()
            var restoredCompletedBytes = 0L
            downloadConfig.completedChunks.forEach { chunk ->
                if (chunk.isValid(totalFileSize)) {
                    completedChunks.add(chunk)
                    restoredCompletedBytes += chunk.size
                } else {
                    DownloaderManager.config.logger.d(TAG, "无效分片区间，跳过：$chunk")
                }
            }

            // 初始化待下载分片（总分片 - 已完成分片）
            val allChunks = mutableListOf<Chunk>()
            splitIntoChunks(0L, totalFileSize - 1, allChunks)

            // 过滤出待下载分片（未完成的）
            pendingChunks.addAll(allChunks.filter { chunk ->
                !completedChunks.any { it.start == chunk.start && it.end == chunk.end }
            })

            // 更新已完成字节数
            completedBytes.set(restoredCompletedBytes)
            DownloaderManager.config.logger.d(TAG, "断点配置加载成功：已完成${completedChunks.size}个分片，待下载${pendingChunks.size}个分片，已完成字节：$restoredCompletedBytes")
            true
        } catch (e: Exception) {
            DownloaderManager.config.logger.e(TAG, "加载断点配置失败，执行全新下载", e)
            configFile.delete() // 删除损坏的配置文件
            false
        }
    }

    /**
     * 全新下载：初始化所有待下载分片
     */
    private fun initNewDownloadChunks() {
        pendingChunks.clear()
        splitIntoChunks(0L, totalFileSize - 1)
        DownloaderManager.config.logger.d(TAG, "全新下载：拆分出 ${pendingChunks.size} 个分片，总大小：$totalFileSize")
    }

    /**
     * 保存断点配置到文件（原子写入，防止损坏）
     */
    private fun saveDownloadConfig(configFile: File) {
        // 无有效进度则不保存
        if (totalFileSize <= 0 || completedBytes.get() <= 0 || pendingChunks.size == splitTotalChunksCount()) {
            DownloaderManager.config.logger.d(TAG, "无有效下载进度，不保存配置文件")
            return
        }

        try {
            // 收集已完成分片（总分片 - 待下载分片）
            val allChunks = mutableListOf<Chunk>()
            splitIntoChunks(0L, totalFileSize - 1, allChunks)

            val completedChunks = allChunks.filter { chunk ->
                !pendingChunks.contains(chunk)
            }

            // 构建配置对象
            val downloadConfig = DownloadConfig(
                totalSize = totalFileSize,
                completedChunks = completedChunks
            )

            // 原子写入（先写临时文件，再替换）
            val tempConfigFile = File("${configFile.path}.tmp")
            tempConfigFile.writeText(gson.toJson(downloadConfig), StandardCharsets.UTF_8)
            tempConfigFile.renameTo(configFile) // 原子替换，避免配置文件损坏

            DownloaderManager.config.logger.d(TAG, "断点配置保存成功：已完成${completedChunks.size}个分片")
        } catch (e: Exception) {
            DownloaderManager.config.logger.e(TAG, "保存断点配置失败", e)
        }
    }

    /**
     * 计算总分片数（辅助方法）
     */
    private fun splitTotalChunksCount(): Int {
        var count = 0
        var current = 0L
        while (current < totalFileSize) {
            val chunkEnd = minOf(current + CHUNK_SIZE - 1, totalFileSize - 1)
            count++
            current = chunkEnd + 1
        }
        return count
    }

    /**
     * 拆分区间为Chunk分片（核心：替换原LongRange）
     */
    private fun splitIntoChunks(
        start: Long,
        end: Long,
        collectToList: MutableList<Chunk>? = null
    ) {
        var current = start
        while (current <= end) {
            val chunkEnd = minOf(current + CHUNK_SIZE - 1, end)
            val chunk = Chunk(start = current, end = chunkEnd) // 创建Chunk对象
            if (collectToList != null) {
                collectToList.add(chunk)
            } else {
                pendingChunks.offer(chunk)
            }
            current = chunkEnd + 1
        }
    }

    /**
     * 核心下载执行逻辑
     */
    private suspend fun executeDownload(
        task: DownloadTaskEntity,
        client: OkHttpClient,
        stateChecker: suspend () -> DownloadStatus?,
        scope: CoroutineScope,
        configFile: File,
        onState: suspend (DownloadState) -> Unit
    ) {
        // 启动进度发射协程
        val progressJob = scope.launch(Dispatchers.IO) {
            while (isActive && completedBytes.get() < totalFileSize) {
                // 检查暂停/取消状态
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

        // 启动多线程下载
        val downloadJobs = mutableListOf<Job>()
        repeat(DownloaderManager.config.downloadThreadCount) { threadIndex ->
            val job = scope.launch(Dispatchers.IO) {
                // 每个线程独立创建RandomAccessFile，线程内复用
                var raf: RandomAccessFile? = null
                try {
                    val tempFile = File(task.tempFilePath)
                    // 线程初始化时创建RandomAccessFile（仅一次）
                    raf = RandomAccessFile(tempFile, "rw")
                    downloadWorker(threadIndex, task, raf, client, stateChecker, configFile)
                } catch (e: Exception) {
                    if (!isPausedOrCanceled.get()) throw e
                } finally {
                    // 线程退出时关闭流，防止句柄泄漏
                    raf?.close()
                    DownloaderManager.config.logger.d(TAG, "线程$threadIndex 关闭RandomAccessFile")
                }
            }
            downloadJobs.add(job)
        }

        // 等待所有下载线程完成
        downloadJobs.joinAll()
        progressJob.cancel()

        // 非暂停/取消状态下，校验下载完整性
        if (!isPausedOrCanceled.get()) {
            if (completedBytes.get() == totalFileSize) {
                val finalPath = moveTempFileToFinal(task)
                configFile.delete() // 下载成功，删除配置文件
                onState(DownloadState.Success)
                DownloaderManager.config.logger.d(TAG, "下载完成，最终路径：$finalPath")
            } else {
                saveDownloadConfig(configFile) // 下载不完整，保存配置
                onState(DownloadState.Error("文件下载不完整：${completedBytes.get()}/${totalFileSize}"))
            }
        }
    }

    /**
     * 单个下载线程逻辑（适配Chunk类型）
     */
    private suspend fun downloadWorker(
        threadIndex: Int,
        task: DownloadTaskEntity,
        raf: RandomAccessFile,
        client: OkHttpClient,
        stateChecker: suspend () -> DownloadStatus?,
        configFile: File
    ) {
        DownloaderManager.config.logger.d(TAG, "线程$threadIndex 启动，开始下载")

        while (pendingChunks.isNotEmpty() && !isPausedOrCanceled.get()) {
            val chunk = pendingChunks.poll() ?: break // 直接获取Chunk对象
            val (start, end) = chunk.start to chunk.end // 解构Chunk的起始/结束字节

            // 再次检查暂停/取消状态
            val currentStatus = stateChecker.invoke()
            if (currentStatus == DownloadStatus.PAUSED || currentStatus == DownloadStatus.CANCELED) {
                isPausedOrCanceled.set(true)
                pendingChunks.offer(chunk) // 放回Chunk对象
                break
            }

            try {
                // 下载单个分片
                downloadSingleChunk(threadIndex, task, client, raf, start, end)
                // 更新已完成字节数（直接用Chunk的size属性）
                completedBytes.addAndGet(chunk.size)
                // 实时保存断点配置（可选：可批量保存，减少IO）
                saveDownloadConfig(configFile)

                DownloaderManager.config.logger.d(TAG, "线程$threadIndex 完成分片：$chunk，已完成：${completedBytes.get()}/$totalFileSize")
            } catch (e: Exception) {
                if (isPausedOrCanceled.get()) break

                // 分片重试逻辑（Key改为Chunk）
                val retryCount = chunkRetryMap.getOrDefault(chunk, 0) + 1
                chunkRetryMap[chunk] = retryCount

                if (retryCount <= MAX_CHUNK_RETRY) {
                    pendingChunks.offer(chunk)
                    val delayTime = 1000L * retryCount
                    DownloaderManager.config.logger.d(TAG, "线程$threadIndex 分片$chunk 下载失败（第$retryCount 次），${delayTime}ms后重试：${e.message}")
                    delay(delayTime)
                } else {
                    val errorMsg = "线程$threadIndex 分片$chunk 下载失败（重试$MAX_CHUNK_RETRY 次）"
                    DownloaderManager.config.logger.e(TAG, errorMsg, e)
                    throw IOException(errorMsg, e)
                }
            }
        }

        DownloaderManager.config.logger.d(TAG, "线程$threadIndex 退出（暂停标记：${isPausedOrCanceled.get()}）")
    }

    /**
     * 下载单个分片（无修改，仅参数为Long类型）
     */
    private fun downloadSingleChunk(
        threadIndex: Int,
        task: DownloadTaskEntity,
        client: OkHttpClient,
        raf: RandomAccessFile,
        start: Long,
        end: Long
    ) {
        // 构建Range请求
        val request = Request.Builder()
            .url(task.url)
            .header("Range", "bytes=$start-$end")
            .apply { task.headers.forEach { (k, v) -> addHeader(k, v) } }
            .build()

        val response = client.newCall(request).execute()
        if (!response.isSuccessful || response.code != 206) {
            throw IOException("分片$start-$end 请求失败，响应码：${response.code}")
        }

        // 写入分片数据
        response.body.byteStream().use { inputStream ->
            raf.seek(start)
            val buffer = ByteArray(8 * 1024)
            var bytesRead: Int
            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                raf.write(buffer, 0, bytesRead)
            }
            raf.fd.sync() // 刷盘确保数据写入
        }
        response.close()
    }

    /**
     * 获取文件总大小（无修改）
     */
    private fun getFileTotalSize(url: String, client: OkHttpClient): Long? {
        return try {
            val request = Request.Builder().url(url).head().build()
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

    /**
     * 计算下载进度（无修改）
     */
    private fun calculateProgress(downloaded: Long, total: Long): Int {
        return if (total > 0) ((downloaded * 100) / total).toInt() else 0
    }

    /**
     * 计算下载速度（无修改）
     */
    private fun calculateDownloadSpeed(): Long {
        val currentTime = System.currentTimeMillis()
        val currentBytes = completedBytes.get()

        speedHistory.offer(currentTime to currentBytes)
        // 只保留5秒内的速度记录
        while (speedHistory.isNotEmpty() && currentTime - speedHistory.peek()!!.first > 5000) {
            speedHistory.poll()
        }

        if (speedHistory.size < 2) return 0

        val first = speedHistory.peek()!!
        val deltaTime = currentTime - first.first
        val deltaBytes = currentBytes - first.second

        return if (deltaTime > 0) (deltaBytes * 1000 / deltaTime) else 0
    }

    /**
     * 移动临时文件到最终路径（无修改）
     */
    private suspend fun moveTempFileToFinal(task: DownloadTaskEntity): String {
        val tempFile = File(task.tempFilePath)
        // 校验文件大小（预分配后理论上不会不匹配，防止极端情况）
        if (tempFile.length() != totalFileSize) {
            throw IOException("临时文件大小不符：实际=${tempFile.length()}，预期=$totalFileSize")
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