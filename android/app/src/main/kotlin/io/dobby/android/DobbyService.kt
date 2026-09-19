package io.dobby.android

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import io.dobby.android.ui.MainActivity
import io.dobby.core.nlu.llm.Tier2Program
import io.dobby.core.registry.Introspection
import io.dobby.core.registry.SockRegistry
import io.dobby.llama.LlamaTier2
import io.dobby.pipeline.VoicePipeline
import io.dobby.pipeline.llm.LlmModelStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * The process Dobby actually lives in.
 *
 * A foreground service rather than an Activity because a wall panel's screen is off most of
 * the time and the Activity is not: the registry, the Socks and their state have to outlive
 * it. `dobby-plan.md` §7.1's rule is the reason [MainActivity] starts this and not the other
 * way round — on Android 12+ a service only keeps the microphone if it was started while an
 * Activity was in the foreground, so the flow is launch → grant → start, every reboot.
 *
 * The wake word (M2) will make this service the thing that is always listening. Today it is
 * the thing that is always *loaded*: the model stays in memory and the Socks keep their state
 * between utterances, which is most of the latency the wake word would otherwise pay for.
 */
class DobbyService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var wakeLock: PowerManager.WakeLock? = null

    /** Owned here, not by the Sock: whoever creates a `SoundPool` is the one who releases it. */
    private var clockHardware: ClockHardware? = null

    /** The local model, once it has loaded. Null on every device and every path that cannot. */
    private var tier2: LlamaTier2? = null

    lateinit var controller: DobbyController
        private set

    inner class LocalBinder : Binder() {
        val service: DobbyService get() = this@DobbyService
    }

    // The wake lock deliberately has no timeout: it is held for exactly as long as the service
    // lives, which is the point of a mains-powered wall panel (§7.1). A timeout here would put
    // Dobby to sleep mid-week.
    @SuppressLint("WakelockTimeout")
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        // A microphone-typed foreground service without RECORD_AUDIO is a SecurityException,
        // and this service is START_STICKY — so the crash would be a restart loop rather than
        // a crash. The Activity never starts us without the permission; Android's own restart
        // after a kill can, if the user revoked it in between.
        if (!hasMicPermission()) {
            Log.w(TAG, "started without RECORD_AUDIO — stopping instead of crash-looping")
            stopSelf()
            return
        }

        // A microphone foreground service may not be startable from the background — which is
        // exactly where the timer backstop starts it from (clock.specs.md §10). Refusal is a
        // logged miss, not a crash: this service is START_STICKY, so a crash is a restart loop.
        try {
            startForeground(
                NOTIFICATION_ID,
                notification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
        } catch (e: Exception) {
            Log.w(TAG, "could not go foreground — stopping instead of crash-looping", e)
            stopSelf()
            return
        }

        // CPU on, screen off — legitimate here: the panel is wall-mounted and permanently
        // powered (§7.1). It is released in onDestroy and nowhere else.
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "dobby:service")
            .apply { acquire() }

        clockHardware = ClockHardware(this)
        val settings = Settings(this)
        val resolver = buildTier2(settings)
        controller = DobbyController(
            scope = scope,
            pipeline = VoicePipeline(
                this,
                scope,
                settings.wakeWordId,
                settings.listenCue,
                settings.micProfile,
            ),
            sockContext = { announce -> AndroidSockContext(this, scope, announce) },
            hardware = clockHardware,
            settings = settings,
            tier2Resolver = resolver,
            // The mode is read per turn rather than captured, so a change to the setting
            // takes effect on the next turn instead of on the next restart.
            turnAudio = AndroidTurnAudio(this, mode = { settings.turnDuck }),
        )
        scope.launch { controller.start() }

        // Its own launch, after controller.start(), because the prefill is tens of seconds and
        // the microphone must not wait for it. Nothing downstream needs Tier 2 to be ready —
        // an utterance arriving mid-load simply gets Tier 1 alone, which is the whole product
        // as it shipped last milestone.
        if (resolver != null) {
            scope.launch { prepareTier2(resolver, settings) }
        }
    }

    /**
     * Builds the resolver, or returns null for any of the several good reasons not to.
     *
     * Off unless switched on, because the weights are a deliberate gigabyte; off if the model
     * is not downloaded, because this is not the place to start a gigabyte download; and off
     * permanently if the last attempt to load it did not come back.
     */
    private fun buildTier2(settings: Settings): LlamaTier2? {
        if (!settings.llmEnabled) return null
        if (settings.llmDisabledByCrash) {
            Log.w(TAG, "Tier 2 stays off: a previous load did not survive. Re-arm it in settings.")
            return null
        }
        // Still set from last time means the process died between "about to load" and "loaded".
        // The only thing that can do that is the native side, and on a START_STICKY service
        // retrying it is a boot loop.
        if (settings.llmLoadAttempted) {
            settings.llmDisabledByCrash = true
            settings.llmLoadAttempted = false
            Log.e(TAG, "Tier 2 disabled: the last load attempt did not complete (native crash?)")
            return null
        }

        val store = LlmModelStore(filesDir)
        val gguf = store.resolved()
        if (!gguf.isFile) {
            Log.i(TAG, "Tier 2 off: ${'$'}{gguf.name} is not downloaded")
            return null
        }

        val build = SockRegistry.build(DobbySocks.create(clockHardware).socks)
        val registry = build.registry ?: return null
        val program = Tier2Program.ofOrNull(registry, Introspection(registry)) { Log.w(TAG, it) }
            ?: return null

        return LlamaTier2(gguf, program).also { tier2 = it }
    }

    /** The tripwire's two halves, around the one call that can take the process down. */
    private suspend fun prepareTier2(resolver: LlamaTier2, settings: Settings) {
        resolver.prepare(
            onAttempt = { settings.llmLoadAttempted = true },
            onLoaded = { settings.llmLoadAttempted = false },
        )
    }

    /**
     * §9's order of retreat, as a real trigger rather than a paragraph.
     *
     * At `RUNNING_CRITICAL` the system is about to start killing background processes, and the
     * cheapest ~280 MiB in this process is the Tier 2 KV cache. The model's own gigabyte stays
     * mapped — those are file-backed pages the kernel can reclaim by itself — and the context
     * re-prefills in the background once the pressure is off.
     *
     * STT is never touched. A panel that cannot hear is broken; a panel that cannot paraphrase
     * is the product as it shipped last milestone.
     *
     * **The levels are deprecated and this is still the right hook.** Since API 35 every
     * `TRIM_MEMORY_*` constant except `TRIM_MEMORY_UI_HIDDEN` is deprecated, and the platform
     * no longer promises to deliver them — so this is a signal to act on when it arrives, not a
     * guarantee to rely on. There is no replacement that tells a foreground service "give
     * memory back now", and the alternative to a best-effort retreat is no retreat at all. The
     * backstop if it never fires is the same one as before: the process is killed and
     * `START_STICKY` brings it back.
     */
    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        val resolver = tier2 ?: return
        when {
            level >= TRIM_MEMORY_RUNNING_CRITICAL -> scope.launch {
                Log.w(TAG, "onTrimMemory(${'$'}level): releasing the Tier 2 context")
                resolver.releaseContext()
            }

            level <= TRIM_MEMORY_RUNNING_MODERATE -> scope.launch { resolver.reprefill() }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        // Android restarts us after a kill. The microphone may not survive that, which is what
        // the watchdog in M7 is for; being loaded again is still better than being gone.
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder = LocalBinder()

    override fun onDestroy() {
        // onCreate may have bailed out before the controller existed.
        if (::controller.isInitialized) runBlocking { controller.stop() }
        tier2?.let { resolver -> runBlocking { resolver.close() } }
        tier2 = null
        clockHardware?.release()
        clockHardware = null
        scope.cancel()
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        super.onDestroy()
    }

    private fun hasMicPermission() =
        checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Dobby",
            // Low: the panel's own screen is the UI. This notification exists because Android
            // requires one, not because anyone needs to be told.
            NotificationManager.IMPORTANCE_LOW,
        ).apply { description = "Dobby hört zu" }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, DobbyService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Dobby")
            .setContentText("bereit")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Beenden", stop).build())
            .setOngoing(true)
            .build()
    }

    companion object {
        const val ACTION_STOP: String = "io.dobby.android.STOP"

        private const val TAG = "Dobby"
        private const val CHANNEL_ID = "dobby.status"
        private const val NOTIFICATION_ID = 1

        /**
         * Starts the service from a foreground Activity — the only way it keeps the microphone
         * (§7.1). Calling this from anywhere else is the bug that makes Dobby deaf after a
         * reboot.
         */
        fun startFrom(context: Context) {
            context.startForegroundService(Intent(context, DobbyService::class.java))
        }
    }
}
