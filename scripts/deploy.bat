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
start "" "D:\openmate\opencode-bridge\release\openmate.exe"
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
