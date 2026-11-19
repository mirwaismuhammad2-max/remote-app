cat > /home/claude/RemoteAppProject/app/src/main/java/com/example/remoteapp/MainActivity.kt << 'EOF'
package com.example.remoteapp

import android.content.Intent
import android.media.projection.MediaProjectionManager
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

    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            val pairingCode = getOrCreatePairingCode()
            val serviceIntent = Intent(this, ScreenCaptureService::class.java).apply {
                putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, result.data)
                putExtra(ScreenCaptureService.EXTRA_PAIRING_CODE, pairingCode)
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
    ) { /* best-effort either way */ }

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
                        pairingCode = getOrCreatePairingCode(),
                        onOpenWhitelist = { screen = "whitelist" },
                        onStartSession = ::startSession,
                        onRegenerateCode = ::regenerateCode,
                        onOpenAccessibilitySettings = ::openAccessibilitySettings,
                        onOpenUsageAccessSettings = ::openUsageAccessSettings
                    )
                }
            }
        }
    }

    private fun startSession() {
        val captureIntent = mediaProjectionManager.createScreenCaptureIntent()
        screenCaptureLauncher.launch(captureIntent)
    }

    private fun openAccessibilitySettings() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    private fun openUsageAccessSettings() {
        startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
    }

    private fun getOrCreatePairingCode(): String {
        val prefs = getSharedPreferences("remoteapp_prefs", MODE_PRIVATE)
        var code = prefs.getString("pairing_code", null)
        if (code == null) {
            code = (100000..999999).random().toString()
            prefs.edit().putString("pairing_code", code).apply()
        }
        return code
    }

    private fun regenerateCode(): String {
        val newCode = (100000..999999).random().toString()
        getSharedPreferences("remoteapp_prefs", MODE_PRIVATE)
            .edit().putString("pairing_code", newCode).apply()
        return newCode
    }
}

@Composable
fun HomeScreen(
    pairingCode: String,
    onOpenWhitelist: () -> Unit,
    onStartSession: () -> Unit,
    onRegenerateCode: () -> String,
    onOpenAccessibilitySettings: () -> Unit,
    onOpenUsageAccessSettings: () -> Unit
) {
    var currentCode by remember { mutableStateOf(pairingCode) }

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
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("On your OTHER phone's browser, go to:", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(4.dp))
                Text(
                    "https://mirwaismuhammad2-max.github.io/remote-app/remote-viewer/",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(12.dp))
                Text("Then enter this pairing code (only needed the first time):", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(4.dp))
                Text(
                    currentCode,
                    style = MaterialTheme.typography.headlineMedium
                )
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = { currentCode = onRegenerateCode() }) {
                    Text("Generate a new code")
                }
                Text(
                    "Works over Wi-Fi or mobile data — the two phones don't need to be on the same network.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        Spacer(Modifier.height(24.dp))
        Button(onClick = onStartSession, modifier = Modifier.fillMaxWidth()) {
            Text("Start Sharing")
        }
    }
}
EOF
echo done
