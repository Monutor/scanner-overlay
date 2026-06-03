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
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import dagger.hilt.android.AndroidEntryPoint
import java.lang.ref.WeakReference
import com.scanner.overlay.BuildConfig
import com.scanner.overlay.calibration.SewCalibration
import com.scanner.overlay.calibration.SupportedBrowsers

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
    @Volatile private var pendingSewResult: SewInputCallback? = null
    @Volatile private var lastEffectiveTarget: String = ""
    private val watchdogHandler = Handler(Looper.getMainLooper())
    private val watchdogTimeoutMs: Long = 6_000L

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
        if (sewInputInProgress && !sewResultDelivered) {
            val cb = pendingSewResult
            pendingSewResult = null
            if (cb != null) {
                sewResultDelivered = true
                sewInputInProgress = false
                cb(false, "Сервис остановлен")
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {
        android.util.Log.w("ScannerAccessibility", "Service interrupted")
        mainHandler.removeCallbacksAndMessages(null)
        watchdogHandler.removeCallbacksAndMessages(null)
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
                if (BuildConfig.DEBUG) android.util.Log.d("ScannerAccessibility", "findFocus is editable, returning pkg=${it.packageName}")
                return it
            }
            if (BuildConfig.DEBUG) android.util.Log.d("ScannerAccessibility", "findFocus not editable (editable=${it.isEditable}, focused=${it.isFocused}, pkg=${it.packageName}), falling through to window scan")
            it.safeRecycle()
        }
        val ownPkg = BuildConfig.APPLICATION_ID
        for ((i, win) in windows.withIndex()) {
            if (!win.isActive) {
                if (BuildConfig.DEBUG) android.util.Log.d("ScannerAccessibility", "  window[$i] not active, skip")
                continue
            }
            val root = win.root ?: continue
            val pkg = root.packageName?.toString() ?: ""
            if (pkg == ownPkg) {
                if (BuildConfig.DEBUG) android.util.Log.d("ScannerAccessibility", "  window[$i] own package ($pkg), skip")
                root.safeRecycle()
                continue
            }
            if (BuildConfig.DEBUG) android.util.Log.d("ScannerAccessibility", "  window[$i] active pkg=$pkg className=${root.className}")
            val found = findInputField(root)
            if (found != null) {
                if (BuildConfig.DEBUG) android.util.Log.d("ScannerAccessibility", "  window[$i] -> FOUND editable field! pkg=${found.packageName} className=${found.className}")
                return found
            }
            root.safeRecycle()
        }
        if (BuildConfig.DEBUG) android.util.Log.d("ScannerAccessibility", "findFocusedOrEditable: no editable field found in any active window")
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
        val setTextOk = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        if (BuildConfig.DEBUG) android.util.Log.d("ScannerAccessibility", "setText(legacy): ACTION_SET_TEXT ok=$setTextOk pkg=${node.packageName} text='$text'")
        if (setTextOk) {
            val textToSend = text
            mainHandler.postDelayed({
                pressEnter(node)
                node.safeRecycle()
                mainHandler.postDelayed({
                    findAndClickSendButton(500, textToSend)
                }, 300)
            }, 200)
            return
        }

        if (BuildConfig.DEBUG) android.util.Log.d("ScannerAccessibility", "setText(legacy): ACTION_SET_TEXT FAILED, using clipboard+contextMenu fallback")
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        val original = clipboard.primaryClip
        pendingClipboardRestore = original
        clipboard.setPrimaryClip(ClipData.newPlainText("barcode", text))

        node.refresh()
        val clickOk = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        val focusOk = node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        if (BuildConfig.DEBUG) android.util.Log.d("ScannerAccessibility", "setText(legacy): ACTION_CLICK ok=$clickOk ACTION_FOCUS ok=$focusOk")
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
                val currentClip = clipboard.primaryClip
                val stillOurs = currentClip != null &&
                    currentClip.itemCount > 0 &&
                    currentClip.getItemAt(0)?.text?.toString() == text
                if (stillOurs) {
                    original?.let { clipboard.setPrimaryClip(it) }
                } else if (BuildConfig.DEBUG) {
                    android.util.Log.d("ScannerAccessibility", "setText fallback: clipboard changed by user, skip restore")
                }
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
        if (BuildConfig.DEBUG) android.util.Log.d("ScannerAccessibility", "pasteFromContextMenu: focused=${focused != null}")
        if (focused != null) {
            focused.refresh()
            val longClickOk = focused.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
            if (BuildConfig.DEBUG) android.util.Log.d("ScannerAccessibility", "pasteFromContextMenu: ACTION_LONG_CLICK ok=$longClickOk")
            focused.safeRecycle()
        }
        mainHandler.postDelayed({
            findAndClickPaste()
        }, 400)
    }

    private fun findAndClickPaste() {
        val targets = listOf("Вставить", "Paste", "Встав")
        if (BuildConfig.DEBUG) android.util.Log.d("ScannerAccessibility", "findAndClickPaste: searching ${windows.size} windows for $targets")
        for (win in windows) {
            val root = win.root ?: continue
            for (text in targets) {
                val node = findNodeContaining(root, text)
                if (node != null && node.isClickable) {
                    val pkg = win.root?.packageName?.toString() ?: "<null>"
                    if (BuildConfig.DEBUG) android.util.Log.d("ScannerAccessibility", "findAndClickPaste: found '$text' in pkg=$pkg, clicking")
                    node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    node.safeRecycle()
                    root.safeRecycle()
                    return
                }
                node?.safeRecycle()
            }
            root.safeRecycle()
        }
        if (BuildConfig.DEBUG) {
            val winSummary = windows.mapNotNull { it.root?.packageName?.toString() }.distinct().joinToString(",")
            android.util.Log.d("ScannerAccessibility", "findAndClickPaste: NOT FOUND. activePackages=[$winSummary]")
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
                        root.safeRecycle()
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
        val effectiveBarcode: String
        val effectiveTarget: String
        synchronized(this) {
            if (sewInputInProgress) {
                onResult(false, "Подождите завершения ввода")
                return
            }
            if (!calibration.isCalibrated) {
                onResult(false, "Калибровка не выполнена")
                return
            }
            val detected = detectActiveSupportedBrowser()
            effectiveTarget = detected ?: calibration.targetPackage
            if (effectiveTarget.isEmpty()) {
                onResult(false, "Откройте SEW в поддерживаемом браузере")
                return
            }
            logEnvironmentSnapshot("env.start", effectiveTarget, detected, calibration)
            if (BuildConfig.DEBUG) android.util.Log.d(
                "ScannerAccessibility",
                "runSewAutoInput: effectiveTarget=$effectiveTarget (detected=$detected, configured=${calibration.targetPackage})"
            )
            sewInputInProgress = true
            sewResultDelivered = false
            pendingSewResult = onResult
            lastEffectiveTarget = effectiveTarget
            effectiveBarcode = if (testMode) "TEST_CALIBRATION" else barcode
        }

        mainHandler.postDelayed({
            if (!sewInputInProgress) return@postDelayed
            step1FindWindow(calibration, effectiveTarget, testMode, effectiveBarcode, onResult, onStep)
        }, 500L)
    }

    private fun detectActiveSupportedBrowser(): String? {
        for (win in windows) {
            if (!win.isActive) continue
            if (win.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
            val pkg = win.root?.packageName?.toString() ?: continue
            if (pkg in SupportedBrowsers.SUPPORTED_PACKAGES) {
                return pkg
            }
        }
        return null
    }

    fun isTargetWindowActive(targetPackage: String): Boolean {
        return findTargetWindow(targetPackage) != null
    }

    fun debugActiveWindows(): List<String> {
        return windows.filter { it.isActive }
            .mapNotNull { it.root?.packageName?.toString() }
            .distinct()
    }

    fun ensureTargetWindowActive(
        activity: android.app.Activity,
        targetPackage: String,
        onResult: (active: Boolean) -> Unit
    ) {
        if (targetPackage.isEmpty()) { onResult(true); return }
        if (isTargetWindowActive(targetPackage)) {
            android.util.Log.d("ScannerAccessibilityService", "ensureTarget: $targetPackage already active")
            onResult(true)
            return
        }
        android.util.Log.d(
            "ScannerAccessibilityService",
            "ensureTarget: $targetPackage not in windows; windows=${debugActiveWindows()}"
        )
        val launchIntent = android.content.Intent(android.content.Intent.ACTION_MAIN).apply {
            setPackage(targetPackage)
            addFlags(
                android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                    android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            )
        }
        try {
            activity.startActivity(launchIntent)
            android.util.Log.d("ScannerAccessibilityService", "ensureTarget: startActivity sent")
        } catch (e: Exception) {
            android.util.Log.w(
                "ScannerAccessibilityService",
                "ensureTarget: launch failed for $targetPackage: ${e.message}"
            )
        }
        pollForTargetActive(targetPackage, attemptsLeft = 15, onResult = onResult)
    }

    private fun pollForTargetActive(
        targetPackage: String,
        attemptsLeft: Int,
        onResult: (Boolean) -> Unit
    ) {
        if (attemptsLeft <= 0) {
            android.util.Log.d(
                "ScannerAccessibilityService",
                "ensureTarget: poll exhausted for $targetPackage; final windows=${debugActiveWindows()}"
            )
            onResult(false)
            return
        }
        if (isTargetWindowActive(targetPackage)) {
            android.util.Log.d(
                "ScannerAccessibilityService",
                "ensureTarget: $targetPackage active after ${(15 - attemptsLeft) * 200}ms"
            )
            onResult(true)
            return
        }
        mainHandler.postDelayed({
            pollForTargetActive(targetPackage, attemptsLeft - 1, onResult)
        }, 200L)
    }

    fun cancelOngoingSewInput(message: String = "Отменено") {
        if (!sewInputInProgress) return
        watchdogHandler.removeCallbacksAndMessages(null)
        sewInputInProgress = false
        val cb = pendingSewResult
        pendingSewResult = null
        if (cb != null && !sewResultDelivered) {
            sewResultDelivered = true
            cb(false, message)
        }
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
        targetPackage: String?,
        testMode: Boolean,
        effectiveBarcode: String,
        onResult: SewInputCallback,
        onStep: SewStepCallback?
    ) {
        armWatchdog(onResult)
        logWindowsSnapshot("step1.immediate", targetPackage)
        val immediate = findTargetWindow(targetPackage)
        if (immediate != null) {
            onStep?.invoke("SEW найден", true, null)
            armWatchdog(onResult)
            mainHandler.postDelayed({
                if (!sewInputInProgress) return@postDelayed
                step2ClickOpenModal(calibration, testMode, effectiveBarcode, onResult, onStep)
            }, 1000L)
            return
        }
        pollForTargetWindow(calibration, targetPackage, testMode, effectiveBarcode, onResult, onStep, attemptsLeft = 30)
    }

    private fun findTargetWindow(targetPackage: String?): AccessibilityWindowInfo? {
        val byPreferred = if (targetPackage.isNullOrEmpty()) null else windows.firstOrNull {
            it.root?.packageName == targetPackage && it.isActive
        }
        if (byPreferred != null) {
            if (lastEffectiveTarget != targetPackage) lastEffectiveTarget = targetPackage ?: ""
            return byPreferred
        }
        val byFallback = windows.firstOrNull { w ->
            w.isActive && SupportedBrowsers.SUPPORTED_PACKAGES.contains(w.root?.packageName?.toString())
        }
        if (byFallback != null) {
            val actualPkg = byFallback.root?.packageName?.toString() ?: ""
            if (lastEffectiveTarget != actualPkg) {
                lastEffectiveTarget = actualPkg
                if (BuildConfig.DEBUG) android.util.Log.d(
                    "ScannerAccessibility",
                    "findTargetWindow: fallback preferred='$targetPackage' → actual='$actualPkg'"
                )
            }
        }
        return byFallback
    }

    private fun logWindowsSnapshot(tag: String, targetPackage: String?) {
        if (!BuildConfig.DEBUG) return
        val snap = StringBuilder()
        snap.append("[$tag] target=").append(targetPackage).append(" count=").append(windows.size)
        for ((i, win) in windows.withIndex()) {
            val root = win.root
            val pkg = root?.packageName?.toString() ?: "<null>"
            val cls = root?.className?.toString() ?: "<null>"
            snap.append(" | w[").append(i).append("] active=").append(win.isActive)
                .append(" type=").append(win.type)
                .append(" pkg=").append(pkg)
                .append(" cls=").append(cls)
        }
        android.util.Log.d("ScannerAccessibility", snap.toString())
    }

    private fun logEnvironmentSnapshot(
        tag: String,
        effectiveTarget: String,
        detected: String?,
        calibration: SewCalibration
    ) {
        if (!BuildConfig.DEBUG) return
        val a11yEnabled = try {
            Settings.Secure.getInt(contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED)
        } catch (_: Exception) { -1 }
        val enabledSvcs = try {
            Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
        } catch (_: Exception) { "<err>" }
        val containsOurService = enabledSvcs.contains(packageName)
        android.util.Log.d(
            "ScannerAccessibility",
            "[$tag] device=${Build.BRAND}/${Build.MODEL} sdk=${Build.VERSION.SDK_INT} " +
                "appPkg=$packageName a11yEnabled=$a11yEnabled ourServiceActive=$containsOurService " +
                "enabledSvcs=$enabledSvcs " +
                "effectiveTarget=$effectiveTarget detected=$detected configured=${calibration.targetPackage} " +
                "isCalibrated=${calibration.isCalibrated} " +
                "openModal=(${calibration.openModal.x},${calibration.openModal.y}) " +
                "confirm=(${calibration.confirm.x},${calibration.confirm.y})"
        )
    }

    private fun pollForTargetWindow(
        calibration: SewCalibration,
        targetPackage: String?,
        testMode: Boolean,
        effectiveBarcode: String,
        onResult: SewInputCallback,
        onStep: SewStepCallback?,
        attemptsLeft: Int
    ) {
        if (attemptsLeft <= 0) {
            logWindowsSnapshot("step1.poll.exhausted", targetPackage)
            val activePkgs = windows.filter { it.isActive }
                .mapNotNull { it.root?.packageName?.toString() }
                .distinct()
                .joinToString(", ")
            val msg = if (activePkgs.isBlank()) {
                "Окно $targetPackage не появилось"
            } else {
                "Окно $targetPackage не найдено. Активные: $activePkgs"
            }
            releaseWatchdogAndFinish(onResult, false, msg)
            return
        }
        mainHandler.postDelayed({
            val t = findTargetWindow(targetPackage)
            if (t != null) {
                logWindowsSnapshot("step1.poll.found", targetPackage)
                onStep?.invoke("SEW найден", true, null)
                armWatchdog(onResult)
                mainHandler.postDelayed({
                    if (!sewInputInProgress) return@postDelayed
                    step2ClickOpenModal(calibration, testMode, effectiveBarcode, onResult, onStep)
                }, 1000L)
            } else {
                if (attemptsLeft == 30 || attemptsLeft % 10 == 0) {
                    logWindowsSnapshot("step1.poll.tick$attemptsLeft", targetPackage)
                }
                if (BuildConfig.DEBUG && (attemptsLeft == 25 || attemptsLeft == 15 || attemptsLeft == 5)) {
                    val activeDetails = windows.filter { it.isActive }.joinToString("; ") { w ->
                        val pkg = w.root?.packageName?.toString() ?: "<null>"
                        val cls = w.root?.className?.toString()?.substringAfterLast('.') ?: "<null>"
                        "type=${w.type} pkg=$pkg cls=$cls"
                    }
                    android.util.Log.d(
                        "ScannerAccessibility",
                        "[step1.poll.detail.t$attemptsLeft] activeWindows=$activeDetails"
                    )
                }
                pollForTargetWindow(calibration, targetPackage, testMode, effectiveBarcode, onResult, onStep, attemptsLeft - 1)
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
        if (BuildConfig.DEBUG) {
            android.util.Log.d(
                "ScannerAccessibility",
                "[step2.beforeTap] target=$lastEffectiveTarget coords=(${calibration.openModal.x},${calibration.openModal.y})"
            )
            logWindowsSnapshot("step2.beforeTap", lastEffectiveTarget)
        }
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
        val accepted = dispatchGesture(
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
        if (BuildConfig.DEBUG) android.util.Log.d("ScannerAccessibility", "tryOpenModal: dispatchGesture accepted=$accepted point=(${point.x},${point.y}) attemptsLeft=$attemptsLeft")
        if (!accepted) {
            if (attemptsLeft > 1) {
                mainHandler.postDelayed({
                    if (!sewInputInProgress) return@postDelayed
                    tryOpenModal(calibration, point, testMode, effectiveBarcode, onResult, onStep, attemptsLeft - 1)
                }, 300L)
                return
            }
            val msg = "Жест не принят системой (тап не дошёл)"
            onStep?.invoke("Кнопка «Ручной ввод» доступна", false, msg)
            releaseWatchdogAndFinish(onResult, false, msg)
            return
        }
        if (BuildConfig.DEBUG) logWindowsSnapshot("step2.afterTap.attempts${3 - attemptsLeft + 1}", lastEffectiveTarget)
        mainHandler.postDelayed({
            armWatchdog(onResult)
            val input = findInputFieldAcrossWindows()
            if (BuildConfig.DEBUG) {
                val found = input != null
                val pkg = input?.packageName?.toString() ?: "<null>"
                android.util.Log.d(
                    "ScannerAccessibility",
                    "[step2.findInput] found=$found pkg=$pkg attemptsLeft=$attemptsLeft"
                )
            }
            if (input != null) {
                input.safeRecycle()
                onStep?.invoke("Кнопка «Ручной ввод» доступна", true, null)
                step3FindInput(calibration, testMode, effectiveBarcode, onResult, onStep)
            } else if (attemptsLeft > 1) {
                tryOpenModal(calibration, point, testMode, effectiveBarcode, onResult, onStep, attemptsLeft - 1)
            } else {
                if (BuildConfig.DEBUG) logWindowsSnapshot("step2.noModalAfter3Taps", lastEffectiveTarget)
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
            root.safeRecycle()
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
                if (found.isEditable) {
                    root.safeRecycle()
                    return found
                }
                val parent = found.parent
                found.safeRecycle()
                if (parent != null && parent.isEditable) {
                    root.safeRecycle()
                    return parent
                }
                parent?.safeRecycle()
            }
            root.safeRecycle()
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
        if (BuildConfig.DEBUG) android.util.Log.d("ScannerAccessibility", "step4SetText: ACTION_SET_TEXT ok=$ok pkg=${inputNode.packageName} text='$barcode' editable=${inputNode.isEditable} focused=${inputNode.isFocused}")
        if (!ok) {
            if (BuildConfig.DEBUG) android.util.Log.d("ScannerAccessibility", "step4SetText: SET_TEXT FAILED, using clipboard fallback")
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            val original = clipboard.primaryClip
            pendingClipboardRestore = original
            clipboard.setPrimaryClip(ClipData.newPlainText("barcode", barcode))
            inputNode.refresh()
            val focusOk = inputNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            if (BuildConfig.DEBUG) android.util.Log.d("ScannerAccessibility", "step4SetText: ACTION_FOCUS ok=$focusOk")
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
            waitForKeyboardClosed(calibration, testMode, onResult, onStep, attemptsLeft = 5)
        } else {
            mainHandler.postDelayed({
                if (!sewInputInProgress) return@postDelayed
                step6ClickConfirm(calibration, testMode, onResult, onStep)
            }, 100L)
        }
    }

    private fun waitForKeyboardClosed(
        calibration: SewCalibration,
        testMode: Boolean,
        onResult: SewInputCallback,
        onStep: SewStepCallback?,
        attemptsLeft: Int
    ) {
        if (attemptsLeft <= 0) {
            if (BuildConfig.DEBUG) android.util.Log.d("ScannerAccessibility", "waitForKeyboardClosed: gave up after retries, clicking confirm anyway")
            mainHandler.postDelayed({
                if (!sewInputInProgress) return@postDelayed
                step6ClickConfirm(calibration, testMode, onResult, onStep)
            }, 100L)
            return
        }
        mainHandler.postDelayed({
            if (!sewInputInProgress) return@postDelayed
            val stillOpen = windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
            if (BuildConfig.DEBUG) android.util.Log.d("ScannerAccessibility", "waitForKeyboardClosed: stillOpen=$stillOpen attemptsLeft=$attemptsLeft")
            if (!stillOpen) {
                step6ClickConfirm(calibration, testMode, onResult, onStep)
            } else {
                waitForKeyboardClosed(calibration, testMode, onResult, onStep, attemptsLeft - 1)
            }
        }, 200L)
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

        if (testMode) {
            onStep?.invoke("Кнопка «Готово» найдена", true, null)
            val rect = android.graphics.Rect()
            textNode.getBoundsInScreen(rect)
            textNode.safeRecycle()
            val (tapX, tapY) = if (!rect.isEmpty) {
                ((rect.left + rect.right) / 2f) to ((rect.top + rect.bottom) / 2f)
            } else {
                calibration.confirm.x.toFloat() to calibration.confirm.y.toFloat()
            }
            clickConfirmAtCoords(tapX, tapY, onResult, testMode = true)
            return
        }

        val rect = android.graphics.Rect()
        textNode.getBoundsInScreen(rect)
        textNode.safeRecycle()
        val (tapX, tapY) = if (!rect.isEmpty) {
            val cx = (rect.left + rect.right) / 2f
            val cy = (rect.top + rect.bottom) / 2f
            if (BuildConfig.DEBUG) android.util.Log.d("ScannerAccessibility", "Готово: tap at fresh bounds ($cx, $cy) rect=$rect")
            cx to cy
        } else {
            val x = calibration.confirm.x.toFloat()
            val y = calibration.confirm.y.toFloat()
            if (BuildConfig.DEBUG) android.util.Log.d("ScannerAccessibility", "Готово: bounds empty, fallback to calibration ($x, $y)")
            x to y
        }
        clickConfirmAtCoords(tapX, tapY, onResult)
    }

    private fun clickConfirmAtCoords(
        x: Float,
        y: Float,
        onResult: SewInputCallback,
        testMode: Boolean = false
    ) {
        if (BuildConfig.DEBUG) android.util.Log.d("ScannerAccessibility", "Готово: tap at ($x, $y)")
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
            if (testMode) {
                releaseWatchdogAndFinish(onResult, true, "Тест пройден")
                return@postDelayed
            }
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
        pendingSewResult = null
        onResult(ok, message)
    }

    private fun tapPath(x: Float, y: Float): Path {
        return Path().apply {
            moveTo(x, y)
            lineTo(x, y)
        }
    }
}
