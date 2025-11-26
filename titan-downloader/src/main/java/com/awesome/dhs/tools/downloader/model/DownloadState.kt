package com.awesome.dhs.tools.downloader.model

/**
 * FileName: AppDatabase
 * Author: haosen
 * Date: 10/3/2025 6:01 AM
 * Description: Represents the various states of a download process.
 */
sealed class DownloadState {
    /** Indicates the download is actively in progress. */
    data class InProgress(
        val progress: Int,
        val downloadedBytes: Long,
        val totalBytes: Long,
        val speedBps: Long
    ) : DownloadState()

    /** Indicates the download has completed successfully. */
    data object Success : DownloadState()

    /** Indicates the download has failed. */
    data class Error(val message: String, val cause: Throwable? = null) : DownloadState()

//    data object Paused : DownloadState()
}