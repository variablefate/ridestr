---
name: deploy
description: Deploy test builds to Android device over Tailscale wireless ADB. Use for "deploy rider", "deploy driver", "send build", "install on device", "push to phone", "test on device", "deploy both apps", "standard deploy".
---

# Deploy to Android

Deploy debug builds to Android devices via ADB.

**Deploy = Build + Install + Launch** (always launch the app after installing)

## Standard Deployment (Recommended for Testing)

**Use for**: "standard deploy", "deploy standard", "deploy for testing", "test deployment"

Deploys **Rider → USB device** and **Driver → Emulator** without needing wireless debugging port.

```bash
# 1. Check connected devices
C:/Users/Iwill/AppData/Local/Android/Sdk/platform-tools/adb.exe devices -l

# 2. Build both apps
JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" ./gradlew :rider-app:assembleDebug :drivestr:assembleDebug

# 3. Install Rider on USB device (use device serial from `adb devices`)
C:/Users/Iwill/AppData/Local/Android/Sdk/platform-tools/adb.exe -s {USB_DEVICE_SERIAL} install -r rider-app/build/outputs/apk/debug/rider-app-debug.apk

# 4. Install Driver on emulator
C:/Users/Iwill/AppData/Local/Android/Sdk/platform-tools/adb.exe -s emulator-5554 install -r drivestr/build/outputs/apk/debug/drivestr-debug.apk

# 5. Launch both apps
C:/Users/Iwill/AppData/Local/Android/Sdk/platform-tools/adb.exe -s {USB_DEVICE_SERIAL} shell am start -n com.ridestr.rider/.MainActivity
C:/Users/Iwill/AppData/Local/Android/Sdk/platform-tools/adb.exe -s emulator-5554 shell am start -n com.drivestr.app/.MainActivity
```

**Note**: USB device serial looks like `49011FDAP00005` (physical) vs `emulator-5554` (emulator).

---

# Deploy to Android over Tailscale (Remote)

Deploy debug builds to Android device via wireless ADB over Tailscale network.

## IMPORTANT: Ask for Port First

**The wireless debugging port changes frequently.** Before deploying via Tailscale, ALWAYS ask the user:

> What's the current wireless debugging port? (Check Settings → Developer options → Wireless debugging on your device)

## Environment Configuration

```bash
# Full paths required on this Windows system
ADB="C:/Users/Iwill/AppData/Local/Android/Sdk/platform-tools/adb.exe"
JAVA_HOME="C:/Program Files/Android/Android Studio/jbr"

# Device - IP is static via Tailscale, PORT changes frequently
IP="100.80.133.113"
PORT="<ask user every time>"
```

## App Package Names

- **Rider**: `com.ridestr.rider/.MainActivity`
- **Driver**: `com.drivestr.app/.MainActivity`

## Deploy Workflow

Replace `{PORT}` with the port provided by the user.

### Deploy Rider App
```bash
# 1. Connect
C:/Users/Iwill/AppData/Local/Android/Sdk/platform-tools/adb.exe connect 100.80.133.113:{PORT}

# 2. Build
JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" ./gradlew :rider-app:assembleDebug

# 3. Install
C:/Users/Iwill/AppData/Local/Android/Sdk/platform-tools/adb.exe -s 100.80.133.113:{PORT} install -r rider-app/build/outputs/apk/debug/rider-app-debug.apk

# 4. Launch (ALWAYS do this)
C:/Users/Iwill/AppData/Local/Android/Sdk/platform-tools/adb.exe -s 100.80.133.113:{PORT} shell am start -n com.ridestr.rider/.MainActivity
```

### Deploy Driver App
```bash
# 1. Connect
C:/Users/Iwill/AppData/Local/Android/Sdk/platform-tools/adb.exe connect 100.80.133.113:{PORT}

# 2. Build
JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" ./gradlew :drivestr:assembleDebug

# 3. Install
C:/Users/Iwill/AppData/Local/Android/Sdk/platform-tools/adb.exe -s 100.80.133.113:{PORT} install -r drivestr/build/outputs/apk/debug/drivestr-debug.apk

# 4. Launch (ALWAYS do this)
C:/Users/Iwill/AppData/Local/Android/Sdk/platform-tools/adb.exe -s 100.80.133.113:{PORT} shell am start -n com.drivestr.app/.MainActivity
```

### Deploy Both Apps
```bash
# 1. Connect
C:/Users/Iwill/AppData/Local/Android/Sdk/platform-tools/adb.exe connect 100.80.133.113:{PORT}

# 2. Build both
JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" ./gradlew :rider-app:assembleDebug :drivestr:assembleDebug

# 3. Install both
C:/Users/Iwill/AppData/Local/Android/Sdk/platform-tools/adb.exe -s 100.80.133.113:{PORT} install -r rider-app/build/outputs/apk/debug/rider-app-debug.apk
C:/Users/Iwill/AppData/Local/Android/Sdk/platform-tools/adb.exe -s 100.80.133.113:{PORT} install -r drivestr/build/outputs/apk/debug/drivestr-debug.apk

# 4. Launch both (ALWAYS do this)
C:/Users/Iwill/AppData/Local/Android/Sdk/platform-tools/adb.exe -s 100.80.133.113:{PORT} shell am start -n com.ridestr.rider/.MainActivity
C:/Users/Iwill/AppData/Local/Android/Sdk/platform-tools/adb.exe -s 100.80.133.113:{PORT} shell am start -n com.drivestr.app/.MainActivity
```

## Quick Reference

| Step | Rider | Driver |
|------|-------|--------|
| Build | `./gradlew :rider-app:assembleDebug` | `./gradlew :drivestr:assembleDebug` |
| APK Path | `rider-app/build/outputs/apk/debug/rider-app-debug.apk` | `drivestr/build/outputs/apk/debug/drivestr-debug.apk` |
| Launch | `shell am start -n com.ridestr.rider/.MainActivity` | `shell am start -n com.drivestr.app/.MainActivity` |

## Troubleshooting

### Connection Refused
The port changed. Ask user for new port from Settings → Developer options → Wireless debugging.

### JAVA_HOME Not Set
Prefix gradle commands with:
```bash
JAVA_HOME="C:/Program Files/Android/Android Studio/jbr"
```

### Device Offline
On the Android device:
1. Settings → Developer options → Wireless debugging
2. Ensure Wireless debugging is enabled
3. Note the new port (it changes after sleep/reboot)
