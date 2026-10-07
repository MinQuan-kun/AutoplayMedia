package com.signage.player.data

import android.content.Context
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
 * Bộ xử lý tải file Video chạy ngầm (Background Downloader):
 * - Sử dụng OkHttp với cơ chế theo dõi chuyển hướng (Redirects).
 * - Lưu trữ an toàn trong Internal Storage (không cần cấp quyền đọc ghi thẻ nhớ).
 * - Sử dụng file tạm (.download) để chống lỗi file bị hỏng nếu rớt mạng giữa chừng.
 */
class MediaDownloader(private val context: Context) {

    // Cấu hình OkHttp Client với timeout dài để tải file video dung lượng lớn
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    // Thư mục lưu trữ video nội bộ: /data/data/com.signage.player/files/signage_media
    private val mediaDir: File by lazy {
        File(context.filesDir, "signage_media").apply {
            if (!exists()) mkdirs()
        }
    }

    /**
     * QUAN TRỌNG: Hàm tải video chính chạy trên Coroutine Dispatchers.IO.
     * @param rawUrl Đường dẫn video đầu vào do người dùng nhập.
     * @param onProgress Callback thông báo tiến độ tải (% hoàn thành và số byte đã tải).
     * @return Result chứa File video đã tải xong hoặc Exception nếu có lỗi.
     */
    suspend fun downloadMedia(
        rawUrl: String,
        onProgress: (progress: Int, bytesRead: Long, totalBytes: Long) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            // 1. Kiểm tra nếu URL là ảnh -> Chặn ngay lập tức
            if (UrlHelper.isImage(rawUrl)) {
                return@withContext Result.failure(
                    IllegalArgumentException("Không thể sử dụng ảnh! Ứng dụng chỉ hỗ trợ video.")
                )
            }

            // 2. Kiểm tra nếu URL là YouTube -> Chặn và hướng dẫn
            if (UrlHelper.isYouTube(rawUrl)) {
                return@withContext Result.failure(
                    IllegalArgumentException("Link YouTube không thể tải trực tiếp file thô về máy do mã hoá bảo vệ luồng của Google! Vui lòng dùng link file trực tiếp (.mp4) hoặc Google Drive.")
                )
            }

            // 3. Tự động chuyển link Google Drive / Dropbox sang link tải trực tiếp
            val directUrl = UrlHelper.transformToDirectDownloadUrl(rawUrl)

            val request = Request.Builder()
                .url(directUrl)
                .header("User-Agent", "Mozilla/5.0 (Android; Mobile; rv:109.0) Gecko/109.0 Firefox/119.0")
                .build()

            val response = client.newCall(request).execute()

            // 4. Kiểm tra mã phản hồi HTTP từ máy chủ
            if (!response.isSuccessful) {
                val errorMsg = when (response.code) {
                    404 -> "Không tìm thấy file video (Lỗi 404 Not Found). Link có thể đã bị xoá."
                    403 -> "Không có quyền truy cập (Lỗi 403 Forbidden). Nếu dùng Google Drive, hãy bật quyền 'Bất kỳ ai có đường liên kết'."
                    500, 502, 503 -> "Máy chủ lưu trữ video đang gặp sự cố (HTTP ${response.code})."
                    else -> "Tải thất bại: HTTP ${response.code} ${response.message}"
                }
                response.close()
                return@withContext Result.failure(Exception(errorMsg))
            }

            // 5. Kiểm tra định dạng dữ liệu (Content-Type) trả về
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

            val body = response.body ?: run {
                response.close()
                return@withContext Result.failure(Exception("Nội dung rỗng từ máy chủ"))
            }

            // 6. Chuẩn bị file tạm và ghi dữ liệu theo từng khối (Chunk 8KB)
            val contentLength = body.contentLength()
            val targetFileName = generateFileName(rawUrl, response.header("Content-Disposition"))
            val targetFile = File(mediaDir, targetFileName)
            val tempFile = File(mediaDir, "$targetFileName.download")

            var inputStream: InputStream? = null
            var outputStream: FileOutputStream? = null

            try {
                inputStream = body.byteStream()
                outputStream = FileOutputStream(tempFile)

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
                        // Trường hợp máy chủ không gửi Content-Length (Chunked transfer)
                        onProgress(-1, bytesReadTotal, -1)
                    }
                }

                outputStream.flush()
            } finally {
                outputStream?.close()
                inputStream?.close()
                response.close()
            }

            // 7. QUAN TRỌNG: Chỉ khi tải đủ 100%, mới đổi tên file tạm thành file chính thức
            // Giúp ngăn ngừa lỗi người dùng phát phải file video bị cắt cụt do mất kết nối giữa chừng
            if (targetFile.exists()) {
                targetFile.delete()
            }
            if (!tempFile.renameTo(targetFile)) {
                tempFile.copyTo(targetFile, overwrite = true)
                tempFile.delete()
            }

            Result.success(targetFile)
        } catch (e: UnknownHostException) {
            Result.failure(Exception("Không thể kết nối máy chủ! Vui lòng kiểm tra lại đường truyền mạng hoặc tên miền URL."))
        } catch (e: SocketTimeoutException) {
            Result.failure(Exception("Hết thời gian chờ kết nối (Timeout). Mạng quá yếu hoặc máy chủ phản hồi chậm."))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Kiểm tra xem trong máy đã có sẵn bản sao của link này chưa (để phát Offline tức thì).
     */
    fun getCachedFileForUrl(url: String): File? {
        val targetName = generateFileName(url, null)
        val file = File(mediaDir, targetName)
        return if (file.exists() && file.length() > 0) file else null
    }

    /**
     * Xóa sạch toàn bộ video trong bộ nhớ đệm.
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
     * Tạo tên file an toàn dựa trên MD5 Hash của URL.
     */
    private fun generateFileName(url: String, contentDisposition: String?): String {
        val cleanUrl = url.split("?").first()
        val pathFileName = cleanUrl.substringAfterLast("/", "")
        
        val ext = when {
            pathFileName.contains(".") -> "." + pathFileName.substringAfterLast(".")
            else -> ".mp4" // Mặc định đuôi .mp4
        }

        val hash = md5(url).take(16)
        return "video_$hash$ext"
    }

    private fun md5(input: String): String {
        val bytes = MessageDigest.getInstance("MD5").digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
