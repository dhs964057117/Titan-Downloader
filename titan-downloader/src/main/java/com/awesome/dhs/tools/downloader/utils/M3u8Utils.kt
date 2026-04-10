package com.awesome.dhs.tools.downloader.utils

import java.net.URL
import java.util.regex.Pattern

/**
 * FileName: AppDatabase
 * Author: haosen
 * Date: 1/24/2026 10:55 AM
 * Description:
 **/
internal object M3u8Utils {
    // 支持解析 KEY="VALUE" 或 KEY=VALUE，处理逗号和引号
    fun parseAttribute(line: String, key: String): String? {
        // 匹配 KEY="VALUE"
        val patternQuote = Pattern.compile("$key=\"(.*?)\"")
        val matcherQuote = patternQuote.matcher(line)
        if (matcherQuote.find()) return matcherQuote.group(1)

        // 匹配 KEY=VALUE (直到逗号或行尾)
        val patternSimple = Pattern.compile("$key=([^,]+)")
        val matcherSimple = patternSimple.matcher(line)
        if (matcherSimple.find()) return matcherSimple.group(1)

        return null
    }

    fun resolveUrl(baseUrl: String, relativeUrl: String): String {
        if (relativeUrl.startsWith("http")) return relativeUrl
        val parent = baseUrl.substringBeforeLast("/", "")
        if (relativeUrl.startsWith("/")) {
            val url = URL(baseUrl)
            return "${url.protocol}://${url.host}${if (url.port != -1) ":" + url.port else ""}$relativeUrl"
        }
        return "$parent/$relativeUrl"
    }

    fun regexGet(content: String, patternStr: String): String? {
        val matcher = Pattern.compile(patternStr).matcher(content)
        return if (matcher.find()) matcher.group(1) else null
    }

    fun hexToBytes(s: String): ByteArray {
        val len = s.length
        val data = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            data[i / 2] = ((Character.digit(s[i], 16) shl 4) + Character.digit(s[i + 1], 16)).toByte()
            i += 2
        }
        return data
    }

    /**
     * 根据 Media Sequence Number 生成 IV (128-bit)
     * 只有当 EXT-X-KEY 中没有 IV 属性时才使用此方法
     */
    fun createIvFromSequenceNumber(sequenceNumber: Long): ByteArray {
        val iv = ByteArray(16)
        // 将 Long (8字节) 填入 iv 数组的最后 8 个字节，前 8 个字节为 0 (padding)
        // 大端序
        for (i in 0 until 8) {
            iv[15 - i] = (sequenceNumber shr (8 * i)).toByte()
        }
        return iv
    }

    fun <T> List<T>.medianWithOrNull(comparator: Comparator<T>): T? {
        if (isEmpty()) return null

        val sorted = this.sortedWith(comparator)
        return sorted[sorted.size / 2]
    }
}