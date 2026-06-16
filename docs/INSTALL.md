# OpenMate Installation Guide

## 1. Run the Bridge

The Bridge is the proxy service that connects the Android client to opencode.

### Windows

1. Double-click `openmate.exe` to run
2. After the Bridge starts, a **QR code** is displayed in the terminal or the admin page

### Linux

```bash
chmod +x openmate
./openmate
```

After the Bridge starts, a **QR code** is displayed in the terminal.

### Install as a System Service (Optional)

```bash
./openmate install     # Windows Service / Linux systemd
./openmate uninstall   # Uninstall
```

On Windows, running as a service is generally not recommended because it requires setting up a separate service account and password. Running as a regular application is sufficient — auto-start on boot can be configured in the admin page.

## 2. Install the Android APK

Transfer `OpenMate-*.apk` to your phone and install it.

## 3. Scan to Pair

When you first open the app, it enters the QR code pairing screen:

1. The Bridge displays a **QR code** (in the terminal or the admin page)
2. Scan the QR code in the app — pairing and connection are automatic
3. If you're not on the same LAN, the app automatically connects via the cloud relay gateway

### Manual Pairing (Alternative)

If QR scanning isn't available, you can pair using a PIN code:

1. The app displays a **6-digit PIN code**
2. On the PC running the Bridge, execute:
   ```
   openmate approve <PIN>
   ```
3. The app automatically completes pairing and connects

### Add Instance Manually

In the app, add an instance with the IP address of the PC running the Bridge, default port `4097`.
