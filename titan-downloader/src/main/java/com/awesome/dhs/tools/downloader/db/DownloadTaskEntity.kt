package com.awesome.dhs.tools.downloader.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.awesome.dhs.tools.downloader.model.DownloadStatus
import com.awesome.dhs.tools.downloader.model.DownloadTypes
import java.util.UUID


/**
 * FileName: DownloadTaskEntity
 * Author: haosen
 * Date: 10/3/2025 4:00 AM
 * Description:
 **/
@Entity(tableName = "download_tasks")
data class DownloadTaskEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0, // 自增长 Long 类型主键
    // --- 核心下载信息 ---
    val url: String,
    val filePath: String, // 文件存储的完整路径
    val tempFilePath: String,       // 临时文件路径，用于下载过程
    val fileName: String,

    var status: DownloadStatus = DownloadStatus.QUEUED,
    var progress: Int = 0,
    var totalBytes: Long = 0L,
    var downloadedBytes: Long = 0L,
    // --- 时间戳 ---
    val createdTime: Long = System.currentTimeMillis(),
    var updateTime: Long = System.currentTimeMillis(),

    // --- 丰富的元数据 (Metadata) ---
    val type: String = DownloadTypes.OTHER,
    val uid: String?,               // 用户提供的 ID，用于反向追踪。增加索引以优化查询。
    val source: String? = null,     // 任务来源，例如 "discover_page", "user_profile"
    val cover: String? = null,      // 封面图的 URL 或本地路径
    val duration: Long = 0L,        // 时长 (ms)，主要用于音视频
    val resolution: String? = null, // 分辨率，例如 "1920x1080"
    val extra: String? = null,      // 扩展字段，可存储 JSON 字符串以备未来之需

    var error: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val tag: String? = null // 用于对任务进行分组
)
