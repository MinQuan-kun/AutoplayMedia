package com.signage.player.data

import android.content.Context
import android.media.MediaMetadataRetriever
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.Call
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

    @Volatile
    private var activeCall: Call? = null

    /**
     * Hủy lập tức socket tải đang chạy ngầm để không bị xung đột tiến trình.
     */
    fun cancelActiveDownload() {
        try {
            activeCall?.cancel()
            activeCall = null
        } catch (_: Exception) {}
    }

    private val cookieStore = java.util.concurrent.ConcurrentHashMap<String, List<okhttp3.Cookie>>()
    private val cookieJar = object : okhttp3.CookieJar {
        override fun saveFromResponse(url: okhttp3.HttpUrl, cookies: List<okhttp3.Cookie>) {
            cookieStore[url.host()] = cookies
        }
        override fun loadForRequest(url: okhttp3.HttpUrl): List<okhttp3.Cookie> {
            return cookieStore[url.host()] ?: emptyList()
        }
    }

    private val client: OkHttpClient = TLSSocketFactory.enableTls12OnPreLollipop(
        OkHttpClient.Builder()
            .cookieJar(cookieJar)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
    ).build()

    // Thư mục lưu trữ video: Ưu tiên ExternalFilesDir để mediaserver có quyền đọc trực tiếp
    val mediaDir: File by lazy {
        val ext = context.getExternalFilesDir("signage_media")
        val dir = ext ?: File(context.filesDir, "signage_media")
        dir.apply {
            if (!exists()) mkdirs()
            setReadable(true, false)
            setExecutable(true, false)
        }
    }

    init {
        migrateLegacyInternalFiles()
        // Tự động dọn sạch rác sót lại từ các lần chạy trước ngay khi khởi tạo
        cleanOrphanTempFiles()
    }

    /**
     * Tự động chuyển đổi các video cũ từ thư mục nội bộ (filesDir) sang thư mục mở rộng (mediaDir)
     * để mediaserver có quyền đọc trực tiếp, tránh nhân đôi dung lượng.
     */
    private fun migrateLegacyInternalFiles() {
        try {
            val legacyDir = File(context.filesDir, "signage_media")
            if (legacyDir.exists() && legacyDir.absolutePath != mediaDir.absolutePath) {
                legacyDir.listFiles()?.forEach { file ->
                    if (file.isFile && file.length() > 0 && !file.name.endsWith(".download")) {
                        val destFile = File(mediaDir, file.name)
                        if (!destFile.exists()) {
                            file.copyTo(destFile, overwrite = true)
                            destFile.setReadable(true, false)
                        }
                    }
                    file.delete()
                }
                legacyDir.delete()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Lỗi khi chuyển đổi file cũ: ${e.message}")
        }
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
            // Dọn sạch thư mục cũ nếu còn sót lại
            val legacyDir = File(context.filesDir, "signage_media")
            if (legacyDir.exists() && legacyDir.absolutePath != mediaDir.absolutePath) {
                legacyDir.listFiles()?.forEach { if (it.isFile) it.delete() }
                legacyDir.delete()
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
            var freedBytes = 0L
            mediaDir.listFiles()?.forEach { file ->
                if (file.isFile) {
                    val isActive = activeFile != null && file.absolutePath == activeFile.absolutePath
                    val isFallback = fallbackFile != null && file.absolutePath == fallbackFile.absolutePath
                    val isActiveDownloading = file.name.endsWith(".download") && activeCall != null
                    if (!isActive && !isFallback && !isActiveDownloading) {
                        freedBytes += file.length()
                        if (file.delete()) deletedCount++
                    }
                }
            }
            if (deletedCount > 0) {
                Log.d(TAG, "Đã dọn sạch $deletedCount video/tệp cũ, giải phóng ${freedBytes / (1024 * 1024)} MB")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Lỗi khi dọn video cũ: ${e.message}")
        }
    }

    /**
     * Kiểm tra xem video theo đường dẫn URL đã được tải hoàn tất trong máy chưa.
     * Nhận diện thông minh theo tiền tố băm (Prefix Hash) bất kể phần mở rộng (.mp4, .mkv, .webm, .mov,...).
     */
    fun getCachedFileForUrl(url: String): File? {
        if (url.isBlank()) return null
        val normalizedKey = UrlHelper.getNormalizedCacheKey(url)
        val hashNormalized = md5(normalizedKey).take(12)
        val prefixNormalized = "signage_$hashNormalized"

        // Khóa băm phụ tương thích ngược với các bản cũ (tính theo raw url)
        val hashRaw = md5(url.trim()).take(12)
        val prefixRaw = "signage_$hashRaw"

        return mediaDir.listFiles()?.firstOrNull { file ->
            file.isFile &&
            !file.name.endsWith(".download") &&
            file.length() > 0 &&
            (file.name.startsWith(prefixNormalized) || file.name.startsWith(prefixRaw))
        }
    }

    /**
     * Xóa toàn bộ video và file đệm trong máy (khi người dùng bấm nút Xoá Cache).
     */
    fun clearAllCache(): Boolean {
        return try {
            cancelActiveDownload()
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

            // 2. Kiểm tra dung lượng bộ nhớ trống trên thiết bị (kiểm tra phân vùng lưu video)
            val freeSpace = mediaDir.usableSpace
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

            cancelActiveDownload()
            var call = client.newCall(request)
            activeCall = call
            var response = call.execute()

            // 6. Xử lý xác nhận tải file lớn của Google Drive (Virus scan confirmation token)
            var contentType = response.header("Content-Type")?.lowercase(Locale.ROOT) ?: ""
            if (response.isSuccessful && contentType.contains("text/html") && UrlHelper.isGoogleDrive(rawUrl)) {
                val html = response.body()?.string() ?: ""
                response.close()

                val confirmUrl = extractGoogleDriveConfirmUrl(html, directUrl)
                if (!confirmUrl.isNullOrBlank()) {
                    val confirmRequest = Request.Builder()
                        .url(confirmUrl)
                        .header("User-Agent", "Mozilla/5.0 (Android; Mobile; rv:109.0) Gecko/109.0 Firefox/119.0")
                        .build()
                    call = client.newCall(confirmRequest)
                    activeCall = call
                    response = call.execute()
                    contentType = response.header("Content-Type")?.lowercase(Locale.ROOT) ?: ""
                } else {
                    return@withContext Result.failure(
                        Exception("Google Drive yêu cầu quyền truy cập công khai ('Bất kỳ ai có đường liên kết') hoặc file đã vượt giới hạn tải trong ngày.")
                    )
                }
            }

            // 7. Kiểm tra mã phản hồi HTTP
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

            // 8. Kiểm tra Content-Type
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

                val buffer = ByteArray(32 * 1024)
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

            // 10. Đổi tên nguyên tử thành công
            if (temp.exists() && temp.length() > 0) {
                // Kiểm tra tính toàn vẹn và hợp lệ của video TRƯỚC KHI chuyển thành file chính thức
                if (!isValidVideoFile(temp)) {
                    temp.delete()
                    return@withContext Result.failure(
                        Exception("Dữ liệu tải về không phải định dạng video hợp lệ (tệp hỏng hoặc phản hồi HTML từ máy chủ)")
                    )
                }

                if (targetFile.exists()) targetFile.delete()
                val success = temp.renameTo(targetFile)
                if (success && targetFile.exists()) {
                    targetFile.setReadable(true, false)
                    targetFile.setWritable(true, false)
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
        } catch (e: InterruptedIOException) {
            return@withContext Result.failure(Exception("Tạm dừng tải video do ngắt kết nối hoặc đổi lệnh"))
        } catch (t: Throwable) {
            // Bắt mọi lỗi kể cả OutOfMemoryError để không bao giờ văng ứng dụng
            Log.e(TAG, "Lỗi nghiêm trọng khi tải video: ${t.message}", t)
            return@withContext Result.failure(Exception("Lỗi tải video: ${t.localizedMessage ?: "Không xác định"}"))
        } finally {
            activeCall = null
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

        val normalizedKey = UrlHelper.getNormalizedCacheKey(url)
        val hash = md5(normalizedKey)
        return "signage_${hash.take(12)}$ext"
    }

    private fun md5(input: String): String {
        val md = MessageDigest.getInstance("MD5")
        return md.digest(input.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    /**
     * Trích xuất đường dẫn hoặc mã xác nhận tải file lớn của Google Drive từ trang HTML cảnh báo virus.
     */
    private fun extractGoogleDriveConfirmUrl(html: String, originalUrl: String): String? {
        // 1. Tìm href="/uc?export=download..."
        val hrefRegex = Regex("""href=["'](/uc\?export=download[^"']+)["']""")
        hrefRegex.find(html)?.let { match ->
            val relative = match.groupValues[1].replace("&amp;", "&")
            return "https://docs.google.com$relative"
        }

        // 2. Tìm form action với query confirm
        val formActionRegex = Regex("""<form[^>]*id=["']download-form["'][^>]*action=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        formActionRegex.find(html)?.let { match ->
            var actionUrl = match.groupValues[1].replace("&amp;", "&")
            val inputRegex = Regex("""<input[^>]*type=["']hidden["'][^>]*name=["']([^"']+)["'][^>]*value=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            val params = mutableListOf<String>()
            inputRegex.findAll(html).forEach { inputMatch ->
                val name = inputMatch.groupValues[1]
                val value = inputMatch.groupValues[2]
                params.add("$name=$value")
            }
            if (params.isNotEmpty()) {
                actionUrl = if (actionUrl.contains("?")) "$actionUrl&${params.joinToString("&")}" else "$actionUrl?${params.joinToString("&")}"
            }
            return actionUrl
        }

        // 3. Tìm downloadUrl trong script JSON
        val dlRegex = Regex(""""downloadUrl"\s*:\s*"([^"]+)"""")
        dlRegex.find(html)?.let { match ->
            val raw = match.groupValues[1]
            return raw.replace("\\u003d", "=").replace("\\u0026", "&")
        }

        // 4. Tìm confirm token regex: confirm=([0-9a-zA-Z_-]+)
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

    /**
     * Kiểm tra tính hợp lệ của file video bằng cấu trúc byte ma thuật (Magic Bytes)
     * và MediaMetadataRetriever để đảm bảo file tải về là video thực sự, không phải HTML/text giả mạo.
     */
    private fun isValidVideoFile(file: File): Boolean {
        if (!file.exists() || file.length() < 1024) return false

        // 1. Kiểm tra 64 byte đầu tiên
        val header = ByteArray(64)
        try {
            FileInputStream(file).use { fis ->
                val read = fis.read(header)
                if (read < 16) return false
            }
        } catch (_: Exception) {
            return false
        }

        // Chặn tuyệt đối nếu nội dung bắt đầu bằng HTML / XML / JSON
        val headerStr = String(header, 0, minOf(32, header.size), Charsets.US_ASCII).lowercase(Locale.ROOT).trim()
        if (headerStr.startsWith("<!doc") ||
            headerStr.startsWith("<html") ||
            headerStr.startsWith("<?xml") ||
            headerStr.startsWith("{") ||
            headerStr.startsWith("<head") ||
            headerStr.startsWith("<body")
        ) {
            return false
        }

        // 2. Kiểm tra các định dạng video phổ biến qua Magic Bytes
        // MP4 / MOV: bytes 4..7 là "ftyp", "moov", "wide"
        val isMp4 = header.size >= 12 &&
            header[4] == 'f'.code.toByte() && header[5] == 't'.code.toByte() &&
            header[6] == 'y'.code.toByte() && header[7] == 'p'.code.toByte()

        // WebM / MKV: byte đầu là 0x1A 0x45 0xDF 0xA3
        val isMatroska = (header[0].toInt() and 0xFF == 0x1A) &&
            (header[1].toInt() and 0xFF == 0x45) &&
            (header[2].toInt() and 0xFF == 0xDF) &&
            (header[3].toInt() and 0xFF == 0xA3)

        // AVI: RIFF....AVI
        val isAvi = header.size >= 12 &&
            header[0] == 'R'.code.toByte() && header[1] == 'I'.code.toByte() &&
            header[2] == 'F'.code.toByte() && header[3] == 'F'.code.toByte() &&
            header[8] == 'A'.code.toByte() && header[9] == 'V'.code.toByte() &&
            header[10] == 'I'.code.toByte() && header[11] == ' '.code.toByte()

        // MPEG-TS: byte đầu là 0x47
        val isTs = (header[0].toInt() and 0xFF == 0x47)

        // FLV: FLV\x01
        val isFlv = header[0] == 'F'.code.toByte() && header[1] == 'L'.code.toByte() && header[2] == 'V'.code.toByte()

        val isStandardMagicBytes = isMp4 || isMatroska || isAvi || isTs || isFlv

        // 3. Xác thực sâu hơn bằng MediaMetadataRetriever của Android
        var retrieverValid = false
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            val hasVideo = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO)
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            if (hasVideo != null || (width > 0 && height > 0)) {
                retrieverValid = true
            }
        } catch (_: Exception) {
            // MediaMetadataRetriever có thể không hỗ trợ một số codec lạ nhưng nếu magic bytes đúng thì vẫn chấp nhận
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }

        return isStandardMagicBytes || retrieverValid
    }
}
