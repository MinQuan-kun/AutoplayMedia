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
 * - Tự động ghi nhớ thời điểm video đang phát dở khi thoát/tắt app để phát tiếp tục
 *   mà không bao giờ bị chạy lại từ đầu.
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

    // Ghi nhớ khoảnh khắc chính xác (miligiây) của video để tiếp tục phát
    private var savedPosition: Int = 0

    fun getCurrentPosition(): Int {
        return try {
            val pos = videoView.currentPosition
            if (pos > 0) pos else savedPosition
        } catch (_: Exception) {
            savedPosition
        }
    }

    fun restorePlaybackPosition(pos: Int) {
        if (pos > 0) {
            savedPosition = pos
        }
    }

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
            override fun surfaceDestroyed(holder: android.view.SurfaceHolder) {
                // Khi bề mặt bị hủy (thoát ra ngoài), lưu ngay vị trí phát
                try {
                    val pos = videoView.currentPosition
                    if (pos > 0) savedPosition = pos
                } catch (_: Exception) {}
            }
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

            // Khôi phục chính xác thời điểm video đang phát dở trước khi thoát app
            if (savedPosition > 0) {
                videoView.seekTo(savedPosition)
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
            // Khi video kết thúc 1 vòng lặp bình thường, reset về đầu để vòng lặp mới tiếp tục
            savedPosition = 0
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
                    failedFile?.delete()
                } catch (_: Exception) {}
                playVideoFile(fallback, keepPosition = false)
            } else {
                onError("$baseMsg Vui lòng kiểm tra định dạng video.")
            }
            true // Đã bắt lỗi an toàn, không để Android hiện popup văng app
        }
    }

    /**
     * Nạp và phát file video nội bộ đã được lưu trong bộ nhớ máy.
     * @param keepPosition nếu true sẽ tiếp tục phát tại vị trí trước đó, false sẽ phát từ đầu.
     */
    fun playVideoFile(file: File, keepPosition: Boolean = false) {
        if (!keepPosition && currentFile?.absolutePath != file.absolutePath) {
            savedPosition = 0
        }
        currentFile = file
        videoView.visibility = View.VISIBLE
        val uri = Uri.fromFile(file)
        videoView.stopPlayback()
        videoView.setVideoURI(uri)
    }

    /**
     * Tạm dừng khi chuyển sang màn hình khác và lưu lại khoảnh khắc phát.
     */
    fun pause() {
        try {
            val pos = try { videoView.currentPosition } catch (_: Exception) { 0 }
            if (pos > 0) {
                savedPosition = pos
            }
            if (videoView.isPlaying) {
                videoView.pause()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Tiếp tục phát khi quay trở lại ứng dụng đúng khoảnh khắc đã tạm dừng.
     */
    fun resume() {
        try {
            if (currentFile != null && currentFile!!.exists()) {
                if (!videoView.isPlaying) {
                    val pos = try { videoView.currentPosition } catch (_: Exception) { 0 }
                    if (pos <= 0 && savedPosition > 0) {
                        val uri = Uri.fromFile(currentFile)
                        videoView.setVideoURI(uri)
                    } else {
                        if (savedPosition > 0) {
                            videoView.seekTo(savedPosition)
                        }
                        videoView.start()
                    }
                }
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
