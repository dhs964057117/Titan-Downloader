package com.awesome.dhs.tools.downloader.strategy.hls

import kotlinx.serialization.Serializable


/**
 * FileName: M3u8Segment
 * Author: haosen
 * Date: 1/3/2026 10:07 PM
 * Description:
 **/
@Serializable
internal data class M3u8Segment(
    val url: String,
    val index: Int,
    val name: String,
    val key: M3u8Key?,
    val duration: Float,
)

@Serializable
internal data class M3u8InitSegment(
    val url: String,
    val name: String,
    // byteRange support can be added here
)

@Serializable
internal data class M3u8Key(
    val method: String,
    val uri: String,
    val iv: String?,
    var loadedKeyBytes: ByteArray? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as M3u8Key

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