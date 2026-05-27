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

## 🟡 Средние (надёжность/UX)

### 3. Retry для автообновления с экспоненциальной задержкой
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
                delay(delayMs * (2L shl attempt)) // экспоненциальная задержка
            }
        }
    }
    return Result.failure(lastError ?: Exception("Unknown error"))
}

// Использование:
suspend fun checkForUpdate(): UpdateResult = withContext(Dispatchers.IO) {
    val result = withRetry(maxRetries = 3, delayMs = 1000L) {
        // ... текущая логика HTTP-запроса
    }
    result.fold(
        onSuccess = { it },
        onFailure = { UpdateResult.Error(it.message ?: "Ошибка проверки") }
    )
}
```

---

### 4. Сохранение позиции FloatingButton при сворачивании/убийии сервиса
**Файлы:** `FloatingScanButton.kt`, `ScannerForegroundService.kt`

**Проблема:** Позиция сохраняется только на `ACTION_UP`. Если сервис убьют во время перетаскивания — позиция потеряется.

**Решение:** Добавить сохранение по таймеру и при изменении атрибутов окна:
```kotlin
// Во FloatingScanButton.init:
private val positionSaveHandler = Handler(Looper.getMainLooper())
private var savePositionRunnable: Runnable? = null

private fun schedulePositionSave() {
    savePositionRunnable?.let { positionSaveHandler.removeCallbacks(it) }
    savePositionRunnable = Runnable { savePosition() }
    positionSaveHandler.postDelayed(savePositionRunnable!!, 2000L)
}

// В onTouchListener ACTION_MOVE:
schedulePositionSave()

// Добавить callback в Service:
override fun onWindowAttributesChanged(config: WindowManager.LayoutParams) {
    floatingButton?.savePosition()
}
```

---

### 5. Звуковой сигнал при успешном сканировании (beep)
**Файлы:** `OverlayActivity.kt`, `OverlayViewModel.kt` или отдельный файл `BeepPlayer.kt`

**Проблема:** В AGENTS.md упомянут beep, но он не реализован.

**Решение:** Создать простой плеер через `SoundPool`:
```kotlin
// BeepPlayer.kt
class BeepPlayer @Inject constructor(@ApplicationContext private val context: Context) {
    private var soundPool: SoundPool? = null
    private var beepId: Int = 0

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            val builder = SoundPool.Builder()
                .setMaxStreams(1)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
            soundPool = builder.build { pool ->
                beepId = pool.load(context, R.raw.beep, 1)
            }
        }
    }

    fun playBeep() {
        soundPool?.play(beepId, 1f, 1f, 0, 0, 1f)
    }

    fun release() {
        soundPool?.release()
        soundPool = null
    }
}
```

Затем вызвать в `OverlayActivity.onBarcodeScanned()` после вибрации.

---

### 6. Обработка конфигурационных изменений (поворот экрана)
**Файлы:** `OverlayActivity.kt`, `OverlayViewModel.kt`

**Проблема:** При повороте экрана создаются дублирующиеся таймеры и обработчики.

**Решение:** Использовать `savedInstanceState` для сохранения состояния:
```kotlin
// OverlayActivity.kt
private val STATE_KEY = "overlay_state"

override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    
    // Сохраняем состояние перед recreation
    if (savedInstanceState != null) {
        savedInstanceState.getString(STATE_KEY)?.let { savedState ->
            // восстановить состояние если нужно
        }
    }
    
    window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)
    // ...
}

override fun onSaveInstanceState(out: Bundle) {
    super.onSaveInstanceState(out)
    out.putString(STATE_KEY, "active")
    finishHandler.removeCallbacks(finishRunnable)
}
```

---

## 🟢 Низкий приоритет (чистка/современные API)

### 7. Миграция с deprecated CameraX API
**Файлы:** `OverlayActivity.kt` (строка ~564)

**Проблема:** `setTargetResolution()` deprecated в CameraX 1.4+.

**Решение:** Заменить на:
```kotlin
val imageAnalysis = ImageAnalysis.Builder()
    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
    .setDefaultResolution(android.util.Size(1280, 720))
    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
    .build()
```

---

### 8. Миграция с deprecated `recycle()` на try-with-resources
**Файлы:** `ScannerAccessibilityService.kt` (множество мест)

**Проблема:** `AccessibilityNodeInfo.recycle()` deprecated, риск утечки при исключениях.

**Решение:** Создать extension-функцию:
```kotlin
private fun AccessibilityNodeInfo.safeRecycle() {
    try { recycle() } catch (_: Exception) {}
}

// Заменить все node.recycle() на node.safeRecycle()
```

---

### 9. Обработка onInterrupt() в AccessibilityService
**Файлы:** `ScannerAccessibilityService.kt`

**Проблема:** Метод пустой, сервис может терять контекст при переключении приложений.

**Решение:** Добавить логирование и сброс состояния:
```kotlin
override fun onInterrupt() {
    android.util.Log.w("ScannerAccessibility", "Service interrupted")
    mainHandler.removeCallbacksAndMessages(null)
}
```

---

## 📊 Приоритизация

| # | Название | Сложность | Влияние |
|---|----------|-----------|---------|
| 1 | Вынести ключи из gradle | Низкая | 🔴 Высокое (безопасность) |
| 2 | WeakReference для instance | Низкая | 🟡 Среднее (стабильность) |
| 3 | Retry для автообновления | Средняя | 🟡 Среднее (надёжность) |
| 4 | Сохранение позиции кнопки | Средняя | 🟡 Среднее (UX) |
| 5 | Звуковой сигнал beep | Низкая | 🟢 Низкое (UX) |
| 6 | Обработка поворота экрана | Средняя | 🟡 Среднее (стабильность) |
| 7 | Миграция CameraX API | Низкая | 🟢 Низкое (чистка) |
| 8 | safeRecycle extension | Низкая | 🟢 Низкое (чистка) |
| 9 | Обработка onInterrupt() | Низкая | 🟢 Низкое (стабильность) |
