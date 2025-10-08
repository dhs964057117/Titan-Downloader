package com.awesome.dhs.tools.downloader.model

import android.content.Context
import android.os.Environment
import com.awesome.dhs.tools.downloader.interfac.ILogger
import com.awesome.dhs.tools.downloader.interfac.NoOpLogger
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * FileName: AppDatabase
 * Author: haosen
 * Date: 10/3/2025 5:10 AM
 * Description:
 **/
data class DownloaderConfig(
    val maxConcurrentDownloads: Int,
    val finalDirectory: String,
    val logger: ILogger,
    val httpClient: OkHttpClient
) {
    /**
     * Builder for creating a [DownloaderConfig] instance.
     */
    class Builder(context: Context) {
        private var maxConcurrentDownloads: Int = 3
        private var httpClient: OkHttpClient? = null
        private var finalDirectory: String =
            (context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                ?: File(context.filesDir, "downloads")).absolutePath // [CHANGE] File -> String

        fun setHttpClient(client: OkHttpClient) = apply { this.httpClient = client } // [NEW]

        private var logger: ILogger = NoOpLogger()

        fun setMaxConcurrentDownloads(count: Int) = apply { this.maxConcurrentDownloads = count }
        fun setFinalDirectory(dirPath: String) =
            apply { this.finalDirectory = dirPath } // [CHANGE] File -> String

        fun setLogger(logger: ILogger) = apply { this.logger = logger }

        fun build(): DownloaderConfig {
            return DownloaderConfig(
                maxConcurrentDownloads = maxConcurrentDownloads,
                finalDirectory = finalDirectory,
                logger = logger,
                httpClient = this.httpClient ?: OkHttpClient.Builder()
                    .connectTimeout(30, TimeUnit.SECONDS) // 连接超时
                    .readTimeout(5, TimeUnit.MINUTES)     // 读取超时，设置为较长时间以适应大文件和慢网络
                    .writeTimeout(30, TimeUnit.SECONDS)   // 写入超时
                    .retryOnConnectionFailure(true)       // 关键！允许连接失败时自动重试
                    .build()
            )
        }
    }
}