# M2b — Barge-in: hearing past the panel's own speaker

## Context

M2 made the panel hands-free, and it works in a quiet room. It stops working the moment the
panel plays anything. Say the wake phrase over music and the microphone hears the music too —
the utterance that reaches Parakeet is a command mixed with whatever was playing, and what
comes back is a transcript of the room rather than of the person in it.

This is not a wake-word bug. [AudioSource.kt:116](android/pipeline/src/main/kotlin/io/dobby/pipeline/audio/AudioSource.kt#L116)
opens the microphone with `MediaRecorder.AudioSource.VOICE_RECOGNITION`, which is *defined* as
the source with no AGC, no noise suppression and **no echo cancellation** — it hands the
recogniser the least-processed signal the hardware can give, because in a quiet room that is
the best thing for ASR. The panel hears itself because nothing was ever asked to stop it.

`dobby-plan.md` does not mention acoustic echo cancellation anywhere: not in §5.1, not in §5.2,
not in §9's accepted tradeoffs. It is a gap in the spec rather than a decision being worked
against, and it lands on the use case the panel exists for. Spotify is M1 and Radio is M4;
"start, stop and change the music" is the thing people will say to a wall most often, and it is
exactly the thing that cannot be said while music is playing.

**Two failures, not one, and they have different fixes.**

1. **The command is polluted.** The wake word fired — the person got through — but the audio
   captured for the command carries the music under it. Silero VAD, watching that stream, never
   finds its ~800 ms of trailing silence, so every turn runs to the 10 s hard cap and Parakeet
   is handed ten seconds of music with a sentence somewhere inside. Fixable without echo
   cancellation, by ducking. This is most of the pain and nearly all of the cheap relief.
2. **The wake word cannot be heard over the music**, and the music can *say* the wake word.
   Only echo cancellation fixes this, and on this device only the platform's.

Part A is (1). Part B is (2). They are independent and Part A is worth shipping alone.

---

## What is true today that this plan relies on

- **One `AudioRecord`, many sinks.** [AudioSource](android/pipeline/src/main/kotlin/io/dobby/pipeline/audio/AudioSource.kt)
  is the only code that touches the microphone; the source constant is hard-coded at line 116
  and everything else about it (16 kHz, mono, 1280-sample frames) is fixed by openWakeWord.
- **The wake word already sits out the turn.**
  [VoicePipeline.listen](android/pipeline/src/main/kotlin/io/dobby/pipeline/VoicePipeline.kt#L308)
  detaches the detector before capture and `endTurn` puts it back, and
  [say](android/pipeline/src/main/kotlin/io/dobby/pipeline/VoicePipeline.kt#L404) resets it
  after speaking. **Self-triggering on Dobby's own TTS is therefore already solved.** What is
  left is music, which plays across the whole turn *and* between turns — precisely the window
  the existing mechanism does not cover.
- **One way through, two ways in.** The wake word emits a signal and
  [DobbyController.onWakeWord](android/app/src/main/kotlin/io/dobby/android/DobbyController.kt#L305)
  calls the same `listen()` the button calls. Anything hung off the wake word alone would miss
  the button; anything hung off `listen()` covers both. `onWakeWord` already performs one
  "before the turn" side effect — `screen.wakeFor` — so a second has precedent, but it belongs
  one level down.
- **`PlaybackCoordinator` is Sock-facing and single-slot.**
  [AndroidPlayback.grant](android/app/src/main/kotlin/io/dobby/android/AndroidSockContext.kt#L71)
  abandons whatever `AudioFocusRequest` exists before making its own, and tracks exactly one
  `holder`. A turn-level duck routed through it would abandon the Radio Sock's focus and never
  give it back. **This is the trap in the obvious implementation.**
- **Ducking has test precedent.** [TimerEngine.kt:162](socks/clock/src/main/kotlin/io/dobby/socks/clock/TimerEngine.kt#L162)
  takes transient focus so a kitchen timer ducks Spotify rather than stopping it, and
  `ClockChainTest` asserts the music comes back to full volume. `FakeSockContext.duckedBy`
  exists for exactly this kind of assertion.
- **Spotify's audio never passes through Dobby.** [spotify.specs.md:11](socks.specs/spotify.specs.md#L11)
  is `spotify-app-remote` — the Spotify app decodes and plays; Dobby sends commands. There is
  no PCM to use as an echo reference, and `AudioPlaybackCapture` cannot get one either
  (Spotify sets capture-by-none). **This single fact is what makes the platform AEC the only
  option that can ever cover the main use case.**
- **Radio's audio does.** [radio.specs.md:11](socks.specs/radio.specs.md#L11) is in-process
  Media3 ExoPlayer, so Dobby owns both the samples and the player volume.
- **The wake word's thresholds are unmeasured.** `DEFAULT_THRESHOLD = 0.5f`,
  `DEFAULT_PATIENCE = 2` in [WakeWordDetector](android/pipeline/src/main/kotlin/io/dobby/pipeline/wakeword/WakeWordDetector.kt#L201)
  are placeholders; the README says §5.1's two counts have not been done. **That is an
  advantage here** — no tuning has to be thrown away, and the source can still be chosen before
  any number is committed to.

---

## Part A — Duck the turn

### A1. A turn-level duck that stacks rather than replaces

The turn is not a Sock and must not borrow a Sock's focus slot. A new, narrow interface,
owned by the turn rather than by `SockContext`:

```kotlin
package io.dobby.core.audio

/**
 * Attenuates whatever is playing for the length of one turn.
 *
 * Deliberately not on PlaybackCoordinator: that one arbitrates *between Socks*, is keyed by
 * sock id and holds exactly one request. This is the panel interrupting all of them at once,
 * for as long as somebody is talking to it, and it has to leave the Sock-level arbitration
 * exactly as it found it.
 */
interface TurnAudio {
    suspend fun duck()
    suspend fun release()
}
```

The Android implementation has **two channels, because the audio has two origins**:

- **Out of process (Spotify, and anything else on the device).** Its own `AudioFocusRequest`,
  separate object from `AndroidPlayback`'s, `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK` (or
  `..._TRANSIENT`; see A3), abandoned on `release()`. The system does the attenuation.
- **In process (Radio's ExoPlayer, in M4).** Audio focus is the wrong tool for ducking
  yourself. A registry of in-process players that `duck()` attenuates directly —
  `player.volume = DUCK_VOLUME` — and `release()` restores. Radio does not exist yet, so this
  plan defines the seam and **adds the requirement to [radio.specs.md](socks.specs/radio.specs.md)**
  rather than leaving M4 to discover it.

### A2. Where it hooks

In [DobbyController.listen](android/app/src/main/kotlin/io/dobby/android/DobbyController.kt#L364),
inside `turn.withLock`: duck before the loop, release in the **same `finally`** that already
calls `engine.endTurn()` and `pipeline.endTurn()`. That placement is not incidental:

- It covers the button and the wake word, because both arrive here. Hanging it off
  `onWakeWord` would half-fix it.
- It is turn-scoped, not utterance-scoped. A turn is up to three utterances plus Dobby's spoken
  answers, and un-ducking between them would let the music swell back up in the gaps — which is
  worse than not ducking at all, because it happens exactly while the person is waiting to
  speak again.
- The `finally` is already the place that guarantees the wake word comes back. A duck that
  outlived a thrown turn would be a panel that permanently quietened the music, which is a more
  annoying failure than the one being fixed.

`submit()` — the typed path — does **not** duck. No microphone is open.

### A3. Duck or pause is a setting, and the default is decided by measurement

`AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK` leaves the music quietly playing;
`AUDIOFOCUS_GAIN_TRANSIENT` pauses it. Pausing is better for recognition and worse to live
with — it is heavier, and for Radio it means a stream re-buffer on every turn
([radio.specs.md:193](socks.specs/radio.specs.md#L193) already budgets reconnects). Ducking is
gentler and may not be enough.

Both go in behind `Settings.turnDuck: TurnDuck { DUCK, PAUSE }`, default `DUCK`, and **the
default is re-decided from the Part B measurement rather than argued about now.** This project
measures; this is a case for it.

### A4. What A actually buys

The duck is what makes the VAD's 800 ms endpoint reachable again. That is the load-bearing
consequence and the one to assert: with music ducked, a turn ends when the person stops talking
instead of at the 10 s cap, which takes about eight seconds out of every interaction and stops
Parakeet being handed a haystack.

It does **not** help the wake word, which has to be heard *before* there is a turn to duck. And
there is a lag: Spotify's fade is ~100–300 ms, so the first moments after the wake word are
still loud. `SPEECH_WINDOW` is 5 s, so there is room, but a very fast talker will clip the tail
of the fade.

---

## Part B — Echo cancellation

### B1. Make the microphone source a choice

`AudioSource` takes the source as a constructor parameter rather than hard-coding it —
threaded through `VoicePipeline` from `Settings`. **The default does not change in this step.**

```kotlin
enum class MicProfile(val source: Int) {
    /** Unprocessed, what M2 shipped. Best in a quiet room, deaf to nothing but itself. */
    RECOGNITION(MediaRecorder.AudioSource.VOICE_RECOGNITION),

    /** The telephony chain: platform AEC, noise suppression and AGC. */
    COMMUNICATION(MediaRecorder.AudioSource.VOICE_COMMUNICATION),
}
```

### B2. What `VOICE_COMMUNICATION` engages, and what it costs

It requests the platform's voice-call processing chain, which is where the hardware echo
canceller lives. On top of it, attach `AcousticEchoCanceler` and `NoiseSuppressor` to the
`AudioRecord`'s `audioSessionId` when `isAvailable()` — usually already enabled for this source
and a no-op otherwise. `create()` returning null is the normal negative answer, not an error,
and must not fail the microphone.

The cost is real and has to be written down:

- **The AEC's reference signal is HAL-dependent.** Whether the *media* mix is in the echo
  reference on this Nord CE, or only a voice-call downlink, is an empirical question about
  Qualcomm's audio HAL. It cannot be read off the API.
- **AGC and noise suppression are tuned for telephony, not for ASR.** openWakeWord's graphs
  were trained on unprocessed audio; the score distribution will shift, so
  `DEFAULT_THRESHOLD` and `DEFAULT_PATIENCE` become **per-profile** constants. Parakeet may get
  worse too, in the quiet room that is the common case.
- **`AudioManager.setMode(MODE_IN_COMMUNICATION)` is out of bounds.** On some devices it is
  what fully engages the AEC path; it also reroutes audio to the earpiece and turns the volume
  rocker into a call-volume control. On a wall panel that is unshippable. If measurement shows
  the AEC only works in that mode on this device, that is a **finding that kills B2**, and the
  answer becomes A3's `PAUSE` default plus "no barge-in during playback".

### B3. Two measurements, two harnesses

The question "what threshold?" and the question "does the AEC work?" are different and should
not be measured with the same effort.

**Offline, on the JVM — the threshold curve.** [WakeWordModelContractTest](android/pipeline/src/test/kotlin/io/dobby/pipeline/wakeword/WakeWordModelContractTest.kt)
already runs the real openWakeWord graphs without an emulator. Extend the same approach: mix
recorded wake phrases with recorded music at a sweep of SNRs and score them. That gives the
detection curve against interference for a few minutes of compute, and it is repeatable. It
measures the *detector*, not the echo canceller — which is the point; it isolates one variable.

**On the device — the AEC itself, and §5.1's overdue counts.** A 2×2:

| | silent room | music at panel volume |
|---|---|---|
| `RECOGNITION` | *(the baseline M2 never took)* | *(today's failure, quantified)* |
| `COMMUNICATION` | | |

Each cell: say the phrase 50 times and count false rejects; leave it armed through an evening
and count false accepts. Plus the `SttTemplateTest` pass rate per profile, which is the
existing instrumented harness for "did the recogniser get worse".

Four cells is a lot of standing in a room saying "Hey Jarvis". It is also the entire basis for
choosing the default, and it is work the README already owes.

### B4. Self-triggering: two sources that look alike

If the radio says "Hey Jarvis" through the panel's own speaker, that is **echo of the panel's
own output** and the AEC is exactly the right tool. If a television across the room says it,
that is a second sound source and no echo canceller can touch it — that one is threshold,
patience, and the `MAX_CLARIFY_ROUNDS` bound that already stops a television holding the
microphone open all evening. Worth separating, because the first is fixable here and the second
is not.

---

## Locked decisions

- **The turn duck lives in `DobbyController`.** Not in `VoicePipeline`, which knows nothing
  about Socks or playback and should keep not knowing; not in `PlaybackCoordinator`, which is
  Sock-facing and single-slot.
- **The turn duck gets its own `AudioFocusRequest` and never touches the Sock's.**
- **The duck is turn-scoped**, taken before the first utterance and released in the `finally`
  that re-arms the wake word.
- **The microphone source becomes configurable; the default does not move until measured.**
- **No `MODE_IN_COMMUNICATION`.**
- **No software AEC in this plan.**
- **Thresholds become per-profile**, because a number measured under one is meaningless under
  the other.

---

## Verification

JVM, no emulator, as everything else in this project is:

- **`TurnDuckTest`** — the duck is taken before the first `pipeline.listen()`; released exactly
  once per turn, not once per utterance; released when a Sock throws, when the mic is
  unavailable, and when the turn ends on `Ended`; the button path ducks identically to the wake
  word path; `submit()` does not duck.
- **The clobber case** — a turn duck taken while Clock holds transient focus leaves Clock's
  focus intact and its `holder` unchanged. This is the trap from A1 and the reason `TurnAudio`
  is a separate interface; it should fail loudly if someone later routes it through
  `PlaybackCoordinator`.
- **The endpoint** — with the duck active, a turn ends on the VAD's silence rather than the
  10 s cap. A4's whole claim, asserted.
- **Per-profile threshold constants** exist and are wired from the profile, so a future edit
  cannot silently apply the quiet-room number to the processed stream.
- **The SNR sweep** (B3) as an opt-in test alongside `WakeWordModelContractTest`, skipping
  itself when the fixtures are absent, as `SttTemplateTest` already does.

Instrumented: `SttTemplateTest` run under both profiles, pass rate recorded in the README.

---

## Sequence

0. **Reproduce and quantify first.** Play radio, say the wake phrase, capture what Parakeet
   actually returned and how long the turn took. Without this there is no way to tell A1 from
   a placebo.
1. **Part A** — `TurnAudio`, the controller hook, the setting. Ship it; it stands alone.
2. **Radio's half of A1 into [radio.specs.md](socks.specs/radio.specs.md)** so M4 builds it in
   rather than discovering it.
3. **B1** — the source becomes a setting, default unchanged. One line of behaviour change: none.
4. **B3** — the offline sweep, then the 2×2 on the device. This is also §5.1, finally done.
5. **Lock the default profile, the per-profile thresholds, and A3's duck-or-pause** from the
   numbers. Update the README's "Before you trust it".

Steps 1–3 are days. Step 4 is an evening in a room and is the long pole.

---

## Open risks

- **The platform AEC may not reference the media mix on this device.** Then barge-in during
  playback is not supported, `PAUSE` becomes the A3 default, and the panel is one that stops the
  music to listen. That is a worse product than the one intended and may simply be the true
  answer for this hardware; it is better to find it in step 4 than after building AEC3.
- **`VOICE_COMMUNICATION` may cost more in the quiet room than it buys over music.** The quiet
  room is the common case. The fallback design if so: **switch profiles dynamically** —
  `RECOGNITION` when nothing is playing, `COMMUNICATION` when something is. `AudioSource` can
  reopen the recorder because it is the only thing allowed to, and the switch happens on
  playback start/stop rather than mid-turn. It costs a re-open and a brief deaf gap, and it
  breaks the "the mic is open exactly while something is listening" comment's simplicity, so it
  is a fallback and not the opening move.
- **Requesting focus while our own app already holds it** (turn duck over a playing Radio) has
  in-app listener semantics worth verifying before relying on them — which is the other reason
  the in-process channel attenuates the player directly instead.
- **Physics.** Speaker and microphone on one handset, near-field, against a voice from three
  metres: the echo arrives far louder than the person. Hardware AEC buys perhaps 20–30 dB, and
  above some playback volume nothing in software saves it. A volume cap, or a speaker that is
  not the phone, may end up mattering as much as any of this.

---

## Not in this plan

- **WebRTC AEC3 with Dobby's own playback as the reference.** Correct, device-independent, and
  only ever able to cover Radio — Spotify's samples do not exist in this process. It needs a
  native build, a reference tap and delay alignment via `AudioTrack.getTimestamp()`. It is its
  own plan, and it is worth writing only if step 4 says the platform AEC is inadequate *and*
  radio-only coverage is worth having.
- **`AudioPlaybackCapture` as a reference for other apps.** Blocked by Spotify's capture policy.
  Recorded here so it is not re-investigated.
- **Multi-microphone beamforming.** The device has more than one mic; `VOICE_RECOGNITION` mono
  gives the processed single channel and reliable per-mic access is not a thing across Android.
- **The Radio Sock itself.** M4.
- **An external speaker.** A hardware answer to a software plan, and the right thing to consider
  only once the numbers in step 4 exist.
