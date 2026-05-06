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
if %errorlevel% neq 0 (
    start "" "D:\openmate\opencode-bridge\release\openmate.exe"
)

:bridge_fail

echo === Installing APK ===
adb install -r "D:\openmate\android\app\build\outputs\apk\debug\app-debug.apk"
if %errorlevel% neq 0 (
    echo FAILED: Could not install APK - is device connected?
)

echo === Done ===
