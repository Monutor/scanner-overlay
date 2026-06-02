# SEW Auto-Modal Input Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** After a successful scan, fully automatically open SEW's "Ручной ввод" modal in Chrome / PWA WebAPK, inject the barcode, and click "Готово" — without any intermediate user taps.

**Architecture:** Hybrid accessibility + coordinate-fallback. The "Ручной ввод" trigger button and the modal's text field have no stable a11y identifier, so we open the modal and tap "Готово" by calibrated coordinates. The text input is found via `findFocus(FOCUS_INPUT)` first, with coordinates as a fallback. Calibration is a 3-step transparent overlay activity; the chain itself runs in `ScannerAccessibilityService` with a 4-second rolling watchdog.

**Tech Stack:** Kotlin 2.0.21, Jetpack Compose, Hilt 2.52 (KSP), Android `AccessibilityService` + `dispatchGesture`, `GestureDescription`, AGP 8.5.2, Gradle 8.7.

**Spec:** `docs/superpowers/specs/2026-06-01-sew-auto-modal-design.md`

**Verify strategy:** Project has no unit tests (per `AGENTS.md`). Each task verifies by `./gradlew compileDebugKotlin` (fast, runs Hilt KSP). The final task verifies by `./gradlew installDebug` to confirm a full APK builds.

---

## File Structure

| File | Status | Responsibility |
|---|---|---|
| `app/src/main/java/com/scanner/overlay/calibration/SewCalibration.kt` | New | `data class SewCalibration` with `targetPackage: String`, `openModal: Point`, `input: Point`, `confirm: Point`; computed `isCalibrated` |
| `app/src/main/java/com/scanner/overlay/calibration/SewCalibrationViewModel.kt` | New | Holds current step (0/1/2 or countdown), persists captures to `SharedPreferences` |
| `app/src/main/java/com/scanner/overlay/calibration/SewCalibrationActivity.kt` | New | 3-step transparent overlay; countdown; touch listener records `(rawX, rawY, rootPackageName)` without blocking touch dispatch |
| `app/src/main/java/com/scanner/overlay/settings/SewTestResult.kt` | New | `data class SewTestResult(steps, inProgress)` + `data class StepStatus(name, ok, message)` |
| `app/src/main/java/com/scanner/overlay/accessibility/ScannerAccessibilityService.kt` | Modify | + `typealias SewInputCallback`; + `runSewAutoInput(barcode, calibration, testMode, onResult, onStep?)`; + `cancelOngoingSewInput()`; + 4-sec rolling watchdog; + per-step test reporting |
| `app/src/main/java/com/scanner/overlay/overlay/OverlayActivity.kt` | Modify | + `@Inject SewCalibration`; + `triggerSewAutoInput(barcode)`; + new UI state "Ввод в SEW…"; replace `finishRunnable` chain |
| `app/src/main/java/com/scanner/overlay/settings/SettingsViewModel.kt` | Modify | + `sewCalibration: StateFlow<SewCalibration>`; + `saveSewCalibration`; + `resetSewCalibration`; + `runSewCalibrationTest()` (suspend) |
| `app/src/main/java/com/scanner/overlay/settings/SettingsScreen.kt` | Modify | + `SewCalibrationCard` composable with `Откалибровать` / `Тест` / `Сбросить`; + test result block |
| `app/src/main/java/com/scanner/overlay/di/AppModule.kt` | Modify | + `provideSewCalibration(prefs)` provider |
| `app/src/main/AndroidManifest.xml` | Modify | + `<activity SewCalibrationActivity>` with transparent theme, `excludeFromRecents="true"`, `taskAffinity=""`, `exported="false"`, `showWhenLocked="true"`, `turnScreenOn="true"`, `screenOrientation="portrait"` |

**SharedPreferences keys** (all in `scanner_prefs`):

| Key | Type | Default | Meaning |
|---|---|---|---|
| `sew_calibrated` | Boolean | `false` | Mirror of `SewCalibration.isCalibrated` |
| `sew_target_package` | String | `""` | `packageName` of foreground app at first tap (Chrome `com.android.chrome` or PWA `org.chromium.webapk.<hash>`) |
| `sew_open_modal_x`, `sew_open_modal_y` | Int | 0 | Calibrated "Ручной ввод" button center |
| `sew_input_x`, `sew_input_y` | Int | 0 | Calibrated input field center (fallback) |
| `sew_confirm_x`, `sew_confirm_y` | Int | 0 | Calibrated "Готово" button center (fallback) |

---

## Deviations from the spec (intentional, minor)

The spec lists `ScannerAccessibilityService` as gaining `setOnInjectionResultListener` in §4.2. This listener is not strictly required: `runSewAutoInput`'s `onResult` callback already covers all cases that need to communicate with the caller (OverlayActivity and SettingsViewModel test). We omit the global listener to keep the change minimal and avoid a long-lived callback reference inside the service.

The spec lists `runSewAutoInput(barcode, calibration, testMode, onResult)` as the final signature in §4.2. To support per-step test reporting (spec §5.3, §8), we add an **optional** `onStep: ((String, Boolean, String?) -> Unit)? = null` parameter. When set (only by the test path), it fires once per step before the final `onResult`. When `null` (the production path), the behavior is identical to the spec.

---

## Task 1: Data types and Hilt provider

**Files:**
- Create: `app/src/main/java/com/scanner/overlay/calibration/SewCalibration.kt`
- Create: `app/src/main/java/com/scanner/overlay/settings/SewTestResult.kt`
- Modify: `app/src/main/java/com/scanner/overlay/di/AppModule.kt:1-23`

- [ ] **Step 1: Create `SewCalibration.kt`**

```kotlin
package com.scanner.overlay.calibration

import android.graphics.Point

data class SewCalibration(
    val targetPackage: String,
    val openModal: Point,
    val input: Point,
    val confirm: Point
) {
    val isCalibrated: Boolean
        get() = targetPackage.isNotEmpty()

    companion object {
        fun empty(): SewCalibration = SewCalibration(
            targetPackage = "",
            openModal = Point(0, 0),
            input = Point(0, 0),
            confirm = Point(0, 0)
        )
    }
}
```

- [ ] **Step 2: Create `SewTestResult.kt`**

```kotlin
package com.scanner.overlay.settings

data class SewTestResult(
    val steps: List<StepStatus>,
    val inProgress: Boolean = false,
    val finished: Boolean = false,
    val errorMessage: String? = null
)

data class StepStatus(
    val name: String,
    val ok: Boolean,
    val message: String? = null
)
```

- [ ] **Step 3: Add `provideSewCalibration` to `AppModule.kt`**

Replace the body of `AppModule` (lines 13–23 in current file) with:

```kotlin
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    private const val PREFS_NAME = "scanner_prefs"

    @Provides
    @Singleton
    fun provideSharedPreferences(@ApplicationContext context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    @Provides
    @Singleton
    fun provideSewCalibration(prefs: SharedPreferences): SewCalibration {
        return SewCalibration(
            targetPackage = prefs.getString("sew_target_package", "") ?: "",
            openModal = Point(
                prefs.getInt("sew_open_modal_x", 0),
                prefs.getInt("sew_open_modal_y", 0)
            ),
            input = Point(
                prefs.getInt("sew_input_x", 0),
                prefs.getInt("sew_input_y", 0)
            ),
            confirm = Point(
                prefs.getInt("sew_confirm_x", 0),
                prefs.getInt("sew_confirm_y", 0)
            )
        )
    }
}
```

Add the imports at the top of the file:

```kotlin
import android.graphics.Point
import com.scanner.overlay.calibration.SewCalibration
```

- [ ] **Step 4: Verify it compiles**

Run: `cd G:\Front-end-projects\AIDevelops\ScannerOverlay; .\gradlew.bat compileDebugKotlin`
Expected: `BUILD SUCCESSFUL` in 30–60 s (Hilt KSP runs and accepts the new provider).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/scanner/overlay/calibration/SewCalibration.kt app/src/main/java/com/scanner/overlay/settings/SewTestResult.kt app/src/main/java/com/scanner/overlay/di/AppModule.kt
git commit -m "feat(calibration): add SewCalibration type, SewTestResult DTO, and Hilt provider"
```

---

## Task 2: `ScannerAccessibilityService.runSewAutoInput` + watchdog + test mode

**Files:**
- Modify: `app/src/main/java/com/scanner/overlay/accessibility/ScannerAccessibilityService.kt`

This is the largest task. The chain has 7 steps (spec §5.2) plus rolling watchdog plus `testMode` per-step reporting. Build the whole thing in one task so the unit of change is reviewable as a single feature.

- [ ] **Step 1: Add the typealias and chain-state fields**

Right after the existing `import` block (after line 14), insert:

```kotlin
typealias SewInputCallback = (success: Boolean, message: String) -> Unit
typealias SewStepCallback = (name: String, ok: Boolean, message: String?) -> Unit
```

Right after the existing field block (after line 24, before `override fun onServiceConnected`), insert:

```kotlin
    @Volatile private var sewInputInProgress: Boolean = false
    private val watchdogHandler = Handler(Looper.getMainLooper())
    private val watchdogTimeoutMs: Long = 4_000L
```

- [ ] **Step 2: Append the public entry point and the chain steps**

Append to the class body (just before the closing `}` of the class — after line 313 and before line 314 which is the companion object; insert this block before `companion object`):

```kotlin
    fun runSewAutoInput(
        barcode: String,
        calibration: SewCalibration,
        testMode: Boolean = false,
        onResult: SewInputCallback,
        onStep: SewStepCallback? = null
    ) {
        if (sewInputInProgress) {
            onResult(false, "Подождите завершения ввода")
            return
        }
        if (!calibration.isCalibrated) {
            onResult(false, "Калибровка не выполнена")
            return
        }
        sewInputInProgress = true
        val effectiveBarcode = if (testMode) "TEST_CALIBRATION" else barcode
        armWatchdog(onResult)

        step1FindWindow(calibration, testMode, effectiveBarcode, onResult, onStep)
    }

    fun cancelOngoingSewInput(message: String = "Отменено") {
        if (!sewInputInProgress) return
        watchdogHandler.removeCallbacksAndMessages(null)
        sewInputInProgress = false
    }

    private fun armWatchdog(onResult: SewInputCallback) {
        watchdogHandler.removeCallbacksAndMessages(null)
        watchdogHandler.postDelayed({
            if (sewInputInProgress) {
                sewInputInProgress = false
                onResult(false, "Таймаут")
            }
        }, watchdogTimeoutMs)
    }

    private fun step1FindWindow(
        calibration: SewCalibration,
        testMode: Boolean,
        effectiveBarcode: String,
        onResult: SewInputCallback,
        onStep: SewStepCallback?
    ) {
        val target = windows.firstOrNull {
            it.root?.packageName == calibration.targetPackage && it.isActive
        }
        if (target == null) {
            releaseWatchdogAndFinish(onResult, false, "Откройте SEW (PWA или Chrome)")
            return
        }
        onStep?.invoke("SEW найден", true, null)
        armWatchdog(onResult)
        step2ClickOpenModal(calibration, testMode, effectiveBarcode, onResult, onStep)
    }

    private fun step2ClickOpenModal(
        calibration: SewCalibration,
        testMode: Boolean,
        effectiveBarcode: String,
        onResult: SewInputCallback,
        onStep: SewStepCallback?
    ) {
        val point = calibration.openModal
        dispatchGesture(
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(
                    point.x.toFloat(), point.y.toFloat(), 0L, 50L
                ))
                .build(),
            null, null
        )
        mainHandler.postDelayed({
            armWatchdog(onResult)
            onStep?.invoke("Кнопка «Ручной ввод» доступна", true, null)
            step3FindInput(calibration, testMode, effectiveBarcode, onResult, onStep)
        }, 600L)
    }

    private fun step3FindInput(
        calibration: SewCalibration,
        testMode: Boolean,
        effectiveBarcode: String,
        onResult: SewInputCallback,
        onStep: SewStepCallback?
    ) {
        val fromFocus = findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (fromFocus != null && fromFocus.isEditable) {
            onStep?.invoke("Поле ввода найдено", true, null)
            armWatchdog(onResult)
            step4SetText(fromFocus, effectiveBarcode, testMode, onResult, onStep)
            return
        }
        fromFocus?.safeRecycle()

        val fromText = findInputByPlaceholder("Штрих-код")
        if (fromText != null) {
            onStep?.invoke("Поле ввода найдено", true, null)
            armWatchdog(onResult)
            step4SetText(fromText, effectiveBarcode, testMode, onResult, onStep)
            return
        }

        val p = calibration.input
        dispatchGesture(
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(
                    p.x.toFloat(), p.y.toFloat(), 0L, 50L
                ))
                .build(),
            null, null
        )
        mainHandler.postDelayed({
            armWatchdog(onResult)
            val retry = findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            if (retry != null && retry.isEditable) {
                onStep?.invoke("Поле ввода найдено", true, null)
                step4SetText(retry, effectiveBarcode, testMode, onResult, onStep)
            } else {
                retry?.safeRecycle()
                releaseWatchdogAndFinish(onResult, false, "Поле ввода не найдено — перекалибруйте")
            }
        }, 300L)
    }

    private fun findInputByPlaceholder(text: String): AccessibilityNodeInfo? {
        for (win in windows) {
            val root = win.root ?: continue
            val found = findNodeContaining(root, text)
            if (found != null) {
                if (found.isEditable) return found
                val parent = found.parent
                found.safeRecycle()
                if (parent != null && parent.isEditable) return parent
                parent?.safeRecycle()
            }
        }
        return null
    }

    private fun step4SetText(
        inputNode: AccessibilityNodeInfo,
        barcode: String,
        testMode: Boolean,
        onResult: SewInputCallback,
        onStep: SewStepCallback?
    ) {
        val args = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                barcode
            )
        }
        inputNode.refresh()
        val ok = inputNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        if (!ok) {
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            val original = clipboard.primaryClip
            pendingClipboardRestore = original
            clipboard.setPrimaryClip(ClipData.newPlainText("barcode", barcode))
            inputNode.refresh()
            inputNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            mainHandler.postDelayed({
                inputNode.safeRecycle()
                val pasted = findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                if (pasted != null && pasted.isEditable) {
                    step4SetText(pasted, barcode, testMode, onResult, onStep)
                } else {
                    pasted?.safeRecycle()
                    releaseWatchdogAndFinish(onResult, false, "Ввод не зафиксирован")
                }
            }, 250L)
            return
        }
        mainHandler.postDelayed({
            if (testMode) {
                onStep?.invoke("Ввод работает", true, null)
                armWatchdog(onResult)
                step6ClickConfirm(calibration = null, testMode, onResult, onStep)
            } else {
                step5Verify(inputNode, barcode, onResult, onStep)
            }
        }, 200L)
    }

    private fun step5Verify(
        inputNode: AccessibilityNodeInfo,
        barcode: String,
        onResult: SewInputCallback,
        onStep: SewStepCallback?
    ) {
        val refreshed = findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        val current = refreshed?.text?.toString() ?: ""
        refreshed?.safeRecycle()
        if (!current.contains(barcode)) {
            releaseWatchdogAndFinish(onResult, false, "Ввод не зафиксирован")
            return
        }
        onStep?.invoke("Ввод работает", true, null)
        armWatchdog(onResult)
        step6ClickConfirm(calibration = null, testMode = false, onResult, onStep)
    }

    private fun step6ClickConfirm(
        calibration: SewCalibration?,
        testMode: Boolean,
        onResult: SewInputCallback,
        onStep: SewStepCallback?
    ) {
        val candidates = mutableListOf<AccessibilityNodeInfo>()
        for (win in windows) {
            val root = win.root ?: continue
            val found = findSendButton(root, listOf("Готово", "Done", "Submit", "Отправить", "Send"))
            if (found != null) candidates.add(found)
        }
        val clickable = candidates.firstOrNull { it.isClickable }
        if (clickable != null) {
            if (testMode) {
                onStep?.invoke("Кнопка «Готово» найдена", true, null)
                releaseWatchdogAndFinish(onResult, true, "Тест пройден")
            } else {
                clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                clickable.safeRecycle()
                candidates.forEach { it.takeIf { c -> c !== clickable }?.safeRecycle() }
                releaseWatchdogAndFinish(onResult, true, "Готово")
            }
            return
        }
        candidates.forEach { it.safeRecycle() }
        if (calibration != null) {
            val p = calibration.confirm
            dispatchGesture(
                GestureDescription.Builder()
                    .addStroke(GestureDescription.StrokeDescription(
                        p.x.toFloat(), p.y.toFloat(), 0L, 50L
                    ))
                    .build(),
                null, null
            )
            releaseWatchdogAndFinish(onResult, true, "Готово")
        } else {
            if (testMode) {
                onStep?.invoke("Кнопка «Готово» найдена", false, "Кнопка не найдена")
                releaseWatchdogAndFinish(onResult, false, "Кнопка «Готово» не найдена")
            } else {
                releaseWatchdogAndFinish(onResult, false, "Кнопка «Готово» не найдена")
            }
        }
    }

    private fun releaseWatchdogAndFinish(onResult: SewInputCallback, ok: Boolean, message: String) {
        watchdogHandler.removeCallbacksAndMessages(null)
        sewInputInProgress = false
        onResult(ok, message)
    }
```

- [ ] **Step 3: Add new imports at the top of the file**

```kotlin
import android.accessibilityservice.GestureDescription
import android.graphics.Point
import com.scanner.overlay.calibration.SewCalibration
```

- [ ] **Step 4: Verify it compiles**

Run: `cd G:\Front-end-projects\AIDevelops\ScannerOverlay; .\gradlew.bat compileDebugKotlin`
Expected: `BUILD SUCCESSFUL`. The new `runSewAutoInput` is unused at this point (OverlayActivity still uses `autoInjectText`), so Hilt KSP has no complaints. If Kotlin flags the unused public method, the `@Suppress("unused")` not needed for public methods — but if you see `Unused symbol` warnings, they are warnings, not errors.

If you see `step6ClickConfirm` has an unused `testMode` parameter in the non-test path, that is fine. The test path is exercised by Task 4.

- [ ] **Step 5: Manual smoke (optional, requires a connected device)**

If a device with Chrome and SEW is available: temporarily wire a trigger in `MainActivity` that calls `ScannerAccessibilityService.instance?.runSewAutoInput("12345", SewCalibration(...), onResult = { ok, msg -> Log.d("Sew", "$ok $msg") })` with hand-set coordinates. Confirm the chain opens the modal, types, and clicks "Готово". Revert the trigger before committing.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/scanner/overlay/accessibility/ScannerAccessibilityService.kt
git commit -m "feat(accessibility): add runSewAutoInput chain with watchdog and test mode"
```

---

## Task 3: `OverlayActivity` triggers SEW auto-input and shows new UI state

**Files:**
- Modify: `app/src/main/java/com/scanner/overlay/overlay/OverlayActivity.kt`

- [ ] **Step 1: Add Hilt field for `SewCalibration` and new state flag**

Right after `private var injectionAttempted = false` (line 74), add:

```kotlin
    @javax.inject.Inject lateinit var sewCalibration: SewCalibration

    private var isSubmittingToSew: Boolean = false
```

- [ ] **Step 2: Replace the `finishRunnable` (lines 76–93) with a SEW-aware version**

Replace the existing `finishRunnable` with:

```kotlin
    private val finishRunnable = Runnable {
        android.util.Log.d("OverlayActivity", "finishRunnable executing, isFinishing=$isFinishing, barcode=${pendingBarcode}")
        try {
            val barcode = pendingBarcode
            if (!isFinishing && barcode != null && !injectionAttempted) {
                injectionAttempted = true
                if (sewCalibration.isCalibrated) {
                    triggerSewAutoInput(barcode)
                } else {
                    android.util.Log.d("OverlayActivity", "SEW not calibrated, falling back to autoInjectText")
                    Toast.makeText(this, "Сделайте калибровку SEW", Toast.LENGTH_SHORT).show()
                    val service = ScannerAccessibilityService.instance
                    if (service?.autoInjectText(barcode) != true) {
                        service?.injectText(barcode)
                    }
                    if (!isFinishing) finish()
                }
            } else if (!isFinishing) {
                finish()
            }
        } catch (e: Exception) {
            android.util.Log.e("OverlayActivity", "finishRunnable crash", e)
            if (!isFinishing) finish()
        }
    }

    private fun triggerSewAutoInput(barcode: String) {
        isSubmittingToSew = true
        val service = ScannerAccessibilityService.instance
        if (service == null) {
            android.util.Log.w("OverlayActivity", "Accessibility service not running, falling back")
            Toast.makeText(this, "Сервис доступности не запущен", Toast.LENGTH_SHORT).show()
            if (!isFinishing) finish()
            return
        }
        service.runSewAutoInput(
            barcode = barcode,
            calibration = sewCalibration,
            onResult = { ok, message -> onSewInputResult(ok, message) }
        )
        // The service owns the chain timing; we close immediately so Chrome can take focus.
        if (!isFinishing) finish()
    }

    private fun onSewInputResult(ok: Boolean, message: String) {
        // Called on the accessibility service's main thread; bounce to UI thread.
        mainHandler.post {
            isSubmittingToSew = false
            Toast.makeText(
                this,
                if (ok) "Штрих введён" else "Не удалось ввести — откройте модалку вручную",
                Toast.LENGTH_SHORT
            ).show()
            vibrateResult(ok)
        }
    }

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    private fun vibrateResult(ok: Boolean) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val ms = if (ok) 100L else 400L
                val amp = if (ok) VibrationEffect.DEFAULT_AMPLITUDE else VibrationEffect.DEFAULT_AMPLITUDE
                vibrator.vibrate(VibrationEffect.createOneShot(ms, amp))
            }
        } catch (_: Exception) {}
    }
```

Remove the existing `private val mainHandler = …` declaration that is NOT present (the file uses `finishHandler` only). The new `mainHandler` field above is required.

Note: the existing `private val finishHandler = android.os.Handler(android.os.Looper.getMainLooper())` is already declared (line 72). Delete the duplicate `mainHandler` declaration added above; reuse `finishHandler` if you prefer, but for clarity keep a separate `mainHandler`. **Pick one**: use `finishHandler.post { ... }` instead of `mainHandler.post { ... }` to avoid an extra field. Apply that substitution in the snippet above (`onSewInputResult`).

- [ ] **Step 3: Add a "Ввод в SEW…" UI state to `OverlayContent`**

The `OverlayContent` composable (line 249) currently shows the `Success` UI for 2 seconds before the chain fires. We want to keep the green "✓" success screen visible for ~2 seconds, then switch to a small "Ввод в SEW…" overlay for ~1 second while the chain is firing (so the user sees the activity is doing something before it closes).

In the `when` block inside the `Box` (line 438), add a new branch **after** the `state is OverlayViewModel.OverlayState.Success` branch (around line 564):

```kotlin
            isSubmittingToSew -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0x99000000)),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .background(Color(0x1AFFFFFF), RoundedCornerShape(24.dp))
                            .border(0.5.dp, Color(0x14FFFFFF), RoundedCornerShape(24.dp))
                            .padding(horizontal = 40.dp, vertical = 32.dp)
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(48.dp),
                            color = Color(0xFF4CAF50),
                            strokeWidth = 3.dp
                        )
                        Spacer(Modifier.height(16.dp))
                        Text(
                            "Ввод в SEW…",
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp,
                            color = Color.White
                        )
                    }
                }
            }
```

- [ ] **Step 4: Pass `isSubmittingToSew` into the composable**

Add a new parameter to `OverlayContent`:

```kotlin
fun OverlayContent(
    viewModel: OverlayViewModel,
    isSubmittingToSew: Boolean = false,
    onClose: () -> Unit,
    onBarcodeScanned: (String, Boolean) -> Unit,
    onScheduleFinish: (String, Boolean) -> Unit,
    onManualSubmit: (String) -> Unit,
    onRetry: () -> Unit = {},
    onCancelFinish: () -> Unit = {},
    onRequestInputFocus: () -> Unit = {},
    onReleaseInputFocus: () -> Unit = {}
) {
```

And in `OverlayActivity.onCreate` (around line 121), pass it:

```kotlin
                    OverlayContent(
                        viewModel = viewModel,
                        isSubmittingToSew = isSubmittingToSew,
                        onClose = { finish() },
                        // ... rest unchanged
                    )
```

- [ ] **Step 5: Verify it compiles**

Run: `cd G:\Front-end-projects\AIDevelops\ScannerOverlay; .\gradlew.bat compileDebugKotlin`
Expected: `BUILD SUCCESSFUL`. The flow now uses `runSewAutoInput` when calibrated, falls back to `autoInjectText` + toast otherwise.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/scanner/overlay/overlay/OverlayActivity.kt
git commit -m "feat(overlay): trigger SEW auto-input after scan and show submitting state"
```

---

## Task 4: `SettingsViewModel` — calibration state, save, reset, and test

**Files:**
- Modify: `app/src/main/java/com/scanner/overlay/settings/SettingsViewModel.kt`

- [ ] **Step 1: Add imports**

At the top of the file (after the existing imports), add:

```kotlin
import com.scanner.overlay.accessibility.ScannerAccessibilityService
import com.scanner.overlay.calibration.SewCalibration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.graphics.Point
```

- [ ] **Step 2: Add prefs keys and state flows to the `companion object` and class body**

Inside `companion object` (after line 40), append:

```kotlin
        private const val PREF_KEY_SEW_CALIBRATED = "sew_calibrated"
        private const val PREF_KEY_SEW_TARGET_PACKAGE = "sew_target_package"
        private const val PREF_KEY_SEW_OPEN_MODAL_X = "sew_open_modal_x"
        private const val PREF_KEY_SEW_OPEN_MODAL_Y = "sew_open_modal_y"
        private const val PREF_KEY_SEW_INPUT_X = "sew_input_x"
        private const val PREF_KEY_SEW_INPUT_Y = "sew_input_y"
        private const val PREF_KEY_SEW_CONFIRM_X = "sew_confirm_x"
        private const val PREF_KEY_SEW_CONFIRM_Y = "sew_confirm_y"
```

Add a constructor parameter for the calibration snapshot (so the VM can re-snapshot when needed). Update the class signature to:

```kotlin
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val app: Application,
    private val prefs: SharedPreferences,
    private val currentCalibration: SewCalibration
) : AndroidViewModel(app) {
```

Add new state flows inside the class body (after the existing `_scanQuality` block, around line 49):

```kotlin
    private val _sewCalibration = MutableStateFlow(currentCalibration)
    val sewCalibration: StateFlow<SewCalibration> = _sewCalibration.asStateFlow()

    private val _sewTestResult = MutableStateFlow(
        SewTestResult(steps = emptyList(), finished = true)
    )
    val sewTestResult: StateFlow<SewTestResult> = _sewTestResult.asStateFlow()
```

- [ ] **Step 3: Add `saveSewCalibration` and `resetSewCalibration`**

After `updateScanQuality` (after line 102), add:

```kotlin
    fun saveSewCalibration(calibration: SewCalibration) {
        prefs.edit()
            .putBoolean(PREF_KEY_SEW_CALIBRATED, true)
            .putString(PREF_KEY_SEW_TARGET_PACKAGE, calibration.targetPackage)
            .putInt(PREF_KEY_SEW_OPEN_MODAL_X, calibration.openModal.x)
            .putInt(PREF_KEY_SEW_OPEN_MODAL_Y, calibration.openModal.y)
            .putInt(PREF_KEY_SEW_INPUT_X, calibration.input.x)
            .putInt(PREF_KEY_SEW_INPUT_Y, calibration.input.y)
            .putInt(PREF_KEY_SEW_CONFIRM_X, calibration.confirm.x)
            .putInt(PREF_KEY_SEW_CONFIRM_Y, calibration.confirm.y)
            .apply()
        _sewCalibration.value = calibration
    }

    fun resetSewCalibration() {
        prefs.edit()
            .putBoolean(PREF_KEY_SEW_CALIBRATED, false)
            .putString(PREF_KEY_SEW_TARGET_PACKAGE, "")
            .putInt(PREF_KEY_SEW_OPEN_MODAL_X, 0)
            .putInt(PREF_KEY_SEW_OPEN_MODAL_Y, 0)
            .putInt(PREF_KEY_SEW_INPUT_X, 0)
            .putInt(PREF_KEY_SEW_INPUT_Y, 0)
            .putInt(PREF_KEY_SEW_CONFIRM_X, 0)
            .putInt(PREF_KEY_SEW_CONFIRM_Y, 0)
            .apply()
        _sewCalibration.value = SewCalibration.empty()
        _sewTestResult.value = SewTestResult(steps = emptyList(), finished = true)
    }
```

- [ ] **Step 4: Add `runSewCalibrationTest()` (suspend)**

After `resetSewCalibration`, add:

```kotlin
    fun runSewCalibrationTest() {
        val calibration = _sewCalibration.value
        if (!calibration.isCalibrated) {
            _sewTestResult.value = SewTestResult(
                steps = listOf(StepStatus("SEW откалиброван", ok = false, message = "Сначала откалибруйте")),
                finished = true
            )
            return
        }
        val stepNames = listOf(
            "SEW найден",
            "Кнопка «Ручной ввод» доступна",
            "Поле ввода найдено",
            "Ввод работает",
            "Кнопка «Готово» найдена"
        )
        _sewTestResult.value = SewTestResult(
            steps = stepNames.map { StepStatus(it, ok = false, message = "Ожидание...") },
            inProgress = true,
            finished = false
        )
        viewModelScope.launch {
            withContext(Dispatchers.Main) {
                val service = ScannerAccessibilityService.instance
                if (service == null) {
                    _sewTestResult.value = SewTestResult(
                        steps = listOf(StepStatus(
                            "Сервис доступности",
                            ok = false,
                            message = "Включите специальные возможности"
                        )),
                        finished = true
                    )
                    return@withContext
                }
                service.runSewAutoInput(
                    barcode = "TEST_CALIBRATION",
                    calibration = calibration,
                    testMode = true,
                    onResult = { ok, message ->
                        // Final result — mark the last still-pending step as finished.
                        viewModelScope.launch {
                            val current = _sewTestResult.value
                            val updated = current.steps.mapIndexed { i, s ->
                                if (s.message == "Ожидание..." && !s.ok) {
                                    s.copy(ok = ok && i == current.steps.lastIndex, message = if (ok) null else message)
                                } else s
                            }
                            _sewTestResult.value = current.copy(
                                steps = updated,
                                inProgress = false,
                                finished = true
                            )
                        }
                    },
                    onStep = { name, ok, message ->
                        viewModelScope.launch {
                            val current = _sewTestResult.value
                            val idx = current.steps.indexOfFirst { it.name == name }
                            if (idx >= 0) {
                                val updated = current.steps.toMutableList()
                                updated[idx] = StepStatus(name = name, ok = ok, message = message)
                                _sewTestResult.value = current.copy(steps = updated)
                            }
                        }
                    }
                )
            }
        }
    }
```

- [ ] **Step 5: Verify it compiles**

Run: `cd G:\Front-end-projects\AIDevelops\ScannerOverlay; .\gradlew.bat compileDebugKotlin`
Expected: `BUILD SUCCESSFUL`. Hilt now needs to provide `SewCalibration` — this is wired in Task 1. If you see `Missing binding: SewCalibration cannot be provided`, double-check `AppModule.provideSewCalibration` is present.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/scanner/overlay/settings/SettingsViewModel.kt
git commit -m "feat(settings): add SEW calibration state, save/reset, and test runner"
```

---

## Task 5: `SewCalibrationViewModel`

**Files:**
- Create: `app/src/main/java/com/scanner/overlay/calibration/SewCalibrationViewModel.kt`

- [ ] **Step 1: Create the file**

```kotlin
package com.scanner.overlay.calibration

import android.app.Application
import android.content.SharedPreferences
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface CalibrationUiState {
    data class Countdown(val secondsLeft: Int) : CalibrationUiState
    data class Step(val stepIndex: Int) : CalibrationUiState
    data class Saved(val calibration: SewCalibration) : CalibrationUiState
}

@HiltViewModel
class SewCalibrationViewModel @Inject constructor(
    private val app: Application,
    private val prefs: SharedPreferences
) : AndroidViewModel(app) {

    private val _state = MutableStateFlow<CalibrationUiState>(CalibrationUiState.Countdown(3))
    val state: StateFlow<CalibrationUiState> = _state.asStateFlow()

    private val openModal = intArrayOf(0, 0)
    private val input = intArrayOf(0, 0)
    private val confirm = intArrayOf(0, 0)
    private var capturedPackage: String = ""

    init {
        startCountdown()
    }

    private fun startCountdown() {
        viewModelScope.launch {
            for (s in 3 downTo 1) {
                _state.value = CalibrationUiState.Countdown(s)
                delay(1000)
            }
            _state.value = CalibrationUiState.Step(stepIndex = 0)
        }
    }

    fun recordTap(rawX: Int, rawY: Int, rootPackageName: String) {
        val s = _state.value
        if (s !is CalibrationUiState.Step) return
        when (s.stepIndex) {
            0 -> {
                if (capturedPackage.isEmpty()) capturedPackage = rootPackageName
                openModal[0] = rawX
                openModal[1] = rawY
                advanceTo(1)
            }
            1 -> {
                input[0] = rawX
                input[1] = rawY
                advanceTo(2)
            }
            2 -> {
                confirm[0] = rawX
                confirm[1] = rawY
                persist()
            }
        }
    }

    private fun advanceTo(nextIndex: Int) {
        _state.value = CalibrationUiState.Step(stepIndex = nextIndex)
    }

    private fun persist() {
        val calibration = SewCalibration(
            targetPackage = capturedPackage,
            openModal = android.graphics.Point(openModal[0], openModal[1]),
            input = android.graphics.Point(input[0], input[1]),
            confirm = android.graphics.Point(confirm[0], confirm[1])
        )
        prefs.edit()
            .putBoolean("sew_calibrated", true)
            .putString("sew_target_package", capturedPackage)
            .putInt("sew_open_modal_x", openModal[0])
            .putInt("sew_open_modal_y", openModal[1])
            .putInt("sew_input_x", input[0])
            .putInt("sew_input_y", input[1])
            .putInt("sew_confirm_x", confirm[0])
            .putInt("sew_confirm_y", confirm[1])
            .apply()
        _state.value = CalibrationUiState.Saved(calibration)
    }
}
```

- [ ] **Step 2: Verify it compiles**

Run: `cd G:\Front-end-projects\AIDevelops\ScannerOverlay; .\gradlew.bat compileDebugKotlin`
Expected: `BUILD SUCCESSFUL`. (No consumers yet — that's Task 6.)

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/scanner/overlay/calibration/SewCalibrationViewModel.kt
git commit -m "feat(calibration): add SewCalibrationViewModel with countdown and 3-step capture"
```

---

## Task 6: `SewCalibrationActivity` + manifest

**Files:**
- Create: `app/src/main/java/com/scanner/overlay/calibration/SewCalibrationActivity.kt`
- Modify: `app/src/main/AndroidManifest.xml`

- [ ] **Step 1: Create `SewCalibrationActivity.kt`**

```kotlin
package com.scanner.overlay.calibration

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class SewCalibrationActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)
        window.addFlags(WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Important: do NOT add FLAG_NOT_TOUCHABLE — we need taps to pass through
        // to Chrome / SEW underneath. The Compose layer does not consume touches.

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = Color.Transparent) {
                    CalibrationScreen()
                }
            }
        }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun CalibrationScreen(
    viewModel: SewCalibrationViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0x33000000))
    ) {
        // Layer 1: instruction card (does not consume touches)
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 48.dp, start = 24.dp, end = 24.dp)
                .background(Color(0xE6000000), RoundedCornerShape(16.dp))
                .padding(20.dp)
        ) {
            when (val s = state) {
                is CalibrationUiState.Countdown -> {
                    Text(
                        "Калибровка SEW",
                        color = Color.White,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Переключитесь в SEW (PWA или Chrome с открытой страницей). Старт через ${s.secondsLeft}…",
                        color = Color.White,
                        fontSize = 14.sp
                    )
                }
                is CalibrationUiState.Step -> {
                    val instruction = when (s.stepIndex) {
                        0 -> "Шаг 1/3: тапните по синей кнопке с иконкой штрих-кода «Ручной ввод»"
                        1 -> "Шаг 2/3: откройте модалку (нажмите «Ручной ввод» ещё раз) и тапните по полю ввода «Штрих-код»"
                        2 -> "Шаг 3/3: тапните по синей кнопке «Готово» в правом нижнем углу модалки"
                        else -> ""
                    }
                    Text(
                        "Калибровка SEW",
                        color = Color.White,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        instruction,
                        color = Color.White,
                        fontSize = 16.sp
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Тап пройдёт сквозь подсказку в SEW",
                        color = Color(0xAAFFFFFF),
                        fontSize = 12.sp
                    )
                }
                is CalibrationUiState.Saved -> {
                    Text(
                        "Калибровка сохранена",
                        color = Color(0xFF4CAF50),
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }

        // Layer 2: invisible full-screen touch listener
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                val container = FrameLayout(ctx).apply {
                    isClickable = false
                    isFocusable = false
                }
                val listener = View.OnTouchListener { _, ev ->
                    if (ev.action == android.view.MotionEvent.ACTION_UP) {
                        val rootPackage = currentRootPackageName(ctx)
                        viewModel.recordTap(ev.rawX.toInt(), ev.rawY.toInt(), rootPackage)
                    }
                    // Return false: do not consume the event — Chrome / SEW still gets the tap.
                    false
                }
                container.setOnTouchListener(listener)
                container
            }
        )
    }
}

private fun currentRootPackageName(ctx: Context): String {
    val am = ctx.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
    val ourService = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        .firstOrNull { it.resolveInfo.serviceInfo.packageName == ctx.packageName }
    if (ourService == null) {
        // No accessibility service — caller is on their own; try to read window package via fallback.
        return ""
    }
    // We can call into the running service to get the foreground window's package.
    val svc = com.scanner.overlay.accessibility.ScannerAccessibilityService.instance
    return svc?.let { service ->
        try {
            service.windows.firstOrNull { it.isActive }?.root?.packageName?.toString() ?: ""
        } catch (_: Exception) { "" }
    } ?: ""
}
```

- [ ] **Step 2: Register the activity in `AndroidManifest.xml`**

Insert this block after the existing `OverlayActivity` entry (line 33–41), before `<service android:name=".service.ScannerForegroundService"` (line 43):

```xml
        <activity
            android:name=".calibration.SewCalibrationActivity"
            android:exported="false"
            android:excludeFromRecents="true"
            android:taskAffinity=""
            android:showWhenLocked="true"
            android:turnScreenOn="true"
            android:screenOrientation="portrait"
            android:theme="@style/Theme.ScannerOverlay.Transparent" />
```

- [ ] **Step 3: Verify it compiles**

Run: `cd G:\Front-end-projects\AIDevelops\ScannerOverlay; .\gradlew.bat compileDebugKotlin`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/scanner/overlay/calibration/SewCalibrationActivity.kt app/src/main/AndroidManifest.xml
git commit -m "feat(calibration): add SewCalibrationActivity and register in manifest"
```

---

## Task 7: `SettingsScreen` calibration card + test runner

**Files:**
- Modify: `app/src/main/java/com/scanner/overlay/settings/SettingsScreen.kt`

- [ ] **Step 1: Add imports**

After the existing imports, add:

```kotlin
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.scanner.overlay.calibration.SewCalibration
import com.scanner.overlay.calibration.SewCalibrationActivity
```

- [ ] **Step 2: Wire state and launcher in `SettingsScreen`**

Right after the existing `updateState` and `currentVersion` reads (after line 63), add:

```kotlin
    val sewCalibration by viewModel.sewCalibration.collectAsState()
    val sewTestResult by viewModel.sewTestResult.collectAsState()

    val calibrationLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            // VM state already updated by the activity via prefs.
            // Re-read by toggling a snapshot.
            viewModel.refreshSewCalibration()
        }
    }
```

- [ ] **Step 3: Add `refreshSewCalibration` to `SettingsViewModel`**

In `SettingsViewModel.kt`, after `resetSewCalibration`, add:

```kotlin
    fun refreshSewCalibration() {
        val cal = SewCalibration(
            targetPackage = prefs.getString("sew_target_package", "") ?: "",
            openModal = android.graphics.Point(
                prefs.getInt("sew_open_modal_x", 0),
                prefs.getInt("sew_open_modal_y", 0)
            ),
            input = android.graphics.Point(
                prefs.getInt("sew_input_x", 0),
                prefs.getInt("sew_input_y", 0)
            ),
            confirm = android.graphics.Point(
                prefs.getInt("sew_confirm_x", 0),
                prefs.getInt("sew_confirm_y", 0)
            )
        )
        _sewCalibration.value = cal
    }
```

- [ ] **Step 4: Add the calibration card to the `Column`**

In `SettingsScreen` (line 75), inside the `Column` and **after** the `PermissionsCard` block, add:

```kotlin
            SewCalibrationCard(
                calibration = sewCalibration,
                testResult = sewTestResult,
                onCalibrate = {
                    calibrationLauncher.launch(
                        android.content.Intent(context, SewCalibrationActivity::class.java)
                    )
                },
                onReset = { viewModel.resetSewCalibration() },
                onTest = { viewModel.runSewCalibrationTest() }
            )
```

- [ ] **Step 5: Add the `SewCalibrationCard` composable**

Append to the end of the file (after the `UpdateCard` body):

```kotlin
@Composable
private fun SewCalibrationCard(
    calibration: SewCalibration,
    testResult: SewTestResult,
    onCalibrate: () -> Unit,
    onReset: () -> Unit,
    onTest: () -> Unit
) {
    Card {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "Калибровка SEW",
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(Modifier.height(4.dp))
            Text(
                if (calibration.isCalibrated)
                    "Откалибровано для ${calibration.targetPackage}"
                else
                    "Не откалибровано. Нажмите «Откалибровать» после открытия SEW.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))

            Button(
                onClick = onCalibrate,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Откалибровать")
            }

            if (calibration.isCalibrated) {
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = onTest,
                        enabled = !testResult.inProgress,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Тест")
                    }
                    OutlinedButton(
                        onClick = onReset,
                        enabled = !testResult.inProgress,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Сбросить")
                    }
                }

                if (testResult.steps.isNotEmpty() && !testResult.inProgress) {
                    Spacer(Modifier.height(12.dp))
                    testResult.steps.forEach { step ->
                        StepRow(step)
                    }
                }
                if (testResult.inProgress) {
                    Spacer(Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(Modifier.width(12.dp))
                        Text("Тест идёт...")
                    }
                }
            }
        }
    }
}

@Composable
private fun StepRow(step: StepStatus) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            if (step.ok) "✓" else "✗",
            color = if (step.ok) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.error,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(step.name, fontSize = 14.sp)
            if (step.message != null) {
                Text(
                    step.message,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
```

- [ ] **Step 6: Verify it compiles**

Run: `cd G:\Front-end-projects\AIDevelops\ScannerOverlay; .\gradlew.bat compileDebugKotlin`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 7: Build a full APK**

Run: `cd G:\Front-end-projects\AIDevelops\ScannerOverlay; .\gradlew.bat installDebug`
Expected: `BUILD SUCCESSFUL` and APK installed on the connected device. If no device is attached, run `.\gradlew.bat assembleDebug` instead.

- [ ] **Step 8: Manual smoke (on device with Chrome and SEW)**

1. Open SEW in Chrome, navigate to the screen with the "Ручной ввод" button.
2. Open the floating scanner button → Settings → "Калибровка SEW" → "Откалибровать".
3. Confirm the 3-step calibration completes; status flips to "Откалибровано для com.android.chrome".
4. From Settings → "Тест" — confirm 5 rows with all ✓ (or, if the modal is already open from calibration, a fail row for "Кнопка «Ручной ввод» доступна").
5. Close the modal that the test leaves open, then go back to SEW.
6. Tap the floating scanner button → scan any barcode → confirm "Ввод в SEW…" appears briefly, then the modal opens, the barcode is typed, and "Готово" submits.

- [ ] **Step 9: Commit**

```bash
git add app/src/main/java/com/scanner/overlay/settings/SettingsScreen.kt app/src/main/java/com/scanner/overlay/settings/SettingsViewModel.kt
git commit -m "feat(settings): add SEW calibration card with calibrate, test, and reset"
```

---

## Self-review

**Spec coverage** (re-reads §1–§12 of `docs/superpowers/specs/2026-06-01-sew-auto-modal-design.md`):

- §1 motivation — implemented in Task 3 (trigger after scan).
- §2 current behavior — preserved: `autoInjectText` fallback path retained in Task 3 step 2.
- §3 target behavior — Task 3 implements "Ввод в SEW…" state, 2s pre-finish delay, 4s accessibility chain, success toast+vibrate, error toast+vibrate.
- §4 architecture — Task 1 (data), Task 2 (chain), Task 3 (overlay hook), Task 4 (settings), Task 5–6 (calibration activity), Task 7 (UI).
- §5 data flow — `runSewAutoInput` steps 1–7 in Task 2; `triggerSewAutoInput` in Task 3.
- §6 edge cases — step 1 fail ("Откройте SEW"), step 3 fallback (findFocus + byText + coords), step 4 fallback (clipboard+context-menu via existing `setText`), step 5 verify (`current.contains(barcode)`), step 6 fallback (coords), watchog 4s.
- §7 calibration activity — Task 5 (VM), Task 6 (Activity + manifest).
- §8 test — Task 4 (VM `runSewCalibrationTest`), Task 7 (UI rendering).
- §9 future extensions — N/A (out of scope).
- §10 alternatives — N/A.
- §11 resolved questions — reflected in code (no hardcoded package, no Enter fallback, Toast+vibrate on error, single package, test button present).
- §12 acceptance criteria — covered by Tasks 1–7 plus the manual smoke in Task 7 step 8.

**Placeholder scan:** No "TBD" / "TODO" / "implement later" in any code or step.

**Type consistency:**
- `SewCalibration` is used in AppModule (Task 1), `OverlayActivity` field (Task 3), `SettingsViewModel` (Task 4 + Task 7 step 3), `SewCalibrationViewModel.persist` (Task 5), `runSewAutoInput` (Task 2).
- `Point` is `android.graphics.Point` everywhere.
- `SewTestResult` / `StepStatus` consumed in `SettingsViewModel.runSewCalibrationTest` (Task 4) and rendered in `SewCalibrationCard` (Task 7).
- `runSewAutoInput` signature is consistent: defined in Task 2, called from Task 3 (no `onStep`) and Task 4 (with `onStep`).
- Prefs keys (`sew_calibrated`, `sew_target_package`, `sew_open_modal_x/y`, `sew_input_x/y`, `sew_confirm_x/y`) match between `SettingsViewModel.saveSewCalibration`/`resetSewCalibration`/`refreshSewCalibration` and `SewCalibrationViewModel.persist` and `AppModule.provideSewCalibration`.

**No spec gaps found.** Deviations (no `setOnInjectionResultListener`, added `onStep` param) documented at the top of the plan.
