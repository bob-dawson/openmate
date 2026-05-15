$ErrorActionPreference = "Stop"

$AndroidRoot = "D:\openmate\android"
$ApkOutput = "$AndroidRoot\app\build\outputs\apk\release\app-release.apk"

Write-Host "Building signed release APK..." -ForegroundColor Cyan

Push-Location $AndroidRoot
try {
    .\gradlew.bat assembleRelease --no-daemon 2>&1 | ForEach-Object {
        if ($_ -match "^e:") { Write-Host $_ -ForegroundColor Red }
        elseif ($_ -match "BUILD SUCCESSFUL") { Write-Host $_ -ForegroundColor Green }
        elseif ($_ -match "BUILD FAILED") { Write-Host $_ -ForegroundColor Red }
    }

    if (-not (Test-Path $ApkOutput)) {
        Write-Host "Release APK not found at $ApkOutput" -ForegroundColor Red
        exit 1
    }

    $apk = Get-Item $ApkOutput
    $sizeMB = [math]::Round($apk.Length / 1MB, 1)
    Write-Host ""
    Write-Host "Release APK: $($apk.FullName)" -ForegroundColor Green
    Write-Host "Size: $sizeMB MB" -ForegroundColor Green
    Write-Host "Built: $($apk.LastWriteTime)" -ForegroundColor Green

    if ($args -contains "--install") {
        Write-Host "Installing to device..." -ForegroundColor Cyan
        adb shell am force-stop com.openmate
        adb install -r $apk.FullName
        Write-Host "Installed!" -ForegroundColor Green
    }
} finally {
    Pop-Location
}