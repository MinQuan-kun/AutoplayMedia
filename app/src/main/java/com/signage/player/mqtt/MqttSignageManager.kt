package com.signage.player.mqtt

import android.content.Context
import android.util.Log
import com.signage.player.data.PreferencesManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.eclipse.paho.client.mqttv3.IMqttActionListener
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.IMqttToken
import org.eclipse.paho.client.mqttv3.MqttAsyncClient
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttException
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import java.util.regex.Pattern

/**
 * Trình quản lý kết nối MQTT thời gian thực (Real-time MQTT IoT Client):
 * - Giữ kết nối liên tục (Persistent TCP Connection) đến MQTT Broker trung tâm.
 * - Xuyên qua 100% các router Wi-Fi, 4G gia đình mà không cần mở cổng mạng (NAT traversal).
 * - Lắng nghe 2 kênh:
 *     1. Kênh chung: "signage/all/video" (Gửi 1 lần - toàn bộ máy đổi cùng lúc)
 *     2. Kênh riêng: "signage/device/{DEVICE_ID}/video" (Gửi riêng cho máy này)
 */
class MqttSignageManager(
    context: Context,
    private val onVideoUrlReceived: (newUrl: String) -> Unit,
    private val onStatusChanged: (isConnected: Boolean, message: String) -> Unit
) {

    private val prefs = PreferencesManager(context)
    private var mqttClient: MqttAsyncClient? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    companion object {
        private const val TAG = "MqttSignageManager"
        private val URL_JSON_PATTERN = Pattern.compile("\"(?:videoUrl|url)\"\\s*:\\s*\"([^\"]+)\"")
    }

    /**
     * Khởi động kết nối đến MQTT Broker trong background.
     */
    fun connect() {
        scope.launch {
            try {
                val brokerUrl = prefs.mqttBrokerUrl
                val deviceId = prefs.mqttDeviceId

                Log.d(TAG, "Đang kết nối MQTT Broker: $brokerUrl với Device ID: $deviceId")
                onStatusChanged(false, "Đang kết nối: $brokerUrl...")

                // Ngắt kết nối cũ nếu có
                disconnectSilently()

                // Sử dụng MemoryPersistence siêu nhẹ, không ghi rác ra ổ đĩa
                mqttClient = MqttAsyncClient(brokerUrl, deviceId, MemoryPersistence())

                val options = MqttConnectOptions().apply {
                    isAutomaticReconnect = true  // Tự động kết nối lại ngay khi có Wi-Fi
                    isCleanSession = true
                    connectionTimeout = 15
                    keepAliveInterval = 30

                    // Hỗ trợ xác thực Server riêng (Username / Password)
                    val user = prefs.mqttUsername.trim()
                    val pass = prefs.mqttPassword.trim()
                    if (user.isNotBlank()) {
                        userName = user
                        if (pass.isNotBlank()) {
                            password = pass.toCharArray()
                        }
                    }
                }

                mqttClient?.setCallback(object : MqttCallbackExtended {
                    override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                        Log.d(TAG, "Kết nối MQTT thành công! Reconnect=$reconnect")
                        onStatusChanged(true, "Đã kết nối ($brokerUrl)")
                        subscribeTopics()
                    }

                    override fun connectionLost(cause: Throwable?) {
                        Log.w(TAG, "Mất kết nối MQTT: ${cause?.message}")
                        onStatusChanged(false, "Mất kết nối: ${cause?.localizedMessage ?: "Đang thử lại..."}")
                    }

                    override fun messageArrived(topic: String?, message: MqttMessage?) {
                        val payload = message?.payload?.let { String(it) }?.trim() ?: return
                        Log.d(TAG, "Nhận được tin nhắn từ topic [$topic]: $payload")
                        handleIncomingPayload(payload)
                    }

                    override fun deliveryComplete(token: IMqttDeliveryToken?) {}
                })

                mqttClient?.connect(options, null, object : IMqttActionListener {
                    override fun onSuccess(asyncActionToken: IMqttToken?) {
                        Log.d(TAG, "Yêu cầu kết nối MQTT đã gửi đi thành công.")
                    }

                    override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                        Log.e(TAG, "Kết nối MQTT thất bại: ${exception?.message}")
                        onStatusChanged(false, "Lỗi kết nối: ${exception?.localizedMessage}")
                    }
                })

            } catch (e: Exception) {
                Log.e(TAG, "Lỗi khởi tạo MQTT: ${e.message}")
                onStatusChanged(false, "Lỗi: ${e.localizedMessage}")
            }
        }
    }

    /**
     * Đăng ký lắng nghe các Topic từ Server trung tâm.
     */
    private fun subscribeTopics() {
        val client = mqttClient ?: return
        if (!client.isConnected) return

        val broadcastTopic = prefs.mqttTopic
        val deviceSpecificTopic = "signage/device/${prefs.mqttDeviceId}/video"

        val topics = arrayOf(broadcastTopic, deviceSpecificTopic)
        val qos = intArrayOf(1, 1) // QoS 1 đảm bảo tin nhắn gửi đến ít nhất 1 lần

        try {
            client.subscribe(topics, qos, null, object : IMqttActionListener {
                override fun onSuccess(asyncActionToken: IMqttToken?) {
                    Log.d(TAG, "Đã đăng ký thành công các topic: ${topics.joinToString()}")
                }

                override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                    Log.e(TAG, "Lỗi đăng ký topic: ${exception?.message}")
                }
            })
        } catch (e: MqttException) {
            Log.e(TAG, "Lỗi subscribe MQTT: ${e.message}")
        }
    }

    /**
     * Xử lý nội dung nhận được từ MQTT:
     * - Trường hợp 1: Nhận URL trực tiếp dạng text: "https://example.com/video.mp4"
     * - Trường hợp 2: Nhận JSON: {"videoUrl": "https://example.com/video.mp4"}
     */
    private fun handleIncomingPayload(payload: String) {
        var videoUrl = payload.trim()

        // Kiểm tra nếu là JSON -> Bóc tách link videoUrl
        if (payload.startsWith("{") && payload.endsWith("}")) {
            val matcher = URL_JSON_PATTERN.matcher(payload)
            if (matcher.find()) {
                val extracted = matcher.group(1)
                if (!extracted.isNullOrBlank()) {
                    videoUrl = extracted.trim()
                }
            }
        }

        // Bỏ các ký tự nháy kép nếu có sót lại
        videoUrl = videoUrl.removeSurrounding("\"").trim()

        if (videoUrl.startsWith("http://") || videoUrl.startsWith("https://")) {
            onVideoUrlReceived(videoUrl)
        } else {
            Log.w(TAG, "Bỏ qua tin nhắn không phải URL hợp lệ: $payload")
        }
    }

    fun disconnect() {
        scope.launch {
            disconnectSilently()
        }
    }

    private fun disconnectSilently() {
        try {
            if (mqttClient?.isConnected == true) {
                mqttClient?.disconnect()
            }
            mqttClient?.close()
        } catch (_: Exception) {}
        mqttClient = null
    }
}
