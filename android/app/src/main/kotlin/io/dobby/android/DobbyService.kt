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
import io.dobby.pipeline.VoicePipeline
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

        startForeground(
            NOTIFICATION_ID,
            notification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
        )

        // CPU on, screen off — legitimate here: the panel is wall-mounted and permanently
        // powered (§7.1). It is released in onDestroy and nowhere else.
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "dobby:service")
            .apply { acquire() }

        controller = DobbyController(scope, VoicePipeline(this, scope)) { announce ->
            AndroidSockContext(this, scope, announce)
        }
        scope.launch { controller.start() }
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
