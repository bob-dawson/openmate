#!/bin/sh
# OpenMate Bridge installer for Linux and macOS.
#
#   curl -fsSL <install.sh url> | sh
#   curl -fsSL <install.sh url> | sh -s -- --version v0.3.9 --service
#
# The script mirrors what the apps do: it reads version.json (gateway first,
# raw.githubusercontent as fallback), prefers the mirror for your region and
# falls back to the other one when a download fails.
set -eu

GITHUB_BASE="https://github.com/bob-dawson/openmate/releases/download"
ATOMGIT_BASE="https://atomgit.com/article88/openmate/releases/download"
VERSION_URL="https://gateway.clawmate.net/version.json"
VERSION_URL_FALLBACK="https://raw.githubusercontent.com/bob-dawson/openmate/main/version.json"

VERSION=""
INSTALL_DIR=""
USE_SYSTEM=0
WANT_SERVICE=0
REGION=""
ASSET=""
DEST=""

usage() {
    cat <<'EOF'
OpenMate Bridge installer (Linux / macOS)

Usage:
  curl -fsSL <install.sh url> | sh -s -- [options]

Options:
  --version <vX.Y.Z>    install a specific version (default: latest release)
  --dir <path>          install directory (default: ~/.local/bin)
  --system              install into /usr/local/bin (use sudo when needed)
  --service             register autostart (launchd on macOS, systemd on Linux)
  -h, --help            show this help
EOF
}

log() { printf '%s\n' "$*" >&2; }
die() { printf 'error: %s\n' "$*" >&2; exit 1; }

fetch() { # url dest
    if command -v curl >/dev/null 2>&1; then
        curl -fsSL --connect-timeout 15 --max-time 900 -o "$2" "$1"
    elif command -v wget >/dev/null 2>&1; then
        wget -q --timeout=30 -O "$2" "$1"
    else
        die "curl or wget is required"
    fi
}

while [ $# -gt 0 ]; do
    case "$1" in
        --version) VERSION="${2:-}"; shift 2 ;;
        --dir) INSTALL_DIR="${2:-}"; shift 2 ;;
        --system) USE_SYSTEM=1; shift ;;
        --service) WANT_SERVICE=1; shift ;;
        --region) REGION="${2:-}"; shift 2 ;;
        -h|--help) usage; exit 0 ;;
        *) die "unknown option: $1 (try --help)" ;;
    esac
done

detect_asset() {
    os=$(uname -s)
    arch=$(uname -m)
    case "$os" in
        Darwin) os=darwin ;;
        Linux) os=linux ;;
        *) die "unsupported OS: $os (Windows: use install.ps1)" ;;
    esac
    case "$arch" in
        arm64|aarch64) arch=arm64 ;;
        x86_64|amd64) arch=x86_64 ;;
        *) die "unsupported architecture: $arch" ;;
    esac
    if [ "$os" = darwin ] && [ "$arch" != arm64 ]; then
        die "only Apple Silicon macOS builds are published"
    fi
    ASSET="openmate-$os-$arch"
}

detect_region() {
    [ -n "$REGION" ] && return 0
    case "${LC_ALL:-${LANG:-}}" in
        zh_CN*|zh-CN*|zh_CN.*) REGION=cn ;;
        *) REGION=default ;;
    esac
}

latest_tag() {
    tmp=$(mktemp 2>/dev/null || echo "${TMPDIR:-/tmp}/openmate-version.$$")
    tag=""
    if fetch "$VERSION_URL" "$tmp" 2>/dev/null || fetch "$VERSION_URL_FALLBACK" "$tmp" 2>/dev/null; then
        tag=$(sed -n 's/.*"tag"[^"]*"\(v[0-9][^"]*\)".*/\1/p' "$tmp" | head -n 1)
    fi
    rm -f "$tmp"
    [ -n "$tag" ] || die "could not determine the latest version; pass --version"
    printf '%s' "$tag"
}

mirrors() {
    if [ "$REGION" = cn ]; then
        printf '%s %s' "$ATOMGIT_BASE" "$GITHUB_BASE"
    else
        printf '%s %s' "$GITHUB_BASE" "$ATOMGIT_BASE"
    fi
}

download_asset() { # tag asset dest
    for base in $(mirrors); do
        url="$base/$1/$2"
        log "  downloading $url"
        if fetch "$url" "$3" && [ -s "$3" ]; then
            return 0
        fi
        log "  failed, trying next mirror"
    done
    return 1
}

resolve_dest() {
    if [ "$USE_SYSTEM" = 1 ]; then
        DEST="/usr/local/bin"
        SUDO="sudo"
        if [ "$(id -u)" = 0 ]; then SUDO=""; fi
    else
        DEST="${INSTALL_DIR:-$HOME/.local/bin}"
        SUDO=""
    fi
}

setup_service() {
    if [ "$(uname -s)" = Darwin ]; then
        plist="$HOME/Library/LaunchAgents/net.openmate.bridge.plist"
        mkdir -p "$HOME/Library/LaunchAgents" "$HOME/.openmate"
        cat > "$plist" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>Label</key><string>net.openmate.bridge</string>
    <key>ProgramArguments</key><array><string>$DEST/openmate</string></array>
    <key>RunAtLoad</key><true/>
    <key>KeepAlive</key><true/>
    <key>StandardOutPath</key><string>$HOME/.openmate/bridge.log</string>
    <key>StandardErrorPath</key><string>$HOME/.openmate/bridge.log</string>
</dict>
</plist>
EOF
        launchctl unload "$plist" 2>/dev/null || true
        launchctl load "$plist"
        log "registered launchd agent: $plist"
    else
        # The Bridge writes and enables its own systemd unit (needs root).
        $SUDO "$DEST/openmate" install
    fi
}

detect_asset
detect_region
resolve_dest
[ -n "$VERSION" ] || VERSION=$(latest_tag)
case "$VERSION" in v*) ;; *) VERSION="v$VERSION" ;; esac

log "OpenMate Bridge $VERSION ($ASSET, region=$REGION)"
log "install directory: $DEST"

tmp_dir=$(mktemp -d 2>/dev/null || mktemp -d -t openmate)
trap 'rm -rf "$tmp_dir"' EXIT

download_asset "$VERSION" "$ASSET" "$tmp_dir/openmate" || die "download failed from all mirrors"
chmod +x "$tmp_dir/openmate"

$SUDO mkdir -p "$DEST"
$SUDO cp "$tmp_dir/openmate" "$DEST/openmate"
$SUDO chmod 755 "$DEST/openmate"
log "installed: $DEST/openmate"

if [ "$WANT_SERVICE" = 1 ]; then
    setup_service
fi

case ":$PATH:" in
    *":$DEST:"*) ;;
    *) log "note: $DEST is not in your PATH - add it, e.g.:"
       log "      echo 'export PATH=\"$DEST:\$PATH\"' >> ~/.profile" ;;
esac

cat <<EOF

Done. Next steps:
  1. make sure opencode v2 is installed (https://opencode.ai)
  2. start the bridge:  $DEST/openmate
  3. open the management page (printed on startup, default http://127.0.0.1:4097/ui/)
  4. pair the OpenMate app with the shown address/PIN

Run with --service to autostart at login.
EOF
