package com.signage.player.player

import android.media.MediaPlayer
import android.net.Uri
import android.view.View
import android.widget.VideoView
import java.io.File

/**
 * ==============================================================================
 * TRÌNH ĐIỀU KHIỂN PHÁT VIDEO NATIVE (TƯƠNG THÍCH MỌI PHIÊN BẢN ANDROID 4.3 - 15)
 * ==============================================================================
 * - Sử dụng android.widget.VideoView & MediaPlayer tích hợp sẵn trong hệ thống Android.
 * - Giải mã phần cứng trực tiếp (Hardware Acceleration VPU), cực nhẹ, không tốn RAM.
 * - Tự động lặp lại vô tận 24/7 (Continuous Loop) không gián đoạn.
 * - Không phụ thuộc thư viện Google Media3 nặng nề, hoàn toàn không bị lỗi văng trên TV cũ.
 */
class SignagePlayerManager(
    private val videoView: VideoView,
    private val onError: (String) -> Unit
) {

    private var mediaPlayer: MediaPlayer? = null
    private var currentFile: File? = null

    init {
        setupListeners()
    }

    private fun setupListeners() {
        videoView.setOnPreparedListener { mp ->
            mediaPlayer = mp
            mp.isLooping = true // Lặp vô tận 24/7
            videoView.start()
        }

        videoView.setOnCompletionListener {
            // Dự phòng nếu hệ điều hành của một số TV đời cũ không tự bắt cờ looping
            if (currentFile != null && currentFile!!.exists()) {
                videoView.start()
            }
        }

        videoView.setOnErrorListener { _, what, extra ->
            val msg = when (what) {
                MediaPlayer.MEDIA_ERROR_SERVER_DIED -> "Bộ giải mã đa phương tiện hệ thống khởi động lại"
                else -> "Lỗi giải mã video (Mã lỗi: $what, Chi tiết: $extra). Vui lòng kiểm tra định dạng video."
            }
            onError(msg)
            true // Đã bắt lỗi, ngăn Android hiện popup lỗi crash khó chịu
        }
    }

    /**
     * Nạp và phát file video nội bộ đã được lưu trong bộ nhớ máy.
     */
    fun playVideoFile(file: File) {
        currentFile = file
        videoView.visibility = View.VISIBLE
        val uri = Uri.fromFile(file)
        videoView.stopPlayback()
        videoView.setVideoURI(uri)
    }

    /**
     * Tạm dừng khi chuyển sang màn hình khác.
     */
    fun pause() {
        try {
            if (videoView.isPlaying) {
                videoView.pause()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Tiếp tục phát khi quay trở lại ứng dụng.
     */
    fun resume() {
        try {
            if (!videoView.isPlaying && currentFile != null && currentFile!!.exists()) {
                videoView.start()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Giải phóng tài nguyên phần cứng.
     */
    fun release() {
        try {
            videoView.stopPlayback()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        mediaPlayer = null
        currentFile = null
    }
}
