package com.cairodrive.app

import com.cairodrive.app.auto.CarSearchBridge
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {

    private var carChannel: MethodChannel? = null

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        carChannel = MethodChannel(
            flutterEngine.dartExecutor.binaryMessenger,
            CarSearchBridge.CHANNEL,
        ).also(CarSearchBridge::attach)
    }

    override fun onDestroy() {
        CarSearchBridge.detach()
        carChannel = null
        super.onDestroy()
    }
}
