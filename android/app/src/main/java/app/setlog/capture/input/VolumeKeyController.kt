package app.setlog.capture.input

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import app.setlog.capture.model.InputSettings
import app.setlog.capture.model.PhysicalVolumeKey
import app.setlog.capture.model.ShortcutAction

/**
 * Maps the two hardware volume keys without ever requiring a simultaneous long-press.
 *
 * The old Volume Up + Volume Down chord collided with Android's accessibility shortcut. Vibe.now
 * instead reserves one configurable key for press-and-hold recording and uses multi-press gestures
 * on the other key. A double action is delayed briefly so a third press can still win.
 */
class VolumeKeyController(
    private val settingsProvider: () -> InputSettings,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
    private val wallClock: () -> Long = System::currentTimeMillis,
    private val handler: Handler = Handler(Looper.getMainLooper()),
    private val callback: Callback,
) {
    interface Callback {
        fun onRecordHoldStarted(pressedAtEpochMs: Long)
        fun onRecordHoldEnded()
        fun onShortcutAction(action: ShortcutAction)
    }

    private var heldRecordKey: PhysicalVolumeKey? = null
    private var recordHoldStarted = false
    private var shortcutKeyDown = false
    private var shortcutTapCount = 0
    private var firstShortcutTapAtMs = 0L

    private val resolveShortcut = Runnable {
        val settings = settingsProvider()
        val count = shortcutTapCount
        clearShortcutSequence()
        when {
            count >= 3 -> callback.dispatch(settings.triplePressAction)
            count == 2 -> callback.dispatch(settings.doublePressAction)
        }
    }

    fun dispatch(event: KeyEvent): Boolean {
        val key = event.keyCode.toPhysicalVolumeKey() ?: return false
        val settings = settingsProvider()
        val isRecordKey = key == settings.recordKey
        val shortcutsEnabled = settings.doublePressAction != ShortcutAction.NONE ||
            settings.triplePressAction != ShortcutAction.NONE

        return when {
            isRecordKey -> {
                handleRecordKey(key, event)
                true
            }
            shortcutsEnabled -> {
                handleShortcutKey(event)
                true
            }
            else -> false
        }
    }

    private fun handleRecordKey(key: PhysicalVolumeKey, event: KeyEvent) {
        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount > 0 || heldRecordKey != null) return
                heldRecordKey = key
                recordHoldStarted = true
                callback.onRecordHoldStarted(wallClock())
            }

            KeyEvent.ACTION_UP -> {
                if (heldRecordKey != key) return
                heldRecordKey = null
                if (recordHoldStarted) {
                    recordHoldStarted = false
                    callback.onRecordHoldEnded()
                }
            }
        }
    }

    private fun handleShortcutKey(event: KeyEvent) {
        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount > 0 || shortcutKeyDown) return
                shortcutKeyDown = true
            }

            KeyEvent.ACTION_UP -> {
                if (!shortcutKeyDown) return
                shortcutKeyDown = false
                registerShortcutTap()
            }
        }
    }

    private fun registerShortcutTap() {
        val now = clock()
        if (shortcutTapCount == 0 || now - firstShortcutTapAtMs > MULTI_PRESS_WINDOW_MS) {
            clearShortcutSequence()
            firstShortcutTapAtMs = now
            shortcutTapCount = 1
        } else {
            shortcutTapCount += 1
        }

        handler.removeCallbacks(resolveShortcut)
        if (shortcutTapCount >= 3) {
            val action = settingsProvider().triplePressAction
            clearShortcutSequence()
            callback.dispatch(action)
        } else {
            val elapsed = now - firstShortcutTapAtMs
            handler.postDelayed(
                resolveShortcut,
                (MULTI_PRESS_WINDOW_MS - elapsed).coerceAtLeast(MINIMUM_RESOLUTION_DELAY_MS),
            )
        }
    }

    private fun Callback.dispatch(action: ShortcutAction) {
        if (action != ShortcutAction.NONE) {
            onShortcutAction(action)
        }
    }

    private fun clearShortcutSequence() {
        handler.removeCallbacks(resolveShortcut)
        shortcutTapCount = 0
        firstShortcutTapAtMs = 0L
    }

    fun reset() {
        clearShortcutSequence()
        shortcutKeyDown = false
        heldRecordKey = null
        if (recordHoldStarted) {
            recordHoldStarted = false
            callback.onRecordHoldEnded()
        }
    }

    companion object {
        const val MULTI_PRESS_WINDOW_MS = 720L
        private const val MINIMUM_RESOLUTION_DELAY_MS = 80L
    }
}

private fun Int.toPhysicalVolumeKey(): PhysicalVolumeKey? = when (this) {
    KeyEvent.KEYCODE_VOLUME_UP -> PhysicalVolumeKey.UP
    KeyEvent.KEYCODE_VOLUME_DOWN -> PhysicalVolumeKey.DOWN
    else -> null
}
