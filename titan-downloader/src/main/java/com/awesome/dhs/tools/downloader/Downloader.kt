package com.awesome.dhs.tools.downloader

import android.annotation.SuppressLint
import android.content.Context
import com.awesome.dhs.tools.downloader.DownloaderManager.Companion.getInstance
import com.awesome.dhs.tools.downloader.core.DownloadRepository
import com.awesome.dhs.tools.downloader.core.DownloadRepositoryImpl
import com.awesome.dhs.tools.downloader.core.DownloaderImpl
import com.awesome.dhs.tools.downloader.db.AppDatabase
import com.awesome.dhs.tools.downloader.interfac.IDownloader
import com.awesome.dhs.tools.downloader.model.DownloaderConfig
import kotlin.getValue


/**
 * FileName: Downloader
 * Author: haosen
 * Date: 10/3/2025 5:15 AM
 * Description: 公共下载器入口点。
 * 这是一个单例，必须在使用前通过 [initialize] 进行初始化。
 **/
val Downloader by lazy { getInstance() }

class DownloaderManager private constructor(
    internal val context: Context,
    internal var config: DownloaderConfig,
    internal val repository: DownloadRepository,
) : IDownloader by DownloaderImpl.getInstance(context, config, repository) {

    companion object {
        @SuppressLint("StaticFieldLeak")
        @Volatile
        private var _instance: DownloaderManager? = null

        /**
         * 获取 DownloaderManager 的单例实例。
         * @throws IllegalStateException 如果 [initialize] 未被调用。
         */
        @JvmStatic
        fun getInstance(): DownloaderManager {
            return _instance
                ?: throw IllegalStateException("DownloaderManager must be initialized first.")
        }

        /**
         * Initializes the downloader framework. This must be called once, typically in your Application class.
         * @param context The application context.
         * @param config The configuration for the downloader.
         * @throws IllegalStateException if already initialized.
         */
        @JvmStatic
        fun initialize(context: Context, config: DownloaderConfig) {
            synchronized(this) {
                if (_instance != null) {
                    throw IllegalStateException("DownloaderManager is already initialized.")
                }
                // 1. 创建核心依赖
                val appContext = context.applicationContext
                val database = AppDatabase.instance(appContext, "downloader.db")
                val repository = DownloadRepositoryImpl(database.downloadDao())

                // 2. 创建并启动单例
                _instance = DownloaderManager(appContext, config, repository)

                // 3. 启动核心服务，在初始化时就启动
                DownloaderImpl.getInstance(context, config, repository).startDispatcher()
            }
        }
    }

    /**
     * 内部访问 DownloaderImpl 的方法
     */
    internal fun impl(): DownloaderImpl = DownloaderImpl.getInstance(context, config, repository)

    /**
     * 允许在运行时更新配置
     */
    override fun updateConfig(config: (DownloaderConfig) -> DownloaderConfig) {
        val originConfig = this.config
        this.config = config.invoke(originConfig)
        impl().updateConfig(config)
    }
}