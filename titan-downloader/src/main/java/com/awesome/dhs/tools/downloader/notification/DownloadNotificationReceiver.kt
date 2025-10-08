package com.awesome.dhs.tools.downloader.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.awesome.dhs.tools.downloader.Downloader

/**
 * FileName: AppDatabase
 * Author: haosen
 * Date: 10/3/2025 7:02 AM
 * Description:
 **/
class DownloadNotificationReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_PAUSE = "downloader.action.PAUSE"
        const val ACTION_RESUME = "downloader.action.RESUME"
        const val ACTION_CANCEL = "downloader.action.CANCEL"
        const val EXTRA_TASK_ID = "downloader.extra.TASK_ID"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val taskId = intent.getLongExtra(EXTRA_TASK_ID, -1L)
        if (taskId == -1L) return

        when (intent.action) {
            ACTION_PAUSE -> Downloader.pause(taskId)
            ACTION_RESUME -> Downloader.resume(taskId)
            ACTION_CANCEL -> Downloader.cancel(taskId)
        }
    }
}