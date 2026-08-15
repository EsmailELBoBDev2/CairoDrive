package com.cairodrive.app

import com.cairodrive.app.auto.CarSearchBridge
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {

    private var carChannel: MethodChannel? = null
    private var identityChannel: MethodChannel? = null

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        carChannel = MethodChannel(
            flutterEngine.dartExecutor.binaryMessenger,
            CarSearchBridge.CHANNEL,
        ).also(CarSearchBridge::attach)

        // Lets the Dart side (AppIdentity, app/lib/src/config/app_identity.dart)
        // learn this build's true package name + signing cert SHA-1, so the
        // Google Places request can identify itself the way an Android-app-
        // restricted key requires. See AppIdentity.kt for why this exists.
        identityChannel = MethodChannel(
            flutterEngine.dartExecutor.binaryMessenger,
            "com.cairodrive.app/identity",
        ).also { channel ->
            channel.setMethodCallHandler { call, result ->
                if (call.method == "resolve") {
                    val identity = AppIdentity.resolve(applicationContext)
                    if (identity == null) {
                        result.success(null)
                    } else {
                        result.success(
                            mapOf(
                                "package" to identity.packageName,
                                "certSha1" to identity.certSha1,
                            ),
                        )
                    }
                } else {
                    result.notImplemented()
                }
            }
        }
    }

    override fun onDestroy() {
        CarSearchBridge.detach()
        carChannel = null
        identityChannel = null
        super.onDestroy()
    }
}
