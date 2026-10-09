package com.signage.player.ui

import android.app.Dialog
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.signage.player.R
import com.signage.player.data.MediaDownloader
import com.signage.player.data.PreferencesManager
import com.signage.player.data.UrlHelper
import com.signage.player.databinding.ActivityMainBinding
import com.signage.player.databinding.DialogSettingsBinding
import com.signage.player.mqtt.MqttSignageManager
import com.signage.player.player.SignagePlayerManager
import com.signage.player.server.RestApiSyncManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * ==============================================================================
 * MÀN HÌNH CHÍNH (MAIN SIGNAGE ACTIVITY)
 * ==============================================================================
 * Điều phối hoạt động toàn bộ hệ thống:
 * - Hỗ trợ cả 3 phương thức kết nối Server:
 *     1. Server MQTT riêng (Tức thì < 1s)
 *     2. Server Web REST API riêng (Định kỳ kéo link từ Server)
 *     3. Nhập link video trực tiếp (Thủ công)
 * - Chế độ Kiosk toàn màn hình, giữ màn hình sáng 24/7.
 * - Hỗ trợ điều khiển bằng Remote TV (D-Pad).
 * - Sử dụng ViewBinding 100% type-safe, không dùng findViewById.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: PreferencesManager
    private lateinit var downloader: MediaDownloader
    private lateinit var playerManager: SignagePlayerManager

    // Hai bộ quản lý kết nối Server
    private lateinit var mqttManager: MqttSignageManager
    private lateinit var restApiManager: RestApiSyncManager

    private var downloadJob: Job? = null
    private var settingsDialog: Dialog? = null
    private var dialogBinding: DialogSettingsBinding? = null

    // Trạng thái kết nối hiển thị trên Dialog
    private var isMqttConnected: Boolean = false
    private var mqttStatusMessage: String = "Đang kết nối..."
    private var restApiStatusMessage: String = "Chưa kiểm tra"

    // Bộ lắng nghe sự kiện màn hình TV bật lại sau khi tắt để tự động phát tiếp
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_ON) {
                setupFullscreenMode()
                playerManager.resume()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Giữ màn hình sáng liên tục 24/7, không bao giờ bị tắt (Kiosk Display)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Khởi tạo ViewBinding màn hình chính
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Cấu hình chế độ toàn màn hình Kiosk (ẩn thanh điều hướng và thanh trạng thái)
        setupFullscreenMode()

        // Khởi tạo các thành phần cốt lõi
        prefs = PreferencesManager(this)
        downloader = MediaDownloader(this)
        playerManager = SignagePlayerManager(
            videoView = binding.videoView,
            onError = { errorMessage ->
                runOnUiThread {
                    Toast.makeText(this, errorMessage, Toast.LENGTH_LONG).show()
                }
            }
        )

        // Khởi tạo 2 bộ kết nối Server
        initServerConnections()

        // Lắng nghe sự kiện mở bảng cài đặt
        binding.btnOpenSettings.setOnClickListener {
            showSettingsDialog()
        }

        binding.btnOverlaySettings.setOnClickListener {
            showSettingsDialog()
        }

        binding.rootContainer.setOnLongClickListener {
            showSettingsDialog()
            true
        }

        // Đăng ký lắng nghe sự kiện màn hình TV bật lại
        try {
            registerReceiver(screenReceiver, IntentFilter(Intent.ACTION_SCREEN_ON))
        } catch (_: Exception) {}

        // Bắt đầu áp dụng phương thức kết nối đã lưu và phát nội dung
        applyCurrentSyncMode()
        checkAndStartContent()
    }

    /**
     * Khởi tạo các bộ quản lý kết nối MQTT và REST API.
     */
    private fun initServerConnections() {
        // 1. Quản lý MQTT (Phương thức 1)
        mqttManager = MqttSignageManager(
            context = this,
            onVideoUrlReceived = { newUrl ->
                runOnUiThread {
                    handleRemoteVideoUpdate("MQTT Server", newUrl)
                }
            },
            onStatusChanged = { isConnected, message ->
                runOnUiThread {
                    isMqttConnected = isConnected
                    mqttStatusMessage = message
                    updateDialogStatus()
                }
            }
        )

        // 2. Quản lý REST API (Phương thức 2)
        restApiManager = RestApiSyncManager(
            context = this,
            onVideoUrlReceived = { newUrl ->
                runOnUiThread {
                    handleRemoteVideoUpdate("REST API Server", newUrl)
                }
            },
            onStatusChanged = { isSuccess, message ->
                runOnUiThread {
                    restApiStatusMessage = message
                    updateDialogStatus()
                }
            }
        )
    }

    /**
     * Áp dụng phương thức đồng bộ theo cấu hình đã lưu.
     */
    private fun applyCurrentSyncMode() {
        when (prefs.syncMode) {
            PreferencesManager.MODE_MQTT -> {
                restApiManager.stopSync()
                mqttManager.connect()
            }
            PreferencesManager.MODE_HTTP_API -> {
                mqttManager.disconnect()
                restApiManager.startSync()
            }
            else -> {
                // Chế độ thủ công: ngắt kết nối cả hai
                mqttManager.disconnect()
                restApiManager.stopSync()
            }
        }
    }

    /**
     * Xử lý khi nhận được link video mới từ Server.
     */
    private fun handleRemoteVideoUpdate(sourceName: String, newUrl: String) {
        if (UrlHelper.isImage(newUrl) || UrlHelper.isYouTube(newUrl)) {
            Toast.makeText(this, "$sourceName gửi link không hợp lệ (Ảnh/YouTube)", Toast.LENGTH_SHORT).show()
            return
        }

        if (newUrl != prefs.mediaUrl) {
            Toast.makeText(this, "⚡ $sourceName: Đã nhận video mới!", Toast.LENGTH_LONG).show()
            prefs.mediaUrl = newUrl
            startDownloadAndPlay(newUrl)
        }
    }

    /**
     * Cấu hình ẩn hoàn toàn các thanh điều hướng của Android (Immersive Mode).
     */
    private fun setupFullscreenMode() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val insetsController = WindowInsetsControllerCompat(window, window.decorView)
        insetsController.hide(WindowInsetsCompat.Type.systemBars())
        insetsController.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    override fun onResume() {
        super.onResume()
        setupFullscreenMode()
        playerManager.resume()
    }

    override fun onPause() {
        super.onPause()
        playerManager.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(screenReceiver)
        } catch (_: Exception) {}
        downloadJob?.cancel()
        playerManager.release()
        mqttManager.disconnect()
        restApiManager.stopSync()
        settingsDialog?.dismiss()
        dialogBinding = null
    }

    /**
     * Hỗ trợ Remote TV (D-Pad).
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_MENU,
            KeyEvent.KEYCODE_SETTINGS -> {
                showSettingsDialog()
                return true
            }
            KeyEvent.KEYCODE_BACK -> {
                if (settingsDialog?.isShowing == true) {
                    settingsDialog?.dismiss()
                    return true
                }
                showSettingsDialog()
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER -> {
                if (settingsDialog?.isShowing != true && binding.downloadOverlay.visibility != View.VISIBLE) {
                    showSettingsDialog()
                    return true
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    /**
     * Khởi động nội dung: phát offline ngay nếu có file lưu trong máy.
     */
    private fun checkAndStartContent() {
        val currentUrl = prefs.mediaUrl
        if (currentUrl.isBlank()) {
            if (prefs.syncMode == PreferencesManager.MODE_DIRECT) {
                showSettingsDialog()
            }
            return
        }

        // 1. Kiểm tra xem file video đã có sẵn và nguyên vẹn trong máy chưa
        val cachedFile = downloader.getCachedFileForUrl(currentUrl)
        if (cachedFile != null && cachedFile.exists() && cachedFile.length() > 0) {
            binding.downloadOverlay.visibility = View.GONE
            binding.tvOfflineBadge.visibility = View.VISIBLE
            playerManager.playVideoFile(cachedFile)
            // Đã có video đầy đủ -> Phát ngay lập tức, KHÔNG tải lại để tránh tốn băng thông và đầy bộ nhớ!
            return
        }

        // 2. Chỉ tải nếu chưa có file hoặc file bị thiếu
        startDownloadAndPlay(currentUrl)
    }

    /**
     * Tải video ngầm với cơ chế tự động thử lại (Auto-Retry) khi rớt mạng.
     * Nếu máy đã có video đang chạy -> tiếp tục phát êm ái ngầm mà không che màn hình!
     */
    private fun startDownloadAndPlay(url: String, retryCount: Int = 0) {
        downloadJob?.cancel()
        downloadJob = lifecycleScope.launch {
            val cachedFile = downloader.getCachedFileForUrl(url)
            val fallbackFile = playerManager.lastKnownGoodFile ?: cachedFile
            val hasPlayingVideo = fallbackFile != null && fallbackFile.exists()

            // Chỉ hiện màn hình chờ xám nếu máy CHƯA CÓ video nào đang chạy
            if (!hasPlayingVideo) {
                binding.downloadOverlay.visibility = View.VISIBLE
                binding.btnOverlaySettings.visibility = View.GONE
                binding.progressBar.visibility = View.VISIBLE
                binding.progressBar.isIndeterminate = false
                binding.progressBar.progress = 0
                binding.tvStatus.text = if (retryCount > 0) "Đang thử tải lại (Lần $retryCount)..." else getString(R.string.status_downloading)
                binding.tvDownloadPercent.text = "0%"
            } else {
                // Đang có video chạy -> Phát video dự phòng ngầm, không che màn hình
                binding.tvOfflineBadge.visibility = View.VISIBLE
            }

            val result = downloader.downloadMedia(url, fallbackFile) { progress, bytesRead, _ ->
                runOnUiThread {
                    if (!hasPlayingVideo) {
                        if (progress >= 0) {
                            binding.progressBar.progress = progress
                            binding.tvDownloadPercent.text = "$progress%"
                        } else {
                            binding.progressBar.isIndeterminate = true
                            val mb = bytesRead / (1024 * 1024)
                            binding.tvDownloadPercent.text = "$mb MB"
                        }
                    }
                }
            }

            result.onSuccess { downloadedFile ->
                binding.downloadOverlay.visibility = View.GONE
                binding.tvOfflineBadge.visibility = View.GONE
                prefs.lastCachedFilePath = downloadedFile.absolutePath

                playerManager.playVideoFile(downloadedFile)
            }.onFailure { error ->
                val errorMsg = error.localizedMessage ?: "Lỗi kết nối tải video"
                val nextDelaySec = when {
                    retryCount < 2 -> 15
                    retryCount < 5 -> 30
                    else -> 60
                }

                if (hasPlayingVideo) {
                    binding.tvOfflineBadge.visibility = View.VISIBLE
                    Log.w("MainActivity", "Tải video mới thất bại ($errorMsg). Sẽ tự động thử lại sau ${nextDelaySec}s...")
                    // Chờ và tự động thử lại trong nền mà không làm gián đoạn video đang chiếu
                    delay(nextDelaySec * 1000L)
                    if (prefs.mediaUrl == url) {
                        startDownloadAndPlay(url, retryCount + 1)
                    }
                } else {
                    // Chưa có video nào -> Đếm ngược trên màn hình chờ và tự thử lại
                    binding.downloadOverlay.visibility = View.VISIBLE
                    binding.progressBar.visibility = View.GONE
                    binding.btnOverlaySettings.visibility = View.VISIBLE

                    for (sec in nextDelaySec downTo 1) {
                        binding.tvStatus.text = "Mất kết nối mạng (Lỗi: $errorMsg)"
                        binding.tvDownloadPercent.text = "Sẽ tự động thử lại sau ${sec}s..."
                        delay(1000L)
                        if (prefs.mediaUrl != url) return@launch
                    }

                    startDownloadAndPlay(url, retryCount + 1)
                }
            }
        }
    }

    /**
     * Bảng Cài đặt hỗ trợ đầy đủ 3 phương thức cấu hình Server.
     * Sử dụng ViewBinding (DialogSettingsBinding) an toàn 100%, không bị lỗi Unresolved reference.
     */
    private fun showSettingsDialog() {
        if (settingsDialog?.isShowing == true) return

        val dBinding = DialogSettingsBinding.inflate(layoutInflater)
        dialogBinding = dBinding

        // Điền giá trị hiện tại vào giao diện (Hiển thị địa chỉ MAC thật của phần cứng)
        dBinding.tvDeviceIdHeader.text = "MÃ THIẾT BỊ (MAC / ID): ${prefs.mqttDeviceId}"
        dBinding.etMqttBroker.setText(prefs.mqttBrokerUrl)
        dBinding.etMqttUser.setText(prefs.mqttUsername)
        dBinding.etMqttPass.setText(prefs.mqttPassword)
        dBinding.etMqttTopic.setText(prefs.mqttTopic)

        dBinding.etRestApiUrl.setText(prefs.serverApiUrl)
        dBinding.etApiInterval.setText(prefs.apiSyncIntervalMinutes.toString())

        dBinding.etDirectVideoUrl.setText(prefs.mediaUrl)

        // Hiển thị khung cấu hình tương ứng với chế độ đang lưu
        when (prefs.syncMode) {
            PreferencesManager.MODE_MQTT -> {
                dBinding.rbModeMqtt.isChecked = true
                dBinding.layoutMqttConfig.visibility = View.VISIBLE
                dBinding.layoutHttpConfig.visibility = View.GONE
                dBinding.layoutDirectConfig.visibility = View.GONE
            }
            PreferencesManager.MODE_HTTP_API -> {
                dBinding.rbModeHttp.isChecked = true
                dBinding.layoutMqttConfig.visibility = View.GONE
                dBinding.layoutHttpConfig.visibility = View.VISIBLE
                dBinding.layoutDirectConfig.visibility = View.GONE
            }
            else -> {
                dBinding.rbModeDirect.isChecked = true
                dBinding.layoutMqttConfig.visibility = View.GONE
                dBinding.layoutHttpConfig.visibility = View.GONE
                dBinding.layoutDirectConfig.visibility = View.VISIBLE
            }
        }

        // Lắng nghe sự kiện chuyển đổi chế độ trên giao diện
        dBinding.rgSyncMode.setOnCheckedChangeListener { _, checkedId ->
            when (checkedId) {
                R.id.rbModeMqtt -> {
                    dBinding.layoutMqttConfig.visibility = View.VISIBLE
                    dBinding.layoutHttpConfig.visibility = View.GONE
                    dBinding.layoutDirectConfig.visibility = View.GONE
                }
                R.id.rbModeHttp -> {
                    dBinding.layoutMqttConfig.visibility = View.GONE
                    dBinding.layoutHttpConfig.visibility = View.VISIBLE
                    dBinding.layoutDirectConfig.visibility = View.GONE
                }
                R.id.rbModeDirect -> {
                    dBinding.layoutMqttConfig.visibility = View.GONE
                    dBinding.layoutHttpConfig.visibility = View.GONE
                    dBinding.layoutDirectConfig.visibility = View.VISIBLE
                }
            }
        }

        // Xóa bộ nhớ đệm
        dBinding.btnClearCache.setOnClickListener {
            downloader.clearAllCache()
            prefs.clearCacheInfo()
            Toast.makeText(this, getString(R.string.cache_cleared), Toast.LENGTH_SHORT).show()
        }

        val dialog = AlertDialog.Builder(this, com.google.android.material.R.style.Theme_MaterialComponents_DayNight_Dialog_Alert)
            .setView(dBinding.root)
            .setCancelable(true)
            .create()

        dBinding.btnCloseDialog.setOnClickListener {
            dialog.dismiss()
        }

        // Lưu và áp dụng cấu hình
        dBinding.btnSaveAndPlay.setOnClickListener {
            val selectedMode = when (dBinding.rgSyncMode.checkedRadioButtonId) {
                R.id.rbModeMqtt -> PreferencesManager.MODE_MQTT
                R.id.rbModeHttp -> PreferencesManager.MODE_HTTP_API
                else -> PreferencesManager.MODE_DIRECT
            }

            prefs.syncMode = selectedMode

            when (selectedMode) {
                PreferencesManager.MODE_MQTT -> {
                    val broker = dBinding.etMqttBroker.text.toString().trim()
                    if (broker.isNotBlank()) prefs.mqttBrokerUrl = broker
                    prefs.mqttUsername = dBinding.etMqttUser.text.toString().trim()
                    prefs.mqttPassword = dBinding.etMqttPass.text.toString().trim()
                    val topic = dBinding.etMqttTopic.text.toString().trim()
                    if (topic.isNotBlank()) prefs.mqttTopic = topic

                    Toast.makeText(this, "Đã lưu cấu hình MQTT! Đang kết nối lại...", Toast.LENGTH_SHORT).show()
                }
                PreferencesManager.MODE_HTTP_API -> {
                    val apiUrl = dBinding.etRestApiUrl.text.toString().trim()
                    if (apiUrl.isBlank() || (!apiUrl.startsWith("http://") && !apiUrl.startsWith("https://"))) {
                        Toast.makeText(this, "Vui lòng nhập đường dẫn API hợp lệ!", Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }
                    prefs.serverApiUrl = apiUrl
                    prefs.apiSyncIntervalMinutes = dBinding.etApiInterval.text.toString().toIntOrNull() ?: 30

                    Toast.makeText(this, "Đã lưu cấu hình REST API! Đang đồng bộ...", Toast.LENGTH_SHORT).show()
                }
                PreferencesManager.MODE_DIRECT -> {
                    val directUrl = dBinding.etDirectVideoUrl.text.toString().trim()
                    if (directUrl.isBlank() || (!directUrl.startsWith("http://") && !directUrl.startsWith("https://"))) {
                        Toast.makeText(this, "Vui lòng nhập đường dẫn video hợp lệ!", Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }
                    if (UrlHelper.isImage(directUrl) || UrlHelper.isYouTube(directUrl)) {
                        Toast.makeText(this, "Chỉ hỗ trợ video, không dùng ảnh hoặc YouTube!", Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }
                    prefs.mediaUrl = directUrl
                    startDownloadAndPlay(directUrl)
                }
            }

            // Kích hoạt chế độ mới
            applyCurrentSyncMode()
            dialog.dismiss()
        }

        dialog.setOnDismissListener {
            dialogBinding = null
        }

        settingsDialog = dialog
        updateDialogStatus()
        dialog.show()
    }

    /**
     * Cập nhật trạng thái kết nối MQTT và REST API trên giao diện Dialog.
     */
    private fun updateDialogStatus() {
        dialogBinding?.let { dBinding ->
            if (isMqttConnected) {
                dBinding.tvMqttStatusText.text = "Trạng thái: ● Đã kết nối ($mqttStatusMessage)"
                dBinding.tvMqttStatusText.setTextColor(android.graphics.Color.parseColor("#4CAF50"))
            } else {
                dBinding.tvMqttStatusText.text = "Trạng thái: ○ $mqttStatusMessage"
                dBinding.tvMqttStatusText.setTextColor(android.graphics.Color.parseColor("#FFA726"))
            }

            dBinding.tvRestApiStatusText.text = "Trạng thái: $restApiStatusMessage"
        }
    }
}
