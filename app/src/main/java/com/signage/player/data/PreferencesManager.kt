package com.signage.player.data

import android.content.Context
import android.content.SharedPreferences
import java.util.UUID

/**
 * ==============================================================================
 * CẤU HÌNH KẾT NỐI
 * ==============================================================================
 * Quản lý thông tin cấu hình cho cả 3 chế độ hoạt động:
 * 1. Chế độ MQTT: Server MQTT (Broker URL, User, Pass, Topic).
 * 2. Chế độ REST API: Server Web (API URL).
 * 3. Chế độ Thủ công: Nhập link video trực tiếp (Google Drive, MP4...).
 */
class PreferencesManager(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val PREFS_NAME = "smart_signage_prefs"

        // Chế độ đồng bộ
        private const val KEY_SYNC_MODE = "key_sync_mode"
        const val MODE_MQTT = "MQTT"          // Chế độ 1: Server MQTT thời gian thực (< 1s)
        const val MODE_HTTP_API = "HTTP_API"  // Chế độ 2: Server Web REST API (định kỳ kéo link)
        const val MODE_DIRECT = "DIRECT"      // Chế độ 3: Nhập link video thủ công

        // Cấu hình video & hệ thống
        private const val KEY_MEDIA_URL = "key_media_url"
        private const val KEY_AUTO_BOOT = "key_auto_boot"
        private const val KEY_LAST_CACHED_FILE = "key_last_cached_file"

        // Cấu hình MQTT Server
        private const val KEY_MQTT_BROKER = "key_mqtt_broker"
        private const val KEY_MQTT_USERNAME = "key_mqtt_username"
        private const val KEY_MQTT_PASSWORD = "key_mqtt_password"
        private const val KEY_MQTT_DEVICE_ID = "key_mqtt_device_id"
        private const val KEY_MQTT_TOPIC = "key_mqtt_topic"

        // Cấu hình HTTP REST API Server
        private const val KEY_SERVER_API_URL = "key_server_api_url"
        private const val KEY_API_SYNC_INTERVAL = "key_api_sync_interval"
    }

    /**
     * Chế độ kết nối đang được lựa chọn (MODE_MQTT, MODE_HTTP_API, hoặc MODE_DIRECT).
     */
    var syncMode: String
        get() = prefs.getString(KEY_SYNC_MODE, MODE_MQTT) ?: MODE_MQTT
        set(value) = prefs.edit().putString(KEY_SYNC_MODE, value).apply()

    /**
     * URL video hiện tại đang được phát.
     */
    var mediaUrl: String
        get() = prefs.getString(KEY_MEDIA_URL, "") ?: ""
        set(value) = prefs.edit().putString(KEY_MEDIA_URL, value.trim()).apply()

    /**
     * Tự động khởi động khi cắm nguồn thiết bị (Mặc định luôn bật cho Kiosk).
     */
    var isAutoBootEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUTO_BOOT, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_BOOT, value).apply()

    /**
     * Đường dẫn file video đã tải lưu trong bộ nhớ máy (Offline).
     */
    var lastCachedFilePath: String?
        get() = prefs.getString(KEY_LAST_CACHED_FILE, null)
        set(value) = prefs.edit().putString(KEY_LAST_CACHED_FILE, value).apply()

    // --------------------------------------------------------------------------
    // CẤU HÌNH TRƯỜNG HỢP 1: SERVER MQTT
    // --------------------------------------------------------------------------

    /**
     * Địa chỉ Broker MQTT (Ví dụ: "tcp://broker.hivemq.com:1883" hoặc "tcp://mqtt.user.com:1883").
     */
    var mqttBrokerUrl: String
        get() = prefs.getString(KEY_MQTT_BROKER, "tcp://broker.hivemq.com:1883") ?: "tcp://broker.hivemq.com:1883"
        set(value) = prefs.edit().putString(KEY_MQTT_BROKER, value.trim()).apply()

    /**
     * Tên tài khoản xác thực MQTT (nếu server yêu cầu).
     */
    var mqttUsername: String
        get() = prefs.getString(KEY_MQTT_USERNAME, "") ?: ""
        set(value) = prefs.edit().putString(KEY_MQTT_USERNAME, value.trim()).apply()

    /**
     * Mật khẩu xác thực MQTT (nếu server yêu cầu).
     */
    var mqttPassword: String
        get() = prefs.getString(KEY_MQTT_PASSWORD, "") ?: ""
        set(value) = prefs.edit().putString(KEY_MQTT_PASSWORD, value.trim()).apply()

    /**
     * Mã định danh duy nhất của thiết bị (Device ID).
     */
    var mqttDeviceId: String
        get() {
            var id = prefs.getString(KEY_MQTT_DEVICE_ID, null)
            if (id.isNullOrBlank()) {
                id = "Signage_" + UUID.randomUUID().toString().replace("-", "").take(6).uppercase()
                prefs.edit().putString(KEY_MQTT_DEVICE_ID, id).apply()
            }
            return id
        }
        set(value) = prefs.edit().putString(KEY_MQTT_DEVICE_ID, value.trim()).apply()

    /**
     * Kênh (Topic) nhận lệnh phát video cho toàn bộ máy.
     */
    var mqttTopic: String
        get() = prefs.getString(KEY_MQTT_TOPIC, "signage/all/video") ?: "signage/all/video"
        set(value) = prefs.edit().putString(KEY_MQTT_TOPIC, value.trim()).apply()

    // --------------------------------------------------------------------------
    // CẤU HÌNH TRƯỜNG HỢP 2: SERVER WEB REST API
    // --------------------------------------------------------------------------

    /**
     * Đường dẫn HTTP REST API (Ví dụ: "https://api.user.com/v1/device-video").
     */
    var serverApiUrl: String
        get() = prefs.getString(KEY_SERVER_API_URL, "") ?: ""
        set(value) = prefs.edit().putString(KEY_SERVER_API_URL, value.trim()).apply()

    /**
     * Chu kỳ kiểm tra video mới từ REST API (tính theo Phút, mặc định 30 phút).
     */
    var apiSyncIntervalMinutes: Int
        get() = prefs.getInt(KEY_API_SYNC_INTERVAL, 30)
        set(value) = prefs.edit().putInt(KEY_API_SYNC_INTERVAL, value.coerceAtLeast(5)).apply()

    /**
     * Xóa thông tin file đệm (dùng khi người dùng bấm nút Xoá Cache).
     */
    fun clearCacheInfo() {
        prefs.edit()
            .remove(KEY_LAST_CACHED_FILE)
            .apply()
    }
}
