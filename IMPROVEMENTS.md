# Планы улучшений Scanner Overlay

## 🔴 Критические (безопасность/стабильность)

### 1. Вынести ключи подписи из gradle-файла
**Файлы:** `build.gradle.kts`, `local.properties`

**Проблема:** Пароли от релизного keystore захардкожены в открытом виде.

**Решение:**
```kotlin
// build.gradle.kts
val localProps = File(rootDir, "local.properties")
val storePass = if (localProps.exists()) {
    val props = java.util.Properties()
    props.load(localProps.inputStream())
    props.getProperty("release.storePassword", "")
} else { "" }

signingConfigs {
    create("release") {
        storeFile = rootProject.file("release.keystore")
        storePassword = storePass
        keyAlias = "scanner"
        keyPassword = if (localProps.exists()) {
            val props = java.util.Properties()
            props.load(localProps.inputStream())
            props.getProperty("release.keyPassword", "")
        } else { "" }
    }
}
```

**local.properties:**
```properties
release.storePassword=scanner123
release.keyPassword=scanner123
```

---

### 2. Утечка памяти через static singleton AccessibilityService
**Файлы:** `ScannerAccessibilityService.kt`

**Проблема:** Статический `instance` — потенциальная утечка памяти при убийении сервиса системой.

**Решение:** Заменить на `WeakReference` + cleanup в `onDestroy`:
```kotlin
private val _instance = java.lang.ref.WeakReference<ScannerAccessibilityService?>(null)

companion object {
    var instance: ScannerAccessibilityService?
        get() = _instance.get()
        private set
}

override fun onDestroy() {
    super.onDestroy()
    _instance.clear()
    // ... существующий cleanup
}
```

---

### 3. Retry сбоев сканирования — scanCompleted не сбрасывается
**Файлы:** `OverlayActivity.kt`

**Проблема:** `scanCompleted` (AtomicBoolean) устанавливается в `true` при первом сканировании и **никогда не сбрасывается** (строка 723). Кнопка «Повторить» сбрасывает `injectionAttempted`, но не `scanCompleted` — повторное сканирование невозможно.

**Решение:**
```kotlin
// В onRetry:
onRetry = {
    injectionAttempted = false
    scanCompleted.set(false)  // добавить эту строку
    viewModel.resetToScanning()
}
```

---

## 🟡 Средние (надёжность/UX)

### 4. scanQuality не применяется к камере
**Файлы:** `SettingsViewModel.kt`, `OverlayActivity.kt`

**Проблема:** Настройка «Быстро/Стандарт/Максимум» сохраняется в SharedPreferences, но **нигде не читается** при создании `ImageAnalysis`. Камера всегда использует 1280×720.

**Решение:** Прочитать `scan_quality` из prefs и смаппить на разрешение:
```kotlin
val quality = prefs.getInt("scan_quality", 1)
val resolution = when (quality) {
    0 -> android.util.Size(640, 360)   // Быстро
    2 -> android.util.Size(1920, 1080) // Максимум
    else -> android.util.Size(1280, 720) // Стандарт
}

val imageAnalysis = ImageAnalysis.Builder()
    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
    .setDefaultResolution(resolution)
    .build()
```

---

### 5. Утечка MLKit-клиента и executor при рекомпозиции
**Файлы:** `OverlayActivity.kt`

**Проблема:** Каждая рекомпозиция `CameraPreview` создаёт новый `BarcodeAnalyzer` с новым MLKit-клиентом и `Executors.newSingleThreadExecutor()`. Старые не закрываются — утечка потоков и памяти.

**Решение:** Добавить cleanup в `DisposableEffect.onDispose`:
```kotlin
DisposableEffect(Unit) {
    onDispose {
        analyzerExecutor.shutdown()
        scanner.close()  // BarcodeAnalyzer должен хранить ссылку
    }
}
```

---

### 6. Retry для автообновления с экспоненциальной задержкой
**Файлы:** `AutoUpdateManager.kt`

**Проблема:** Один запрос — если сервер недоступен, пользователь видит ошибку.

**Решение:** Добавить retry-логику:
```kotlin
private suspend fun <T> withRetry(
    maxRetries: Int = 3,
    delayMs: Long = 1000L,
    block: suspend () -> T
): Result<T> {
    var lastError: Exception? = null
    repeat(maxRetries) { attempt ->
        try {
            return Result.success(block())
        } catch (e: Exception) {
            lastError = e
            if (attempt < maxRetries - 1) {
                delay(delayMs * (2L shl attempt))
            }
        }
    }
    return Result.failure(lastError ?: Exception("Unknown error"))
}
```

---

### 7. Сохранение позиции FloatingButton при сворачивании/убийии сервиса
**Файлы:** `FloatingScanButton.kt`, `ScannerForegroundService.kt`

**Проблема:** Позиция сохраняется только на `ACTION_UP`. Если сервис убьют во время перетаскивания — позиция потеряется.

**Решение:** Добавить сохранение по таймеру и при изменении атрибутов окна:
```kotlin
private val positionSaveHandler = Handler(Looper.getMainLooper())
private var savePositionRunnable: Runnable? = null

private fun schedulePositionSave() {
    savePositionRunnable?.let { positionSaveHandler.removeCallbacks(it) }
    savePositionRunnable = Runnable { savePosition() }
    positionSaveHandler.postDelayed(savePositionRunnable!!, 2000L)
}

// В onTouchListener ACTION_MOVE:
schedulePositionSave()
```

---

### 8. Звуковой сигнал при успешном сканировании (beep)
**Файлы:** `OverlayActivity.kt` или отдельный файл `BeepPlayer.kt`

**Проблема:** Вибрация есть, но звукового сигнала нет.

**Решение:** Создать простой плеер через `SoundPool` и вызвать в `onBarcodeScanned()` после вибрации.

---

### 9. Обработка конфигурационных изменений (поворот экрана)
**Файлы:** `OverlayActivity.kt`

**Проблема:** При повороте экрана создаются дублирующиеся таймеры и обработчики.

**Решение:** Использовать `savedInstanceState` для сохранения состояния или заблокировать поворот через `android:screenOrientation="portrait"` в манифесте.

---

### 10. Избыточное логирование в продакшене
**Файлы:** `BarcodeAnalyzer.kt`, `ScannerAccessibilityService.kt`

**Проблема:** `Log.d` на каждом кадре (~30 строк/сек) и в большинстве методов AccessibilityService. Засоряет logcat и тратит I/O.

**Решение:** Обернуть в `if (BuildConfig.DEBUG)` или удалить частые логи (особенно `analyze frame` в BarcodeAnalyzer).

---

### 11. Утечка AccessibilityNodeInfo при обходе дерева
**Файлы:** `ScannerAccessibilityService.kt`

**Проблема:** BFS в `findInputField`, `findSendButton`, `findNodeContaining` добавляет детей в очередь через `getChild()`, но не вызывает `recycle()` для промежуточных узлов. Только узлы в финальном `clearQueue` перерабатываются.

**Решение:** Добавить `safeRecycle()` для каждого узла после проверки и добавления детей в очередь.

---

## 🟢 Низкий приоритет (чистка/UX)

### 12. Мёртвый код — удалить неиспользуемые элементы
**Файлы:** `ScannerResult.kt`, `ScannerAccessibilityService.kt`, `strings.xml`

**Неиспользуемое:**
- `ScannerResult.Scanning` — никогда не создаётся
- `textInjectionCallback` — объявлен, но нигде не подключён
- `ACTION_INJECT_TEXT` в `onStartCommand` — путь не используется
- `BarcodeOverlayData` — вычисляется, но UI не рисует
- Строки в `strings.xml` от старого UI (`sew_package_label`, `scan_sound_label`, `check_permissions`, `service_enabled`, `service_disabled`, `status_service`, `status_sew`, `status_sew_not_found`, `start_service`, `stop_service`)

---

### 13. Захардкоженные значения → настройки
**Файлы:** `BarcodeAnalyzer.kt`, `OverlayActivity.kt`, `ScannerAccessibilityService.kt`

**Значения, которые стоит вынести:**
- Префикс `STL` для лукапа (`BarcodeAnalyzer.kt:140`)
- Задержка инъекции 600ms (`ScannerAccessibilityService.kt:91`)
- Поиск кнопки «Отправить» 2000ms (`ScannerAccessibilityService.kt:303`)
- Таймаут NotFound 7с (`OverlayViewModel.kt:69`)
- Размер превью 300dp (`OverlayActivity.kt:270`)

---

### 14. Миграция с deprecated CameraX API
**Файлы:** `OverlayActivity.kt`

**Проблема:** `setTargetResolution()` deprecated в CameraX 1.4+.

**Решение:** Уже используется `setDefaultResolution()`. Добавить `setOutputImageFormat(OUTPUT_IMAGE_FORMAT_RGBA_8888)`.

---

### 15. safeRecycle extension
**Файлы:** `ScannerAccessibilityService.kt`

**Проблема:** `AccessibilityNodeInfo.recycle()` deprecated, риск утечки при исключениях.

**Решение:** Уже есть `safeRecycle()` extension. Убедиться что он используется везде вместо голого `recycle()`.

---

### 16. Обработка onInterrupt() в AccessibilityService
**Файлы:** `ScannerAccessibilityService.kt`

**Проблема:** Метод пустой, сервис может терять контекст при переключении приложений.

**Решение:** Добавить логирование и сброс состояния:
```kotlin
override fun onInterrupt() {
    Log.w("ScannerAccessibility", "Service interrupted")
    mainHandler.removeCallbacksAndMessages(null)
}
```

---

### 17. BarcodeOverlayData — либо использовать, либо удалить
**Файлы:** `BarcodeAnalyzer.kt`, `ScannerResult.kt`

**Проблема:** `BarcodeOverlayData` вычисляется из bounding box и передаётся в `ScannerResult.Success`, но UI его не рисует.

**Решение:** Удалить как мёртвый код, или реализовать отрисовку рамки вокруг распознанного штрихкода.

---

## 📊 Приоритизация

| # | Название | Сложность | Влияние |
|---|----------|-----------|---------|
| 1 | Вынести ключи из gradle | Низкая | 🔴 Высокое (безопасность) |
| 2 | WeakReference для instance | Низкая | 🟡 Среднее (стабильность) |
| 3 | scanCompleted не сбрасывается — retry сломан | Низкая | 🔴 Высокое (баг) |
| 4 | scanQuality не применяется к камере | Низкая | 🔴 Высокое (баг) |
| 5 | Утечка MLKit-клиента/executor | Средняя | 🔴 Высокое (память) |
| 6 | Retry для автообновления | Средняя | 🟡 Среднее (надёжность) |
| 7 | Сохранение позиции кнопки | Средняя | 🟡 Среднее (UX) |
| 8 | Звуковой сигнал beep | Низкая | 🟢 Низкое (UX) |
| 9 | Обработка поворота экрана | Средняя | 🟡 Среднее (стабильность) |
| 10 | Избыточное логирование | Низкая | 🟡 Среднее (производительность) |
| 11 | Утечка NodeInfo при обходе дерева | Средняя | 🟡 Среднее (стабильность) |
| 12 | Удалить мёртвый код | Низкая | 🟢 Низкое (чистка) |
| 13 | Захардкоженные значения → настройки | Средняя | 🟢 Низкое (UX) |
| 14 | Миграция CameraX API | Низкая | 🟢 Низкое (чистка) |
| 15 | safeRecycle extension | Низкая | 🟢 Низкое (чистка) |
| 16 | Обработка onInterrupt() | Низкая | 🟢 Низкое (стабильность) |
| 17 | BarcodeOverlayData — использовать или удалить | Низкая | 🟢 Низкое (чистка) |
