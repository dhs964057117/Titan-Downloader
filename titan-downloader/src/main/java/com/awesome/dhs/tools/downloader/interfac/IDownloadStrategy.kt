package com.awesome.dhs.tools.downloader.interfac

import com.awesome.dhs.tools.downloader.db.DownloadTaskEntity
import com.awesome.dhs.tools.downloader.model.DownloadState
import com.awesome.dhs.tools.downloader.model.DownloadStatus
import kotlinx.coroutines.flow.Flow
import okhttp3.OkHttpClient

/**
 * FileName: AppDatabase
 * Author: haosen
 * Date: 10/3/2025 4:25 AM
 * Description: Defines the contract for a download strategy.
 * Each strategy is responsible for handling the download of a specific type of content (e.g., HTTP, HLS).
 */
interface IDownloadStrategy {
    fun download(
        task: DownloadTaskEntity,
        client: OkHttpClient,
        stateChecker: suspend () -> DownloadStatus?
    ): Flow<DownloadState>
}