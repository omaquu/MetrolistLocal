package com.metrolist.music.linkhub

import android.content.Context
import android.content.Intent

/**
 * MetrolistLinkHub — remote-palvelimen herätin.
 *
 * Ongelma: LinkHubRemote pyörii MusicServicen sisällä, mutta Android
 * käynnistää MusicServicen vasta kun toisto alkaa (play-nappi). Appi voi olla
 * auki ilman palvelua → remote ei kuuntele → "Connection refused".
 *
 * Ratkaisu: MainActivity.kt kutsuu ensureRemoteRunning() onCreate:ssa —
 * se startaa MusicServicen foregroundissa (ilman toistoa) joka puolestaan
 * käynnistää LinkHubRemoten. Mikäli palvelu on jo ylhäällä, kutsu on no-op.
 */
object LinkHubRemoteBootstrap {

    @JvmStatic
    fun ensureRemoteRunning(context: Context) {
        // v3: singleton-remote käynnistyy heti appiprosessissa, ilman MusicServicea.
        // Service kiinnittyy myöhemmin attach():lla kun se herää (play-painallus).
        com.metrolist.music.linkhub.LinkHubRemote.start(context)
    }
}
