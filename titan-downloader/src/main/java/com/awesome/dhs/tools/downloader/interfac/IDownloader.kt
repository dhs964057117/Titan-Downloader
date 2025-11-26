package com.awesome.dhs.tools.downloader.interfac

import androidx.paging.PagingData
import androidx.room.Index
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
    fun enqueue(vararg requests: DownloadRequest)
    fun pause(vararg ids: Long)
    fun resume(vararg ids: Long)
    fun cancel(vararg ids: Long)
    fun delete(vararg ids: Long, deleteFile: Boolean = true)
    fun getTask(id: Long): Flow<DownloadTaskEntity?>
    fun getTaskByUid(uid: String): Flow<DownloadTaskEntity?>
    fun getAllTasks(order: Index.Order = Index.Order.DESC): Flow<List<DownloadTaskEntity>>
    fun getUpdateTasks(order: Index.Order = Index.Order.DESC): Flow<List<DownloadTaskEntity>>
    fun getCompletedTasks(order: Index.Order = Index.Order.DESC): Flow<List<DownloadTaskEntity>>
    fun getAllTasksPaged(
        pageSize: Int = 20,
        order: Index.Order = Index.Order.DESC,
    ): Flow<PagingData<DownloadTaskEntity>>

    fun getUpdateTasksPaged(
        pageSize: Int,
        order: Index.Order = Index.Order.DESC,
    ): Flow<PagingData<DownloadTaskEntity>>

    fun getCompletedTasksPaged(
        pageSize: Int,
        order: Index.Order = Index.Order.DESC,
    ): Flow<PagingData<DownloadTaskEntity>>

    fun addListener(listener: DownloadListener)
    fun removerListener(listener: DownloadListener)
    fun updateConfig(config: (DownloaderConfig) -> DownloaderConfig)
}
