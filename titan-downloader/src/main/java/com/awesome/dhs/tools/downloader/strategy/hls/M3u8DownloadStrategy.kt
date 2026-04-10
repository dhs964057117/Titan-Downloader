package com.awesome.dhs.tools.downloader.strategy.hls

import com.awesome.dhs.tools.downloader.Downloader
import com.awesome.dhs.tools.downloader.db.DownloadTaskEntity
import com.awesome.dhs.tools.downloader.interfac.IDownloadStrategy
import com.awesome.dhs.tools.downloader.model.DownloadState
import com.awesome.dhs.tools.downloader.strategy.hls.M3u8Parser.Companion.AES_128
import com.awesome.dhs.tools.downloader.strategy.hls.M3u8Parser.Companion.SAMPLE_AES
import com.awesome.dhs.tools.downloader.strategy.hls.M3u8Parser.ParseResult
import com.awesome.dhs.tools.downloader.utils.CookieSerializer.saveFromResponse
import com.awesome.dhs.tools.downloader.utils.FileUtil
import com.awesome.dhs.tools.downloader.utils.M3u8Utils
import com.awesome.dhs.tools.downloader.utils.NetUtil.downloadBytes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 *
 * FileName: M3u8DownloadStrategy
 * Author: haosen
 * Date: 1/1/2026 5:21 PM
 * Description:
 *
 * M3U8 下载策略
 * 支持：Master Playlist 自动选轨、AES-128 解密、音视频分离轨道下载、断点续传、MP4 混流
 */

class M3u8DownloadStrategy : IDownloadStrategy {

    companion object {
        const val TAG = "M3u8DownloadStrategy"
        const val MAX_BUFFER_SIZE = 4 * 1024 * 1024 //4MB
    }

    private fun logD(msg: String) = Downloader.config.logger.d(TAG, msg)
    private fun logE(msg: String) = Downloader.config.logger.e(TAG, msg)

    override fun download(
        task: DownloadTaskEntity,
        client: OkHttpClient,
    ): Flow<DownloadState> = channelFlow {
        logD("-->执行：M3u8DownloadStrategy")

        // 1. 初始化目录结构
        val tempDir = File(task.tempFilePath + "_hls")
        val mediaPlaylistCacheFile = File(tempDir, "parse_result.json") // 解析结果缓存

        val videoTempDir = File(tempDir, "video_ts").apply { mkdirs() }
        val audioTempDir = File(tempDir, "audio_ts").apply { mkdirs() }
        val subtitleTempDir = File(tempDir, "subtitle_ts").apply { mkdirs() }

        // 用于 Muxer 的本地 M3U8 文件
        val videoPlayListM3u8 = File(videoTempDir, "video.m3u8")
        val audioPlayListM3u8 = File(audioTempDir, "audio.m3u8")

        // 进度监控
        val completedSegmentsCount = AtomicInteger(0)
        val downloadSpeedBytes = AtomicLong(0)
        val lastSpeedTime = AtomicLong(System.currentTimeMillis())
        val completedBytes = AtomicLong(0)

        try {
            // 2. 解析 (支持断点恢复解析结果)
            send(DownloadState.InProgress(0, 0, 0, 0))
            val parser = M3u8Parser(videoPlayListM3u8, audioPlayListM3u8, client, task.headers)

            val parseResult: ParseResult = if (mediaPlaylistCacheFile.exists()) {
                try {
                    val json = mediaPlaylistCacheFile.readText()
                    Json.decodeFromString<ParseResult>(json).also {
                        logD("-->从本地缓存恢复 HLS 结构")
                    }
                } catch (e: Exception) {
                    logD("-->解析缓存无效，重新解析")
                    val result = parser.parse(task.url)
                    mediaPlaylistCacheFile.writeText(Json.encodeToString(result))
                    result
                }
            } else {
                logD("-->开始网络解析")
                val result = parser.parse(task.url)
                mediaPlaylistCacheFile.writeText(Json.encodeToString(result))
                result
            }

            // 3. 提取下载列表
            val videoSegments = parseResult.video.segments
            val audioSegments = parseResult.audio?.segments ?: emptyList()
            val subtitleSegments = parseResult.subtitle?.segments ?: emptyList()

            val totalSegments = videoSegments.size + audioSegments.size + subtitleSegments.size
            if (totalSegments == 0) throw IOException("No segments found in playlist")

            logD("-->解析完成: 视频[${videoSegments.size}], 音频[${audioSegments.size}], 字幕[${subtitleSegments.size}]")

            // 4. 预下载解密 Keys (Key 通常较小，预先下载到内存中)
            val allSegments = videoSegments + audioSegments
            val distinctKeys = allSegments.mapNotNull { it.encryptionKey }.distinctBy { it.uri }

            if (distinctKeys.isNotEmpty()) {
                logD("-->开始预下载 ${distinctKeys.size} 个解密Key")
                distinctKeys.forEach { key ->
                    if (key.loadedKeyBytes == null) {
                        key.loadedKeyBytes = downloadBytes(key.uri, client, task.headers)
                    }
                }
            }

            // 5. 预估总大小 (用于进度条)
            val initialStaticSize = estimateTotalSizeStatic(parseResult)
            val estimatedTotalSize = AtomicLong(initialStaticSize)
            val isDynamicEstimate = initialStaticSize <= 0L
            // 缓存所有轨道的总微秒时长，用于动态修正预估大小
            val totalMediaDurationUs = videoSegments.sumOf { it.durationUs } +
                    audioSegments.sumOf { it.durationUs } +
                    subtitleSegments.sumOf { it.durationUs }

            // 动态推算大小的累加器
            val downloadedSegmentBytes = AtomicLong(0L)
            val downloadedSegmentDurationUs = AtomicLong(0L)

            send(DownloadState.InProgress(0, 0, estimatedTotalSize.get(), 0))

            // 6. 并发下载逻辑
            val concurrency = 5
            val semaphore = Semaphore(concurrency)
            val exceptions = Collections.synchronizedList(ArrayList<Exception>())

            /**
             * 动态更新文件大小预估
             */
            fun updateDynamicSizeEstimate(segmentSize: Long, segmentDurationUs: Long) {
                val currentDur = downloadedSegmentDurationUs.addAndGet(segmentDurationUs)
                val currentBytes = downloadedSegmentBytes.addAndGet(segmentSize)

                // 如果一开始静态预估失败（等于 -1），则利用已经下载的数据动态推算出总大小
                if (isDynamicEstimate && currentDur > 0) {
                    val avgBytesPerUs = currentBytes.toDouble() / currentDur
                    val dynamicEstimate = (avgBytesPerUs * totalMediaDurationUs).toLong()
                    estimatedTotalSize.set(dynamicEstimate)
                    logD("-->动态更新预估大小：$dynamicEstimate")
                }
            }

            /**
             * 核心下载函数
             * @param targetFile 最终文件 (如 video_0.ts)
             */
            suspend fun downloadSegment(
                segment: HlsSegment,
                targetFile: File,
                client: OkHttpClient,
            ) {
                semaphore.withPermit {
                    if (exceptions.isNotEmpty()) return@withPermit

                    // A. 断点判断：如果 .ts 文件存在且 > 0，视为已完成 (Atomic Success)
                    if (targetFile.exists() && targetFile.length() > 0) {
                        val existingSize = targetFile.length()
                        completedBytes.addAndGet(existingSize)
                        completedSegmentsCount.incrementAndGet()
                        logD("-->${segment.name} 已下载，跳过")
                        // 即使跳过，也要利用本地文件的大小参与动态预估计算
                        updateDynamicSizeEstimate(existingSize, segment.durationUs)
                        return@withPermit
                    }

                    // B. 准备临时文件
                    val tempFile = File(targetFile.parent, targetFile.name)
                    if (tempFile.exists()) tempFile.delete() // 清理旧的脏文件

                    try {
                        val requestBuilder = Request.Builder().url(segment.url)
                        task.headers.forEach { (k, v) ->
                            if ("cookie".equals(k, true)) {
                                saveFromResponse(segment.url, v)
                            } else {
                                requestBuilder.addHeader(k, v)
                            }
                        }

                        // Byte Range 支持
                        if (segment.byteRange != null) {
                            val start = segment.byteRange.offset
                            val end = start + segment.byteRange.length - 1
                            requestBuilder.addHeader("Range", "bytes=$start-$end")
                        }

                        val response = client.newCall(requestBuilder.build()).execute()
                        if (!response.isSuccessful) throw IOException("Download failed: ${response.code} for ${segment.url}")

                        val body = response.body ?: throw IOException("Body is null")
                        val inputStream: InputStream = body.byteStream()
                        val fileOutputStream = FileOutputStream(tempFile)

                        // C. 配置流式解密 (CipherInputStream)
                        var finalInputStream = inputStream
                        val key = segment.encryptionKey
                        if (key != null && (AES_128.equals(key.method, true) || SAMPLE_AES.equals(
                                key.method,
                                true
                            ))
                        ) {
                            val keyBytes =
                                key.loadedKeyBytes ?: throw IOException("Key data missing")
                            // 优先显式 IV，否则使用 Sequence Number
                            val ivBytes = if (key.iv != null) {
                                M3u8Utils.hexToBytes(key.iv.replace("0x", ""))
                            } else {
                                M3u8Utils.createIvFromSequenceNumber(segment.mediaSequence)
                            }

                            val cipher = Cipher.getInstance("AES/CBC/PKCS7Padding")
                            val secretKeySpec = SecretKeySpec(keyBytes, "AES")
                            val ivParameterSpec = IvParameterSpec(ivBytes)
                            cipher.init(Cipher.DECRYPT_MODE, secretKeySpec, ivParameterSpec)

                            finalInputStream = CipherInputStream(inputStream, cipher)
                        }

                        // D. 流式写入 (8KB Buffer)
                        val buffer = ByteArray(MAX_BUFFER_SIZE)
                        var len: Int
                        try {
                            while (finalInputStream.read(buffer).also { len = it } != -1) {
                                fileOutputStream.write(buffer, 0, len)
                                val total = completedBytes.addAndGet(len.toLong())

                                // 节流进度发送
                                val now = System.currentTimeMillis()
                                val last = lastSpeedTime.get()
                                if (now - last > 1000 && lastSpeedTime.compareAndSet(last, now)) {
                                    val delta = total - downloadSpeedBytes.get()
                                    val speed = if (now > last) (delta * 1000 / (now - last)) else 0
                                    downloadSpeedBytes.set(total)

                                    val currentEstimate = estimatedTotalSize.get()
                                    val progress = if (currentEstimate > 0)
                                        ((total.toDouble() / currentEstimate) * 100).toInt()
                                            .coerceIn(0, 99)
                                    else 0

                                    send(
                                        DownloadState.InProgress(
                                            progress,
                                            total,
                                            currentEstimate,
                                            speed
                                        )
                                    )
                                }
                            }
                            fileOutputStream.flush()
                            fileOutputStream.fd.sync()
                        } finally {
                            logD("-->${segment.name} 下载完成！")
                            fileOutputStream.close()
                            finalInputStream.close()
                            body.close()
                        }

                        // E. 原子重命名：.dl -> .ts
                        if (tempFile.exists() && tempFile.length() > 0) {
                            val finalSize = tempFile.length()
                            if (!tempFile.renameTo(targetFile)) {
                                // 处理文件系统重命名失败的情况
                                FileUtil.moveToPublicLegacy(tempFile, targetFile.absolutePath)
                                tempFile.delete()
                            }
                            // 下载成功后，将实际大小计入动态推算库
                            updateDynamicSizeEstimate(finalSize, segment.durationUs)
                        } else {
                            throw IOException("Downloaded temp file missing or empty")
                        }

                        completedSegmentsCount.incrementAndGet()

                    } catch (e: Exception) {
                        exceptions.add(e)
                        if (tempFile.exists()) tempFile.delete() // 失败清理
                        throw e
                    }
                }
            }

            // 7. 启动下载任务
            coroutineScope {
                // 视频分片 (使用 index 命名以保证顺序)
                videoSegments.forEachIndexed { i, seg ->
                    launch(Dispatchers.IO) {
                        downloadSegment(seg, File(videoTempDir, seg.name), client)
                    }
                }
                // 音频分片
                audioSegments.forEachIndexed { i, seg ->
                    launch(Dispatchers.IO) {
                        downloadSegment(seg, File(audioTempDir, seg.name), client)
                    }
                }
                // 字幕分片
                subtitleSegments.forEachIndexed { i, seg ->
                    launch(Dispatchers.IO) {
                        downloadSegment(seg, File(subtitleTempDir, seg.name), client)
                    }
                }
            }

            if (exceptions.isNotEmpty()) {
                throw exceptions[0]
            }
            // 8.1 准备本地 M3U8 文件
            // 将原始 M3U8 中的网络 URL 替换为本地的 file:// 路径，供 Transformer 读取
            rewriteM3u8ToLocal(
                videoPlayListM3u8,
                videoTempDir,
                videoSegments.size
            )
            if (audioSegments.isNotEmpty() && audioPlayListM3u8.exists()) {
                rewriteM3u8ToLocal(
                    audioPlayListM3u8,
                    audioTempDir,
                    audioSegments.size
                )
            }
            // 8.2 混流 (Media3 Transformer)
            logD("-->下载完成，准备混流")
            send(DownloadState.InProgress(99, completedBytes.get(), estimatedTotalSize.get(), 0))

            val finalOutputFile = File(task.tempFilePath)
            if (finalOutputFile.exists()) finalOutputFile.delete()
            finalOutputFile.createNewFile()

            // 8.1 执行混流
            val muxResult = HlsMuxer.mixVideoAudio(
                videoPlayerList = videoPlayListM3u8,
                audioPlayerList = audioPlayListM3u8, // 空 File 表示无音频
                outPath = finalOutputFile.absolutePath
            ) { progress ->
                // 这里可以回调混流进度，暂时只打日志
                logD("Muxing... $progress")
            }

            if (muxResult is HlsMuxer.MuxerResult.Error) {
                throw muxResult.exception ?: IOException(muxResult.message)
            }

            // 9. 处理字幕 (移动/合并)
            if (subtitleSegments.isNotEmpty()) {
                // 简化处理：假设是 WebVTT，只处理第一个分片作为 Sidecar
                // 完整实现应将所有分片合并为一个文件
                val firstSub = File(subtitleTempDir, "sub_0.vtt")
                if (firstSub.exists()) {
                    val targetSubName = task.fileName.substringBeforeLast(".") + ".vtt"
                    FileUtil.moveToPublicDirectory(
                        Downloader.context,
                        firstSub,
                        task.filePath,
                        targetSubName
                    )
                }
            }

            send(DownloadState.Success)
            tempDir.deleteRecursively() // 可选：清理临时文件
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            logE("Error: ${e.message}")
            send(DownloadState.Error("M3U8 download failed: ${e.message}", e))
        }
    }

    /**
     * 重写 M3U8 文件：将分片的网络 URL 替换为本地文件路径
     * 使 Media3 Transformer 能够读取本地已下载的 TS 分片
     */
    private fun rewriteM3u8ToLocal(m3u8File: File, tsDir: File, count: Int) {
        if (!m3u8File.exists()) return

        val originalLines = m3u8File.readLines()
        val newContent = StringBuilder()
        var segmentIndex = 0

        val iterator = originalLines.iterator()
        while (iterator.hasNext()) {
            val line = iterator.next().trim()

            if (line.startsWith("#EXTINF")) {
                newContent.append(line).append("\n")
                // 下一行通常是 URL，但也可能是其他 Tag
                var nextLine = if (iterator.hasNext()) iterator.next().trim() else ""
                while (nextLine.startsWith("#") && iterator.hasNext()) {
                    newContent.append(nextLine).append("\n")
                    nextLine = iterator.next().trim()
                }

                // 此时 nextLine 应该是 URL，我们替换它
                if (segmentIndex < count) {
                    val localFile = File(tsDir, nextLine)
                    newContent.append(localFile.absolutePath).append("\n")
                    segmentIndex++
                }
            } else if (!line.startsWith("#") && line.isNotEmpty()) {
                // 这是一个 URL 行，已经在上面处理过了，跳过
                continue
            } else {
                // 其他标签保留
                newContent.append(line).append("\n")
            }
        }

        m3u8File.writeText(newContent.toString())
    }

    /**
     * 预估文件总大小
     * 策略：
     * 1. 优先检查分片是否包含 ByteRange (最高优先级，100%准确)
     * 2. 其次使用 Master Playlist 中的 BANDWIDTH (较准确)
     * 3. 如果都不满足返回 -1，交由下载过程中的动态修正推算
     */
    private fun estimateTotalSizeStatic(result: ParseResult): Long {
        try {
            // 策略 1: 检查是否是 ByteRange 模式 (单文件 HLS)
            // 如果分片包含 ByteRange，直接累加 length，100% 准确，无网络消耗
            val videoHasByteRange = result.video.segments.firstOrNull()?.byteRange != null
            if (videoHasByteRange) {
                val vSize = result.video.segments.sumOf { it.byteRange?.length ?: 0L }
                val aSize = result.audio?.segments?.sumOf { it.byteRange?.length ?: 0L } ?: 0L
                val sSize = result.subtitle?.segments?.sumOf { it.byteRange?.length ?: 0L } ?: 0L
                val total = vSize + aSize + sSize
                if (total > 0) {
                    logD("-->通过 ByteRange 精确计算总大小：$total")
                    return total
                }
            }

            // 策略 2: 使用 Playlist 中的 Bandwidth (如果是 Master 解析出来的)
            // bandwidth 是 bits per second -> bytes per second = bandwidth / 8
            if (result.bandwidth > 0) {
                val totalDurationSec =
                    result.video.segments.sumOf { it.durationUs.toDouble() } / 1_000_000.0
                val estimated = (totalDurationSec * (result.bandwidth / 8)).toLong()
                logD("-->通过 Bandwidth 估算文件大小：$estimated")
                if (estimated > 0) return estimated
            }
        } catch (e: Exception) {
            logE("Failed to statically estimate size: ${e.message}")
        }

        logD("-->无法静态预估大小，返回-1交由动态预估")
        return -1L // 无法预估，将启动下载并通过已下分片实时动态推算
    }
}