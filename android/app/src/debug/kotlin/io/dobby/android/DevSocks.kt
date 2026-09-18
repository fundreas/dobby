package io.dobby.android

import io.dobby.core.sock.Sock
import io.dobby.socks.devi.DeviSock

/**
 * Debug build: Devi is present.
 *
 * Its greeting goes to [out], which the service points at the chat transcript — on a wall
 * panel there is no stdout to print to.
 */
object DevSocks {
    fun create(out: (String) -> Unit): List<Sock> = listOf(DeviSock(out))
}
