# Scanner Overlay — AGENTS.md

Android-приложение для сканирования штрихкодов через камеру с автоматическим вводом в любое приложение через AccessibilityService.

## Tech stack

- Kotlin 2.0.0, Jetpack Compose + Material3, Hilt 2.51.1
- CameraX 1.3.4, MLKit Barcode Scanning 17.2.0
- compileSdk/targetSdk = 36, minSdk = 26, Java 17
- Gradle 8.7, AGP 8.5.0, version catalog (`gradle/libs.versions.toml`)

## Dev commands (PowerShell — `build.ps1`)

| Command | What it does |
|---|---|
| `build.ps1 install` | `./gradlew installDebug` |
| `build.ps1 apk` | `./gradlew assembleDebug` |
| `build.ps1 run` | Запускает MainActivity через adb |
| `build.ps1 uninstall` | adb uninstall com.scanner.overlay |

`JAVA_HOME` и `ANDROID_HOME` задаются в `build.ps1` — не через system env.

## Architecture

Single module `:app`, namespace `com.scanner.overlay`.

```
MainActivity              — лаунчер, запрос permission, показывает SettingsScreen
SettingsScreen/VM         — настройки: пуск/стоп сервиса, выбор целевого пакета
OverlayActivity           — прозрачный fullscreen с камерой, сканирование + автоввод
OverlayViewModel          — состояние Scanning/Success/Error, таймаут 45s
BarcodeAnalyzer           — MLKit, 600ms startup delay, центр кадра (75% crop)
ScannerAccessibilityService — ввод текста в focused/editable поле
ScannerForegroundService  — persistent уведомление для быстрого запуска оверлея
AppModule                 — SharedPreferences ("scanner_prefs")
```

## Key classes

- `ScannerResult` — sealed interface: `Success(barcode, format)`, `Error(msg)`, `Scanning`
- `OverlayState` — sealed interface: `Scanning`, `Success(barcode)`, `Error`
- `AppInfo` — `data class(packageName, appName)` для селектора приложений

## Accessibility quirks

- `ScannerAccessibilityService` injects text via `ACTION_SET_TEXT`, fallback: clipboard → long click → paste
- Ищет "Вставить"/"Paste" в контекстном меню через обход дерева `windows`
- Target package хранится в SharedPreferences как `"sew_package"` (пустая строка = любое приложение)

## OverlayActivity quirks

- Transparent theme (`Theme.ScannerOverlay.Transparent`), `showWhenLocked`, `turnScreenOn`, `FLAG_KEEP_SCREEN_ON`
- При успешном скане: вибрация (200ms) → beep → `autoInjectText` → если не вышло, `injectText` → finish через 500ms
- Torch toggle через `CameraControl.enableTorch()`

## Permissions

Запрашиваются: CAMERA, POST_NOTIFICATIONS (TIRAMISU+).  
Ручные (через Settings): SYSTEM_ALERT_WINDOW, BIND_ACCESSIBILITY_SERVICE.  
Декларированы в манифесте: VIBRATE, FOREGROUND_SERVICE, FOREGROUND_SERVICE_SPECIAL_USE, QUERY_ALL_PACKAGES.

## Tests

No test files found in the project. No test framework configured.

## Non-obvious constraints

- `build.ps1` завязан на конкретные пути SDK (G:\AndroidStudioSDK, G:\AndoidStudio\jbr)
- `gradle.properties`: `android.overridePathCheck=true`, `android.suppressUnsupportedCompileSdk=36`
- KAPT used for Hilt (`kapt` plugin, not KSP)
- `ic_scan` — кастомный drawable для иконки уведомления
