package com.awesome.dhs.tools.downloader.interfac

import com.awesome.dhs.tools.downloader.model.DownloadStatus

/**
 * FileName: AppDatabase
 * Author: haosen
 * Date: 10/3/2025 4:15 AM
 * Description:
 **/
/**
 * A callback interface for DownloadWorkers to report their state changes
 * back to the DownloadDispatcher.
 */
internal interface DispatcherTaskCallback {
    /**
     * Called frequently to report download progress and speed.
     * This is a high-frequency, in-memory-only operation.
     */
    fun onProgress(
        taskId: Long,
        progress: Int,
        downloadedBytes: Long,
        totalBytes: Long,
        speedBps: Long
    )

    /**
     * Called when a task's state permanently changes (e.g., completes, fails, pauses).
     * This is a low-frequency operation that will trigger a database write.
     */
    fun onStateChanged(
        taskId: Long,
        status: DownloadStatus,
        finalPath: String? = null,
        error: String? = null
    )
}