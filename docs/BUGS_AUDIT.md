# Аудит багов ScannerOverlay

**Дата:** 2026-06-01
**Версия:** v1.4.0 (versionCode 10)
**Объём:** 20 `.kt` файлов + манифест + ресурсы + build-конфигурация
**Метод:** систематическое чтение, верификация ресурсов, отслеживание потоков данных и ссылок

> Замечание: `AGENTS.md` устарел — говорит, что SEW-feature удалён, но это полноценная подсистема (`SewCalibrationService`, `SewCalibration`, `AppInfo`, `SewTestResult`, `SewCalibrationCard` в UI). Source layout table неполная. `strings.xml` чистый (не mojibake, как утверждает AGENTS.md), но 8/14 строк не используются.

---

## A. КРИТИЧЕСКИЕ — могут вызвать краш или зависание

### A1. `startForeground` без указания `foregroundServiceType` на Android 14+

**Файлы:**
- `app/src/main/java/com/scanner/overlay/service/ScannerForegroundService.kt:45`
- `app/src/main/java/com/scanner/overlay/service/SewCalibrationService.kt:40`

**Проблема:** в манифесте оба сервиса объявлены с `android:foregroundServiceType="specialUse"`, но в коде вызывается `startForeground(NOTIFICATION_ID, notification)` **без** `ServiceInfo.FOREGROUND_SERVICE_TYPE_*`. На Android 14+ (API 34) это может привести к `ForegroundServiceTypeException` / крашу процесса сразу при старте сервиса.

**Фикс:** использовать перегрузку `startForeground(int, Notification, int)` и передавать `ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE` на API 29+.

---

### A2. `cancelOngoingSewInput` игнорирует `message` и не вызывает `onResult`

**Файл:** `app/src/main/java/com/scanner/overlay/accessibility/ScannerAccessibilityService.kt:359-363`

```kotlin
fun cancelOngoingSewInput(message: String = "Отменено") {
    if (!sewInputInProgress) return
    watchdogHandler.removeCallbacksAndMessages(null)
    sewInputInProgress = false
}
```

**Проблема:** параметр `message` принимается, но **не используется**. `onResult(...)` не вызывается. Любой вызывающий код (например, тест-режим из `SettingsViewModel.runSewCalibrationTest`) после cancel **зависнет навсегда** в ожидании callback.

**Фикс:** в конце функции добавить:
```kotlin
sewResultDelivered = true
onResultCallback?.invoke(false, message)
```
(при условии хранения текущего `onResult` в поле).

---

### A3. `AccessibilityService.onDestroy` не уведомляет caller о прерывании

**Файл:** `app/src/main/java/com/scanner/overlay/accessibility/ScannerAccessibilityService.kt:43-54`

**Проблема:** если сервис умирает посреди `runSewAutoInput` (например, пользователь отключил спец. возможности в Settings, или система убила процесс), `onResult` **не вызывается**. Юзер не получает ни Toast, ни вибро → "ничего не происходит". `OverlayActivity` уже `finish()`-нут к этому моменту (строка 146), так что визуального фидбэка нет вообще.

**Фикс:** в `onDestroy` проверить `sewInputInProgress` и если true — вызвать сохранённый `onResult` с сообщением `"Сервис остановлен"`.

---

### A4. `CancellationException` ловится в catch-all и логируется как crash

**Файл:** `app/src/main/java/com/scanner/overlay/overlay/OverlayActivity.kt:388-398`

```kotlin
coroutineScope.launch {
    try {
        detectedBarcode = true
        onBarcodeScanned(result.barcode, result.lookupResult != null)
        delay(2000)
        viewModel.onBarcodeDetected(result)
        onScheduleFinish(result.barcode, result.lookupResult != null)
    } catch (e: Exception) {
        android.util.Log.e("ScanFlow", "launch crash", e)
    }
}
```

**Проблема:** `kotlinx.coroutines.CancellationException` — это `Exception` по иерархии JVM. Если пользователь закрывает активити во время `delay(2000)`, корутина отменяется, исключение **ловится** и логируется как обычный crash → шум в logcat, потеря реальных исключений.

**Фикс:**
```kotlin
} catch (e: kotlinx.coroutines.CancellationException) {
    throw e
} catch (e: Exception) {
    android.util.Log.e("ScanFlow", "launch crash", e)
}
```

---

## B. СЕРЬЁЗНЫЕ — функциональные / UX

### B1. `isSubmittingToSew` захвачен по значению — оверлей "Ввод в SEW…" никогда не показывается

**Файл:** `app/src/main/java/com/scanner/overlay/overlay/OverlayActivity.kt:328, 517-545`

```kotlin
fun OverlayContent(
    viewModel: OverlayViewModel,
    isSubmittingToSew: Boolean = false,  // <-- обычный Boolean, не State<Boolean>
    ...
)
```

**Проблема:** параметр `isSubmittingToSew` — обычный `Boolean`, **захваченный по значению** при первой композиции. Когда `triggerSewAutoInput` устанавливает `isSubmittingToSew = true` (строка 109), Compose **не рекомпонует** UI. Оверлей "Ввод в SEW…" со спиннером (строки 517-545) — мёртвый код, никогда не виден. Рефактор не завершён.

**Фикс (вариант 1):** заменить параметр на `Boolean` из `mutableStateOf` или `StateFlow`:
```kotlin
isSubmittingToSew: State<Boolean>,
```
и в Activity:
```kotlin
private val _isSubmittingToSew = mutableStateOf(false)
```

**Фикс (вариант 2):** убрать оверлей и `isSubmittingToSew` полностью, оставить только Toast после `onResult`.

---

### B2. `runSewAutoInput` — fire-and-forget, Activity закрывается сразу

**Файл:** `app/src/main/java/com/scanner/overlay/overlay/OverlayActivity.kt:108-147` (`triggerSewAutoInput`)

```kotlin
service.runSewAutoInput(
    barcode = barcode,
    calibration = sewCalibration,
    onResult = { ok, message -> onSewInputResult(ok, message) }
)
if (!isFinishing) finish()  // <-- закрывается ДО того, как SEW-цепочка завершится
```

**Проблема:** `runSewAutoInput` — асинхронная цепочка длительностью до 14 секунд. Сразу после её запуска Activity делает `finish()`. Когда `onResult` приходит через 1-14 секунд, `OverlayActivity` уже мёртв. `Toast.makeText(this, ...)` (строка 157) на finished activity **может не отобразиться** (Android скрывает Toast, если нет foreground window). Вибратор `vibrateResult` работает (системный сервис), но юзер может не понять, что произошло.

**Фикс:** заменить Toast на `NotificationManager.notify(...)` (через `NotificationCompat.Builder` с foreground-service-каналом), либо показывать Toast через `applicationContext` (но он работает только с foreground активити). Альтернатива — завершить `runSewAutoInput` синхронно через `CountDownLatch` перед `finish()` (но это противоречит асинхронной природе AccessibilityService).

---

### B3. `detectedBarcode` (Compose-state) никогда не сбрасывается в `false`

**Файл:** `app/src/main/java/com/scanner/overlay/overlay/OverlayActivity.kt:344, 390`

```kotlin
var detectedBarcode by remember { mutableStateOf(false) }
...
detectedBarcode = true  // <-- только set, никогда reset
```

**Проблема:** после успешного скана `detectedBarcode = true`, на UI зелёная подсветка + текст "штрихкод найден". После возврата в `Scanning` state (например, из `MultipleMatches → Отмена` → `viewModel.resetToScanning()`) Compose-state **остаётся `true`**. Текст в scanning layout (строки 455-467) продолжает показывать "штрихкод найден" зелёным, даже если нового скана не было.

**Фикс:** в `LaunchedEffect(state)` (или в начале `OverlayContent`):
```kotlin
LaunchedEffect(state) {
    if (state is OverlayViewModel.OverlayState.Scanning) {
        detectedBarcode = false
    }
}
```

---

### B4. Manual input `Handler.postDelayed` не отменяется в `onDestroy`

**Файл:** `app/src/main/java/com/scanner/overlay/overlay/OverlayActivity.kt:212-219`

```kotlin
onManualSubmit = { barcode ->
    cancelFinish()
    finish()
    injectionAttempted = true
    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({  // <-- runnable не сохраняется
        ScannerAccessibilityService.instance?.let {
            if (it.autoInjectText(barcode) != true) {
                it.injectText(barcode)
            }
        }
    }, 500)
}
```

**Проблема:** `Handler` аллоцируется здесь, runnable **не сохраняется** в поле, `onDestroy` (строки 318-321) **не может** его отменить. Если пользователь закроет активити в течение 500мс, runnable всё равно сработает. `ScannerAccessibilityService.instance` — `WeakReference`, может быть null, тогда no-op; если не null — injection произойдёт на мёртвой Activity.

**Фикс:** вынести Handler + Runnable в поля:
```kotlin
private val manualInputHandler = android.os.Handler(android.os.Looper.getMainLooper())
private var manualInputRunnable: Runnable? = null
```
В `onDestroy` добавить `manualInputRunnable?.let { manualInputHandler.removeCallbacks(it) }`.

---

### B5. `GLOBAL_ACTION_BACK` может нажать back в target app вместо закрытия клавиатуры

**Файл:** `app/src/main/java/com/scanner/overlay/accessibility/ScannerAccessibilityService.kt:633-637` (`closeKeyboardAndClickConfirm`)

```kotlin
val keyboardOpen = windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
if (keyboardOpen) {
    performGlobalAction(GLOBAL_ACTION_BACK)
    ...
}
```

**Проблема:** `GLOBAL_ACTION_BACK` отправляет back-press. На некоторых PWA (особенно Chrome WebAPK) одно нажатие back может одновременно закрыть клавиатуру **и** навигировать в target app (например, закрыть только что открытую модалку "Ручной ввод"). Это означает: калибровка прошла, мы нажали "Ручной ввод", открылась модалка, мы ввели штрих, потом `GLOBAL_ACTION_BACK` → модалка закрылась, штрих не подтверждён.

**Фикс:** использовать более точечный подход — `AccessibilityNodeInfo.ACTION_DISMISS` на input field, или вообще не закрывать клавиатуру (просто подождать её исчезновения, проверяя `windows`).

---

### B6. Clipboard restore без проверки текущего содержимого в happy-path `setText`

**Файл:** `app/src/main/java/com/scanner/overlay/accessibility/ScannerAccessibilityService.kt:236-247`

```kotlin
mainHandler.postDelayed({
    pasteFromContextMenu()
    mainHandler.postDelayed({
        ...
        pendingClipboardRestore = null
        original?.let { clipboard.setPrimaryClip(it) }  // <-- без проверки, что в clipboard всё ещё наш текст
    }, 3000)
}, 250)
```

**Проблема:** в fallback-пути `setText` (когда `ACTION_SET_TEXT` не сработал) мы пишем в clipboard наш штрих, потом пытаемся paste. Через 3 секунды восстанавливаем оригинальный clipboard **безусловно**. Если за эти 3 секунды пользователь скопировал что-то ещё (например, другой штрих), его содержимое **затирается** нашим `original`. `onDestroy` (строки 46-52) проверяет `currentClip.getItemAt(0)?.text?.toString() == lastInjectedText`, а здесь — нет.

**Фикс:**
```kotlin
val currentClip = clipboard.primaryClip
if (currentClip != null && currentClip.getItemAt(0)?.text?.toString() == text) {
    original?.let { clipboard.setPrimaryClip(it) }
}
```

---

### B7-B8. Жёсткие задержки в `findAndClickSendButton` и `setText` — плохой UX

**Файл:** `app/src/main/java/com/scanner/overlay/accessibility/ScannerAccessibilityService.kt:300-318, 216-222`

Полная цепочка: `ACTION_SET_TEXT` → 300мс → `pressEnter` → 400мс → `postDelayed(2000)` → `findAndClickSendButton`.

**Проблема:** юзер видит результат ввода в поле **за 2.7 секунды** до того, как сервис нажмёт Send. Если он сам нажмёт Send/Enter раньше — наш callback сработает на уже отправленной форме. Race с пользователем.

**Фикс:** уменьшить 2000мс до 400-600мс; в идеале — реактивно отслеживать изменение состояния UI (window content changed event), а не опрашивать по таймеру.

---

### B9. `pollForTargetWindow` — 9 секунд без watchdog

**Файл:** `app/src/main/java/com/scanner/overlay/accessibility/ScannerAccessibilityService.kt:374-434`

**Проблема:** если окно SEW не найдено сразу, опрос идёт 30 × 300мс = **9 секунд** без watchdog-таймаута. `armWatchdog` вызывается только **после** нахождения окна (строка 425). Если пользователь свернул SEW во время опроса — ждём 9 секунд, прежде чем сообщить об ошибке.

**Фикс:** вызвать `armWatchdog(onResult)` сразу при входе в `step1FindWindow` (строка 379) — он же rolling, обновится при каждом retry.

---

### B10. SewCalibrationService перехватывает touch → пользователь не может тапнуть в SEW

**Файл:** `app/src/main/java/com/scanner/overlay/service/SewCalibrationService.kt:62-69`

```kotlin
setOnTouchListener { _, ev ->
    if (ev.action == MotionEvent.ACTION_UP) {
        val x = ev.rawX.toInt()
        val y = ev.rawY.toInt()
        handleTap(x, y)
    }
    true  // <-- consume event
}
```

**Проблема:** overlay перехватывает все touch-события (consume). Текст нотификации "Шаг 1/2: нажмите на «Ручной ввод»" вводит в заблуждение — пользователь ожидает, что его тап уйдёт в SEW и откроет модалку. На самом деле тап уходит в overlay и сохраняется как координата. **UX-баг**: пользователь должен заранее открыть SEW, дойти до места, где нужна модалка, и только потом запускать калибровку. Это нигде не документировано.

**Фикс (вариант 1):** документировать flow в "Как настроить" блоке `SettingsScreen.SewCalibrationCard` (добавить "Перед калибровкой откройте SEW...").
**Фикс (вариант 2):** на первом шаге overlay не consume (`return false`), тогда тап уйдёт в SEW. Но тогда нужно отдельно сохранять координату (через rootViewLocationOnScreen + accessibility service events — более сложный рефактор).

---

## C. УМЕРЕННЫЕ — надёжность / гигиена

### C1. Поля Activity без `@Volatile` / `StateFlow` — хрупкий invariant

**Файл:** `app/src/main/java/com/scanner/overlay/overlay/OverlayActivity.kt:75, 76, 80`

```kotlin
private var pendingBarcode: String? = null
private var injectionAttempted = false
private var isSubmittingToSew: Boolean = false
```

**Проблема:** обычные `var`, не `@Volatile`, не `StateFlow`, не `mutableStateOf`. Сейчас безопасно, потому что **все** чтения/записи происходят в Main looper (гарантия happens-before). Любой будущий refactor, который перенесёт запись в background thread (например, в callback от `ImageAnalysis.Analyzer` после небольшой правки), сломает silently.

**Фикс:** заменить на `StateFlow` или `mutableStateOf`, или добавить `@Volatile`.

---

### C2. Утечки `AccessibilityNodeInfo` в 6+ местах

**Файл:** `app/src/main/java/com/scanner/overlay/accessibility/ScannerAccessibilityService.kt`

**Проблема:** `win.root` (`AccessibilityNodeInfo`) **никогда не переиспользуется** после использования. Каждый вызов следующих функций → новая утечка per SEW run:
- `findFocusedOrEditable` (line 104) — `win.root` (line 105)
- `findAndClickPaste` (line 285) — `win.root`
- `findAndClickSendButton` (line 307) — `win.root`
- `findInputFieldAcrossWindows` (line 494) — `win.root`
- `findInputByPlaceholder` (line 543) — `win.root`
- `step6ClickConfirm` (lines 653-657) — найденные `textNode` из предыдущих итераций не `safeRecycle()`
- `isButtonStillPresent` (lines 702-714) — найденные `textNode` из предыдущих итераций не `safeRecycle()`

Каждый `AccessibilityNodeInfo` занимает ~1KB. За 10-20 SEW-операций — десятки KB утечки. В долгой сессии может привести к ANR на accessibility-сервисе.

**Фикс:** добавить `.safeRecycle()` после `win.root` использования, в helper-функциях явно переиспользовать `root` после BFS.

---

### C3. `LifecycleEventObserver ON_DESTROY` cleanup неполный

**Файл:** `app/src/main/java/com/scanner/overlay/overlay/OverlayActivity.kt:878-895`

```kotlin
DisposableEffect(lifecycleOwner) {
    val observer = LifecycleEventObserver { _, event ->
        if (event == Lifecycle.Event.ON_DESTROY) {
            cameraProviderRef.value?.unbindAll()
            cameraControl.value = null
            cameraProviderRef.value = null
            // <-- analyzerExecutor НЕ shutdown()
            // <-- scannerRef.value.close() НЕ вызывается
        }
    }
    lifecycleOwner.lifecycle.addObserver(observer)
    onDispose { ... }
}
```

**Проблема:** `ON_DESTROY` делает `unbindAll()`, но **не закрывает** `analyzerExecutor` (single-thread thread pool) и `BarcodeAnalyzer.scanner` (MLKit client). Если `CameraPreview` когда-нибудь уйдёт из композиции без destroy активити (например, при условном рендере в будущем) — утечка executor-треда и MLKit native client.

**Фикс:** перенести `scannerRef.value?.close()` и `analyzerExecutor.shutdown()` в `ON_DESTROY` блок, либо полностью убрать observer и положиться на `onDispose`.

---

### C4. Неправильный порядок teardown в `CameraPreview`

**Файл:** `app/src/main/java/com/scanner/overlay/overlay/OverlayActivity.kt:888-893`

```kotlin
onDispose { 
    lifecycleOwner.lifecycle.removeObserver(observer)
    cameraProviderRef.value?.unbindAll()
    cameraControl.value = null
    scannerRef.value?.close()        // <-- close() до shutdown()
    scannerRef.value = null
    analyzerExecutor.shutdown()        // <-- shutdown() после close()
}
```

**Проблема:** `scannerRef.value?.close()` вызывается **до** `analyzerExecutor.shutdown()`. В теории, in-flight задача на executor'е может обратиться к `scanner` после `close()` (но `scanner` ссылка уже удерживается в BarcodeAnalyzer, не через `scannerRef.value`). Сейчас безопасно (single-thread executor + ссылка в BarcodeAnalyzer стабильна), но порядок нарушает принцип "сначала остановить генератор событий, потом освободить ресурсы". Корректный порядок: `shutdown()` → `close()`.

---

### C5. TOCTOU race на `sewInputInProgress`

**Файл:** `app/src/main/java/com/scanner/overlay/accessibility/ScannerAccessibilityService.kt:33-34, 334, 342`

```kotlin
@Volatile private var sewInputInProgress: Boolean = false
...
if (sewInputInProgress) {           // <-- read
    onResult(false, "Подождите завершения ввода")
    return
}
...
sewInputInProgress = true            // <-- write
```

**Проблема:** `@Volatile` гарантирует visibility, но **не атомарность** check-then-set. Два потока, вызвавших `runSewAutoInput` одновременно, могут оба пройти проверку до записи. Сейчас безопасно (всё в Main), но хрупко.

**Фикс:** обернуть в `synchronized(this) { if (sewInputInProgress) ...; sewInputInProgress = true; }`.

---

### C6. `Handler` аллоцируется на каждом кадре в camera callback

**Файл:** `app/src/main/java/com/scanner/overlay/overlay/OverlayActivity.kt:926`

```kotlin
onResult = { result ->
    android.os.Handler(android.os.Looper.getMainLooper()).post {  // <-- new Handler per frame
        ...
    }
}
```

**Проблема:** на 30 FPS это 30 аллокаций `Handler` в секунду. Минорный GC-давление, легко фиксится.

**Фикс:** вынести в поле `private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())`.

---

### C7. `MediaPlayer.prepare()` на Main thread

**Файл:** `app/src/main/java/com/scanner/overlay/overlay/OverlayActivity.kt:288`

**Проблема:** синхронный `prepare()` блокирует UI-поток на длительность декодирования `scan_beep.mp3` (17KB, но на медленных устройствах может быть 50-200мс). Видимый hitch.

**Фикс:** использовать `prepareAsync()` + `setOnPreparedListener`.

---

### C8. `onInterrupt` не очищает `watchdogHandler`

**Файл:** `app/src/main/java/com/scanner/overlay/accessibility/ScannerAccessibilityService.kt:58-61`

```kotlin
override fun onInterrupt() {
    android.util.Log.w("ScannerAccessibility", "Service interrupted")
    mainHandler.removeCallbacksAndMessages(null)  // <-- только mainHandler
}
```

**Проблема:** `watchdogHandler` callback'и не очищаются. Stale timeout может сработать после `onInterrupt` (и вызвать `releaseWatchdogAndFinish` с флагом `sewInputInProgress == false` — no-op, но шум в logcat).

**Фикс:** добавить `watchdogHandler.removeCallbacksAndMessages(null)`.

---

### C9. `dispatchGesture` возвращаемое значение отбрасывается в `tryOpenModal`

**Файл:** `app/src/main/java/com/scanner/overlay/accessibility/ScannerAccessibilityService.kt:455-465`

```kotlin
dispatchGesture(
    GestureDescription.Builder().addStroke(...).build(),
    null, null  // <-- callbacks null, return value also discarded
)
```

**Проблема:** `dispatchGesture` возвращает `Boolean` (принят ли жест системой). В `clickConfirmAtCoords` (line 684) возвращаемое значение проверяется, а здесь — нет. Если жест не принят (например, экран выключен), мы вслепую ждём `postDelayed(1000)` и пробуем снова. 3 попытки в одно и то же место без проверки.

**Фикс:** сохранять `val accepted = dispatchGesture(...)`, если `!accepted` — сразу fail-fast.

---

### C10. Мёртвый код: `currentOnShowManualInput`

**Файл:** `app/src/main/java/com/scanner/overlay/overlay/OverlayActivity.kt:867`

```kotlin
val currentOnShowManualInput = rememberUpdatedState(onShowManualInput)
```

**Проблема:** `rememberUpdatedState` создаёт `State<T>`, но переменная **нигде не читается** (поиск по файлу не находит использований). Мёртвый код.

**Фикс:** удалить строку.

---

### C11. Два `Handler(Looper.getMainLooper())` для одного лупера

**Файл:** `app/src/main/java/com/scanner/overlay/accessibility/ScannerAccessibilityService.kt:35, 63`

```kotlin
private val watchdogHandler = Handler(Looper.getMainLooper())  // <-- один
...
private val mainHandler = Handler(Looper.getMainLooper())        // <-- второй
```

**Проблема:** оба Handler'а на main looper, оба используются для разных категорий callback'ов (watchdog vs основная цепочка). Не баг, но copy-paste-артефакт. Можно объединить в один, если разделение не принципиально.

**Фикс:** оставить как есть (явное разделение watchdog vs main flow — хорошая практика), либо объединить.

---

### C12. `PREF_KEY_SEW_CALIBRATED` — мёртвое состояние

**Файлы:**
- `app/src/main/java/com/scanner/overlay/service/SewCalibrationService.kt:107` (write)
- `app/src/main/java/com/scanner/overlay/settings/SettingsViewModel.kt:47, 193` (write)
- `app/src/main/java/com/scanner/overlay/settings/SettingsViewModel.kt:208` (read для refresh)

**Проблема:** `sew_calibrated` (Boolean) пишется в prefs, но `isCalibrated` (`SewCalibration.kt:10-13`) вычисляется из координат `openModal.x > 0 && openModal.y > 0 && confirm.x > 0 && confirm.y > 0`. Булева переменная **никогда не читается** для определения статуса калибровки. Дублирующее мёртвое состояние.

**Фикс:** удалить `PREF_KEY_SEW_CALIBRATED` записи, либо (если хочется хранить явно) использовать как primary source of truth и убрать координатную проверку.

---

## D. КОСМЕТИЧЕСКИЕ / ДОКУМЕНТАЦИЯ

### D1. `AGENTS.md` устарел

**Файл:** `AGENTS.md`

**Проблема:** AGENTS.md утверждает:
- "`QUERY_ALL_PACKAGES` НЕ заявлен и не используется — таргетирования на конкретный пакет в текущем коде нет"
- "Список доступных приложений для таргетинга — сейчас отсутствует (см. `sew_package` — удалён)"

На самом деле SEW-feature **полностью реализована** и интегрирована:
- `calibration/SewCalibration.kt`
- `service/SewCalibrationService.kt` (192 строки)
- `settings/SewTestResult.kt`
- `settings/AppInfo.kt`
- `OverlayActivity` инжектит `SewCalibration`
- `ScannerAccessibilityService.runSewAutoInput` принимает `calibration: SewCalibration`
- `SettingsScreen.SewCalibrationCard` — полноценный UI

Source layout table неполная (отсутствуют 3 файла).

**Фикс:** обновить AGENTS.md: добавить 3 новых файла, описать SEW-подсистему, убрать ложное утверждение про `sew_package`.

---

### D2. 8 из 14 строк в `strings.xml` не используются

**Файл:** `app/src/main/res/values/strings.xml`

**Неиспользуемые строки:** `settings_title`, `barcode_found`, `enter_manually`, `close`, `permission_required`, `overlay_permission_hint`, `accessibility_permission_hint`, `permission_required`.

Composables жёстко кодируют русский текст (per AGENTS.md инструкции). Эти строки определены, но не подключены.

**Фикс (опционально):** удалить неиспользуемые строки, либо мигрировать Composables на `stringResource(R.string.*)`.

---

### D3. `accessibility_service_config.xml` description неинформативен

**Файл:** `app/src/main/res/xml/accessibility_service_config.xml`

```xml
android:description="@string/app_name"  <!-- "Scanner Overlay" -->
```

**Проблема:** в настройках спец. возможностей Android пользователь видит описание "Scanner Overlay" — не понятно, зачем нужен сервис. Не критично, но ухудшает trust → пользователь может не включить сервис.

**Фикс:** добавить `accessibility_description` в `strings.xml` с пояснением ("Автоматический ввод штрихкодов в SEW").

---

### D4. `proguard-rules.pro` слишком агрессивен

**Файл:** `app/proguard-rules.pro`

```pro
-keep class com.scanner.overlay.** { *; }
-dontwarn com.google.mlkit.**
-keep class com.google.mlkit.** { *; }
```

**Проблема:** `-keep class com.scanner.overlay.** { *; }` обнуляет R8-минификацию для всего нашего кода. Для проекта ~20 классов приемлемо, но R8 не может переименовать классы → APK чуть больше, чем мог бы быть.

**Фикс (опционально):** сузить keep до `@HiltAndroidApp`, `@AndroidEntryPoint`, `@HiltViewModel` классов + reflection-использующих.

---

### D5. `Build.VERSION.SDK_INT >= O` всегда true (мёртвая проверка)

**Файл:** `app/src/main/java/com/scanner/overlay/overlay/OverlayActivity.kt:311`

```kotlin
if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
    vibrator.vibrate(VibrationEffect.createOneShot(200, ...))
}
```

**Проблема:** `minSdk = 26` (Android 8.0 = `O`), условие всегда true. Проверка мёртвая. Не баг, но шум.

**Фикс:** убрать условие, всегда вызывать `createOneShot`.

---

### D6. `PREF_KEY_AWAITING` может застрять в `true` при crash процесса

**Файл:** `app/src/main/java/com/scanner/overlay/service/SewCalibrationService.kt:38, 175`

```kotlin
// onCreate
prefs?.edit()?.putBoolean(PREF_KEY_AWAITING, true)?.apply()

// onDestroy
prefs?.edit()?.putBoolean(PREF_KEY_AWAITING, false)?.apply()
```

**Проблема:** если система **убивает процесс** (не через `onDestroy`, а OOM-killer), `onDestroy` не вызывается, `PREF_KEY_AWAITING` остаётся `true`. При следующем открытии `SettingsScreen` UI показывает "идёт калибровка" (потому что VM `init` читает значение, строки 105-107), хотя никакого сервиса нет.

**Фикс:** при старте `SewCalibrationService` проверять, не запущен ли уже экземпляр; либо в `SewCalibrationService.onCreate` сбрасывать флаг в false перед стартом (если другой экземпляр уже завершился).

---

## Резюме по приоритетам

### 🔴 Фиксить в первую очередь (4 бага, ~2-3 часа работы)
- **A1** — `Service.startForeground(id, notification, type)` на API 34+
- **A2** — `cancelOngoingSewInput` — добавить вызов `onResult(false, message)`
- **A3** — `onDestroy` accessibility service — уведомить caller, если `sewInputInProgress`
- **A4** — `catch (CancellationException) rethrow`

### 🟠 Фиксить следующими (10 багов, ~4-6 часов)
- **B1** — завершить или откатить `isSubmittingToSew` рефактор
- **B2** — заменить post-finish Toast на Notification
- **B3** — сброс `detectedBarcode` при возврате в Scanning
- **B4** — отменяемый `manualInputHandler`
- **B5** — заменить `GLOBAL_ACTION_BACK` на точечное dismiss клавиатуры
- **B6** — проверка перед `clipboard.setPrimaryClip` в happy-path
- **B7-B8** — уменьшить 2.7s задержку до 400-600мс
- **B9** — вызвать `armWatchdog` в начале `step1FindWindow`
- **B10** — документировать calibration flow в `SewCalibrationCard`

### 🟡 Гигиена (12 пунктов, объединить в один PR "code quality")
- **C1-C12** — переработка `win.root` recycle, переход на `StateFlow` для UI-полей активити, рефактор `teardown` в `CameraPreview`, удаление мёртвого кода.

### 🟢 Документация (низкий приоритет)
- **D1** — обновить AGENTS.md
- **D2** — почистить `strings.xml`
- **D3** — добавить описание accessibility-сервиса
- **D4-D6** — косметика
