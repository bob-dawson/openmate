@echo off
setlocal

echo === Building Bridge (release) ===
cargo build --release --manifest-path "D:\openmate\opencode-bridge\Cargo.toml"
if %errorlevel% neq 0 (
    echo FAILED: Bridge build failed
    goto :eof
)

echo === Stopping Bridge ===
net stop OpenMate >nul 2>&1
taskkill /F /IM openmate.exe >nul 2>&1
timeout /t 2 /nobreak >nul

echo === Copying Bridge binary ===
copy /Y "D:\openmate\opencode-bridge\target\release\openmate.exe" "D:\openmate\opencode-bridge\release\openmate.exe"
if %errorlevel% neq 0 (
    echo FAILED: Could not copy Bridge binary
    goto :eof
)

echo === Starting Bridge ===
net start OpenMate >nul 2>&1
if %errorlevel% equ 0 goto :bridge_ok

sc.exe query OpenMate >nul 2>&1
if %errorlevel% equ 0 (
    echo Service installed but failed to start. Check services.msc for account configuration.
    goto :bridge_fg
)

echo Service not installed, installing...
"D:\openmate\opencode-bridge\release\openmate.exe" install
if %errorlevel% equ 0 (
    echo Service installed. Starting...
    net start OpenMate >nul 2>&1
    if %errorlevel% equ 0 goto :bridge_ok
    echo Service start failed. Check services.msc for account configuration.
    goto :bridge_fg
)

echo Install failed, starting in foreground...
:bridge_fg
start "" "D:\openmate\opencode-bridge\release\openmate.exe"

:bridge_ok
timeout /t 3 /nobreak >nul

echo === Building APK (release) ===
cd /d "D:\openmate\android"
call .\gradlew.bat assembleRelease --no-daemon
if %errorlevel% neq 0 (
    echo FAILED: APK build failed
    goto :eof
)

echo === Installing APK ===
adb install -r "D:\openmate\android\app\build\outputs\apk\release\app-release.apk"
if %errorlevel% neq 0 (
    echo FAILED: Could not install APK - is device connected?
)

echo === Done ===
