# Quality Selector Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Добавить выбор качества сканирования (Быстро/Стандарт/Максимум) в настройки для оптимизации работы на слабых устройствах (Xiaomi, Huawei).

**Architecture:** Добавить новую карточку `QualityCard` в SettingsScreen с `ExposedDropdownMenuBox`, хранить выбранное качество в SharedPreferences (`scan_quality`), применять разрешение камеры при инициализации ImageAnalysis в CameraPreview.

**Tech Stack:** Kotlin 2.0.0, Jetpack Compose + Material3, Hilt 2.51.1, CameraX 1.3.4

---

## Файлы для изменения

| Файл | Действие | Описание |
|------|----------|----------|
| `app/src/main/java/com/scanner/overlay/settings/SettingsViewModel.kt` | Modify | Добавить state и метод для управления качеством сканирования |
| `app/src/main/java/com/scanner/overlay/settings/SettingsScreen.kt` | Modify | Добавить карточку QualityCard с выбором качества |
| `app/src/main/java/com/scanner/overlay/overlay/OverlayActivity.kt:585-590` | Modify | Применять выбранное разрешение из SharedPreferences при создании ImageAnalysis |

---

### Task 1: SettingsViewModel — добавить state для качества сканирования

**Files:**
- Modify: `app/src/main/java/com/scanner/overlay/settings/SettingsViewModel.kt`

- [ ] **Step 1: Добавить константу ключа и state flow**

В companion object добавить:
```kotlin
private const val PREF_KEY_SCAN_QUALITY = "scan_quality"
```

Добавить private mutable state:
```kotlin
private val _scanQuality = MutableStateFlow(prefs.getInt(PREF_KEY_SCAN_QUALITY, 1))
val scanQuality: StateFlow<Int> = _scanQuality.asStateFlow()
```

Значение по умолчанию `1` = "Стандарт" (текущее поведение без изменений).

- [ ] **Step 2: Добавить метод updateScanQuality**

```kotlin
fun updateScanQuality(quality: Int) {
    require(quality in 0..2) { "Quality must be 0, 1, or 2" }
    _scanQuality.value = quality
    prefs.edit().putInt(PREF_KEY_SCAN_QUALITY, quality).apply()
}
```

- [ ] **Step 3: Проверить компиляцию**

Запустить: `.\build.ps1 apk`
Ожидаемый результат: BUILD SUCCESSFUL

---

### Task 2: SettingsScreen — добавить QualityCard

**Files:**
- Modify: `app/src/main/java/com/scanner/overlay/settings/SettingsScreen.kt`

- [ ] **Step 1: Добавить QualityCard в SettingsScreen**

В функции `SettingsScreen`, после строки с `TimeoutCard` (строка ~82), добавить:
```kotlin
QualityCard(
    quality = scanQuality,
    onQualityChange = { viewModel.updateScanQuality(it) }
)
```

- [ ] **Step 2: Реализовать функцию QualityCard**

Добавить новый `@Composable` после `TimeoutCard`:

```kotlin
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QualityCard(
    quality: Int,
    onQualityChange: (Int) -> Unit
) {
    val options = listOf(
        0 to "Быстро",
        1 to "Стандарт",
        2 to "Максимум"
    )
    var expanded by remember { mutableStateOf(false) }
    val label = options.find { it.first == quality }?.second
        ?: "Стандарт"

    Card {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "Качество сканирования",
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Разрешение камеры",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))

            ExposedDropdownMenuBox(
                expanded = expanded,
                onExpandedChange = { expanded = !expanded }
            ) {
                OutlinedTextField(
                    value = label,
                    onValueChange = {},
                    readOnly = true,
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor(),
                    singleLine = true
                )

                ExposedDropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false }
                ) {
                    options.forEach { (q, text) ->
                        DropdownMenuItem(
                            text = { Text(text) },
                            onClick = {
                                onQualityChange(q)
                                expanded = false
                            }
                        )
                    }
                }
            }
        }
    }
}
```

- [ ] **Step 3: Проверить компиляцию**

Запустить: `.\build.ps1 apk`
Ожидаемый результат: BUILD SUCCESSFUL

---

### Task 3: OverlayActivity — применять выбранное разрешение

**Files:**
- Modify: `app/src/main/java/com/scanner/overlay/overlay/OverlayActivity.kt` (строки ~585-590)

- [ ] **Step 1: Заменить фиксированное разрешение на динамическое из SharedPreferences**

В функции `CameraPreview`, в блоке `AndroidView.factory`, заменить:
```kotlin
val imageAnalysis = ImageAnalysis.Builder()
    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
    .setTargetResolution(android.util.Size(1280, 720))
    .build()
```

На:
```kotlin
val qualityPrefs = ctx.getSharedPreferences("scanner_prefs", android.content.Context.MODE_PRIVATE)
val scanQuality = qualityPrefs.getInt("scan_quality", 1) // 0=Быстро, 1=Стандарт, 2=Максимум
val resolution = when (scanQuality) {
    0 -> androidx.camera.core.Size(640, 480)   // Быстро
    2 -> androidx.camera.core.Size(1920, 1080)  // Максимум
    else -> androidx.camera.core.Size(1280, 720) // Стандарт (по умолчанию)
}

val imageAnalysis = ImageAnalysis.Builder()
    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
    .setTargetResolution(resolution)
    .build()
```

- [ ] **Step 2: Проверить компиляцию**

Запустить: `.\build.ps1 apk`
Ожидаемый результат: BUILD SUCCESSFUL

---

### Task 4: Финальная проверка и установка на устройство

- [ ] **Step 1: Полная сборка и установка**

```powershell
.\build.ps1 install
```

Ожидаемый результат: Installed on device, BUILD SUCCESSFUL

- [ ] **Step 2: Визуальная проверка**

В приложении проверить:
1. SettingsScreen → новая карточка "Качество сканирования" с выпадающим списком
2. Три варианта: Быстро, Стандарт, Максимум
3. Изменение значения сохраняется (перезаход в настройки — значение осталось)
4. Применение разрешения работает (изменить на "Быстро", запустить сканирование — камера должна работать быстрее)

---

## Self-Review

**Spec coverage:**
- ✅ QualityCard UI — Task 2
- ✅ SharedPreferences storage — Task 1
- ✅ Camera resolution application — Task 3
- ✅ Default value (Стандарт = текущее поведение) — все задачи

**Placeholder scan:** Все шаги содержат конкретный код, нет TBD/TODO.

**Type consistency:** `scanQuality: StateFlow<Int>`, ключ `"scan_quality"`, значения 0/1/2 — согласовано во всех задачах.
