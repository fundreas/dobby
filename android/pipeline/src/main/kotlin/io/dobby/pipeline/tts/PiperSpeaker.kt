package io.dobby.pipeline.tts

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.HandlerThread
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.Executors

/**
 * A Piper voice, synthesised sentence by sentence and played as it arrives.
 *
 * One VITS graph, ~114 MB on disk and ~150 MB resident, run through sherpa-onnx's `OfflineTts`
 * — the same `libsherpa-onnx-jni.so` the recogniser uses, with espeak-ng and ONNX Runtime
 * already inside it. Nothing native is added by this class; what it adds is an `AudioTrack`,
 * which is the first one in the project.
 *
 * **Streaming by sentence is the whole design.** `maxNumSentences = 1` makes
 * `generateWithCallback` deliver one callback per sentence, and each one is written to a
 * playing track — so a three-sentence Help answer starts speaking after the first sentence is
 * synthesised rather than after the third. On this device that is the difference between one
 * second of silence and three.
 *
 * **One dedicated thread.** Synthesis is CPU-bound and blocks; the JNI callback arrives on the
 * calling thread; and a blocking `AudioTrack.write` on a `Dispatchers.Default` worker would
 * starve the wake word's inference on the audio path. A thread of its own also serialises
 * sentences for free.
 *
 * **No audio focus.** Inside a turn, M2b's duck already holds it with these same words —
 * `USAGE_ASSISTANT` / `CONTENT_TYPE_SPEECH`. Outside a turn the only caller is a Sock's
 * `announce()`, and the Clock Sock already holds transient focus while it rings. A third
 * request from the same process over the top of those two is not this class's to make.
 */
class PiperSpeaker(
    private val files: VoiceFiles,
    private val dataDir: File,
    private val gain: Float = 1f,
    private val numThreads: Int = DEFAULT_THREADS,
) : Speaker {

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "dobby-tts")
    }
    private val dispatcher = executor.asCoroutineDispatcher()
    private val worker = CoroutineScope(SupervisorJob() + dispatcher)

    /** Somewhere for `AudioTrack` to deliver the end-of-audio marker that is not the TTS thread. */
    private val markerThread = HandlerThread("dobby-tts-marker").apply { start() }
    private val markerHandler = Handler(markerThread.looper)

    @Volatile
    private var tts: OfflineTts? = null

    /** Set once a graph has refused to load, so every sentence does not pay for it again. */
    @Volatile
    private var loadFailed = false

    /** Set by [release]: the graph is gone and the next sentence belongs to another voice. */
    @Volatile
    private var freed = false

    /** Read by the synthesis callback and by the write loop. [stop]'s only mechanism. */
    @Volatile
    private var stopped = false

    /** The track currently playing, so [stop] can reach it from another thread. */
    @Volatile
    private var track: AudioTrack? = null

    override suspend fun awaitReady(): Boolean = withContext(dispatcher) { load() != null }

    /**
     * Speaks [text] and returns once the audio has finished playing.
     *
     * Runs on the TTS thread through an [async] belonging to *this* object's scope rather than
     * the caller's: the JNI call cannot be interrupted, so cancelling the caller has to arrive
     * as [stop] — the flag the callback and the write loop read — and not as a coroutine
     * cancellation the blocked thread would never notice.
     */
    override suspend fun say(text: String): Boolean {
        if (text.isBlank()) return false
        val task = worker.async { speak(text) }
        return try {
            task.await()
        } catch (e: CancellationException) {
            stop()
            throw e
        }
    }

    /**
     * Stops the sentence in flight.
     *
     * The flag ends the synthesis at the next callback and the next write; `pause` and `flush`
     * throw away what is already queued in the track, which is up to a second of audio.
     */
    override fun stop() {
        stopped = true
        val playing = track ?: return
        try {
            playing.pause()
            playing.flush()
        } catch (_: IllegalStateException) {
            // Released between the read above and here. Nothing to stop.
        }
    }

    /**
     * Frees the graph, keeping the voice selected.
     *
     * `dobby-plan.md` §9's order of retreat, one line below Tier 2's KV cache: ~150 MB given
     * back at `RUNNING_CRITICAL`. The next sentence is spoken by the system voice — [say]
     * returns false for it — while the reload runs behind it on the same thread, so the retreat
     * costs one sentence in the wrong voice rather than a second of silence in the right one.
     */
    suspend fun release() = withContext(dispatcher) {
        tts?.free()
        tts = null
        freed = true
    }

    override fun shutdown() {
        stop()
        worker.cancel()
        // On the same thread the synthesis runs on, so the pointer is never freed underneath a
        // generate that is still using it. Queued behind whatever was in flight.
        executor.execute {
            tts?.free()
            tts = null
        }
        executor.shutdown()
        markerThread.quitSafely()
    }

    /** On the TTS thread. */
    private suspend fun speak(text: String): Boolean {
        if (freed) {
            freed = false
            // Queued behind this call on the single thread, so it reloads while the system
            // voice covers this sentence.
            worker.launch { load() }
            return false
        }

        val engine = load() ?: return false
        stopped = false
        val rate = engine.sampleRate()
        val output = open(rate) ?: return false
        track = output
        var frames = 0L
        try {
            output.play()
            // One callback per sentence, because maxNumSentences is 1. Returning 0 is how the
            // native side is told to stop, and it is the only way out of a long answer.
            engine.generateWithCallback(text) { samples ->
                if (stopped) {
                    0
                } else {
                    frames += write(output, samples)
                    1
                }
            }
            if (!stopped) drain(output, frames, rate)
        } catch (e: RuntimeException) {
            // sherpa-onnx reports anything it cannot synthesise as a plain runtime exception
            // from JNI. One sentence in the fallback voice beats a crashed service.
            return false
        } finally {
            track = null
            try {
                output.stop()
            } catch (_: IllegalStateException) {
                // An uninitialised track cannot be stopped, and does not need to be.
            }
            output.release()
        }
        return frames > 0
    }

    /**
     * Writes one sentence's samples, applying the voice's gain.
     *
     * `WRITE_BLOCKING`, which is what makes the track the pacing device: the thread sits in the
     * write until there is room, so synthesis of sentence *n+1* happens while sentence *n*
     * plays. Clipping is deliberate and cheap — a gain above 1 on a loud sentence is the only
     * way to get there, and a clipped sample beats a wrapped one.
     */
    private fun write(output: AudioTrack, samples: FloatArray): Int {
        if (gain != 1f) {
            for (i in samples.indices) samples[i] = (samples[i] * gain).coerceIn(-1f, 1f)
        }
        var offset = 0
        while (offset < samples.size && !stopped) {
            val written =
                output.write(samples, offset, samples.size - offset, AudioTrack.WRITE_BLOCKING)
            if (written <= 0) break
            offset += written
        }
        return offset
    }

    /**
     * Waits for the last sample to leave the track.
     *
     * [say]'s contract is that it returns when the audio has *finished playing*, and the write
     * loop above finishes up to a second early — that is the buffer. The marker is the exact
     * answer; the timeout is there because a marker that never arrives would otherwise hold the
     * microphone shut for good, and a second of slack is cheaper than that.
     */
    private suspend fun drain(output: AudioTrack, frames: Long, rate: Int) {
        if (frames <= 0 || rate <= 0) return
        val marker = frames.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val reached = CompletableDeferred<Unit>()
        val listener = object : AudioTrack.OnPlaybackPositionUpdateListener {
            override fun onMarkerReached(played: AudioTrack?) {
                reached.complete(Unit)
            }

            override fun onPeriodicNotification(played: AudioTrack?) = Unit
        }
        try {
            output.setPlaybackPositionUpdateListener(listener, markerHandler)
            output.notificationMarkerPosition = marker
            val remaining = marker - output.playbackHeadPosition
            // A marker the head has already passed never fires. Checking first is what keeps
            // the common short-sentence case from paying the timeout every time.
            if (remaining > 0) {
                val millis = remaining.toLong() * MILLIS_PER_SECOND / rate + MARKER_GRACE_MS
                withTimeoutOrNull(millis) { reached.await() }
            }
        } catch (_: IllegalStateException) {
            // Released under us by shutdown(). There is nothing left to drain.
        } finally {
            try {
                output.setPlaybackPositionUpdateListener(null)
            } catch (_: IllegalStateException) {
                // As above.
            }
        }
    }

    /**
     * A float PCM track at the model's own sample rate.
     *
     * Float because that is what sherpa hands back — normalised to −1..1 — so there is no
     * conversion step to get wrong. The rate comes from the graph and is never a constant: a
     * `medium` export somebody sideloads is 22 050 Hz today and need not be tomorrow.
     *
     * `USAGE_ASSISTANT` on the media volume, which is the lesson [Earcon] paid for: a
     * notification stream is muted outright by a phone set to vibrate, and a wall panel very
     * reasonably is.
     */
    private fun open(rate: Int): AudioTrack? {
        if (rate <= 0) return null
        val minimum = AudioTrack.getMinBufferSize(rate, CHANNEL, ENCODING)
        val oneSecond = rate * BYTES_PER_SAMPLE
        val bytes = if (minimum > 0) maxOf(minimum, oneSecond) else oneSecond
        return try {
            val output = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(ENCODING)
                        .setSampleRate(rate)
                        .setChannelMask(CHANNEL)
                        .build(),
                )
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(bytes)
                .build()
            if (output.state == AudioTrack.STATE_INITIALIZED) {
                output
            } else {
                output.release()
                null
            }
        } catch (e: IllegalStateException) {
            // The audio hardware is busy, or the device refuses the format. Mute for one
            // sentence is the fallback's problem, not a crash.
            null
        } catch (e: UnsupportedOperationException) {
            null
        }
    }

    /** On the TTS thread, always. */
    private fun load(): OfflineTts? {
        tts?.let { return it }
        if (loadFailed) return null
        return try {
            OfflineTts(
                config = OfflineTtsConfig(
                    model = OfflineTtsModelConfig(
                        vits = OfflineTtsVitsModelConfig(
                            model = files.model.absolutePath,
                            tokens = files.tokens.absolutePath,
                            dataDir = dataDir.absolutePath,
                        ),
                        numThreads = numThreads,
                    ),
                    // One callback per sentence. Without it the callback arrives once, at the
                    // end, and the streaming above is a buffer copy with extra steps.
                    maxNumSentences = 1,
                ),
            ).also { tts = it }
        } catch (e: RuntimeException) {
            // A graph that will not load, a token table that does not belong to it, or phoneme
            // data that is not there. All three arrive as a runtime exception from JNI.
            loadFailed = true
            null
        }
    }

    companion object {
        /**
         * The two big cores.
         *
         * The device is a MediaTek MT6877 — two Cortex-A78 and six A55 — and Piper's graph does
         * not scale past the fast pair. More threads means the scheduler puts work on an A55 and
         * the sentence waits for it.
         */
        const val DEFAULT_THREADS: Int = 2

        private const val ENCODING = AudioFormat.ENCODING_PCM_FLOAT
        private const val CHANNEL = AudioFormat.CHANNEL_OUT_MONO
        private const val BYTES_PER_SAMPLE = 4
        private const val MILLIS_PER_SECOND = 1000L

        /** How long a lost marker is waited for past the end of the audio. */
        private const val MARKER_GRACE_MS = 1000L
    }
}
