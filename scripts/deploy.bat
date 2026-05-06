@echo off
setlocal

echo === Stopping Bridge ===
net stop OpenMate >nul 2>&1
taskkill /F /IM openmate.exe >nul 2>&1
timeout /t 2 /nobreak >nul

echo === Copying Bridge binary ===
copy /Y "D:\openmate\opencode-bridge\target\release\openmate.exe" "D:\openmate\opencode-bridge\release\openmate.exe"
if %errorlevel% neq 0 (
    echo FAILED: Could not copy Bridge binary
    goto :bridge_fail
)

echo === Starting Bridge ===
net start OpenMate >nul 2>&1
if %errorlevel% equ 0 goto :bridge_ok

echo Service not installed, installing...
"D:\openmate\opencode-bridge\release\openmate.exe" install
if %errorlevel% equ 0 goto :bridge_ok

echo Install failed, starting in foreground...
start "" "D:\openmate\opencode-bridge\release\openmate.exe"

:bridge_ok
timeout /t 3 /nobreak >nul

:bridge_fail

echo === Installing APK ===
adb install -r "D:\openmate\android\app\build\outputs\apk\debug\app-debug.apk"
if %errorlevel% neq 0 (
    echo FAILED: Could not install APK - is device connected?
)

echo === Done ===
