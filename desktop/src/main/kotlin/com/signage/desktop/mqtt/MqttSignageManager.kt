package com.signage.desktop.mqtt

import com.signage.desktop.data.PreferencesManager
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended
import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Quản lý kết nối MQTT trên máy tính Windows, đổi video tức thì dưới 1 giây.
 */
class MqttSignageManager(
    private val prefs: PreferencesManager,
    private val onVideoUrlReceived: (String) -> Unit,
    private val onStatusChanged: (isConnected: Boolean, message: String) -> Unit
) {

    private var client: MqttClient? = null
    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    private var reconnectTask: ScheduledFuture<*>? = null

    @Volatile
    private var isUserDisconnected = false

    fun connect() {
        isUserDisconnected = false
        val broker = prefs.mqttBrokerUrl
        val deviceId = prefs.mqttDeviceId

        if (broker.isBlank()) {
            onStatusChanged(false, "Chưa nhập địa chỉ Broker")
            return
        }

        disconnectInternal()
        onStatusChanged(false, "Đang kết nối tới $broker...")

        try {
            val cleanClientId = "SignageDesktop_" + deviceId.replace(":", "").replace("-", "")
            val newClient = MqttClient(broker, cleanClientId, MemoryPersistence())
            client = newClient

            val options = MqttConnectOptions().apply {
                isAutomaticReconnect = true
                isCleanSession = false
                connectionTimeout = 15
                keepAliveInterval = 30
                if (prefs.mqttUsername.isNotBlank()) {
                    userName = prefs.mqttUsername
                    password = prefs.mqttPassword.toCharArray()
                }
            }

            newClient.setCallback(object : MqttCallbackExtended {
                override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                    onStatusChanged(true, "Đã kết nối ($serverURI)")
                    subscribeTopics(newClient)
                }

                override fun connectionLost(cause: Throwable?) {
                    onStatusChanged(false, "Mất kết nối: ${cause?.message ?: "Ngắt kết nối"}")
                    scheduleReconnect()
                }

                override fun messageArrived(topic: String?, message: MqttMessage?) {
                    message?.let {
                        val payload = String(it.payload, Charsets.UTF_8).trim()
                        handleIncomingPayload(payload)
                    }
                }

                override fun deliveryComplete(token: IMqttDeliveryToken?) {}
            })

            newClient.connect(options)

        } catch (e: Exception) {
            onStatusChanged(false, "Lỗi kết nối: ${e.message}")
            scheduleReconnect()
        }
    }

    private fun subscribeTopics(mqttClient: MqttClient) {
        val topic = prefs.mqttTopic
        val deviceId = prefs.mqttDeviceId
        val cleanId = deviceId.replace(":", "")

        val topicsToSub = mutableListOf<String>()
        if (topic.isNotBlank()) topicsToSub.add(topic)
        topicsToSub.add("signage/device/$deviceId/command")
        if (cleanId != deviceId) topicsToSub.add("signage/device/$cleanId/command")
        topicsToSub.add("signage/broadcast/command")

        topicsToSub.distinct().forEach { t ->
            try {
                mqttClient.subscribe(t, 1)
            } catch (_: Exception) {}
        }
    }

    private fun handleIncomingPayload(payload: String) {
        try {
            if (payload.startsWith("{") && payload.endsWith("}")) {
                val json = JSONObject(payload)
                val url = when {
                    json.has("url") -> json.getString("url")
                    json.has("video_url") -> json.getString("video_url")
                    json.has("media_url") -> json.getString("media_url")
                    json.has("link") -> json.getString("link")
                    else -> ""
                }
                if (url.isNotBlank()) onVideoUrlReceived(url.trim())
            } else if (payload.startsWith("http://") || payload.startsWith("https://")) {
                onVideoUrlReceived(payload)
            }
        } catch (_: Exception) {}
    }

    private fun scheduleReconnect() {
        if (isUserDisconnected) return
        reconnectTask?.cancel(false)
        reconnectTask = scheduler.schedule({
            if (!isUserDisconnected && client?.isConnected != true) {
                connect()
            }
        }, 10, TimeUnit.SECONDS)
    }

    fun disconnect() {
        isUserDisconnected = true
        reconnectTask?.cancel(true)
        disconnectInternal()
        onStatusChanged(false, "Đã ngắt kết nối")
    }

    private fun disconnectInternal() {
        try {
            client?.let {
                if (it.isConnected) it.disconnect()
                it.close()
            }
        } catch (_: Exception) {}
        client = null
    }
}
