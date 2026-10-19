package com.metrolist.music.linkhub

import android.content.Context
import android.content.Intent

/**
 * MetrolistLinkHub — remote-palvelimen herätin.
 *
 * Ongelma: LinkHubRemoteControl pyörii MusicServicen sisällä, mutta Android
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
        try {
            val intent = Intent(context, com.metrolist.music.playback.MusicService::class.java)
            intent.action = "com.metrolist.music.linkhub.BOOTSTRAP_REMOTE"
            context.startForegroundService(intent)
        } catch (t: Throwable) {
            // esim. Android 12+ ForegroundServiceStartNotAllowedException jos appi taustalla
            // — ei kriittinen: remote käynnistyy ensimmäisellä toistolla joka tapauksessa
        }
    }
}
