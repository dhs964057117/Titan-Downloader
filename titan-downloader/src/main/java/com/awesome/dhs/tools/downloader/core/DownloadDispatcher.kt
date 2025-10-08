package com.awesome.dhs.tools.downloader.core

import com.awesome.dhs.tools.downloader.model.DownloaderConfig
import com.awesome.dhs.tools.downloader.db.DownloadTaskEntity
import com.awesome.dhs.tools.downloader.model.DownloadStatus
import com.awesome.dhs.tools.downloader.work.DownloadWorker
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.awesome.dhs.tools.downloader.DownloaderManager
import com.awesome.dhs.tools.downloader.interfac.DispatcherTaskCallback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * FileName: AppDatabase
 * Author: haosen
 * Date: 10/3/2025 4:05 AM
 * Description:
 **/
internal class DownloadDispatcher(
    var config: DownloaderConfig,
    private val repository: DownloadRepository,
    private val workManager: WorkManager
) : DispatcherTaskCallback {

    companion object {
        const val TAG = "DownloadDispatcher"
    }

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val promotionMutex = Mutex()
    val notificationController by lazy { DownloaderManager.impl().notificationController }

    // --- In-Memory State ---
    private val readyTasks = ConcurrentLinkedQueue<DownloadTaskEntity>()
    private val runningTasks = ConcurrentHashMap<Long, DownloadTaskEntity>()
    private val pausedTasks = ConcurrentHashMap<Long, DownloadTaskEntity>()

    // --- State Exposure to UI ---
    private val _tasksStateFlow = MutableStateFlow<List<DownloadTaskEntity>>(emptyList())
    val tasksStateFlow = _tasksStateFlow.asStateFlow()

    // 创建一个用于广播任务更新事件的 SharedFlow
    private val _taskUpdateEventFlow = MutableSharedFlow<DownloadTaskEntity>()
    val taskUpdateEventFlow = _taskUpdateEventFlow.asSharedFlow()
    private var lastDbUpdateTime = 0L
    private val dbUpdateInterval = 1000L // 每 1 秒最多写一次数据库

    /**
     * Loads tasks from the database to rehydrate the in-memory queues upon app start.
     */
    suspend fun rehydrate() = withContext(Dispatchers.IO) {
        // Only rehydrate once
        if (readyTasks.isNotEmpty() || runningTasks.isNotEmpty() || pausedTasks.isNotEmpty()) return@withContext

        val allTasks = repository.getAllTasksSuspend()
        allTasks.forEach { task ->
            when (task.status) {
                DownloadStatus.QUEUED -> readyTasks.add(task)
                // Treat previously running tasks as queued to ensure they are rescheduled
                DownloadStatus.RUNNING -> {
                    task.status = DownloadStatus.QUEUED
                    readyTasks.add(task)
                }

                DownloadStatus.PAUSED -> pausedTasks[task.id] = task
                else -> { /* Terminal states (COMPLETED, FAILED, CANCELED) are ignored */
                }
            }
        }
        emitCurrentState()
        promoteAndExecute()
    }

    /**
     * Enqueues a list of new tasks that have just been inserted into the database.
     */
    fun enqueue(newTasks: List<DownloadTaskEntity>) {
        scope.launch {
            readyTasks.addAll(newTasks)
            emitCurrentState()
            promoteAndExecute()
        }
    }

    fun updateConfig(newConfig: DownloaderConfig) {
        this.config = newConfig
        // Trigger a promotion check in case the concurrency limit was increased
        promoteAndExecute()
    }

    // --- Task Control Methods ---

    fun pause(ids: List<Long>) = scope.launch {
        ids.forEach { id ->
            var task: DownloadTaskEntity? = null
            // Find in ready queue
            readyTasks.find { it.id == id }?.let {
                task = it
                readyTasks.remove(it)
            }
            // Find in running queue
            runningTasks[id]?.let {
                task = it
            }
            task?.let {
                it.status = DownloadStatus.PAUSED
                pausedTasks[it.id] = it
                // Key Node: Persist state change
                repository.updateStatus(it.id, DownloadStatus.PAUSED)
            }
        }
        emitCurrentState()
    }

    fun resume(ids: List<Long>) = scope.launch {
        ids.forEach { id ->
            pausedTasks.remove(id)?.let {
                it.status = DownloadStatus.QUEUED
                readyTasks.add(it)
                // Key Node: Persist state change
                repository.updateStatus(it.id, DownloadStatus.QUEUED)
            }
        }
        emitCurrentState()
        promoteAndExecute()
    }

    fun cancel(ids: List<Long>) = scope.launch {
        val tasksToCancel = mutableListOf<DownloadTaskEntity>()
        ids.forEach { id ->
            readyTasks.find { it.id == id }?.also { readyTasks.remove(it); tasksToCancel.add(it) }
            runningTasks.remove(id)?.also { tasksToCancel.add(it) }
            pausedTasks.remove(id)?.also { tasksToCancel.add(it) }
        }

        if (tasksToCancel.isNotEmpty()) {
            val taskIds = tasksToCancel.map { it.id }
            // Key Node: Persist state change
            repository.updateStatuses(taskIds, DownloadStatus.CANCELED)

            tasksToCancel.forEach { task ->
                workManager.cancelAllWorkByTag(task.id.toString())
                try {
                    File(task.tempFilePath).delete()
                    cleanupTaskFiles(task)
                } catch (e: Exception) {
                    DownloaderManager.config.logger.d(TAG, "$e")
                }
            }
        }
        emitCurrentState()
    }


    // --- Core Scheduling Logic ---

    private fun promoteAndExecute() = scope.launch {
        promotionMutex.withLock {
            while (runningTasks.size < config.maxConcurrentDownloads) {
                val task = readyTasks.poll() ?: break // No more tasks to run

                task.status = DownloadStatus.RUNNING
                runningTasks[task.id] = task
                startDownloadWorker(task)
            }
            emitCurrentState()
        }
    }

    private suspend fun startDownloadWorker(task: DownloadTaskEntity) {
        // Key Node: Persist state change just before starting
        repository.updateStatus(task.id, DownloadStatus.RUNNING)
        val workRequest = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(workDataOf("TASK_ID" to task.id))
            .addTag(task.id.toString())
            .build()

        workManager.enqueueUniqueWork(
            task.id.toString(),
            ExistingWorkPolicy.KEEP,
            workRequest
        )
    }

    // --- Callback Implementation from DispatcherTaskCallback ---

    override fun onProgress(
        taskId: Long,
        progress: Int,
        downloadedBytes: Long,
        totalBytes: Long,
        speedBps: Long
    ) {
        // 1. 更新内存状态 (保持不变)
        val task = runningTasks[taskId]
        task?.let {
            it.progress = progress
            it.downloadedBytes = downloadedBytes
            it.totalBytes = totalBytes
            // We'd add a transient 'speed' field to DownloadTaskEntity for this
        }
        val currentTime = System.currentTimeMillis()
        // 2. 由 Dispatcher 主动更新通知，因为它有最新的内存数据
        if (task != null) {
            scope.launch {
                notificationController.cancel(task.id.toInt() + 100000)
                val notificationBuilder =
                    DownloaderManager.impl().notificationController.buildNotification(
                        task,
                        speedBps
                    )
                DownloaderManager.impl().notificationController.show(
                    taskId.toInt(),
                    notificationBuilder
                )
                scope.launch { _taskUpdateEventFlow.emit(task.copy(updateTime = currentTime)) }
            }
        }
        // 3. 将内存中的高频进度变化，降频写入数据库
        if (currentTime - lastDbUpdateTime >= dbUpdateInterval) {
            lastDbUpdateTime = currentTime
            scope.launch(Dispatchers.IO) {
                // 批量更新所有正在运行任务的进度
                runningTasks.values.forEach { runningTask ->
                    repository.updateProgress(
                        runningTask.id,
                        runningTask.progress,
                        runningTask.downloadedBytes,
                        runningTask.totalBytes,
                        currentTime
                    )
                }
            }
        }
        emitCurrentState()
    }

    override fun onStateChanged(
        taskId: Long,
        status: DownloadStatus,
        finalPath: String?,
        error: String?
    ) {
        scope.launch {
            var updateTask: DownloadTaskEntity? = null
            promotionMutex.withLock {
                val time = System.currentTimeMillis()
                val task = runningTasks.remove(taskId)
                // 如果任务是从 runningTasks 中移除的，才进行后续操作
                if (task != null) {
                    updateTask = task
                    task.status = status // 更新为最终状态
                    _taskUpdateEventFlow.emit(task.copy(updateTime = time))
                    // 使用更全面的数据库更新方法
                    when (status) {
                        DownloadStatus.COMPLETED -> {
                            if (finalPath != null) {
                                repository.updateOnSuccess(
                                    taskId,
                                    status,
                                    finalPath,
                                    time
                                ) // 假设 repo 方法已更新
                            }
                        }

                        DownloadStatus.PAUSED -> repository.updateStatus(taskId, status)
                        DownloadStatus.FAILED -> repository.updateOnError(taskId, status, error)
                        DownloadStatus.CANCELED -> repository.updateStatus(taskId, status) // 取消也应更新
                        else -> {}
                    }
                }
            }
            // 在锁之外执行文件 IO
            updateTask?.let {
                val staticNotificationId = it.id.toInt() + 100000
                when (it.status) {
                    DownloadStatus.PAUSED -> {
                        // 显示一个“已暂停”的通知
                        val builder = notificationController.buildNotification(it, 0L)
                        notificationController.show(staticNotificationId, builder)
                    }

                    DownloadStatus.COMPLETED, DownloadStatus.FAILED, DownloadStatus.CANCELED -> {
                        // 任务结束后，移除通知
                        notificationController.cancel(it.id.toInt())
                        notificationController.cancel(staticNotificationId)
                        cleanupTaskFiles(it)
                    }

                    else -> {
                        notificationController.cancel(staticNotificationId)
                    }
                }
                emitCurrentState() // 确保在状态改变后更新UI
                promoteAndExecute()
            }
        }
    }

    /**
     * Gathers all tasks from in-memory queues, sorts them, and emits them to the UI-facing StateFlow.
     */
    private fun emitCurrentState() {
        val allTasks = (readyTasks + runningTasks.values + pausedTasks.values)
            .sortedByDescending { it.createdTime }
        _tasksStateFlow.value = allTasks
    }

    /**
     * 创建一个私有的辅助函数，用于清理任务的所有相关文件
     */
    private fun cleanupTaskFiles(task: DownloadTaskEntity) {
        try {
            // 清理临时文件
            val tempFile = File(task.tempFilePath)
            if (tempFile.exists()) {
                tempFile.delete()
            }

            // 清理最终目录中的占位文件
            val finalFile = File(task.filePath)
            if (finalFile.exists() && finalFile.length() == 0L) { // 只删除 0 字节的占位文件，以防万一
                finalFile.delete()
            }
        } catch (e: Exception) {
            DownloaderManager.config.logger.d(TAG, "$e")
        }
    }
}