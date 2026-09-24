package io.dobby.android

import io.dobby.core.registry.Introspection
import io.dobby.core.sock.Sock
import io.dobby.socks.calculator.CalculatorSock
import io.dobby.socks.clock.ClockSock
import io.dobby.socks.conversation.ConversationSock
import io.dobby.socks.help.HelpSock
import io.dobby.socks.memo.MemoSock
import io.dobby.socks.radio.RadioPlayer
import io.dobby.socks.radio.RadioSock
import io.dobby.socks.spotify.MusicSearch
import io.dobby.socks.spotify.SpotifyCredentials
import io.dobby.socks.spotify.SpotifyPlayer
import io.dobby.socks.spotify.SpotifySock
import io.dobby.socks.spotify.IntentFallback
import io.dobby.socks.system.SystemSock
import io.dobby.socks.system.VolumeControl

/**
 * The one place that knows which Socks exist.
 *
 * Adding a Sock is a line here and nothing else — that is the property `dobby-plan.md` §8's
 * M3 is "done when", and it holds on Android exactly as it held in the terminal.
 *
 * Development Socks are not listed here at all. They come from [DevSocks], which exists twice:
 * the debug source set returns Winky, the release source set returns nothing. The plan requires
 * that Winky cannot ship (§8, M1), and a source-set split is the only version of that rule the
 * compiler enforces.
 */
object DobbySocks {

    /**
     * The Sock list, plus the callback that hands the Help Sock its view of the finished
     * registry. Help is the one Sock that needs to see the palette it is itself part of, so it
     * is bound immediately after the registry is built.
     */
    class Wiring(
        val socks: List<Sock>,
        /** Kept by name because the panel draws its clock and its timer (`clock.specs.md` §7). */
        val clock: ClockSock,
        /** Same reason: the panel draws what is playing (`spotify.specs.md` §7). */
        val spotify: SpotifySock,
        /** And the station, and its ICY title (`radio.specs.md` §7). */
        val radio: RadioSock,
        /**
         * And whether the room is silent, for the panel's speaker button
         * (`system.specs.md` §4).
         */
        val system: SystemSock,
        /** And the open memos, and which one was last read out (`memo.specs.md` §8). */
        val memo: MemoSock,
        val bindDirectory: (Introspection) -> Unit,
    )

    /**
     * @param hardware `AlarmManager` and `SoundPool` for the Clock Sock. Null off-device — the
     *   timer then runs off its coroutine alone and chimes silently, which is exactly what a
     *   unit test wants and what the terminal harness gets.
     * @param spotify the App Remote and the Web API search. Null off-device, where the Sock
     *   reports `Unavailable` and fails every command with the spoken line from its spec —
     *   which keeps the palette complete and the utterance tables assertable with no device.
     * @param system `AudioManager`. Null off-device, same bargain as the two above: the System
     *   Sock is `Unavailable`, every template still compiles and every utterance still routes.
     * @param radio the Media3 player and its turn-duck registration. Null off-device, where the
     *   Sock falls back to [RadioPlayer.NONE] — the station table, the resolver and the whole
     *   command surface still work, and only the sound is missing.
     */
    fun create(
        hardware: ClockHardware? = null,
        spotify: SpotifyHardware? = null,
        system: SystemHardware? = null,
        radio: RadioHardware? = null,
    ): Wiring {
        var directory: Introspection? = null
        val clock = if (hardware == null) {
            ClockSock()
        } else {
            ClockSock(alarm = hardware.alarm, chime = hardware.chime)
        }
        val music = SpotifySock(
            player = spotify?.player ?: SpotifyPlayer.NONE,
            search = spotify?.search ?: MusicSearch.NONE,
            credentials = spotify?.credentials ?: SpotifyCredentials.NONE,
            fallback = spotify?.fallback ?: IntentFallback.NONE,
        )
        val tuner = RadioSock(player = radio?.player ?: RadioPlayer.NONE)
        val device = SystemSock(system?.volume ?: VolumeControl.NONE)
        val notes = MemoSock()
        val socks = buildList {
            add(clock)
            add(music)
            add(tuner)
            add(device)
            add(CalculatorSock())
            add(ConversationSock())
            add(notes)
            add(HelpSock { directory })
            addAll(DevSocks.create())
        }
        return Wiring(socks, clock, music, tuner, device, notes) { directory = it }
    }
}
