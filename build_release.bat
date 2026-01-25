@echo off
set "JAVA_HOME=C:\Program Files\Android\Android Studio\jbr"
cd /d "C:\Users\Iwill\Documents\Projects\ridestr 2026"
echo Building Rider app (release)...
call gradlew.bat :rider-app:assembleRelease
echo.
echo Building Driver app (release)...
call gradlew.bat :drivestr:assembleRelease
echo.
echo Done! APKs located at:
echo   rider-app\build\outputs\apk\release\rider-app-release.apk
echo   drivestr\build\outputs\apk\release\drivestr-release.apk
