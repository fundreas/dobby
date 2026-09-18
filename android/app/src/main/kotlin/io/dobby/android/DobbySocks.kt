package io.dobby.android

import io.dobby.core.registry.Introspection
import io.dobby.core.sock.Sock
import io.dobby.socks.clock.ClockSock
import io.dobby.socks.help.HelpSock

/**
 * The one place that knows which Socks exist.
 *
 * Adding a Sock is a line here and nothing else — that is the property `dobby-plan.md` §8's
 * M3 is "done when", and it holds on Android exactly as it held in the terminal.
 *
 * Development Socks are not listed here at all. They come from [DevSocks], which exists twice:
 * the debug source set returns Devi, the release source set returns nothing. The plan requires
 * that Devi cannot ship (§8, M1), and a source-set split is the only version of that rule the
 * compiler enforces.
 */
object DobbySocks {

    /**
     * The Sock list, plus the callback that hands the Help Sock its view of the finished
     * registry. Help is the one Sock that needs to see the palette it is itself part of, so it
     * is bound immediately after the registry is built.
     */
    class Wiring(val socks: List<Sock>, val bindDirectory: (Introspection) -> Unit)

    fun create(out: (String) -> Unit): Wiring {
        var directory: Introspection? = null
        val socks = buildList {
            add(ClockSock())
            add(HelpSock { directory })
            addAll(DevSocks.create(out))
        }
        return Wiring(socks) { directory = it }
    }
}
