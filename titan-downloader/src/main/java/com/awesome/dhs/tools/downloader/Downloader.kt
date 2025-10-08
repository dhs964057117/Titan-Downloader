package com.awesome.dhs.tools.downloader

import android.annotation.SuppressLint
import android.content.Context
import com.awesome.dhs.tools.downloader.DownloaderManager.Companion.instance
import com.awesome.dhs.tools.downloader.core.DownloadRepositoryImpl
import com.awesome.dhs.tools.downloader.core.DownloaderImpl
import com.awesome.dhs.tools.downloader.db.AppDatabase
import com.awesome.dhs.tools.downloader.interfac.IDownloader
import com.awesome.dhs.tools.downloader.model.DownloaderConfig


/**
 * FileName: Downloader
 * Author: haosen
 * Date: 10/3/2025 5:15 AM
 * Description:
 **/
val Downloader by lazy { instance() }

@SuppressLint("StaticFieldLeak")
class DownloaderManager private constructor() : IDownloader by DownloaderImpl.getInstance() {

    companion object {
        @Volatile
        private var _instance: DownloaderManager? = null

        internal fun instance(): DownloaderManager {
            if (context == null) {
                throw IllegalStateException("must call initialize first")
            }
            return _instance ?: synchronized(this) {
                _instance ?: DownloaderManager().also {
                    _instance = it
                }
            }
        }

        internal var context: Context? = null
        internal lateinit var config: DownloaderConfig
        internal val repository by lazy {
            DownloadRepositoryImpl(AppDatabase.instance(context!!, "downloader.db").downloadDao())
        }

        /**
         * Initializes the downloader framework. This must be called once, typically in your Application class.
         * @param context The application context.
         * @param config The configuration for the downloader.
         * @throws IllegalStateException if already initialized.
         */
        fun initialize(context: Context, config: DownloaderConfig) {
            synchronized(this) {
                if (Companion.context != null) {
                    throw IllegalStateException("Downloader is already initialized.")
                }
                Companion.context = context
                Companion.config = config
            }
        }

        internal fun impl(): DownloaderImpl = DownloaderImpl.getInstance()
    }

    override fun updateConfig(config: DownloaderConfig) {
        Companion.config = config
        impl().updateConfig(config)
    }
}