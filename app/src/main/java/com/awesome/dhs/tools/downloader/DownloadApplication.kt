package com.awesome.dhs.tools.downloader

import android.app.Application
import android.content.Context
import android.os.Environment
import android.util.Log
import com.awesome.dhs.tools.downloader.interfac.ILogger
import com.awesome.dhs.tools.downloader.model.DownloaderConfig


/**
 * FileName: DownloadApplication
 * Author: haosen
 * Date: 10/3/2025 7:51 AM
 * Description:
 **/
class DownloadApplication : Application() {
    companion object {
        lateinit var context: Context
    }

    override fun onCreate() {
        super.onCreate()
        context = this
        // 使用 Builder 创建配置
        val downloaderConfig = DownloaderConfig.Builder(this)
            .setFinalDirectory(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).absolutePath)
            .setMaxConcurrentDownloads(5)
//            .setHttpClient()
            .setLogger(TimberLogger()) // 使用我们自己实现的 Logger
            .build()

        // 初始化 Downloader 单例
        DownloaderManager.initialize(this, downloaderConfig)
    }

}

class TimberLogger : ILogger {
    override fun d(tag: String, message: String) {
        Log.d(tag, message)
    }

    override fun e(tag: String, message: String, throwable: Throwable?) {
        Log.e(tag, message, throwable)
    }
}