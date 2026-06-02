# Barcode resilience — design

**Date:** 2026-06-02
**Status:** Approved (in-session)
**Scope:** Уменьшить количество пересканирований для складских штрихкодов формата `STL`+12 цифр при плохом качестве печати (стёртая краска, блики ламината, обрезанные / лишние символы).

## Problem

Текущий `BarcodeAnalyzer` стреляет `onResult(Success)` по первому же прочтению. На реальных складских полках:
- стёртая краска → 1-2 цифры читаются как другие (substitution);
- ламинированный штрих → блик ломает 1-2 сегмента (то же);
- недопечатанный край → последние 1-2 цифры теряются (deletion);
- лишний символ (мусор, клякса) → вставка 1-2 символов (insertion).

Результат: юзер сканирует, видит «Не найден в базе» / «Ввести вручную», пересканирует. Цель — уменьшить это.

## Solution overview

Два дополняющих механизма:

1. **Multi-frame confirmation** — для `STL`-префиксных кодов не стреляем сразу, а ждём, пока тот же канонический код повторится 2 раза в окне 300 мс. Если за 700 мс подтверждения нет — стреляем top-1 (самый частый) из окна.
2. **Extended fuzzy match** — `MAX_FUZZY_DISTANCE` 2 → 3. Покрывает случаи, когда код вообще не приводится к каноническому виду (3 разных цифры), но в базе есть очень похожий.

`BarcodeShape.bestCanonical()` — нормализация: best-effort приведение любого прочтения к каноническому `STL`+12 цифр (uppercase, strip garbage, chop 16→15, prepend STL если 12+ цифр без префикса).

Multi-frame применяется **только** к складским. Не-складские (EAN-13, QR, CODE-39) стреляют сразу — нулевая регрессия для не-складских сценариев.

## Data flow

```
Кадр N → MLKit → center filter → CODE_39 length
                                       ↓
                                BarcodeShape.bestCanonical(value) → canonical?
                                       ↓
                ┌──────────────────────┴──────────────────────┐
                ↓ null (non-warehouse)                        ↓ non-null (warehouse)
        существующее поведение:                          FrameWindow.add(canonical, now)
        cooldown + dedup + fire сразу                     (prune старше 300мс)
                                                            ↓
                                                  ≥2 одинаковых canonical в окне?
                                                            ↓ ДА
                                                  onResult(Success(canonical, format, lookup))
                                                  reset window + firstScanAt=0
                                                            ↓ НЕТ
                                                  firstScanAt == 0? schedule fireFallback через 700мс

        fireFallback (на том же executor, через 700мс):
          - если firstScanAt == 0 → return
          - best = window.bestCanonical()
          - если best != null && best != lastFiredCode → fire Success(best, format, lookup)
          - reset window + firstScanAt=0
```

## Components

### `scanner/BarcodeShape.kt` (новый)

```kotlin
object BarcodeShape {
    const val PREFIX = "STL"
    const val TOTAL_LENGTH = 15
    private val CANONICAL_REGEX = Regex("^STL\\d{12}$")

    fun isCanonical(code: String): Boolean = CANONICAL_REGEX.matches(code)

    fun bestCanonical(code: String): String? {
        if (code.isBlank()) return null
        if (isCanonical(code)) return code

        val upper = code.uppercase().filter { it.isLetterOrDigit() }
        if (upper.isEmpty()) return null

        val stlIdx = upper.indexOf(PREFIX)
        if (stlIdx >= 0) {
            val digitsAfterStl = upper.drop(stlIdx + 3).takeWhile { it.isDigit() }
            if (digitsAfterStl.length >= 12) {
                return PREFIX + digitsAfterStl.take(12)
            }
            return null  // <12 digits после STL — нельзя угадать
        }

        val digitsOnly = upper.filter { it.isDigit() }
        if (digitsOnly.length >= 12) {
            return PREFIX + digitsOnly.take(12)
        }
        return null
    }
}
```

**Test cases (в уме):**
- `STL000000010003` → себя
- `stl000000010003` → uppercase
- `STL0000000100035` (16) → `STL000000010003` (chop 16→15)
- `XSTL000000010003` → `STL000000010003` (strip leading garbage)
- `STL-00000001-0003` → `STL000000010003` (strip dashes)
- `STL00000001000` (14) → `null` (1 цифра потеряна, нельзя угадать)
- `000000010003` → `STL000000010003` (prepend)
- `STL00000001A003` → `null` (A в цифровой части)
- `4006381333931` (EAN-13) → `null` (нет STL и 12 цифр без STL — ну ок, ровно 13, даст 12 → `STL400638133393` — это плохо!)

Стоп. EAN-13 имеет 13 цифр. `digitsOnly.length = 13 >= 12` → вернёт `STL`+первые 12 цифр. Это **ложный canonical**. Нужно фильтровать.

Дополнительное правило: если в исходной строке нет ни одной буквы (только цифры) и длина ≠ 15 — не делаем prepend. EAN-13 (13 цифр), UPC-A (12 цифр), EAN-8 (8 цифр) — все пройдут как «чисто цифровые» без STL-префикса.

Уточнение `bestCanonical`:
```kotlin
fun bestCanonical(code: String): String? {
    if (code.isBlank()) return null
    if (isCanonical(code)) return code

    val upper = code.uppercase().filter { it.isLetterOrDigit() }
    if (upper.isEmpty()) return null

    val stlIdx = upper.indexOf(PREFIX)
    if (stlIdx >= 0) {
        val digitsAfterStl = upper.drop(stlIdx + 3).takeWhile { it.isDigit() }
        if (digitsAfterStl.length >= 12) {
            return PREFIX + digitsAfterStl.take(12)
        }
        return null
    }

    // STL не нашли. Если строка содержит буквы — это не наш формат (EAN-13, UPC, etc.).
    if (upper.any { it.isLetter() }) return null

    val digitsOnly = upper
    if (digitsOnly.length >= 12) {
        return PREFIX + digitsOnly.take(12)
    }
    return null
}
```

**Перепроверка:**
- `4006381333931` (EAN-13, 13 цифр, без букв) — содержит только цифры, 13 >= 12 → вернёт `STL400638133393`. Это **всё ещё ложный canonical**.

Чёрт. Нужно третье ограничение. EAN-13 имеет 13 цифр. UPC-A — 12. Оба близки к нашим 12 цифрам, но у них **нет** STL-префикса. У нас **обязательно** STL в каноническом виде. Логично: без STL-префикса в исходнике — не пытаемся prepend.

**Финальный вариант:**
```kotlin
fun bestCanonical(code: String): String? {
    if (code.isBlank()) return null
    if (isCanonical(code)) return code

    val upper = code.uppercase().filter { it.isLetterOrDigit() }
    if (upper.isEmpty()) return null

    val stlIdx = upper.indexOf(PREFIX)
    if (stlIdx < 0) return null  // без STL-префикса — не наш формат

    val digitsAfterStl = upper.drop(stlIdx + 3).takeWhile { it.isDigit() }
    if (digitsAfterStl.length >= 12) {
        return PREFIX + digitsAfterStl.take(12)
    }
    return null
}
```

**Финальная перепроверка:**
- `STL000000010003` → ✅
- `stl000000010003` → ✅ (uppercase)
- `STL0000000100035` (16) → chop → ✅
- `XSTL000000010003` → strip leading → ✅
- `STL-00000001-0003` → strip dashes → ✅
- `STL00000001000` (14) → 11 цифр после STL → null ✅
- `000000010003` → нет STL → null ✅ (раньше prepend, теперь правильный null — но это регрессия для случая «пришла только цифровая часть»)

Хм. На складе если юзер сканирует код, где верхняя часть (где STL) стёрта, и остались только 12 цифр — это вероятно. С `bestCanonical` теперь вернёт `null` → переход в «non-warehouse» path → fire Success сразу с этими 12 цифрами → `BarcodeDatabase.lookup` (который проверяет `startsWith("STL")` в `BarcodeAnalyzer`) → NotFound.

А если бы `bestCanonical` prepend-ил STL, то `lookup` нашёл бы ExactMatch.

Компромисс: либо prepend и риск ложного canonical для EAN-13, либо не prepend и риск пропустить стёртый STL.

**Решение: prepend только если строка содержит букву** (присутствие букв = не EAN/UPC).

```kotlin
fun bestCanonical(code: String): String? {
    if (code.isBlank()) return null
    if (isCanonical(code)) return code

    val upper = code.uppercase().filter { it.isLetterOrDigit() }
    if (upper.isEmpty()) return null

    val stlIdx = upper.indexOf(PREFIX)
    if (stlIdx >= 0) {
        val digitsAfterStl = upper.drop(stlIdx + 3).takeWhile { it.isDigit() }
        if (digitsAfterStl.length >= 12) {
            return PREFIX + digitsAfterStl.take(12)
        }
        return null
    }

    // STL не нашли в строке. Если строка содержит БУКВЫ — возможно, это наш формат с потерей префикса.
    // (EAN-13, UPC-A — только цифры, не содержат букв.)
    if (upper.none { it.isLetter() }) return null

    val digitsOnly = upper.filter { it.isDigit() }
    if (digitsOnly.length >= 12) {
        return PREFIX + digitsOnly.take(12)
    }
    return null
}
```

**Финальная перепроверка:**
- `STL000000010003` → ✅ canonical
- `stl000000010003` → ✅ canonical
- `STL0000000100035` (16) → chop → ✅
- `XSTL000000010003` → strip leading → ✅
- `STL-00000001-0003` → strip dashes → ✅
- `STL00000001000` (14) → 11 цифр после STL → null ✅
- `000000010003` → нет STL, нет букв → null ✅ (EAN-13, UPC, etc.)
- `STL00000001A003` → A в цифровой части → null ✅
- `4006381333931` (EAN-13) → нет STL, нет букв → null ✅
- `STL000000010003X` (garbage at end, 16 chars) → chop after STL finds 12 digits → ✅
- `STL0A00000010003` (A внутри) → STL найден, 0A → takeWhile отбрасывает A → "0" (1 цифра) → null ✅ правильно

Хорошо, дизайн стабилен.

### `scanner/FrameWindow.kt` (новый)

```kotlin
class FrameWindow(private val windowMs: Long) {
    private data class Entry(val code: String, val timestamp: Long)
    private val buffer = ArrayDeque<Entry>()

    /** Добавляет code, удаляет записи старше now - windowMs.
     *  Возвращает code, если он встречается >= requiredMatches раз в окне (иначе null). */
    fun add(code: String, now: Long, requiredMatches: Int = 2): String? {
        prune(now)
        buffer.addLast(Entry(code, now))
        if (buffer.size < requiredMatches) return null
        val first = buffer.first().code
        if (buffer.count { it.code == first } >= requiredMatches) return first
        return null
    }

    /** Самый частый код в окне (при равенстве — первый по порядку добавления). */
    fun bestCanonical(): String? {
        if (buffer.isEmpty()) return null
        return buffer.groupingBy { it.code }.eachCount()
            .maxByOrNull { it.value }?.key
    }

    fun clear() = buffer.clear()

    fun isEmpty(): Boolean = buffer.isEmpty()

    private fun prune(now: Long) {
        val cutoff = now - windowMs
        while (buffer.isNotEmpty() && buffer.first().timestamp < cutoff) {
            buffer.removeFirst()
        }
    }
}
```

**Threading:** все операции однопоточные (вызываются только с `analyzerExecutor`).

### `scanner/BarcodeAnalyzer.kt` (изменения)

**Новые параметры конструктора:**
```kotlin
class BarcodeAnalyzer(
    private val maxCenterDistanceFraction: Float = 0.18f,
    private val cooldownMs: Long = 2000L,
    private val startupDelayMs: Long = 1500L,
    private val executor: ScheduledExecutorService,
    private val windowMs: Long = 300L,
    private val fallbackDelayMs: Long = 700L,
    private val requiredMatches: Int = 2,
    private val onResult: (ScannerResult) -> Unit
) : ImageAnalysis.Analyzer { ... }
```

**Новые поля:**
```kotlin
private val window = FrameWindow(windowMs)
@Volatile private var firstScanAt: Long = 0L
@Volatile private var lastFiredCode: String? = null
private var lastFireTime = 0L  // touched only on executor thread
```

**Новая логика `analyze()`** (после существующих checks: image, startup, center, CODE_39):
```kotlin
val value = centerBarcode.rawValue ?: centerBarcode.displayValue ?: return@addOnSuccessListener

if (centerBarcode.format == Barcode.FORMAT_CODE_39 && value.length < 12) {
    return@addOnSuccessListener
}

val canonical = BarcodeShape.bestCanonical(value)
if (BuildConfig.DEBUG) android.util.Log.d("BarcodeAnalyzer", "value=$value canonical=$canonical")

if (canonical != null) {
    handleWarehouseCode(canonical, centerBarcode.format)
} else {
    handleNonWarehouseCode(value, centerBarcode.format)
}
```

```kotlin
private fun handleWarehouseCode(canonical: String, format: Int) {
    val now = System.currentTimeMillis()
    if (canonical == lastFiredCode && now - lastFireTime < cooldownMs) return

    val confirmed = window.add(canonical, now, requiredMatches)
    if (confirmed != null) {
        val lookupResult = BarcodeDatabase.lookup(canonical)
        if (BuildConfig.DEBUG) android.util.Log.d("BarcodeAnalyzer", "CONFIRMED: $canonical lookup=$lookupResult")
        onResult(ScannerResult.Success(canonical, format, lookupResult))
        lastFiredCode = canonical
        lastFireTime = now
        window.clear()
        firstScanAt = 0L
    } else if (firstScanAt == 0L) {
        firstScanAt = now
        executor.schedule(::fireFallback, fallbackDelayMs, TimeUnit.MILLISECONDS)
    }
}

private fun fireFallback() {
    if (firstScanAt == 0L) return
    val best = window.bestCanonical()
    firstScanAt = 0L
    window.clear()
    if (best == null || best == lastFiredCode) return
    val lookupResult = BarcodeDatabase.lookup(best)
    if (BuildConfig.DEBUG) android.util.Log.d("BarcodeAnalyzer", "FALLBACK: $best lookup=$lookupResult")
    onResult(ScannerResult.Success(best, Barcode.FORMAT_CODE_128, lookupResult))
    lastFiredCode = best
    lastFireTime = System.currentTimeMillis()
}

private fun handleNonWarehouseCode(value: String, format: Int) {
    val now = System.currentTimeMillis()
    if (value == lastScannedCode && now - lastScanTime < cooldownMs) return
    if (scannedCodes.contains(value)) return
    addScannedCode(value)
    lastScannedCode = value
    lastScanTime = now
    if (BuildConfig.DEBUG) android.util.Log.d("BarcodeAnalyzer", "NON-WAREHOUSE SUCCESS: $value")
    onResult(ScannerResult.Success(value, format, null))
}

fun reset() {
    executor.execute {
        window.clear()
        firstScanAt = 0L
        lastFiredCode = null
    }
}
```

**Note:** `fireFallback` не получает `format` напрямую, использует `Barcode.FORMAT_CODE_128` как placeholder. Если важен точный формат — можно сохранять в `FrameWindow.Entry` помимо кода. Но в текущей системе `format` в `Success` используется только для логов; пользовательский UX его не показывает. Оставляю placeholder, не раздуваю API.

### `scanner/BarcodeDatabase.kt` (изменения)

- `MAX_FUZZY_DISTANCE = 2` → `MAX_FUZZY_DISTANCE = 3`.
- Фильтр длины: `abs(it.barcode.length - scanned.length) <= MAX_FUZZY_DISTANCE` (было 2).
- API `lookup()` не меняется.
- **Hamming не добавляю**: для наших фикс-длинных 15-символьных кодов Hamming == Levenshtein (только substitutions). При разных длинах Hamming неприменим. Лишний код без выигрыша.

### `overlay/OverlayActivity.kt` (изменения)

В `CameraPreview`:
- `Executors.newSingleThreadExecutor()` → `Executors.newSingleThreadScheduledExecutor()`.
- Передаём `analyzerExecutor` в `BarcodeAnalyzer(...)`.
- `LaunchedEffect(resetScanCompleted)` дополнительно зовёт `scannerRef.value?.reset()`.

```kotlin
val analyzerExecutor = remember { java.util.concurrent.Executors.newSingleThreadScheduledExecutor() }
...
LaunchedEffect(resetScanCompleted) {
    if (resetScanCompleted) {
        scanCompleted.set(false)
        scannerRef.value?.reset()
    }
}
...
imageAnalysis.setAnalyzer(
    analyzerExecutor,
    BarcodeAnalyzer(
        executor = analyzerExecutor,
        onResult = { result -> ... }
    ).also { scannerRef.value = it }
)
```

## Threading invariants

- `analyze()` ВСЕГДА на `analyzerExecutor` (single-thread). `executor.schedule(::fireFallback, ...)` тоже на этом же треде.
- `window`, `firstScanAt`, `lastFireTime` — только executor thread (кроме `firstScanAt`/`lastFiredCode`, которые @Volatile на случай если кто-то снаружи прочитает — но никто не читает; defensive).
- `reset()` постит runnable в executor — гарантия однопоточности при модификации window/firstScanAt.
- `close()` shutdown не нужен (executor живёт в CameraPreview).

## Non-warehouse regression check

`handleNonWarehouseCode` повторяет **в точности** существующую логику:
1. Cooldown 2s на `lastScannedCode`.
2. Dedup через `scannedCodes` (size 50).
3. Fire `Success(value, format, null)`.
4. `lastScannedCode = value`, `lastScanTime = now`.

Единственное отличие — обёрнуто в helper. Логика идентична.

## Backward compatibility

- `ScannerResult` не меняется.
- `BarcodeDatabase.lookup()` сигнатура не меняется.
- `BarcodeAnalyzer` ctor получает обязательный параметр `executor` — единственный breaking change, но caller только один (`CameraPreview`), который обновляется в том же коммите.

## Out of scope

- Изменения в `OverlayViewModel`, `OverlayActivity` (кроме `CameraPreview` wiring) — не нужны.
- CSV / база — не трогаем.
- Auto-update flow — не трогаем.

## Verification

- `build.ps1 apk` → debug APK собирается без ошибок.
- (Ручное): сканировать несколько нормальных кодов из `barcodes.csv` — все находятся.
- (Ручное): сканировать EAN-13 (например с упаковки) — мгновенный Success, без multi-frame задержки.
- (Ручное): сканировать испорченный код 2 раза подряд — Success после 2-го, не сразу.
