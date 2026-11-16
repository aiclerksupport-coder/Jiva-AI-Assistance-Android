# Jiva AI one-command build and install

From the **extracted source root** in Windows PowerShell, run:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File ".\BUILD_FULL.ps1"
```

To target one phone when multiple are attached:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File ".\BUILD_FULL.ps1" -DeviceSerial "YOUR_ADB_SERIAL"
```

The historical `-AllowIncompleteTrial` flag is accepted for compatibility but does **not** disable checks or bypass build failures.

The script checks Flutter/Dart/ADB, runs verbose `flutter doctor -v`, `flutter clean -v`, `dart format --output=write`, verbose `flutter pub get`, `flutter analyze`, expanded `flutter test`, `flutter build apk --release --verbose`, validates APK presence and hash, checks exactly one authorized USB-debugging device, then runs `adb install -r`.

On the first failing stage, script stops with exit code 1 and writes **only that failure's stage output** under `BUILD_FAILURE_LOGS/FAILED_*.log`. Successful stage capture files are discarded. Command output remains visible live. It does not suppress compile/test problems, and it does not report install success unless ADB returns success.

**Prerequisites:** Windows, Flutter SDK matching `pubspec.yaml` (Dart >=3.12.0), Android SDK/ADB on PATH, JDK 17, Android USB drivers (if applicable), phone USB debugging + authorization, internet for initial dependencies. Source's Android release currently uses `debug` signing; APK is not Play Store release-ready. `dart format` updates source formatting. Flutter/Android compile validity is not proven until running this script on your Windows machine.

Important: the ZIP contains a Flutter Android host tree but the Gradle wrapper JAR is not committed; existing `flutter/android/scripts/ensure-gradle-wrapper.ps1` uses a pinned download for it. If Gradle wrapper resolution fails, script stops and logs the failure; it does not silently bypass it.
