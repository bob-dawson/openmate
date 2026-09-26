# Mirror a GitHub Release (notes + assets) to AtomGit from a Windows machine.
#
# Why this exists: the CI job (.github/scripts/mirror-release-atomgit.sh) uploads
# from GitHub Actions runners, which AtomGit sometimes cannot serve (uploads hang
# or return 502). Running from a normal network works reliably. Use this when the
# CI "AtomGit Release Mirror" job fails; it is idempotent and safe to re-run.
#
# Usage:
#   $env:ATOMGIT_USER  = 'article88'
#   $env:ATOMGIT_TOKEN = '<rotated token>'          # never hardcode / never commit
#   pwsh -File scripts/mirror-release-atomgit.ps1                  # latest release
#   pwsh -File scripts/mirror-release-atomgit.ps1 -Tag v0.3.7
#
# Optional env: ATOMGIT_REPO (default "openmate"), ATOMGIT_HOST (default "atomgit.com").
param(
    [string]$Tag,
    [string]$User = $env:ATOMGIT_USER,
    [string]$Token = $env:ATOMGIT_TOKEN,
    [string]$Repo = $(if ($env:ATOMGIT_REPO) { $env:ATOMGIT_REPO } else { 'openmate' }),
    [string]$HostName = $(if ($env:ATOMGIT_HOST) { $env:ATOMGIT_HOST } else { 'atomgit.com' })
)

$ErrorActionPreference = 'Stop'

if ([string]::IsNullOrWhiteSpace($User) -or [string]::IsNullOrWhiteSpace($Token)) {
    Write-Error 'ATOMGIT_USER / ATOMGIT_TOKEN are required (set env vars or pass -User/-Token).'
    exit 2
}

if ([string]::IsNullOrWhiteSpace($Tag)) {
    $Tag = gh release list --limit 1 --json tagName --jq '.[0].tagName'
}
if ([string]::IsNullOrWhiteSpace($Tag)) { Write-Error 'No release tag found.'; exit 2 }

$api = "https://$HostName/api/v5/repos/$User/$Repo"
$authHeader = @{ 'PRIVATE-TOKEN' = $Token }
# Assets are downloaded outside the repo so the script itself can never be uploaded.
$workDir = Join-Path $env:TEMP "openmate-atomgit-$Tag"

Write-Output "mirror $Tag -> $HostName/$User/$Repo"

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
    $assets = @(gh release view $Tag --json assets --jq '.assets[].name')
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
        gh release download $Tag -p $name -D $workDir --clobber
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
            '--silent', '--show-error', '--location',
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
