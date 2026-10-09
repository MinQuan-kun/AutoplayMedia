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
    var lastKnownGoodFile: File? = null
        private set

    val currentPlayingFile: File?
        get() = currentFile ?: lastKnownGoodFile

    var onVideoPlayStarted: ((File) -> Unit)? = null

    fun isPlaying(): Boolean {
        return try {
            videoView.isPlaying
        } catch (_: Exception) {
            false
        }
    }

    init {
        setupListeners()
    }

    private fun setupListeners() {
        // Tự động khôi phục luồng phát khi cắm lại dây HDMI hoặc bật lại màn hình TV (SurfaceCreated)
        videoView.holder.addCallback(object : android.view.SurfaceHolder.Callback {
            override fun surfaceCreated(holder: android.view.SurfaceHolder) {
                resume()
            }
            override fun surfaceChanged(holder: android.view.SurfaceHolder, format: Int, width: Int, height: Int) {}
            override fun surfaceDestroyed(holder: android.view.SurfaceHolder) {}
        })

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

            // Ghi nhận file này phát thành công 100% làm bản dự phòng an toàn
            currentFile?.let {
                if (it.exists() && it.length() > 0) {
                    lastKnownGoodFile = it
                    // Báo hiệu video mới đã phát thành công trên màn hình -> an tâm dọn dẹp video cũ
                    onVideoPlayStarted?.invoke(it)
                }
            }
        }

        videoView.setOnCompletionListener {
            // Dự phòng nếu hệ điều hành của một số TV đời cũ không tự bắt cờ looping
            if (currentFile != null && currentFile!!.exists()) {
                videoView.start()
            }
        }

        videoView.setOnErrorListener { _, what, extra ->
            val failedFile = currentFile
            val fallback = lastKnownGoodFile

            val baseMsg = when (what) {
                MediaPlayer.MEDIA_ERROR_SERVER_DIED -> "Bộ giải mã đa phương tiện hệ thống khởi động lại"
                else -> "Lỗi giải mã video (Mã lỗi: $what, Chi tiết: $extra)."
            }

            // Cơ chế FALLBACK: Nếu video mới bị lỗi định dạng nhưng trong máy có video cũ chạy tốt
            if (fallback != null && fallback.exists() && fallback.absolutePath != failedFile?.absolutePath) {
                onError("$baseMsg Đang tự động khôi phục video dự phòng gần nhất...")
                try {
                    // Xóa video mới bị lỗi codec để không bị kẹt lặp lại
                    failedFile?.delete()
                } catch (_: Exception) {}
                playVideoFile(fallback)
            } else {
                onError("$baseMsg Vui lòng kiểm tra định dạng video.")
            }
            true // Đã bắt lỗi an toàn, không để Android hiện popup văng app
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
