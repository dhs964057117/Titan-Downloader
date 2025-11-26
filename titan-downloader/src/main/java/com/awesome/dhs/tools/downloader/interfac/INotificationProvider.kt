package com.awesome.dhs.tools.downloader.interfac

import android.app.Notification
import android.app.PendingIntent
import androidx.core.app.NotificationCompat
import androidx.work.ForegroundInfo
import com.awesome.dhs.tools.downloader.db.DownloadTaskEntity

/**
 * INotificationProvider接口定义，允许使用者完全自定义通知的行为。
 * An interface allowing library users to fully customize notification behavior.
 */
interface INotificationProvider {

    /**
     * 当 DownloadWorker 启动时，框架会立即调用此方法。
     * 必须在此方法中创建并显示一个通知，并返回一个 [ForegroundInfo] 对象。
     * 通知 ID 必须使用 `task.id.toInt()`。
     *
     * @param task 刚刚开始下载的任务。
     * @return 供 Worker 使用的 ForegroundInfo。
     */
    suspend fun createForegroundInfo(
        task: DownloadTaskEntity,
        notification: Notification,
    ): ForegroundInfo

    /**
     * 创建一个当用户点击通知主体时触发的 PendingIntent。
     *
     * @param task 相关的任务。
     * @return 一个 PendingIntent，或者 null（无操作）。
     */
    fun createClickIntent(task: DownloadTaskEntity): PendingIntent?

    /**
     * 创建一个通知。
     * @param task 相关的任务。
     * @param speedBps 下载速度
     * @param clickIntent
     * @return 通知的构建体
     */
    suspend fun buildNotification(
        task: DownloadTaskEntity,
        clickIntent: PendingIntent? = null,
    ): NotificationCompat.Builder

    /**
     * 进度更新通知。
     * @param notificationId 通知Id
     * @param task 相关的任务。
     * @param speedBps 下载速度
     * @param progress 下载进度
     * @return 通知的构建体
     */
    suspend fun updateNotification(
        notificationId: Int,
        task: DownloadTaskEntity,
    )

    /**
     * 取消通知。
     */
    fun cancel(notificationId: Int)

    fun show(notificationId: Int, builder: NotificationCompat.Builder)
}