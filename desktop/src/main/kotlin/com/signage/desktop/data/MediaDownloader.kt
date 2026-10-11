package com.signage.desktop.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Bộ tải video thông minh trên Windows, tự bảo vệ dung lượng đĩa và chống tải lặp.
 */
class MediaDownloader {

    companion object {
        private const val MIN_FREE_SPACE_BYTES = 100L * 1024 * 1024 // Tối thiểu 100MB trống
    }

    val mediaDir: File by lazy {
        val appData = System.getenv("LOCALAPPDATA")
            ?: (System.getProperty("user.home") + File.separator + "AppData" + File.separator + "Local")
        val dir = File(appData, "SmartSignage" + File.separator + "media")
        if (!dir.exists()) dir.mkdirs()
        dir
    }

    private val cookieStore = ConcurrentHashMap<String, List<Cookie>>()
    private val cookieJar = object : CookieJar {
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            cookieStore[url.host] = cookies
        }
        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            return cookieStore[url.host] ?: emptyList()
        }
    }

    private val client: OkHttpClient = OkHttpClient.Builder()
        .cookieJar(cookieJar)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    @Volatile
    private var activeCall: Call? = null

    init {
        cleanOrphanTempFiles()
    }

    fun cancelActiveDownload() {
        try {
            activeCall?.cancel()
            activeCall = null
        } catch (_: Exception) {}
    }

    fun cleanOrphanTempFiles() {
        try {
            mediaDir.listFiles()?.forEach { file ->
                if (file.isFile && (file.name.endsWith(".download") || file.name.startsWith("temp_"))) {
                    file.delete()
                }
            }
        } catch (_: Exception) {}
    }

    fun purgeOldVideosExcept(activeFile: File?, fallbackFile: File? = null) {
        try {
            mediaDir.listFiles()?.forEach { file ->
                if (file.isFile) {
                    val isActive = activeFile != null && file.absolutePath == activeFile.absolutePath
                    val isFallback = fallbackFile != null && file.absolutePath == fallbackFile.absolutePath
                    val isActiveDownloading = file.name.endsWith(".download") && activeCall != null
                    if (!isActive && !isFallback && !isActiveDownloading) {
                        file.delete()
                    }
                }
            }
        } catch (_: Exception) {}
    }

    fun getCachedFileForUrl(url: String): File? {
        if (url.isBlank()) return null
        val normalizedKey = UrlHelper.getNormalizedCacheKey(url)
        val hashNormalized = md5(normalizedKey).take(12)
        val prefixNormalized = "signage_$hashNormalized"

        val hashRaw = md5(url.trim()).take(12)
        val prefixRaw = "signage_$hashRaw"

        return mediaDir.listFiles()?.firstOrNull { file ->
            file.isFile &&
            !file.name.endsWith(".download") &&
            file.length() > 0 &&
            (file.name.startsWith(prefixNormalized) || file.name.startsWith(prefixRaw))
        }
    }

    fun clearAllCache(): Boolean {
        return try {
            cancelActiveDownload()
            mediaDir.listFiles()?.forEach { it.delete() }
            true
        } catch (_: Exception) {
            false
        }
    }

    suspend fun downloadMedia(
        rawUrl: String,
        fallbackFile: File? = null,
        onProgress: (progress: Int, bytesRead: Long, totalBytes: Long) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        var tempFile: File? = null
        var isDownloadSuccessful = false

        try {
            cleanOrphanTempFiles()

            val freeSpace = mediaDir.usableSpace
            if (freeSpace < MIN_FREE_SPACE_BYTES) {
                return@withContext Result.failure(
                    Exception("Ổ đĩa sắp đầy (còn dưới 100MB trống). Không thể tải video!")
                )
            }

            if (UrlHelper.isImage(rawUrl)) {
                return@withContext Result.failure(
                    IllegalArgumentException("Ứng dụng chỉ hỗ trợ phát Video, không dùng ảnh!")
                )
            }

            if (UrlHelper.isYouTube(rawUrl)) {
                return@withContext Result.failure(
                    IllegalArgumentException("Link YouTube không thể tải trực tiếp file thô! Vui lòng dùng Google Drive hoặc file MP4 trực tiếp.")
                )
            }

            val directUrl = UrlHelper.transformToDirectDownloadUrl(rawUrl)
            val request = Request.Builder()
                .url(directUrl)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .build()

            cancelActiveDownload()
            var call = client.newCall(request)
            activeCall = call
            var response = call.execute()

            // Xử lý xác nhận tải file lớn của Google Drive
            var contentType = response.header("Content-Type")?.lowercase(Locale.ROOT) ?: ""
            if (response.isSuccessful && contentType.contains("text/html") && UrlHelper.isGoogleDrive(rawUrl)) {
                val html = response.body?.string() ?: ""
                response.close()

                val confirmUrl = extractGoogleDriveConfirmUrl(html, directUrl)
                if (!confirmUrl.isNullOrBlank()) {
                    val confirmRequest = Request.Builder()
                        .url(confirmUrl)
                        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                        .build()
                    call = client.newCall(confirmRequest)
                    activeCall = call
                    response = call.execute()
                    contentType = response.header("Content-Type")?.lowercase(Locale.ROOT) ?: ""
                } else {
                    return@withContext Result.failure(
                        Exception("Google Drive yêu cầu quyền truy cập công khai ('Bất kỳ ai có đường liên kết') hoặc đã hết giới hạn tải.")
                    )
                }
            }

            if (!response.isSuccessful) {
                val code = response.code
                val errorMsg = when (code) {
                    404 -> "Không tìm thấy file video (Lỗi 404 Not Found)."
                    403 -> "Không có quyền truy cập (Lỗi 403 Forbidden)."
                    500, 502, 503 -> "Máy chủ video gặp sự cố (HTTP $code)."
                    else -> "Tải thất bại: HTTP $code ${response.message}"
                }
                response.close()
                return@withContext Result.failure(Exception(errorMsg))
            }

            if (contentType.startsWith("image/")) {
                response.close()
                return@withContext Result.failure(
                    IllegalArgumentException("Máy chủ phản hồi đây là file Ảnh! Ứng dụng chỉ phát Video.")
                )
            }

            if (contentType.contains("text/html")) {
                response.close()
                return@withContext Result.failure(
                    IllegalArgumentException("Link này trỏ về trang Web (HTML) chứ không phải file video trực tiếp!")
                )
            }

            val body = response.body ?: run {
                response.close()
                return@withContext Result.failure(Exception("Nội dung rỗng từ máy chủ"))
            }

            val contentLength = body.contentLength()
            if (contentLength > 0 && freeSpace < (contentLength + MIN_FREE_SPACE_BYTES)) {
                response.close()
                return@withContext Result.failure(
                    Exception("Ổ đĩa không đủ dung lượng để lưu video này!")
                )
            }

            val targetFileName = generateFileName(rawUrl, response.header("Content-Disposition"))
            val targetFile = File(mediaDir, targetFileName)
            val temp = File(mediaDir, "$targetFileName.download")
            tempFile = temp

            var inputStream: InputStream? = null
            var outputStream: FileOutputStream? = null

            try {
                inputStream = body.byteStream()
                outputStream = FileOutputStream(temp)

                val buffer = ByteArray(64 * 1024)
                var bytesReadTotal: Long = 0
                var lastReportedProgress = -1
                var lastReportTimeMs = 0L
                var read: Int

                while (inputStream.read(buffer).also { read = it } != -1) {
                    if (!coroutineContext.isActive) {
                        throw InterruptedIOException("Tải video đã bị hủy")
                    }

                    outputStream.write(buffer, 0, read)
                    bytesReadTotal += read

                    val now = System.currentTimeMillis()
                    if (contentLength > 0) {
                        val progress = ((bytesReadTotal * 100) / contentLength).toInt().coerceIn(0, 100)
                        if ((progress != lastReportedProgress && now - lastReportTimeMs >= 100) || progress == 100) {
                            lastReportedProgress = progress
                            lastReportTimeMs = now
                            onProgress(progress, bytesReadTotal, contentLength)
                        }
                    } else {
                        if (now - lastReportTimeMs >= 200) {
                            lastReportTimeMs = now
                            onProgress(-1, bytesReadTotal, -1)
                        }
                    }
                }

                outputStream.flush()
            } finally {
                outputStream?.close()
                inputStream?.close()
                response.close()
                activeCall = null
            }

            if (temp.exists() && temp.length() > 0) {
                if (!isValidVideoFile(temp)) {
                    temp.delete()
                    return@withContext Result.failure(
                        Exception("Dữ liệu tải về không phải định dạng video hợp lệ (phản hồi HTML hoặc file hỏng)")
                    )
                }

                if (targetFile.exists()) targetFile.delete()
                val success = temp.renameTo(targetFile)
                if (success && targetFile.exists()) {
                    isDownloadSuccessful = true
                    return@withContext Result.success(targetFile)
                } else {
                    return@withContext Result.failure(Exception("Không thể ghi file video vào ổ đĩa"))
                }
            } else {
                return@withContext Result.failure(Exception("Dữ liệu tải về bị rỗng (0 bytes)"))
            }

        } catch (e: UnknownHostException) {
            return@withContext Result.failure(Exception("Không có kết nối Internet hoặc tên miền không tồn tại"))
        } catch (e: SocketTimeoutException) {
            return@withContext Result.failure(Exception("Kết nối quá thời gian chờ (Timeout)"))
        } catch (e: InterruptedIOException) {
            return@withContext Result.failure(Exception("Tạm dừng tải video do ngắt kết nối"))
        } catch (t: Throwable) {
            return@withContext Result.failure(Exception("Lỗi tải video: ${t.localizedMessage ?: "Không xác định"}"))
        } finally {
            activeCall = null
            if (!isDownloadSuccessful && tempFile != null && tempFile.exists()) {
                try { tempFile.delete() } catch (_: Exception) {}
            }
        }
    }

    private fun extractGoogleDriveConfirmUrl(html: String, originalUrl: String): String? {
        val hrefRegex = Regex("""href=["'](/uc\?export=download[^"']+)["']""")
        hrefRegex.find(html)?.let { match ->
            return "https://docs.google.com" + match.groupValues[1].replace("&amp;", "&")
        }

        val formActionRegex = Regex("""<form[^>]*id=["']download-form["'][^>]*action=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        formActionRegex.find(html)?.let { match ->
            var actionUrl = match.groupValues[1].replace("&amp;", "&")
            val inputRegex = Regex("""<input[^>]*type=["']hidden["'][^>]*name=["']([^"']+)["'][^>]*value=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            val params = mutableListOf<String>()
            inputRegex.findAll(html).forEach { inputMatch ->
                params.add("${inputMatch.groupValues[1]}=${inputMatch.groupValues[2]}")
            }
            if (params.isNotEmpty()) {
                actionUrl = if (actionUrl.contains("?")) "$actionUrl&${params.joinToString("&")}" else "$actionUrl?${params.joinToString("&")}"
            }
            return actionUrl
        }

        val confirmRegex = Regex("""confirm=([0-9a-zA-Z_-]+)""")
        confirmRegex.find(html)?.let { match ->
            val token = match.groupValues[1]
            val fileId = UrlHelper.extractGoogleDriveFileId(originalUrl)
            if (!fileId.isNullOrBlank()) {
                return "https://docs.google.com/uc?export=download&id=$fileId&confirm=$token"
            }
        }
        return null
    }

    private fun isValidVideoFile(file: File): Boolean {
        if (!file.exists() || file.length() < 1024) return false
        val header = ByteArray(64)
        try {
            FileInputStream(file).use { fis ->
                val read = fis.read(header)
                if (read < 16) return false
            }
        } catch (_: Exception) {
            return false
        }

        val headerStr = String(header, 0, minOf(32, header.size), Charsets.US_ASCII).lowercase(Locale.ROOT).trim()
        if (headerStr.startsWith("<!doc") ||
            headerStr.startsWith("<html") ||
            headerStr.startsWith("<?xml") ||
            headerStr.startsWith("{")
        ) {
            return false
        }
        return true
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

        val normalizedKey = UrlHelper.getNormalizedCacheKey(url)
        val hash = md5(normalizedKey)
        return "signage_${hash.take(12)}$ext"
    }

    private fun md5(input: String): String {
        val md = MessageDigest.getInstance("MD5")
        return md.digest(input.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }
}
