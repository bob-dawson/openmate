#!/bin/bash
# Build OpenMate Bridge for Linux (x86_64) inside WSL
# Usage: wsl -d Ubuntu-24.04 -- bash /mnt/d/openmate/opencode-bridge/build-linux.sh

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

echo "=== Installing build dependencies ==="
sudo apt-get update -qq
sudo apt-get install -y -qq build-essential pkg-config libssl-dev 2>/dev/null

if ! command -v cargo &>/dev/null; then
    echo "=== Installing Rust ==="
    curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh -s -- -y
    source "$HOME/.cargo/env"
fi

echo "=== Rust version ==="
rustc --version
cargo --version

echo "=== Building Bridge for Linux (release) ==="
cargo build --release --target x86_64-unknown-linux-gnu

echo ""
echo "=== Build successful ==="
echo "Binary: target/x86_64-unknown-linux-gnu/release/openmate"
ls -lh target/x86_64-unknown-linux-gnu/release/openmate
