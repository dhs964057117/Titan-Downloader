package com.awesome.dhs.tools.downloader.model // 替换成你的包名
/**
 * FileName: AppDatabase
 * Author: haosen
 * Date: 10/3/2025 6:00 AM
 * Description:
 **/
enum class DownloadStatus {
    QUEUED,    // 排队中
    RUNNING,   // 下载中
    PAUSED,    // 已暂停
    COMPLETED, // 已完成
    FAILED,    // 失败
    CANCELED   // 已取消
}