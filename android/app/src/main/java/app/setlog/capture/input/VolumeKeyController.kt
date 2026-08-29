package app.setlog.capture.input

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import java.util.ArrayDeque

class VolumeKeyController(
    private val clock: () -> Long = SystemClock::elapsedRealtime,
    private val wallClock: () -> Long = System::currentTimeMillis,
    private val handler: Handler = Handler(Looper.getMainLooper()),
    private val callback: Callback,
) {
    interface Callback {
        fun onVolumeUpHoldStarted(pressedAtEpochMs: Long)
        fun onVolumeUpHoldEnded()
        fun onFinishChordReached()
        fun onGalleryTriplePress()
    }

    private var volumeUpDown = false
    private var volumeDownDown = false
    private var recordingHoldStarted = false
    private var chordTriggered = false
    private var chordCandidate = false
    private val minusReleaseTimes = ArrayDeque<Long>()

    private val delayedStartRecording = Runnable {
        if (volumeUpDown && !volumeDownDown && !chordTriggered) {
            recordingHoldStarted = true
            callback.onVolumeUpHoldStarted(wallClock())
        }
    }

    private val delayedFinishChord = Runnable {
        if (volumeUpDown && volumeDownDown && chordCandidate && !chordTriggered) {
            chordTriggered = true
            chordCandidate = false
            if (recordingHoldStarted) {
                recordingHoldStarted = false
                callback.onVolumeUpHoldEnded()
            }
            callback.onFinishChordReached()
        }
    }

    fun dispatch(event: KeyEvent): Boolean {
        return when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> {
                handleVolumeUp(event)
                true
            }
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                handleVolumeDown(event)
                true
            }
            else -> false
        }
    }

    private fun handleVolumeUp(event: KeyEvent) {
        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount > 0 || volumeUpDown) return
                volumeUpDown = true
                if (volumeDownDown) {
                    beginChord()
                } else {
                    handler.postDelayed(delayedStartRecording, CHORD_GRACE_MS)
                }
            }
            KeyEvent.ACTION_UP -> {
                if (!volumeUpDown) return
                volumeUpDown = false
                handler.removeCallbacks(delayedStartRecording)
                cancelChord()
                if (recordingHoldStarted) {
                    recordingHoldStarted = false
                    callback.onVolumeUpHoldEnded()
                }
                unlockChordIfReleased()
            }
        }
    }

    private fun handleVolumeDown(event: KeyEvent) {
        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount > 0 || volumeDownDown) return
                volumeDownDown = true
                if (volumeUpDown) {
                    handler.removeCallbacks(delayedStartRecording)
                    if (recordingHoldStarted) {
                        recordingHoldStarted = false
                        callback.onVolumeUpHoldEnded()
                    }
                    beginChord()
                }
            }
            KeyEvent.ACTION_UP -> {
                if (!volumeDownDown) return
                volumeDownDown = false
                val wasChord = chordCandidate || chordTriggered
                cancelChord()
                if (!wasChord && !chordTriggered) {
                    registerMinusRelease()
                } else {
                    minusReleaseTimes.clear()
                }
                if (volumeUpDown && !chordTriggered && !recordingHoldStarted) {
                    handler.postDelayed(delayedStartRecording, CHORD_GRACE_MS)
                }
                unlockChordIfReleased()
            }
        }
    }

    private fun beginChord() {
        if (chordTriggered) return
        chordCandidate = true
        handler.removeCallbacks(delayedFinishChord)
        handler.postDelayed(delayedFinishChord, FINISH_HOLD_MS)
    }

    private fun cancelChord() {
        chordCandidate = false
        handler.removeCallbacks(delayedFinishChord)
    }

    private fun registerMinusRelease() {
        val now = clock()
        while (minusReleaseTimes.isNotEmpty() && now - minusReleaseTimes.first() > TRIPLE_WINDOW_MS) {
            minusReleaseTimes.removeFirst()
        }
        minusReleaseTimes.addLast(now)
        if (minusReleaseTimes.size >= 3) {
            minusReleaseTimes.clear()
            callback.onGalleryTriplePress()
        }
    }

    private fun unlockChordIfReleased() {
        if (!volumeUpDown && !volumeDownDown) {
            chordTriggered = false
            chordCandidate = false
        }
    }

    fun reset() {
        handler.removeCallbacks(delayedStartRecording)
        handler.removeCallbacks(delayedFinishChord)
        if (recordingHoldStarted) {
            recordingHoldStarted = false
            callback.onVolumeUpHoldEnded()
        }
        volumeUpDown = false
        volumeDownDown = false
        chordTriggered = false
        chordCandidate = false
        minusReleaseTimes.clear()
    }

    companion object {
        const val FINISH_HOLD_MS = 2_000L
        const val CHORD_GRACE_MS = 90L
        const val TRIPLE_WINDOW_MS = 900L
    }
}
