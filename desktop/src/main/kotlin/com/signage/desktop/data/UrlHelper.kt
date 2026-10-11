package com.signage.desktop.data

import java.util.Locale
import java.util.regex.Pattern

/**
 * Tiện ích xử lý và chuẩn hóa URL đầu vào cho bản Desktop Windows.
 */
object UrlHelper {

    private val GOOGLE_DRIVE_FILE_ID_PATTERN =
        Pattern.compile("/file/d/([a-zA-Z0-9_-]+)|id=([a-zA-Z0-9_-]+)")

    private val IMAGE_EXTENSIONS = listOf(
        ".jpg", ".jpeg", ".png", ".webp", ".gif", ".bmp", ".svg", ".tiff"
    )

    fun isImage(url: String): Boolean {
        val clean = cleanUrl(url)
        return IMAGE_EXTENSIONS.any { clean.endsWith(it) }
    }

    fun isYouTube(url: String): Boolean {
        val clean = url.lowercase(Locale.ROOT)
        return clean.contains("youtube.com") || clean.contains("youtu.be")
    }

    fun isGoogleDrive(url: String): Boolean {
        val lower = url.lowercase(Locale.ROOT)
        return lower.contains("drive.google.com") || lower.contains("docs.google.com") || lower.contains("drive.usercontent.google.com")
    }

    fun extractGoogleDriveFileId(url: String): String? {
        if (!isGoogleDrive(url)) return null
        val matcher = GOOGLE_DRIVE_FILE_ID_PATTERN.matcher(url.trim())
        if (matcher.find()) {
            return matcher.group(1) ?: matcher.group(2)
        }
        return null
    }

    fun getNormalizedCacheKey(rawUrl: String): String {
        val trimmed = rawUrl.trim()
        val gDriveId = extractGoogleDriveFileId(trimmed)
        if (!gDriveId.isNullOrBlank()) {
            return "gdrive_$gDriveId"
        }
        return cleanUrl(trimmed)
    }

    fun isDropbox(url: String): Boolean {
        return url.contains("dropbox.com")
    }

    fun transformToDirectDownloadUrl(url: String): String {
        var clean = url.trim()

        if (isGoogleDrive(clean)) {
            val fileId = extractGoogleDriveFileId(clean)
            if (!fileId.isNullOrBlank()) {
                return "https://docs.google.com/uc?export=download&id=$fileId"
            }
        }

        if (isDropbox(clean)) {
            clean = clean.replace("dl=0", "dl=1")
            if (!clean.contains("dl=1")) {
                clean = if (clean.contains("?")) "$clean&dl=1" else "$clean?dl=1"
            }
            return clean
        }

        return clean
    }

    fun cleanUrl(url: String): String {
        return url.lowercase(Locale.ROOT).split("?").first().split("#").first()
    }
}
