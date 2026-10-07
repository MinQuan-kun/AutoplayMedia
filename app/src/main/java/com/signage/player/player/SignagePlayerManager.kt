package com.signage.player.player

import android.content.Context
import android.net.Uri
import android.view.View
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import java.io.File

/**
 * Trình điều khiển phát Video
 */
class SignagePlayerManager(
    private val context: Context,
    private val playerView: PlayerView,
    private val onError: (String) -> Unit
) {

    private var exoPlayer: ExoPlayer? = null

    init {
        initExoPlayer()
    }
    private fun initExoPlayer() {
        exoPlayer = ExoPlayer.Builder(context).build().apply {
            repeatMode = Player.REPEAT_MODE_ALL
            playWhenReady = true

            addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    val msg = when (error.errorCode) {
                        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED ->
                            "Thiết bị không hỗ trợ bộ giải mã cho video này (Codec không tương thích)"
                        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED ->
                            "Định dạng file video bị lỗi hoặc không đầy đủ (Malformed file)"
                        else -> "Lỗi phát video: ${error.localizedMessage}"
                    }
                    onError(msg)
                }
            })
        }

        // Gắn Player vào giao diện hiển thị PlayerView
        playerView.player = exoPlayer
    }

    fun playVideoFile(file: File) {
        playerView.visibility = View.VISIBLE
        val uri = Uri.fromFile(file)
        val mediaItem = MediaItem.fromUri(uri)

        exoPlayer?.let { player ->
            player.stop()
            player.clearMediaItems()
            player.setMediaItem(mediaItem)
            player.prepare()
            player.play()
        }
    }

    /**
     * Tạm dừng khi ứng dụng chuyển xuống nền hoặc khi có sự kiện ưu tiên.
     */
    fun pause() {
        exoPlayer?.pause()
    }

    /**
     * Tiếp tục phát khi ứng dụng hiển thị lại.
     */
    fun resume() {
        exoPlayer?.play()
    }

    /**
     * Giải phóng bộ nhớ RAM và GPU khi Activity bị huỷ.
     */
    fun release() {
        exoPlayer?.release()
        exoPlayer = null
    }
}
