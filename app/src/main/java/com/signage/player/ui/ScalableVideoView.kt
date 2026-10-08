package com.signage.player.ui

import android.content.Context
import android.util.AttributeSet
import android.widget.VideoView

/**
 * ==============================================================================
 * TRÌNH PHÁT VIDEO TỰ ĐỘNG THÍCH ỨNG TỶ LỆ MÀN HÌNH (SCALABLE VIDEO VIEW)
 * ==============================================================================
 * Hỗ trợ mọi tỷ lệ màn hình (TV 16:9, Kiosk đứng 9:16, màn hình vuông 4:3, Ultra-wide 21:9):
 * 1. SCALE_FIT: Vừa vặn (Giữ nguyên tỷ lệ gốc, hiển thị trọn vẹn video, viền đen nếu khác tỷ lệ).
 * 2. SCALE_FILL: Tràn viền (Phóng to lấp đầy 100% màn hình, không viền đen, cắt mép nếu cần).
 * 3. SCALE_STRETCH: Kéo dãn (Kéo dãn hình ảnh phủ kín toàn bộ màn hình).
 */
class ScalableVideoView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : VideoView(context, attrs, defStyleAttr) {

    companion object {
        const val SCALE_FIT = "FIT"         // Vừa vặn (Mặc định)
        const val SCALE_FILL = "FILL"       // Tràn toàn màn hình (Center-Crop)
        const val SCALE_STRETCH = "STRETCH" // Kéo dãn toàn màn hình
    }

    var scaleMode: String = SCALE_FIT
        set(value) {
            field = value
            requestLayout()
            invalidate()
        }

    private var videoWidth: Int = 0
    private var videoHeight: Int = 0

    /**
     * Cập nhật kích thước thực tế của video (được gọi từ MediaPlayer khi load xong).
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
            val widthSpecSize = MeasureSpec.getSize(widthMeasureSpec)
            val heightSpecSize = MeasureSpec.getSize(heightMeasureSpec)

            when (scaleMode) {
                SCALE_STRETCH -> {
                    // Kéo dãn đầy toàn bộ khung màn hình
                    width = widthSpecSize
                    height = heightSpecSize
                }

                SCALE_FILL -> {
                    // Tràn viền (Center-Crop): Phóng to lấp đầy 100% màn hình, không để khoảng đen
                    val widthRatio = widthSpecSize.toFloat() / videoWidth.toFloat()
                    val heightRatio = heightSpecSize.toFloat() / videoHeight.toFloat()
                    val maxRatio = maxOf(widthRatio, heightRatio)

                    width = (videoWidth * maxRatio).toInt()
                    height = (videoHeight * maxRatio).toInt()
                }

                else -> {
                    // SCALE_FIT: Giữ nguyên tỷ lệ chuẩn của video, không bị mất góc hay méo hình
                    if (videoWidth * heightSpecSize < widthSpecSize * videoHeight) {
                        width = heightSpecSize * videoWidth / videoHeight
                        height = heightSpecSize
                    } else if (videoWidth * heightSpecSize > widthSpecSize * videoHeight) {
                        width = widthSpecSize
                        height = widthSpecSize * videoHeight / videoWidth
                    } else {
                        width = widthSpecSize
                        height = heightSpecSize
                    }
                }
            }
        }

        setMeasuredDimension(width, height)
    }
}
