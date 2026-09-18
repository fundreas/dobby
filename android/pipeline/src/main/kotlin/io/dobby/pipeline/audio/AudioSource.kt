package io.dobby.pipeline.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
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
 * There is exactly one microphone and, in M2, there will be two things that want it at the
 * same time: the wake word and the recognizer. Android will not open the mic twice, so the
 * split happens here — the reader loop is the only code that touches `AudioRecord`, and
 * everything downstream is a sink. Adding Porcupine later is [addSink], not a rewrite.
 *
 * Frame length and sample rate are Porcupine's (512 samples at 16 kHz) for the same reason:
 * the wake word cannot choose, and Vosk does not care.
 */
class AudioSource(
    val sampleRate: Int = SAMPLE_RATE,
    val frameLength: Int = FRAME_LENGTH,
) {
    /** Where a failure on the audio thread goes. Set by the owner; there is nobody to throw to. */
    var onError: (Throwable) -> Unit = {}
    private val sinks = CopyOnWriteArrayList<FrameSink>()

    @Volatile
    private var record: AudioRecord? = null
    private var reader: Job? = null

    val isRunning: Boolean get() = reader?.isActive == true

    fun addSink(sink: FrameSink) {
        sinks += sink
    }

    fun removeSink(sink: FrameSink) {
        sinks -= sink
    }

    /**
     * Opens the microphone and starts routing frames.
     *
     * @throws MicrophoneUnavailableException if the mic cannot be opened — which on Android 12+
     * is the normal outcome when the service was not started from a foreground activity
     * (`dobby-plan.md` §7.1), not an exotic failure.
     */
    @SuppressLint("MissingPermission")
    fun start(scope: CoroutineScope) {
        if (isRunning) return

        val minBuffer = AudioRecord.getMinBufferSize(sampleRate, CHANNEL, ENCODING)
        if (minBuffer <= 0) {
            throw MicrophoneUnavailableException("sample rate ${sampleRate}Hz is not supported")
        }

        val opened = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
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

        opened.startRecording()
        if (opened.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
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
                // native code is a crash, not an exception.
                if (record === opened) record = null
                opened.release()
            }
        }
    }

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

    companion object {
        /** What Vosk's German model expects, and the only rate Porcupine accepts. */
        const val SAMPLE_RATE: Int = 16_000

        /** Porcupine's frame length. Vosk is indifferent, the wake word is not. */
        const val FRAME_LENGTH: Int = 512

        private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        private const val BYTES_PER_SAMPLE = 2
        private const val BUFFER_FRAMES = 8
    }
}
