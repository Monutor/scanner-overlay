package com.scanner.overlay.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Path
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import dagger.hilt.android.AndroidEntryPoint
import java.lang.ref.WeakReference
import com.scanner.overlay.BuildConfig
import com.scanner.overlay.calibration.SewCalibration

typealias SewInputCallback = (success: Boolean, message: String) -> Unit
typealias SewStepCallback = (name: String, ok: Boolean, message: String?) -> Unit

private fun AccessibilityNodeInfo.safeRecycle() {
    try { recycle() } catch (_: Exception) {}
}

@AndroidEntryPoint
class ScannerAccessibilityService : AccessibilityService() {

    private var pendingClipboardRestore: ClipData? = null
    private var lastInjectedText: String? = null

    @Volatile private var sewInputInProgress: Boolean = false
    @Volatile private var sewResultDelivered: Boolean = false
    private val watchdogHandler = Handler(Looper.getMainLooper())
    private val watchdogTimeoutMs: Long = 4_000L

    override fun onServiceConnected() {
        super.onServiceConnected()
        _instance = WeakReference(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        _instance.clear()
        pendingClipboardRestore?.let { original ->
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            val currentClip = clipboard.primaryClip
            if (currentClip != null && currentClip.getItemAt(0)?.text?.toString() == lastInjectedText) {
                clipboard.setPrimaryClip(original)
            }
        }
        pendingClipboardRestore = null
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {
        android.util.Log.w("ScannerAccessibility", "Service interrupted")
        mainHandler.removeCallbacksAndMessages(null)
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    fun autoInjectText(text: String): Boolean {
        if (BuildConfig.DEBUG) android.util.Log.d("ScannerAccessibility", "autoInjectText: text=$text, instance=${instance != null}, winCount=${windows.size}")
        val input = findFocusedOrEditable()
        if (input != null) {
            if (BuildConfig.DEBUG) android.util.Log.d("ScannerAccessibility", "autoInjectText: found node, pkg=${input.packageName}, editable=${input.isEditable}, focused=${input.isFocused}, visible=${input.isVisibleToUser}")
            setText(input, text)
            return true
        }
        if (BuildConfig.DEBUG) android.util.Log.d("ScannerAccessibility", "autoInjectText: node NOT found")
        return false
    }

    fun injectText(text: String) {
        if (BuildConfig.DEBUG) android.util.Log.d("ScannerAccessibility", "injectText scheduled: text=$text")
        mainHandler.removeCallbacksAndMessages(null)
        mainHandler.postDelayed({
            if (BuildConfig.DEBUG) android.util.Log.d("ScannerAccessibility", "injectText running: winCount=${windows.size}")
            val input = findFocusedOrEditable()
            if (input != null) {
                if (BuildConfig.DEBUG) android.util.Log.d("ScannerAccessibility", "injectText: found node, pkg=${input.packageName}")
                setText(input, text)
            } else {
                if (BuildConfig.DEBUG) android.util.Log.d("ScannerAccessibility", "injectText: node NOT found")
            }
        }, 600)
    }

    private fun findFocusedOrEditable(): AccessibilityNodeInfo? {
        if (BuildConfig.DEBUG) android.util.Log.d("ScannerAccessibility", "findFocusedOrEditable: winCount=${windows.size}")
        val focus = findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (BuildConfig.DEBUG) android.util.Log.d("ScannerAccessibility", "findFocus result: $focus")
        focus?.let {
            if (it.isEditable) {
                if (BuildConfig.DEBUG) android.util.Log.d("ScannerAccessibility", "findFocus is editable, returning")
                return it
            }
            if (BuildConfig.DEBUG) android.util.Log.d("ScannerAccessibility", "findFocus not editable (editable=${it.isEditable}, focused=${it.isFocused}), falling through to window scan")
            it.safeRecycle()
        }
        for ((i, win) in windows.withIndex()) {
            val root = win.root ?: continue
            if (BuildConfig.DEBUG) android.util.Log.d("ScannerAccessibility", "  window[$i] pkg=${root.packageName} className=${root.className}")
            val found = findInputField(root)
            if (found != null) {
                if (BuildConfig.DEBUG) android.util.Log.d("ScannerAccessibility", "  window[$i] -> FOUND editable field! className=${found.className}")
                return found
            }
        }
        if (BuildConfig.DEBUG) android.util.Log.d("ScannerAccessibility", "findFocusedOrEditable: no editable field found in any window")
        return null
    }

    private fun findInputField(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val queue = java.util.ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var depth = 0
        while (queue.isNotEmpty() && depth < 50) {
            repeat(queue.size) {
                val node = queue.poll() ?: return@repeat
                if (node.isEditable || node.isFocused) {
                    clearQueue(queue)
                    return node
                }
                for (i in 0 until node.childCount) {
                    val child = node.getChild(i) ?: continue
                    queue.add(child)
                }
                node.safeRecycle()
            }
            depth++
        }
        clearQueue(queue)
        return null
    }

    private fun findSendButton(
        root: AccessibilityNodeInfo,
        targets: List<String>
    ): AccessibilityNodeInfo? {
        val queue = java.util.ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var depth = 0
        while (queue.isNotEmpty() && depth < 50) {
            repeat(queue.size) {
                val node = queue.poll() ?: return@repeat
                val cd = node.contentDescription?.toString() ?: ""
                if (cd.isNotEmpty() && targets.any { cd.contains(it, ignoreCase = true) }) {
                    clearQueue(queue)
                    return node
                }
                val nodeText = node.text?.toString() ?: ""
                if (nodeText.isNotEmpty() && targets.any { nodeText.contains(it, ignoreCase = true) }) {
                    clearQueue(queue)
                    return node
                }
                for (i in 0 until node.childCount) {
                    val child = node.getChild(i) ?: continue
                    queue.add(child)
                }
                node.safeRecycle()
            }
            depth++
        }
        clearQueue(queue)
        return null
    }

    private fun findNodeContaining(root: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
        val queue = java.util.ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var depth = 0
        while (queue.isNotEmpty() && depth < 50) {
            repeat(queue.size) {
                val node = queue.poll() ?: return@repeat
                val nodeText = node.text?.toString() ?: ""
                val contentDesc = node.contentDescription?.toString() ?: ""
                if (nodeText.contains(text, ignoreCase = true) || contentDesc.contains(text, ignoreCase = true)) {
                    clearQueue(queue)
                    return node
                }
                for (i in 0 until node.childCount) {
                    val child = node.getChild(i) ?: continue
                    queue.add(child)
                }
                node.safeRecycle()
            }
            depth++
        }
        clearQueue(queue)
        return null
    }

    private fun clearQueue(queue: java.util.ArrayDeque<AccessibilityNodeInfo>) {
        var node: AccessibilityNodeInfo? = queue.poll()
        while (node != null) {
            node.safeRecycle()
            node = queue.poll()
        }
    }

    private fun setText(node: AccessibilityNodeInfo, text: String) {
        lastInjectedText = text
        val args = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                text
            )
        }
        node.refresh()
        if (node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
            val textToSend = text
            mainHandler.postDelayed({
                pressEnter(node)
                node.safeRecycle()
                mainHandler.postDelayed({
                    findAndClickSendButton(2000, textToSend)
                }, 400)
            }, 300)
            return
        }

        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        val original = clipboard.primaryClip
        pendingClipboardRestore = original
        clipboard.setPrimaryClip(ClipData.newPlainText("barcode", text))

        node.refresh()
        node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        node.safeRecycle()

        mainHandler.postDelayed({
            pasteFromContextMenu()
            mainHandler.postDelayed({
                val pastedField = findFocusedOrEditable()
                if (pastedField != null) {
                    pressEnter(pastedField)
                    pastedField.safeRecycle()
                }
                pendingClipboardRestore = null
                original?.let { clipboard.setPrimaryClip(it) }
            }, 3000)
        }, 250)
    }

    private fun pressEnter(node: AccessibilityNodeInfo?) {
        if (node != null) {
            node.refresh()
            if (Build.VERSION.SDK_INT >= 33) {
                try {
                    if (node.performAction(
                            AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id
                        )
                    ) {
                        return
                    }
                } catch (_: Exception) {}
            }
            // Fallback: blur the field to trigger keyboard's "Done" action
            try {
                node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            } catch (_: Exception) {}
        }
    }

    private fun pasteFromContextMenu() {
        val focused = findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focused != null) {
            focused.refresh()
            focused.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
            focused.safeRecycle()
        }
        mainHandler.postDelayed({
            findAndClickPaste()
        }, 400)
    }

    private fun findAndClickPaste() {
        val targets = listOf("Вставить", "Paste", "Встав")
        for (win in windows) {
            val root = win.root ?: continue
            for (text in targets) {
                val node = findNodeContaining(root, text)
                if (node != null && node.isClickable) {
                    node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    node.safeRecycle()
                    return
                }
                node?.safeRecycle()
            }
        }
    }

    private fun findAndClickSendButton(timeoutMs: Long, barcode: String) {
        val targets = listOf("Send", "Отправить", "Submit", "Готово", "Done")
        mainHandler.postDelayed({
            val focusedNode = findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            if (focusedNode != null) {
                val text = focusedNode.text?.toString() ?: ""
                focusedNode.safeRecycle()
                if (text.contains(barcode)) {
                    for (win in windows) {
                        val root = win.root ?: continue
                        val sendBtn = findSendButton(root, targets)
                        if (sendBtn != null && sendBtn.isClickable) {
                            sendBtn.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                            sendBtn.safeRecycle()
                            return@postDelayed
                        }
                        sendBtn?.safeRecycle()
                    }
                }
            }
        }, timeoutMs)
    }

    companion object {
        private var _instance: WeakReference<ScannerAccessibilityService?> = WeakReference(null)
        val instance: ScannerAccessibilityService?
            get() = _instance.get()
    }

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
        sewResultDelivered = false
        val effectiveBarcode = if (testMode) "TEST_CALIBRATION" else barcode

        step1FindWindow(calibration, testMode, effectiveBarcode, onResult, onStep)
    }

    fun isTargetWindowActive(targetPackage: String): Boolean {
        return findTargetWindow(targetPackage) != null
    }

    fun debugActiveWindows(): List<String> {
        return windows.filter { it.isActive }
            .mapNotNull { it.root?.packageName?.toString() }
            .distinct()
    }

    fun cancelOngoingSewInput(message: String = "Отменено") {
        if (!sewInputInProgress) return
        watchdogHandler.removeCallbacksAndMessages(null)
        sewInputInProgress = false
    }

    private fun armWatchdog(onResult: SewInputCallback) {
        watchdogHandler.removeCallbacksAndMessages(null)
        watchdogHandler.postDelayed({
            if (sewInputInProgress && !sewResultDelivered) {
                releaseWatchdogAndFinish(onResult, false, "Таймаут")
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
        val immediate = findTargetWindow(calibration.targetPackage)
        if (immediate != null) {
            onStep?.invoke("SEW найден", true, null)
            armWatchdog(onResult)
            mainHandler.postDelayed({
                if (!sewInputInProgress) return@postDelayed
                step2ClickOpenModal(calibration, testMode, effectiveBarcode, onResult, onStep)
            }, 1000L)
            return
        }
        pollForTargetWindow(calibration, testMode, effectiveBarcode, onResult, onStep, attemptsLeft = 30)
    }

    private fun findTargetWindow(targetPackage: String): AccessibilityWindowInfo? {
        return windows.firstOrNull {
            it.root?.packageName == targetPackage && it.isActive
        }
    }

    private fun pollForTargetWindow(
        calibration: SewCalibration,
        testMode: Boolean,
        effectiveBarcode: String,
        onResult: SewInputCallback,
        onStep: SewStepCallback?,
        attemptsLeft: Int
    ) {
        if (attemptsLeft <= 0) {
            val activePkgs = windows.filter { it.isActive }
                .mapNotNull { it.root?.packageName?.toString() }
                .distinct()
                .joinToString(", ")
            val msg = if (activePkgs.isBlank()) {
                "Окно ${calibration.targetPackage} не появилось"
            } else {
                "Окно ${calibration.targetPackage} не найдено. Активные: $activePkgs"
            }
            releaseWatchdogAndFinish(onResult, false, msg)
            return
        }
        mainHandler.postDelayed({
            val t = findTargetWindow(calibration.targetPackage)
            if (t != null) {
                onStep?.invoke("SEW найден", true, null)
                armWatchdog(onResult)
                mainHandler.postDelayed({
                    if (!sewInputInProgress) return@postDelayed
                    step2ClickOpenModal(calibration, testMode, effectiveBarcode, onResult, onStep)
                }, 1000L)
            } else {
                pollForTargetWindow(calibration, testMode, effectiveBarcode, onResult, onStep, attemptsLeft - 1)
            }
        }, 300L)
    }

    private fun step2ClickOpenModal(
        calibration: SewCalibration,
        testMode: Boolean,
        effectiveBarcode: String,
        onResult: SewInputCallback,
        onStep: SewStepCallback?
    ) {
        tryOpenModal(calibration, calibration.openModal, testMode, effectiveBarcode, onResult, onStep, attemptsLeft = 3)
    }

    private fun tryOpenModal(
        calibration: SewCalibration,
        point: android.graphics.Point,
        testMode: Boolean,
        effectiveBarcode: String,
        onResult: SewInputCallback,
        onStep: SewStepCallback?,
        attemptsLeft: Int
    ) {
        dispatchGesture(
            GestureDescription.Builder()
                .addStroke(
                    GestureDescription.StrokeDescription(
                        tapPath(point.x.toFloat(), point.y.toFloat()),
                        0L,
                        50L
                    )
                )
                .build(),
            null, null
        )
        mainHandler.postDelayed({
            armWatchdog(onResult)
            val input = findInputFieldAcrossWindows()
            if (input != null) {
                input.safeRecycle()
                onStep?.invoke("Кнопка «Ручной ввод» доступна", true, null)
                step3FindInput(calibration, testMode, effectiveBarcode, onResult, onStep)
            } else if (attemptsLeft > 1) {
                tryOpenModal(calibration, point, testMode, effectiveBarcode, onResult, onStep, attemptsLeft - 1)
            } else {
                val msg = "Модалка не открылась после 3 тапов (${point.x}, ${point.y})"
                onStep?.invoke("Кнопка «Ручной ввод» доступна", false, msg)
                releaseWatchdogAndFinish(onResult, false, msg)
            }
        }, 1000L)
    }

    private fun findInputFieldAcrossWindows(): AccessibilityNodeInfo? {
        val fromFocus = findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (fromFocus != null && fromFocus.isEditable) return fromFocus
        fromFocus?.safeRecycle()

        val fromPlaceholder = findInputByPlaceholder("Штрих-код")
        if (fromPlaceholder != null) return fromPlaceholder

        for (win in windows) {
            if (!win.isActive) continue
            val root = win.root ?: continue
            val editable = findFirstEditable(root)
            if (editable != null) return editable
        }
        return null
    }

    private fun findFirstEditable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val queue = java.util.ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var depth = 0
        while (queue.isNotEmpty() && depth < 50) {
            repeat(queue.size) {
                val node = queue.poll() ?: return@repeat
                if (node.isEditable) {
                    clearQueue(queue)
                    return node
                }
                for (i in 0 until node.childCount) {
                    val child = node.getChild(i) ?: continue
                    queue.add(child)
                }
                node.safeRecycle()
            }
            depth++
        }
        clearQueue(queue)
        return null
    }

    private fun step3FindInput(
        calibration: SewCalibration,
        testMode: Boolean,
        effectiveBarcode: String,
        onResult: SewInputCallback,
        onStep: SewStepCallback?
    ) {
        val input = findInputFieldAcrossWindows()
        if (input != null) {
            onStep?.invoke("Поле ввода найдено", true, null)
            armWatchdog(onResult)
            step4SetText(input, effectiveBarcode, calibration, testMode, onResult, onStep)
            return
        }
        releaseWatchdogAndFinish(onResult, false, "Поле ввода не найдено")
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
        calibration: SewCalibration,
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
                    step4SetText(pasted, barcode, calibration, testMode, onResult, onStep)
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
                step6ClickConfirm(calibration, testMode, onResult, onStep)
            } else {
                step5Verify(inputNode, barcode, calibration, onResult, onStep)
            }
        }, 200L)
    }

    private fun step5Verify(
        inputNode: AccessibilityNodeInfo,
        barcode: String,
        calibration: SewCalibration,
        onResult: SewInputCallback,
        onStep: SewStepCallback?
    ) {
        try {
            inputNode.refresh()
            val current = inputNode.text?.toString() ?: ""
            if (BuildConfig.DEBUG) android.util.Log.d("ScannerAccessibility", "step5Verify: current='$current' expected='$barcode' editable=${inputNode.isEditable} focused=${inputNode.isFocused}")
            if (!current.contains(barcode)) {
                inputNode.safeRecycle()
                releaseWatchdogAndFinish(onResult, false, "Ввод не зафиксирован")
                return
            }
            inputNode.safeRecycle()
        } catch (_: Exception) {
            inputNode.safeRecycle()
        }
        onStep?.invoke("Ввод работает", true, null)
        armWatchdog(onResult)
        closeKeyboardAndClickConfirm(calibration, testMode = false, onResult, onStep)
    }

    private fun closeKeyboardAndClickConfirm(
        calibration: SewCalibration,
        testMode: Boolean,
        onResult: SewInputCallback,
        onStep: SewStepCallback?
    ) {
        val keyboardOpen = windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
        if (BuildConfig.DEBUG) android.util.Log.d("ScannerAccessibility", "closeKeyboardAndClickConfirm: keyboardOpen=$keyboardOpen")
        if (keyboardOpen) {
            performGlobalAction(GLOBAL_ACTION_BACK)
            if (BuildConfig.DEBUG) android.util.Log.d("ScannerAccessibility", "closeKeyboardAndClickConfirm: GLOBAL_ACTION_BACK sent to dismiss keyboard")
        }
        mainHandler.postDelayed({
            if (!sewInputInProgress) return@postDelayed
            step6ClickConfirm(calibration, testMode, onResult, onStep)
        }, 350L)
    }

    private fun step6ClickConfirm(
        calibration: SewCalibration,
        testMode: Boolean,
        onResult: SewInputCallback,
        onStep: SewStepCallback?
    ) {
        val buttonTexts = listOf("Готово", "Done", "Submit", "Отправить", "Send")
        var textNode: AccessibilityNodeInfo? = null
        for (win in windows) {
            val root = win.root ?: continue
            textNode = findSendButton(root, buttonTexts)
            if (textNode != null) break
        }
        if (textNode == null) {
            if (testMode) onStep?.invoke("Кнопка «Готово» найдена", false, "Текст не найден")
            releaseWatchdogAndFinish(onResult, false, "Кнопка «Готово» не найдена")
            return
        }
        textNode.safeRecycle()

        if (testMode) {
            onStep?.invoke("Кнопка «Готово» найдена", true, null)
            releaseWatchdogAndFinish(onResult, true, "Тест пройден")
            return
        }

        clickConfirmAtCoords(calibration, onResult)
    }

    private fun clickConfirmAtCoords(
        calibration: SewCalibration,
        onResult: SewInputCallback
    ) {
        val x = calibration.confirm.x.toFloat()
        val y = calibration.confirm.y.toFloat()
        if (BuildConfig.DEBUG) android.util.Log.d("ScannerAccessibility", "Готово: tap at calibration coords ($x, $y)")
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(tapPath(x, y), 0L, 100L))
            .build()
        val dispatched = dispatchGesture(gesture, null, null)
        if (BuildConfig.DEBUG) android.util.Log.d("ScannerAccessibility", "Готово dispatchGesture: dispatched=$dispatched at ($x, $y)")
        if (!dispatched) {
            releaseWatchdogAndFinish(onResult, false, "Не удалось нажать Готово")
            return
        }
        mainHandler.postDelayed({
            if (!sewInputInProgress) return@postDelayed
            val stillThere = isButtonStillPresent()
            if (BuildConfig.DEBUG) android.util.Log.d("ScannerAccessibility", "Готово verify after tap: stillThere=$stillThere")
            if (!stillThere) {
                releaseWatchdogAndFinish(onResult, true, "Готово")
            } else {
                releaseWatchdogAndFinish(onResult, false, "Кнопка не нажалась (тап не сработал)")
            }
        }, 600L)
    }

    private fun isButtonStillPresent(): Boolean {
        val buttonTexts = listOf("Готово", "Done", "Submit", "Отправить", "Send")
        for (win in windows) {
            if (!win.isActive) continue
            val root = win.root ?: continue
            val found = findSendButton(root, buttonTexts)
            if (found != null) {
                found.safeRecycle()
                return true
            }
        }
        return false
    }

    private fun releaseWatchdogAndFinish(onResult: SewInputCallback, ok: Boolean, message: String) {
        watchdogHandler.removeCallbacksAndMessages(null)
        sewInputInProgress = false
        if (sewResultDelivered) return
        sewResultDelivered = true
        onResult(ok, message)
    }

    private fun tapPath(x: Float, y: Float): Path {
        return Path().apply {
            moveTo(x, y)
            lineTo(x, y)
        }
    }
}
