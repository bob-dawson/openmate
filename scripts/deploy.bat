@echo off
setlocal

echo === Stopping Bridge ===
taskkill /F /IM openmate.exe 2>nul
timeout /t 2 /nobreak >nul

echo === Copying Bridge binary ===
copy /Y "D:\openmate\opencode-bridge\target\release\openmate.exe" "D:\openmate\opencode-bridge\release\openmate.exe"
if %errorlevel% neq 0 (
    echo FAILED: Could not copy Bridge binary
    goto :bridge_fail
)

echo === Starting Bridge ===
start "" "D:\openmate\opencode-bridge\release\openmate.exe"
timeout /t 3 /nobreak >nul

:bridge_fail

echo === Installing APK ===
adb install -r "D:\openmate\android\app\build\outputs\apk\debug\app-debug.apk"
if %errorlevel% neq 0 (
    echo FAILED: Could not install APK - is device connected?
)

echo === Done ===
