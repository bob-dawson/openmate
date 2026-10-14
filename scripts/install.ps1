<#
.SYNOPSIS
  OpenMate Bridge installer for Windows.

.DESCRIPTION
  Downloads openmate.exe from the release for this machine and optionally
  registers it as a Windows service. Mirrors are picked the same way the apps
  do: version.json tells us the current tag, we try the mirror for your region
  first and fall back to the other one.

.EXAMPLE
  irm <install.ps1 url> | iex

.EXAMPLE
  irm <install.ps1 url> | iex; Install-OpenMateBridge -Service
#>
param(
    [string]$Version,
    [string]$Dir = "$env:LOCALAPPDATA\OpenMate",
    [switch]$Service
)

$ErrorActionPreference = 'Stop'

$GitHubBase = 'https://github.com/bob-dawson/openmate/releases/download'
$AtomGitBase = 'https://atomgit.com/article88/openmate/releases/download'
$VersionUrl = 'https://gateway.clawmate.net/version.json'
$VersionUrlFallback = 'https://raw.githubusercontent.com/bob-dawson/openmate/main/version.json'

function Get-LatestTag {
    $json = $null
    foreach ($url in @($VersionUrl, $VersionUrlFallback)) {
        try {
            $json = Invoke-RestMethod -Uri $url -TimeoutSec 20
            break
        } catch {
            Write-Host "  version.json unavailable at $url"
        }
    }
    if (-not $json) { throw 'could not determine the latest version; pass -Version' }
    return $json.bridge.tag
}

function Get-MirrorBases {
    $region = if ($env:OPENMATE_REGION) { $env:OPENMATE_REGION } else { 'default' }
    $culture = [System.Globalization.CultureInfo]::CurrentUICulture.Name
    if ($culture -like 'zh-CN*') { $region = 'cn' }
    if ($region -eq 'cn') { return @($AtomGitBase, $GitHubBase) }
    return @($GitHubBase, $AtomGitBase)
}

$asset = 'openmate.exe'
if (-not $Version) { $Version = Get-LatestTag }
if ($Version -notlike 'v*') { $Version = "v$Version" }

Write-Host "OpenMate Bridge $Version ($asset)"
New-Item -ItemType Directory -Force -Path $Dir | Out-Null
$target = Join-Path $Dir 'openmate.exe'

$downloaded = $false
foreach ($base in Get-MirrorBases) {
    $url = "$base/$Version/$asset"
    Write-Host "  downloading $url"
    try {
        Invoke-WebRequest -Uri $url -OutFile $target -TimeoutSec 900
        if ((Get-Item $target).Length -gt 0) { $downloaded = $true; break }
    } catch {
        Write-Host "  failed, trying next mirror"
    }
}
if (-not $downloaded) { throw 'download failed from all mirrors' }
Write-Host "installed: $target"

if ($Service) {
    Write-Host 'registering Windows service (needs an elevated shell)...'
    & $target install
}

Write-Host @"

Done. Next steps:
  1. make sure opencode v2 is installed (https://opencode.ai)
  2. start the bridge:  $target
  3. open the management page (printed on startup, default http://127.0.0.1:4097/ui/)
  4. pair the OpenMate app with the shown address/PIN

Re-run with -Service to autostart at boot.
"@
