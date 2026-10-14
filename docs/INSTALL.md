# OpenMate Installation Guide

## Quick install

### Bridge — command line

**Linux / macOS**

```bash
curl -fsSL https://gateway.clawmate.net/install.sh | sh
```

**Windows (PowerShell)**

```powershell
irm https://gateway.clawmate.net/install.ps1 | iex
```

The installer detects your OS and CPU, reads the current version from `version.json`,
prefers the download mirror for your region (GitHub / AtomGit) and falls back to the other
one when a download fails. Both scripts live in this repository — read them first if you
prefer:

```bash
curl -fsSL https://raw.githubusercontent.com/bob-dawson/openmate/main/scripts/install.sh | sh
```

Useful options:

```bash
# autostart at login / boot (systemd on Linux, launchd on macOS)
curl -fsSL https://gateway.clawmate.net/install.sh | sh -s -- --service

# pin a version, install elsewhere, or force a mirror order
curl -fsSL https://gateway.clawmate.net/install.sh | sh -s -- --version v0.3.9 --dir ~/bin --region cn
```

> `scripts/install.sh` and `scripts/install.ps1` are the sources; the gateway URLs above
> are just copies for a short one-liner.

### Android app

Scan with your phone — the release page opens, pick the newest APK:

| China (AtomGit) | Global (GitHub) |
|:---:|:---:|
| <img src="assets/qr-app-atomgit.png" width="180" alt="AtomGit releases"> | <img src="assets/qr-app-github.png" width="180" alt="GitHub releases"> |

Direct links: [AtomGit](https://atomgit.com/article88/openmate/releases) ·
[GitHub](https://github.com/bob-dawson/openmate/releases/latest)

## Manual install

### 1. Run the Bridge

The Bridge is the proxy service that connects the Android client to opencode.

#### Windows

1. Double-click `openmate.exe` to run
2. After the Bridge starts, a **QR code** is displayed in the terminal or the admin page

#### Linux / macOS

```bash
chmod +x openmate          # macOS: openmate-darwin-arm64
./openmate
```

After the Bridge starts, a **QR code** is displayed in the terminal.

#### Install as a System Service (Optional)

```bash
./openmate install     # Windows Service / Linux systemd
./openmate uninstall   # Uninstall
```

On Windows, running as a service is generally not recommended because it requires setting up
a separate service account and password. Running as a regular application is sufficient —
auto-start on boot can be configured in the admin page. On macOS there is no `install`
subcommand; use the launchd agent created by `install.sh --service`.

### 2. Install the Android APK

Download `OpenMate-*.apk` from [Releases](https://github.com/bob-dawson/openmate/releases/latest)
(or the [AtomGit mirror](https://atomgit.com/article88/openmate/releases)) and install it on
your phone — or just scan one of the QR codes above.

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
