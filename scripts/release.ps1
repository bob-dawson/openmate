param(
    [string]$Version = "",
    [switch]$SkipBridge,
    [switch]$SkipAndroid,
    [switch]$SkipLinux,
    [switch]$SkipTag,
    [switch]$DryRun
)

$ErrorActionPreference = "Stop"

function Invoke-Native {
    param([scriptblock]$Cmd)
    $global:LASTEXITCODE = 0
    & @Cmd 2>&1
    return $global:LASTEXITCODE
}

$ProjectRoot = "D:\openmate"
$BridgeRoot = "$ProjectRoot\opencode-bridge"
$AndroidRoot = "$ProjectRoot\android"
$ReleaseBase = "$ProjectRoot\release"

function Get-BridgeVersion {
    $content = Get-Content "$BridgeRoot\Cargo.toml" -Raw
    if ($content -match 'version\s*=\s*"([^"]+)"') {
        return $Matches[1]
    }
    throw "Cannot read version from Cargo.toml"
}

function Get-LastTag {
    param([string]$ExcludeVersion = "")
    $ea = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
    $tags = & git -C $ProjectRoot tag --sort=-version:refname 2>&1
    $ErrorActionPreference = $ea
    $exclude = if ($ExcludeVersion) { "v$ExcludeVersion" } else { "" }
    foreach ($t in $tags) {
        $t = $t.Trim()
        if ($t -and $t -ne $exclude) { return $t }
    }
    return $null
}

function Generate-Changelog {
    param([string]$Version, [string]$LastTag, [string]$OutPath)

    $header = "# OpenMate $Version`n`nReleased: $(Get-Date -Format 'yyyy-MM-dd')`n"

    $logArgs = @("--pretty=format:- %s (%h)", "--no-merges")
    if ($LastTag) {
        $logArgs += "$LastTag..HEAD"
    }

    $tmpFile = [System.IO.Path]::GetTempFileName()
    $prevEncoding = [Console]::OutputEncoding
    [Console]::OutputEncoding = [System.Text.Encoding]::UTF8
    & git -c core.quotepath=false -C $ProjectRoot log @logArgs | Out-File -FilePath $tmpFile -Encoding UTF8
    [Console]::OutputEncoding = $prevEncoding
    $commits = Get-Content $tmpFile -Encoding UTF8
    Remove-Item $tmpFile -Force
    if (-not $commits) {
        $commits = "(no commits since last tag)"
    }

    $bridgeSection = "`n## Bridge`n"
    $bridgeCommits = $commits | Where-Object { $_ -match "bridge|Bridge|sync" }
    if ($bridgeCommits) {
        $bridgeSection += ($bridgeCommits -join "`n") + "`n"
    } else {
        $bridgeSection += "(no bridge-specific commits)`n"
    }

    $androidSection = "`n## Android`n"
    $androidCommits = $commits | Where-Object { $_ -match "android|Android|compose|UI|message|session" }
    if ($androidCommits) {
        $androidSection += ($androidCommits -join "`n") + "`n"
    } else {
        $androidSection += "(no android-specific commits)`n"
    }

    $allSection = "`n## All Commits`n"
    $allSection += ($commits -join "`n") + "`n"

    $content = $header + $bridgeSection + $androidSection + $allSection
    [System.IO.File]::WriteAllText($OutPath, $content, [System.Text.UTF8Encoding]::new($false))
    Write-Host "  CHANGELOG written to $OutPath" -ForegroundColor Green
}

# --- Main ---

if (-not $Version) {
    $Version = Get-BridgeVersion
    Write-Host "Using version from Cargo.toml: $Version" -ForegroundColor Cyan
}

$ReleaseDir = "$ReleaseBase\$Version"
$InstallDoc = "$ProjectRoot\docs\INSTALL.md"

if ($DryRun) {
    Write-Host "[DRY RUN] Would create release $Version at $ReleaseDir" -ForegroundColor Yellow
    Write-Host "[DRY RUN] Components: Bridge=$( -not $SkipBridge), Android=$( -not $SkipAndroid), Linux=$( -not $SkipLinux), Tag=$( -not $SkipTag)"
    return
}

Write-Host ""
Write-Host "=== OpenMate Release $Version ===" -ForegroundColor Cyan
Write-Host ""

# Step 1: Create release directory
New-Item -ItemType Directory -Force -Path $ReleaseDir | Out-Null
Write-Host "[1/5] Release directory: $ReleaseDir" -ForegroundColor Green

# Step 2: Build Bridge (Windows)
if (-not $SkipBridge) {
    Write-Host "[2/5] Building Bridge (Windows x86_64)..." -ForegroundColor Cyan
    Push-Location $BridgeRoot
    try {
        $ea = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
        $output = cargo build --release 2>&1
        $ErrorActionPreference = $ea
        $output | Where-Object { $_ -match "Compiling|Finished|error" } | ForEach-Object { Write-Host "  $_" }
        if ($LASTEXITCODE -ne 0) { throw "Bridge build failed" }

        Copy-Item "$BridgeRoot\target\release\openmate.exe" "$ReleaseDir\openmate.exe" -Force
        Copy-Item "$BridgeRoot\bridge.toml" "$ReleaseDir\bridge.toml" -Force
        Write-Host "  Bridge Windows -> openmate.exe" -ForegroundColor Green
    } finally {
        Pop-Location
    }
} else {
    Write-Host "[2/5] Skipping Bridge build" -ForegroundColor Yellow
}

# Step 3: Build Bridge (Linux)
if (-not $SkipLinux -and -not $SkipBridge) {
    Write-Host "[3/5] Building Bridge (Linux x86_64)..." -ForegroundColor Cyan
    Push-Location $BridgeRoot
    try {
        $target = "x86_64-unknown-linux-gnu"
        $installed = rustup target list --installed 2>$null
        if ($installed -notcontains $target) {
            Write-Host "  Installing target $target..." -ForegroundColor Yellow
            rustup target add $target
        }
        $ea = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
        $output = cargo build --release --target $target 2>&1
        $ErrorActionPreference = $ea
        $output | Where-Object { $_ -match "Compiling|Finished|error" } | ForEach-Object { Write-Host "  $_" }
        if ($LASTEXITCODE -ne 0) {
            Write-Host "  Linux cross-compile failed (may need linker config)" -ForegroundColor Red
        } else {
            Copy-Item "$BridgeRoot\target\$target\release\openmate" "$ReleaseDir\openmate-linux-x86_64" -Force
            Write-Host "  Bridge Linux -> openmate-linux-x86_64" -ForegroundColor Green
        }
    } finally {
        Pop-Location
    }
} else {
    Write-Host "[3/5] Skipping Linux build" -ForegroundColor Yellow
}

# Step 4: Build Android APK
if (-not $SkipAndroid) {
    Write-Host "[4/5] Building Android APK..." -ForegroundColor Cyan
    Push-Location $AndroidRoot
    try {
        $ea = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
        $output = .\gradlew.bat assembleRelease --no-daemon 2>&1
        $ErrorActionPreference = $ea
        $output | Where-Object { $_ -match "^e:|BUILD SUCCESSFUL|BUILD FAILED" } | ForEach-Object { Write-Host "  $_" }
        if ($LASTEXITCODE -ne 0) { throw "Android build failed" }

        Copy-Item "$AndroidRoot\app\build\outputs\apk\release\app-release.apk" "$ReleaseDir\OpenMate-$Version.apk" -Force
        $apkSize = [math]::Round((Get-Item "$ReleaseDir\OpenMate-$Version.apk").Length / 1MB, 1)
        Write-Host "  APK -> OpenMate-$Version.apk ($apkSize MB)" -ForegroundColor Green
    } finally {
        Pop-Location
    }
} else {
    Write-Host "[4/5] Skipping Android build" -ForegroundColor Yellow
}

# Step 5: Copy install guide and generate changelog and tag
Write-Host "[5/5] Generating changelog..." -ForegroundColor Cyan
if (Test-Path $InstallDoc) {
    Copy-Item $InstallDoc "$ReleaseDir\INSTALL.md" -Force
}
$lastTag = Get-LastTag -ExcludeVersion $Version
if ($lastTag) {
    Write-Host "  Last tag: $lastTag" -ForegroundColor Gray
} else {
    Write-Host "  No previous tags found" -ForegroundColor Gray
}

Generate-Changelog -Version $Version -LastTag $lastTag -OutPath "$ReleaseDir\CHANGELOG.md"

if (-not $SkipTag) {
    $tag = "v$Version"
    Write-Host "  Creating git tag $tag..." -ForegroundColor Cyan
    $ea2 = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
    git -C $ProjectRoot tag $tag 2>&1 | Out-Null
    $ErrorActionPreference = $ea2
    if ($LASTEXITCODE -eq 0) {
        Write-Host "  Tagged: $tag" -ForegroundColor Green
    } else {
        Write-Host "  Tag $tag may already exist" -ForegroundColor Yellow
    }
}

# Summary
Write-Host ""
Write-Host "=== Release $Version Complete ===" -ForegroundColor Green
Get-ChildItem $ReleaseDir | ForEach-Object {
    $size = if ($_.Length -gt 1MB) { "({0:N1} MB)" -f ($_.Length / 1MB) } elseif ($_.Length -gt 1KB) { "({0:N1} KB)" -f ($_.Length / 1KB) } else { "({0:N0} B)" -f $_.Length }
    Write-Host ("  {0} {1}" -f $_.Name, $size) -ForegroundColor White
}
Write-Host ""
Write-Host "Next: git push origin v$Version" -ForegroundColor Cyan