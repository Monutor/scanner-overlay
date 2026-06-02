# Scanner Overlay — AGENTS.md

Android-приложение для сканирования штрихкодов через камеру с автоматическим вводом в любое приложение через `AccessibilityService`. Один модуль `:app`, namespace `com.scanner.overlay`, package `com.scanner.overlay`.

## Tech stack

- Kotlin 2.0.21, Jetpack Compose + Material3 (BOM 2024.12.01), Hilt 2.52
- CameraX 1.4.1, MLKit Barcode Scanning 17.3.0, Coroutines 1.9.0
- compileSdk / targetSdk = 36, minSdk = 26, Java 17
- Gradle 8.7 (wrapper), AGP 8.5.2, **KSP (не KAPT!)** для Hilt
- Version catalog: `gradle/libs.versions.toml`
- Нет unit/instrumentation тестов (нет `src/test/`, `src/androidTest/`) — проверка только сборкой и установкой

## Dev commands (PowerShell — `build.ps1`)

| Command | What it does |
|---|---|
| `build.ps1 install` | `./gradlew installDebug` |
| `build.ps1 apk` | `./gradlew assembleDebug` |
| `build.ps1 run` | `adb shell am start` MainActivity |
| `build.ps1 uninstall` | `adb uninstall com.scanner.overlay` |
| `build.ps1 release` | `assembleRelease` + git tag + `gh release create` (нужен `gh` CLI) |
| `build.ps1 install-release` | `assembleRelease` + `adb install -r` |
| (default / no arg) | `adb install -r` на свежий debug APK (если нет — bundle) |

`JAVA_HOME` / `ANDROID_HOME` по умолчанию: `G:\AndroidStudio\jbr`, `G:\AndroidStudioSDK` (переопределяются через env).

Альтернатива: `build-and-install.bat` — uninstall → `installDebug --no-daemon` (Windows batch, без PowerShell).

## Source layout (verified)

| File | Role |
|---|---|
| `MainActivity.kt` | Лаунчер, запрашивает CAMERA + POST_NOTIFICATIONS, рендерит `SettingsScreen` |
| `ScannerApp.kt` | `Application` + `@HiltAndroidApp` |
| `settings/SettingsScreen.kt` | UI настроек (Compose, русский inline) |
| `settings/SettingsViewModel.kt` | Сервис on/off, таймаут, качество, апдейт |
| `overlay/OverlayActivity.kt` | Прозрачный fullscreen + камера + анимации + ручной ввод |
| `overlay/OverlayViewModel.kt` | Состояния сканера, 7s авто-reset для NotFound |
| `scanner/BarcodeAnalyzer.kt` | MLKit, фильтрация по центру, cooldown, кэш |
| `scanner/BarcodeDatabase.kt` | Загрузка CSV, exact/prefix/fuzzy lookup |
| `scanner/BarcodeLookupResult.kt` | `WarehouseItem` + sealed `BarcodeLookupResult` |
| `scanner/ScannerResult.kt` | Sealed `ScannerResult` |
| `accessibility/ScannerAccessibilityService.kt` | Ввод текста в focused/editable поле, авто-Enter и Send, SEW auto-input |
| `service/ScannerForegroundService.kt` | Persistent-уведомление, владелец FloatingScanButton |
| `service/FloatingScanButton.kt` | WindowManager overlay-кнопка (drag-to-move, tap-to-launch) |
| `service/SewCalibrationService.kt` | 2-tap калибровка таргет-приложения через WindowManager overlay |
| `calibration/SewCalibration.kt` | Data class: `targetPackage` + 2 click points (`openModal`, `confirm`) |
| `settings/SewTestResult.kt` | DTO для пошагового теста калибровки (countdown, steps) |
| `settings/AppInfo.kt` | DTO: `packageName` + `label` для picker-а установленных приложений |
| `update/AutoUpdateManager.kt` | Скачивание APK с GitHub Releases, FileProvider install |
| `di/AppModule.kt` | Hilt: `SharedPreferences` (`"scanner_prefs"`) + `SewCalibration` singleton |

## Key types (verified — старые описания в репо были неверны)

- `ScannerResult` — `Success(barcode: String, format: Int, lookupResult: BarcodeLookupResult? = null)` / `Error(message: String)`. **Нет** состояния `Scanning` (оно в `OverlayState`).
- `OverlayState` (внутри `OverlayViewModel`) — `Scanning`, `Success(barcode, hasHint: Boolean)`, `NotFound(scannedBarcode)`, `MultipleMatches(items, scannedBarcode)`, `Error`.
- `BarcodeLookupResult` — `ExactMatch(WarehouseItem)` / `PrefixMatch(List<WarehouseItem>)` / `FuzzyMatch(WarehouseItem, distance: Int)` / `NotFound`.
- `WarehouseItem(name, barcode, section, type, number, level)`.
- `UpdateInfo`, `UpdateResult`, `UpdateUiState` — для авто-апдейта (см. ниже).

## BarcodeAnalyzer (`scanner/BarcodeAnalyzer.kt`)

- `startupDelayMs = 1500L` — первые 1.5 сек после создания кадры игнорируются (фокус камеры + MLKit warm-up)
- **Не crop**, а фильтр по расстоянию до центра: `maxCenterDistanceFraction = 0.18f` (18% диагонали кадра). Баркоды дальше от центра отбрасываются.
- Cooldown 2 сек на одинаковый код; кэш последних 50 кодов против повторов
- `CODE_39` < 12 символов отбрасывается
- Warehouse lookup вызывается **только** если `barcode.startsWith("STL")` — иначе `lookupResult = null`
- Форматы: QR, EAN-13/8, CODE-128/39/93, UPC-A/E, DataMatrix, Aztec, PDF417

## Warehouse CSV

- Файл: `app/src/main/assets/barcodes.csv` (UTF-8). Колонки: `name,barcode,section,type,number,level`.
- Загружается **синхронно** при первом `BarcodeDatabase.init(context)` в `OverlayActivity.onCreate` (двойной checked lock). Большие файлы заблокируют UI-поток — текущий файл ~194 строки, это OK.
- Lookup: exact match → prefix match (один = exact, много = список) → Levenshtein fuzzy (max distance = 2) → `NotFound`.
- В демо-данных баркоды имеют префикс `STL<digits>` (`STL000014010001` и т.п.) — без этого префикса lookup не вызывается в принципе.

## SharedPreferences `"scanner_prefs"` (verified keys)

| Key | Type | Default | Назначение |
|---|---|---|---|
| `service_running` | Boolean | — | дублирует состояние `ScannerForegroundService.isRunning` |
| `scan_timeout_ms` | Long | `45_000` | UI: 15/30/45/60/90/120 сек |
| `scan_quality` | Int | `1` | `0`=640×360, `1`=1280×720, `2`=1920×1080 (ImageAnalysis) |
| `floating_button_x` | Int | — | X позиции плавающей кнопки |
| `floating_button_y` | Int | — | Y позиции плавающей кнопки |
| `sew_target_package` | String | `""` | package name целевого приложения SEW (для авто-ввода) |
| `sew_open_modal_x/y` | Int | `0` | координаты тапа по «Ручной ввод» в target app |
| `sew_confirm_x/y` | Int | `0` | координаты тапа по «Готово» в открытой модалке target app |
| `sew_awaiting_calibration` | Boolean | `false` | true пока `SewCalibrationService` в onCreate, флаг занятой сессии |

## `ScannerAccessibilityService` (`accessibility/`)

- Синглтон через `companion._instance: WeakReference` (сетится в `onServiceConnected`).
- Попытка ввода в порядке:
  1. `ACTION_SET_TEXT` сразу (`autoInjectText`).
  2. Иначе `injectText` (postDelayed 600 мс) — даёт время на фокус/появление поля.
  3. Внутри `setText`: если `ACTION_SET_TEXT` не сработал → ставит текст в Clipboard → `ACTION_CLICK` + `ACTION_FOCUS` → 250 мс → `pasteFromContextMenu` (long click → 400 мс → ищет «Вставить»/«Paste»/«Встав» в окнах через обход `windows`) → восстанавливает прежний clipboard.
- **После успешной вставки**:
  - `pressEnter`: `ACTION_IME_ENTER` (API 33+) с fallback `ACTION_CLICK`.
  - Через 400 мс `findAndClickSendButton(2000ms timeout)` — ищет в окнах элемент с текстом/contentDescription, содержащим «Send»/«Отправить»/«Submit»/«Готово»/«Done», и кликает, только если в текущем поле действительно лежит наш баркод.
- `findFocusedOrEditable` сначала пробует `findFocus(FOCUS_INPUT)`; если найденный узел не editable — рециклит его и идёт обходом по `windows` (BFS, depth ≤ 50).
- Реактор-флаги: `safeRecycle()` (try/catch) на каждом `recycle()`, потому что Android кидает `IllegalStateException` если узел уже recycled.
- Конфиг в `res/xml/accessibility_service_config.xml`: `flagReportViewIds|flagRetrieveInteractiveWindows`, `canPerformGestures`, `canRetrieveWindowContent`.

## `OverlayActivity` (`overlay/`)

- Тема `Theme.ScannerOverlay.Transparent` (полупрозрачный фон, `windowIsTranslucent=true`, `windowCloseOnTouchOutside=true`).
- Манифест: `excludeFromRecents="true"`, `taskAffinity=""`, `showWhenLocked="true"`, `turnScreenOn="true"`, `screenOrientation="portrait"`, `exported="false"`.
- Window flags: `NOT_TOUCH_MODAL` + `WATCH_OUTSIDE_TOUCH` + `KEEP_SCREEN_ON` + `NOT_FOCUSABLE` (последний **снимается** только при открытии диалога ручного ввода и возвращается при закрытии).
- Успешный скан: вибро 200 мс (`VibrationEffect.createOneShot`) → beep (`res/raw/scan_beep.mp3`, фолбэк — system ringtone `TYPE_NOTIFICATION`) → 2-секундная пауза для показа «штрихкод найден» → `onBarcodeDetected` → через **1.5 секунды** `finishRunnable` пробует `autoInjectText`, при неудаче `injectText`, затем `finish()`.
- Manual input: кнопка `⌨` показывает диалог с полем, при submit — `autoInjectText` → fallback `injectText` через 500 мс, потом `finish()`.
- `OverlayState.NotFound` автоматически сбрасывается в `Scanning` через 7 секунд (без подтверждения).

## SEW auto-input (калибровка + авто-ввод)

Целевая фича: пользователь выбирает SEW-приложение в Settings → проходит 2-tap калибровку → при сканировании баркода `OverlayActivity.triggerSewAutoInput` сам тапает по «Ручной ввод» в target app, ждёт модалку, тапает «Готово», и затем через `ScannerAccessibilityService` вставляет штрихкод.

- `calibration/SewCalibration.kt` — Hilt-singleton, `targetPackage: String` + `openModal: Point` + `confirm: Point`. Геттер `isCalibrated: Boolean` (требует `targetPackage` непустой и обе точки ≠ (0,0)).
- `service/SewCalibrationService.kt` — foreground-сервис (`foregroundServiceType="specialUse"`, канал `sew_calibration_channel`, `NOTIFICATION_ID=1002`). Поднимает полупрозрачный (`0x33000000`) fullscreen overlay через `WindowManager` (`TYPE_APPLICATION_OVERLAY`, `NOT_FOCUSABLE | NOT_TOUCH_MODAL | WATCH_OUTSIDE_TOUCH | LAYOUT_IN_SCREEN`), ловит `ACTION_UP` и пишет координаты в prefs. Если `sew_target_package` пуст — Toast + `stopSelf()`. Шаги: 1) тап по «Ручной ввод» → сохраняет `sew_open_modal_x/y`; 2) тап по «Готово» в открытой модалке → сохраняет `sew_confirm_x/y` + `sew_calibrated=true`.
- `ScannerAccessibilityService` держит `@Volatile sewInputInProgress` + `sewResultDelivered` + `watchdogHandler` (4 сек таймаут) для SEW-флоу. `startSewAutoInput(barcode, calibration, onStep, onResult)` оркестрирует клики + ввод; коллбэки идут в `SettingsScreen.SewCalibrationCard` для теста.
- `SettingsViewModel.runSewCalibrationTest` — end-to-end прогон с countdown 3 сек; результат стримится в `SewTestResult` (steps + countdown + finished/inProgress/errorMessage).
- `settings/AppInfo.kt` — DTO `{packageName, label}` для picker-а установленных приложений в Settings.

## `ScannerForegroundService` (`service/`)

- `foregroundServiceType="specialUse"` в манифесте.
- Канал уведомления `CHANNEL_ID = "scanner_channel"`, IMPORTANCE_HIGH, `NOTIFICATION_ID = 1001`.
- Иконка уведомления: `R.drawable.ic_scan` (кастомный drawable в `res/drawable/ic_scan.xml`).
- Действия: tap → `OverlayActivity` (NO_HISTORY + EXCLUDE_FROM_RECENTS + NO_USER_ACTION); action-button → `ACTION_STOP` → `stopSelf()`.
- При старте проверяет `Settings.canDrawOverlays()`: если false, открывает overlay-настройки и сразу `stopSelf()`.
- Компаньон-флаг `isRunning: Boolean` (volatile) — UI читает его, не полагаясь на `ServiceConnection`.

## `FloatingScanButton` (`service/`)

- `WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY`, `FLAG_NOT_FOCUSABLE`, `PixelFormat.TRANSLUCENT`, gravity `TOP|START`.
- Кнопка 60dp, круглая, синяя (`#1976D2`), иконка — `R.drawable.ic_launcher_foreground` (НЕ `ic_scan`).
- Drag-детект: `scaledTouchSlop`. Позиция сохраняется в prefs через debounce 2 сек после последнего `MOVE`, и сразу на `UP` если был drag.
- Tap: 500 мс debounce (`lastTapTime`), запускает `OverlayActivity` с теми же флагами, что и из уведомления.

## Auto-update (`update/AutoUpdateManager.kt`)

- URL манифеста: `https://github.com/Monutor/scanner-overlay/releases/latest/download/update.json`.
- Схема JSON: `{versionCode: Int, versionName: String, downloadUrl: String, releaseNotes: String}`.
- Сравнивается с `BuildConfig.VERSION_CODE`. 3 ретрая с exponential backoff (1с → 2с → 4с).
- HTTP timeouts: манифест 10с/15с, APK 15с/60с.
- APK сохраняется в `context.externalCacheDir/app-update.apk`, ставится через `FileProvider` с authority `${applicationId}.fileprovider` (уже объявлен в манифесте).
- `build.ps1 release` парсит `versionName/versionCode` из `app/build.gradle.kts`, генерит `update.json` локально, создаёт git tag, пушит, затем `gh release create` с двумя ассетами (`app-release.apk` + `update.json`). После успеха `update.json` удаляется. Если `gh` не установлен — тег уже на origin, аплоадить руками.

## Permissions

Запрашиваются программно (`MainActivity.onCreate` + `requestNeededPermissions`):
- `CAMERA`
- `POST_NOTIFICATIONS` (только TIRAMISU+, т.е. SDK 33+)

Открывают системные настройки из `SettingsScreen`:
- `SYSTEM_ALERT_WINDOW` → `Settings.ACTION_MANAGE_OVERLAY_PERMISSION` (с `package:...` URI)
- `BIND_ACCESSIBILITY_SERVICE` → `Settings.ACTION_ACCESSIBILITY_SETTINGS`

Декларированы в `AndroidManifest.xml` (без рантайм-запроса): `INTERNET`, `VIBRATE`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE`, `REQUEST_INSTALL_PACKAGES`, `BIND_ACCESSIBILITY_SERVICE`. Поле `uses-feature camera required="true"`.

**`QUERY_ALL_PACKAGES` НЕ заявлен и не используется** — список установленных приложений для SEW-picker-а получается через `<queries><intent action="android.intent.action.MAIN"/></intent></queries>` (только launcher-приложения).

## Release / signing

- `release.keystore` — в корне репо, в `.gitignore`. **Не коммитить** (хотя в текущей копии присутствует локально).
- Пароли читаются из `local.properties` (тоже `.gitignore`):
  - `release.storePassword=scanner123`
  - `release.keyPassword=scanner123`
  - `sdk.dir=G:\\AndroidStudioSDK`
- Alias ключа: `scanner` (захардкожен в `app/build.gradle.kts`).
- **И `debug`, и `release` buildType используют один и тот же release-signing config** — установка debug поверх release и наоборот не ломается.
- `release`: `isMinifyEnabled = true`, `proguard-android-optimize.txt` + `proguard-rules.pro`.
- `lint { checkReleaseBuilds = false }` — lint не блокирует релиз-сборку.

## Non-obvious gotchas

- **KSP, не KAPT** для Hilt. В `app/build.gradle.kts` стоит `alias(libs.plugins.ksp)` и `ksp(libs.hilt.compiler)`. Если увидишь `kapt(...)` — это регрессия, не «стиль».
- `gradle.properties` — нестандартные флаги: `android.overridePathCheck=true`, `android.suppressUnsupportedCompileSdk=36`, `android.builtInKotlin=false`, `android.newDsl=false`, `android.r8.strictFullModeForKeepRules=false`, `android.r8.optimizedResourceShrinking=false`, `android.usesSdkInManifest.disallowed=false`. Не «прибирать» их без причины.
- `BarcodeAnalyzer` и `ScannerAccessibilityService` намеренно насыщены `BuildConfig.DEBUG`-gated `Log.d` вызовами — это рабочая диагностика, не шум. Удалять только если уверен, что фича стабильна.
- `app/src/main/res/values/strings.xml` валиден (UTF-8), но почти не используется UI — Composables держат русские строки хардкодом (например, «штрихкод найден», «Готово», «Закрыть», «Ввести вручную», «Повторить», «Не найден в базе», «Найдено N варианта», «Выберите правильный:», «Отмена», «Ручной ввод», «Отправить», «Назад», «наведите на код»). Реально используются только `app_name`, `camera_unavailable`, `channel_name/description`, `notification_title/text`, `scan_action`. Если правишь `strings.xml` — не рассчитывай, что это влияет на большинство экранов.
- `OverlayActivity` — `app/src/main/java/com/scanner/overlay/overlay/OverlayActivity.kt` держит `vibrator`, `prefs`, `finishHandler`, `pendingBarcode`, `injectionAttempted` как поля активити, не VM. Помни про поворот экрана / `onDestroy` cleanup.
- `BarcodeDatabase` использует mutableList + HashMap без синхронизации на чтение — безопасно, потому что инициализация happens-before через `init()`. Не вызывай `init()` параллельно из разных активити.
- В репо нет ни GitHub Actions, ни pre-commit хуков. Сборка и релиз — локальные через `build.ps1`.
- Документы по дизайну лежат в `docs/superpowers/{specs,plans}/` — стоит читать перед крупными правками UI/UX.
- Перед началом работы над фичей сверяйся с `docs/BUGS_AUDIT.md` (если существует) — там зафиксированы реальные краш/зависание баги с точными file:line. **По состоянию на 2026-06-02 все 30 пунктов аудита (A1–A4, B1–B10, C1–C12, D1–D6, кроме C11 как «не-баг») помечены как ✅ Исправлено — см. «Журнал исправлений» в начале файла.**

## Where to look when changing X

| Задача | Начать с |
|---|---|
| Логика сканирования / фильтры | `scanner/BarcodeAnalyzer.kt` |
| Что показывать после скана | `overlay/OverlayViewModel.kt` (`OverlayState`) + `OverlayActivity.kt` (UI-блоки по `when`) |
| Ввод текста в чужое приложение | `accessibility/ScannerAccessibilityService.kt` |
| SEW-калибровка (2-tap overlay) | `service/SewCalibrationService.kt` + `calibration/SewCalibration.kt` |
| SEW auto-input flow (тапы + вставка) | `OverlayActivity.triggerSewAutoInput` + `ScannerAccessibilityService.startSewAutoInput` + `SettingsViewModel.runSewCalibrationTest` |
| Плавающая кнопка | `service/FloatingScanButton.kt` + `ScannerForegroundService.kt` |
| Авто-апдейт | `update/AutoUpdateManager.kt` + `build.ps1 release` |
| Складская база | `app/src/main/assets/barcodes.csv` + `scanner/BarcodeDatabase.kt` |
| Настройки пользователя | `settings/SettingsScreen.kt` + `SettingsViewModel.kt` (там же `scan_timeout_ms` / `scan_quality`) |
