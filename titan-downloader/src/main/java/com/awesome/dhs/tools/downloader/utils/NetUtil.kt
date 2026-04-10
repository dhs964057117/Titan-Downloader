package com.awesome.dhs.tools.downloader.utils

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import kotlin.collections.component1
import kotlin.collections.component2
import kotlin.collections.forEach


/**
 * FileName: NetUtil
 * Author: haosen
 * Date: 1/7/2026 6:23 PM
 * Description:
 **/
object NetUtil {

    fun getFileHeaderSize(
        url: String,
        client: OkHttpClient,
        headers: Map<String, String>?,
    ): Long? {
        return try {
            val reqBuilder = Request.Builder().url(url).head()
            headers?.forEach { (k, v) -> reqBuilder.addHeader(k, v) }
            client.newCall(reqBuilder.build()).execute().use { response ->
                if (!response.isSuccessful) {
                    return null
                }
                response.header("Content-Length")?.toLong()
            }
        } catch (e: Exception) {
            null
        }
    }

    fun downloadString(url: String, client: OkHttpClient, headers: Map<String, String>?): Pair<String, String> {
        val request = Request.Builder().url(url).apply {
            headers?.forEach { (k, v) -> addHeader(k, v) }
        }.build()
        client.newCall(request).execute().use { response ->
            // 有些网站地址会重定向，获取真实的url
            val finalUrl = response.request.url.toUrl()
            if (!response.isSuccessful) throw IOException("Failed to download m3u8: $url code:${response.code}")
            return finalUrl.toString() to (response.body?.string() ?: "")
        }
    }

    fun downloadBytes(url: String, client: OkHttpClient, headers: Map<String, String>?): ByteArray {
        val request = Request.Builder().url(url).apply {
            headers?.forEach { (k, v) -> addHeader(k, v) }
        }.build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Failed to download bytes: $url")
            return response.body?.bytes() ?: ByteArray(0)
        }
    }
}