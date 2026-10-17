# Serial Monitor (Android)

Native Kotlin + Jetpack Compose (Material 3). App id `com.matthew.serialmonitor`, minSdk 24, target/compile SDK 37.

Build (JDK 17 + Android SDK):

    ./gradlew testDebugUnitTest lintRelease assembleRelease
    cp app/build/outputs/apk/release/app-release.apk SerialMonitor.apk

Layout:
- `core/` pure Kotlin, unit tested: line assembly, UTF-8 decode, hex, line endings, CRC, plot parser/buffer, CSV, highlight, ESP crash detector, reset sequences, USB IDs
- `transport/` USB (usb-serial-for-android 3.11.0 + custom probe table), Bluetooth Classic SPP, BLE UART
- `session/` process-wide SerialSession + foreground SerialService (survives rotation/background)
- `ui/` Compose screens: Connect, Terminal, Plotter, Settings

Signing: release builds read `keystore/keystore.properties` (not in this repo). Without it, build the debug APK with `./gradlew assembleDebug`.
