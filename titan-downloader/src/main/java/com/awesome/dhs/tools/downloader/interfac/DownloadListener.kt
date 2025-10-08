package com.awesome.dhs.tools.downloader.interfac

import com.awesome.dhs.tools.downloader.db.DownloadTaskEntity

/**
 * FileName: AppDatabase
 * Author: haosen
 * Date: 10/3/2025 4:16 AM
 * Description:
 **/
interface DownloadListener {
    /**
     * Called whenever a task's status or progress is updated.
     * @param task The updated DownloadTaskEntity.
     */
    fun onTaskUpdated(task: DownloadTaskEntity)
}