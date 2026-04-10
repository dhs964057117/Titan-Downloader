package com.awesome.dhs.tools.downloader.strategy

import com.awesome.dhs.tools.downloader.db.DownloadTaskEntity
import com.awesome.dhs.tools.downloader.interfac.IDownloadStrategy
import com.awesome.dhs.tools.downloader.strategy.hls.M3u8DownloadStrategy


/**
 * FileName: DownloadStrategyFactory
 * Author: haosen
 * Date: 10/3/2025 4:47 AM
 * Description:
 **/
object DownloadStrategyFactory {
    fun getStrategy(task: DownloadTaskEntity): IDownloadStrategy = when {
        // 1. 优先检测 M3U8
        isM3u8(task) -> M3u8DownloadStrategy()
        // 2. 默认 HTTP 下载
        else -> HttpDownloadStrategy()
    }

    // todo 后续增加更准确的判断方式
    private fun isM3u8(task: DownloadTaskEntity): Boolean {
        return (task.url.contains("m3u8", true) || task.url.contains("hls", true))
    }
}