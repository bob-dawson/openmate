param([switch]$SkipBuild)

$ErrorActionPreference = "Stop"
$ProjectDir = "D:\openmate\opencode-bridge"
$ReleaseDir = "$ProjectDir\release"
$BinaryName = "openmate.exe"
$BinaryPath = "$ReleaseDir\$BinaryName"

# 1. Find running Bridge and stop it
$proc = Get-Process -Name "openmate" -ErrorAction SilentlyContinue
if ($proc) {
    $oldPid = $proc.Id
    Write-Host "Found running Bridge (PID=$oldPid)" -ForegroundColor Yellow

    # Find listening port from netstat
    $port = 0
    $netstatOut = netstat -ano
    foreach ($line in $netstatOut) {
        if ($line -match "LISTENING" -and $line -match "\s$oldPid\s*$") {
            if ($line -match '0\.0\.0\.0:(\d+)') { $port = [int]$Matches[1]; break }
            if ($line -match '\[::\]:(\d+)') { $port = [int]$Matches[1]; break }
        }
    }
    # Also scan netstat if status API failed
    if ($port -eq 0) {
        $raw = netstat -ano
        $line = $raw | Where-Object { $_ -match "LISTENING" -and $_ -match "\b$($proc.Id)\b" }
        if ($line -and $line[0] -match '0\.0\.0\.0:(\d+)') { $port = [int]$Matches[1] }
    }

    if ($port -gt 0) {
        Write-Host "Requesting graceful shutdown on port $port..." -ForegroundColor Cyan
        try {
            Invoke-RestMethod -Uri "http://127.0.0.1:$port/api/bridge/shutdown" -Method POST -TimeoutSec 5
            Write-Host "Shutdown requested" -ForegroundColor Green
        } catch {
            Write-Host "Shutdown API failed: $_" -ForegroundColor Yellow
        }
        # Wait for process to exit
        $deadline = (Get-Date).AddSeconds(10)
        while ((Get-Date) -lt $deadline -and (Get-Process -Id $proc.Id -ErrorAction SilentlyContinue)) {
            Start-Sleep -Milliseconds 500
        }
        if (Get-Process -Id $proc.Id -ErrorAction SilentlyContinue) {
            Write-Host "Process did not exit, forcing kill..." -ForegroundColor Yellow
            Stop-Process -Id $proc.Id -Force
            Start-Sleep -Seconds 2
        }
    } else {
        Write-Host "Cannot find port, forcing kill..." -ForegroundColor Yellow
        Stop-Process -Id $proc.Id -Force
        Start-Sleep -Seconds 2
    }
    Write-Host "Bridge stopped" -ForegroundColor Green
} else {
    Write-Host "No running Bridge process found" -ForegroundColor Gray
}

# 2. Build release
if (-not $SkipBuild) {
    Write-Host "Building release..." -ForegroundColor Cyan
    Push-Location $ProjectDir
    $savedEAP = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    & cargo build --release 2>&1 | Out-Host
    $buildResult = $LASTEXITCODE
    $ErrorActionPreference = $savedEAP
    Pop-Location
    if ($buildResult -ne 0) { Write-Host "Build failed!" -ForegroundColor Red; exit 1 }
    Write-Host "Build OK" -ForegroundColor Green
}

# 3. Copy binary to release dir
Copy-Item "$ProjectDir\target\release\$BinaryName" $BinaryPath -Force
Write-Host "Binary copied to $BinaryPath" -ForegroundColor Green

# 4. Start new Bridge
Write-Host "Starting Bridge from $BinaryPath..." -ForegroundColor Cyan
Start-Process -FilePath $BinaryPath -WindowStyle Hidden
Start-Sleep -Seconds 5

# 5. Verify
$newProc = Get-Process -Name "openmate" -ErrorAction SilentlyContinue
if ($newProc) {
    $newPort = 0
    $netstatOut = netstat -ano
    foreach ($line in $netstatOut) {
        if ($line -match "LISTENING" -and $line -match "\s$($newProc.Id)\s*$") {
            if ($line -match '0\.0\.0\.0:(\d+)') { $newPort = [int]$Matches[1]; break }
            if ($line -match '\[::\]:(\d+)') { $newPort = [int]$Matches[1]; break }
        }
    }
    Write-Host "Bridge running (old=$oldPid, new=$($newProc.Id), port=$newPort)" -ForegroundColor Green
    if ($newPort -gt 0) {
        try {
            $status = Invoke-RestMethod -Uri "http://127.0.0.1:$newPort/api/bridge/status" -TimeoutSec 3
            Write-Host "  opencode: $($status.opencode.status), version: $($status.bridge.version)" -ForegroundColor Gray
        } catch {}
    }
} else {
    Write-Host "WARNING: Bridge process not found after start!" -ForegroundColor Red
}