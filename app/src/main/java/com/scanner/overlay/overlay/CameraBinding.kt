package com.scanner.overlay.overlay

import android.os.Handler
import androidx.camera.lifecycle.ProcessCameraProvider
import java.util.concurrent.atomic.AtomicLong

/**
 * Centralizes camera bind/unbind for the whole process.
 *
 * [ProcessCameraProvider] is a singleton shared across every [OverlayActivity] in the process.
 * Camera hardware is released asynchronously after [ProcessCameraProvider.unbindAll], so a new
 * session that starts while the previous one is still releasing (fast open/close, notification
 * tap right after closing, or "Retry" via key re-creation) can hit a bind that silently fails to
 * render preview. That leaves a black screen until the process is killed.
 *
 * This holder records release timestamps and schedules binds on the main looper only after any
 * pending cooldown has elapsed, so the next bind never races an in-flight native release without
 * blocking the UI thread. It also clears any partial binding on error so a failed session does not
 * poison the shared provider for later sessions.
 */
object CameraBinding {

    private const val RELEASE_COOLDOWN_MS = 500L

    private val lastReleaseMs = AtomicLong(0L)

    /** Record that the camera was just released so the next bind waits for native release. */
    fun recordReleased() {
        lastReleaseMs.set(System.currentTimeMillis())
    }

    /**
     * Run [action] on the main looper after any recent release has had time to free hardware.
     * Non-blocking: if a cooldown is still pending, schedules [action] via [handler].
     */
    fun runWhenCameraFree(handler: Handler, action: () -> Unit) {
        val since = System.currentTimeMillis() - lastReleaseMs.get()
        if (since >= RELEASE_COOLDOWN_MS) {
            action()
        } else {
            handler.postDelayed(action, RELEASE_COOLDOWN_MS - since)
        }
    }

    /** Force-release any leftover bindings so the shared provider can be reused after an error. */
    fun forceReset(provider: ProcessCameraProvider?) {
        try {
            provider?.unbindAll()
        } catch (_: Throwable) {
        }
        recordReleased()
    }
}
