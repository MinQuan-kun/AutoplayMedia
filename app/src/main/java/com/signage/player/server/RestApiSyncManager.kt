package com.signage.player.server

import android.content.Context
import android.util.Log
import com.signage.player.data.PreferencesManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

/**
 * ==============================================================================
 * BỘ QUẢN LÝ ĐỒNG BỘ HTTP REST API (DÀNH CHO SERVER WEB)
 * ==============================================================================
 * Dành cho trường hợp có hệ thống Web ERP/CRM/CMS (PHP, Node.js, .NET...)
 * và muốn thiết bị định kỳ gọi lên Server để lấy link video mới nhất.
 *
 * Cách thức hoạt động:
 * 1. Ứng dụng gửi HTTP GET đến đường dẫn API.
 *    Kèm Header "X-Device-Id" để máy chủ phân biệt từng máy lọc nước.
 * 2. Máy chủ trả về JSON: {"videoUrl": "https://..."} hoặc URL text thẳng.
 * 3. Ứng dụng tự động đọc link và chuyển video mới nếu có thay đổi.
 */
class RestApiSyncManager(
    context: Context,
    private val onVideoUrlReceived: (newUrl: String) -> Unit,
    private val onStatusChanged: (isSuccess: Boolean, message: String) -> Unit
) {

    private val prefs = PreferencesManager(context)
    private val scope = CoroutineScope(Dispatchers.IO)
    private var syncJob: Job? = null

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    companion object {
        private const val TAG = "RestApiSyncManager"
        private val URL_JSON_PATTERN = Pattern.compile("\"(?:videoUrl|url)\"\\s*:\\s*\"([^\"]+)\"")
    }

    /**
     * Bắt đầu chu kỳ định kỳ gọi lên Server API (chạy ngay lần đầu, sau đó lặp lại theo phút).
     */
    fun startSync() {
        val apiUrl = prefs.serverApiUrl.trim()
        if (apiUrl.isBlank()) {
            onStatusChanged(false, "Chưa cấu hình đường dẫn Server API")
            return
        }

        syncJob?.cancel()
        syncJob = scope.launch {
            val intervalMinutes = prefs.apiSyncIntervalMinutes.coerceAtLeast(5)
            val delayMillis = intervalMinutes * 60 * 1000L

            Log.d(TAG, "Bắt đầu đồng bộ REST API: $apiUrl (Chu kỳ mỗi $intervalMinutes phút)")

            while (isActive) {
                fetchLatestVideoFromApi(apiUrl)
                delay(delayMillis)
            }
        }
    }

    /**
     * Gửi 1 request lên Server API để lấy link video.
     */
    suspend fun fetchLatestVideoFromApi(apiUrl: String) = withContext(Dispatchers.IO) {
        try {
            onStatusChanged(false, "Đang kiểm tra Server API...")

            // Tạo request kèm định danh Device ID trong Header
            val request = Request.Builder()
                .url(apiUrl)
                .header("X-Device-Id", prefs.mqttDeviceId)
                .header("Accept", "application/json, text/plain")
                .build()

            val response = client.newCall(request).execute()

            if (!response.isSuccessful) {
                val errorMsg = "Server trả về lỗi HTTP ${response.code}"
                Log.w(TAG, errorMsg)
                onStatusChanged(false, errorMsg)
                response.close()
                return@withContext
            }

            val bodyString = response.body?.string()?.trim() ?: ""
            response.close()

            if (bodyString.isBlank()) {
                onStatusChanged(false, "Nội dung trả về từ Server rỗng")
                return@withContext
            }

            // Bóc tách link từ JSON hoặc chuỗi text
            var extractedUrl = bodyString
            if (bodyString.startsWith("{") && bodyString.endsWith("}")) {
                val matcher = URL_JSON_PATTERN.matcher(bodyString)
                if (matcher.find()) {
                    extractedUrl = matcher.group(1) ?: ""
                }
            }

            extractedUrl = extractedUrl.removeSurrounding("\"").trim()

            if (extractedUrl.startsWith("http://") || extractedUrl.startsWith("https://")) {
                Log.d(TAG, "Đã nhận được link video từ API: $extractedUrl")
                onStatusChanged(true, "Kết nối Server API thành công")
                onVideoUrlReceived(extractedUrl)
            } else {
                onStatusChanged(false, "Server không trả về URL video hợp lệ")
            }

        } catch (e: Exception) {
            Log.e(TAG, "Lỗi gọi Server API: ${e.message}")
            onStatusChanged(false, "Lỗi kết nối API: ${e.localizedMessage}")
        }
    }

    /**
     * Dừng tiến trình gọi định kỳ khi tắt app hoặc chuyển chế độ khác.
     */
    fun stopSync() {
        syncJob?.cancel()
        syncJob = null
    }
}
