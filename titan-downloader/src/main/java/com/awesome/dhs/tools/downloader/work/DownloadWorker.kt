package com.awesome.dhs.tools.downloader.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.awesome.dhs.tools.downloader.DownloaderManager
import com.awesome.dhs.tools.downloader.constants.Constant.KEY_EXCEPTION
import com.awesome.dhs.tools.downloader.constants.Constant.TASK_REQUEST
import com.awesome.dhs.tools.downloader.db.jsonToDownloadTaskEntity
import com.awesome.dhs.tools.downloader.model.DownloadState
import com.awesome.dhs.tools.downloader.model.DownloadStatus
import com.awesome.dhs.tools.downloader.strategy.DownloadStrategyFactory
import java.io.File
import kotlin.coroutines.cancellation.CancellationException
import com.awesome.dhs.tools.downloader.notification.NotificationController.Companion.PAUSE_NOTIFICATION_ID_OFFSET
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import com.awesome.dhs.tools.downloader.model.DownloadStatus.FAILED
import com.awesome.dhs.tools.downloader.utils.FileUtil
import kotlinx.coroutines.DelicateCoroutinesApi

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

    private val downloader = DownloaderManager.getInstance()
    private val repository = downloader.repository
    private val config = downloader.config
    private val httpClient = config.httpClient
    private val notificationController = config.notificationProvider

    companion object {
        const val TAG = "DownloadWorker"
    }

    @OptIn(DelicateCoroutinesApi::class)
    override suspend fun doWork(): Result {
        val originTask = inputData.getString(TASK_REQUEST)?.let { jsonToDownloadTaskEntity(it) }
            ?: return Result.failure(
                workDataOf(KEY_EXCEPTION to "EXCEPTION_FAILED_DESERIALIZE")
            )

        val taskId = originTask.id
        val notificationId = taskId.toInt() // 使用 taskId 作为通知 ID

        // 1. 启动前台服务
        try {
            // 开始下载时，移除可能存在的旧的“暂停/失败”通知，避免重复
            notificationController.cancel(notificationId + PAUSE_NOTIFICATION_ID_OFFSET)
            val initialNotification =
                notificationController.buildNotification(originTask, config.notificationClickIntent)
                    .build()
            // Then, create the ForegroundInfo, specifying the type
            val foregroundInfo =
                notificationController.createForegroundInfo(originTask, initialNotification)
            setForeground(foregroundInfo)
        } catch (e: Exception) {
            config.logger.e(TAG, "Failed to set foreground service for task $taskId", e)
            repository.updateOnError(taskId, FAILED, "Failed to start foreground service")
            return Result.failure()
        }

        // 2. 获取下载策略并执行
        val strategy = DownloadStrategyFactory.getStrategy(originTask)
        var finalResult: Result = Result.failure()
        var task = originTask
        try {
            strategy.download(originTask, httpClient).collect { state ->
                // 检查 Worker 是否已被外部停止
                if (isStopped) {
                    config.logger.d(TAG, "Task $taskId Worker was stopped.")
                    throw CancellationException("Worker was stopped.")
                }

                when (state) {
                    is DownloadState.InProgress -> {
                        task = task.copy(speedBps = state.speedBps, progress = state.progress)
                        downloader.impl().notifyListeners(task)
                        val currentTime = System.currentTimeMillis()
                        repository.updateProgress(
                            taskId,
                            state.progress,
                            state.downloadedBytes,
                            state.totalBytes,
                            state.speedBps,
                            currentTime
                        )
                    }

                    is DownloadState.Success -> {
                        config.logger.d(TAG, "Task $taskId download success. Moving file...")
                        task = task.copy(status = DownloadStatus.COMPLETED, progress = 100)
                        downloader.impl().notifyListeners(task)
                        try {
                            // 将临时文件写入到早已确定的 task.filePath
                            FileUtil.moveToPublicDirectory(
                                applicationContext,
                                File(originTask.tempFilePath),
                                originTask.filePath,
                                originTask.fileName
                            )

                            // 数据库更新：此时 filePath 和 fileName 已经是正确的了，无需再次修改
                            repository.updateOnSuccess(
                                taskId,
                                finalPath = originTask.filePath,
                                fileName = originTask.fileName // 使用数据库中已有的正确名字
                            )
                            finalResult = Result.success()
                        } catch (e: Exception) {
                            // 提交失败（例如 IO 错误）
                            repository.updateOnError(
                                taskId,
                                FAILED,
                                "Write final file failed: ${e.message}"
                            )
                            finalResult = Result.failure()
                        }
                    }

                    is DownloadState.Error -> {
                        config.logger.e(TAG, "Task $taskId failed: ${state.message}", state.cause)
                        task = task.copy(status = FAILED)
                        downloader.impl().notifyListeners(task)
                        repository.updateOnError(taskId, FAILED, state.message)
                        finalResult = Result.failure()
                    }
                }
                // 高频更新通知
                notificationController.updateNotification(notificationId, task)
            }
        } catch (e: Exception) {
            GlobalScope.launch {
                if (e is CancellationException) {
                    // 由 pause() 或 cancel() 触发
                    task = repository.getById(task.id) ?: task
                    config.logger.d(TAG, "Task $taskId was cancelled or paused by user.$task")
                    if (task.status == DownloadStatus.PAUSED) {
                        config.logger.d(TAG, "Task $taskId paused.")
                        downloader.impl().notifyListeners(task)
                        repository.updateStatus(taskId, DownloadStatus.PAUSED)
                        notificationController.updateNotification(notificationId, task)
                        finalResult = Result.success() // 暂停被视为 "成功" 完成工作
                    } else {
                        config.logger.e(TAG, "Task $taskId failed: ${e.message}", e.cause)
                        task = task.copy(status = FAILED)
                        downloader.impl().notifyListeners(task)
                        repository.updateOnError(taskId, FAILED, e.message)
                        notificationController.updateNotification(notificationId, task)
                        finalResult = Result.failure()
                    }
                } else {
                    // 其他未知异常
                    config.logger.e(TAG, "Task $taskId encountered unknown error", e)
                    repository.updateOnError(taskId, FAILED, e.message ?: "Unknown worker error")
                    notificationController.updateNotification(
                        notificationId,
                        task.copy(status = FAILED)
                    )
                    finalResult = Result.failure()
                }
            }
        }
        return finalResult
    }
}