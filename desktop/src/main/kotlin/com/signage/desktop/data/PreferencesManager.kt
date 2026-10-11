package com.signage.desktop.data

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Properties

/**
 * Quản lý lưu trữ cấu hình trên Windows qua tệp config.properties tại %LOCALAPPDATA%\SmartSignage\
 */
class PreferencesManager {

    companion object {
        const val MODE_MQTT = "MQTT"
        const val MODE_HTTP_API = "HTTP_API"
        const val MODE_DIRECT = "DIRECT"

        private const val DEFAULT_BROKER = "tcp://broker.emqx.io:1883"
    }

    private val configFile: File by lazy {
        val appData = System.getenv("LOCALAPPDATA")
            ?: (System.getProperty("user.home") + File.separator + "AppData" + File.separator + "Local")
        val dir = File(appData, "SmartSignage")
        if (!dir.exists()) dir.mkdirs()
        File(dir, "config.properties")
    }

    private val properties = Properties()

    init {
        loadProperties()
    }

    private fun loadProperties() {
        if (configFile.exists()) {
            try {
                FileInputStream(configFile).use { fis ->
                    properties.load(fis)
                }
            } catch (_: Exception) {}
        }
    }

    private fun saveProperties() {
        try {
            FileOutputStream(configFile).use { fos ->
                properties.store(fos, "Smart Signage Desktop Configuration")
            }
        } catch (_: Exception) {}
    }

    var syncMode: String
        get() = properties.getProperty("sync_mode", MODE_DIRECT)
        set(value) {
            properties.setProperty("sync_mode", value)
            saveProperties()
        }

    var mediaUrl: String
        get() = properties.getProperty("media_url", "")
        set(value) {
            properties.setProperty("media_url", value)
            saveProperties()
        }

    var lastCachedFilePath: String?
        get() = properties.getProperty("last_cached_file")
        set(value) {
            if (value != null) properties.setProperty("last_cached_file", value)
            else properties.remove("last_cached_file")
            saveProperties()
        }

    var lastPlaybackPosition: Long
        get() = properties.getProperty("last_playback_pos", "0").toLongOrNull() ?: 0L
        set(value) {
            properties.setProperty("last_playback_pos", value.toString())
            saveProperties()
        }

    var autoStartOnBoot: Boolean
        get() = properties.getProperty("auto_boot", "true").toBoolean()
        set(value) {
            properties.setProperty("auto_boot", value.toString())
            saveProperties()
        }

    // MQTT
    var mqttBrokerUrl: String
        get() = properties.getProperty("mqtt_broker", DEFAULT_BROKER)
        set(value) {
            properties.setProperty("mqtt_broker", value)
            saveProperties()
        }

    var mqttUsername: String
        get() = properties.getProperty("mqtt_user", "")
        set(value) {
            properties.setProperty("mqtt_user", value)
            saveProperties()
        }

    var mqttPassword: String
        get() = properties.getProperty("mqtt_pass", "")
        set(value) {
            properties.setProperty("mqtt_pass", value)
            saveProperties()
        }

    var mqttDeviceId: String
        get() {
            var id = properties.getProperty("mqtt_device_id", "")
            if (id.isBlank()) {
                id = DeviceIdentifierHelper.getMacAddress()
                mqttDeviceId = id
            }
            return id
        }
        set(value) {
            properties.setProperty("mqtt_device_id", value)
            saveProperties()
        }

    var mqttTopic: String
        get() {
            var t = properties.getProperty("mqtt_topic", "")
            if (t.isBlank()) {
                t = "signage/device/${mqttDeviceId}/command"
                mqttTopic = t
            }
            return t
        }
        set(value) {
            properties.setProperty("mqtt_topic", value)
            saveProperties()
        }

    // REST API
    var serverApiUrl: String
        get() = properties.getProperty("rest_api_url", "")
        set(value) {
            properties.setProperty("rest_api_url", value)
            saveProperties()
        }

    var apiSyncIntervalMinutes: Int
        get() = properties.getProperty("rest_api_interval", "30").toIntOrNull() ?: 30
        set(value) {
            properties.setProperty("rest_api_interval", value.toString())
            saveProperties()
        }

    fun clearCacheInfo() {
        lastCachedFilePath = null
        lastPlaybackPosition = 0L
    }
}
