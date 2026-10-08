package com.signage.player.ui

import android.content.Context
import android.util.AttributeSet
import android.widget.VideoView
import kotlin.math.abs

/**
 * ==============================================================================
 * TRÌNH PHÁT VIDEO TỰ ĐỘNG THÍCH ỨNG TỶ LỆ THEO MÀN HÌNH (100% AUTOMATIC ADAPTIVE)
 * ==============================================================================
 * Hệ thống tự động đo đạc và lựa chọn tỷ lệ hiển thị tối ưu mà không cần người dùng cài đặt:
 *
 * 1. CÙNG HƯỚNG (Cùng Ngang hoặc Cùng Dọc):
 *    - Video ngang trên TV ngang (16:9, 16:10, 4:3) hoặc Video dọc trên Kiosk đứng (9:16).
 *    - Tự động CROP tràn viền lấp đầy 100% màn hình, loại bỏ hoàn toàn viền đen gây mất thẩm mỹ.
 *
 * 2. KHÁC HƯỚNG (Video Dọc trên TV Ngang, hoặc Video Ngang trên Kiosk Dọc):
 *    - Tự động FIT vừa vặn tối đa và căn giữa màn hình, bảo toàn nguyên vẹn 100% nội dung video,
 *      không bao giờ bị cắt mất phần trên/dưới hoặc hai bên.
 */
class ScalableVideoView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : VideoView(context, attrs, defStyleAttr) {

    private var videoWidth: Int = 0
    private var videoHeight: Int = 0

    /**
     * Cập nhật độ phân giải gốc của video từ MediaPlayer khi nạp xong file.
     */
    fun setVideoSize(width: Int, height: Int) {
        if (width > 0 && height > 0) {
            videoWidth = width
            videoHeight = height
            requestLayout()
            invalidate()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        var width = getDefaultSize(videoWidth, widthMeasureSpec)
        var height = getDefaultSize(videoHeight, heightMeasureSpec)

        if (videoWidth > 0 && videoHeight > 0) {
            val screenWidth = MeasureSpec.getSize(widthMeasureSpec)
            val screenHeight = MeasureSpec.getSize(heightMeasureSpec)

            if (screenWidth > 0 && screenHeight > 0) {
                val screenIsLandscape = screenWidth >= screenHeight
                val videoIsLandscape = videoWidth >= videoHeight

                if (screenIsLandscape == videoIsLandscape) {
                    // CÙNG HƯỚNG (Cùng màn ngang hoặc cùng màn dọc)
                    val screenRatio = screenWidth.toFloat() / screenHeight.toFloat()
                    val videoRatio = videoWidth.toFloat() / videoHeight.toFloat()
                    val diffRatio = abs(screenRatio - videoRatio) / screenRatio

                    if (diffRatio <= 0.22f) {
                        // Chênh lệch tỷ lệ nhỏ (ví dụ 16:9 vs 16:10, hoặc video chuẩn):
                        // Tự động CROP tràn viền lấp đầy 100% màn hình, không để dải đen
                        val widthRatio = screenWidth.toFloat() / videoWidth.toFloat()
                        val heightRatio = screenHeight.toFloat() / videoHeight.toFloat()
                        val maxRatio = maxOf(widthRatio, heightRatio)

                        width = (videoWidth * maxRatio).toInt()
                        height = (videoHeight * maxRatio).toInt()
                    } else {
                        // Cùng hướng nhưng lệch nhiều (ví dụ video 21:9 trên màn hình 4:3 vuông):
                        // Tự động FIT vừa vặn để bảo toàn góc hình
                        val widthRatio = screenWidth.toFloat() / videoWidth.toFloat()
                        val heightRatio = screenHeight.toFloat() / videoHeight.toFloat()
                        val minRatio = minOf(widthRatio, heightRatio)

                        width = (videoWidth * minRatio).toInt()
                        height = (videoHeight * minRatio).toInt()
                    }
                } else {
                    // KHÁC HƯỚNG (Video dọc trên TV ngang, hoặc Video ngang trên Kiosk dọc):
                    // Tự động FIT căn giữa hoàn hảo, đảm bảo không bao giờ bị cắt mất nội dung video
                    val widthRatio = screenWidth.toFloat() / videoWidth.toFloat()
                    val heightRatio = screenHeight.toFloat() / videoHeight.toFloat()
                    val minRatio = minOf(widthRatio, heightRatio)

                    width = (videoWidth * minRatio).toInt()
                    height = (videoHeight * minRatio).toInt()
                }
            }
        }

        setMeasuredDimension(width, height)
    }
}
