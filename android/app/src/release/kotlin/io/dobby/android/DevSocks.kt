package io.dobby.android

import io.dobby.core.sock.Sock

/**
 * Release build: there are no development Socks, and `:socks:devi` is not even on the
 * classpath — the app module declares it `debugImplementation`.
 */
object DevSocks {
    @Suppress("UNUSED_PARAMETER")
    fun create(out: (String) -> Unit): List<Sock> = emptyList()
}
