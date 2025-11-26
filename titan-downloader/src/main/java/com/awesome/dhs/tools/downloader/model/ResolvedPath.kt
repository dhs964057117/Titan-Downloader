package com.awesome.dhs.tools.downloader.model

/**
 * FileName: ResolvedPath
 * Author: haosen
 * Date: 10/3/2025 6:21 AM
 * Description: A data class to hold the fully resolved and sanitized path information for a download task.
 * @param filePath 在 Legacy 模式下是物理文件路径；在 Android Q+ 模式下，这里存放的是 Content Uri 的字符串形式 (content://...)
 * @param tempPath 临时文件路径
 * @param fileName 最终确定的文件名 (Q+模式下为 MediaStore 实际分配的名字)
 * @param relativePath 相对路径 (仅 Q+ 使用)
 */
internal data class ResolvedPath(
    val filePath: String,   // The complete, sanitized, absolute path for the final file.
    val tempPath: String,    // The full temporary file path
    val fileName: String,     // The sanitized file name component.
    val relativePath: String? = null,
)