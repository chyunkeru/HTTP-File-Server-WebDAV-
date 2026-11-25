# Changelog

All notable changes to the **HTTP & WebDAV File Server for Android** are documented in this file.

---

## [v1.3.0] - 2026-11-25

### ✨ Added
- **Automatic SD Card Detection:** Removable external storage (`/storage/XXXX-XXXX`) is automatically detected on launch and seeded alongside Internal Storage without manual path entry.
- **Granular Share Toggles:** Checkboxes on each configured drive to toggle sharing on or off individually (both Internal Storage and SD Card enabled by default).
- **Windows 1-Click Network Drive Hub:** Redesigned into 3 equal-size, single-line cards in the Web UI:
  - **⚡ Native Windows WebDAV (`Zero Install`):** 1-click batch script that unlocks the 4GB file transfer limit, fixes the 10-second WPAD proxy freeze, and maps Drive `S:`.
  - **🚀 Virtual Drive (`Local Cache`):** Offline-first virtual disk powered by WinFsp and Rclone with instant thumbnail generation, parallel chunk streaming, and automatic drive letter collision avoidance.
  - **🔌 Disconnect & Cleaner (`Reset Mounts`):** Interactive universal disconnect script to safely unmount any drive letter (`S:`, `Z:`, or `ALL`) and purge stale Windows Explorer ghost mounts.
- **Open-Source & Safe Use Disclaimer:** Added prominent usage disclaimers across the Web UI hub, script headers, app dashboard, and documentation.

### 🎨 UI & Design Improvements
- **Uniform Checkbox & Bin Alignment:** Added a disabled, greyed-out trash bin to default Internal Storage so that all drive rows share the identical layout structure (`[Icon] [Details] [Checkbox] [Trash Bin]`), achieving pixel-perfect vertical alignment.
- **Distinct Drive Icons:** Material icons for SD Card (`SdCard`), Internal Storage (`Storage`), and custom folders (`FolderOpen`).
- **Single-Line Badges & Titles:** Cleaned badge typography (`Zero Install`, `Local Cache`, `Reset Mounts`) and eliminated multi-line text wrapping on the Web Hub.

### 🐛 Fixed & Removed
- **Removed Fake SMB Port-Proxy:** Completely eliminated the SMB port-proxy setup script and related UI elements, as this server is strictly an HTTP & WebDAV server (preventing Windows `System error 67`).
- **WebDAV UAC Split-Token Fix:** Decoupled administrator registry tweaks from standard user network drive mapping so Windows File Explorer immediately recognizes mounted drives without rebooting.

---

## [v1.2.4] - 2026-11-20
- **In-Browser Code & Config Editor:** Monaco editor integration with live write-back via WebDAV PUT.
- **Instant Search Filter:** Real-time client-side search across directory listings.
- **Storage Metrics Badge:** Live partition free/used space calculation in the Web UI.

---

## [v1.2.0] - 2026-11-15
- **Dynamic QR Codes:** Quick client connection scanning from the Android dashboard.
- **Real-Time Access Logs:** In-app access log viewer with HTTP status code badges.
- **Room Database v2:** Persistent folder configuration and server settings.

---

## [v1.1.0] - 2026-11-01
- **WebDAV Level 2 Implementation:** Added support for `PROPFIND`, `MKCOL`, `MOVE`, `COPY`, `DELETE`, `PUT`, `GET`, and `OPTIONS`.
- **Foreground Service:** Persistent background service with Android Wi-Fi lock and status bar notification.
