package io.dobby.android

import io.dobby.core.sock.Sock
import io.dobby.socks.devi.DeviSock

/** Debug build: Devi is present. */
object DevSocks {
    fun create(): List<Sock> = listOf(DeviSock())
}
