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
        return headers?.let { gson.toJson(it) }
    }

    @TypeConverter
    fun toHeaders(headersJson: String?): Map<String, String>? {
        val type = object : TypeToken<Map<String, String>>() {}.type
        return headersJson?.let { gson.fromJson(it, type) }
    }
}