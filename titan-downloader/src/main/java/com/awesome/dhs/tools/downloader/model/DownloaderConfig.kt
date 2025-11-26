package com.awesome.dhs.tools.downloader.model

import android.app.PendingIntent
import android.content.Context
import android.os.Environment
import com.awesome.dhs.tools.downloader.interfac.ILogger
import com.awesome.dhs.tools.downloader.interfac.INotificationProvider
import com.awesome.dhs.tools.downloader.interfac.NoOpLogger
import com.awesome.dhs.tools.downloader.notification.DefaultNotificationProvider
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
    val httpClient: OkHttpClient,
    val notificationProvider: INotificationProvider,
    val notificationClickIntent: PendingIntent?,
) {
    companion object {
        const val CONNECT_TIMEOUT = 30L
        const val READ_TIMEOUT = 5 * 60L
        const val WRITE_TIMEOUT = 30L
    }

    /**
     * Builder for creating a [DownloaderConfig] instance.
     */
    class Builder(private val context: Context) {
        private var maxConcurrentDownloads: Int = 3
        private var httpClient: OkHttpClient? = null
        private var finalDirectory: String =
            (context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                ?: File(context.filesDir, "downloads")).absolutePath // [CHANGE] File -> String

        private var logger: ILogger = NoOpLogger()
        private var notificationProvider: INotificationProvider? = null
        private var notificationClickIntent: PendingIntent? = null
        fun setHttpClient(client: OkHttpClient) = apply { this.httpClient = client }
        fun setMaxConcurrentDownloads(count: Int) = apply { this.maxConcurrentDownloads = count }
        fun setFinalDirectory(dirPath: String) =
            apply { this.finalDirectory = dirPath } // [CHANGE] File -> String

        fun setLogger(logger: ILogger) = apply { this.logger = logger }

        /**
         * 允许提供自定义的通知实现。
         */
        fun setNotificationProvider(provider: INotificationProvider) =
            apply { this.notificationProvider = provider }

        /**
         * 允许提供自定义的通知点击事件实现。
         */
        fun setNotificationClickIntent(notificationClickIntent: PendingIntent) =
            apply { this.notificationClickIntent = notificationClickIntent }

        fun build(): DownloaderConfig {
            val finalHttpClient = this.httpClient ?: OkHttpClient.Builder()
                .connectTimeout(CONNECT_TIMEOUT, TimeUnit.SECONDS)
                .readTimeout(READ_TIMEOUT, TimeUnit.SECONDS)
                .writeTimeout(WRITE_TIMEOUT, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build()

            // 如果未提供，则使用默认的
            val finalProvider = this.notificationProvider
                ?: DefaultNotificationProvider(context, finalHttpClient, notificationClickIntent)

            return DownloaderConfig(
                maxConcurrentDownloads = maxConcurrentDownloads,
                finalDirectory = finalDirectory,
                logger = logger,
                httpClient = finalHttpClient,
                notificationProvider = finalProvider,
                notificationClickIntent = notificationClickIntent,
            )
        }
    }
}