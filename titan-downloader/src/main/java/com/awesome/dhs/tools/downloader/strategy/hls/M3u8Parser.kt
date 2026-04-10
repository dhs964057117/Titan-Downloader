package com.awesome.dhs.tools.downloader.strategy.hls

import com.awesome.dhs.tools.downloader.Downloader
import com.awesome.dhs.tools.downloader.model.HLSSelector
import com.awesome.dhs.tools.downloader.utils.FileNameResolver.ILLEGAL_CHARACTERS_REGEX
import com.awesome.dhs.tools.downloader.utils.M3u8Utils
import com.awesome.dhs.tools.downloader.utils.M3u8Utils.medianWithOrNull
import com.awesome.dhs.tools.downloader.utils.NetUtil.downloadString
import kotlinx.serialization.Serializable
import okhttp3.OkHttpClient
import java.io.File
import java.io.IOException
import java.util.regex.Pattern

/**
 *
 * FileName: M3u8DownloadStrategy
 * Author: haosen
 * Date: 1/25/2026 5:21 PM
 * Description:M3U8 解析器，解析M3U8流的属性
 */
internal class M3u8Parser(
    private val videoPlayListCache: File,
    private val audioPlayListCache: File,
    private val client: OkHttpClient,
    private val headers: Map<String, String>,
) {
    companion object {
        private const val TAG = "M3u8Parser"
        private val REGEX_BANDWIDTH = Pattern.compile("BANDWIDTH=(\\d+)\\b")
        private val REGEX_AVERAGE_BANDWIDTH = Pattern.compile("AVERAGE-BANDWIDTH=(\\d+)\\b")
        private val REGEX_CODECS = Pattern.compile("CODECS=\"(.+?)\"")
        private val REGEX_RESOLUTION = Pattern.compile("RESOLUTION=(\\d+x\\d+)")
        private val REGEX_FRAME_RATE = Pattern.compile("FRAME-RATE=([\\d.]+)\\b")
        private val REGEX_AUDIO = Pattern.compile("AUDIO=\"(.+?)\"")
        private val REGEX_SUBTITLES = Pattern.compile("SUBTITLES=\"(.+?)\"")

        private val REGEX_URI = Pattern.compile("URI=\"(.+?)\"")
        private val REGEX_METHOD = Pattern.compile("METHOD=([A-Z0-9-]+)")
        private val REGEX_IV = Pattern.compile("IV=0x([0-9A-Fa-f]+)")
        private val REGEX_KEYFORMAT = Pattern.compile("KEYFORMAT=\"(.+?)\"")

        private val REGEX_BYTERANGE = Pattern.compile("#EXT-X-BYTERANGE:(\\d+)(?:@(\\d+))?")
        private val REGEX_MEDIA_SEQUENCE = Pattern.compile("#EXT-X-MEDIA-SEQUENCE:(\\d+)")
        private val REGEX_TARGET_DURATION = Pattern.compile("#EXT-X-TARGETDURATION:(\\d+)")
        const val AES_128 = "AES-128"
        const val SAMPLE_AES = "SAMPLE-AES"
    }

    @Serializable
    data class ParseResult(
        val video: HlsMediaPlaylist,
        val audio: HlsMediaPlaylist? = null,
        val subtitle: HlsMediaPlaylist? = null,
        val bandwidth: Int = 0,
        val averageBandwidth: Int = 0,
        val masterPlaylist: HlsMasterPlaylist? = null,
    )

    fun parse(url: String): ParseResult {
        val (url, content) = downloadString(url, client, headers)

        return if (content.contains("#EXT-X-STREAM-INF")) {
            parseMaster(content, url)
        } else {
            videoPlayListCache.writeText(content)
            val media = parseMedia(content, url)
            ParseResult(video = media)
        }
    }

    private fun parseMaster(content: String, baseUrl: String): ParseResult {
        val variants = ArrayList<HlsVariant>()
        val audios = ArrayList<HlsUrl>()
        val subtitles = ArrayList<HlsUrl>()
        val lines = content.lines()

        for (i in lines.indices) {
            val line = lines[i].trim()
            if (line.isEmpty()) continue

            if (line.startsWith("#EXT-X-MEDIA:")) {
                val type = M3u8Utils.parseAttribute(line, "TYPE")
                val groupId = M3u8Utils.parseAttribute(line, "GROUP-ID")
                val name = M3u8Utils.parseAttribute(line, "NAME") ?: "unknown"
                val uriStr = M3u8Utils.parseAttribute(line, "URI")
                val language = M3u8Utils.parseAttribute(line, "LANGUAGE")

                if (type != null && groupId != null) {
                    val fullUrl = if (uriStr != null) M3u8Utils.resolveUrl(baseUrl, uriStr) else ""
                    if (fullUrl.isNotEmpty()) {
                        val hlsUrl = HlsUrl(fullUrl, name, language, groupId, type)
                        if (type == "AUDIO") audios.add(hlsUrl)
                        if (type == "SUBTITLES") subtitles.add(hlsUrl)
                    }
                }
            } else if (line.startsWith("#EXT-X-STREAM-INF:")) {
                val bandwidth = parseStringAttr(line, REGEX_BANDWIDTH)?.toIntOrNull() ?: 0
                val avgBandwidth =
                    parseStringAttr(line, REGEX_AVERAGE_BANDWIDTH)?.toIntOrNull() ?: bandwidth
                val codecs = parseStringAttr(line, REGEX_CODECS)
                val resolution = parseStringAttr(line, REGEX_RESOLUTION)
                val frameRate = parseStringAttr(line, REGEX_FRAME_RATE)?.toFloatOrNull()
                val audioGroup = parseStringAttr(line, REGEX_AUDIO)
                val subtitleGroup = parseStringAttr(line, REGEX_SUBTITLES)

                val nextLine = lines.getOrNull(i + 1)?.trim()
                if (!nextLine.isNullOrEmpty() && !nextLine.startsWith("#")) {
                    val fullUrl = M3u8Utils.resolveUrl(baseUrl, nextLine)
                    variants.add(
                        HlsVariant(
                            fullUrl,
                            bandwidth,
                            avgBandwidth,
                            codecs,
                            resolution,
                            frameRate,
                            audioGroup,
                            subtitleGroup
                        )
                    )
                }
            }
        }

        val bestVariant = selectVariant(variants)
        Downloader.config.logger.d(TAG, "选择的bestVariant:$bestVariant")
        val (_, videoContent) = downloadString(bestVariant.url, client, headers)
        videoPlayListCache.writeText(videoContent)
        val videoTrack = parseMedia(videoContent, bestVariant.url)

        var audioTrack: HlsMediaPlaylist? = null
        if (bestVariant.audioGroupId != null) {
            val audioUrl = selectMediaUrl(audios, bestVariant.audioGroupId)
            if (audioUrl != null) {
                val (_, audioContent) = downloadString(audioUrl.url, client, headers)
                audioPlayListCache.writeText(audioContent)
                audioTrack = parseMedia(audioContent, audioUrl.url)
            }
        }

        var subTrack: HlsMediaPlaylist? = null
        if (bestVariant.subtitleGroupId != null) {
            val subUrl = selectMediaUrl(subtitles, bestVariant.subtitleGroupId)
            if (subUrl != null) {
                val (_, subContent) = downloadString(subUrl.url, client, headers)
                subTrack = parseMedia(subContent, subUrl.url)
            }
        }

        return ParseResult(
            video = videoTrack,
            audio = audioTrack,
            subtitle = subTrack,
            bandwidth = bestVariant.bandwidth,
            averageBandwidth = bestVariant.averageBandwidth,
            masterPlaylist = HlsMasterPlaylist(baseUrl, variants, audios, subtitles)
        )
    }

    private fun parseMedia(content: String, baseUrl: String): HlsMediaPlaylist {
        val lines = content.lines()
        val segments = ArrayList<HlsSegment>()

        var targetDurationUs = 0L
        var mediaSequence = 0L
        var hasEndTag = false

        // 全局/持久状态
        var currentKey: HlsEncryptionKey? = null
        var previousByteRangeEnd = 0L // 用于累加 ByteRange Offset

        // 分片临时状态 (每次遇到 URL 后重置)
        var currentDurationUs: Long = 0
        var currentTitle = ""
        var currentByteRangeLength: Long? = null
        var currentByteRangeOffset: Long? = null
        var isDiscontinuity = false
        var name = ""
        // 1. 预扫描头部信息
        val seqMatcher = REGEX_MEDIA_SEQUENCE.matcher(content)
        if (seqMatcher.find()) mediaSequence = seqMatcher.group(1)?.toLong() ?: 0
        var segmentMediaSequence = mediaSequence

        val durMatcher = REGEX_TARGET_DURATION.matcher(content)
        if (durMatcher.find()) targetDurationUs = (durMatcher.group(1)?.toLong() ?: 0) * 1000000

        // 2. 状态机循环解析
        for (line in lines) {
            val trimLine = line.trim()
            if (trimLine.isEmpty()) continue

            if (trimLine.startsWith("#")) {
                // 处理标签
                if (trimLine.startsWith("#EXTINF:")) {
                    val value = trimLine.substringAfter("#EXTINF:")
                    val parts = value.split(",", limit = 2)
                    val durationSec = parts.getOrNull(0)?.toDoubleOrNull() ?: 0.0
                    currentDurationUs = (durationSec * 1000000).toLong()
                    currentTitle = parts.getOrNull(1)?.trim() ?: ""
                } else if (trimLine.startsWith("#EXT-X-KEY:")) {
                    val method = parseStringAttr(trimLine, REGEX_METHOD) ?: "NONE"
                    if (method == "NONE") {
                        currentKey = null
                    } else {
                        val uri = parseStringAttr(trimLine, REGEX_URI)
                        val iv = parseStringAttr(trimLine, REGEX_IV)
                        val keyFormat = parseStringAttr(trimLine, REGEX_KEYFORMAT)
                        val fullKeyUrl = if (uri != null) M3u8Utils.resolveUrl(baseUrl, uri) else ""
                        currentKey = HlsEncryptionKey(method, fullKeyUrl, iv, keyFormat)
                    }
                } else if (trimLine.startsWith("#EXT-X-BYTERANGE:")) {
                    val matcher = REGEX_BYTERANGE.matcher(trimLine)
                    if (matcher.find()) {
                        currentByteRangeLength = matcher.group(1)?.toLong()
                        val offsetStr = matcher.group(2)
                        if (offsetStr != null) {
                            currentByteRangeOffset = offsetStr.toLong()
                        }
                    }
                } else if (trimLine.startsWith("#EXT-X-DISCONTINUITY")) {
                    isDiscontinuity = true
                } else if (trimLine.startsWith("#EXT-X-ENDLIST")) {
                    hasEndTag = true
                }
                // 重要：遇到不认识的 # 标签直接忽略，不影响状态累积
            } else {
                // 遇到非 # 开头的行 -> URL -> 创建分片
                val fullUrl = M3u8Utils.resolveUrl(baseUrl, trimLine)
                name = (trimLine.takeIf { !it.startsWith("http") && !it.startsWith("https") }
                    ?: trimLine.substringAfterLast("/", "")).replace(ILLEGAL_CHARACTERS_REGEX, "")
                // 计算 ByteRange
                var segmentByteRange: HlsByteRange? = null
                if (currentByteRangeLength != null) {
                    val start = currentByteRangeOffset ?: previousByteRangeEnd
                    segmentByteRange = HlsByteRange(start, currentByteRangeLength)

                    // 更新累加器，供下一个没有 Offset 的 ByteRange 使用
                    previousByteRangeEnd = start + currentByteRangeLength
                } else {
                    // 如果该分片没有 ByteRange，通常重置累加器 (视具体流而定，安全起见设为0)
                    previousByteRangeEnd = 0
                }

                // 相对时间计算 (简化处理，仅累加)
                // 实际相对时间 = sum(previous durations)
                val relativeStartTimeUs = segments.sumOf { it.durationUs }

                segments.add(
                    HlsSegment(
                        url = fullUrl,
                        durationUs = currentDurationUs,
                        title = currentTitle,
                        name = name,
                        relativeStartTimeUs = relativeStartTimeUs,
                        mediaSequence = segmentMediaSequence,
                        encryptionKey = currentKey,
                        byteRange = segmentByteRange,
                        isDiscontinuity = isDiscontinuity
                    )
                )

                // 重置分片级临时状态
                currentDurationUs = 0
                currentTitle = ""
                currentByteRangeLength = null
                currentByteRangeOffset = null
                isDiscontinuity = false

                segmentMediaSequence++
            }
        }

        return HlsMediaPlaylist(baseUrl, mediaSequence, targetDurationUs, hasEndTag, segments)
    }

    private fun parseStringAttr(line: String, pattern: Pattern): String? {
        val matcher = pattern.matcher(line)
        return if (matcher.find()) matcher.group(1) else null
    }

    private fun selectVariant(variants: List<HlsVariant>): HlsVariant {
        if (variants.isEmpty()) throw IOException("No variants found")
        val selector = Downloader.config.hlsSelector

        val comparator = compareBy<HlsVariant> {
            val res = it.resolution?.split("x")
            if (res != null && res.size == 2) res[0].toInt() * res[1].toInt() else 0
        }.thenBy { it.averageBandwidth }.thenBy { it.bandwidth }

        return when (selector) {
            HLSSelector.HIGH -> variants.maxWithOrNull(comparator)!!
            HLSSelector.LOW -> variants.minWithOrNull(comparator)!!
            HLSSelector.MEDIUM -> variants.medianWithOrNull(comparator)!!
        }
    }

    private fun selectMediaUrl(urls: List<HlsUrl>, groupId: String): HlsUrl? {
        val group = urls.filter { it.groupId == groupId }
        return group.find { false }
            ?: group.find { false }
            ?: group.firstOrNull()
    }
}