package com.awesome.dhs.tools.downloader.db

import androidx.room.TypeConverter
import com.awesome.dhs.tools.downloader.model.DownloadStatus
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * FileName: TypeConverters
 * Author: haosen
 * Date: 10/3/2025 4:03 AM
 * Description:
 **/


class TypeConverters {

    private val json = Json {
        isLenient = true          // 解析更宽松的JSON格式
        ignoreUnknownKeys = true  // 忽略JSON中存在但数据类中没有的字段
    }

    @TypeConverter
    fun fromDownloadStatus(status: DownloadStatus?): String? {
        return status?.name
    }

    @TypeConverter
    fun toDownloadStatus(status: String?): DownloadStatus? {
        return status?.let { enumValueOf<DownloadStatus>(it) }
    }

    @TypeConverter
    fun fromHeaders(headers: Map<String, String>?): String? {
        return headers?.let { json.encodeToString(it) }
    }

    @TypeConverter
    fun toHeaders(headersJson: String?): Map<String, String>? {
        return headersJson?.let { json.decodeFromString(it) }
    }
}