/*
 * MetrolistLinkHub — Remote Control -asetusnäkymä
 * Näyttää IP, portin ja tokenin jolla LinkHub ohjaa soitinta.
 * GPL-3.0 (oma fork — perustuu Metrolist-projektiin)
 */
package com.metrolist.music.ui.screens.settings

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.metrolist.music.linkhub.LinkHubRemoteControl
import java.net.NetworkInterface

@Composable
fun LinkHubRemoteSettings() {
    val context = LocalContext.current
    val token = remember { LinkHubRemoteControl.getOrCreateToken(context) }
    val port = remember { context.getSharedPreferences("linkhub_remote", Context.MODE_PRIVATE)
        .getInt("port", LinkHubRemoteControl.DEFAULT_PORT) }
    val ips = remember { getLocalIpAddresses() }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            "LinkHub Remote Control",
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            "Ohjaa tätä soitinta LinkHub-dashboardilta (musiikki-haku, jonotus, play/pause). " +
                "Kopioi alla olevat tiedot LinkHubin musiikkisivulle.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row {
                    Text("Osoite: ", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        ips.firstOrNull()?.let { "$it:$port" } ?: "(ei verkkoa) :$port",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Row {
                    Text("Token: ", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        token.chunked(8).joinToString(" "),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Text(
                    "Vain samassa verkossa toimii. Token vaaditaan jokaiseen komentoon.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
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
