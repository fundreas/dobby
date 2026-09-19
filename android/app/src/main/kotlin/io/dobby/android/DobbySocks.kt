package io.dobby.android

import io.dobby.core.registry.Introspection
import io.dobby.core.sock.Sock
import io.dobby.socks.calculator.CalculatorSock
import io.dobby.socks.clock.ClockSock
import io.dobby.socks.help.HelpSock

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
        val bindDirectory: (Introspection) -> Unit,
    )

    /**
     * @param hardware `AlarmManager` and `SoundPool` for the Clock Sock. Null off-device — the
     *   timer then runs off its coroutine alone and chimes silently, which is exactly what a
     *   unit test wants and what the terminal harness gets.
     */
    fun create(hardware: ClockHardware? = null): Wiring {
        var directory: Introspection? = null
        val clock = if (hardware == null) {
            ClockSock()
        } else {
            ClockSock(alarm = hardware.alarm, chime = hardware.chime)
        }
        val socks = buildList {
            add(clock)
            add(CalculatorSock())
            add(HelpSock { directory })
            addAll(DevSocks.create())
        }
        return Wiring(socks, clock) { directory = it }
    }
}
