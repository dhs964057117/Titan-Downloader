package com.awesome.dhs.tools.downloader.db

import androidx.room.TypeConverter
import com.awesome.dhs.tools.downloader.model.DownloadStatus
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * FileName: TypeConverters
 * Author: haosen
 * Date: 10/3/2025 4:03 AM
 * Description:
 **/


class TypeConverters {
    private val gson = Gson()

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
        return headers?.let { gson.toJson(it) } ?: ""
    }

    @TypeConverter
    fun toHeaders(headersJson: String?): Map<String, String>? {
        return when {
            headersJson == null -> emptyMap()
            headersJson.isBlank() -> emptyMap()  // 处理空字符串
            "null".equals(headersJson, true) -> emptyMap()  // 处理 "null" 字符串
            else -> {
                try {
                    val type = object : TypeToken<Map<String, String>>() {}.type
                    Gson().fromJson(headersJson, type) ?: emptyMap()
                } catch (e: Exception) {
                    // 如果是无效的 JSON，尝试其他格式
                    if (headersJson.startsWith("{") && headersJson.endsWith("}")) {
                        try {
                            // 尝试更宽松的解析
                            val mapType = object : TypeToken<Map<String, Any>>() {}.type
                            val rawMap: Map<String, Any> = Gson().fromJson(headersJson, mapType)
                            rawMap.mapValues { it.value.toString() }
                        } catch (e2: Exception) {
                            emptyMap()
                        }
                    } else {
                        emptyMap()
                    }
                }
            }
        }
    }
}