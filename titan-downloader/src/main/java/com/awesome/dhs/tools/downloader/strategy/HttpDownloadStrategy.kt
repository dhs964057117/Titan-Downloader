package com.awesome.dhs.tools.downloader.strategy

import com.awesome.dhs.tools.downloader.DownloaderManager
import com.awesome.dhs.tools.downloader.db.DownloadTaskEntity
import com.awesome.dhs.tools.downloader.interfac.IDownloadStrategy
import com.awesome.dhs.tools.downloader.model.DownloadState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 下载策略分发器（核心）
 * 1. 检测目标URL是否支持断点续传（Range请求）
 * 2. 支持 → 分发到多线程策略
 * 3. 不支持 → 分发到单线程策略
 */
class HttpDownloadStrategy : IDownloadStrategy {
    private val multiDownloadStrategy by lazy { HttpMultiDownloadStrategy() }
    private val singleDownloadStrategy by lazy { HttpSingleDownloadStrategy() }

    override fun download(
        task: DownloadTaskEntity,
        client: OkHttpClient): Flow<DownloadState> = flow {
        // 步骤1：检测是否支持断点续传（HEAD请求验证Accept-Ranges头）
        val isRangeSupported = checkRangeSupport(task, client)

        // 步骤2：分发策略并转发结果
        val downloadFlow = if (isRangeSupported) {
            // 支持断点续传 → 多线程下载
            multiDownloadStrategy.download(task, client)
        } else {
            // 不支持断点续传 → 单线程下载
            singleDownloadStrategy.download(task, client)
        }

        // 步骤3：转发具体策略的下载状态
        downloadFlow.collect { state ->
            emit(state)
        }
    }.flowOn(Dispatchers.IO)

    /**
     * 核心检测逻辑：验证URL是否支持Range请求（断点续传）
     */
    private fun checkRangeSupport(task: DownloadTaskEntity, client: OkHttpClient): Boolean {
        return try {
            val request = Request.Builder()
                .url(task.url)
                .head()
                // 添加 header
                .apply { task.headers.forEach { (k, v) -> addHeader(k, v) } }
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    // HEAD请求失败，降级到单线程
                    return false
                }
                // 关键：检查Accept-Ranges头（bytes表示支持，none表示不支持）
                val acceptRanges = response.header("Accept-Ranges") ?: "none"
                val isSupported = acceptRanges.equals("bytes", ignoreCase = true)

                // 额外校验：部分服务器返回Content-Length但不支持Range，需二次验证
                if (isSupported) {
                    val contentLength = response.header("Content-Length")?.toLongOrNull() ?: 0L
                    // 赋值 task,后面不需要再请求了contentLength
                    task.totalBytes = contentLength
                    // 小文件（<10MB）直接用单线程，避免多线程开销
                    return contentLength > 10 * 1024 * 1024L
                }
                return false
            }
        } catch (e: Exception) {
            // 任何异常 → 降级到单线程
            false
        }
    }
}