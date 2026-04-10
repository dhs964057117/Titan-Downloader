package com.awesome.dhs.tools.downloader.strategy.hls

import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import com.awesome.dhs.tools.downloader.Downloader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File

/**
 * HLS 混流工具类 (基于 AndroidX Media3 Transformer)
 * 支持视频 m3u8 自带音频的情况，智能决定是否混入外部音频。
 */
object HlsMuxer {

    private const val TAG = "HlsMuxer"

    sealed class MuxerResult {
        data class Success(val outFile: File) : MuxerResult()
        data class Error(val message: String, val exception: Exception? = null) : MuxerResult()
    }

    /**
     * 执行混流操作
     *
     * @param videoPlayerList 视频 m3u8 文件
     * @param audioPlayerList 音频 m3u8 文件（可为空）
     * @param outPath 输出路径（必须以 .mp4 结尾）
     * @param forceReplaceAudio 是否强制替换视频自带的音频（若为 true，即使视频有音频也会丢弃并混入外部音频）
     * @param onProgress 进度回调 (0.0 - 1.0)
     */
    @OptIn(UnstableApi::class)
    suspend fun mixVideoAudio(
        videoPlayerList: File,
        audioPlayerList: File,
        outPath: String,
        forceReplaceAudio: Boolean = false,
        onProgress: ((Float) -> Unit)? = null,
    ): MuxerResult = withContext(Dispatchers.IO) {

        val outputFile = File(outPath)
        try {
            // 1. 检测视频是否自带音频
            val videoHasAudio = hasAudioTrackInVideo(videoPlayerList)
            Log.d(TAG, "Video has audio: $videoHasAudio")

            // 2. 决定实际使用的音频源
            val effectiveAudioSource = when {
                !audioPlayerList.exists() -> null
                videoHasAudio && !forceReplaceAudio -> {
                    Log.w(
                        TAG,
                        "Video already contains audio, ignoring external audio. Use forceReplaceAudio=true to replace."
                    )
                    null
                }

                else -> audioPlayerList
            }

            // 3. 时长匹配检查（仅当使用外部音频时）
            if (effectiveAudioSource != null) {
                val videoDuration = getDuration(videoPlayerList)
                val audioDuration = getDuration(audioPlayerList)
                val diff = kotlin.math.abs(videoDuration - audioDuration)
                if (diff > 2000) {
                    Log.w(
                        TAG,
                        "Audio duration ($audioDuration ms) vs Video duration ($videoDuration ms) mismatch: ${diff}ms diff. May cause A/V sync issues."
                    )
                }
            }

            // 4. 执行三级降级策略
            onProgress?.invoke(0.05f)

            // 策略 A: 高速模式 (Transmux)
            Log.d(TAG, "Attempt 1: High-Speed Transmuxing...")
            val resultA = runTransformerSafe(
                videoSource = videoPlayerList,
                externalAudio = effectiveAudioSource,
                outputFile = outputFile,
                allowTransmux = true,
                onProgress = onProgress
            )
            if (resultA is MuxerResult.Success) {
                Log.d(TAG, "High-Speed Transmuxing Success!")
                return@withContext resultA
            }
            val errorA = (resultA as MuxerResult.Error)
            Log.e(TAG, "Transmux failed: ${errorA.message}. Falling back to Re-encode...")

            // 策略 B: 兼容模式 (Re-encode)
            Log.d(TAG, "Attempt 2: Robust Re-encoding...")
            if (outputFile.exists()) outputFile.delete()
            onProgress?.invoke(0.1f)

            val resultB = runTransformerSafe(
                videoSource = videoPlayerList,
                externalAudio = effectiveAudioSource,
                outputFile = outputFile,
                allowTransmux = false,
                onProgress = onProgress
            )
            if (resultB is MuxerResult.Success) {
                Log.d(TAG, "Robust Re-encoding Success!")
                return@withContext resultB
            }
            val errorB = (resultB as MuxerResult.Error)
            Log.e(TAG, "Re-encode failed: ${errorB.message}.")

            // 策略 C: 视频专用降级（仅当外部音频导致编解码错误，且视频本身无音频时尝试）
            val isCodecError = errorB.message.contains("Codec", ignoreCase = true) ||
                    errorB.message.contains("AudioDecoder", ignoreCase = true)
            if (effectiveAudioSource != null && isCodecError && !videoHasAudio) {
                Log.w(TAG, "Attempt 3: Audio codec missing. Trying Video-Only export...")
                if (outputFile.exists()) outputFile.delete()
                onProgress?.invoke(0.1f)

                val resultC = runTransformerSafe(
                    videoSource = videoPlayerList,
                    externalAudio = null,   // 丢弃音频
                    outputFile = outputFile,
                    allowTransmux = false,
                    onProgress = onProgress
                )
                if (resultC is MuxerResult.Success) {
                    Log.d(TAG, "Video-Only Export Success!")
                    return@withContext resultC
                }
            }

            // 所有策略均失败
            return@withContext MuxerResult.Error(
                "混流失败 (已尝试所有策略).\n1. Transmux: ${errorA.message}\n2. Re-encode: ${errorB.message}",
                errorB.exception ?: errorA.exception
            )

        } catch (e: Exception) {
            Log.e(TAG, "Fatal error during muxing", e)
            return@withContext MuxerResult.Error("混流严重错误: ${e.message}", e)
        }
    }

    /**
     * 安全执行 Transformer 导出
     *
     * @param videoSource 视频 m3u8 文件
     * @param externalAudio 外部音频 m3u8（可为 null，若提供则会自动丢弃视频原始音频）
     * @param outputFile 输出文件
     * @param allowTransmux true = 直接复制（快）, false = 重编码（稳）
     * @param onProgress 进度回调
     */
    @OptIn(UnstableApi::class)
    private suspend fun runTransformerSafe(
        videoSource: File,
        externalAudio: File?,
        outputFile: File,
        allowTransmux: Boolean,
        onProgress: ((Float) -> Unit)?,
    ): MuxerResult = withContext(Dispatchers.Main) {
        try {
            val context = Downloader.context

            // 1. 构建视频 MediaItem
            val videoItem = MediaItem.Builder()
                .setUri(Uri.fromFile(videoSource))
                .setMimeType(MimeTypes.APPLICATION_M3U8)
                .build()

            // 如果有外部音频，则丢弃视频自带的音频；否则保留
            val removeVideoAudio = externalAudio != null
            val videoEditedItem = EditedMediaItem.Builder(videoItem)
                .setRemoveAudio(removeVideoAudio)
                .build()

            // 2. 构建音频 MediaItem（如果有外部音频）
            val audioEditedItem = externalAudio?.let {
                val audioItem = MediaItem.Builder()
                    .setUri(Uri.fromFile(it))
                    .setMimeType(MimeTypes.APPLICATION_M3U8)
                    .build()
                EditedMediaItem.Builder(audioItem)
                    .setRemoveVideo(true)
                    .build()
            }

            // 3. 构建 Composition —— 关键修改点
            val sequences = mutableListOf<EditedMediaItemSequence>()
            if (externalAudio != null) {
                // 有外部音频：视频序列（移除原音频）+ 音频序列
                sequences.add(EditedMediaItemSequence.withVideoFrom(listOf(videoEditedItem)))
                sequences.add(EditedMediaItemSequence.withAudioFrom(listOf(audioEditedItem!!)))
            } else {
                // 无外部音频：保留原始视频的所有轨道（包括音频）
                sequences.add(EditedMediaItemSequence.withAudioAndVideoFrom(listOf(videoEditedItem)))
            }

            val composition = Composition.Builder(sequences)
                .setTransmuxVideo(allowTransmux)
                .setTransmuxAudio(allowTransmux)
                .build()

            // 4. 构建 Transformer
            val transformer = Transformer.Builder(context).build()

            // 5. 使用 callbackFlow 监听结果（进度回调部分不变）
            return@withContext callbackFlow {
                val listener = object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        trySend(MuxerResult.Success(outputFile))
                        close()
                    }

                    override fun onError(
                        composition: Composition,
                        exportResult: ExportResult,
                        exportException: ExportException,
                    ) {
                        trySend(
                            MuxerResult.Error(
                                "Export failed: ${exportException.message}",
                                exportException
                            )
                        )
                        close()
                    }
                }

                transformer.addListener(listener)
                transformer.start(composition, outputFile.absolutePath)

                // 进度轮询
                val progressHolder = ProgressHolder()
                val progressJob = launch {
                    while (isActive) {
                        val progressState = transformer.getProgress(progressHolder)
                        if (progressState == Transformer.PROGRESS_STATE_AVAILABLE) {
                            val progress = progressHolder.progress / 100f
                            onProgress?.invoke(progress)
                            Log.d(TAG, "Export progress: $progress")
                        } else {
                            Log.d(TAG, "Progress state: $progressState")
                        }
                        delay(500)
                    }
                }

                awaitClose {
                    progressJob.cancel()
                    transformer.removeListener(listener)
                }
            }.first()

        } catch (e: Exception) {
            return@withContext MuxerResult.Error("Transformer init failed: ${e.message}", e)
        }
    }

    // ==================== 辅助函数 ====================

    /**
     * 判断视频 m3u8 是否包含音频轨道（通过解析第一个 TS 分片）
     */
    private fun hasAudioTrackInVideo(videoM3u8: File): Boolean {
        var extractor: MediaExtractor? = null
        return try {
            val firstTsFile = getFirstTsFileFromM3u8(videoM3u8) ?: return false
            extractor = MediaExtractor()
            extractor.setDataSource(firstTsFile.absolutePath)
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME)
                if (mime?.startsWith("audio/") == true) {
                    return true
                }
            }
            false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to detect audio track: ${e.message}")
            false
        } finally {
            extractor?.release()
        }
    }

    /**
     * 从 m3u8 文件中提取第一个 TS 分片的 File 对象
     */
    private fun getFirstTsFileFromM3u8(m3u8: File): File? {
        return try {
            BufferedReader(m3u8.bufferedReader()).use { reader ->
                val firstTsLine = reader.lineSequence()
                    .firstOrNull { line -> line.trim().endsWith(".ts") && !line.startsWith("#") }
                if (firstTsLine != null) {
                    val tsPath = firstTsLine.trim()
                    val tsFile = if (tsPath.startsWith("/")) File(tsPath)
                    else File(m3u8.parentFile, tsPath)
                    if (tsFile.exists()) tsFile else null
                } else null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse m3u8: ${e.message}")
            null
        }
    }

    /**
     * 获取媒体文件的时长（毫秒）
     * 注意：对于 m3u8，此方法可能不稳定，仅作为参考。
     */
    private fun getDuration(file: File): Long {
        var retriever: MediaMetadataRetriever? = null
        return try {
            retriever = MediaMetadataRetriever()
            retriever.setDataSource(file.absolutePath)
            val durationStr =
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            durationStr?.toLongOrNull() ?: 0L
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get duration: ${e.message}")
            0L
        } finally {
            retriever?.release()
        }
    }
}