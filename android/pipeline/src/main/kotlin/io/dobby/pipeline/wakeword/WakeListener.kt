package io.dobby.pipeline.wakeword

import io.dobby.pipeline.audio.FrameSink
import java.io.Closeable

/**
 * One detection: the panel's name was heard.
 *
 * @param score how sure the detector is — the classifier's own output, or, for
 *   [TranscriptWakeWord], how closely the transcript matched the phrase. Only ever displayed.
 * @param rest what was said *after* the phrase in the same breath, already separated out, or ""
 *   when the phrase stood alone. The classifier can never fill this in — it fires mid-phrase
 *   and has no idea what followed — but a recogniser that has just read "hey dobby spiele
 *   musik" knows exactly what the command was, and throwing it away would make somebody say it
 *   twice.
 */
data class WakeWord(val score: Float, val rest: String = "")

/**
 * Something that sits on the microphone stream and says when the panel's name was said.
 *
 * The seam the two approaches meet at (see [WakeMode]). Everything in [io.dobby.pipeline]
 * that arms, parks and re-arms the wake word does it through this interface and never through
 * one of the implementations, so adding the second way of listening did not add a second way of
 * arming it — which is the thing that would have drifted.
 */
interface WakeListener : FrameSink, Closeable {

    /** What the panel answers to, for the status line to name. */
    val phrase: String

    /**
     * Forget everything heard so far, and listen again from now.
     *
     * Called whenever the stream this listener sees has a hole in it or a lie in it: the
     * microphone was closed for a turn, or Dobby has been speaking into it. For
     * [TranscriptWakeWord] it is also the re-arm — it goes deliberately deaf the moment it
     * fires, and this is what wakes it back up.
     */
    fun reset()
}
