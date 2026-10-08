package com.signage.player.player

import android.media.MediaPlayer
import android.net.Uri
import android.view.View
import com.signage.player.ui.ScalableVideoView
import java.io.File

/**
 * ==============================================================================
 * TRÌNH ĐIỀU KHIỂN PHÁT VIDEO NATIVE & TỰ ĐỘNG CO GIÃN TỶ LỆ THEO MÀN HÌNH
 * ==============================================================================
 * - Hỗ trợ mọi phiên bản Android từ 4.3 (Jelly Bean) đến Android 15.
 * - Giải mã phần cứng VPU trực tiếp, cực nhẹ, không ngốn RAM.
 * - Tự động lặp lại liên tục 24/7.
 * - Tự động điều chỉnh kích thước hiển thị theo tỷ lệ màn hình (Fit, Fill, Stretch).
 */
class SignagePlayerManager(
    private val videoView: ScalableVideoView,
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

            // Cập nhật độ phân giải gốc của video cho ScalableVideoView tính toán tỷ lệ
            val w = mp.videoWidth
            val h = mp.videoHeight
            if (w > 0 && h > 0) {
                videoView.setVideoSize(w, h)
            }

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
     * Cập nhật chế độ tỷ lệ hiển thị video (FIT, FILL, STRETCH).
     */
    fun setScaleMode(mode: String) {
        videoView.scaleMode = mode
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
