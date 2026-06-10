# Scanner Overlay — AGENTS.md

Android-приложение для сканирования штрихкодов через камеру с автоматическим вводом в любое приложение через `AccessibilityService`. Один модуль `:app`, namespace `com.scanner.overlay`, package `com.scanner.overlay`.

## Tech stack

- Kotlin 2.0.21, Jetpack Compose + Material3 (BOM 2024.12.01), Hilt 2.52
- CameraX 1.4.1, MLKit Barcode Scanning 17.3.0, Coroutines 1.9.0
- compileSdk / targetSdk = 36, minSdk = 26, Java 17
- Gradle 8.7 (wrapper), AGP 8.5.2, **KSP (не KAPT!)** для Hilt
- Version catalog: `gradle/libs.versions.toml`
- Нет unit/instrumentation тестов — проверка только сборкой и установкой

## Dev commands

| Команда | Что делает |
|---|---|---|
| `.\gradlew installDebug` | Сборка + установка debug |
| `.\gradlew assembleDebug` | Только сборка debug APK |
| `build.ps1 install` | То же, что `installDebug` |
| `build.ps1 apk` | `assembleDebug` (apk без установки) |
| `build.ps1 run` | Launch MainActivity через adb |
| `build.ps1 install-release` | `assembleRelease` + `adb install -r` |
| `build.ps1 release` | Полный релиз (assembleRelease + git tag + gh release) |
| `adb uninstall com.scanner.overlay` | Удаление |

`JAVA_HOME` / `ANDROID_HOME` по умолчанию: `G:\AndroidStudio\jbr`, `G:\AndroidStudioSDK`. `build.ps1` сам их выставляет. Если вызываешь `gradlew` напрямую — выстави явно.

## Source layout

| Файл | Роль |
|---|---|
| `MainActivity.kt` | Лаунчер, запрашивает CAMERA + POST_NOTIFICATIONS, рендерит `SettingsScreen` |
| `ScannerApp.kt` | `@HiltAndroidApp` Application |
| `settings/SettingsScreen.kt` | UI настроек (Compose, строки русские inline) |
| `settings/SettingsViewModel.kt` | Сервис on/off, таймаут, качество, SEW-тест, авто-апдейт |
| `settings/ShelfPickerActivity.kt` | Выбор полки из базы + поиск + авто-ввод в SEW |
| `overlay/OverlayActivity.kt` | Прозрачный fullscreen + камера + анимации + ручной ввод |
| `overlay/OverlayViewModel.kt` | Состояния сканера (`OverlayState`) |
| `scanner/BarcodeAnalyzer.kt` | MLKit-анализ: фильтр по центру, cooldown, dedup-кэш |
| `scanner/BarcodeDatabase.kt` | Загрузка CSV из assets, поиск полок по названию |
| `scanner/WarehouseItem.kt` | `WarehouseItem` — данные полки склада |
| `scanner/ScannerResult.kt` | Sealed: `Success(barcode, format)` / `Error` |
| `accessibility/ScannerAccessibilityService.kt` | Ввод текста в поля, SEW auto-input (6-шаговый pipeline) |
| `service/ScannerForegroundService.kt` | Persistent notification + владелец FloatingScanButton |
| `service/FloatingScanButton.kt` | WindowManager overlay-кнопка (drag, tap → OverlayActivity) |
| `service/ShelfPickerButton.kt` | Оранжевая кнопка выбора полки (drag, tap → ShelfPickerActivity) |
| `service/ArticleLookupButton.kt` | Зелёная кнопка поиска по артикулу М.Видео (drag, tap → ArticleLookupActivity) |
| `service/SewCalibrationService.kt` | 2-tap калибровка через overlay (шаги внизу на экране) |
| `calibration/SewCalibration.kt` | Data class: `targetPackage` + 2 click points |
| `calibration/SupportedBrowsers.kt` | Детекция Yandex/Chrome/Brave/Edge для SEW |
| `settings/SewTestResult.kt` | DTO для пошагового теста калибровки |
| `settings/AppInfo.kt` | DTO: `packageName` + `label` для picker-а приложений |
| `settings/FavoritesStore.kt` | Избранные полки (max 5, pipe-separated в prefs) |
| `settings/ArticleLookupActivity.kt` | Прозрачный fullscreen с поиском артикула + WebView (mvideo.ru) |
| `util/Toasts.kt` | Bottom-aligned toast helpers |
| `update/AutoUpdateManager.kt` | Скачивание APK с GitHub Releases, FileProvider install |
| `di/AppModule.kt` | Hilt: только `SharedPreferences` (`"scanner_prefs"`) |

## Key types

- `ScannerResult` — `Success(barcode, format)` / `Error`. **Нет** `Scanning` (оно в `OverlayState`).
- `OverlayState` — `Scanning`, `Success(barcode)`, `Error`.
- `WarehouseItem(name, barcode, section, type, number, level)`.

## SharedPreferences `"scanner_prefs"`

| Key | Type | Назначение |
|---|---|---|
| `service_running` | Boolean | Дублирует `ScannerForegroundService.isRunning` |
| `scan_timeout_ms` | Long (default `45_000`) | 15/30/45/60/90/120 сек |
| `scan_quality` | Int (default `1`) | `0`=640×360, `1`=1280×720, `2`=1920×1080 |
| `floating_button_x/y` | Int | Позиция плавающей кнопки |
| `shelf_button_x/y` | Int | Позиция кнопки выбора полки |
| `article_button_x/y` | Int | Позиция зелёной кнопки поиска по артикулу |
| `last_article_query` | String | Последний введённый артикул (для восстановления в инпуте) |
| `sew_target_package` | String | package name SEW-приложения |
| `shelf_picker_enabled` | Boolean | Видимость кнопки выбора полки |
| `article_lookup_enabled` | Boolean | Видимость зелёной кнопки поиска по артикулу |
| `favorite_shelves_order` | String | Избранные полки (pipe-separated barcodes) |
| `sew_open_modal_x/y` | Int | Координаты «Ручной ввод» |
| `sew_confirm_x/y` | Int | Координаты «Готово» |
| `sew_awaiting_calibration` | Boolean | Флаг занятой калибровочной сессии |

## SewCalibration — НЕ singleton, не Hilt-провайдер

`SewCalibration` читается **свежим из SharedPreferences** при каждом использовании. Никакого `@Singleton`-провайдера в `AppModule` нет. `OverlayActivity`, `ShelfPickerActivity`, `SettingsViewModel` строят `SewCalibration` на месте через `buildSewCalibration()` / `readSewCalibration()`.

## ScannerAccessibilityService

- Синглтон через `companion._instance: WeakReference`.
- Ввод текста: `ACTION_SET_TEXT` → fallback Clipboard + Paste через контекстное меню.
- После вставки: `pressEnter` (`ACTION_IME_ENTER` → `ACTION_CLICK`) + `findAndClickSendButton` (ищет Send/Отправить/Submit/Готово/Done).
- `safeRecycle()` с try/catch на каждом `recycle()`.
- Gated `Log.d` по `BuildConfig.DEBUG` — рабочая диагностика, не шум.

### SEW auto-input pipeline (6 шагов)

`runSewAutoInput(barcode, calibration, testMode, onResult, onStep)` — асинхронный pipeline через `Handler` postDelayed:

1. `step1FindWindow` — найти окно target, poll до 30×300ms
2. `tryOpenModal` — dispatchGesture по `openModal` coords, до 3 retry, ждать 1s появления поля
3. `step3FindInput` — найти editable поле
4. `step4SetText` — `ACTION_SET_TEXT`. В testMode → сразу `step6ClickConfirm`
5. `step5Verify` — проверить что текст вставлен → `closeKeyboardAndClickConfirm`
6. `step6ClickConfirm` — найти «Готово» via BFS, вычислить bounds, dispatchGesture. **В testMode тоже диспатчит тап** (только без верификации)

Watchdog: 6 секунд. Если не перевзведён → `releaseWatchdogAndFinish(false, "Таймаут")`.

## OverlayActivity

- Window flags: `NOT_TOUCH_MODAL | WATCH_OUTSIDE_TOUCH | KEEP_SCREEN_ON | NOT_FOCUSABLE`. `NOT_FOCUSABLE` снимается только при открытии диалога ручного ввода.
- После успешного скана: вибро 200ms → beep (res/raw/scan_beep.mp3 или ringtone) → 2s пауза → `finishRunnable` → `triggerSewAutoInput` (если калиброван) → fallback `autoInjectText` → `injectText` → `finish()`.
- `triggerSewAutoInput(barcode)`: детектит активный браузер, вызывает `service.runSewAutoInput`. Результат → Notification (успех/ошибка).

## SewCalibrationService

Foreground-сервис, поднимает semi-transparent overlay + тёмную панель статуса внизу. Перед вызовом — countdown 5 секунд. Пользователь должен **заранее открыть модалку ручного ввода и убрать клавиатуру**, т.к. оверлей блокирует экран.

После калибровки: координаты сохраняются в prefs. Оверлей-кнопка + тост дублируют шаги.

## FloatingScanButton

WindowManager overlay: 60dp, круглая, синяя (`#1976D2`), `ic_launcher_foreground`. Drag-to-move с сохранением в prefs. Tap c debounce 500ms → OverlayActivity.

## Auto-update

Манифест: `github.com/Monutor/scanner-overlay/releases/latest/download/update.json`. 3 retry exponential backoff. APK → `externalCacheDir/app-update.apk` → FileProvider install.

## Permissions

- Runtime: `CAMERA`, `POST_NOTIFICATIONS` (SDK 33+)
- Системные настройки: `SYSTEM_ALERT_WINDOW` → `ACTION_MANAGE_OVERLAY_PERMISSION`, `BIND_ACCESSIBILITY_SERVICE` → `ACTION_ACCESSIBILITY_SETTINGS`
- В манифесте (без рантайма): `INTERNET`, `VIBRATE`, `FOREGROUND_SERVICE*`, `REQUEST_INSTALL_PACKAGES`, `BIND_ACCESSIBILITY_SERVICE`
- **Нет** `QUERY_ALL_PACKAGES` — только launcher-приложения через `<queries><intent action="MAIN"/></queries>`
- `uses-feature camera required="true"`

## Release workflow

При команде «выпусти релиз» делаю без вопросов:

1. `versionCode++` / `versionName` в `app/build.gradle.kts`
2. `git add -A && git commit -m "v<version>"`
3. `git tag v<version> && git push && git push --tags`
4. `assembleRelease` (с env vars JAVA_HOME / ANDROID_HOME)
5. Генерация `update.json` в корне (versionCode, versionName, downloadUrl)
6. `gh release create v<version> app/build/outputs/apk/release/app-release.apk update.json`
7. Удаление `update.json` локально
8. `adb install -r app/build/outputs/apk/release/app-release.apk`

## Non-obvious gotchas

- **KSP, не KAPT** для Hilt. Если увидишь `kapt(...)` — регрессия.
- `gradle.properties` — нестандартные флаги, не «прибирать».
- `strings.xml` почти не используется — UI строки в Compose хардкодом (русские).
- `BarcodeDatabase` инициализируется синхронно без блокировок чтения — только из `ShelfPickerActivity.onCreate`.
- Нет CI/CD, сборка только локально.
- `OverlayActivity` держит `vibrator`, `prefs`, `finishHandler` как поля активити — помни про `onDestroy`.
- И debug и release используют один release-signing config — установка поверх не ломается.
- `build.ps1` сам выставляет `JAVA_HOME` / `ANDROID_HOME`. Если вызываешь `gradlew` напрямую — выстави явно.
- `BarcodeAnalyzer` использует dedup-кэш (50 записей) и cooldown (2s) для всех штрихкодов.
- Дизайн-доки: `docs/superpowers/{specs,plans}/`. `docs/DESIGN_BACKLOG.md` — design review notes (10 issues, исправлены в v1.9.1).
- `strings.xml` app_name — **"SEW-Помощник"**, не "Scanner Overlay". TopAppBar использует `stringResource(R.string.app_name)`.
- Версия: `versionCode` / `versionName` в `app/build.gradle.kts:19-20`.

## Where to look

| Задача | Файлы |
|---|---|
| Логика сканирования / фильтры | `BarcodeAnalyzer.kt` |
| Состояния сканера + авто-ввод | `OverlayViewModel.kt` + `OverlayActivity.kt` |
| Ввод текста в чужое приложение | `ScannerAccessibilityService.kt` |
| SEW-калибровка (2-tap) | `SewCalibrationService.kt` + `SewCalibration.kt` |
| SEW auto-input (тапы + вставка) | `OverlayActivity.triggerSewAutoInput` + `ScannerAccessibilityService.runSewAutoInput` + `SettingsViewModel.runSewCalibrationTest` |
| Floating scan button | `FloatingScanButton.kt` + `ScannerForegroundService.kt` |
| Авто-апдейт | `AutoUpdateManager.kt` |
| Складская база | `assets/barcodes.csv` + `BarcodeDatabase.kt` |
| Настройки / SEW-тест | `SettingsScreen.kt` + `SettingsViewModel.kt` |
| Выбор полки + поиск | `ShelfPickerActivity.kt` |
| Избранные полки (favorites) | `FavoritesStore.kt` |
