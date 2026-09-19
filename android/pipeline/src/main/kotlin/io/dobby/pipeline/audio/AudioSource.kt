package io.dobby.pipeline.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.CopyOnWriteArrayList

/**
 * One consumer of the microphone stream.
 *
 * Called on the audio reader thread, once per frame, for as long as the mic is open — so it
 * must not block. The array is reused between calls: a sink that keeps the samples copies them.
 */
fun interface FrameSink {
    fun onFrames(frame: ShortArray, length: Int)
}

class MicrophoneUnavailableException(message: String, cause: Throwable? = null) :
    Exception(message, cause)

/**
 * Owns the process's one [AudioRecord] and routes its frames to every registered [FrameSink].
 *
 * There is exactly one microphone and two things that want it: the wake word, between turns,
 * and the utterance capture, during one. They take turns rather than overlap — command audio is
 * not wake-word audio — but their swap happens mid-stream, which is exactly why neither of them
 * is allowed to open or close the recorder. Android will not open the mic twice, so the split
 * happens here: the reader loop is the only code that touches `AudioRecord`, and everything
 * downstream is a sink (`dobby-plan.md` §2, invariant 4).
 *
 * **The mic is open exactly while something is listening.** Callers add and remove sinks; they
 * never start or stop the recorder. That is not convenience, it is the invariant: the handover
 * at each end of a turn only works because whoever is arriving joins before whoever is leaving
 * goes, and the recorder stays open across the overlap without anybody having to say so.
 *
 * Frame length and sample rate are the wake word's (1280 samples — 80 ms — at 16 kHz):
 * openWakeWord's melspectrogram front-end wants multiples of 80 ms, while the STT side buffers
 * whatever it is handed and the VAD re-cuts it into its own windows. The component that cannot
 * choose is the one that gets to.
 */
class AudioSource(
    private val scope: CoroutineScope,
    /**
     * Which microphone to open, and what the platform does to the signal on the way out.
     *
     * A parameter rather than a constant because the two profiles are not orderable: the
     * unprocessed one is better in a quiet room and deaf to the panel's own speaker, and the
     * processed one is the only thing on this device that can hear past music. Which is right
     * is a measurement (`m2b-plan.md` B3), and until it has been taken the default is what M2
     * shipped.
     */
    val profile: MicProfile = MicProfile.DEFAULT,
    val sampleRate: Int = SAMPLE_RATE,
    val frameLength: Int = FRAME_LENGTH,
) {
    /** Where a failure on the audio thread goes. Set by the owner; there is nobody to throw to. */
    var onError: (Throwable) -> Unit = {}

    private val sinks = CopyOnWriteArrayList<FrameSink>()

    @Volatile
    private var record: AudioRecord? = null
    private var reader: Job? = null

    /** The platform effects attached to the open session, released with it. */
    private var effects: List<AudioEffect> = emptyList()

    val isRunning: Boolean get() = reader?.isActive == true

    /**
     * Starts routing frames to [sink], opening the microphone if it is not already open.
     *
     * @throws MicrophoneUnavailableException if the mic cannot be opened — which on Android 12+
     * is the normal outcome when the service was not started from a foreground activity
     * (`dobby-plan.md` §7.1), not an exotic failure.
     */
    @Synchronized
    fun addSink(sink: FrameSink) {
        sinks += sink
        try {
            if (!isRunning) open()
        } catch (e: MicrophoneUnavailableException) {
            sinks -= sink
            throw e
        }
    }

    /** Stops routing to [sink], closing the microphone once nothing is listening any more. */
    @Synchronized
    fun removeSink(sink: FrameSink) {
        sinks -= sink
        if (sinks.isEmpty()) stop()
    }

    /** Closes the microphone regardless of who is still listening. For shutdown only. */
    @Synchronized
    fun stop() {
        val open = record
        record = null
        // stop() is what unblocks a read() already in flight, so the reader coroutine can
        // reach its finally and release. Cancelling the job alone would not.
        if (open != null) {
            try {
                if (open.recordingState == AudioRecord.RECORDSTATE_RECORDING) open.stop()
            } catch (e: IllegalStateException) {
                // Already released by the reader after a read error. Nothing left to stop.
                onError(e)
            }
        }
        reader?.cancel()
        reader = null
    }

    @SuppressLint("MissingPermission")
    private fun open() {
        val minBuffer = AudioRecord.getMinBufferSize(sampleRate, CHANNEL, ENCODING)
        if (minBuffer <= 0) {
            throw MicrophoneUnavailableException("sample rate ${sampleRate}Hz is not supported")
        }

        val opened = try {
            AudioRecord(
                profile.source,
                sampleRate,
                CHANNEL,
                ENCODING,
                maxOf(minBuffer, frameLength * BYTES_PER_SAMPLE * BUFFER_FRAMES),
            )
        } catch (e: IllegalArgumentException) {
            throw MicrophoneUnavailableException("could not open the microphone", e)
        } catch (e: SecurityException) {
            throw MicrophoneUnavailableException("microphone permission was refused", e)
        }

        if (opened.state != AudioRecord.STATE_INITIALIZED) {
            opened.release()
            throw MicrophoneUnavailableException("the microphone is held by something else")
        }

        // Before startRecording, because an effect is attached to the session rather than to
        // the stream and some HALs only honour one set up on an idle session.
        val attached = attachEffects(opened.audioSessionId)
        effects = attached

        opened.startRecording()
        if (opened.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            release(attached)
            opened.release()
            throw MicrophoneUnavailableException("the microphone did not start")
        }

        record = opened
        reader = scope.launch(Dispatchers.IO) {
            val frame = ShortArray(frameLength)
            try {
                while (isActive) {
                    val read = opened.read(frame, 0, frameLength)
                    if (read < 0) {
                        onError(MicrophoneUnavailableException("audio read failed ($read)"))
                        break
                    }
                    for (sink in sinks) {
                        try {
                            sink.onFrames(frame, read)
                        } catch (e: RuntimeException) {
                            // One broken sink must not take the microphone down with it.
                            onError(e)
                        }
                    }
                }
            } finally {
                // The reader owns the release, and it is the only thread that can do it
                // safely: releasing an AudioRecord from elsewhere while read() is blocked in
                // native code is a crash, not an exception. The effects go with the session
                // they were attached to, and must go first: they hold a handle on it. Both
                // checks are the same guard — a reader that is shutting down while the mic has
                // already been reopened must release its own session's things, not the new
                // session's.
                if (record === opened) record = null
                release(attached)
                opened.release()
            }
        }
    }

    /**
     * Attaches the platform's echo canceller and noise suppressor to [sessionId], if it has any.
     *
     * Only for a profile that wants them, and only where the device offers them.
     * `create()` returning null is the normal negative answer on hardware that has no such
     * effect — not an error, and never a reason to fail the microphone: a panel that cannot
     * cancel its own echo still hears people in a quiet room, and a panel that refuses to open
     * the microphone hears nobody at all.
     */
    private fun attachEffects(sessionId: Int): List<AudioEffect> {
        if (!profile.wantsPlatformEffects) return emptyList()
        return buildList {
            if (AcousticEchoCanceler.isAvailable()) {
                addIfEnabled(AcousticEchoCanceler.create(sessionId), "echo canceller")
            } else {
                Log.i(TAG, "no platform echo canceller on this device")
            }
            if (NoiseSuppressor.isAvailable()) {
                addIfEnabled(NoiseSuppressor.create(sessionId), "noise suppressor")
            }
        }
    }

    private fun MutableList<AudioEffect>.addIfEnabled(effect: AudioEffect?, what: String) {
        if (effect == null) {
            Log.i(TAG, "$what unavailable for this session")
            return
        }
        try {
            effect.enabled = true
            add(effect)
        } catch (e: IllegalStateException) {
            // The effect exists but this session will not take it. Nothing to do but let go.
            Log.w(TAG, "$what refused to enable", e)
            effect.release()
        }
    }

    private fun release(attached: List<AudioEffect>) {
        if (effects === attached) effects = emptyList()
        attached.forEach { it.release() }
    }

    companion object {
        private const val TAG = "Dobby"

        /** What openWakeWord requires, and what Parakeet and Silero VAD are configured for. */
        const val SAMPLE_RATE: Int = 16_000

        /**
         * 80 ms at [SAMPLE_RATE] — openWakeWord's frame (`dobby-plan.md` §5.1).
         *
         * Its front-end accepts multiples of 80 ms, longer frames buying efficiency at the
         * cost of detection latency; on a panel you speak to, latency is the thing you feel.
         * Nothing downstream cares — the capture buffer takes any length and the VAD windows
         * internally — so the wake word sets it.
         */
        const val FRAME_LENGTH: Int = 1280

        private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        private const val BYTES_PER_SAMPLE = 2
        private const val BUFFER_FRAMES = 8
    }
}
