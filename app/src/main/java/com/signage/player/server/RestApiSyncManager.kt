package com.signage.player.server

import android.content.Context
import android.util.Log
import com.signage.player.data.PreferencesManager
import com.signage.player.data.TLSSocketFactory
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
 * BỘ QUẢN LÝ ĐỒNG BỘ HTTP REST API
 * ==============================================================================
 * Tương thích từ Android 4.3 (Jelly Bean) đến Android 14/15:
 * - Hỗ trợ TLS 1.2 kết nối HTTPS an toàn.
 * - Định kỳ gửi HTTP GET lên Server API kèm Header "X-Device-Id".
 */
class RestApiSyncManager(
    context: Context,
    private val onVideoUrlReceived: (newUrl: String) -> Unit,
    private val onStatusChanged: (isSuccess: Boolean, message: String) -> Unit
) {

    private val prefs = PreferencesManager(context)
    private val scope = CoroutineScope(Dispatchers.IO)
    private var syncJob: Job? = null

    private val client: OkHttpClient = TLSSocketFactory.enableTls12OnPreLollipop(
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
    ).build()

    companion object {
        private const val TAG = "RestApiSyncManager"
        private val URL_JSON_PATTERN = Pattern.compile("\"(?:videoUrl|url)\"\\s*:\\s*\"([^\"]+)\"")
    }

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

    suspend fun fetchLatestVideoFromApi(apiUrl: String) = withContext(Dispatchers.IO) {
        try {
            onStatusChanged(false, "Đang kiểm tra Server API...")

            val request = Request.Builder()
                .url(apiUrl)
                .header("X-Device-Id", prefs.mqttDeviceId)
                .header("Accept", "application/json, text/plain")
                .build()

            val response = client.newCall(request).execute()

            if (!response.isSuccessful) {
                val errorMsg = "Server trả về lỗi HTTP ${response.code()}"
                Log.w(TAG, errorMsg)
                onStatusChanged(false, errorMsg)
                response.close()
                return@withContext
            }

            val bodyString = response.body()?.string()?.trim() ?: ""
            response.close()

            if (bodyString.isBlank()) {
                onStatusChanged(false, "Nội dung trả về từ Server rỗng")
                return@withContext
            }

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
                val errorMsg = "Phản hồi từ Server không chứa URL hợp lệ"
                Log.w(TAG, errorMsg)
                onStatusChanged(false, errorMsg)
            }

        } catch (e: Exception) {
            val errorMsg = "Lỗi kết nối Server API: ${e.message}"
            Log.e(TAG, errorMsg, e)
            onStatusChanged(false, errorMsg)
        }
    }

    fun stopSync() {
        syncJob?.cancel()
        syncJob = null
        Log.d(TAG, "Đã dừng đồng bộ REST API")
    }
}
