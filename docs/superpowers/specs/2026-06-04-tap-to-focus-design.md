# Tap-to-Focus (опционально) — дизайн

**Дата:** 2026-06-04
**Версия:** v1.9.0 (предложение, бамп на минор из-за новой фичи)
**Область:** `OverlayActivity.kt`, `SettingsScreen.kt`, `SettingsViewModel.kt`

## 1. Цель и мотивация

На складе штрихкод иногда мелкий или бликует. CameraX в `Preview` использует `CONTROL_AF_MODE_CONTINUOUS_PICTURE`, но непрерывный AF не справляется с мелкими/бликующими кодами — камера «теряет» резкость. Сейчас нет способа принудительно перефокусироваться на конкретной точке. Фича добавляет **тап-по-камере = фокус + замер экспозиции** в выбранной точке, с визуальной и тактильной обратной связью. Включается **в настройках**, по умолчанию **ON**.

Torch (LED-вспышка) уже реализован в `OverlayActivity.kt` (кнопка ⚡ на L517-527, `enableTorch()` в `CameraPreview` L889-917). **Его не трогаем.**

## 2. Изменяемые файлы

| Файл | Что меняется |
|---|---|
| `app/src/main/java/com/scanner/overlay/overlay/OverlayActivity.kt` | Gesture handler на камерный `Box`; новый `@Composable` `FocusIndicator`; одноразовый hint-тост в `onCreate` |
| `app/src/main/java/com/scanner/overlay/settings/SettingsViewModel.kt` | Новый `PREF_KEY_TAP_TO_FOCUS_ENABLED`; `StateFlow` + setter |
| `app/src/main/java/com/scanner/overlay/settings/SettingsScreen.kt` | Новый `TapToFocusCard` в секции «Сканирование» |

Никаких новых пакетов, Hilt-модулей, разрешений или asset-файлов. Никаких изменений в манифесте.

## 3. Архитектура и поток данных

```
SettingsScreen (TapToFocusCard)
    └─ user toggles Switch
        └─ SettingsViewModel.setTapToFocusEnabled(true/false)
            ├─ MutableStateFlow.value = ...
            └─ prefs.edit().putBoolean("tap_to_focus_enabled", ...).apply()

OverlayActivity.onCreate
    ├─ читает prefs один раз → local var tapToFocusEnabled
    └─ если !focusHintShown && tapToFocusEnabled:
        └─ reusableBottomToast("Тап по камеру для фокуса", 3s)
        └─ prefs.edit().putBoolean("focus_hint_shown", true).apply()

OverlayActivity.compose (камерный Box)
    └─ Modifier.pointerInput(Unit) { detectTapGestures { offset -> ... } }
        └─ ранние выходы: !tapToFocusEnabled || state != Scanning || cameraControl == null
        └─ previewView.meteringPointFactory.createPoint(offset.x, offset.y)
        └─ FocusMeteringAction.Builder(point, FLAG_AF or FLAG_AE)
              .setAutoCancelDuration(3, TimeUnit.SECONDS)
              .build()
        └─ camera.cameraControl.startFocusAndMetering(action)
              .addListener({ focusPoint=Offset; focusSuccess=isFocusSuccessful }, mainExecutor)
```

`OverlayActivity` не подписывается на `SettingsViewModel` — он читает prefs напрямую (одноразовое чтение в `onCreate`). Это сознательное упрощение: `OverlayActivity` — не Compose-VM, у неё нет Hilt-инжекта, и реактивная подписка на prefs не нужна (toggle меняется только пока overlay не открыт, юзер закроет-переоткроет — подхватится).

## 4. SharedPreferences

| Ключ | Тип | Default | Назначение |
|---|---|---|---|
| `tap_to_focus_enabled` | Boolean | `true` | Включён ли тап-фокус |
| `focus_hint_shown` | Boolean | `false` | Показан ли одноразовый hint-тост |

Оба ключа в файле `"scanner_prefs"` (тот же, что и остальные). Имена — snake_case, как у существующих ключей.

## 5. UI/UX

### 5.1. Gesture + индикатор фокуса (в `OverlayActivity.kt`)

- Тап обрабатывается **только в состоянии `OverlayState.Scanning`**. В Success / NotFound / MultipleMatches / Error / ManualInput — игнорируется.
- **Визуал** — жёлтое кольцо 80dp × 80dp, белый stroke 2dp, центр `(offset.x, offset.y)`. Цвет жёлтый `#FFD600` (тот же, что у torch-кнопки).
- **Анимация появления:** `scaleIn` 1.3 → 1.0 за 150ms, `fadeIn` 80ms. Реализация: два `Animatable<Float>` — один для `alpha` (0..1), второй для `scale` (1.3..1.0), оба запускаются в `LaunchedEffect(point)`.
- **Исчезновение:** `fadeOut` 600ms после `autoCancel` (3 сек) или сразу после `isFocusSuccessful`. Запускается в `LaunchedEffect(success)` с задержкой.
- **Успех:** `performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)`. Цвет остаётся жёлтым.
- **Провал:** `isFocusSuccessful == false` → кольцо кратко переходит в красный `#FF5252` (200ms), затем `fadeOut` 600ms. Без haptic — визуала достаточно.
- **Composable signature:** `FocusIndicator(point: Offset?, success: Boolean?, onFadeOut: () -> Unit)`.

### 5.2. TapToFocusCard (в `SettingsScreen.kt`)

Расположение: внутри `Column(...) { ... }` после `ScanQualityCard` (L148-151) и **до** `SectionEyebrow("Система")` (L153). Логически принадлежит секции «Сканирование».

Структура (по образцу `ScanQualityCard`, использует переиспользуемый `FloatingToggleRow` L401-451):

```kotlin
TapToFocusCard(
    enabled = tapToFocusEnabled,
    onChange = viewModel::setTapToFocusEnabled
)
```

- **Иконка:** `Icons.Default.CenterFocusStrong`, синий круг `BlueFab` (`0xFF1976D2`)
- **Title:** "Тап для фокуса"
- **Subtitle:** "Коснитесь камеры, чтобы перефокусироваться"
- **Material `Switch`**, default ON
- **Описание под карточкой** (опционально, в стиле существующих карточек): "Полезно, когда штрихкод мелкий или бликует — касание принудительно фокусирует камеру на выбранной точке."

### 5.3. Одноразовый hint

В `OverlayActivity.onCreate`:
```kotlin
val tapToFocusEnabled = prefs.getBoolean("tap_to_focus_enabled", true)
val focusHintShown = prefs.getBoolean("focus_hint_shown", false)
if (tapToFocusEnabled && !focusHintShown) {
    reusableBottomToast("Тап по камеру для фокуса").show()
    prefs.edit().putBoolean("focus_hint_shown", true).apply()
}
```

Используем существующий `reusableBottomToast()` (см. `util/Toasts.kt`). Показывается один раз после установки/обновления, не навязчив.

## 6. SettingsViewModel — точные изменения

В `companion object` (L47-59) добавить:
```kotlin
const val PREF_KEY_TAP_TO_FOCUS_ENABLED = "tap_to_focus_enabled"
```

В тело класса (рядом с другими `_xEnabled` StateFlow, L63-71):
```kotlin
private val _tapToFocusEnabled = MutableStateFlow(
    prefs.getBoolean(PREF_KEY_TAP_TO_FOCUS_ENABLED, true)
)
val tapToFocusEnabled: StateFlow<Boolean> = _tapToFocusEnabled.asStateFlow()
```

Сеттер (рядом с `setShelfPickerEnabled` L206-211, по тому же паттерну):
```kotlin
fun setTapToFocusEnabled(enabled: Boolean) {
    if (_tapToFocusEnabled.value == enabled) return
    _tapToFocusEnabled.value = enabled
    prefs.edit().putBoolean(PREF_KEY_TAP_TO_FOCUS_ENABLED, enabled).apply()
}
```

## 7. OverlayActivity — точные изменения

**Новые импорты:**
```kotlin
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.camera.core.FocusMeteringAction
import java.util.concurrent.TimeUnit
```

**Tap-обработчик** — обернуть существующий `Box` (L409-414) с камерой и контролами:
```kotlin
Box(
    modifier = Modifier
        .fillMaxSize()
        .pointerInput(state, tapToFocusEnabled) {
            detectTapGestures { offset ->
                if (!tapToFocusEnabled) return@detectTapGestures
                if (state !is OverlayViewModel.OverlayState.Scanning) return@detectTapGestures
                val control = cameraControl.value ?: return@detectTapGestures
                val factory = previewView.meteringPointFactory
                val point = factory.createPoint(offset.x, offset.y)
                val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
                    .setAutoCancelDuration(3, TimeUnit.SECONDS)
                    .build()
                focusPoint = offset
                focusSuccess = null
                val future = control.startFocusAndMetering(action)
                future.addListener({
                    val ok = runCatching { future.get().isFocusSuccessful }.getOrDefault(false)
                    focusSuccess = ok
                    if (ok) performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                    // Провал — только визуальный фидбек (красное кольцо 200ms), без haptic.
                }, ContextCompat.getMainExecutor(context))
            }
        }
)
```

**Заметка по haptic:** `HapticFeedbackConstants.CONFIRM` / `REJECT` доступны только с API 30, а `minSdk = 26`. Используем `CONTEXT_CLICK` (API 23+, безопасно на 26+) для успеха. На провал haptic не нужен — достаточно визуальной красной вспышки.

**Состояние** (в `setContent`-блоке, рядом с `torchOn` L370):
```kotlin
var tapToFocusEnabled by remember { mutableStateOf(prefs.getBoolean("tap_to_focus_enabled", true)) }
var focusPoint by remember { mutableStateOf<Offset?>(null) }
var focusSuccess by remember { mutableStateOf<Boolean?>(null) }
```

**Индикатор** — отдельный `Box`, наложенный поверх камеры (внутри того же `Box` или рядом), рисует `Canvas` с кольцом в позиции `focusPoint`:
```kotlin
FocusIndicator(point = focusPoint, success = focusSuccess, onFadeOut = {
    focusPoint = null
    focusSuccess = null
})
```

**Composable `FocusIndicator`** — `Animatable<Float>` для `alpha` и `scale`, `LaunchedEffect(point)` запускает анимации, `LaunchedEffect(success)` меняет цвет.

## 8. Поведение по умолчанию и edge cases

| Условие | Поведение |
|---|---|
| `tapToFocusEnabled = true`, state = Scanning | Тап работает, индикатор появляется |
| `tapToFocusEnabled = true`, state = Success/NotFound/MultipleMatches/Error/ManualInput | Тап игнорируется |
| `tapToFocusEnabled = false` (любое state) | Тап игнорируется, индикатор не рисуется |
| Первый запуск после установки/обновления | Hint-тост "Тап по камеру для фокуса" (если `tapToFocusEnabled` ON), затем `focus_hint_shown = true` |
| Юзер выключил toggle в настройках, потом обратно включил | `focus_hint_shown` остаётся `true`, тост повторно не показывается (уже видел) |
| `cameraControl.value == null` (камера ещё не привязалась) | Тап игнорируется, без crash |
| Surface ещё рисует старый `NotFound` индикатор (7s reset) | Тап игнорируется (state != Scanning) |
| `isFocusSuccessful == false` | Красная вспышка 200ms + `HapticFeedbackConstants.REJECT`, fadeOut 600ms |
| 3 секунды прошло без `isFocusSuccessful` | `autoCancel` срабатывает, индикатор fadeOut 600ms |

## 9. Что НЕ делаем

- Не трогаем torch-кнопку и `enableTorch()`.
- Не добавляем постоянный перекрестие / focus-area marker.
- Не меняем `CONTROL_AF_MODE_CONTINUOUS_PICTURE` — CameraX сам переключается на single AF по тапу и возвращается обратно по `autoCancel`.
- Не пишем строки в `strings.xml` (UI хардкод по конвенции проекта).
- Не делаем реактивную подписку `OverlayActivity` на prefs (одноразовое чтение в `onCreate`).
- Не добавляем unit/instrumentation тестов (их в проекте нет, проверка — сборка + ручной сценарий).
- Не выпускаем релиз сразу — после имплементации `git add -A && git commit`, затем обычный релиз-флоу через `build.ps1 release` и `adb install -r`.

## 10. План проверки

После имплементации — `.\gradlew installDebug` (или `build.ps1 install`):

1. **Свежая установка** (или `adb shell pm clear com.scanner.overlay`): открыть `OverlayActivity` → должен показаться hint-тост "Тап по камеру для фокуса" на 3 сек.
2. **Тап по камере в Scanning**: жёлтое кольцо появляется → `HapticFeedbackConstants.CONFIRM` (50ms вибра) → кольцо fadeOut 600ms. Штрихкод после тапа читается чётче.
3. **Тап во время Success / NotFound**: ничего не происходит.
4. **Зайти в Настройки → Сканирование** → видна карточка "Тап для фокуса" с включённым Switch.
5. **Выключить Switch** в настройках → вернуться в `OverlayActivity` → тап по камере: ничего.
6. **Включить обратно** → тап работает.
7. **Тап в темноте** (с включённым torch через ⚡) + в месте с бликом: фокус должен пойматься (проверка на реальном устройстве).
8. **Lint/R8** — `.\gradlew assembleRelease` (через `build.ps1 release`) — никаких новых предупреждений.

## 11. Файловая сводка для имплементации

```
app/src/main/java/com/scanner/overlay/overlay/OverlayActivity.kt    # +80-100 строк
app/src/main/java/com/scanner/overlay/settings/SettingsViewModel.kt # +15 строк
app/src/main/java/com/scanner/overlay/settings/SettingsScreen.kt    # +30-50 строк (новый TapToFocusCard)
```

Всего ≈ 130-160 строк. Один мини-цикл `git commit` → ручной smoke test → релиз.
