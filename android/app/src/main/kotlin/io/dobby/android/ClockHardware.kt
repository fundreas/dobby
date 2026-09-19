package io.dobby.android

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.SoundPool
import android.util.Log
import io.dobby.socks.clock.ChimePlayer
import io.dobby.socks.clock.ChimeSound
import io.dobby.socks.clock.TimerAlarm
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * The two pieces of hardware the Clock Sock needs, and the only place either one is named.
 *
 * The Sock itself sees [TimerAlarm] and [ChimePlayer] and nothing else — which is why its whole
 * timer lifecycle is tested on a plain JVM with a virtual clock and no emulator.
 */
class ClockHardware(context: Context) {
    val alarm: TimerAlarm = AndroidTimerAlarm(context)
    val chime: ChimePlayer = AndroidChime(context)

    /** Called by the service that created this, never by the Sock. */
    fun release() {
        chime.release()
        alarm.cancel()
    }
}

/**
 * The `AlarmManager` backstop behind a running timer (`clock.specs.md` §3).
 *
 * The coroutine countdown is the primary mechanism and is almost always the one that fires.
 * This exists for the case the plan calls the real risk on this device (§7.4): OxygenOS decides
 * the process may sleep, and a kitchen timer that silently does not go off is worse than no
 * timer at all.
 *
 * The receiver is registered for as long as an alarm is pending and unregistered with it, so a
 * cancelled timer leaves nothing behind that could wake the panel at three in the morning.
 */
private class AndroidTimerAlarm(context: Context) : TimerAlarm {

    private val appContext = context.applicationContext
    private val alarms = appContext.getSystemService(AlarmManager::class.java)

    private var pending: PendingIntent? = null

    override val canScheduleExact: Boolean get() = alarms.canScheduleExactAlarms()

    override fun schedule(at: Instant, onFire: () -> Unit) {
        cancel()
        TimerAlarmReceiver.live = onFire

        val broadcast = PendingIntent.getBroadcast(
            appContext,
            REQUEST_CODE,
            Intent(appContext, TimerAlarmReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        pending = broadcast

        // Without the permission the timer still runs off the coroutine — it is only the
        // backstop that gets weaker, which is exactly what `Degraded` tells the user (§10).
        if (canScheduleExact) {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at.toEpochMilli(), broadcast)
        } else {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at.toEpochMilli(), broadcast)
        }
    }

    override fun cancel() {
        pending?.let { alarms.cancel(it) }
        pending = null
        TimerAlarmReceiver.live = null
    }

    private companion object {
        const val REQUEST_CODE = 0
    }
}

/**
 * Where the backstop lands.
 *
 * Declared in the manifest rather than registered at runtime, because the case it exists for is
 * the one where there is no runtime left: the process was killed with a timer running. Then
 * [live] is null, the service is started, and the Clock Sock picks the timer up from config on
 * `onStart` (`clock.specs.md` §10). While the process *is* alive — the ordinary case, a doze —
 * [live] pokes the running timer directly and nothing is restarted.
 *
 * Starting a foreground service from the background is normally refused; a broadcast from an
 * exact alarm is one of the documented exemptions. `OxygenOS` is the reason the spec says to
 * verify this on the actual device, so a refusal is logged rather than allowed to crash.
 */
class TimerAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val running = live
        if (running != null) {
            running()
            return
        }
        runCatching { DobbyService.startFrom(context) }
            .onFailure { Log.w(TAG, "timer alarm could not restart the service", it) }
    }

    companion object {
        /** Set while a timer is armed, by the process that armed it. */
        @Volatile
        internal var live: (() -> Unit)? = null

        private const val TAG = "Dobby"
    }
}

/**
 * The expiry chime, on `SoundPool`.
 *
 * `SoundPool` rather than `MediaPlayer` because the chime repeats every three seconds and must
 * start instantly; the samples are tiny and are loaded once, when the service starts, so the
 * first stroke is never the one that pays for the decode.
 *
 * The attributes are `USAGE_ALARM`: the chime has to be audible when media is ducked, which is
 * the whole point of the Sock asking for *transient* focus rather than stopping the music.
 */
private class AndroidChime(context: Context) : ChimePlayer {

    private val pool = SoundPool.Builder()
        .setMaxStreams(1)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build(),
        )
        .build()

    private val loaded: MutableSet<Int> = ConcurrentHashMap.newKeySet()

    // Registered before the first load() call, and not in an init block below it: a sample can
    // finish decoding before the constructor returns, and a listener attached afterwards would
    // miss it — leaving a chime that is loaded and never rings.
    init {
        pool.setOnLoadCompleteListener { _, sampleId, status ->
            if (status == LOAD_OK) {
                loaded += sampleId
            } else {
                Log.w(TAG, "chime sample $sampleId failed to load ($status)")
            }
        }
    }

    private val samples: Map<ChimeSound, Int> = buildMap {
        put(ChimeSound.GLOCKE, pool.load(context, R.raw.chime_glocke, PRIORITY))
        put(ChimeSound.PIEP, pool.load(context, R.raw.chime_piep, PRIORITY))
        put(ChimeSound.GONG, pool.load(context, R.raw.chime_gong, PRIORITY))
    }

    @Volatile
    private var stream: Int = 0

    override fun play(sound: ChimeSound) {
        val sample = samples[sound] ?: return
        if (sample !in loaded) {
            // Only reachable if a timer expires within a moment of the service starting.
            Log.w(TAG, "chime $sound not loaded yet")
            return
        }
        stream = pool.play(sample, VOLUME, VOLUME, PRIORITY, NO_LOOP, NORMAL_RATE)
    }

    override fun stop() {
        if (stream != 0) pool.stop(stream)
        stream = 0
    }

    override fun release() {
        stop()
        pool.release()
    }

    private companion object {
        const val TAG = "Dobby"
        const val LOAD_OK = 0
        const val VOLUME = 1f
        const val PRIORITY = 1
        const val NO_LOOP = 0
        const val NORMAL_RATE = 1f
    }
}
