#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")"
source ~/.cargo/env
cargo build --release "$@"
