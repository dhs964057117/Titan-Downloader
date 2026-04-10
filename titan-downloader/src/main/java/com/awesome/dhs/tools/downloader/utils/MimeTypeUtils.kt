package com.awesome.dhs.tools.downloader.utils

import android.webkit.MimeTypeMap
import com.awesome.dhs.tools.downloader.model.DownloadTypes

/**
 * FileName: FileUtil
 * Author: haosen
 * Date: 12/31/2025 11:21 AM
 * Description: MimeTypeUtils
 **/
object MimeTypeUtils {

    fun String?.getMimeType(): String {
        val extension = this?.substringAfterLast('.', "")?.lowercase()
        return when (extension) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "mp4" -> "video/mp4"
            "avi" -> "video/x-msvideo"
            "mov" -> "video/quicktime"
            "mp3" -> "audio/mpeg"
            "wav" -> "audio/wav"
            "pdf" -> "application/pdf"
            "doc", "docx" -> "application/msword"
            "xls", "xlsx" -> "application/vnd.ms-excel"
            "zip" -> "application/zip"
            "txt" -> "text/plain"
            else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
                ?: "application/octet-stream"
        }
    }

    fun String?.getDownloadType(): String {
        val mimeType = this.getMimeType()

        return when {
            isImageMimeType(mimeType) -> DownloadTypes.IMAGE
            isVideoMimeType(mimeType) -> DownloadTypes.VIDEO
            isAudioMimeType(mimeType) -> DownloadTypes.AUDIO
            isDocumentMimeType(mimeType) -> DownloadTypes.DOCUMENT
            isArchiveMimeType(mimeType) -> DownloadTypes.ARCHIVE
            else -> DownloadTypes.OTHER
        }
    }

    private fun isImageMimeType(mimeType: String): Boolean {
        return mimeType.startsWith("image/")
    }

    private fun isVideoMimeType(mimeType: String): Boolean {
        return mimeType.startsWith("video/")
    }

    private fun isAudioMimeType(mimeType: String): Boolean {
        return mimeType.startsWith("audio/")
    }

    private fun isDocumentMimeType(mimeType: String): Boolean {
        return mimeType.startsWith("text/") ||
                mimeType == "application/pdf" ||
                mimeType.startsWith("application/vnd.ms-") ||
                mimeType.startsWith("application/vnd.openxmlformats-") ||
                mimeType.startsWith("application/vnd.oasis.opendocument.") ||
                mimeType in setOf(
            "application/rtf",
            "application/x-tex",
            "application/epub+zip",
            "application/x-mobipocket-ebook"
        )
    }

    private fun isArchiveMimeType(mimeType: String): Boolean {
        return mimeType.startsWith("application/") && (
                mimeType.contains("zip") ||
                        mimeType.contains("rar") ||
                        mimeType.contains("7z") ||
                        mimeType.contains("tar") ||
                        mimeType.contains("gzip") ||
                        mimeType.contains("bzip2") ||
                        mimeType.contains("xz") ||
                        mimeType.contains("compress") ||
                        mimeType.contains("archive")
                )
    }

    fun isM3u8(url: String, fileName: String, mimeType: String?): Boolean {
        return url.contains(".m3u8", ignoreCase = true) ||
                fileName.endsWith(".m3u8", ignoreCase = true) ||
                mimeType == "application/x-mpegURL" ||
                mimeType == "application/vnd.apple.mpegurl"
    }
}