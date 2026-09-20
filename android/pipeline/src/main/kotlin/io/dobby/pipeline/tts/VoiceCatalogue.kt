package io.dobby.pipeline.tts

import io.dobby.pipeline.stt.RemoteFile

/**
 * One voice the panel can speak with.
 *
 * @param id stable key, persisted in settings. Never change it for an existing entry — a
 *   renamed id silently resets somebody's choice back to the default.
 * @param name what the settings row says.
 * @param language BCP-47, as the voice was trained: `de-DE`, `en-GB`. Empty for the system
 *   voice, which is whatever the phone has — and read as German, which is what a phone in this
 *   kitchen speaks.
 *
 *   **This tag is the answer language.** `io.dobby.core.sock.Lang.of` maps it, and the panel
 *   reads it for every sentence a Sock hands back: choosing Cori chooses English, choosing
 *   Thorsten or the Android voice chooses German. `m2c-plan.md` Part E sketched the other
 *   direction — a language setting the voice follows — and this is the inversion of it, for the
 *   reason that decided it: the voice is the control people reach for, and it is the one whose
 *   wrong value is audible. Understanding stays German either way (Part E again): the Tier 1
 *   palette and the Tier 2 few-shots are German, so an English-answering panel is still spoken
 *   to in German.
 * @param description one line under the name, in the settings screen.
 * @param directory where the files live under `files/voices/`. Empty for the system voice.
 * @param files model and token table. Empty for the system voice, which downloads nothing.
 * @param gain linear, applied to the samples before they are written. Piper voices are
 *   normalised per dataset and not against each other: Cori measures about 5 dB below
 *   Thorsten on the same sentences.
 * @param greeting the one sentence Dobby says when this voice is chosen, in the voice itself.
 *   A voice is chosen by ear, and a radio button that changes nothing you can hear until the
 *   next timer fires is a setting nobody trusts. Blank falls back to "Ich bin <name>."
 */
data class VoiceOption(
    val id: String,
    val name: String,
    val language: String,
    val description: String,
    val directory: String,
    val files: List<RemoteFile>,
    val gain: Float = 1f,
    val greeting: String = "",
) {
    /** The phone's own `TextToSpeech`, which needs no files and no directory. */
    val isSystem: Boolean get() = files.isEmpty() && directory.isEmpty()

    /** What the panel says the moment this voice takes over. */
    val spokenGreeting: String get() = greeting.ifBlank { "Ich bin $name." }
}

/**
 * The voices offered in settings.
 *
 * Two [Piper](https://github.com/rhasspy/piper) voices and the phone's own. Piper voices are a
 * single VITS graph plus a token table, run offline through sherpa-onnx's `OfflineTts` — the
 * same JNI library the recogniser already uses, with espeak-ng inside it. Both are permissively
 * licensed (Thorsten is CC0, Cori is public domain), which is what makes them shippable where
 * the wake-word heads are not.
 *
 * The files come from the `csukuangfj` conversions rather than from `rhasspy/piper-voices`:
 * sherpa reads the voice's metadata out of the graph, and only the converted exports carry it.
 * The URLs are pinned to a **revision** for the same reason
 * [io.dobby.pipeline.stt.SpeechModels] pins Parakeet's — a checksum against a moving branch is
 * a download that breaks the day upstream re-uploads a file.
 *
 * **The system voice is a catalogue entry, not a special case.** It is the voice that speaks on
 * first run while Thorsten downloads, the fallback when a download fails or a graph will not
 * load, and the way back if a Piper voice turns out to mispronounce something the platform got
 * right. Keeping that path alive with a name is cheaper than keeping it alive as dead code.
 */
object VoiceCatalogue {

    /** Where voices live under the model root. Also what the sideload recipe writes into. */
    const val DIRECTORY: String = "voices"

    private const val THORSTEN_DIRECTORY = "vits-piper-de_DE-thorsten-high"
    private const val CORI_DIRECTORY = "vits-piper-en_GB-cori-high"

    private const val THORSTEN_REVISION = "d0d70c92994440adabb10804fdbe9f075f066c7b"
    private const val CORI_REVISION = "37f6efb503f5dc22de01bad04c9a118a99111096"

    /**
     * German, male, 22.05 kHz — and the default.
     *
     * Trained on [Thorsten-Voice](https://github.com/thorstenMueller/Thorsten-Voice), released
     * CC0, fine-tuned from the US-English `lessac` high voice. `high` rather than `medium`
     * because the device has the headroom for it; see *Measured* in `m2c-plan.md` for the
     * real-time factor that decided it.
     */
    val THORSTEN: VoiceOption = VoiceOption(
        id = "thorsten",
        name = "Thorsten",
        language = "de-DE",
        description = "Deutsch · 114 MB · Antwortet auf Deutsch.",
        directory = THORSTEN_DIRECTORY,
        files = listOf(
            voice(THORSTEN_DIRECTORY, THORSTEN_REVISION, "de_DE-thorsten-high.onnx",
                "d3d0f8fc180fd28b64a286452572e4ea0716e45ac068ee6ee678f89777034e39", 113_895_328),
            voice(THORSTEN_DIRECTORY, THORSTEN_REVISION, TOKENS,
                "87c8ef66eae5473ed0cc0366b3964c736ca6c5f676c979522ea31234e47430b9", 921),
        ),
        greeting = "Ich bin Thorsten.",
    )

    /**
     * British English, female, 22.05 kHz — **and the English answer language**.
     *
     * LibriVox recordings in the public domain, ~24 h, trained from scratch by Bryce Beattie.
     *
     * Choosing her used to be choosing pronunciation: she read German sentences with English
     * phonemes, because nothing could yet change *what* was said. It now changes both — every
     * Sock builds its answer from a `Phrase`, and [language] is what selects the wording. What
     * does **not** change is understanding: commands are still spoken to the panel in German.
     *
     * The gain is not cosmetic: on the same sentences she peaks about 5 dB below Thorsten, so
     * switching voices would otherwise also be switching volume.
     */
    val CORI: VoiceOption = VoiceOption(
        id = "cori",
        name = "Cori",
        language = "en-GB",
        description = "Englisch (britisch) · 114 MB · Antwortet auf Englisch. " +
            "Befehle bleiben deutsch.",
        directory = CORI_DIRECTORY,
        files = listOf(
            voice(CORI_DIRECTORY, CORI_REVISION, "en_GB-cori-high.onnx",
                "006bb4db48e066f7f1be91d218db3b76617a707196271694ca6455d7bbd13842", 114_219_480),
            voice(CORI_DIRECTORY, CORI_REVISION, TOKENS,
                "ef3a7e4a8d1af0c9d4dc45aaae1a6242ebe24a7ed6f3d025a49eb29682784c6d", 940),
        ),
        // English, which is now the whole of what picking her does — and hearing it said is
        // the fastest honest answer to "what does picking Cori do?".
        greeting = "Hello, I'm Cori.",
    )

    /** Whatever the phone's `TextToSpeech` offers, which is what spoke before this milestone. */
    val SYSTEM: VoiceOption = VoiceOption(
        id = "system",
        name = "Android-Stimme",
        language = "",
        description = "Die Stimme des Telefons, wie bisher. Antwortet auf Deutsch.",
        directory = "",
        files = emptyList(),
        greeting = "Ich bin die Android-Stimme.",
    )

    val ALL: List<VoiceOption> = listOf(THORSTEN, CORI, SYSTEM)

    val DEFAULT: VoiceOption = THORSTEN

    /**
     * The chosen voice, falling back to the default.
     *
     * A selection can name something that is gone — a sideloaded voice that was deleted, or a
     * value written by an older build. Falling back beats throwing on a settings file, which is
     * the one input that can arrive from a version of the app that no longer exists.
     */
    fun of(id: String?): VoiceOption = ALL.firstOrNull { it.id == id } ?: DEFAULT

    /** Matches a directory on disk back to its catalogue entry, so a sideload is not a duplicate. */
    fun byDirectory(name: String): VoiceOption? =
        ALL.firstOrNull { it.directory.isNotEmpty() && it.directory == name }

    /** Every Piper voice's token table is called this, which is also how a sideload is spotted. */
    const val TOKENS: String = "tokens.txt"

    private fun voice(
        directory: String,
        revision: String,
        name: String,
        sha256: String,
        bytes: Long,
    ) = RemoteFile(
        path = "$DIRECTORY/$directory/$name",
        url = "https://huggingface.co/csukuangfj/$directory/resolve/$revision/$name",
        sha256 = sha256,
        bytes = bytes,
    )
}
