# Design Backlog

Зафиксированные находки design-review от 2026-06-02. Не критично, отложено на потом.

Контекст: рефакторинг SettingsScreen под Material 3 уже применён (см. `docs/superpowers/specs/2026-06-02-sew-shelf-autofill-design.md` для базы). Приложение собирается, установлено на SM-S711B, пользователь доволен основным функционалом. Этот файл — заметки «что бы я ещё подкрутил визуально», ничего не правилось.

## 🔴 Бросается в глаза

### 1. Все иконки — placeholder `Icons.Default.Refresh`
Файл: `app/src/main/java/com/scanner/overlay/settings/SettingsScreen.kt`

- `StatusHero` (стр. 218) — в Hero-карточке primaryContainer крутится стрелка «обновить». Что она «обновляет»? Не считывается.
- `FloatingButtonsCard`: «Сканер» (стр. 336) и «Выбор полки» (стр. 346) — обе с `Refresh`. По иконке не отличить сканер от полки, только по цвету кружочка.
- `PermissionsCard`: Камера/Overlay/A11y (ст. 534, 542, 550) — все три с `Refresh`. Должны быть `CameraAlt` / `Layers` / `Accessibility`.
- `SewCalibrationCard` (стр. 683) — `Refresh` для калибровки.

Предлагаемая замена:
- Hero: `QrCodeScanner` или `DocumentScanner`
- Сканер: `QrCodeScanner`
- Выбор полки: `ViewList` или `Apps`
- Камера: `CameraAlt`
- Overlay: `Layers`
- A11y: `Accessibility`
- SEW-калибровка: `MyLocation` или `Tune`

Зависимости: иконки из `androidx.compose.material.icons.filled.*` уже подключены, новых не нужно. Простая замена через импорты + `Icons.Default.X`.

## 🟠 Реальные баги в UI

### 2. `PermissionRow` для камеры — пустой `onClick`
Файл: `SettingsScreen.kt:538`, функция `PermissionsCard`

Если камера НЕ выдана → показывается кнопка `Открыть` (через `FilledTonalButton` в `PermissionRow`), но `onClick = {}`. Юзер тапает — ничего не происходит. У overlay/a11y есть `onOpenOverlaySettings` / `onOpenAccessibilitySettings`, у камеры — ничего.

Нужно:
- Добавить callback `onOpenCameraSettings: () -> Unit` в `PermissionsCard` + параметром в `PermissionRow`
- Реализовать в `SettingsViewModel` через `Intent(ACTION_APPLICATION_DETAILS_SETTINGS).setData("package:$packageName")`
- Прокинуть из `SettingsScreen`

### 3. `FloatingButtonsCard` описание показывается не вовремя
Файл: `SettingsScreen.kt:358`

Текущее условие: `if (isShelfPickerAvailable || !isFloatingButtonEnabled)`. Если мастер-тумблер выключен — юзер видит длинный текст «Открывает список полок с поиском…» про полки, при том что обе кнопки скрыты.

Правильно: показывать описание только когда `isShelfPickerEnabled = true` (т.е. когда описание само актуально).

Предлагаемый фикс:
```kotlin
if (isShelfPickerEnabled) {
    Column(...) { Text("Открывает список полок с поиском...") }
}
```

### 4. `UpdateCard` «gradient banner» — не gradient
Файл: `SettingsScreen.kt:1063-1084`

В дизайн-обсуждении называли `tertiaryContainer` с градиентом. В коде — обычный `Box(background = tertiaryContainer)` без `Brush.verticalGradient`. Обманывает ожидания.

Опции:
- (a) Добавить `Brush.verticalGradient(listOf(tertiaryContainer, tertiary.copy(alpha=0.3f)))` — будет визуально выделяться
- (b) Убрать упоминание «gradient» из комментариев, оставить как есть
- (c) Сделать `OutlinedBanner` с `tertiary` border + `tertiaryContainer` fill

Проще всего (a), один импорт + один modifier.

## 🟡 Мелкие шероховатости

### 5. `StatusHero` дублирует pill-индикаторы «Камера/Overlay/A11y» с `PermissionsCard` ниже
Файл: `SettingsScreen.kt:240-258` vs `SettingsScreen.kt:533-555`

Две панели с одной и той же информацией → визуальный шум. Можно:
- (a) Оставить только в Hero, убрать `PermissionsCard` (но «Открыть» кнопки нужны для навигации к настройкам — это полезно)
- (b) Оставить только в `PermissionsCard`, из Hero убрать pill-индикаторы
- (c) Сделать `PermissionsCard` collapseable: если все 3 granted — свёрнут в строку

Рекомендую (c) — меньше места, и так понятно что разрешения выданы.

### 6. `HelpBlock` в `SewCalibrationCard` — всегда видна
Файл: `SettingsScreen.kt:712-728`

4-строчная инструкция по калибровке показывается всегда когда `!awaiting`. После калибровки уже не нужна — юзер знает.

Сделать сворачиваемой: первый запуск — развёрнута, после `isCalibrated = true` — свёрнута в строку «Как это работает» с раскрытием по тапу.

Реализация через `var helpExpanded by remember { mutableStateOf(!calibration.isCalibrated) }`.

### 7. `ShelfPickerActivity` placeholder «Поиск (например, ПИКАП)»
Файл: `ShelfPickerActivity.kt:153`

Обрезается в `OutlinedTextField`. Сделать просто `Text("Поиск")` (как в `AppPickerSheet` ст. 908). Подсказку про формат дать в subtitle заголовка sheet-а.

### 8. `ShelfRow` — нет haptic feedback на long-press
Файл: `ShelfPickerActivity.kt:262`

Юзер не чувствует, что long-press «зарегистрировался», toggle в избранное может быть незаметен. Добавить:
```kotlin
val haptic = LocalHapticFeedback.current
.combinedClickable(
    onClick = { onClick(item) },
    onLongClick = {
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        onLongClick(item)
    }
)
```

### 9. `LazyColumn` в `ShelfPicker` и `AppPicker` — фиксированный `heightIn(max = 480.dp)`
Файлы: `ShelfPickerActivity.kt:206`, `AppPickerSheet` в `SettingsScreen.kt:922`

На маленьких телефонах (Galaxy S22 mini и т.п.) — 480dp может быть больше половины экрана, и BottomSheet съедает остальное. На больших — наоборот, остаётся пустое место.

Заменить на:
```kotlin
.heightIn(max = LocalConfiguration.current.screenHeightDp.dp * 0.5f)
```

### 10. `AboutFooter` github URL
Файл: `SettingsScreen.kt:1182`

`labelSmall` (11sp) + `alpha 0.6f` — может быть нечитаемо при ярком свете. Убрать `alpha 0.6f` или поднять до `bodySmall`.

## 🟢 Что НЕ баг (подтверждено)

- Переводы на русский — везде консистентно, без англоязычных вставок.
- `MaterialTheme.colorScheme.*` используется почти везде, хардкод только для `BlueFab`/`OrangeFab`/`0xFFFFA000` (логотипы кнопок) — оправдано.
- `SectionEyebrow` — чистый, единообразный.
- Toast-ы внизу — единый стиль через `toastAtBottom` / `reusableBottomToast`.
- `FilterChip` секции в `ShelfPickerActivity` — аккуратный single-select LazyRow.
- MRU favorites с long-press — работает, дизайн чистый.

## Рекомендуемый порядок правок

1. **Иконки (issue #1)** — самый большой визуальный эффект при минимальной работе. Поменять разом все 7 мест.
2. **Пустой `onClick` в PermissionRow для камеры (#2)** — функциональный баг, а не только дизайн.
3. **Условие показа описания в FloatingButtonsCard (#3)** — однострочный фикс.
4. **Haptic feedback в ShelfRow (#8)** — UX-мелочь, но важная для новой фичи.
5. **Placeholder в ShelfPickerActivity (#7)** — однострочный фикс.
6. Остальные — по желанию, после живого тестирования.

Всё описанное выше — на потом, ничего не ломает. Можно накапливать в этот файл, а править батчами раз в N-релизов.
