package io.dobby.pipeline.wakeword

/**
 * One phrase the panel can be made to answer to.
 *
 * @param id stable key, persisted in settings. Never change it for an existing entry.
 * @param phrase what the settings screen and the status line say.
 * @param file the classifier's filename on disk, which is also how it is recognised again.
 * @param sha256 pinned: these come from a community repository whose contents can change under
 *   a fixed URL, and a silently different classifier is a panel that stops answering with no
 *   error anywhere.
 */
data class WakeWordOption(
    val id: String,
    val phrase: String,
    val file: String,
    val url: String,
    val sha256: String,
)

/**
 * The phrases offered in settings.
 *
 * Only one is ever active. The three graphs split into a shared front-end and a per-phrase
 * classifier head, so listening for several at once would cost almost nothing — but every
 * active phrase is more surface for the television to trip, and a panel that wakes up on its
 * own is worse than one you have to name precisely.
 *
 * All are English-trained (openWakeWord's synthetic voices are English), which is the accepted
 * trade in `dobby-plan.md` §4. Community heads are ~200 KB; the official `hey_jarvis` is 1.3 MB
 * because it uses a larger architecture. Both work: the number of feature frames a head expects
 * is read from its own graph rather than assumed.
 */
object WakeWordCatalogue {

    private const val OPENWAKEWORD_RELEASE =
        "https://github.com/dscripka/openWakeWord/releases/download/v0.5.1"

    private const val COMMUNITY =
        "https://raw.githubusercontent.com/fwartner/home-assistant-wakewords-collection/main/en"

    /** The default. openWakeWord's own model, and the best-trained of the set. */
    val DEFAULT: WakeWordOption = WakeWordOption(
        id = "hey_jarvis",
        phrase = "Hey Jarvis",
        file = "hey_jarvis_v0.1.onnx",
        url = "$OPENWAKEWORD_RELEASE/hey_jarvis_v0.1.onnx",
        sha256 = "94a13cfe60075b132f6a472e7e462e8123ee70861bc3fb58434a73712ee0d2cb",
    )

    val ALL: List<WakeWordOption> = listOf(
        DEFAULT,
        WakeWordOption(
            id = "dumbledore",
            phrase = "Dumbledore",
            file = "Dumbledore.onnx",
            url = "$COMMUNITY/Dumbledore/Dumbledore.onnx",
            sha256 = "1ea5d480596530214c0cf137106462b3e5b8b31879c25b9ba56a2983814e2d73",
        ),
        WakeWordOption(
            id = "hola_casita",
            phrase = "Hola Casita",
            file = "Hola_casita.onnx",
            url = "$COMMUNITY/hola_casita/Hola_casita.onnx",
            sha256 = "cfb70acbfc8fedddbfdf0fcf7572e656520b6219327f7bddf63339e67a62f48a",
        ),
        WakeWordOption(
            id = "scooby",
            phrase = "Scooby",
            file = "Scooby.onnx",
            url = "$COMMUNITY/scooby/Scooby.onnx",
            sha256 = "ba6e9467ece651a918e2249705ed16fa24dfaa3a98f903a146b62516d947e73c",
        ),
        WakeWordOption(
            id = "wall_e",
            phrase = "Wall-E",
            file = "wall-e.onnx",
            url = "$COMMUNITY/wall-e/wall-e.onnx",
            sha256 = "24ca41f1fc35bb5b889bc2412da4e84d684f6eb8d15cdd80217b4c23fe414b96",
        ),
        WakeWordOption(
            id = "janet",
            phrase = "Janet",
            file = "Janet.onnx",
            url = "$COMMUNITY/janet/Janet.onnx",
            sha256 = "a3d0f9cffbba5abe6d9726768a575d5c356cf8bed13a3b583864884af948c3fa",
        ),
    )

    fun byId(id: String?): WakeWordOption? = ALL.firstOrNull { it.id == id }

    /** Matches a downloaded file back to its catalogue entry. */
    fun byFile(name: String): WakeWordOption? = ALL.firstOrNull { it.file == name }
}
