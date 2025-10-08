package com.awesome.dhs.tools.downloader.core

import android.annotation.SuppressLint
import androidx.work.WorkManager
import com.awesome.dhs.tools.downloader.DownloaderManager
import com.awesome.dhs.tools.downloader.db.DownloadTaskEntity
import com.awesome.dhs.tools.downloader.interfac.DownloadListener
import com.awesome.dhs.tools.downloader.interfac.IDownloader
import com.awesome.dhs.tools.downloader.model.DownloadRequest
import com.awesome.dhs.tools.downloader.model.DownloaderConfig
import com.awesome.dhs.tools.downloader.notification.NotificationController
import com.awesome.dhs.tools.downloader.utils.FileNameResolver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * FileName: AppDatabase
 * Author: haosen
 * Date: 10/3/2025 4:36 AM
 * Description: A thin layer that receives public commands and delegates them to the DownloadDispatcher.
 * It is also responsible for creating initial DownloadTaskEntity objects.
 */
internal class DownloaderImpl(
    private val repository: DownloadRepository = DownloaderManager.Companion.repository,
    private val httpClient: OkHttpClient = DownloaderManager.Companion.config.httpClient,
    workManager: WorkManager = WorkManager.getInstance(DownloaderManager.Companion.context!!)
) : IDownloader {

    companion object {
        @SuppressLint("StaticFieldLeak")
        @Volatile
        private var instance: DownloaderImpl? = null

        fun getInstance(): DownloaderImpl {
            return (instance ?: synchronized(this) {
                instance ?: DownloaderImpl().also {
                    instance = it
                }
            })
        }
    }

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // ---  创建 Dispatcher, 它是新的核心 ---
    val dispatcher: DownloadDispatcher =
        DownloadDispatcher(DownloaderManager.Companion.config, repository, workManager)
    val notificationController: NotificationController =
        NotificationController(DownloaderManager.Companion.context!!, httpClient)
    private val listeners = CopyOnWriteArrayList<DownloadListener>()

    init {
        // --- 启动时从数据库恢复 Dispatcher 的内存状态 ---
        scope.launch {
            dispatcher.rehydrate()
            dispatcher.taskUpdateEventFlow.collect { updatedTask ->
                // 当收到来自 Dispatcher 的更新事件时，通知所有外部监听器
                notifyListeners(updatedTask)
            }
        }
    }

    override fun updateConfig(config: DownloaderConfig) {
        dispatcher.updateConfig(config)
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

    override suspend fun enqueue(vararg requests: DownloadRequest) {
        if (requests.isEmpty()) {
            return
        }

        // 1. 准备阶段：并发地创建所有 Task 实体
        val taskEntities = coroutineScope {
            requests.map { request ->
                async {
                    val resolved = FileNameResolver.resolve(
                        request = request,
                        globalFinalDir = dispatcher.config.finalDirectory,
                        client = httpClient
                    )
                    val time = System.currentTimeMillis()

                    val tempDir = File(DownloaderManager.Companion.context!!.cacheDir, "downloads")

                    // 2. 确保这个稳定目录存在
                    if (!tempDir.exists()) {
                        tempDir.mkdirs()
                    }

                    // 3. 在这个稳定目录中创建临时文件
                    val tempFile = File.createTempFile("download_", ".tmp", tempDir)


                    DownloadTaskEntity(
                        url = request.url,
                        filePath = resolved.finalPath,
                        tempFilePath = tempFile.absolutePath,
                        fileName = resolved.fileName,
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
            }.awaitAll()
        }

        // 2. 持久化：将新任务批量写入数据库
        val newIds = repository.insert(*taskEntities.toTypedArray())
        val newTasksWithIds = repository.getByIds(newIds) // 获取带有ID的实体

        // 3. 委托：将新创建的任务交给 Dispatcher 处理
        dispatcher.enqueue(newTasksWithIds)

//        return newIds
    }

    // --- 所有控制方法都变为对 Dispatcher 的委托 ---

    override fun pause(vararg ids: Long) {
        if (ids.isNotEmpty()) dispatcher.pause(ids.toList())
    }

    override fun resume(vararg ids: Long) {
        if (ids.isNotEmpty()) dispatcher.resume(ids.toList())
    }

    override fun cancel(vararg ids: Long) {
        if (ids.isNotEmpty()) dispatcher.cancel(ids.toList())
    }

    override fun getTask(id: Long): Flow<DownloadTaskEntity?> {
        return repository.getByIdFlow(id)
    }

    override fun getTaskByUid(uid: String): Flow<DownloadTaskEntity?> {
        return repository.getByUidFlow(uid)
    }

    override fun getAllTasks(): Flow<List<DownloadTaskEntity>> {
        return repository.getAllTasksFlow()
    }
}