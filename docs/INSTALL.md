# OpenMate Installation Guide

## Quick install

### Bridge — paste this into opencode

OpenMate is an opencode companion, so the easiest way to install the Bridge is to let
opencode itself do it. Copy the block below into an opencode session and send it:

```text
Install and start OpenMate Bridge on this machine - it is the companion service for the OpenMate Android app.

1. Detect my operating system and CPU architecture (Windows / Linux / macOS; x86_64 / arm64).
2. Fetch the latest version: GET https://gateway.clawmate.net/version.json (fall back to
   https://raw.githubusercontent.com/bob-dawson/openmate/main/version.json) and read bridge.tag.
3. Choose a download mirror: in mainland China prefer
   https://atomgit.com/article88/openmate/releases/download, otherwise prefer
   https://github.com/bob-dawson/openmate/releases/download. If a download fails or times out,
   retry with the other mirror.
4. Download the asset for this machine from {mirror}/{tag}/{file}:
   - Windows x64 -> openmate.exe
   - Linux x86_64 -> openmate-linux-x86_64
   - Linux arm64 -> openmate-linux-arm64
   - macOS Apple Silicon -> openmate-darwin-arm64
5. Install it: Windows -> %LOCALAPPDATA%\OpenMate\openmate.exe; macOS/Linux -> ~/.local/bin/openmate
   with chmod +x (tell me if that directory is not in PATH).
6. Start it in the background (macOS/Linux: nohup, log to ~/.openmate/bridge.log).
7. Verify with GET http://127.0.0.1:4097/api/bridge/status, then tell me the admin page URL and
   how to pair my phone.
8. Only if I ask for autostart: Linux -> sudo <path>/openmate install; Windows -> run
   openmate.exe install as administrator; macOS -> a launchd agent.

Do not change my global npm configuration. If opencode v2 is not installed here, tell me first.
```

That is all — opencode detects your platform, picks the mirror that is fast in your region and
starts the Bridge. Prefer to do it yourself? See [Manual install](#manual-install) below.

### Android app

Scan with your phone — the page picks the fastest mirror (GitHub / AtomGit) automatically:

<p align="center">
  <img src="assets/qr-app.png" width="200" alt="Download the OpenMate app">
</p>

Or open <https://gateway.clawmate.net/app> directly, or download `OpenMate-*.apk` from
[Releases](https://github.com/bob-dawson/openmate/releases/latest) /
[AtomGit mirror](https://atomgit.com/article88/openmate/releases).

## Manual install

### 1. Run the Bridge

The Bridge is the proxy service that connects the Android client to opencode.

#### Windows

1. Download `openmate.exe` from [Releases](https://github.com/bob-dawson/openmate/releases/latest) and double-click it
2. After the Bridge starts, a **QR code** is displayed in the terminal or the admin page

#### Linux / macOS

```bash
# Linux: openmate-linux-x86_64 or openmate-linux-arm64
# macOS: openmate-darwin-arm64
chmod +x openmate
./openmate
```

After the Bridge starts, a **QR code** is displayed in the terminal.

#### Optional: repository scripts

`scripts/install.sh` (Linux/macOS) and `scripts/install.ps1` (Windows) do the same steps
non-interactively — handy for unattended setups. They read `version.json`, prefer the mirror
for your region and fall back to the other one:

```bash
sh scripts/install.sh --service        # --version v0.3.9 --dir ~/bin --region cn also work
```

#### Install as a System Service (Optional)

```bash
./openmate install     # Windows Service / Linux systemd
./openmate uninstall   # Uninstall
```

On Windows, running as a service is generally not recommended because it requires setting up
a separate service account and password. Running as a regular application is sufficient —
auto-start on boot can be configured in the admin page. On macOS there is no `install`
subcommand; use a launchd agent (`scripts/install.sh --service` writes one).

### 2. Install the Android APK

Download `OpenMate-*.apk` from [Releases](https://github.com/bob-dawson/openmate/releases/latest)
(or the [AtomGit mirror](https://atomgit.com/article88/openmate/releases)) and install it on
your phone — or just scan the QR code above.

### 3. Scan to Pair

When you first open the app, it enters the QR code pairing screen:

1. **Get the QR code** — the Bridge displays it in the admin web UI (`http://127.0.0.1:4097/ui/`, auto-opened on Windows and macOS). On Linux, the QR code is also printed directly in the terminal — useful for SSH sessions where opening a browser isn't convenient.
2. Scan the QR code in the app — pairing and connection are automatic
3. If you're not on the same LAN, the app automatically connects via the cloud relay gateway

#### Manual Pairing (Alternative)

If QR scanning isn't available, you can pair using a PIN code:

1. The app displays a **6-digit PIN code**
2. On the PC running the Bridge, execute:
   ```
   openmate approve <PIN>
   ```
3. The app automatically completes pairing and connects

#### Add Instance Manually

In the app, add an instance with the IP address of the PC running the Bridge, default port `4097`.

## Troubleshooting

**Bridge can't find opencode** — the Bridge looks for `opencode` in `PATH` plus the usual
install locations (`~/.opencode/bin`, `/opt/homebrew/bin`, `/usr/local/bin`, `~/.local/bin`).
This matters when the Bridge is started from a service or another non-interactive shell
(`nohup`, launchd, systemd), where `~/.zshrc` is not read. If your opencode lives somewhere
else, set an absolute path in the admin page (`opencode.binary`) or edit
`~/.openmate/bridge.db`.

**Sessions show no messages** — the Bridge reads opencode's database directly. It uses
`~/.local/share/opencode/opencode.db` by default and falls back to another database in that
directory when the configured path is empty or unusable. If it still fails, check
`opencode.db_path` in the admin page (and the warning lines in `~/.openmate/bridge.log`).

**The port is already in use** — the Bridge falls back to a random port and logs a warning
(`Port <configured> is already in use`). The admin page shows the actual port.
