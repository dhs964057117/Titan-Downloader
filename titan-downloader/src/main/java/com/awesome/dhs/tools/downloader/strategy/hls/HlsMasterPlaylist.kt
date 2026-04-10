package com.awesome.dhs.tools.downloader.strategy.hls

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/**
 * FileName: HlsMasterPlaylist
 * Author: haosen
 * Date: 1/25/2026 4:11 PM
 * Description:
 **/
@Serializable
data class HlsMasterPlaylist(
    val baseUri: String,
    val variants: List<HlsVariant>,
    val audios: List<HlsUrl>,
    val subtitles: List<HlsUrl>,
    val sessionKey: HlsEncryptionKey? = null,
)

@Serializable
data class HlsMediaPlaylist(
    val baseUri: String,
    val mediaSequence: Long,
    val targetDurationUs: Long,
    val hasEndTag: Boolean,
    val segments: List<HlsSegment>,
    val protectionSchemes: HlsEncryptionKey? = null,
)

@Serializable
data class HlsVariant(
    val url: String,
    val bandwidth: Int,
    val averageBandwidth: Int,
    val codecs: String?,
    val resolution: String?,
    val frameRate: Float?,
    val audioGroupId: String?,
    val subtitleGroupId: String?,
)

@Serializable
data class HlsUrl(
    val url: String,
    val name: String,
    val language: String?,
    val groupId: String?,
    val type: String, // AUDIO, SUBTITLES, CLOSED-CAPTIONS
)

@Serializable
data class HlsSegment(
    val url: String,
    val durationUs: Long,
    val title: String,
    val name: String,
    val relativeStartTimeUs: Long,
    val mediaSequence: Long, // 极其重要，用于生成 IV
    val encryptionKey: HlsEncryptionKey?, // 继承自上文
    val byteRange: HlsByteRange? = null,
    val isDiscontinuity: Boolean = false,
)

@Serializable
data class HlsByteRange(
    val offset: Long,
    val length: Long,
)

@Serializable
data class HlsEncryptionKey(
    val method: String, // NONE, AES-128, SAMPLE-AES
    val uri: String,
    val iv: String?,
    val keyFormat: String? = "identity",
    val keyFormatVersions: String? = "1",
    @Transient var loadedKeyBytes: ByteArray? = null, // 运行时缓存
) {
    // 自动处理 equals/hashcode 因为包含 ByteArray
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as HlsEncryptionKey
        if (method != other.method) return false
        if (uri != other.uri) return false
        if (iv != other.iv) return false
        if (loadedKeyBytes != null) {
            if (other.loadedKeyBytes == null) return false
            if (!loadedKeyBytes.contentEquals(other.loadedKeyBytes)) return false
        } else if (other.loadedKeyBytes != null) return false
        return true
    }

    override fun hashCode(): Int {
        var result = method.hashCode()
        result = 31 * result + uri.hashCode()
        result = 31 * result + (iv?.hashCode() ?: 0)
        result = 31 * result + (loadedKeyBytes?.contentHashCode() ?: 0)
        return result
    }
}