package com.example.remoteapp

import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap

/**
 * Screen for picking which installed apps are remotely launchable,
 * plus quick Open/Close test buttons for the current whitelist.
 *
 * Drop this into a Composable hierarchy, e.g. inside your MainActivity's setContent { }.
 */
@Composable
fun WhitelistScreen() {
    val context = LocalContext.current
    val manager = remember { WhitelistManager(context) }

    var allApps by remember { mutableStateOf<List<WhitelistedApp>>(emptyList()) }
    var whitelisted by remember { mutableStateOf(manager.getWhitelistedPackageNames()) }
    var statusMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        allApps = manager.getInstallableApps()
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Remotely Launchable Apps", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            "Toggle apps to allow remote open/close. Only toggled apps can ever be controlled remotely.",
            style = MaterialTheme.typography.bodySmall
        )
        Spacer(Modifier.height(12.dp))

        statusMessage?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp))
        }

        LazyColumn(modifier = Modifier.weight(1f)) {
            items(allApps) { app ->
                AppRow(
                    app = app,
                    isWhitelisted = whitelisted.contains(app.packageName),
                    onToggle = { checked ->
                        if (checked) manager.addToWhitelist(app.packageName)
                        else manager.removeFromWhitelist(app.packageName)
                        whitelisted = manager.getWhitelistedPackageNames()
                    },
                    onLaunch = {
                        val result = manager.launchApp(app.packageName)
                        statusMessage = "Launch ${app.label}: $result"
                    },
                    onClose = {
                        val result = manager.closeApp(app.packageName)
                        statusMessage = "Close ${app.label}: $result"
                    }
                )
            }
        }
    }
}

@Composable
private fun AppRow(
    app: WhitelistedApp,
    isWhitelisted: Boolean,
    onToggle: (Boolean) -> Unit,
    onLaunch: () -> Unit,
    onClose: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AppIcon(app.icon)
        Spacer(Modifier.width(12.dp))
        Text(app.label, modifier = Modifier.weight(1f))

        if (isWhitelisted) {
            TextButton(onClick = onLaunch) { Text("Open") }
            TextButton(onClick = onClose) { Text("Close") }
        }

        Switch(checked = isWhitelisted, onCheckedChange = onToggle)
    }
}

@Composable
private fun AppIcon(icon: Drawable?) {
    if (icon == null) {
        Box(modifier = Modifier.size(32.dp))
        return
    }
    val bitmap = remember(icon) { icon.toBitmap(width = 96, height = 96) }
    Image(
        painter = BitmapPainter(bitmap.asImageBitmap()),
        contentDescription = null,
        modifier = Modifier.size(32.dp)
    )
}
