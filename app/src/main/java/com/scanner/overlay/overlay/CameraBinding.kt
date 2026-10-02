package com.scanner.overlay.overlay

import android.os.Handler
import android.os.SystemClock
import androidx.camera.core.Camera
import androidx.camera.core.CameraState
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.lifecycle.Observer
import java.util.concurrent.atomic.AtomicBoolean
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
        lastReleaseMs.set(SystemClock.elapsedRealtime())
    }

    /**
     * Run [action] on the main looper after any recent release has had time to free hardware.
     * Non-blocking: if a cooldown is still pending, schedules [action] via [handler].
     */
    fun runWhenCameraFree(handler: Handler, action: () -> Unit) {
        val since = SystemClock.elapsedRealtime() - lastReleaseMs.get()
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

    /**
     * Сколько ждём реального открытия камеры, прежде чем признать сессию неудачной.
     *
     * Обычный запуск укладывается в доли секунды; 10 с — с запасом на холодный старт.
     */
    const val OPEN_TIMEOUT_MS = 10_000L

    private const val TAG = "CameraBinding"

    /**
     * Наблюдатель фактического состояния камеры. Вызывающий обязан снять его через
     * [OpenWatch.cancel].
     */
    class OpenWatch internal constructor(
        private val camera: Camera,
        private val handler: Handler,
        private val observer: Observer<CameraState>,
        private val watchdog: Runnable
    ) {
        /** Снять наблюдателя и отменить watchdog. Идемпотентно. */
        fun cancel() {
            handler.removeCallbacks(watchdog)
            try {
                camera.cameraInfo.cameraState.removeObserver(observer)
            } catch (_: Throwable) {
            }
        }
    }

    /**
     * Следит за [CameraState] — единственным источником правды о том, открылась ли камера.
     *
     * Зачем: [ProcessCameraProvider.bindToLifecycle] возвращает [Camera] **сразу и без
     * исключения**, даже если камера фактически не открылась. Дальше состояние приходит
     * асинхронно:
     *
     * - `PENDING_OPEN` — камеру держит другой клиент (наш прошлый не закрытый use-case,
     *   камера другого приложения, или просто HAL не отдал устройство). Может висеть вечно.
     * - `CLOSED` + непустой [CameraState.getError] — камера не открылась: устройство
     *   отобрано другим приложением, нет разрешения, сбой HAL.
     *
     * Ни то, ни другое не бросает исключение и не даёт ни одного кадра в анализатор, поэтому
     * без наблюдателя экран висит чёрным до таймаута сканирования, не сообщая ни о чём.
     * `watchdog` закрывает эту дыру: если за [OPEN_TIMEOUT_MS] состояние так и не стало
     * `OPEN`, [onFailed] вызывается с описанием проблемы.
     *
     * @param onFailed вызывается на главном потоке ровно один раз за сессию.
     */
    fun watchOpenState(
        camera: Camera,
        handler: Handler,
        onFailed: (String) -> Unit
    ): OpenWatch {
        val settled = AtomicBoolean(false)

        val watchdog = Runnable {
            if (settled.compareAndSet(false, true)) {
                android.util.Log.e(TAG, "camera never reached OPEN within ${OPEN_TIMEOUT_MS}ms")
                onFailed("Камера не открылась за ${OPEN_TIMEOUT_MS / 1000} с — возможно, её держит другое приложение")
            }
        }

        val observer = Observer<CameraState> { state ->
            android.util.Log.d(TAG, "CameraState type=${state.type} error=${state.error}")
            when {
                state.type == CameraState.Type.OPEN -> {
                    if (settled.compareAndSet(false, true)) {
                        handler.removeCallbacks(watchdog)
                        android.util.Log.d(TAG, "camera OPEN")
                    }
                }
                // Отдельного Type.ERROR в CameraX нет: сбой — это CLOSED с заполненным error.
                state.error != null -> {
                    if (settled.compareAndSet(false, true)) {
                        handler.removeCallbacks(watchdog)
                        android.util.Log.e(TAG, "camera failed: type=${state.type} error=${state.error}")
                        onFailed("Камера не открылась: ${state.error}")
                    }
                }
                // PENDING_OPEN / OPENING / CLOSING / CLOSED без ошибки — ждём, watchdog решит.
                else -> Unit
            }
        }

        camera.cameraInfo.cameraState.observeForever(observer)
        handler.postDelayed(watchdog, OPEN_TIMEOUT_MS)
        return OpenWatch(camera, handler, observer, watchdog)
    }
}
