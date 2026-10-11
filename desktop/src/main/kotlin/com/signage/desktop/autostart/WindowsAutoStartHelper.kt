package com.signage.desktop.autostart

import java.io.File

/**
 * Bộ quản lý tự động khởi chạy cùng Windows (Windows Startup Kiosk).
 * Tự động tạo tệp kích hoạt trong thư mục Startup của Windows (shell:startup).
 */
object WindowsAutoStartHelper {

    private const val SHORTCUT_LNK = "SmartSignagePlayer.lnk"
    private const val SHORTCUT_BAT = "SmartSignagePlayer.bat"

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
            val lnkFile = File(startupDir, SHORTCUT_LNK)
            val batFile = File(startupDir, SHORTCUT_BAT)
            if (batFile.exists()) batFile.delete()

            if (enable) {
                val exePath = targetCommand.trim('"', ' ')
                val exeFile = File(exePath)
                val workDir = if (exeFile.exists()) exeFile.parentFile.absolutePath else ""

                // Tạo phím tắt .lnk chuẩn Windows (hỗ trợ Unicode tiếng Việt, không nháy màn hình cmd đen)
                val psCommand = "\$ws = New-Object -ComObject WScript.Shell; \$s = \$ws.CreateShortcut('${lnkFile.absolutePath}'); \$s.TargetPath = '$exePath'; \$s.WorkingDirectory = '$workDir'; \$s.Save();"

                ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-Command", psCommand)
                    .redirectErrorStream(true)
                    .start()
                    .waitFor()
            } else {
                if (lnkFile.exists()) lnkFile.delete()
            }
        } catch (_: Exception) {}
    }

    fun isAutoStartEnabled(): Boolean {
        val lnkFile = File(startupDir, SHORTCUT_LNK)
        val batFile = File(startupDir, SHORTCUT_BAT)
        return (lnkFile.exists() && lnkFile.length() > 0) || (batFile.exists() && batFile.length() > 0)
    }
}
