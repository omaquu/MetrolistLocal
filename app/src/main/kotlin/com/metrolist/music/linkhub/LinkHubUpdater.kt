/**
 * MetrolistLinkHub — omaverkkoinen updater
 *
 * Hakee uusimman APK:n omasta forkista (omaquu/MetrolistLocal, release "latest")
 * ja tarjoaa suoran asennusintent. Riippumaton upstream-Updaterista (joka katsoo
 * MetrolistGroup/Metrolist — meidän forkkimme version-skeema on eri).
 *
 * Vertailu: release-kuvauksesta (nimi) parsitaan build-aikaleima, mutta
 * käytännössä vertaillaan GitHubin tagin julkaistu日期 vs. asennettu
 * BuildConfig.LINKHUB_BUILD_TIME (workflown generoi).
 */
package com.metrolist.music.linkhub

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.metrolist.music.BuildConfig
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import timber.log.Timber
import java.io.File

object LinkHubUpdater {

    private const val TAG = "LinkHubUpdater"
    private const val API_LATEST =
        "https://api.github.com/repos/omaquu/MetrolistLocal/releases/tags/latest"
    private val client = HttpClient()

    data class LatestRelease(
        val apkUrl: String,
        val publishedAt: String,   // ISO-8601 UTC, esim. "2026-10-19T22:46:51Z"
        val sizeBytes: Long,
        val commitSha: String,
    )

    /**
     * Hakee latest-releasen tiedot. Vertaa release-päivämäärää siihen buildiin
     * jonka sisällä tämä koodi on (BuildConfig.LINKHUB_BUILD_INSTANT).
     * Verrataan KOKO aikaleimaa (ei pelkkää päivää) — sama-päivä-buildit
     * eivät näy "ajan tasalla" enää.
     */
    suspend fun checkForUpdate(): LatestRelease? = withContext(Dispatchers.IO) {
        try {
            val response = client.get(API_LATEST)
            val json = JSONObject(response.bodyAsText())
            val published = json.getString("published_at") // esim. 2026-10-19T22:46:51Z
            val assets = json.getJSONArray("assets")
            var apkUrl: String? = null
            var size = 0L
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.getString("name").endsWith(".apk")) {
                    apkUrl = a.getString("browser_download_url")
                    size = a.getLong("size")
                    break
                }
            }
            val sha = json.optString("body", "").lineSequence()
                .firstOrNull { it.contains("commit ") }
                ?.substringAfter("commit ")?.take(7)
                ?: "unknown"

            val url = apkUrl ?: return@withContext null
            val release = LatestRelease(url, published, size, sha)

            // Vertailu ISO-8601 UTC -aikaleimojen merkkijonona:
            // molemmat muotoa yyyy-MM-ddTHH:mm:ssZ → leksigraafinen vertailu = aikajärjestys
            val publishedInstant = published.removeSuffix("Z")
            val builtInstant = BuildConfig.LINKHUB_BUILD_INSTANT.removeSuffix("Z")
            Timber.tag(TAG).i("latest=%s built=%s", publishedInstant, builtInstant)

            if (publishedInstant > builtInstant) release else null
        } catch (t: Throwable) {
            Timber.tag(TAG).e(t, "checkForUpdate failed")
            null
        }
    }

    /**
     * Lataa APK:n cacheen ja palauttaa FileProvider-URIn asennusintentille.
     */
    suspend fun downloadApk(context: Context, release: LatestRelease): File? =
        withContext(Dispatchers.IO) {
            try {
                val dir = File(context.cacheDir, "linkhub_update").apply { mkdirs() }
                val dest = File(dir, "metrolistlinkhub-update.apk")
                val urlConn = java.net.URL(release.apkUrl).openConnection() as java.net.HttpURLConnection
                urlConn.instanceFollowRedirects = true
                urlConn.connectTimeout = 15000
                urlConn.readTimeout = 60000
                urlConn.getInputStream().use { input ->
                    dest.outputStream().use { output -> input.copyTo(output) }
                }
                Timber.tag(TAG).i("APK downloaded: %d bytes", dest.length())
                dest
            } catch (t: Throwable) {
                Timber.tag(TAG).e(t, "downloadApk failed")
                null
            }
        }

    /**
     * Käynnistää APK-asennusintentin (FileProvider-cache-polusta).
     */
    fun launchInstall(context: Context, apk: File) {
        // Providerin authority: applicationId + ".FileProvider" (ks. AndroidManifest)
        val authority = "${context.packageName}.FileProvider"
        val uri = FileProvider.getUriForFile(context, authority, apk)
        // ACTION_INSTALL_PACKAGE (ei ACTION_VIEW) — selkeämpi asennusintent,
        // toimii myös Android 10+ ilman pakettiasentajan välitystä
        val intent = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
            Timber.tag(TAG).i("install intent launched for %s", apk.name)
        } catch (t: Throwable) {
            Timber.tag(TAG).e(t, "install intent failed — fallback ACTION_VIEW")
            val fallback = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try { context.startActivity(fallback) } catch (t2: Throwable) {
                Timber.tag(TAG).e(t2, "fallback also failed")
            }
        }
    }

    /**
     * helpottaa WorkManager-taustatarkistusta: onko tänään jo tarkistettu
     */
    fun shouldCheckToday(context: Context): Boolean {
        val prefs = context.getSharedPreferences("linkhub_remote", Context.MODE_PRIVATE)
        val last = prefs.getString("last_update_check", "") ?: ""
        val today = java.time.LocalDate.now().toString()
        return last != today
    }

    fun markCheckedToday(context: Context) {
        val prefs = context.getSharedPreferences("linkhub_remote", Context.MODE_PRIVATE)
        prefs.edit().putString("last_update_check", java.time.LocalDate.now().toString()).apply()
    }
}
