package com.signage.desktop.server

import com.signage.desktop.data.PreferencesManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Quản lý đồng bộ link video từ Web REST API Server trên Windows.
 */
class RestApiSyncManager(
    private val prefs: PreferencesManager,
    private val onVideoUrlReceived: (String) -> Unit,
    private val onStatusChanged: (isSuccess: Boolean, message: String) -> Unit
) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val scope = CoroutineScope(Dispatchers.IO)
    private var syncJob: Job? = null

    fun startSync() {
        stopSync()
        syncJob = scope.launch {
            while (isActive) {
                fetchVideoUrl()
                val intervalMinutes = prefs.apiSyncIntervalMinutes.coerceAtLeast(1)
                delay(intervalMinutes * 60 * 1000L)
            }
        }
    }

    fun stopSync() {
        syncJob?.cancel()
        syncJob = null
    }

    private fun fetchVideoUrl() {
        val apiUrl = prefs.serverApiUrl
        if (apiUrl.isBlank() || (!apiUrl.startsWith("http://") && !apiUrl.startsWith("https://"))) {
            onStatusChanged(false, "Chưa nhập địa chỉ API hợp lệ")
            return
        }

        try {
            val urlWithParams = if (apiUrl.contains("?")) {
                "$apiUrl&device_id=${prefs.mqttDeviceId}"
            } else {
                "$apiUrl?device_id=${prefs.mqttDeviceId}"
            }

            val request = Request.Builder()
                .url(urlWithParams)
                .header("User-Agent", "SmartSignage-Desktop/2.0")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    onStatusChanged(false, "Lỗi HTTP: ${response.code}")
                    return
                }

                val body = response.body?.string() ?: ""
                var newUrl = ""

                try {
                    val json = JSONObject(body)
                    newUrl = when {
                        json.has("url") -> json.getString("url")
                        json.has("video_url") -> json.getString("video_url")
                        json.has("media_url") -> json.getString("media_url")
                        json.has("link") -> json.getString("link")
                        else -> ""
                    }
                } catch (_: Exception) {
                    val clean = body.trim()
                    if (clean.startsWith("http://") || clean.startsWith("https://")) {
                        newUrl = clean
                    }
                }

                if (newUrl.isNotBlank()) {
                    onStatusChanged(true, "Đồng bộ thành công (${java.text.SimpleDateFormat("HH:mm:ss").format(java.util.Date())})")
                    onVideoUrlReceived(newUrl.trim())
                } else {
                    onStatusChanged(false, "Không tìm thấy trường video_url trong JSON")
                }
            }
        } catch (e: Exception) {
            onStatusChanged(false, "Lỗi đồng bộ: ${e.message}")
        }
    }
}
