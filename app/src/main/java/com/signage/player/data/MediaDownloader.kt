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
 * ==============================================================================
 * BỘ TẢI FILE VIDEO CHẠY NGẦM (BACKGROUND DOWNLOADER)
 * ==============================================================================
 * Tương thích từ Android 4.3 (Jelly Bean) đến Android 14/15 mới nhất:
 * - Kích hoạt TLS 1.2 cho Android 4.3/4.4 để tải an toàn qua HTTPS (Google Drive, Cloud).
 * - Lưu trữ an toàn trong Internal Storage (không cần quyền truy cập thẻ nhớ).
 * - Sử dụng file tạm (.download) và cơ chế đổi tên nguyên tử (Atomic Rename).
 */
class MediaDownloader(private val context: Context) {

    private val client: OkHttpClient = TLSSocketFactory.enableTls12OnPreLollipop(
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
    ).build()

    private val mediaDir: File by lazy {
        File(context.filesDir, "signage_media").apply {
            if (!exists()) mkdirs()
        }
    }

    /**
     * Tải video ngầm qua Coroutine IO.
     */
    suspend fun downloadMedia(
        rawUrl: String,
        onProgress: (progress: Int, bytesRead: Long, totalBytes: Long) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            // 1. Chặn file ảnh
            if (UrlHelper.isImage(rawUrl)) {
                return@withContext Result.failure(
                    IllegalArgumentException("Không thể sử dụng ảnh! Ứng dụng chỉ hỗ trợ video.")
                )
            }

            // 2. Chặn link YouTube thô
            if (UrlHelper.isYouTube(rawUrl)) {
                return@withContext Result.failure(
                    IllegalArgumentException("Link YouTube không thể tải trực tiếp file thô! Vui lòng dùng link file trực tiếp (.mp4) hoặc Google Drive.")
                )
            }

            // 3. Tự chuyển đổi link Google Drive / Dropbox sang link tải trực tiếp
            val directUrl = UrlHelper.transformToDirectDownloadUrl(rawUrl)

            val request = Request.Builder()
                .url(directUrl)
                .header("User-Agent", "Mozilla/5.0 (Android; Mobile; rv:109.0) Gecko/109.0 Firefox/119.0")
                .build()

            val response = client.newCall(request).execute()

            // 4. Kiểm tra mã phản hồi HTTP
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

            // 5. Kiểm tra Content-Type
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

            // 6. Ghi file tạm
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
                        onProgress(-1, bytesReadTotal, -1)
                    }
                }

                outputStream.flush()
            } finally {
                outputStream?.close()
                inputStream?.close()
                response.close()
            }

            // 7. Đổi tên nguyên tử
            if (tempFile.exists() && tempFile.length() > 0) {
                if (targetFile.exists()) targetFile.delete()
                val success = tempFile.renameTo(targetFile)
                if (success && targetFile.exists()) {
                    return@withContext Result.success(targetFile)
                } else {
                    return@withContext Result.failure(Exception("Không thể lưu file video vào bộ nhớ thiết bị"))
                }
            } else {
                tempFile.delete()
                return@withContext Result.failure(Exception("Dữ liệu tải về bị rỗng (0 bytes)"))
            }

        } catch (e: UnknownHostException) {
            return@withContext Result.failure(Exception("Không thể kết nối Internet hoặc tên miền máy chủ không tồn tại"))
        } catch (e: SocketTimeoutException) {
            return@withContext Result.failure(Exception("Kết nối quá thời gian chờ (Timeout). Vui lòng thử lại với mạng ổn định hơn"))
        } catch (e: Exception) {
            return@withContext Result.failure(e)
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

    fun getCachedFileForUrl(url: String): File? {
        val targetFile = File(mediaDir, generateFileName(url, null))
        return if (targetFile.exists() && targetFile.length() > 0) targetFile else null
    }

    fun clearAllCachedVideos(): Boolean {
        return try {
            mediaDir.listFiles()?.forEach { it.delete() }
            true
        } catch (e: Exception) {
            false
        }
    }

    fun clearAllCache(): Boolean {
        return clearAllCachedVideos()
    }
}
