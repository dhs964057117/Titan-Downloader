package com.awesome.dhs.tools.downloader.model

import com.awesome.dhs.tools.downloader.db.DownloadTaskEntity
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * FileName: DownloadRequest
 * Author: haosen
 * Date: 10/3/2025 00:05 AM
 * Description:
 **/
// 这个类后续会扩展更多配置
@Serializable
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
    val tag: String? = null,
)

internal fun DownloadTaskEntity.toDownloadRequest() = DownloadRequest(
    url = this.url,
    filePath = this.filePath.ifBlank { null },
    fileName = this.fileName,
    headers = this.headers
)

internal fun DownloadTaskEntity.toDownloadTask() = DownloadModel(
    id = id,
    url = url,
    filePath = filePath,
    tempFilePath = tempFilePath,
    fileName = fileName,
    status = status,
    progress = progress,
    totalBytes = totalBytes,
    downloadedBytes = downloadedBytes,
    speedBps = speedBps,
    createdTime = createdTime,
    updateTime = updateTime,
    type = type,
    uid = uid,           // 用户提供的 ID，用于反向追踪。增加索引以优化查询。
    source = source,     // 任务来源，例如 "discover_page", "user_profile"
    cover = cover,      // 封面图的 URL 或本地路径
    duration = duration,        // 时长 (ms)，主要用于音视频
    resolution = resolution, // 分辨率，例如 "1920x1080"
    extra = extra,      // 扩展字段，可存储 JSON 字符串以备未来之需
    error = error,
    headers = headers,
    tag = tag, // 用于对任务进行分组
)

fun DownloadRequest.toJson() = Json.encodeToString(this)

fun jsonToDownloadRequest(json: String) = Json.decodeFromString<DownloadRequest>(json)