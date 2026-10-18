/*
 * MetrolistLinkHub — Remote Control -asetusnäkymä v2
 * — token + portti säädettävissä tästä
 * — näyttää kaikki IP:t (Wi-Fi + Ethernet)
 * — updater: hakee uusimman APK:n GitHubista ja käynnistää asennuksen
 * GPL-3.0 (oma fork)
 */
package com.metrolist.music.ui.screens.settings

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.metrolist.music.linkhub.LinkHubRemoteControl
import com.metrolist.music.linkhub.LinkHubUpdater
import kotlinx.coroutines.launch
import java.net.NetworkInterface

@Composable
fun LinkHubRemoteSettings() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences("linkhub_remote", Context.MODE_PRIVATE) }

    var token by remember { mutableStateOf(LinkHubRemoteControl.getOrCreateToken(context)) }
    var port by remember {
        mutableStateOf(prefs.getInt("port", LinkHubRemoteControl.DEFAULT_PORT).toString())
    }
    var tokenDraft by remember { mutableStateOf(token) }
    var portDraft by remember { mutableStateOf(port) }
    val ips = remember { getLocalIpAddresses() }

    // Updater
    var checking by remember { mutableStateOf(false) }
    var downloading by remember { mutableStateOf(false) }
    var updateMsg by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("LinkHub Remote Control", style = MaterialTheme.typography.titleMedium)
        Text(
            "Ohjaa tätä soitinta LinkHub-dashboardilta. Syötä nämä LinkHubin musiikkisivulle.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Puhelimen osoitteet:", style = MaterialTheme.typography.labelMedium)
                if (ips.isEmpty()) {
                    Text("(ei verkkoa)", style = MaterialTheme.typography.bodyMedium)
                } else {
                    ips.forEach { ip ->
                        Text(
                            "$ip:$port",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                Text(
                    "Wi-Fi ja Ethernet (RJ45) voivat olla eri verkkosegmenttejä — jos LinkHub " +
                        "ei tavoita puhelinta, kokeile reitittimen asetuksista client isolation off " +
                        "tai anna puhelimelle kiinteä IP.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        OutlinedTextField(
            value = tokenDraft,
            onValueChange = { tokenDraft = it.trim() },
            label = { Text("Token") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        OutlinedTextField(
            value = portDraft,
            onValueChange = { portDraft = it.filter { c -> c.isDigit() }.take(5) },
            label = { Text("Portti (oletus ${LinkHubRemoteControl.DEFAULT_PORT})") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            var saved by remember { mutableStateOf(false) }
            Button(onClick = {
                val t = tokenDraft.ifBlank { LinkHubRemoteControl.getOrCreateToken(context) }
                val p = portDraft.toIntOrNull() ?: LinkHubRemoteControl.DEFAULT_PORT
                prefs.edit().putString("token", t).putInt("port", p).apply()
                token = t
                port = p.toString()
                saved = true
            }) { Text("Tallenna") }
            if (saved) {
                Text(
                    "Tallennettu — käynnistä soitin uudelleen",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 14.dp),
                )
            }
        }

        Text("Päivitykset", style = MaterialTheme.typography.titleMedium)
        Text(
            "Hakee uusimman APK:n GitHubista (omaquu/MetrolistLocal) ja asentaa suoraan.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    checking = true
                    updateMsg = null
                    scope.launch {
                        val release = LinkHubUpdater.checkForUpdate()
                        checking = false
                        if (release == null) {
                            updateMsg = "Olet ajan tasalla."
                        } else {
                            updateMsg = "Uusi versio — ladataan..."
                            downloading = true
                            val apk = LinkHubUpdater.downloadApk(context, release)
                            downloading = false
                            if (apk != null) {
                                updateMsg = "Asennus käynnistyy — hyväksy puhelimessa."
                                LinkHubUpdater.launchInstall(context, apk)
                            } else {
                                updateMsg = "Lataus epäonnistui."
                            }
                        }
                    }
                },
                enabled = !checking && !downloading,
            ) {
                Text(if (checking) "Tarkistaa..." else if (downloading) "Lataa..." else "Tarkista päivitys")
            }
        }
        if (checking || downloading) {
            CircularProgressIndicator(modifier = Modifier.padding(8.dp))
        }
        updateMsg?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        }
    }
}

private fun getLocalIpAddresses(): List<String> {
    val result = mutableListOf<String>()
    try {
        val interfaces = NetworkInterface.getNetworkInterfaces() ?: return result
        while (interfaces.hasMoreElements()) {
            val ni = interfaces.nextElement()
            if (!ni.isUp || ni.isLoopback) continue
            val addresses = ni.inetAddresses ?: continue
            while (addresses.hasMoreElements()) {
                val addr = addresses.nextElement()
                if (!addr.isLoopbackAddress && addr.hostAddress?.contains(':') == false) {
                    result.add(addr.hostAddress ?: continue)
                }
            }
        }
    } catch (_: Exception) {}
    return result
}
