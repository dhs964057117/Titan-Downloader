package com.awesome.dhs.tools.downloader.strategy

import com.awesome.dhs.tools.downloader.db.DownloadTaskEntity
import com.awesome.dhs.tools.downloader.interfac.IDownloadStrategy


/**
 * FileName: DownloadStrategyFactory
 * Author: haosen
 * Date: 10/3/2025 4:47 AM
 * Description:
 **/
object DownloadStrategyFactory {
    fun getStrategy(task: DownloadTaskEntity): IDownloadStrategy {
        // 目前我们只支持 HTTP，未来可以根据 url 或 task.type 来判断
        // if (task.url.endsWith(".m3u8")) return HlsDownloadStrategy()
        return HttpDownloadStrategy()
    }
}