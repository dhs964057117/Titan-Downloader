package com.awesome.dhs.tools.downloader.model

/**
 * FileName: AppDatabase
 * Author: haosen
 * Date: 10/3/2025 6:21 AM
 * Description: A data class to hold the fully resolved and sanitized path information for a download task.
 */
internal data class ResolvedPath(
    val finalPath: String,   // The complete, sanitized, absolute path for the final file.
    val fileName: String     // The sanitized file name component.
)