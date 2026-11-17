package com.example.remoteapp

import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {

    private lateinit var mediaProjectionManager: MediaProjectionManager
    private var pendingSignalingUrl: String = ""
    private var pendingPairingCode: String = ""

    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            val serviceIntent = Intent(this, ScreenCaptureService::class.java).apply {
                putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, result.data)
                putExtra(ScreenCaptureService.EXTRA_SIGNALING_URL, pendingSignalingUrl)
                putExtra(ScreenCaptureService.EXTRA_PAIRING_CODE, pendingPairingCode)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* no-op either way, notification is best-effort */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mediaProjectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            MaterialTheme {
                var screen by remember { mutableStateOf("home") }
                when (screen) {
                    "whitelist" -> WhitelistScreen()
                    else -> HomeScreen(
                        onOpenWhitelist = { screen = "whitelist" },
                        onStartSession = ::startSession,
                        onOpenAccessibilitySettings = ::openAccessibilitySettings,
                        onOpenUsageAccessSettings = ::openUsageAccessSettings
                    )
                }
            }
        }
    }

    private fun startSession(signalingUrl: String, pairingCode: String) {
        pendingSignalingUrl = signalingUrl
        pendingPairingCode = pairingCode
        val captureIntent = mediaProjectionManager.createScreenCaptureIntent()
        screenCaptureLauncher.launch(captureIntent)
    }

    private fun openAccessibilitySettings() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    private fun openUsageAccessSettings() {
        startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
    }
}

@Composable
fun HomeScreen(
    onOpenWhitelist: () -> Unit,
    onStartSession: (String, String) -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onOpenUsageAccessSettings: () -> Unit
) {
    var serverUrl by remember { mutableStateOf("wss://your-signaling-server.example.com") }
    var pairingCode by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp)
    ) {
        Text("RemoteApp", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(16.dp))

        Text("One-time setup (do these once):", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(8.dp))
        Button(onClick = onOpenAccessibilitySettings, modifier = Modifier.fillMaxWidth()) {
            Text("Enable Accessibility Service")
        }
        Spacer(Modifier.height(8.dp))
        Button(onClick = onOpenUsageAccessSettings, modifier = Modifier.fillMaxWidth()) {
            Text("Grant Usage Access")
        }

        Spacer(Modifier.height(24.dp))
        Button(onClick = onOpenWhitelist, modifier = Modifier.fillMaxWidth()) {
            Text("Choose Whitelisted Apps")
        }

        Spacer(Modifier.height(24.dp))
        Text("Start a remote session:", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = serverUrl,
            onValueChange = { serverUrl = it },
            label = { Text("Signaling server URL") },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = pairingCode,
            onValueChange = { pairingCode = it },
            label = { Text("Pairing code") },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = { onStartSession(serverUrl, pairingCode) },
            modifier = Modifier.fillMaxWidth(),
            enabled = pairingCode.isNotBlank()
        ) {
            Text("Start Sharing")
        }
    }
}
