# Mirror a GitHub Release (assets) to AtomGit from a Windows machine.
#
# CI no longer mirrors releases to AtomGit: uploads from GitHub Actions runners are
# slow and frequently fail. This script is the single supported path - it downloads
# each asset from the GitHub release (optionally through a local proxy) and uploads
# it to AtomGit. It is idempotent and safe to re-run.
#
# Usage:
#   pwsh -File scripts/mirror-release-atomgit.ps1                       # latest release
#   pwsh -File scripts/mirror-release-atomgit.ps1 -Tag v0.3.7
#   pwsh -File scripts/mirror-release-atomgit.ps1 -Tag v0.3.7 -GitHubProxy http://127.0.0.1:7897
#
# GitHub download proxy resolution: -GitHubProxy -> $env:OPENMATE_GITHUB_PROXY ->
# $env:HTTPS_PROXY -> `git config --get http.proxy`. Uploads to AtomGit never use it.
#
# Credentials: -User/-Token -> $env:ATOMGIT_USER/$env:ATOMGIT_TOKEN ->
# %USERPROFILE%\.openmate\atomgit_user / atomgit_token (never commit them).
#
# Optional env: ATOMGIT_REPO (default "openmate"), ATOMGIT_HOST (default "atomgit.com").
param(
    [string]$Tag,
    [string]$User = $env:ATOMGIT_USER,
    [string]$Token = $env:ATOMGIT_TOKEN,
    [string]$Repo = $(if ($env:ATOMGIT_REPO) { $env:ATOMGIT_REPO } else { 'openmate' }),
    [string]$HostName = $(if ($env:ATOMGIT_HOST) { $env:ATOMGIT_HOST } else { 'atomgit.com' }),
    [string]$GitHubProxy = $env:OPENMATE_GITHUB_PROXY
)

$ErrorActionPreference = 'Stop'

# Credential resolution: params -> env vars -> files under %USERPROFILE%\.openmate\
# (kept outside the repo; never commit the token).
if ([string]::IsNullOrWhiteSpace($User)) {
    $userFile = Join-Path $env:USERPROFILE '.openmate\atomgit_user'
    if (Test-Path $userFile) { $User = (Get-Content -Raw $userFile).Trim() }
}
if ([string]::IsNullOrWhiteSpace($Token)) {
    $tokenFile = Join-Path $env:USERPROFILE '.openmate\atomgit_token'
    if (Test-Path $tokenFile) { $Token = (Get-Content -Raw $tokenFile).Trim() }
}

if ([string]::IsNullOrWhiteSpace($User) -or [string]::IsNullOrWhiteSpace($Token)) {
    Write-Error 'AtomGit credentials missing. Provide -User/-Token, set ATOMGIT_USER/ATOMGIT_TOKEN, or create %USERPROFILE%\.openmate\atomgit_user / atomgit_token.'
    exit 2
}

# GitHub downloads may need a proxy (direct access is slow or blocked). Uploads to
# AtomGit are always direct, so the proxy is applied per gh call, never globally.
if ([string]::IsNullOrWhiteSpace($GitHubProxy)) { $GitHubProxy = $env:HTTPS_PROXY }
if ([string]::IsNullOrWhiteSpace($GitHubProxy)) {
    $configured = (git config --get http.proxy) 2>$null
    if ($configured) { $GitHubProxy = $configured.Trim() }
}

function Test-ProxyReachable([string]$ProxyUrl) {
    try {
        $uri = [System.Uri]$ProxyUrl
        $client = [System.Net.Sockets.TcpClient]::new()
        $task = $client.ConnectAsync($uri.Host, $uri.Port)
        $ok = $task.Wait(1500) -and $client.Connected
        $client.Dispose()
        return $ok
    } catch {
        return $false
    }
}

# A configured-but-dead proxy (e.g. the VPN/proxy app is off) must not break downloads.
if (-not [string]::IsNullOrWhiteSpace($GitHubProxy) -and -not (Test-ProxyReachable $GitHubProxy)) {
    Write-Output "github proxy $GitHubProxy is unreachable, downloading directly"
    $GitHubProxy = ''
}

function Invoke-WithGitHubProxy([scriptblock]$Action) {
    $prevHttps = $env:HTTPS_PROXY
    $prevHttp = $env:HTTP_PROXY
    if (-not [string]::IsNullOrWhiteSpace($GitHubProxy)) {
        $env:HTTPS_PROXY = $GitHubProxy
        $env:HTTP_PROXY = $GitHubProxy
    }
    try {
        & $Action
    } finally {
        $env:HTTPS_PROXY = $prevHttps
        $env:HTTP_PROXY = $prevHttp
    }
}

if ([string]::IsNullOrWhiteSpace($Tag)) {
    $Tag = Invoke-WithGitHubProxy { gh release list --limit 1 --json tagName --jq '.[0].tagName' }
}
if ([string]::IsNullOrWhiteSpace($Tag)) { Write-Error 'No release tag found.'; exit 2 }

$api = "https://$HostName/api/v5/repos/$User/$Repo"
$authHeader = @{ 'PRIVATE-TOKEN' = $Token }
# Assets are downloaded outside the repo so the script itself can never be uploaded.
$workDir = Join-Path $env:TEMP "openmate-atomgit-$Tag"

Write-Output "mirror $Tag -> $HostName/$User/$Repo"
if (-not [string]::IsNullOrWhiteSpace($GitHubProxy)) { Write-Output "github downloads via proxy $GitHubProxy" }

try {
    New-Item -ItemType Directory -Force $workDir | Out-Null

    # 1) Ensure the release exists (409 "already exists" is fine).
    $body = @{ tag_name = $Tag; name = $Tag; body = ''; prerelease = $false } | ConvertTo-Json
    try {
        Invoke-RestMethod -Method Post -Uri "$api/releases?access_token=$Token" -Headers $authHeader `
            -ContentType 'application/json' -Body $body -TimeoutSec 60 | Out-Null
        Write-Output 'create release -> ok'
    } catch {
        Write-Output "create release -> $($_.Exception.Message)"
    }

    # 2) Asset names come from the GitHub release, never from a directory listing.
    $assets = @(Invoke-WithGitHubProxy { gh release view $Tag --json assets --jq '.assets[].name' })
    if ($assets.Count -eq 0) { Write-Error "GitHub release $Tag has no assets."; exit 1 }

    $failures = 0
    foreach ($name in $assets) {
        try {
            $rel = Invoke-RestMethod -Uri "$api/releases/$Tag`?access_token=$Token" -Headers $authHeader -TimeoutSec 60
        } catch {
            Write-Output "list assets ERR: $($_.Exception.Message)"; $failures++; break
        }
        if (@($rel.assets | ForEach-Object { $_.name }) -contains $name) {
            Write-Output "skip $name (already on AtomGit)"
            continue
        }

        $local = Join-Path $workDir $name
        Invoke-WithGitHubProxy { gh release download $Tag -p $name -D $workDir --clobber }
        if (-not (Test-Path $local)) { Write-Output "download ERR $name"; $failures++; continue }

        $encoded = [uri]::EscapeDataString($name)
        try {
            $up = Invoke-RestMethod -Uri "$api/releases/$Tag/upload_url?access_token=$Token&file_name=$encoded" `
                -Headers $authHeader -TimeoutSec 60
        } catch {
            Write-Output "upload_url ERR $name : $($_.Exception.Message)"; $failures++; continue
        }
        if (-not $up.url) { Write-Output "no upload url for $name : $($up | ConvertTo-Json -Compress)"; $failures++; continue }

        $curlArgs = @(
            '--silent', '--show-error', '--location', '--noproxy', '*',
            '--connect-timeout', '20', '--max-time', '1800',
            '--retry', '3', '--retry-delay', '5',
            '-X', 'PUT', '--data-binary', "@$local",
            '-o', 'NUL', '-w', '%{http_code}'
        )
        if ($up.headers) {
            $up.headers.PSObject.Properties | ForEach-Object { $curlArgs += @('-H', "$($_.Name): $($_.Value)") }
        }
        $curlArgs += $up.url

        $t0 = Get-Date
        $code = (& curl.exe @curlArgs | Select-Object -Last 1)
        $secs = [int]((Get-Date) - $t0).TotalSeconds
        Write-Output "upload $name -> HTTP $code (${secs}s)"
        if ("$code".Length -eq 0 -or "$code"[0] -ne '2') { $failures++ }

        Remove-Item -Force $local -ErrorAction SilentlyContinue
    }

    Write-Output '== AtomGit release assets =='
    $final = Invoke-RestMethod -Uri "$api/releases/$Tag`?access_token=$Token" -Headers $authHeader -TimeoutSec 60
    @($final.assets) | ForEach-Object { Write-Output (' - ' + $_.name) }

    if ($failures -gt 0) { Write-Output "AtomGit mirror incomplete: $failures asset(s) failed."; exit 1 }
    Write-Output 'AtomGit mirror complete.'
} finally {
    Remove-Item -Recurse -Force $workDir -ErrorAction SilentlyContinue
}
