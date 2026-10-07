package com.signage.player.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.signage.player.data.PreferencesManager
import com.signage.player.ui.MainActivity

/**
 * Bộ thu phát tín hiệu khởi động hệ thống (Boot Completed Broadcast Receiver):
 * - Tự động kích hoạt khi thiết bị (Điện thoại, TV Box, Bo mạch màn hình máy lọc nước) bật nguồn.
 * - Khởi chạy MainActivity lên màn hình chính mà không cần thao tác bấm tay của con người.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        // Hỗ trợ cả sự kiện khởi động chuẩn của Android và sự kiện QuickBoot của một số dòng chip TV Box
        val isBootEvent = intent.action == Intent.ACTION_BOOT_COMPLETED ||
                intent.action == "android.intent.action.QUICKBOOT_POWERON" ||
                intent.action == "com.htc.intent.action.QUICKBOOT_POWERON"

        if (isBootEvent) {
            val prefs = PreferencesManager(context)

            // QUAN TRỌNG: Chỉ tự mở khi máy đã có cấu hình link hoặc file video lưu sẵn
            if (prefs.isAutoBootEnabled && prefs.mediaUrl.isNotBlank()) {
                val launchIntent = Intent(context, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(launchIntent)
            }
        }
    }
}
