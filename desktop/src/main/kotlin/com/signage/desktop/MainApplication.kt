package com.signage.desktop

import com.signage.desktop.ui.SignageWindow
import javafx.application.Application
import javafx.stage.Stage

/**
 * Điểm vào chính của ứng dụng Smart Signage trên Windows.
 */
class MainApplication : Application() {

    override fun start(primaryStage: Stage) {
        val window = SignageWindow(primaryStage)
        window.init()
    }
}

fun main(args: Array<String>) {
    Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
        try {
            val appData = System.getenv("LOCALAPPDATA") ?: System.getProperty("user.home")
            val logFile = java.io.File(appData, "SmartSignage/crash.log")
            logFile.parentFile?.mkdirs()
            val sw = java.io.StringWriter()
            throwable.printStackTrace(java.io.PrintWriter(sw))
            logFile.appendText("Crash in thread [${thread.name}]:\n$sw\n")
        } catch (_: Exception) {}
    }
    Application.launch(MainApplication::class.java, *args)
}
