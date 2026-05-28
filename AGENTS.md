# Scanner Overlay — AGENTS.md

Android-приложение для сканирования штрихкодов через камеру с автоматическим вводом в любое приложение через AccessibilityService.

## Tech stack

- Kotlin 2.0.21, Jetpack Compose + Material3 (BOM 2024.12.01), Hilt 2.52
- CameraX 1.4.1, MLKit Barcode Scanning 17.3.0, Coroutines 1.9.0
- compileSdk/targetSdk = 36, minSdk = 26, Java 17
- Gradle 8.7 (wrapper), AGP 8.5.2, KSP (не KAPT!) для Hilt
- Version catalog: `gradle/libs.versions.toml`

## Dev commands (PowerShell — `build.ps1`)

| Command | What it does |
|---|---|
| `build.ps1 install` | `./gradlew installDebug` |
| `build.ps1 apk` | `./gradlew assembleDebug` |
| `build.ps1 run` | Запускает MainActivity через adb |
| `build.ps1 uninstall` | adb uninstall com.scanner.overlay |
| `build.ps1 release` | Собирает signed APK, создаёт git-тег и GitHub Release (нужен gh CLI) |
| `build.ps1 install-release` | Собирает release APK и ставит на устройство |

`JAVA_HOME` и `ANDROID_HOME` задаются в `build.ps1` по умолчанию (`G:\AndroidStudio\jbr`, `G:\AndroidStudioSDK`). Переопределяются через env.

Альтернатива: `build-and-install.bat` — uninstall + build + install (Windows batch).

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
FloatingScanButton        — WindowManager overlay-кнопка для быстрого запуска сканера
AutoUpdateManager         — автообновление из GitHub Releases (update.json URL)
AppModule                 — SharedPreferences ("scanner_prefs")
ScannerApp                — Application + @HiltAndroidApp
```

## Package structure

| Package | Contents |
|---|---|
| `MainActivity`, `settings.*` | UI: лаунчер, настройки |
| `overlay.*` | OverlayActivity + ViewModel (камера, сканирование) |
| `scanner.*` | BarcodeAnalyzer, ScannerResult sealed interface |
| `accessibility.*` | ScannerAccessibilityService |
| `service.*` | ScannerForegroundService, FloatingScanButton |
| `update.*` | AutoUpdateManager |
| `di.*` | Hilt modules (AppModule) |

## Key types

- `ScannerResult` — sealed interface: `Success(barcode, format)`, `Error(msg)`, `Scanning`
- `OverlayState` — sealed interface: `Scanning`, `Success(barcode)`, `Error`
- `AppInfo` — `data class(packageName, appName)` для селектора приложений

## Accessibility quirks

- `ScannerAccessibilityService` injects text via `ACTION_SET_TEXT`, fallback: clipboard → long click → paste
- Ищет "Вставить"/"Paste" в контекстном меню через обход дерева `windows`
- Target package хранится в SharedPreferences как `"sew_package"` (пустая строка = любое приложение)

## OverlayActivity quirks

- Transparent theme (`Theme.ScannerOverlay.Transparent`), `excludeFromRecents`, `taskAffinity=""`, `showWhenLocked`, `turnScreenOn`
- При успешном скане: вибрация (200ms) → beep → `autoInjectText` → если не вышло, `injectText` → finish через 500ms
- Torch toggle через `CameraControl.enableTorch()`

## FloatingScanButton

- WindowManager overlay (`TYPE_APPLICATION_OVERLAY`) — плавающая кнопка поверх всех окон
- Drag-to-move с сохранением позиции в prefs (`floating_button_x/y`)
- Tap запускает OverlayActivity; double-tap ignored (500ms debounce)
- Требует SYSTEM_ALERT_WINDOW permission

## Permissions

Запрашиваются программно: CAMERA, POST_NOTIFICATIONS (TIRAMISU+).  
Ручные (через Settings): SYSTEM_ALERT_WINDOW, BIND_ACCESSIBILITY_SERVICE.  
Декларированы в манифесте: VIBRATE, FOREGROUND_SERVICE, FOREGROUND_SERVICE_SPECIAL_USE, QUERY_ALL_PACKAGES, INTERNET, REQUEST_INSTALL_PACKAGES.

## Release build

- `app/build.gradle.kts`: signing через `release.keystore` (alias `scanner`, пароль `scanner123`)
- minifyEnabled = true, proguard-rules.pro включены
- versionCode/versionName читаются из `defaultConfig`

## Non-obvious constraints

- `gradle.properties`: `android.overridePathCheck=true`, `android.suppressUnsupportedCompileSdk=36`
- KSP used for Hilt (`ksp` plugin + `ksp(libs.hilt.compiler)`) — НЕ kapt
- `ic_scan` — кастомный drawable для иконки уведомления
- `checkReleaseBuilds = false` в lint — lint отключён при сборке релиза
