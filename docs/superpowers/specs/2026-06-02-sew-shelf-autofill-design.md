# SEW Shelf Autofill — Design

**Дата:** 2026-06-02
**Версия-цель:** v1.7.0
**Статус:** одобрен пользователем 2026-06-02

## Context

SEW (веб-приложение в Chrome / PWA) даёт сотруднику задание: «Отсканируйте ячейку хранения → подберите товар → разместите на другую ячейку». Сейчас весь workflow делается через физическое сканирование ШТРИХКОДОВ полок (с обеих сторон), что медленно и подвержено ошибкам фокусировки камеры.

Штрихкоды полок уже есть в `app/src/main/assets/barcodes.csv` (194 строки, поля `name,barcode,section,type,number,level`). Уникальный префикс `STL<digits>` гарантирует, что мы знаем «чья это полка» без сканирования.

Существующая инфраструктура `runSewAutoInput(barcode, calibration, onResult)` в `ScannerAccessibilityService` уже умеет сама нажимать «Ручной ввод» в SEW → вставлять штрихкод → нажимать «Готово» через overlay. Сейчас её вызывает только `OverlayActivity` после физического скана. **Мы просто дадим ей второй caller** — выбор полки из списка.

## Goal

Добавить **второй режим авто-ввода** штрихов полок: пользователь тапает отдельную плавающую кнопку → открывается список полок с поиском → выбор полки → `runSewAutoInput` подставляет её штрих в SEW.

Существующий сканер (`OverlayActivity`) остаётся нетронутым.

## Non-goals (НЕ делаем в этой итерации)

- Парсинг DOM SEW (читать, какую полку хочет SEW) — слишком хрупко.
- OCR / скриншот камеры — пользователь уже сказал «увидим на практике», итерация 1 должна быть максимально простой.
- Авто-ввод «КУДА» когда SEW даёт нечёткую инструкцию («любая полка в зоне») — fallback на ручной скан.
- Multi-shelf в одном задании (1 товар = 1 полка КУДА в этой итерации).
- Синхронизация drag двух кнопок (независимые позиции).
- Новые зависимости, изменения `OverlayActivity`, `BarcodeAnalyzer`, `runSewAutoInput` (кроме рефакторинга публичной сигнатуры — её не меняем).

## Architecture overview

```
┌─────────────────────────────────────────────────┐
│ ScannerForegroundService (был)                  │
│  └─ FloatingScanButton (был, не трогаем)        │
│  └─ ShelfPickerButton (НОВ) ◄──── collectLatest │
│       (правый от основной, 56dp, оранжевый)     │
└─────────────────────────────────────────────────┘
         │ tap
         ▼
┌─────────────────────────────────────────────────┐
│ ShelfPickerActivity (Composable host)           │
│  └─ ShelfPickerSheet (ModalBottomSheet)         │
│       ├─ OutlinedTextField (поиск)              │
│       └─ LazyColumn items { WarehouseItem }     │
│             data = BarcodeDatabase.getAllShelves│
│             (filter type ∈ {С, П, З})           │
└─────────────────────────────────────────────────┘
         │ item selected
         ▼
┌─────────────────────────────────────────────────┐
│ ScannerAccessibilityService.runSewAutoInput     │
│ (существующий, вызываем с item.barcode)         │
└─────────────────────────────────────────────────┘
```

### Что НЕ меняем (явно)
- `BarcodeAnalyzer` — не трогаем
- `OverlayActivity` / `OverlayViewModel` — не трогаем
- `runSewAutoInput` — переиспользуем как есть, передаём barcode из выбранной `WarehouseItem`
- `BarcodeDatabase.lookup(barcode)` — есть, не меняем
- `WarehouseItem`, `SewCalibration`, `SewCalibrationService` — не трогаем

### Что ДОБАВЛЯЕМ
1. `BarcodeDatabase.getAllShelves(): List<WarehouseItem>` + `searchByName(query): List<WarehouseItem>` — фильтр `type ∈ {С, П, З}` (полки, не товары).
2. `service/ShelfPickerButton.kt` — копия `FloatingScanButton` со своей prefs-логикой, оранжевым цветом, иконкой-полкой.
3. `settings/ShelfPickerActivity.kt` (или прямой Composable) — `ModalBottomSheet` со списком + поиск, по тапу запускает `runSewAutoInput`.
4. Settings card «Выбор полки» — Switch ON/OFF, disabled без `sewCalibration.isCalibrated`.
5. `ScannerForegroundService` — реактивно создаёт/удаляет `ShelfPickerButton` по `shelfPickerEnabled`.
6. SharedPreferences ключи: `shelf_picker_enabled` (Bool, false), `shelf_button_x/y` (Int, -1 = auto).
7. `res/drawable/ic_shelf.xml` — иконка «полка/стеллаж» (Material icon shelf/inventory2).
8. AndroidManifest: `<activity android:name=".settings.ShelfPickerActivity" />` или показываем BottomSheet из сервиса через спец. активти-хост (см. ниже).

### Activity vs service-hosted sheet

**Решение:** создаём `settings/ShelfPickerActivity` — пустая прозрачная активти (`Theme.ScannerOverlay.Transparent`), которая сразу показывает `ShelfPickerSheet` и закрывается после выбора/отмены. Паттерн идентичен `OverlayActivity` по флагам (`excludeFromRecents`, `NO_HISTORY` и т.д. — по аналогии).

Альтернатива (отклонена): показывать `Dialog` напрямую из `ShelfPickerButton` через `Context`. Не работает — WindowManager-overlay-кнопка не имеет активити-контекста, нужен `FLAG_ACTIVITY_NEW_TASK` + настоящая активити для `ModalBottomSheet`.

## Components

### 1. `BarcodeDatabase.getAllShelves()` / `searchByName()`

```kotlin
// scanner/BarcodeDatabase.kt — добавить после fun lookup(...)

fun getAllShelves(): List<WarehouseItem> {
    if (!loaded) return emptyList()
    return items.asSequence()
        .filter { it.type in SHELF_TYPES }
        .sortedBy { it.name.lowercase() }
        .toList()
}

fun searchByName(query: String): List<WarehouseItem> {
    if (!loaded || query.isBlank()) return getAllShelves()
    val q = query.trim().lowercase()
    return items.asSequence()
        .filter { it.type in SHELF_TYPES }
        .filter { it.name.lowercase().contains(q) }
        .sortedBy { it.name.lowercase() }
        .toList()
}

private val SHELF_TYPES = setOf("С", "П", "З")
```

`init(context)` уже вызывается из `OverlayActivity.onCreate` синхронно. Убедиться, что `ShelfPickerActivity` тоже дёрнет `BarcodeDatabase.init(applicationContext)` в `onCreate` перед показом BottomSheet (на случай если первым открытием будет она, без `OverlayActivity`).

### 2. `ShelfPickerButton`

Файл: `app/src/main/java/com/scanner/overlay/service/ShelfPickerButton.kt`.

Копия `FloatingScanButton.kt` со следующими отличими:
- Размер 56dp (не 60dp — визуально поменьше основной).
- Цвет фона `#FB8C00` (оранжевый Material 800).
- Иконка `R.drawable.ic_shelf` (вместо `ic_launcher_foreground`).
- Свой companion-объект с `PREF_X = "shelf_button_x"`, `PREF_Y = "shelf_button_y"`.
- `defaultX = mainButtonX - buttonSize - margin` (16dp зазор) — при `show()` синхронизирует с prefs основной кнопки через `prefs.getInt("floating_button_x", -1)`. Это даёт UX «вторая кнопка по умолчанию прижата к основной», но потом юзер может её утащить независимо.
- При `show()` читает свою позицию; если `-1` — fallback на auto (рядом с основной).
- При `ACTION_UP` без drag — запуск `ShelfPickerActivity` через `Intent` + `FLAG_ACTIVITY_NEW_TASK`.
- Tap-debounce 500ms, drag-debounce 2 сек, scaledTouchSlop — копия логики основной кнопки.

### 3. `ShelfPickerActivity`

Файл: `app/src/main/java/com/scanner/overlay/settings/ShelfPickerActivity.kt`.

```kotlin
@AndroidEntryPoint
class ShelfPickerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        BarcodeDatabase.init(applicationContext)
        val calibration: SewCalibration = (application as ScannerApp).sewCalibration
        // ... show Sheet
        setContent {
            MaterialTheme { /* use theme same as Settings */ }
        }
    }
}
```

Манифест:
```xml
<activity
    android:name=".settings.ShelfPickerActivity"
    android:exported="false"
    android:excludeFromRecents="true"
    android:taskAffinity=""
    android:noHistory="true"
    android:showWhenLocked="true"
    android:turnScreenOn="true"
    android:theme="@style/Theme.ScannerOverlay.Transparent" />
```

`ShelfPickerSheet` — Composable, принимает `items: List<WarehouseItem>`, `onPick: (WarehouseItem) -> Unit`, `onDismiss: () -> Unit`. Паттерн идентичен `AppPickerSheet` (см. `SettingsScreen.kt:732`).

По `onPick`:
```kotlin
fun onShelfPicked(item: WarehouseItem) {
    val service = ScannerAccessibilityService.instance
    if (service == null) {
        Toast.makeText(context, "Включите специальные возможности", Toast.LENGTH_SHORT).show()
        return
    }
    if (!calibration.isCalibrated) {
        Toast.makeText(context, "Сначала откалибруйте SEW", Toast.LENGTH_SHORT).show()
        return
    }
    Toast.makeText(context, "Штрих полки «${item.name}»...", Toast.LENGTH_SHORT).show()
    service.runSewAutoInput(
        barcode = item.barcode,
        calibration = calibration,
        onResult = { ok, message ->
            // Toast — activity может быть уже finished, не критично
            Toast.makeText(context,
                if (ok) "Готово" else "Ошибка: $message",
                Toast.LENGTH_LONG
            ).show()
        }
    )
    finish()  // закрываем sheet сразу
}
```

### 4. SettingsViewModel — новое состояние

```kotlin
private val _shelfPickerEnabled = MutableStateFlow(
    prefs.getBoolean(PREF_KEY_SHELF_PICKER_ENABLED, false)
)
val shelfPickerEnabled: StateFlow<Boolean> = _shelfPickerEnabled.asStateFlow()

fun setShelfPickerEnabled(enabled: Boolean) {
    if (enabled && !_sewCalibration.value.isCalibrated) return
    _shelfPickerEnabled.value = enabled
    prefs.edit().putBoolean(PREF_KEY_SHELF_PICKER_ENABLED, enabled).apply()
}
```

Добавить в `prefsListener` ключ `PREF_KEY_SHELF_PICKER_ENABLED` → рефреш (опционально, для случая изменения извне).

### 5. SettingsScreen — новая Card

Паттерн как `SewCalibrationCard`. Вставляем между `PermissionsCard` и `SewCalibrationCard` (логически: это часть SEW-настройки, должно быть рядом).

```kotlin
ShelfPickerCard(
    enabled = shelfPickerEnabled,
    calibrated = sewCalibration.isCalibrated,
    onToggle = { viewModel.setShelfPickerEnabled(it) }
)
```

Содержимое Card:
- Title «Выбор полки».
- Description «Вторая кнопка рядом со сканером. Открывает список полок — выбор автоматически вводит штрих в SEW. Работает в полу-автоматическом режиме: подходит, когда SEW даёт конкретную ячейку в задании».
- Switch, disabled без калибровки. Text рядом: «Включено» / «Выключено».
- Hint «Сначала откалибруйте SEW» когда `!calibrated`.

### 6. `ScannerForegroundService` — реактивность

В `onCreate` запускаем coroutine в `lifecycleScope`:
```kotlin
lifecycleScope.launch {
    repeatOnLifecycle(Lifecycle.State.STARTED) {
        // читаем shelfPickerEnabled из SharedPreferences напрямую каждый раз
        // (не через VM — service не имеет VM)
        prefs.getBoolean("shelf_picker_enabled", false)
        // Но это статичное значение; нужен flow
    }
}
```

Лучше: передать в сервис `MutableStateFlow<Boolean>` через инжекшн, или конвертировать prefs в flow.

**Решение:** `prefs.observe()` — в проекте нет обёртки, добавим минимальную прямо в `ScannerForegroundService`:

```kotlin
private val shelfPickerEnabledFlow: Flow<Boolean> = callbackFlow {
    val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "shelf_picker_enabled") {
            trySend(prefs.getBoolean("shelf_picker_enabled", false))
        }
    }
    prefs.registerOnSharedPreferenceChangeListener(listener)
    trySend(prefs.getBoolean("shelf_picker_enabled", false))
    awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
}.distinctUntilChanged()
```

В `onCreate`:
```kotlin
lifecycleScope.launch {
    lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
        shelfPickerEnabledFlow.collectLatest { enabled ->
            if (enabled) shelfPickerButton.show()
            else shelfPickerButton.hide()
        }
    }
}
```

Нужно `lifecycle-runtime-ktx` для `repeatOnLifecycle` — уже подключено.

### 7. SharedPreferences ключи (документируем)

| Key | Type | Default | Назначение |
|---|---|---|---|
| `shelf_picker_enabled` | Boolean | `false` | Включена ли вторая кнопка |
| `shelf_button_x` | Int | `-1` | X позиции (если -1 — auto, справа от основной) |
| `shelf_button_y` | Int | `-1` | Y позиции (если -1 — равен Y основной) |

## Data flow (happy path)

1. Пользователь открывает SEW в Chrome, переходит на задание (там видна полка-источник «ПИКАП-01»).
2. Нажимает оранжевую плавающую кнопку → открывается `ShelfPickerActivity` → `ShelfPickerSheet`.
3. Печатает «Пикап» в поиске, видит отфильтрованный список (`ПИКАП-01`, `ПИКАП-02`, `ПИКАП-03`).
4. Тапает «ПИКАП-01» → `runSewAutoInput(barcode="STL000028420012", ...)`:
   - Клик по «Ручной ввод» в SEW.
   - Поиск поля «Штрих-код».
   - `setText` на barcode.
   - Клик «Готово».
   - Коллбэк `onResult(true)`.
5. Toast «Готово», activity закрывается, пользователь берёт товар.
6. Повторяет для полки-приёмника (если SEW дал конкретную).

## Edge cases

| Сценарий | Поведение |
|---|---|
| SEW не откалиброван | Switch в Settings disabled; кнопка `ShelfPickerButton` всё равно может быть запущена → Toast «Сначала откалибруйте SEW» |
| Accessibility-сервис не запущен | Toast «Включите специальные возможности» |
| CSV не загружен | `getAllShelves()` возвращает `emptyList()`; BottomSheet показывает «Список пуст» |
| Список отфильтрован до 0 | BottomSheet показывает «Ничего не найдено» |
| Двойной тап по кнопке за < 500ms | Второй тап игнорируется (debounce) |
| Юзер тащит ShelfPickerButton на FloatingScanButton | scaledTouchSlop + клампинг X не даёт пересечься (каждая кнопка clamp'ит свои координаты независимо) |
| SEW не на переднем плане | `runSewAutoInput` пытается поднять target через `getLaunchIntentForPackage` (уже есть логика в `OverlayActivity.triggerSewAutoInput`); если не получится — `onResult(false, «...»)` |
| Пользователь выбрал неправильную полку | Нажал «Отмена» в BottomSheet → ничего не происходит. Авто-ввода нет → fallback на ручной скан |
| Кнопка ShelfPickerButton перекрывает системный back / navigation | Используем `FLAG_NOT_FOCUSABLE`, как у основной кнопки. Не должна перехватывать. |
| `ShelfPickerActivity` запущена, но `runSewAutoInput` долго работает (>10s) | Activity уже `finish()`нута; callback покажет Toast; новые тапы по кнопке работают (если снова открыть sheet — новый вызов) |
| Служба выключена, пользователь тапнул кнопку | Кнопка скрыта (сервис не активен) — невозможно. Если кнопка каким-то образом осталась — `startActivity` на не-foreground активти упадёт. Митигация: hide() в onDestroy. |

## Testing

В проекте **нет unit/instrumentation тестов** (см. `AGENTS.md` и `BUGS_AUDIT.md`). Проверка — ручная по сценарию.

### Сценарий ручной проверки (после установки APK)

1. **Установка и сервис** — `build.ps1 install`, открыть Settings, включить «Плавающая кнопка» (если выключена).
2. **Калибровка SEW** — обязательна до включения новой кнопки. Settings → Калибровка SEW → выбрать Chrome/Яндекс → «Откалибровать» → пройти 2-tap wizard.
3. **Включение второй кнопки** — Settings → «Выбор полки» → Switch ON. На экране должна появиться оранжевая кнопка слева от синей.
4. **Drag-тест** — перетащить оранжевую кнопку вниз. Синяя остаётся на месте. Перезапустить сервис — обе кнопки на новых местах.
5. **Открытие списка** — тап по оранжевой кнопке → ModalBottomSheet открывается, виден список 194 полок.
6. **Поиск** — ввод «пик» → список сокращается до `ПИКАП-01/02/03`. Очистить → снова 194.
7. **Happy path** — в Chrome открыть SEW на странице задания (с видимой кнопкой «Ручной ввод»). Вернуться на launcher. Тап оранжевой → выбрать «ПИКАП-01» → toast «Штрих полки „ПИКАП-01“…» → через 2-3 сек toast «Готово» → в SEW должна появиться запись с barcode `STL000028420012`.
8. **Edge: отмена** — открыть sheet → нажать «Отмена» / back → ничего не происходит.
9. **Edge: пустой фильтр** — ввести «абракадабра» → «Ничего не найдено».
10. **Edge: не откалибровано** — Settings → «Сбросить калибровку» → тапнуть оранжевую кнопку → sheet открывается (мы не блокируем), выбор → Toast «Сначала откалибруйте SEW».

### Регрессия (existing)
- Основная синяя кнопка работает как раньше (скан, drag, ввод).
- `OverlayActivity` не падает, сканирует, авто-вводит через SEW.
- Settings открывается, обновляется.

## Rollback

Все изменения — additive: новые файлы, новые prefs-ключи, новая Card в Settings. Чтобы откатить:

1. Удалить `ShelfPickerButton.kt`, `ShelfPickerActivity.kt`, `ShelfPickerSheet.kt` (если отдельный файл).
2. Убрать из `ScannerForegroundService` блок `repeatOnLifecycle { shelfPickerEnabledFlow.collectLatest ... }`.
3. Убрать из `SettingsViewModel` `_shelfPickerEnabled` + `setShelfPickerEnabled`.
4. Убрать из `SettingsScreen` карточку `ShelfPickerCard`.
5. Убрать из `AndroidManifest` объявление `ShelfPickerActivity`.
6. Удалить `res/drawable/ic_shelf.xml`.

`BarcodeDatabase.getAllShelves` / `searchByName` — можно оставить (никем не используются, не повредят).

Никакие миграции данных не нужны.

## Files

### Новые (3)
- `app/src/main/java/com/scanner/overlay/service/ShelfPickerButton.kt`
- `app/src/main/java/com/scanner/overlay/settings/ShelfPickerActivity.kt`
- `app/src/main/res/drawable/ic_shelf.xml`

### Изменённые (5)
- `app/src/main/java/com/scanner/overlay/scanner/BarcodeDatabase.kt` (+2 метода, +1 константа)
- `app/src/main/java/com/scanner/overlay/service/ScannerForegroundService.kt` (+prefs-flow, +repeatOnLifecycle, +shelfPickerButton)
- `app/src/main/java/com/scanner/overlay/settings/SettingsViewModel.kt` (+1 StateFlow, +1 метод, +2 const)
- `app/src/main/java/com/scanner/overlay/settings/SettingsScreen.kt` (+ShelfPickerCard Composable, +1 импорт)
- `app/src/main/AndroidManifest.xml` (+1 activity)

### Размер
~480 строк нового кода, 0 новых зависимостей, 0 изменений публичных API кроме additive.

## Risks

| # | Риск | Митигация |
|---|---|---|
| R1 | Конфликт двух кнопок при drag (одна под другой) | `scaledTouchSlop` уже в коде основной кнопки; clamp'инг по X — каждая кнопка clamp'ит себя; визуально две кнопки всегда разделены |
| R2 | Гонка: `ShelfPickerActivity` запущена, prefs изменились, `ShelfPickerButton` пытается перезапуститься | `collectLatest` + idempotent `show()`/`hide()` (`if (isAdded) return`) |
| R3 | Двойное открытие BottomSheet при двойном тапе | Tap-debounce 500ms (как в `FloatingScanButton`); плюс `ShelfPickerActivity` имеет `noHistory=true` + `singleTop` (через intent flags) |
| R4 | `runSewAutoInput` уже запущен (race с другим caller'ом) | Внутри `runSewAutoInput` уже есть `@Volatile sewInputInProgress` + `synchronized` блок (см. `ScannerAccessibilityService.kt:354`) — второй вызов сразу fail-fast |
| R5 | `shelf_picker_enabled=true`, но SEW не откалиброван | Switch в Settings disabled без калибровки; даже если кто-то форсит prefs — BottomSheet не блокируем, но `onPick` покажет Toast «Сначала откалибруйте» |
| R6 | Drag ShelfPickerButton за пределы экрана | clamp'инг по X/Y (как у основной) |
| R7 | При выключенном сервисе кнопка осталась | `ScannerForegroundService.onDestroy` вызывает `floatingButton.hide()` — добавить `shelfPickerButton.hide()` |
| R8 | CSV не загружен в момент открытия `ShelfPickerActivity` | `init(applicationContext)` в onCreate activity перед показом |
