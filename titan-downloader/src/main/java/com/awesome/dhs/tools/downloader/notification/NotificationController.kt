package com.awesome.dhs.tools.downloader.notification

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.ForegroundInfo
import com.awesome.dhs.tools.downloader.DownloaderManager
import com.awesome.dhs.tools.downloader.R
import com.awesome.dhs.tools.downloader.core.DownloadDispatcher.Companion.TAG
import com.awesome.dhs.tools.downloader.db.DownloadTaskEntity
import com.awesome.dhs.tools.downloader.model.DownloadStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * FileName: AppDatabase
 * Author: haosen
 * Date: 10/3/2025 6:58 AM
 * Description:
 **/
class NotificationController(
    private val context: Context,
    private val client: OkHttpClient
) {
    companion object {
        private const val CHANNEL_ID = "downloader_channel"
        private const val CHANNEL_NAME = "Downloads"
    }

    init {
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_DEFAULT // 使用 LOW，这样通知不会发出声音
            ).apply {
                description = "Shows download progress"
                setSound(null, null)
            }
            val notificationManager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    suspend fun buildNotification(
        task: DownloadTaskEntity,
        speedBps: Long
    ): NotificationCompat.Builder {
        val title = task.fileName
        val statusText = getStatusText(task.status)
        val progress = task.progress
        val speedText =
            if (task.status == DownloadStatus.RUNNING) " - ${formatSpeed(speedBps)}" else ""
        val contentText = "$statusText$speedText"

        // 异步获取封面
        val largeIcon = fetchCover(task.cover)

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(contentText)
            .setSmallIcon(R.drawable.svg_notification_download) // 你需要准备一个下载图标
            .setLargeIcon(largeIcon)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setOngoing(true) // 让通知不可被用户轻易划掉
            .setProgress(100, progress, false)

        // 根据状态添加不同的操作按钮
        when (task.status) {
            DownloadStatus.RUNNING -> {
                builder.addAction(
                    R.drawable.svg_notification_pause, "Pause",
                    createActionIntent(task.id, DownloadNotificationReceiver.ACTION_PAUSE)
                )
                builder.addAction(
                    R.drawable.svg_notification_cancel, "Cancel",
                    createActionIntent(task.id, DownloadNotificationReceiver.ACTION_CANCEL)
                )
            }

            DownloadStatus.PAUSED -> {
                builder.addAction(
                    R.drawable.svg_notification_play, "Resume",
                    createActionIntent(task.id, DownloadNotificationReceiver.ACTION_RESUME)
                )
                builder.addAction(
                    R.drawable.svg_notification_cancel, "Cancel",
                    createActionIntent(task.id, DownloadNotificationReceiver.ACTION_CANCEL)
                )
            }

            else -> { /* For QUEUED, COMPLETED, etc., no actions needed for now */
            }
        }
        return builder
    }

    fun show(notificationId: Int, builder: NotificationCompat.Builder) {
        with(NotificationManagerCompat.from(context)) {
            if (ActivityCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                // TODO: Consider calling
                //    ActivityCompat#requestPermissions
                // here to request the missing permissions, and then overriding
                //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
                //                                          int[] grantResults)
                // to handle the case where the user grants the permission. See the documentation
                // for ActivityCompat#requestPermissions for more details.
                return
            }
            notify(notificationId, builder.build())
        }
    }

    fun cancel(notificationId: Int) {
        NotificationManagerCompat.from(context).cancel(notificationId)
    }

    private fun createActionIntent(taskId: Long, action: String): PendingIntent {
        val intent = Intent(context, DownloadNotificationReceiver::class.java).apply {
            this.action = action
            putExtra(DownloadNotificationReceiver.EXTRA_TASK_ID, taskId)
        }
        // 使用 taskId 作为 requestCode 确保每个任务的 PendingIntent 是唯一的
        return PendingIntent.getBroadcast(
            context,
            (action.hashCode() + taskId).toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private suspend fun fetchCover(url: String?): Bitmap? = withContext(Dispatchers.IO) {
        if (url.isNullOrBlank()) return@withContext null
        try {
            val request = Request.Builder().url(url).build()
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                response.body?.byteStream()?.use {
                    BitmapFactory.decodeStream(it)
                }
            } else {
                null
            }
        } catch (e: IOException) {
            DownloaderManager.config.logger.d(TAG, "$e")
            null
        }
    }

    private fun getStatusText(status: DownloadStatus): String = when (status) {
        DownloadStatus.QUEUED -> "Queued"
        DownloadStatus.RUNNING -> "Downloading"
        DownloadStatus.PAUSED -> "Paused"
        DownloadStatus.COMPLETED -> "Completed"
        DownloadStatus.FAILED -> "Failed"
        DownloadStatus.CANCELED -> "Canceled"
    }

    private fun formatSpeed(bytesPerSecond: Long): String {
        if (bytesPerSecond < 1024) return "$bytesPerSecond B/s"
        val kbps = bytesPerSecond / 1024
        if (kbps < 1024) return "$kbps KB/s"
        val mbps = kbps / 1024.0
        return String.format("%.2f MB/s", mbps)
    }

    fun createForegroundInfo(
        notificationId: Int,
        notification: Notification
    ): ForegroundInfo {
        // On modern Android, you must specify the foreground service type.
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                notificationId,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            // For older versions, the type is not needed.
            ForegroundInfo(notificationId, notification)
        }
    }
}