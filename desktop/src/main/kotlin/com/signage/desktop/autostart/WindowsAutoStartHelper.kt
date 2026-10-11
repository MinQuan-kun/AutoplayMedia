package com.signage.desktop.autostart

import java.io.File

/**
 * Bộ quản lý tự động khởi chạy cùng Windows (Windows Startup Kiosk).
 * Tự động tạo tệp kích hoạt trong thư mục Startup của Windows (shell:startup).
 */
object WindowsAutoStartHelper {

    private const val SHORTCUT_NAME = "SmartSignagePlayer.bat"

    private val startupDir: File by lazy {
        val appData = System.getenv("APPDATA")
            ?: (System.getProperty("user.home") + File.separator + "AppData" + File.separator + "Roaming")
        File(appData, "Microsoft" + File.separator + "Windows" + File.separator + "Start Menu" + File.separator + "Programs" + File.separator + "Startup")
    }

    /**
     * Bật hoặc tắt tính năng tự khởi chạy khi mở máy tính Windows.
     * @param enable true để tự chạy khi mở máy, false để hủy.
     * @param targetCommand Đường dẫn lệnh chạy ứng dụng (file .exe hoặc script khởi động).
     */
    fun setAutoStart(enable: Boolean, targetCommand: String) {
        try {
            if (!startupDir.exists()) startupDir.mkdirs()
            val startupFile = File(startupDir, SHORTCUT_NAME)

            if (enable) {
                // Tạo file khởi chạy batch trong thư mục Startup của Windows
                val scriptContent = buildString {
                    appendLine("@echo off")
                    appendLine("timeout /t 3 /nobreak >nul") // Chờ 3s cho mạng Windows sẵn sàng
                    appendLine("start \"\" $targetCommand")
                }
                startupFile.writeText(scriptContent, Charsets.UTF_8)
            } else {
                if (startupFile.exists()) {
                    startupFile.delete()
                }
            }
        } catch (_: Exception) {}
    }

    fun isAutoStartEnabled(): Boolean {
        val startupFile = File(startupDir, SHORTCUT_NAME)
        return startupFile.exists() && startupFile.length() > 0
    }
}
