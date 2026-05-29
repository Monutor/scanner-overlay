package com.scanner.overlay.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import dagger.hilt.android.AndroidEntryPoint
import java.lang.ref.WeakReference
import com.scanner.overlay.BuildConfig

private fun AccessibilityNodeInfo.safeRecycle() {
    try { recycle() } catch (_: Exception) {}
}

@AndroidEntryPoint
class ScannerAccessibilityService : AccessibilityService() {

    private var pendingClipboardRestore: ClipData? = null
    private var lastInjectedText: String? = null

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
}
