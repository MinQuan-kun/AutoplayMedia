package com.signage.player.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * ==============================================================================
 * BỘ TẢI FILE VIDEO THÔNG MINH & TỰ ĐỘNG DỌN RÁC (CRASH-PROOF & STORAGE-SAFE)
 * ==============================================================================
 * Các cơ chế bảo vệ dung lượng và chống văng bộ nhớ:
 * 1. Tự động dọn sạch file tạm (.download) mồ côi ngay khi mở app hoặc trước khi tải.
 * 2. Xóa ngay file tạm nếu quá trình tải bị đứt mạng hoặc gặp sự cố (không để rác đọng lại).
 * 3. Kiểm tra dung lượng bộ nhớ trống (usableSpace) trước khi tải để tránh tràn ổ đĩa.
 * 4. Tự động xoá sạch các video cũ đã phát trước đó, chỉ giữ duy nhất video đang chạy.
 * 5. Bắt toàn bộ lỗi (kể cả OutOfMemoryError) để đảm bảo ứng dụng KHÔNG BAO GIỜ BỊ VĂNG.
 */
class MediaDownloader(private val context: Context) {

    companion object {
        private const val TAG = "MediaDownloader"
        private const val MIN_FREE_SPACE_BYTES = 50L * 1024 * 1024 // Tối thiểu phải còn 50MB trống
    }

    private val client: OkHttpClient = TLSSocketFactory.enableTls12OnPreLollipop(
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
    ).build()

    // Thư mục lưu trữ video: /data/data/com.signage.player/files/signage_media
    private val mediaDir: File by lazy {
        File(context.filesDir, "signage_media").apply {
            if (!exists()) mkdirs()
        }
    }

    init {
        // Tự động dọn sạch rác sót lại từ các lần chạy trước ngay khi khởi tạo
        cleanOrphanTempFiles()
    }

    /**
     * Dọn sạch toàn bộ các file tạm (.download hoặc temp_) bị bỏ dở do mất điện/văng app.
     */
    fun cleanOrphanTempFiles() {
        try {
            var deletedCount = 0
            var freedBytes = 0L
            mediaDir.listFiles()?.forEach { file ->
                if (file.isFile && (file.name.endsWith(".download") || file.name.startsWith("temp_"))) {
                    freedBytes += file.length()
                    if (file.delete()) deletedCount++
                }
            }
            if (deletedCount > 0) {
                val mb = freedBytes / (1024 * 1024)
                Log.d(TAG, "Đã dọn dẹp $deletedCount file tạm mồ côi, giải phóng $mb MB bộ nhớ")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Lỗi khi dọn file tạm: ${e.message}")
        }
    }

    /**
     * Tự động xóa các video cũ, CHỈ GIỮ LẠI DUY NHẤT file video đang chạy hiện tại.
     * Ngăn chặn tình trạng đổi nhiều video làm phình to dung lượng ứng dụng theo thời gian.
     */
    fun purgeOldVideosExcept(activeFile: File?, fallbackFile: File? = null) {
        try {
            var deletedCount = 0
            mediaDir.listFiles()?.forEach { file ->
                if (file.isFile && !file.name.endsWith(".download")) {
                    val isActive = activeFile != null && file.absolutePath == activeFile.absolutePath
                    val isFallback = fallbackFile != null && file.absolutePath == fallbackFile.absolutePath
                    if (!isActive && !isFallback) {
                        if (file.delete()) deletedCount++
                    }
                }
            }
            if (deletedCount > 0) {
                Log.d(TAG, "Đã dọn sạch $deletedCount video cũ để tối ưu dung lượng bộ nhớ (vẫn giữ bản dự phòng an toàn)")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Lỗi khi dọn video cũ: ${e.message}")
        }
    }

    /**
     * Kiểm tra xem video theo đường dẫn URL đã được tải hoàn tất trong máy chưa.
     */
    fun getCachedFileForUrl(url: String): File? {
        if (url.isBlank()) return null
        val targetFile = File(mediaDir, generateFileName(url, null))
        return if (targetFile.exists() && targetFile.length() > 0) targetFile else null
    }

    /**
     * Xóa toàn bộ video và file đệm trong máy (khi người dùng bấm nút Xoá Cache).
     */
    fun clearAllCache(): Boolean {
        return try {
            mediaDir.listFiles()?.forEach { it.delete() }
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Tải file video chạy ngầm với cơ chế tự bảo vệ bộ nhớ máy.
     */
    suspend fun downloadMedia(
        rawUrl: String,
        fallbackFile: File? = null,
        onProgress: (progress: Int, bytesRead: Long, totalBytes: Long) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        var tempFile: File? = null
        var isDownloadSuccessful = false

        try {
            // 1. Dọn dẹp rác mồ côi trước khi bắt đầu tải mới
            cleanOrphanTempFiles()

            // 2. Kiểm tra dung lượng bộ nhớ trống trên thiết bị
            val freeSpace = context.filesDir.usableSpace
            if (freeSpace < MIN_FREE_SPACE_BYTES) {
                return@withContext Result.failure(
                    Exception("Bộ nhớ máy sắp đầy (chỉ còn dưới 50MB trống). Không thể tải video!")
                )
            }

            // 3. Chặn ảnh
            if (UrlHelper.isImage(rawUrl)) {
                return@withContext Result.failure(
                    IllegalArgumentException("Không thể sử dụng ảnh! Ứng dụng chỉ hỗ trợ video.")
                )
            }

            // 4. Chặn link YouTube thô
            if (UrlHelper.isYouTube(rawUrl)) {
                return@withContext Result.failure(
                    IllegalArgumentException("Link YouTube không thể tải trực tiếp file thô! Vui lòng dùng link file trực tiếp (.mp4) hoặc Google Drive.")
                )
            }

            // 5. Chuyển đổi link Google Drive / Dropbox sang link tải trực tiếp
            val directUrl = UrlHelper.transformToDirectDownloadUrl(rawUrl)

            val request = Request.Builder()
                .url(directUrl)
                .header("User-Agent", "Mozilla/5.0 (Android; Mobile; rv:109.0) Gecko/109.0 Firefox/119.0")
                .build()

            val response = client.newCall(request).execute()

            // 6. Kiểm tra mã phản hồi HTTP
            if (!response.isSuccessful) {
                val code = response.code()
                val errorMsg = when (code) {
                    404 -> "Không tìm thấy file video (Lỗi 404 Not Found). Link có thể đã bị xoá."
                    403 -> "Không có quyền truy cập (Lỗi 403 Forbidden). Nếu dùng Google Drive, hãy bật quyền 'Bất kỳ ai có đường liên kết'."
                    500, 502, 503 -> "Máy chủ lưu trữ video đang gặp sự cố (HTTP $code)."
                    else -> "Tải thất bại: HTTP $code ${response.message()}"
                }
                response.close()
                return@withContext Result.failure(Exception(errorMsg))
            }

            // 7. Kiểm tra Content-Type
            val contentType = response.header("Content-Type")?.lowercase(Locale.ROOT) ?: ""
            if (contentType.startsWith("image/")) {
                response.close()
                return@withContext Result.failure(
                    IllegalArgumentException("Máy chủ phản hồi đây là file Ảnh! Ứng dụng chỉ hỗ trợ Video (.mp4, .mkv, .webm,...).")
                )
            }

            if (contentType.contains("text/html")) {
                response.close()
                return@withContext Result.failure(
                    IllegalArgumentException("Link này trỏ về một trang Web (HTML) chứ không phải file video trực tiếp! Nếu dùng Google Drive, hãy kiểm tra quyền chia sẻ công khai.")
                )
            }

            val body = response.body() ?: run {
                response.close()
                return@withContext Result.failure(Exception("Nội dung rỗng từ máy chủ"))
            }

            val contentLength = body.contentLength()

            // 8. Kiểm tra xem đĩa có đủ dung lượng cho file video này không
            if (contentLength > 0 && freeSpace < (contentLength + MIN_FREE_SPACE_BYTES)) {
                response.close()
                val needMb = (contentLength + MIN_FREE_SPACE_BYTES) / (1024 * 1024)
                val haveMb = freeSpace / (1024 * 1024)
                return@withContext Result.failure(
                    Exception("Bộ nhớ thiết bị không đủ! Cần $needMb MB nhưng máy chỉ còn $haveMb MB.")
                )
            }

            // 9. Chuẩn bị file tạm (.download)
            val targetFileName = generateFileName(rawUrl, response.header("Content-Disposition"))
            val targetFile = File(mediaDir, targetFileName)
            val temp = File(mediaDir, "$targetFileName.download")
            tempFile = temp

            var inputStream: InputStream? = null
            var outputStream: FileOutputStream? = null

            try {
                inputStream = body.byteStream()
                outputStream = FileOutputStream(temp)

                val buffer = ByteArray(8 * 1024)
                var bytesReadTotal: Long = 0
                var read: Int

                while (inputStream.read(buffer).also { read = it } != -1) {
                    outputStream.write(buffer, 0, read)
                    bytesReadTotal += read

                    if (contentLength > 0) {
                        val progress = ((bytesReadTotal * 100) / contentLength).toInt().coerceIn(0, 100)
                        onProgress(progress, bytesReadTotal, contentLength)
                    } else {
                        onProgress(-1, bytesReadTotal, -1)
                    }
                }

                outputStream.flush()
            } finally {
                outputStream?.close()
                inputStream?.close()
                response.close()
            }

            // 10. Đổi tên nguyên tử thành công
            if (temp.exists() && temp.length() > 0) {
                if (targetFile.exists()) targetFile.delete()
                val success = temp.renameTo(targetFile)
                if (success && targetFile.exists()) {
                    isDownloadSuccessful = true
                    // Giữ nguyên toàn bộ video cũ, CHỈ dọn dẹp khi video mới đã thực sự phát trên màn hình!
                    return@withContext Result.success(targetFile)
                } else {
                    return@withContext Result.failure(Exception("Không thể ghi file video vào bộ nhớ hệ thống"))
                }
            } else {
                return@withContext Result.failure(Exception("Dữ liệu tải về bị rỗng (0 bytes)"))
            }

        } catch (e: UnknownHostException) {
            return@withContext Result.failure(Exception("Không có kết nối Internet hoặc tên miền không tồn tại"))
        } catch (e: SocketTimeoutException) {
            return@withContext Result.failure(Exception("Kết nối quá thời gian chờ (Timeout). Vui lòng thử lại với mạng ổn định hơn"))
        } catch (t: Throwable) {
            // Bắt mọi lỗi kể cả OutOfMemoryError để không bao giờ văng ứng dụng
            Log.e(TAG, "Lỗi nghiêm trọng khi tải video: ${t.message}", t)
            return@withContext Result.failure(Exception("Lỗi tải video: ${t.localizedMessage ?: "Không xác định"}"))
        } finally {
            // QUAN TRỌNG NHẤT: Nếu tải thất bại hoặc bị ngắt ngang, XÓA NGAY LẬP TỨC file .download dở dang!
            if (!isDownloadSuccessful && tempFile != null && tempFile.exists()) {
                try {
                    tempFile.delete()
                    Log.d(TAG, "Đã xóa ngay file tạm dở dang để bảo vệ dung lượng bộ nhớ máy")
                } catch (e: Exception) {
                    // Ignore
                }
            }
        }
    }

    private fun generateFileName(url: String, contentDisposition: String?): String {
        var ext = ".mp4"
        contentDisposition?.let { cd ->
            val match = Regex("""filename=["']?([^"';]+)["']?""").find(cd)
            match?.groups?.get(1)?.value?.let { name ->
                val dotIndex = name.lastIndexOf('.')
                if (dotIndex != -1) ext = name.substring(dotIndex)
            }
        }

        if (ext == ".mp4") {
            val cleanUrl = url.substringBefore('?').substringBefore('#')
            val dotIndex = cleanUrl.lastIndexOf('.')
            if (dotIndex != -1 && dotIndex > cleanUrl.lastIndexOf('/')) {
                val candidate = cleanUrl.substring(dotIndex)
                if (candidate.length in 3..5) ext = candidate
            }
        }

        val hash = md5(url)
        return "signage_${hash.take(12)}$ext"
    }

    private fun md5(input: String): String {
        val md = MessageDigest.getInstance("MD5")
        return md.digest(input.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }
}
