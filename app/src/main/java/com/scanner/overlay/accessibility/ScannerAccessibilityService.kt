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
import android.content.SharedPreferences
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
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

    @Inject lateinit var prefs: SharedPreferences

    @Volatile private var sewInputInProgress: Boolean = false
    @Volatile private var sewResultDelivered: Boolean = false
    @Volatile private var pendingSewResult: SewInputCallback? = null
    @Volatile private var lastEffectiveTarget: String = ""

    /**
     * Handler that owns the current SEW run's step callbacks and its watchdog.
     *
     * This handler *is* the run identity. Every step of a run is posted here and the
     * whole queue is wiped the moment the run finishes, so a step that had not run yet
     * can never resume inside the next run. The boolean flags alone cannot do this:
     * `sewResultDelivered` is reset to false by the next run, which lets a leftover
     * callback from a timed-out run pass its guard and tap SEW a second time.
     *
     * A per-run handler is used instead of `mainHandler` because the legacy
     * `injectText` path shares `mainHandler`, and wiping that queue would kill it.
     */
    private var sewRunHandler: Handler? = null
    private var pendingWatchdog: Runnable? = null
    private val watchdogTimeoutMs: Long = 8_000L
    private val maxSetTextAttempts: Int = 5
    private var _inputMode: String = "fast"

    override fun onServiceConnected() {
        super.onServiceConnected()
        _inputMode = prefs.getString("sew_input_mode", "fast") ?: "fast"
        _instance = WeakReference(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        _instance.clear()
        sewRunHandler?.removeCallbacksAndMessages(null)
        sewRunHandler = null
        pendingWatchdog = null
        // Nothing may stay queued after the service dies: a leftover callback would hold
        // pendingSewResult (and the ViewModel/Activity behind it) and report a second result.
        mainHandler.removeCallbacksAndMessages(null)
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
        sewRunHandler?.removeCallbacksAndMessages(null)
        sewRunHandler = null
        // Callbacks are gone with the handlers above, so finish the run explicitly —
        // otherwise sewInputInProgress stays true forever and blocks all future input.
        if (sewInputInProgress && !sewResultDelivered) {
            val cb = pendingSewResult
            pendingSewResult = null
            sewResultDelivered = true
            sewInputInProgress = false
            cb?.invoke(false, "Сервис прерван")
        } else {
            sewInputInProgress = false
            pendingSewResult = null
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Ищет кнопку подтверждения («Готово» и т.п.) в дереве [root].
     *
     * Проверка «узел содержит слово Готово» сама по себе ненадёжна: WebView склеивает
     * текст страницы в контейнерные узлы, поэтому подстрока «Send»/«Done» находится
     * где угодно на странице. Из-за этого тап уходил в произвольный элемент, а
     * `isButtonStillPresent()` после успешной отправки возвращал true по слову «Send»
     * на оставшейся под модалкой странице и прогон завершался ошибкой
     * «Кнопка не нажалась» при уже введённом штрихкоде.
     *
     * Поэтому поиск идёт в три прохода, от точного к общему:
     *  1. настоящий контрол (clickable / Button / viewId) с короткой подписью;
     *  2. короткая подпись без требования контрола — WebView часто отдаёт метку
     *     на не-кликабельном узле;
     *  3. прежнее свободное поведение — только если ничего лучше не нашлось, чтобы
     *     не превратить исправление в новую ложноотрицательную ошибку.
     *
     * Шаги поиска кнопки и проверки «кнопка всё ещё на месте» используют одну и ту же
     * функцию, поэтому найденная кнопка и проверка после тапа всегда дают одинаковый
     * ответ по одному и тому же узлу.
     */
    private fun findSendButton(
        root: AccessibilityNodeInfo,
        targets: List<String>
    ): AccessibilityNodeInfo? {
        findConfirmButtonNode(root) { node ->
            looksLikeButton(node) && isShortConfirmLabel(node, targets)
        }?.let { return it }
        return findConfirmButtonNode(root) { node ->
            isShortConfirmLabel(node, targets)
        } ?: findConfirmButtonNode(root) { node ->
            isLooseConfirmLabel(node, targets)
        }
    }

    private fun findConfirmButtonNode(
        root: AccessibilityNodeInfo,
        matches: (AccessibilityNodeInfo) -> Boolean
    ): AccessibilityNodeInfo? {
        val queue = java.util.ArrayDeque<AccessibilityNodeInfo>()
        // root не возвращаем (см. findInputField): вызывающий ресайклит root после поиска.
        for (i in 0 until root.childCount) {
            root.getChild(i)?.let { queue.add(it) }
        }
        var depth = 0
        while (queue.isNotEmpty() && depth < 50) {
            repeat(queue.size) {
                val node = queue.poll() ?: return@repeat
                if (matches(node)) {
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

    /** Подпись узла: contentDescription — это метка доступности, text может быть агрегатом страницы. */
    private fun confirmLabel(node: AccessibilityNodeInfo): String {
        val cd = node.contentDescription?.toString()?.trim().orEmpty()
        if (cd.isNotEmpty()) return cd
        return node.text?.toString()?.trim().orEmpty()
    }

    /**
     * Короткая подпись кнопки: либо точное совпадение, либо подстрока в коротком тексте.
     * Длинный текст — это контейнер вёрстки WebView, а не кнопка.
     */
    private fun isShortConfirmLabel(node: AccessibilityNodeInfo, targets: List<String>): Boolean {
        val label = confirmLabel(node)
        if (label.isEmpty()) return false
        if (targets.any { it.equals(label, ignoreCase = true) }) return true
        if (label.length > MAX_CONFIRM_LABEL_LENGTH) return false
        return targets.any { label.contains(it, ignoreCase = true) }
    }

    /** Прежнее свободное совпадение: contentDescription и text проверялись по отдельности. */
    private fun isLooseConfirmLabel(node: AccessibilityNodeInfo, targets: List<String>): Boolean {
        val cd = node.contentDescription?.toString() ?: ""
        if (cd.isNotEmpty() && targets.any { cd.contains(it, ignoreCase = true) }) return true
        val nodeText = node.text?.toString() ?: ""
        return nodeText.isNotEmpty() && targets.any { nodeText.contains(it, ignoreCase = true) }
    }

    private fun looksLikeButton(node: AccessibilityNodeInfo): Boolean {
        if (node.isClickable) return true
        val className = node.className?.toString() ?: ""
        if (className.contains("Button", ignoreCase = true)) return true
        val viewId = node.viewIdResourceName?.toString() ?: ""
        return viewId.contains("button", ignoreCase = true) ||
            viewId.contains("send", ignoreCase = true) ||
            viewId.contains("done", ignoreCase = true)
    }

    private fun findNodeContaining(root: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
        val queue = java.util.ArrayDeque<AccessibilityNodeInfo>()
        // root не возвращаем (см. findInputField): вызывающий ресайклит root после поиска.
        for (i in 0 until root.childCount) {
            root.getChild(i)?.let { queue.add(it) }
        }
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

    /** refresh()+performAction, shielded: stale/recycled nodes throw IllegalStateException. */
    private fun safePerformAction(node: AccessibilityNodeInfo, action: Int, args: Bundle? = null): Boolean {
        return try {
            node.refresh()
            node.performAction(action, args)
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Вернуть пользовательский буфер обмена, если в нём до сих пор наш внедрённый текст.
     * Stash сбрасывается только при фактическом восстановлении — иначе ретрай той же
     * сессии потерял бы оригинал, а чужой текст пользователя никогда не затираем.
     */
    private fun restoreClipboardIfOurs(expectedText: String?) {
        val stashed = pendingClipboardRestore ?: return
        if (expectedText == null) return
        try {
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            val current = clipboard.primaryClip
            val stillOurs = current != null && current.itemCount > 0 &&
                current.getItemAt(0)?.text?.toString() == expectedText
            if (stillOurs) {
                clipboard.setPrimaryClip(stashed)
                pendingClipboardRestore = null
            }
        } catch (_: Exception) { }
    }

    fun setInputMode(mode: String) {
        _inputMode = mode
        prefs.edit().putString("sew_input_mode", mode).apply()
    }

    private val isFastMode: Boolean get() = _inputMode == "fast"

    companion object {
        /**
         * Максимальная длина подписи, при которой узел ещё считается кнопкой, а не
         * контейнером вёрстки. WebView отдаёт текст модального окна одним узлом, поэтому
         * без ограничения подстрока «Готово» находилась в половине DOM страницы.
         */
        private const val MAX_CONFIRM_LABEL_LENGTH = 32

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
            // Fresh clipboard stash per run: a stash left over from an earlier run (its
            // restore was skipped because the user had already copied something of their
            // own) must not outlive that run, or a later restore would overwrite
            // whatever the user copied in the meantime.
            pendingClipboardRestore = null
            lastInjectedText = null
            effectiveBarcode = if (testMode) "TEST_CALIBRATION" else barcode
        }

        val startDelay = if (isFastMode) 0L else 500L
        // Any queue left over from a previous run dies here, whatever ended it.
        sewRunHandler?.removeCallbacksAndMessages(null)
        val runHandler = Handler(Looper.getMainLooper())
        sewRunHandler = runHandler
        runHandler.postDelayed({
            if (!sewInputInProgress) return@postDelayed
            step1FindWindow(calibration, effectiveTarget, testMode, effectiveBarcode, onResult, onStep)
        }, startDelay)
    }

    private fun detectActiveSupportedBrowser(): String? {
        for (win in windows) {
            if (!win.isActive) continue
            if (win.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
            val root = win.root ?: continue
            try {
                val pkg = root.packageName?.toString() ?: continue
                if (pkg in SupportedBrowsers.SUPPORTED_PACKAGES) {
                    return pkg
                }
            } finally {
                root.safeRecycle()
            }
        }
        return null
    }

    fun cancelOngoingSewInput(message: String = "Отменено") {
        if (!sewInputInProgress) return
        sewRunHandler?.removeCallbacksAndMessages(null)
        sewRunHandler = null
        pendingWatchdog = null
        sewInputInProgress = false
        val cb = pendingSewResult
        pendingSewResult = null
        if (cb != null && !sewResultDelivered) {
            sewResultDelivered = true
            cb(false, message)
        }
    }

    /**
     * Arms the run watchdog on the run's own handler, so it dies with the run instead
     * of being able to fire into the next one.
     *
     * Re-arming cancels the previous watchdog: every phase gets its own full
     * `watchdogTimeoutMs` budget (step1 alone is allowed 25 x 300ms = 7.5s).
     */
    private fun armWatchdog(onResult: SewInputCallback) {
        val handler = sewRunHandler ?: return
        pendingWatchdog?.let { handler.removeCallbacks(it) }
        val watchdog = Runnable {
            pendingWatchdog = null
            if (!sewInputInProgress || sewResultDelivered) return@Runnable
            releaseWatchdogAndFinish(onResult, false, "Таймаут")
        }
        pendingWatchdog = watchdog
        handler.postDelayed(watchdog, watchdogTimeoutMs)
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
            val findDelay = if (isFastMode) 500L else 1000L
            sewRunHandler?.postDelayed({
                if (!sewInputInProgress) return@postDelayed
                step2ClickOpenModal(calibration, testMode, effectiveBarcode, onResult, onStep)
            }, findDelay)
            return
        }
        // 25 попыток × 300мс = 7.5с — гарантированно меньше watchdog (8с):
        // иначе watchdog финиширует раньше, а поздний тик дёргает onStep после результата.
        pollForTargetWindow(calibration, targetPackage, testMode, effectiveBarcode, onResult, onStep, attemptsLeft = 25)
    }

    private fun findTargetWindow(targetPackage: String?): AccessibilityWindowInfo? {
        // Явные циклы вместо firstOrNull-лямбд: каждый win.root создаёт объект,
        // который обязан быть recycled — в лямбде его не закрыть.
        var byFallback: AccessibilityWindowInfo? = null
        var fallbackPkg = ""
        for (w in windows) {
            if (!w.isActive) continue
            val root = w.root ?: continue
            val pkg: String
            try {
                pkg = root.packageName?.toString() ?: continue
            } finally {
                root.safeRecycle()
            }
            if (!targetPackage.isNullOrEmpty() && pkg == targetPackage) {
                if (lastEffectiveTarget != targetPackage) lastEffectiveTarget = targetPackage
                return w
            }
            if (byFallback == null && pkg in SupportedBrowsers.SUPPORTED_PACKAGES) {
                byFallback = w
                fallbackPkg = pkg
            }
        }
        if (byFallback != null) {
            val actualPkg = fallbackPkg
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
            root?.safeRecycle()
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
                .mapNotNull {
                    val r = it.root ?: return@mapNotNull null
                    try {
                        r.packageName?.toString()
                    } finally {
                        r.safeRecycle()
                    }
                }
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
        sewRunHandler?.postDelayed({
            // Прогон уже завершён (watchdog/отмена): поздний тик молча гаснет,
            // onStep после финального onResult недопустим.
            if (!sewInputInProgress || sewResultDelivered) return@postDelayed
            val t = findTargetWindow(targetPackage)
            if (t != null) {
                logWindowsSnapshot("step1.poll.found", targetPackage)
                onStep?.invoke("SEW найден", true, null)
                armWatchdog(onResult)
                val pollFindDelay = if (isFastMode) 500L else 1000L
                sewRunHandler?.postDelayed({
                    if (!sewInputInProgress) return@postDelayed
                    step2ClickOpenModal(calibration, testMode, effectiveBarcode, onResult, onStep)
                }, pollFindDelay)
            } else {
                if (attemptsLeft == 25 || attemptsLeft % 10 == 0) {
                    logWindowsSnapshot("step1.poll.tick$attemptsLeft", targetPackage)
                }
                if (BuildConfig.DEBUG && (attemptsLeft == 25 || attemptsLeft == 15 || attemptsLeft == 5)) {
                    val activeDetails = windows.filter { it.isActive }.joinToString("; ") { w ->
                        val wr = w.root
                        val pkg = wr?.packageName?.toString() ?: "<null>"
                        val cls = wr?.className?.toString()?.substringAfterLast('.') ?: "<null>"
                        wr?.safeRecycle()
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
                sewRunHandler?.postDelayed({
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
        armWatchdog(onResult)
        val pollMax = if (isFastMode) 16 else 20
        pollForModalOrInput(calibration, testMode, effectiveBarcode, onResult, onStep, pollAttemptsLeft = pollMax, maxAttempts = pollMax)
    }

    private fun pollForModalOrInput(
        calibration: SewCalibration,
        testMode: Boolean,
        effectiveBarcode: String,
        onResult: SewInputCallback,
        onStep: SewStepCallback?,
        pollAttemptsLeft: Int,
        maxAttempts: Int
    ) {
        if (pollAttemptsLeft <= 0) {
            if (BuildConfig.DEBUG) logWindowsSnapshot("step2.noModalAfterPoll", lastEffectiveTarget)
            val msg = "Модалка не открылась после $maxAttempts попыток (${calibration.openModal.x}, ${calibration.openModal.y})"
            onStep?.invoke("Кнопка «Ручной ввод» доступна", false, msg)
            releaseWatchdogAndFinish(onResult, false, msg)
            return
        }
        // First attempt: wait for modal to fully open before searching
        if (pollAttemptsLeft == maxAttempts) {
            sewRunHandler?.postDelayed({
                if (!sewInputInProgress) return@postDelayed
                pollForModalOrInput(calibration, testMode, effectiveBarcode, onResult, onStep, pollAttemptsLeft - 1, maxAttempts)
            }, if (isFastMode) 600L else 1000L)
            return
        }
        // Poll for input field — prefer placeholder match, then focus, then any editable in SEW window
        // Каждый тик перевзводит watchdog: findModalInputField() делает полный BFS по DOM
        // SEW во всех окнах, и на тяжёлой странице один проход занимает сотни мс. Общий
        // бюджет 8с тогда истекал бы на живом, просто медленном опросе и давал ложный
        // «Таймаут». С перевзводом watchdog означает «нет прогресса 8с»; при этом он не
        // может прервать текущий тик — тики и watchdog стоят в одной очереди looper'а.
        armWatchdog(onResult)
        val foundInput = findModalInputField()
        if (BuildConfig.DEBUG) {
            val pkg = foundInput?.packageName?.toString() ?: "<null>"
            android.util.Log.d(
                "ScannerAccessibility",
                "[step2.poll] pollAttemptsLeft=$pollAttemptsLeft/$maxAttempts inputFound=${foundInput != null} pkg=$pkg"
            )
        }
        if (foundInput != null) {
            onStep?.invoke("Кнопка «Ручной ввод» доступна", true, null)
            step3FindInput(foundInput, effectiveBarcode, calibration, testMode, onResult, onStep)
            return
        }
        sewRunHandler?.postDelayed({
            if (!sewInputInProgress) return@postDelayed
            pollForModalOrInput(calibration, testMode, effectiveBarcode, onResult, onStep, pollAttemptsLeft - 1, maxAttempts)
        }, 50L)
    }

    private fun isSewNodePackage(node: AccessibilityNodeInfo): Boolean {
        val pkg = node.packageName?.toString() ?: return false
        if (pkg == BuildConfig.APPLICATION_ID) return false
        return pkg == lastEffectiveTarget || pkg in SupportedBrowsers.SUPPORTED_PACKAGES
    }

    private fun findModalInputField(): AccessibilityNodeInfo? {
        // Priority 1: placeholder "Штрих-код" — most reliable for SEW modal
        val byPlaceholder = findInputByPlaceholder("Штрих-код")
        if (byPlaceholder != null) return byPlaceholder
        // Priority 2: focused editable in a SEW browser window (not our own overlay)
        val focusInput = findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focusInput != null && focusInput.isEditable) {
            if (isSewNodePackage(focusInput)) return focusInput
            focusInput.safeRecycle()
        } else {
            focusInput?.safeRecycle()
        }
        // Priority 3: any editable in a SEW browser window, excluding our overlay
        for (win in windows) {
            if (!win.isActive) continue
            val root = win.root ?: continue
            if (!isSewNodePackage(root)) {
                root.safeRecycle()
                continue
            }
            val editable = findFirstEditable(root)
            root.safeRecycle()
            if (editable != null) return editable
        }
        return null
    }

    private fun findInputFieldAcrossWindows(): AccessibilityNodeInfo? {
        val fromFocus = findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (fromFocus != null && fromFocus.isEditable && isSewNodePackage(fromFocus)) return fromFocus
        fromFocus?.safeRecycle()

        val fromPlaceholder = findInputByPlaceholder("Штрих-код")
        if (fromPlaceholder != null) return fromPlaceholder

        for (win in windows) {
            if (!win.isActive) continue
            val root = win.root ?: continue
            if (!isSewNodePackage(root)) {
                root.safeRecycle()
                continue
            }
            val editable = findFirstEditable(root)
            root.safeRecycle()
            if (editable != null) return editable
        }
        return null
    }

    private fun findFirstEditable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val queue = java.util.ArrayDeque<AccessibilityNodeInfo>()
        // root не возвращаем (см. findInputField): вызывающий ресайклит root после поиска.
        for (i in 0 until root.childCount) {
            root.getChild(i)?.let { queue.add(it) }
        }
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
        input: AccessibilityNodeInfo,
        effectiveBarcode: String,
        calibration: SewCalibration,
        testMode: Boolean,
        onResult: SewInputCallback,
        onStep: SewStepCallback?
    ) {
        if (BuildConfig.DEBUG) android.util.Log.d("ScannerAccessibility", "step3FindInput: pkg=${input.packageName} text='${input.text}'")
        onStep?.invoke("Поле ввода найдено", true, null)
        armWatchdog(onResult)
        step4SetText(input, effectiveBarcode, calibration, testMode, onResult, onStep)
    }

    private fun findInputByPlaceholder(text: String): AccessibilityNodeInfo? {
        for (win in windows) {
            // Как и в findModalInputField/findInputFieldAcrossWindows: неактивное окно
            // исключаем. Без этой проверки при двух открытых браузерах (например, Chrome
            // с SEW и Edge рядом) штрихкод ушёл бы в поле фонового окна.
            if (!win.isActive) continue
            val root = win.root ?: continue
            if (!isSewNodePackage(root)) {
                root.safeRecycle()
                continue
            }
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
        onStep: SewStepCallback?,
        attempt: Int = 0
    ) {
        val args = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                barcode
            )
        }
        inputNode.refresh()
        val ok = safePerformAction(inputNode, AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        if (BuildConfig.DEBUG) android.util.Log.d("ScannerAccessibility", "step4SetText: ACTION_SET_TEXT ok=$ok pkg=${inputNode.packageName} text='$barcode' editable=${inputNode.isEditable} focused=${inputNode.isFocused}")
        if (!ok) {
            // Общий буфер обмена в SEW-пайплайне не используется: здесь нет ни ACTION_PASTE,
            // ни вставки из контекстного меню — единственный механизм ввода это ACTION_SET_TEXT
            // с повтором после фокусировки. Запись в буфер могла только затереть данные
            // пользователя, причём безвозвратно: с API 29 приложение без фокуса (наш
            // AccessibilityService, пока в фокусе SEW) не читает primaryClip, поэтому
            // restoreClipboardIfOurs никогда не подтвердил бы, что в буфере наш текст.
            if (BuildConfig.DEBUG) android.util.Log.d("ScannerAccessibility", "step4SetText: SET_TEXT FAILED, retry with focus")
            val focusOk = safePerformAction(inputNode, AccessibilityNodeInfo.ACTION_FOCUS)
            if (BuildConfig.DEBUG) android.util.Log.d("ScannerAccessibility", "step4SetText: ACTION_FOCUS ok=$focusOk")
            sewRunHandler?.postDelayed({
                inputNode.safeRecycle()
                val pasted = findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                if (!sewInputInProgress || sewResultDelivered) {
                    pasted?.safeRecycle()
                    return@postDelayed
                }
                if (pasted != null && pasted.isEditable) {
                    if (attempt + 1 >= maxSetTextAttempts) {
                        pasted?.safeRecycle()
                        releaseWatchdogAndFinish(onResult, false, "Не удалось ввести штрихкод в поле")
                    } else {
                        step4SetText(pasted, barcode, calibration, testMode, onResult, onStep, attempt + 1)
                    }
                } else {
                    pasted?.safeRecycle()
                    releaseWatchdogAndFinish(onResult, false, "Ввод не зафиксирован")
                }
            }, 250L)
            return
        }
        sewRunHandler?.postDelayed({
            if (testMode) {
                // In test mode nothing else owns inputNode, so it must be released here or
                // every "Тест калибровки" run leaks one native AccessibilityNodeInfo peer.
                inputNode.safeRecycle()
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
            // The node went stale or was recycled, so the text could not be read back.
            // Treat it as a failure: falling through would report success and tap
            // "Готово" while the barcode was never actually entered.
            inputNode.safeRecycle()
            releaseWatchdogAndFinish(onResult, false, "Не удалось проверить ввод")
            return
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
        sewRunHandler?.postDelayed({
            if (!sewInputInProgress) return@postDelayed
            step6ClickConfirm(calibration, testMode, onResult, onStep)
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
            if (!isSewNodePackage(root)) {
                root.safeRecycle()
                continue
            }
            textNode = findSendButton(root, buttonTexts)
            if (textNode != null) {
                root.safeRecycle()
                break
            }
            root.safeRecycle()
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
        sewRunHandler?.postDelayed({
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
            if (!isSewNodePackage(root)) {
                root.safeRecycle()
                continue
            }
            val found = findSendButton(root, buttonTexts)
            if (found != null) {
                found.safeRecycle()
                root.safeRecycle()
                return true
            }
            root.safeRecycle()
        }
        return false
    }

    private fun releaseWatchdogAndFinish(onResult: SewInputCallback, ok: Boolean, message: String) {
        // Wipe the run's queue first: every step of this run that has not executed yet
        // dies here, so it cannot resume after the run is over.
        sewRunHandler?.removeCallbacksAndMessages(null)
        pendingWatchdog = null
        if (sewResultDelivered) return
        sewResultDelivered = true
        sewInputInProgress = false
        restoreClipboardIfOurs(lastInjectedText)
        pendingSewResult = null
        sewRunHandler = null
        onResult(ok, message)
    }

    private fun tapPath(x: Float, y: Float): Path {
        return Path().apply {
            moveTo(x, y)
            lineTo(x, y)
        }
    }
}
