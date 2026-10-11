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
    Application.launch(MainApplication::class.java, *args)
}
