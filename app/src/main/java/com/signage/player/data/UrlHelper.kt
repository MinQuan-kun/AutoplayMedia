package com.signage.player.data

import java.util.Locale
import java.util.regex.Pattern

/**
 * Tiện ích xử lý và kiểm tra URL đầu vào:
 * 1. Nhận diện link Ảnh để từ chối (do ứng dụng chỉ chạy Video).
 * 2. Nhận diện link YouTube để cảnh báo (không tải được file thô offline).
 * 3. Tự động chuyển link Google Drive / Dropbox sang link tải trực tiếp.
 */
object UrlHelper {

    // Regex bóc tách ID file Google Drive từ các dạng link: /file/d/{ID} hoặc id={ID}
    private val GOOGLE_DRIVE_FILE_ID_PATTERN =
        Pattern.compile("/file/d/([a-zA-Z0-9_-]+)|id=([a-zA-Z0-9_-]+)")

    // Danh sách phần mở rộng của file ảnh cần chặn
    private val IMAGE_EXTENSIONS = listOf(
        ".jpg", ".jpeg", ".png", ".webp", ".gif", ".bmp", ".svg", ".tiff"
    )

    /**
     * Kiểm tra xem đường dẫn có kết thúc bằng định dạng ảnh không.
     */
    fun isImage(url: String): Boolean {
        val clean = cleanUrl(url)
        return IMAGE_EXTENSIONS.any { clean.endsWith(it) }
    }

    /**
     * Kiểm tra xem link có phải từ nền tảng YouTube không.
     * (YouTube dùng mã hóa luồng, không hỗ trợ tải trực tiếp file thô .mp4).
     */
    fun isYouTube(url: String): Boolean {
        val clean = url.lowercase(Locale.ROOT)
        return clean.contains("youtube.com") || clean.contains("youtu.be")
    }

    /**
     * Kiểm tra xem có phải link Google Drive không.
     */
    fun isGoogleDrive(url: String): Boolean {
        return url.contains("drive.google.com")
    }

    /**
     * Kiểm tra xem có phải link Dropbox không.
     */
    fun isDropbox(url: String): Boolean {
        return url.contains("dropbox.com")
    }

    /**
     * QUAN TRỌNG: Tự động chuyển đổi link xem trước sang link tải trực tiếp file gốc (Direct Raw Download).
     *
     * Ví dụ Google Drive:
     *   Từ: https://drive.google.com/file/d/FILE_ID/view?usp=sharing
     *   Thành: https://drive.usercontent.google.com/download?id=FILE_ID&export=download&confirm=t
     *
     * Ví dụ Dropbox:
     *   Chuyển tham số "dl=0" thành "dl=1" để kích hoạt tải file trực tiếp.
     */
    fun transformToDirectDownloadUrl(url: String): String {
        var clean = url.trim()

        // 1. Chuyển đổi Google Drive
        if (isGoogleDrive(clean)) {
            val matcher = GOOGLE_DRIVE_FILE_ID_PATTERN.matcher(clean)
            if (matcher.find()) {
                val fileId = matcher.group(1) ?: matcher.group(2)
                if (!fileId.isNullOrBlank()) {
                    return "https://drive.usercontent.google.com/download?id=$fileId&export=download&confirm=t"
                }
            }
        }

        // 2. Chuyển đổi Dropbox
        if (isDropbox(clean)) {
            clean = clean.replace("dl=0", "dl=1")
            if (!clean.contains("dl=1")) {
                clean = if (clean.contains("?")) "$clean&dl=1" else "$clean?dl=1"
            }
            return clean
        }

        return clean
    }

    /**
     * Chuẩn hóa URL, loại bỏ query param và hash để phân tích phần mở rộng (extension).
     */
    private fun cleanUrl(url: String): String {
        return url.lowercase(Locale.ROOT).split("?").first().split("#").first()
    }
}
