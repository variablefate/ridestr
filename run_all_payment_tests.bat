@echo off
set "JAVA_HOME=C:\Program Files\Android\Android Studio\jbr"
cd /d "C:\Users\Iwill\Documents\Projects\ridestr 2026"
call "C:\Users\Iwill\Documents\Projects\ridestr 2026\gradlew.bat" :common:testDebugUnitTest --tests "com.ridestr.common.payment.*" --info 2>&1
