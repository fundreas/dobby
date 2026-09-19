package io.dobby.android

import io.dobby.core.sock.Sock
import io.dobby.socks.winky.WinkySock

/** Debug build: Winky is present. */
object DevSocks {
    fun create(): List<Sock> = listOf(WinkySock())
}
