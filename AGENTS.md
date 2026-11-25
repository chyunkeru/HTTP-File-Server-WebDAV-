# AGENTS.md - Developer & Agent Guide

## Stack & Architecture
- **Language & UI:** Kotlin 1.9+, Jetpack Compose, Material 3, AndroidX Lifecycle / ViewModel.
- **Persistence:** Room 2.6+ with KSP (`AppDatabase` v2, `MIGRATION_1_2` adds `isEnabled`).
- **Engine:** Custom HTTP & WebDAV Level 2 engine on raw sockets & NanoHTTPD primitives.
- **Target SDK:** 34 | Min SDK: 26.

## Directory Map
- `app/src/main/java/com/aistudio/MainActivity.kt`: Compose UI, Dashboard, SharesScreen, Windows Tools.
- `app/src/main/java/com/aistudio/db/`: Entities, DAO, Room Database & migrations.
- `app/src/main/java/com/aistudio/server/HttpServer.kt`: Web server, script generators, HTML Hub.
- `app/src/main/java/com/aistudio/server/ServerManager.kt`: Storage detection, service lifecycle.
- `app/src/main/java/com/aistudio/server/WebDavHandler.kt`: PROPFIND, MKCOL, MOVE, COPY, PUT, DELETE.

## Build & Test Commands
- Kotlin compilation: `./gradlew compileDebugKotlin --quiet`
- Assemble APK: `./gradlew assembleDebug --quiet`

## Critical Constraints & Rules
- **SAMSUNG DUAL MESSENGER:** ALWAYS install with `--user 0`:
  `adb -s <serial> install -r -d --user 0 "app/build/outputs/apk/debug/app-debug.apk"`
  Accidental clones cleanup: `adb -s <serial> shell pm uninstall --user 95 com.aistudio.webdav`
- **PROTOCOL SCOPE:** HTTP & WebDAV only (No SMB daemon).
- Keep terminal output compact (`--quiet`).
