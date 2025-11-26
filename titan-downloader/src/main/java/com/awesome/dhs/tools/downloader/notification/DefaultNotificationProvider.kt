package com.awesome.dhs.tools.downloader.notification

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.ForegroundInfo
import com.awesome.dhs.tools.downloader.db.DownloadTaskEntity
import com.awesome.dhs.tools.downloader.interfac.INotificationProvider
import com.awesome.dhs.tools.downloader.model.DownloadStatus
import com.awesome.dhs.tools.downloader.notification.NotificationController.Companion.PAUSE_NOTIFICATION_ID_OFFSET
import okhttp3.OkHttpClient

/**
 * [INotificationProvider] 的默认实现。
 * 提供了标准的“正在下载”、“已暂停”和“已完成”通知行为。
 */
internal class DefaultNotificationProvider(
    context: Context,
    httpClient: OkHttpClient,
    private val notificationClickIntent: PendingIntent? = null,
) : INotificationProvider {

    companion object{
       private const val TAG = "DefaultNotificationProvider"
    }
    // 内部使用 NotificationController 来完成构建工作
    private val controller = NotificationController(context, httpClient, notificationClickIntent)

    /**
     * 用户可以重写此方法以提供自定义 PendingIntent。
     */
    override fun createClickIntent(task: DownloadTaskEntity): PendingIntent? =
        notificationClickIntent

    override suspend fun createForegroundInfo(
        task: DownloadTaskEntity,
        notification: Notification,
    ): ForegroundInfo = controller.createForegroundInfo(task.id.toInt(), notification)

    override suspend fun buildNotification(
        task: DownloadTaskEntity,
        clickIntent: PendingIntent?,
    ): NotificationCompat.Builder = controller.buildNotification(task, clickIntent)

    override suspend fun updateNotification(
        notificationId: Int,
        task: DownloadTaskEntity,
    ) {
        Log.e(TAG,"${task.id} ${task.status}")
        when (task.status) {
            DownloadStatus.PREPARING,
            DownloadStatus.READY,
            DownloadStatus.RUNNING,
                -> {
                // 更新正在运行或暂停的通知
                val staticId = task.id.toInt() + PAUSE_NOTIFICATION_ID_OFFSET // 静态通知，Worker 结束保留
                controller.cancel(staticId)
                val builder =
                    controller.buildNotification(task, createClickIntent(task))
                controller.show(notificationId, builder)
            }

            DownloadStatus.PAUSED -> {
                // 更新暂停的通知
                controller.cancel(task.id.toInt())
                val staticId = task.id.toInt() + PAUSE_NOTIFICATION_ID_OFFSET // 静态通知，Worker 结束保留
                val builder =
                    controller.buildNotification(task, createClickIntent(task))
                controller.show(staticId, builder)
            }

            DownloadStatus.COMPLETED,
            DownloadStatus.FAILED,
                -> {
                // 1. 立即取消“正在进行”的通知
                controller.cancel(notificationId)

                // 2. 创建一个新的、一次性的“已完成”或“已失败”通知
                val terminalBuilder =
                    controller.buildTerminalNotification(task, createClickIntent(task))

                // 3. 使用一个不同的 ID 来显示它，以避免冲突
                val terminalNotificationId =
                    notificationId + NotificationController.TERMINAL_NOTIFICATION_ID_OFFSET
                controller.show(terminalNotificationId, terminalBuilder)
            }

            DownloadStatus.CANCELED,
            DownloadStatus.QUEUED,
                -> {
                // 任务被取消或排队，只需移除通知
                val terminalNotificationId =
                    notificationId + NotificationController.TERMINAL_NOTIFICATION_ID_OFFSET
                val pausedNotificationId = notificationId + NotificationController.PAUSE_NOTIFICATION_ID_OFFSET
                controller.cancel(terminalNotificationId)
                controller.cancel(pausedNotificationId)
                controller.cancel(notificationId)
            }
        }
    }

    override fun cancel(notificationId: Int) = controller.cancel(notificationId)

    override fun show(
        notificationId: Int,
        builder: NotificationCompat.Builder,
    ) = controller.show(notificationId, builder)
}