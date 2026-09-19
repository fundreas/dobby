package io.dobby.socks.calculator

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/** A clock the test moves by hand, so the memory's expiry does not take ten minutes to prove. */
class MutableClock(
    private var now: Instant = Instant.parse("2026-09-19T10:00:00Z"),
    private val zone: ZoneId = ZoneId.of("Europe/Vienna"),
) : Clock() {

    fun advance(by: Duration) {
        now += by
    }

    override fun getZone(): ZoneId = zone

    override fun withZone(zone: ZoneId): Clock = MutableClock(now, zone)

    override fun instant(): Instant = now
}
