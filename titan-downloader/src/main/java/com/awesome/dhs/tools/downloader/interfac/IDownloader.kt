package com.awesome.dhs.tools.downloader.interfac

import com.awesome.dhs.tools.downloader.model.DownloaderConfig
import com.awesome.dhs.tools.downloader.db.DownloadTaskEntity
import kotlinx.coroutines.flow.Flow
import com.awesome.dhs.tools.downloader.model.DownloadRequest

/**
 * FileName: IDownloader
 * Author: haosen
 * Date: 10/3/2025 3:57 AM
 * Description:
 **/


// 1. 定义清晰的公共接口
interface IDownloader {
    suspend fun enqueue(vararg requests: DownloadRequest)
    fun pause(vararg ids: Long)
    fun resume(vararg ids: Long)
    fun cancel(vararg ids: Long)
    fun getTask(id: Long): Flow<DownloadTaskEntity?>
    fun getTaskByUid(uid: String): Flow<DownloadTaskEntity?>
    fun getAllTasks(): Flow<List<DownloadTaskEntity>>
    fun addListener(listener: DownloadListener)
    fun removerListener(listener: DownloadListener)
    fun updateConfig(config: DownloaderConfig)
}
