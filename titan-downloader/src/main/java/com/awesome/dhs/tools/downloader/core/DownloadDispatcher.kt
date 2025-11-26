package com.awesome.dhs.tools.downloader.core

import androidx.work.CoroutineWorker
import com.awesome.dhs.tools.downloader.model.DownloaderConfig
import com.awesome.dhs.tools.downloader.db.DownloadTaskEntity
import com.awesome.dhs.tools.downloader.work.DownloadWorker
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.awesome.dhs.tools.downloader.Downloader
import com.awesome.dhs.tools.downloader.constants.Constant.TASK_REQUEST
import com.awesome.dhs.tools.downloader.db.toJson
import com.awesome.dhs.tools.downloader.model.DownloadStatus
import com.awesome.dhs.tools.downloader.utils.FileUtil
import com.awesome.dhs.tools.downloader.work.PrepareWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * FileName: AppDatabase
 * Author: haosen
 * Date: 10/3/2025 4:05 AM
 * Description:
 **/
internal class DownloadDispatcher(
    @Volatile private var config: DownloaderConfig,
    private val repository: DownloadRepository,
    private val workManager: WorkManager,
) {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val scheduleMutex = Mutex()
    private val activeWorkTags = ConcurrentHashMap.newKeySet<String>()

    companion object {
        const val TAG = "DownloadDispatcher"
        const val WORK_TAG_PREFIX = "titan_downloader_"
        fun getWorkTag(id: Long) = "$WORK_TAG_PREFIX$id"
    }

    /**
     * 启动调度器。
     */
    fun start() {
        scope.launch {
            rehydrate()
            observeActiveWork()
        }
    }

    /**
     * 应用启动时恢复状态。
     * 将所有 "进行中" 的任务重置为 "待命" 状态，以便重新调度。
     */
    private suspend fun rehydrate() {
        config.logger.d(TAG, "Rehydrating dispatcher state...")
        val tasksToReset = repository.getActiveTasks()
        if (tasksToReset.isNotEmpty()) {
            config.logger.d(TAG, "Found ${tasksToReset.size} tasks to reset.")
            tasksToReset.forEach { task ->
                // [兼容性]
                // 升级后首次启动时，可能有旧版本的 Worker (名称为 id) 还在运行。
                // 显式取消它，否则新启动的 Worker (名称为 downloader_id) 会与旧的并行运行。
                cancelWorkForTask(task.id)
                val newStatus =
                    when (task.status) {
                        DownloadStatus.PREPARING -> DownloadStatus.QUEUED
                        else -> DownloadStatus.READY
                    }
                repository.updateStatus(task.id, newStatus)
            }
        }
        // 清理所有已知的 WorkManager 任务
        workManager.pruneWork()
        // 触发一次调度
        scheduleNext()
    }

    /**
     * 监听所有由本调度器启动的 WorkManager 任务。
     */
    private fun observeActiveWork() {
        val workQuery = workManager.getWorkInfosByTagLiveData(WORK_TAG_PREFIX.dropLast(1))

        // 在主线程上观察 LiveData
        scope.launch(Dispatchers.Main) {
            workQuery.observeForever { workInfos ->
                if (workInfos.isNullOrEmpty()) {
                    activeWorkTags.clear()
                    return@observeForever
                }

                val currentActiveTags = ConcurrentHashMap.newKeySet<String>()
                var stateChanged = false

                workInfos.forEach { workInfo ->
                    val tag = workInfo.tags.firstOrNull { it.startsWith(WORK_TAG_PREFIX) }
                        ?: return@forEach
                    currentActiveTags.add(tag)

                    if (workInfo.state.isFinished) {
                        // 一个任务完成了，从活动列表中移除
                        if (activeWorkTags.remove(tag)) {
                            config.logger.d(TAG, "Work $tag finished with state ${workInfo.state}.")
                            stateChanged = true
                        }
                    } else {
                        // 任务仍在运行
                        activeWorkTags.add(tag)
                    }
                }

                // 检查是否有任务从 activeWorkTags 中消失了（例如被系统杀死）
                val staleTags = activeWorkTags.filterNot { currentActiveTags.contains(it) }
                if (staleTags.isNotEmpty()) {
                    staleTags.forEach { activeWorkTags.remove(it) }
                    stateChanged = true
                }

                if (stateChanged || activeWorkTags.size < config.maxConcurrentDownloads) {
                    config.logger.d(
                        TAG,
                        "Active work count: ${activeWorkTags.size}. Triggering schedule."
                    )
                    // 状态有变更，触发一次调度
                    scheduleNext()
                }
            }
        }
    }

    /**
     * 更新配置。
     */
    fun updateConfig(newConfig: DownloaderConfig) {
        this.config = newConfig
        // Trigger a promotion check in case the concurrency limit was increased
        scheduleNext()
    }

    // --- Task Control Methods ---
    /**
     * [核心调度逻辑]
     * 尝试调度下一个可用任务。
     */
    fun scheduleNext() {
        scope.launch {
            scheduleMutex.withLock {
                val activeCount = activeWorkTags.size
                if (activeCount >= config.maxConcurrentDownloads) {
                    config.logger.d(
                        TAG,
                        "Schedule check: At capacity ($activeCount/${config.maxConcurrentDownloads})."
                    )
                    return@withLock
                }

                // 检查是否有空闲槽位
                val availableSlots = config.maxConcurrentDownloads - activeCount
                config.logger.d(
                    TAG,
                    "Schedule check: $availableSlots slots available ($activeCount/${config.maxConcurrentDownloads})."
                )

                // 一次只查询和调度一个任务，以避免竞态条件
                if (availableSlots > 0) {
                    val nextTask = repository.findNextTaskToSchedule()
                    if (nextTask == null) {
                        config.logger.d(TAG, "Schedule check: No tasks found to schedule.")
                        return@withLock
                    }

                    // 检查任务是否已在处理中 (以防万一)
                    val workTag = getWorkTag(nextTask.id)
                    if (activeWorkTags.contains(workTag)) {
                        config.logger.d(
                            TAG,
                            "Schedule check: Task ${nextTask.id} is already active."
                        )
                        return@withLock
                    }

                    // 根据状态启动不同的 Worker
                    when (nextTask.status) {
                        DownloadStatus.READY -> {
                            config.logger.d(TAG, "Scheduling DOWNLOAD for task ${nextTask.id}...")
                            repository.updateStatus(nextTask.id, DownloadStatus.RUNNING)
                            startWork<DownloadWorker>(nextTask.copy(status = DownloadStatus.RUNNING), workTag)
                        }

                        DownloadStatus.QUEUED -> {
                            config.logger.d(TAG, "Scheduling PREPARE for task ${nextTask.id}...")
                            repository.updateStatus(nextTask.id, DownloadStatus.PREPARING)
                            startWork<PrepareWorker>(nextTask, workTag)
                        }

                        else -> {
                            // 理论上不应发生
                            config.logger.e(
                                TAG,
                                "Schedule check: Found task ${nextTask.id} with unexpected status ${nextTask.status}."
                            )
                        }
                    }
                }
            }
        }
    }

    private inline fun <reified W : CoroutineWorker> startWork(
        task: DownloadTaskEntity,
        tag: String,
    ) {
        val workRequest = OneTimeWorkRequestBuilder<W>()
            .setInputData(workDataOf(TASK_REQUEST to task.toJson()))
            .addTag(tag) // 使用唯一 tag
            .addTag(WORK_TAG_PREFIX.dropLast(1)) // 添加通用 tag
            .build()

        activeWorkTags.add(tag) // 预先添加，防止重复调度
        workManager.enqueueUniqueWork(
            tag, // 使用 tag 作为唯一标识
            ExistingWorkPolicy.KEEP,
            workRequest
        )
    }

    // --- 任务控制 ---

    fun pause(ids: List<Long>) {
        scope.launch {
            repository.updateStatuses(ids, DownloadStatus.PAUSED)
            ids.forEach { id ->
                cancelWorkForTask(id)
            }
            config.logger.d(TAG, "Paused tasks: $ids. Triggering schedule.")
            scheduleNext() // 腾出了槽位，立即调度
        }
    }

    fun resume(ids: List<Long>) {
        scope.launch {
            repository.resumeStatusesToReady(ids)
            config.logger.d(TAG, "Resumed tasks: $ids. Triggering schedule.")
            scheduleNext() // 有新任务变为 READY，立即调度
        }
    }

    fun cancel(ids: List<Long>) {
        scope.launch {
            // 先从数据库获取任务信息，以便删除文件
            val tasks = repository.getByIds(ids)
            repository.updateStatuses(ids, DownloadStatus.CANCELED)

            tasks.forEach { task ->
                cancelWorkForTask(task.id)
                cleanupTaskFiles(task)
            }
            config.logger.d(TAG, "Canceled tasks: $ids. Triggering schedule.")
            scheduleNext() // 腾出了槽位，立即调度
        }
    }

    fun delete(ids: List<Long>, deleteFile: Boolean) {
        scope.launch {
            val tasks = repository.getByIds(ids)
            repository.deleteByIds(ids)

            tasks.forEach { task ->
                cancelWorkForTask(task.id)
                cleanupTaskFiles(task, deleteFinalFile = deleteFile)
            }
            config.logger.d(TAG, "Deleted tasks: $ids. Triggering schedule.")
            scheduleNext() // 腾出了槽位，立即调度
        }
    }

    /**
     * 创建一个私有的辅助函数，用于清理任务的所有相关文件
     */
    fun cleanupTaskFiles(task: DownloadTaskEntity, deleteFinalFile: Boolean = true) {
        try {
            // 清理临时文件
            if (task.tempFilePath.isNotBlank()) {
                File(task.tempFilePath).delete()
            }

            // 清理最终文件
            if (deleteFinalFile && task.filePath.isNotBlank()) {
                FileUtil.deleteFile(Downloader.context, task.filePath)
            }
        } catch (e: Exception) {
            config.logger.e(TAG, "Failed to cleanup files for task ${task.id}", e)
        }
    }

    /**
     * [兼容性方法] 取消任务的 Worker。
     * 同时尝试取消新版本 (downloader_id) 和旧版本 (id) 的 UniqueWork。
     */
    private fun cancelWorkForTask(id: Long) {
        // 1. 取消新版本的 Work (Tag: downloader_100)
        val newTag = getWorkTag(id)
        workManager.cancelUniqueWork(newTag)
        activeWorkTags.remove(newTag)

        // 2. [兼容性] 尝试取消旧版本的 Work (Tag: 100)
        workManager.cancelUniqueWork(id.toString())
    }
}