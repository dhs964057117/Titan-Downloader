package com.awesome.dhs.tools.downloader.core

import android.annotation.SuppressLint
import android.content.Context
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.room.Index
import androidx.work.WorkManager
import com.awesome.dhs.tools.downloader.db.DownloadTaskEntity
import com.awesome.dhs.tools.downloader.interfac.DownloadListener
import com.awesome.dhs.tools.downloader.interfac.IDownloader
import com.awesome.dhs.tools.downloader.model.DownloadRequest
import com.awesome.dhs.tools.downloader.model.DownloadStatus
import com.awesome.dhs.tools.downloader.model.DownloaderConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import java.util.concurrent.CopyOnWriteArrayList

/**
 * FileName: AppDatabase
 * Author: haosen
 * Date: 10/3/2025 4:36 AM
 * Description: A thin layer that receives public commands and delegates them to the DownloadDispatcher.
 * It is also responsible for creating initial DownloadTaskEntity objects.
 */
internal class DownloaderImpl(
    private var config: DownloaderConfig,
    private val repository: DownloadRepository,
    workManager: WorkManager,
) : IDownloader {

    companion object {
        @SuppressLint("StaticFieldLeak")
        @Volatile
        private var instance: DownloaderImpl? = null

        private const val TAG = "DownloaderImpl"
        fun getInstance(
            context: Context,
            config: DownloaderConfig,
            repository: DownloadRepository,
        ): DownloaderImpl {
            return (instance ?: synchronized(this) {
                instance ?: DownloaderImpl(
                    config,
                    repository,
                    WorkManager.getInstance(context),
                ).also {
                    instance = it
                }
            })
        }
    }

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // 核心组件 Dispatcher任务调度和通知调度
    val dispatcher: DownloadDispatcher = DownloadDispatcher(config, repository, workManager)
    private val listeners = CopyOnWriteArrayList<DownloadListener>()

    /**
     * 由 DownloaderManager 在初始化时调用。
     */
    fun startDispatcher() {
        dispatcher.start()
        config.logger.d("DownloaderImpl", "Core services started.")
    }

    override fun updateConfig(config:(DownloaderConfig)-> DownloaderConfig) {
        val originConfig = this.config
        this.config = config.invoke(originConfig)
        dispatcher.updateConfig(originConfig)
    }

    /**
     * Adds a listener to receive download task updates.
     * @param listener The listener to add.
     */
    override fun addListener(listener: DownloadListener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener)
        }
    }

    /**
     * Removes a previously added listener.
     * @param listener The listener to remove.
     */
    override fun removerListener(listener: DownloadListener) {
        listeners.remove(listener)
    }

    /**
     * Internal method to notify all registered listeners about a task update.
     */
    internal fun notifyListeners(task: DownloadTaskEntity) {
        // CopyOnWriteArrayList 使得遍历操作是线程安全的
        listeners.forEach {
            it.onTaskUpdated(task)
        }
    }

    override fun enqueue(vararg requests: DownloadRequest) {
        if (requests.isEmpty()) return
        scope.launch {
            val tasks = requests.map { request ->
                val time = System.currentTimeMillis()
                DownloadTaskEntity(
                    url = request.url,
                    // 文件路径和名称将在 PrepareWorker 中解析
                    filePath = request.filePath ?: "", // 可能是用户指定的完整路径，也可能只是目录
                    tempFilePath = "", // 待解析
                    fileName = request.fileName, // 可能是用户指定的，也可能为空
                    status = DownloadStatus.QUEUED, // 初始状态为排队中
                    createdTime = time,
                    updateTime = time,
                    uid = request.uid,
                    type = request.type,
                    source = request.source,
                    cover = request.cover,
                    duration = request.duration,
                    resolution = request.resolution,
                    extra = request.extra,
                    headers = request.headers,
                    tag = request.tag
                )
            }

            val insertedIds = repository.insert(*tasks.toTypedArray())
            config.logger.d(TAG, "$insertedIds")
            // 触发调度器检查新任务
            dispatcher.scheduleNext()
        }
    }

    // --- 所有控制方法都变为对 Dispatcher 的委托 ---

    override fun pause(vararg ids: Long) {
        if (ids.isEmpty()) return
        dispatcher.pause(ids.toList())
    }

    override fun resume(vararg ids: Long) {
        if (ids.isEmpty()) return
        dispatcher.resume(ids.toList())
    }

    override fun cancel(vararg ids: Long) {
        if (ids.isEmpty()) return
        dispatcher.cancel(ids.toList())
    }

    override fun delete(vararg ids: Long, deleteFile: Boolean) {
        if (ids.isEmpty()) return
        dispatcher.delete(ids.toList(), deleteFile)
    }

    override fun getTask(id: Long): Flow<DownloadTaskEntity?> {
        return repository.getByIdFlow(id)
    }

    override fun getTaskByUid(uid: String): Flow<DownloadTaskEntity?> {
        return repository.getByUidFlow(uid)
    }

    override fun getAllTasks(order: Index.Order): Flow<List<DownloadTaskEntity>> {
        return repository.getAllTasksFlow(order)
    }

    override fun getAllTasksPaged(
        pageSize: Int,
        order: Index.Order,
    ): Flow<PagingData<DownloadTaskEntity>> {
        return Pager(
            config = PagingConfig(
                pageSize = pageSize,
                enablePlaceholders = false,
                initialLoadSize = pageSize
            ),
            pagingSourceFactory = { repository.getAllTasksPaged(order) }
        ).flow
    }

    override fun getCompletedTasks(order: Index.Order): Flow<List<DownloadTaskEntity>> {
        return repository.getCompletedTasks(order)
    }

    override fun getCompletedTasksPaged(
        pageSize: Int,
        order: Index.Order,
    ): Flow<PagingData<DownloadTaskEntity>> {
        return Pager(
            config = PagingConfig(
                pageSize = pageSize,
                enablePlaceholders = false,
                initialLoadSize = pageSize
            ),
            pagingSourceFactory = { repository.getAllTasksPaged(order) }
        ).flow
    }

    override fun getUpdateTasks(order: Index.Order): Flow<List<DownloadTaskEntity>> {
        return repository.getUpdateTasks(order)
    }

    override fun getUpdateTasksPaged(
        pageSize: Int,
        order: Index.Order,
    ): Flow<PagingData<DownloadTaskEntity>> {
        return Pager(
            config = PagingConfig(
                pageSize = pageSize,
                enablePlaceholders = false,
                initialLoadSize = pageSize
            ),
            pagingSourceFactory = { repository.getAllTasksPaged(order) }
        ).flow
    }
}