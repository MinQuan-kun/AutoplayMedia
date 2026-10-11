package com.signage.desktop.ui

import com.signage.desktop.autostart.WindowsAutoStartHelper
import com.signage.desktop.data.MediaDownloader
import com.signage.desktop.data.PreferencesManager
import com.signage.desktop.data.UrlHelper
import com.signage.desktop.mqtt.MqttSignageManager
import com.signage.desktop.player.DesktopPlayerManager
import com.signage.desktop.server.RestApiSyncManager
import javafx.application.Platform
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.Cursor
import javafx.scene.Scene
import javafx.scene.control.*
import javafx.scene.input.KeyCode
import javafx.scene.input.MouseButton
import javafx.scene.layout.*
import javafx.scene.paint.Color
import javafx.stage.Modality
import javafx.stage.Stage
import javafx.stage.StageStyle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.util.Timer
import java.util.TimerTask

/**
 * Cửa sổ hiển thị chính Smart Signage trên Windows (Kiosk Fullscreen).
 */
class SignageWindow(private val stage: Stage) {

    private val prefs = PreferencesManager()
    private val downloader = MediaDownloader()

    private val rootPane = StackPane()
    private val videoContainer = StackPane()
    private val overlayPane = VBox(15.0)

    private val progressBar = ProgressBar(0.0)
    private val lblStatus = Label("Đang khởi tạo...")
    private val lblPercent = Label("0%")
    private val btnCloseOverlay = Button("✕ Đóng thông báo")

    private val offlineBadge = Label("● ĐANG PHÁT OFFLINE")

    private lateinit var playerManager: DesktopPlayerManager
    private lateinit var mqttManager: MqttSignageManager
    private lateinit var restApiManager: RestApiSyncManager

    private val scope = CoroutineScope(Dispatchers.IO)
    private var downloadJob: Job? = null
    private var retryJob: Job? = null
    private var currentDownloadToken = 0L

    private var mouseIdleTimer: Timer? = null

    // Thông tin trạng thái cho Dialog
    private var mqttStatusText = "Chưa kết nối"
    private var isMqttConnected = false
    private var restApiStatusText = "Chưa kiểm tra"

    fun init() {
        setupUI()
        setupPlayerAndServer()
        setupEventListeners()

        // Áp dụng tự khởi chạy cùng Windows theo cài đặt
        updateAutoStartRegistration()

        // Khởi động nội dung đã lưu
        checkAndStartContent()
    }

    private fun setupUI() {
        rootPane.style = "-fx-background-color: black;"
        videoContainer.style = "-fx-background-color: black;"

        // Badge báo Offline góc trên bên phải
        offlineBadge.style = "-fx-background-color: rgba(0,0,0,0.65); -fx-text-fill: #4CAF50; -fx-padding: 6 14; -fx-background-radius: 20; -fx-font-weight: bold; -fx-font-size: 13px;"
        offlineBadge.isVisible = false
        StackPane.setAlignment(offlineBadge, Pos.TOP_RIGHT)
        StackPane.setMargin(offlineBadge, Insets(20.0))

        // Nút mở Cài đặt ở góc trên bên trái
        val btnOpenSettings = Button("⚙ Cài đặt").apply {
            style = "-fx-background-color: rgba(30,30,30,0.7); -fx-text-fill: white; -fx-font-size: 13px; -fx-background-radius: 20; -fx-padding: 6 16; -fx-cursor: hand;"
            setOnAction { showSettingsDialog() }
        }
        StackPane.setAlignment(btnOpenSettings, Pos.TOP_LEFT)
        StackPane.setMargin(btnOpenSettings, Insets(20.0))

        // Overlay thông báo tải / trạng thái
        overlayPane.apply {
            alignment = Pos.CENTER
            maxWidth = 480.0
            maxHeight = 220.0
            style = "-fx-background-color: rgba(20,24,35,0.92); -fx-background-radius: 16; -fx-border-color: rgba(255,255,255,0.15); -fx-border-radius: 16; -fx-padding: 24;"

            lblStatus.style = "-fx-text-fill: white; -fx-font-size: 16px; -fx-font-weight: bold;"
            lblPercent.style = "-fx-text-fill: #29B6F6; -fx-font-size: 14px;"
            progressBar.prefWidth = 400.0
            progressBar.prefHeight = 10.0

            btnCloseOverlay.style = "-fx-background-color: #37474F; -fx-text-fill: white; -fx-background-radius: 8; -fx-padding: 6 18; -fx-cursor: hand;"
            btnCloseOverlay.setOnAction { overlayPane.isVisible = false }

            children.addAll(lblStatus, progressBar, lblPercent, btnCloseOverlay)
            isVisible = false
        }
        StackPane.setAlignment(overlayPane, Pos.CENTER)

        rootPane.children.addAll(videoContainer, offlineBadge, btnOpenSettings, overlayPane)

        val scene = Scene(rootPane, 1280.0, 720.0, Color.BLACK)
        stage.scene = scene
        stage.title = "Smart Signage Player"
        stage.fullScreenExitHint = ""
        stage.show()
        stage.isFullScreen = true
        stage.toFront()
    }

    private fun setupPlayerAndServer() {
        playerManager = DesktopPlayerManager(videoContainer) { errorMessage ->
            Platform.runLater {
                lblStatus.text = "Không thể phát video"
                lblPercent.text = errorMessage
                progressBar.isVisible = false
                overlayPane.isVisible = true
            }
        }.apply {
            onVideoPlayStarted = { playingFile ->
                scope.launch {
                    downloader.purgeOldVideosExcept(playingFile)
                }
                prefs.lastCachedFilePath = playingFile.absolutePath
                Platform.runLater {
                    overlayPane.isVisible = false
                    offlineBadge.isVisible = false
                }
            }
        }

        mqttManager = MqttSignageManager(
            prefs = prefs,
            onVideoUrlReceived = { newUrl ->
                Platform.runLater {
                    handleRemoteVideoUpdate("MQTT", newUrl)
                }
            },
            onStatusChanged = { isConnected, message ->
                Platform.runLater {
                    isMqttConnected = isConnected
                    mqttStatusText = message
                }
            }
        )

        restApiManager = RestApiSyncManager(
            prefs = prefs,
            onVideoUrlReceived = { newUrl ->
                Platform.runLater {
                    handleRemoteVideoUpdate("REST API", newUrl)
                }
            },
            onStatusChanged = { _, message ->
                Platform.runLater {
                    restApiStatusText = message
                }
            }
        )

        applyCurrentSyncMode()
    }

    private fun setupEventListeners() {
        val scene = stage.scene

        // Tự động ẩn con trỏ chuột sau 3 giây không di chuyển (Kiosk Standard)
        scene.setOnMouseMoved {
            scene.cursor = Cursor.DEFAULT
            resetMouseIdleTimer(scene)
        }

        // Mở Cài đặt bằng phím Space, Enter, hoặc Click chuột phải
        scene.setOnKeyPressed { event ->
            when (event.code) {
                KeyCode.SPACE, KeyCode.ENTER, KeyCode.F2, KeyCode.M -> {
                    showSettingsDialog()
                }
                KeyCode.ESCAPE -> {
                    if (overlayPane.isVisible) overlayPane.isVisible = false
                }
                else -> {}
            }
        }

        scene.setOnMouseClicked { event ->
            if (event.button == MouseButton.SECONDARY || event.clickCount == 2) {
                showSettingsDialog()
            }
        }
    }

    private fun resetMouseIdleTimer(scene: Scene) {
        mouseIdleTimer?.cancel()
        mouseIdleTimer = Timer().apply {
            schedule(object : TimerTask() {
                override fun run() {
                    Platform.runLater {
                        scene.cursor = Cursor.NONE
                    }
                }
            }, 3000L)
        }
    }

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
                mqttManager.disconnect()
                restApiManager.stopSync()
            }
        }
    }

    private fun handleRemoteVideoUpdate(source: String, newUrl: String) {
        if (newUrl.isNotBlank() && newUrl != prefs.mediaUrl) {
            prefs.mediaUrl = newUrl
            startDownloadAndPlay(newUrl)
        }
    }

    private fun checkAndStartContent() {
        val currentUrl = prefs.mediaUrl
        if (currentUrl.isBlank()) {
            showSettingsDialog()
            return
        }

        // Hỗ trợ file cục bộ trong máy tính
        if (currentUrl.startsWith("/") || currentUrl.startsWith("file://") || currentUrl.matches(Regex("^[a-zA-Z]:\\\\.*"))) {
            val cleanPath = currentUrl.removePrefix("file://")
            val localFile = File(cleanPath)
            if (localFile.exists() && localFile.length() > 0) {
                offlineBadge.isVisible = true
                overlayPane.isVisible = false
                playerManager.playVideoFile(localFile)
                return
            }
        }

        val cachedFile = downloader.getCachedFileForUrl(currentUrl)
        if (cachedFile != null && cachedFile.exists() && cachedFile.length() > 0) {
            offlineBadge.isVisible = true
            overlayPane.isVisible = false
            downloader.purgeOldVideosExcept(cachedFile)
            playerManager.playVideoFile(cachedFile)
            return
        }

        startDownloadAndPlay(currentUrl)
    }

    private fun startDownloadAndPlay(url: String) {
        retryJob?.cancel()
        val cachedFile = downloader.getCachedFileForUrl(url)
        if (cachedFile != null && cachedFile.exists() && cachedFile.length() > 0) {
            offlineBadge.isVisible = true
            overlayPane.isVisible = false
            downloader.purgeOldVideosExcept(cachedFile)
            playerManager.playVideoFile(cachedFile)
            return
        }

        downloader.cancelActiveDownload()
        downloadJob?.cancel()
        val thisToken = ++currentDownloadToken

        overlayPane.isVisible = true
        progressBar.isVisible = true
        progressBar.progress = 0.0
        lblStatus.text = "Đang tải video mới..."
        lblPercent.text = "0%"

        downloadJob = scope.launch {
            val result = downloader.downloadMedia(url) { progress, bytesRead, _ ->
                Platform.runLater {
                    if (thisToken != currentDownloadToken) return@runLater
                    if (progress >= 0) {
                        progressBar.progress = progress / 100.0
                        lblPercent.text = "$progress%"
                    } else {
                        progressBar.progress = -1.0
                        lblPercent.text = "${bytesRead / (1024 * 1024)} MB"
                    }
                }
            }

            result.onSuccess { downloadedFile ->
                Platform.runLater {
                    if (thisToken != currentDownloadToken) return@runLater
                    retryJob?.cancel()
                    overlayPane.isVisible = false
                    offlineBadge.isVisible = false
                    playerManager.playVideoFile(downloadedFile)
                }
            }.onFailure { error ->
                Platform.runLater {
                    if (thisToken != currentDownloadToken) return@runLater
                    lblStatus.text = "Tải video bị gián đoạn"
                    lblPercent.text = "${error.localizedMessage ?: "Mất kết nối mạng"}\nSẽ tự động tải lại từ đầu sau 8 giây..."
                    progressBar.isVisible = false
                }
                retryJob?.cancel()
                retryJob = scope.launch {
                    delay(8000L)
                    if (thisToken == currentDownloadToken) {
                        Platform.runLater {
                            startDownloadAndPlay(url)
                        }
                    }
                }
            }
        }
    }

    private fun updateAutoStartRegistration() {
        val exeInDir = File(System.getProperty("user.dir"), "SmartSignage.exe")
        val cmd = if (exeInDir.exists()) {
            "\"${exeInDir.absolutePath}\""
        } else {
            val javaHome = System.getProperty("java.home")
            val javaw = File(javaHome, "bin/javaw.exe").takeIf { it.exists() }?.absolutePath
                ?: File(javaHome, "bin/java.exe").absolutePath
            val appDir = File(System.getProperty("user.dir"))
            val libDir = File(appDir, "lib")
            if (libDir.exists()) {
                "\"$javaw\" --module-path \"${libDir.absolutePath}\" --add-modules javafx.controls,javafx.media -classpath \"${libDir.absolutePath}\\*\" com.signage.desktop.MainApplicationKt"
            } else {
                val currentJarOrExe = System.getProperty("java.class.path")
                "\"$javaw\" -jar \"$currentJarOrExe\""
            }
        }
        WindowsAutoStartHelper.setAutoStart(prefs.autoStartOnBoot, cmd)
    }

    private fun showSettingsDialog() {
        val dialog = Stage().apply {
            initOwner(stage)
            initModality(Modality.APPLICATION_MODAL)
            title = "Cấu hình Smart Signage (Windows Desktop)"
            isResizable = false
            isAlwaysOnTop = true
        }

        val content = VBox(14.0).apply {
            padding = Insets(20.0)
            prefWidth = 520.0
            style = "-fx-background-color: #263238; -fx-text-fill: white;"
        }

        val lblHeader = Label("MÃ THIẾT BỊ (MAC / ID): ${prefs.mqttDeviceId}").apply {
            style = "-fx-font-weight: bold; -fx-text-fill: #80D8FF; -fx-font-size: 14px;"
        }

        // Chọn phương thức
        val rbMqtt = RadioButton("1. Server MQTT riêng (Tức thì < 1s)").apply { style = "-fx-text-fill: white;" }
        val rbHttp = RadioButton("2. Server Web REST API (Định kỳ kéo link)").apply { style = "-fx-text-fill: white;" }
        val rbDirect = RadioButton("3. Nhập link video trực tiếp").apply { style = "-fx-text-fill: white;" }

        val toggleGroup = ToggleGroup().apply {
            toggles.addAll(rbMqtt, rbHttp, rbDirect)
        }

        // Khung MQTT
        val txtMqttBroker = TextField(prefs.mqttBrokerUrl).apply { promptText = "tcp://broker.emqx.io:1883" }
        val txtMqttUser = TextField(prefs.mqttUsername).apply { promptText = "Tên đăng nhập (nếu có)" }
        val txtMqttPass = PasswordField().apply { text = prefs.mqttPassword; promptText = "Mật khẩu (nếu có)" }
        val txtMqttTopic = TextField(prefs.mqttTopic)
        val lblMqttStatus = Label("Trạng thái: $mqttStatusText").apply {
            style = "-fx-text-fill: ${if (isMqttConnected) "#4CAF50" else "#FFA726"};"
        }
        val boxMqtt = VBox(8.0,
            Label("MQTT Broker URL:").apply { style = "-fx-text-fill: white;" }, txtMqttBroker,
            Label("Tài khoản / Mật khẩu:").apply { style = "-fx-text-fill: white;" },
            HBox(10.0, txtMqttUser, txtMqttPass),
            Label("Topic lệnh:").apply { style = "-fx-text-fill: white;" }, txtMqttTopic,
            lblMqttStatus
        )

        // Khung HTTP
        val txtRestUrl = TextField(prefs.serverApiUrl).apply { promptText = "https://your-server.com/api/get-video" }
        val txtRestInterval = TextField(prefs.apiSyncIntervalMinutes.toString()).apply { promptText = "30" }
        val lblRestStatus = Label("Trạng thái: $restApiStatusText").apply { style = "-fx-text-fill: #FFA726;" }
        val boxHttp = VBox(8.0,
            Label("Đường dẫn API:").apply { style = "-fx-text-fill: white;" }, txtRestUrl,
            Label("Thời gian kiểm tra (phút):").apply { style = "-fx-text-fill: white;" }, txtRestInterval,
            lblRestStatus
        )

        // Khung Direct
        val txtDirectUrl = TextField(prefs.mediaUrl).apply { promptText = "Dán link Google Drive hoặc file MP4 tại đây" }
        val boxDirect = VBox(8.0,
            Label("Link Video / Đường dẫn file:").apply { style = "-fx-text-fill: white;" }, txtDirectUrl
        )

        fun updateVisibility() {
            boxMqtt.isVisible = rbMqtt.isSelected
            boxMqtt.isManaged = rbMqtt.isSelected
            boxHttp.isVisible = rbHttp.isSelected
            boxHttp.isManaged = rbHttp.isSelected
            boxDirect.isVisible = rbDirect.isSelected
            boxDirect.isManaged = rbDirect.isSelected
        }

        when (prefs.syncMode) {
            PreferencesManager.MODE_MQTT -> rbMqtt.isSelected = true
            PreferencesManager.MODE_HTTP_API -> rbHttp.isSelected = true
            else -> rbDirect.isSelected = true
        }
        updateVisibility()

        toggleGroup.selectedToggleProperty().addListener { _, _, _ -> updateVisibility() }

        // Thông báo kết quả xóa cache hiển thị trực tiếp trong Dialog (tránh lỗi z-order cửa sổ)
        val lblCacheStatus = Label("").apply {
            style = "-fx-text-fill: #00E676; -fx-font-weight: bold; -fx-font-size: 13px;"
            isVisible = false
            isManaged = false
        }

        // Nút bấm
        val btnClearCache = Button("Xóa Cache Video").apply {
            style = "-fx-background-color: #546E7A; -fx-text-fill: white; -fx-cursor: hand;"
            setOnAction {
                downloader.clearAllCache()
                prefs.clearCacheInfo()
                lblCacheStatus.text = "✓ Đã dọn sạch toàn bộ video đệm và giải phóng dung lượng ổ đĩa!"
                lblCacheStatus.isVisible = true
                lblCacheStatus.isManaged = true
            }
        }

        val btnSave = Button("LƯU & PHÁT VIDEO").apply {
            style = "-fx-background-color: #00E676; -fx-text-fill: black; -fx-font-weight: bold; -fx-padding: 8 20; -fx-cursor: hand;"
            setOnAction {
                val selectedMode = when {
                    rbMqtt.isSelected -> PreferencesManager.MODE_MQTT
                    rbHttp.isSelected -> PreferencesManager.MODE_HTTP_API
                    else -> PreferencesManager.MODE_DIRECT
                }
                prefs.syncMode = selectedMode
                prefs.autoStartOnBoot = true

                if (selectedMode == PreferencesManager.MODE_MQTT) {
                    prefs.mqttBrokerUrl = txtMqttBroker.text.trim()
                    prefs.mqttUsername = txtMqttUser.text.trim()
                    prefs.mqttPassword = txtMqttPass.text.trim()
                    prefs.mqttTopic = txtMqttTopic.text.trim()
                } else if (selectedMode == PreferencesManager.MODE_HTTP_API) {
                    prefs.serverApiUrl = txtRestUrl.text.trim()
                    prefs.apiSyncIntervalMinutes = txtRestInterval.text.toIntOrNull() ?: 30
                } else {
                    val directUrl = txtDirectUrl.text.trim()
                    prefs.mediaUrl = directUrl
                    startDownloadAndPlay(directUrl)
                }

                applyCurrentSyncMode()
                updateAutoStartRegistration()
                dialog.close()
            }
        }

        val buttonBar = HBox(12.0, btnClearCache, Pane().apply { HBox.setHgrow(this, Priority.ALWAYS) }, btnSave)

        content.children.addAll(
            lblHeader,
            VBox(6.0, rbMqtt, rbHttp, rbDirect),
            Separator(),
            boxMqtt, boxHttp, boxDirect,
            Separator(),
            lblCacheStatus,
            buttonBar
        )

        dialog.scene = Scene(content)
        dialog.showAndWait()
    }
}
