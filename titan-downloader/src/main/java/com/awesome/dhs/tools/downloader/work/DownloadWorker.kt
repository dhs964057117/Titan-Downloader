package com.awesome.dhs.tools.downloader.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.awesome.dhs.tools.downloader.DownloaderManager
import com.awesome.dhs.tools.downloader.db.DownloadTaskEntity
import com.awesome.dhs.tools.downloader.model.DownloadState
import com.awesome.dhs.tools.downloader.model.DownloadStatus
import com.awesome.dhs.tools.downloader.strategy.DownloadStrategyFactory
import java.io.File
import kotlin.coroutines.cancellation.CancellationException


/**
 * FileName: DownloadWorker
 * Author: haosen
 * Date: 10/3/2025 4:48 AM
 * Description:
 **/
internal class DownloadWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {

    val repository = DownloaderManager.repository
    val okHttpClient = DownloaderManager.config.httpClient
    val notificationController = DownloaderManager.impl().notificationController
    val callback = DownloaderManager.impl().dispatcher
    override suspend fun doWork(): Result {
        val taskId = inputData.getLong("TASK_ID", -1L)
        if (taskId == -1L) return Result.failure()
        // 启动前台服务
        val task = repository.getById(taskId) ?: return Result.failure()
        val notificationId = taskId.toInt() // 使用 taskId 作为通知 ID

        val initialNotification = notificationController.buildNotification(task, 0L).build()
        // Then, create the ForegroundInfo, specifying the type
        val foregroundInfo =
            notificationController.createForegroundInfo(notificationId, initialNotification)
        setForeground(foregroundInfo)

        val strategy = DownloadStrategyFactory.getStrategy(task)
        val statusChecker = suspend { repository.getStatusById(taskId) }
        try {
            strategy.download(task, okHttpClient, statusChecker).collect { state ->
                // ---  所有状态更新都通过回调汇报给 Dispatcher ---
                when (state) {
                    is DownloadState.InProgress -> {
                        // 高频汇报进度给 Dispatcher (内存操作)
                        callback.onProgress(
                            taskId,
                            state.progress,
                            state.downloadedBytes,
                            state.totalBytes,
                            state.speedBps
                        )
                    }

                    is DownloadState.Success -> {
                        // 关键节点汇报：成功
                        callback.onStateChanged(taskId, DownloadStatus.COMPLETED, task.filePath)
                    }

                    is DownloadState.Paused -> {
                        // 关键节点汇报：暂停
                        callback.onStateChanged(taskId, DownloadStatus.PAUSED)
                    }

                    is DownloadState.Error -> {
                        // 关键节点汇报：失败
                        callback.onStateChanged(
                            taskId,
                            DownloadStatus.FAILED,
                            error = state.message
                        )
                    }
                }
            }
        } catch (e: CancellationException) {
            handleCancellation(task) // 在 handleCancellation 内部也会调用回调
            return Result.success()
        } catch (e: Exception) {
            // 关键节点汇报：意外失败
            callback.onStateChanged(
                taskId,
                DownloadStatus.FAILED,
                error = e.message ?: "Unknown worker error"
            )
            return Result.failure()
        }

        return Result.success()
    }

    private suspend fun handleCancellation(task: DownloadTaskEntity) {
        // 确保状态是 CANCELED
        repository.updateStatus(task.id, DownloadStatus.CANCELED)
        // 删除临时文件
        try {
            File(task.tempFilePath).delete()
        } catch (e: Exception) {
        }
    }
}