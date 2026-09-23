# Builds the Linux (x86_64-unknown-linux-musl) Bridge binary inside WSL and
# copies it into docker/openmate-linux-x86_64 for the Docker image.
param(
    [string]$Distro = "Ubuntu-24.04",
    [string]$RepoWsl = "/mnt/d/openmate"
)

$ErrorActionPreference = "Stop"

Write-Host "Building Bridge (musl) in WSL [$Distro]..."
wsl -d $Distro -e bash -lc "cd $RepoWsl/opencode-bridge && cargo build --release --target x86_64-unknown-linux-musl"
if ($LASTEXITCODE -ne 0) { throw "cargo build failed" }

Write-Host "Copying binary to docker/openmate-linux-x86_64..."
wsl -d $Distro -e bash -lc "cp $RepoWsl/opencode-bridge/target/x86_64-unknown-linux-musl/release/openmate $RepoWsl/docker/openmate-linux-x86_64"
if ($LASTEXITCODE -ne 0) { throw "copy failed" }

Write-Host "Done: docker/openmate-linux-x86_64"
