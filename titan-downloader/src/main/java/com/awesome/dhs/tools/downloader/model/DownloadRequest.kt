package com.awesome.dhs.tools.downloader.model

/**
 * FileName: AppDatabase
 * Author: haosen
 * Date: 10/3/2025 00:05 AM
 * Description:
 **/
// 这个类后续会扩展更多配置
data class DownloadRequest(
    // --- 必填核心信息 ---
    val url: String,
    val filePath: String? = null,
    val fileName: String,
    // --- 选填元数据 ---
    val uid: String? = null,     // 用于业务方追踪任务
    val type: String = DownloadTypes.OTHER,
    val source: String? = null,
    val cover: String? = null,
    val duration: Long = 0L,
    val resolution: String? = null,
    val extra: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val tag: String? = null
)