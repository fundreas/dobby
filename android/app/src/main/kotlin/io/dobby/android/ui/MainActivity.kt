package io.dobby.android.ui

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.dobby.android.DobbyService

/**
 * The panel's one screen, and the thing that is allowed to start the microphone.
 *
 * On Android 12+ a service only keeps mic access if it was started while an Activity was in
 * the foreground (`dobby-plan.md` §7.1), so the order below is load-bearing: permission first,
 * then `startForegroundService`, then bind. Get it backwards and Dobby is deaf with no error
 * anywhere — which is why the call lives in [DobbyService.startFrom] with that written on it.
 *
 * The plan accepts one manual tap per reboot for the same reason (§9). This is that tap.
 */
class MainActivity : ComponentActivity() {

    private var service by mutableStateOf<DobbyService?>(null)
    private var micGranted by mutableStateOf(false)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service = (binder as DobbyService.LocalBinder).service
        }

        override fun onServiceDisconnected(name: ComponentName) {
            service = null
        }
    }

    private val requestPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted ->
        micGranted = granted[Manifest.permission.RECORD_AUDIO] == true
        if (micGranted) startAndBind()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // §7.2: the panel wakes itself, with no keyguard in the way.
        setShowWhenLocked(true)
        setTurnScreenOn(true)

        micGranted = hasMicPermission()

        setContent {
            DobbyTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    val connected = service
                    when {
                        !micGranted -> PermissionGate(::askForPermissions)

                        connected == null -> Waiting()

                        else -> {
                            val state by connected.controller.state.collectAsStateWithLifecycle()
                            val clock by connected.controller.clock.collectAsStateWithLifecycle()
                            val nowPlaying by connected.controller.spotify.collectAsStateWithLifecycle()
                            val artwork by connected.controller.spotifyArtwork
                                .collectAsStateWithLifecycle()
                            val radio by connected.controller.radio.collectAsStateWithLifecycle()
                            var settingsOpen by remember { mutableStateOf(false) }
                            var helpOpen by remember { mutableStateOf(false) }

                            if (helpOpen) {
                                // The same directory the Help Sock answers out of, so the
                                // screen and the voice cannot disagree (`help.specs.md` §8).
                                HelpScreen(
                                    introspection = connected.controller.introspection,
                                    onBack = { helpOpen = false },
                                )
                            } else if (settingsOpen) {
                                BackHandler { settingsOpen = false }
                                SettingsScreen(
                                    state = state,
                                    onBack = { settingsOpen = false },
                                    onHandsFree = { connected.controller.setHandsFree(it) },
                                    onSelect = { connected.controller.selectWakeWord(it) },
                                    onSelectVoice = { connected.controller.selectVoice(it) },
                                    onListenCue = { connected.controller.setListenCue(it) },
                                    onTurnDuck = { connected.controller.setTurnDuck(it) },
                                    onMicProfile = { connected.controller.setMicProfile(it) },
                                    onSpotifyMarket = { connected.controller.setSpotifyMarket(it) },
                                    onSpotifyPreferTrack = {
                                        connected.controller.setSpotifyPreferTrack(it)
                                    },
                                    onSpotifyAskWhenUnsure = {
                                        connected.controller.setSpotifyAskWhenUnsure(it)
                                    },
                                    onRadioStation = { connected.controller.setRadioStation(it) },
                                )
                            } else {
                                ChatScreen(
                                    state = state,
                                    clock = clock,
                                    nowPlaying = nowPlaying,
                                    artwork = artwork,
                                    radio = radio,
                                    onStopRadio = { connected.controller.stopRadio() },
                                    onListen = { connected.controller.listen() },
                                    onAbort = { connected.controller.stopListening() },
                                    onSettings = { settingsOpen = true },
                                    onHelp = { helpOpen = true },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (micGranted) startAndBind() else askForPermissions()
    }

    override fun onStop() {
        // Unbind, never stop: the service and everything it holds outlive this screen.
        if (service != null) unbindService(connection)
        service = null
        super.onStop()
    }

    private fun startAndBind() {
        DobbyService.startFrom(this)
        bindService(Intent(this, DobbyService::class.java), connection, Context.BIND_AUTO_CREATE)
    }

    private fun askForPermissions() {
        requestPermissions.launch(
            arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS),
        )
    }

    private fun hasMicPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
}

@Composable
private fun PermissionGate(onGrant: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "Dobby braucht das Mikrofon.",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            "Erkennung läuft vollständig auf diesem Gerät. Nichts wird hochgeladen.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Button(onClick = onGrant) { Text("Erlauben") }
    }
}

@Composable
private fun Waiting() {
    Column(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "Dobby startet…",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
