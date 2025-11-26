package com.awesome.dhs.tools.downloader.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.awesome.dhs.tools.downloader.DownloaderManager
import com.awesome.dhs.tools.downloader.model.DownloadStatus
import com.awesome.dhs.tools.downloader.model.toDownloadRequest
import java.io.File
import androidx.work.workDataOf
import com.awesome.dhs.tools.downloader.constants.Constant.KEY_EXCEPTION
import com.awesome.dhs.tools.downloader.constants.Constant.TASK_REQUEST
import com.awesome.dhs.tools.downloader.db.jsonToDownloadTaskEntity
import com.awesome.dhs.tools.downloader.utils.FileNameResolver
import com.awesome.dhs.tools.downloader.utils.FileUtil

/**
 * 准备工作器
 * 负责文件名解析、创建临时文件等耗时I/O操作。
 */
internal class PrepareWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {

    private val repository = DownloaderManager.getInstance().repository
    private val httpClient = DownloaderManager.getInstance().config.httpClient
    private val config = DownloaderManager.getInstance().config

    override suspend fun doWork(): Result {
        val originTask = inputData.getString(TASK_REQUEST)?.let { jsonToDownloadTaskEntity(it) }
            ?: return Result.failure(workDataOf(KEY_EXCEPTION to "EXCEPTION_FAILED_DESERIALIZE"))
        val taskId = originTask.id
        if (taskId == -1L) return Result.failure()

        val task = repository.getById(taskId) ?: return Result.failure()

        // 检查任务是否已被取消或暂停
        if (task.status != DownloadStatus.PREPARING) {
            return Result.success() // 任务状态已改变，不再执行
        }

        return try {
            config.logger.d("PrepareWorker", "Preparing task $taskId...")
            // 1. 解析文件名和路径
            val allocated = FileNameResolver.resolve(
                context = applicationContext,
                request = task.toDownloadRequest(),
                globalFinalDir = config.finalDirectory,
                globalTempDir = File(applicationContext.cacheDir, "downloads").absolutePath,
                client = httpClient
            )

            val currentStatus = repository.getStatusById(taskId)
            if (currentStatus != DownloadStatus.PREPARING) {
                // 清理：如果是 MediaStore URI，需要删除这条 pending 记录
                FileUtil.deleteFile(applicationContext, allocated.filePath)
                return Result.success()
            }

            //  这里的 filePath 可能是 "content://..." 或 "/storage/..."
            //  这里的 fileName 是最终真实的文件名
            repository.updateOnPrepareSuccess(
                id = taskId,
                filePath = allocated.filePath,
                tempFilePath = allocated.tempPath,
                fileName = allocated.fileName
            )
            Result.success()
        } catch (e: Exception) {
            config.logger.e("PrepareWorker", "Failed to prepare task $taskId: ${e.message}", e)
            repository.updateOnError(taskId, DownloadStatus.FAILED, e.message ?: "Prepare failed")
            Result.failure()
        }
    }
}