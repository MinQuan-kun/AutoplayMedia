package com.signage.desktop.player

import javafx.application.Platform
import javafx.geometry.Pos
import javafx.scene.layout.Pane
import javafx.scene.layout.StackPane
import javafx.scene.media.Media
import javafx.scene.media.MediaPlayer
import javafx.scene.media.MediaView
import javafx.util.Duration
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Trình điều khiển phát video trên Windows sử dụng bộ giải mã phần cứng DirectX của JavaFX.
 * Hỗ trợ lặp vô tận 24/7, tự động co giãn tỷ lệ và ghi nhớ vị trí phát.
 */
class DesktopPlayerManager(
    private val containerPane: Pane,
    private val onError: (String) -> Unit
) {

    val mediaView = MediaView()
    private var mediaPlayer: MediaPlayer? = null

    private var currentFile: File? = null
    var lastKnownGoodFile: File? = null
        private set

    val currentPlayingFile: File?
        get() = currentFile ?: lastKnownGoodFile

    var onVideoPlayStarted: ((File) -> Unit)? = null
    private var savedPositionMs: Double = 0.0

    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    private var watchdogTask: ScheduledFuture<*>? = null

    init {
        // Tự động co giãn MediaView theo kích thước cửa sổ màn hình
        mediaView.fitWidthProperty().bind(containerPane.widthProperty())
        mediaView.fitHeightProperty().bind(containerPane.heightProperty())
        mediaView.isPreserveRatio = true

        if (containerPane is StackPane) {
            StackPane.setAlignment(mediaView, Pos.CENTER)
        }

        containerPane.children.add(0, mediaView)
    }

    fun isPlaying(): Boolean {
        return mediaPlayer?.status == MediaPlayer.Status.PLAYING
    }

    fun getCurrentPositionMs(): Long {
        return try {
            mediaPlayer?.currentTime?.toMillis()?.toLong() ?: savedPositionMs.toLong()
        } catch (_: Exception) {
            savedPositionMs.toLong()
        }
    }

    fun restorePlaybackPosition(posMs: Long) {
        if (posMs > 0) {
            savedPositionMs = posMs.toDouble()
        }
    }

    fun playVideoFile(file: File, keepPosition: Boolean = false) {
        if (!file.exists() || file.length() <= 0) {
            onError("File video không tồn tại: ${file.name}")
            return
        }

        if (!keepPosition && currentFile?.absolutePath != file.absolutePath) {
            savedPositionMs = 0.0
        }
        currentFile = file

        Platform.runLater {
            try {
                // Dọn dẹp player cũ
                mediaPlayer?.stop()
                mediaPlayer?.dispose()

                watchdogTask?.cancel(false)
                watchdogTask = scheduler.schedule({
                    Platform.runLater {
                        if (!isPlaying()) {
                            val failed = currentFile
                            val fallback = lastKnownGoodFile
                            val msg = "Video nạp quá thời gian chờ (20s). Video có thể quá nặng hoặc máy tính không hỗ trợ giải mã."
                            if (fallback != null && fallback.exists() && fallback.absolutePath != failed?.absolutePath) {
                                onError("$msg Đang phát lại video dự phòng...")
                                playVideoFile(fallback, keepPosition = false)
                            } else {
                                onError(msg)
                            }
                        }
                    }
                }, 20, TimeUnit.SECONDS)

                val media = Media(file.toURI().toString())
                val player = MediaPlayer(media).apply {
                    cycleCount = MediaPlayer.INDEFINITE // Lặp vô tận 24/7

                    setOnReady {
                        watchdogTask?.cancel(false)
                        if (savedPositionMs > 0.0) {
                            seek(Duration.millis(savedPositionMs))
                        }
                        play()
                        lastKnownGoodFile = file
                        onVideoPlayStarted?.invoke(file)
                    }

                    setOnError {
                        watchdogTask?.cancel(false)
                        val err = error?.message ?: "Lỗi giải mã video không xác định"
                        val fallback = lastKnownGoodFile
                        if (fallback != null && fallback.exists() && fallback.absolutePath != file.absolutePath) {
                            onError("Lỗi video ($err). Đang khôi phục video dự phòng...")
                            playVideoFile(fallback, keepPosition = false)
                        } else {
                            onError("Lỗi phát video: $err")
                        }
                    }
                }

                mediaPlayer = player
                mediaView.mediaPlayer = player

            } catch (e: Exception) {
                onError("Không thể nạp video: ${e.message}")
            }
        }
    }

    fun pause() {
        try {
            mediaPlayer?.let {
                savedPositionMs = it.currentTime.toMillis()
                it.pause()
            }
        } catch (_: Exception) {}
    }

    fun resume() {
        try {
            mediaPlayer?.let {
                if (savedPositionMs > 0.0) {
                    it.seek(Duration.millis(savedPositionMs))
                }
                it.play()
            }
        } catch (_: Exception) {}
    }

    fun release() {
        watchdogTask?.cancel(true)
        try {
            mediaPlayer?.stop()
            mediaPlayer?.dispose()
        } catch (_: Exception) {}
        mediaPlayer = null
    }
}
