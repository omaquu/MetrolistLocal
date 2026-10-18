/**
 * MetrolistLinkHub — Local Remote Control (GPL-3.0, oma fork)
 *
 * Pieni HTTP-palvelin (Com.sun.net.httpserver) joka pyörii MetrolistLinkHub-
 * sovelluksen sisällä ja tarjoilee ohjaus-endpointit LinkHubin (kotipalvelin)
 * käytettäväksi samassa verkossa:
 *
 *   GET  /health                → {"ok":true,"playing":...,"title":...}
 *   GET  /state                 → toistintila + jonon otsikot
 *   POST /play                  → {"videoId":"..."} tai {"url":"https://..."}  (uusi jono)
 *   POST /queue                 → {"url":"..."} | {"videoId":"..."}           (lisää jonoon)
 *   POST /search?q=             → haku (palauttaa listan kappaleista)
 *   POST /toggle                → play/pause
 *   POST /next | /previous
 *   POST /seek                  → {"position": sekuntia}
 *   POST /volume                → {"volume": 0.0..1.0}
 *   POST /stop                  → tyhjennä jono + pysäytä
 *
 * Turvallisuus: token-tarkistus (Authorization: Bearer <token>) + vapaaehtoinen
 * IP-allowlist. Token luodaan ensimmäisellä käynnistyksellä ja näkyy sovelluksen
 * asetuksissa (Remote Control -näkymä) + /sdcard/MetrolistLinkHub/token.txt.
 */
package com.metrolist.music.linkhub

import android.content.Context
import android.content.SharedPreferences
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.WatchEndpoint
import com.metrolist.music.playback.MusicService
import com.metrolist.music.playback.queues.YouTubeQueue
import com.metrolist.music.extensions.toMediaItem
import com.metrolist.music.models.MediaMetadata
import com.metrolist.music.models.toMediaMetadata
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.IOException
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.concurrent.Executors

class LinkHubRemoteControl(val service: MusicService) {

    companion object {
        const val PREFS = "linkhub_remote"
        const val KEY_TOKEN = "token"
        const val KEY_PORT = "port"
        const val KEY_ENABLED = "enabled"
        const val DEFAULT_PORT = 8875
        private val TAG = "LinkHubRemote"

        fun getOrCreateToken(context: Context): String {
            val prefs: SharedPreferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            prefs.getString(KEY_TOKEN, null)?.let { return it }
            val chars = "ABCDEFGHJKMNPQRSTUVWXYZabcdefghjkmnpqrstuvwxyz23456789"
            val token = buildString {
                val rnd = SecureRandom()
                repeat(32) { append(chars[rnd.nextInt(chars.length)]) }
            }
            prefs.edit().putString(KEY_TOKEN, token).apply()
            return token
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var server: HttpServer? = null
    private val prefs: SharedPreferences = service.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val token: String = getOrCreateToken(service)

    /** Suora pääsy toistimeen — MusicService.player on lateinit ExoPlayer. */
    private val player get() = service.player

    fun start(port: Int = prefs.getInt(KEY_PORT, DEFAULT_PORT)) {
        if (server != null) return
        try {
            val s = HttpServer.create(InetSocketAddress(port), 0)
            s.executor = Executors.newFixedThreadPool(2)
            registerRoutes(s)
            s.start()
            server = s
            prefs.edit().putInt(KEY_PORT, port).putBoolean(KEY_ENABLED, true).apply()
            Timber.tag(TAG).i("LinkHub remote listening on :$port (token auth on)")
        } catch (e: IOException) {
            Timber.tag(TAG).e(e, "Failed to start LinkHub remote on :$port")
        }
    }

    fun stop() {
        server?.stop(0)
        server = null
        prefs.edit().putBoolean(KEY_ENABLED, false).apply()
    }

    fun isRunning(): Boolean = server != null

    fun currentPort(): Int = prefs.getInt(KEY_PORT, DEFAULT_PORT)

    // ── Reitit ──────────────────────────────────────────────────────────────

    private fun registerRoutes(s: HttpServer) {
        s.createContext("/") { ex -> route(ex) }
    }

    private fun route(ex: HttpExchange) {
        try {
            val path = ex.requestURI.path.trimEnd('/')
            val method = ex.requestMethod.uppercase()

            // Health ilman tokenia (smoke-check), muut vaativat Bearer-tokenin
            if (path == "/health") {
                respond(ex, 200, """{"ok":true,"app":"MetrolistLinkHub","port":${currentPort()}}""")
                return
            }
            val auth = ex.requestHeaders.getFirst("Authorization") ?: ""
            if (auth != "Bearer $token") {
                respond(ex, 401, """{"error":"unauthorized"}""")
                return
            }

            when {
                method == "GET" && path == "/state" -> handleState(ex)
                method == "GET" && path == "/search" -> handleSearch(ex)
                method == "POST" && path == "/play" -> handlePlay(ex, addToQueue = false)
                method == "POST" && path == "/queue" -> handlePlay(ex, addToQueue = true)
                method == "POST" && path == "/toggle" -> { try { if (player.isPlaying) player.pause() else player.play() } catch (_: Throwable) {}; respond(ex, 200, """{"ok":true}""") }
                method == "POST" && path == "/next" -> { try { player.seekToNext() } catch (_: Throwable) {}; respond(ex, 200, """{"ok":true}""") }
                method == "POST" && path == "/previous" -> { try { player.seekToPrevious() } catch (_: Throwable) {}; respond(ex, 200, """{"ok":true}""") }
                method == "POST" && path == "/seek" -> handleSeek(ex)
                method == "POST" && path == "/volume" -> handleVolume(ex)
                method == "POST" && path == "/stop" -> { try { player.stop() } catch (_: Throwable) {}; respond(ex, 200, """{"ok":true}""") }
                else -> respond(ex, 404, """{"error":"not found"}""")
            }
        } catch (t: Throwable) {
            Timber.tag(TAG).e(t, "route failed: %s", ex.requestURI)
            try { respond(ex, 500, """{"error":"${t.message ?: "internal"}"}""") } catch (_: Exception) {}
        }
    }

    private fun handleState(ex: HttpExchange) {
        val p = try { player } catch (t: Throwable) { null }
        val state = mutableMapOf<String, Any>()
        if (p != null) {
            state["playing"] = p.isPlaying
            state["position"] = (p.currentPosition / 1000.0).toInt()
            state["duration"] = (p.duration.coerceAtLeast(0) / 1000.0).toInt()
            state["currentIndex"] = p.currentMediaItemIndex
            state["queueSize"] = p.mediaItemCount
            state["volume"] = p.volume
            val items = (0 until p.mediaItemCount.coerceAtMost(50)).mapNotNull { i ->
                val mi = p.getMediaItemAt(i)
                mapOf(
                    "videoId" to mi.mediaId,
                    "title" to (mi.mediaMetadata.title?.toString() ?: ""),
                    "artist" to (mi.mediaMetadata.artist?.toString() ?: ""),
                )
            }
            state["queue"] = items
            val current = p.currentMediaItem
            if (current != null) {
                state["current"] = mapOf(
                    "videoId" to current.mediaId,
                    "title" to (current.mediaMetadata.title?.toString() ?: ""),
                    "artist" to (current.mediaMetadata.artist?.toString() ?: ""),
                )
            }
        }
        val json = state.entries.joinToString(",", "{", "}") { (k, v) ->
            "\"$k\":" + when (v) {
                is Map<*, *> -> v.entries.joinToString(",", "{", "}") { (k2, v2) ->
                    "\"$k2\":${jsonVal(v2)}"
                }
                is List<*> -> v.joinToString(",", "[", "]") { item ->
                    (item as Map<*, *>).entries.joinToString(",", "{", "}") { (k2, v2) ->
                        "\"$k2\":${jsonVal(v2)}"
                    }
                }
                else -> jsonVal(v)
            }
        }
        respond(ex, 200, json)
    }

    private fun jsonVal(v: Any?): String = when (v) {
        is String -> "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        is Number, is Boolean -> v.toString()
        null -> "null"
        else -> "\"" + v.toString() + "\""
    }

    private fun handleSearch(ex: HttpExchange) {
        val q = ex.requestURI.rawQuery?.let { raw ->
            raw.split('&').firstOrNull { it.startsWith("q=") }?.substring(2)
        }?.let { java.net.URLDecoder.decode(it, "UTF-8") } ?: ""
        if (q.isBlank()) { respond(ex, 400, """{"error":"missing q"}"""); return }
        scope.launch {
            val result = YouTube.search(q, YouTube.SearchFilter.FILTER_SONG)
            val songs = result.getOrNull()
                ?.items
                ?.filterIsInstance<com.metrolist.innertube.models.SongItem>()
                .orEmpty()
                .take(20)
                .map { s ->
                    val m: LinkedHashMap<String, Any?> = linkedMapOf(
                        "videoId" to s.id,
                        "title" to s.title,
                        "artist" to s.artists.firstOrNull()?.name,
                        "album" to s.album?.name,
                        "duration" to s.duration,
                        "thumbnail" to s.thumbnail,
                    )
                    m
                }
            val json = songs.joinToString(",", "[", "]") { item ->
                item.entries.joinToString(",", "{", "}") { (k, v) -> "\"$k\":${jsonVal(v)}" }
            }
            respond(ex, 200, json)
        }
    }

    private fun handlePlay(ex: HttpExchange, addToQueue: Boolean) {
        val body = ex.requestBody.readBytes().toString(StandardCharsets.UTF_8)
        val videoId = extractJsonString(body, "videoId")
        val url = extractJsonString(body, "url")

        // URL → videoId (youtube.com/watch?v=..., youtu.be/..., music.youtube.com)
        val vid = videoId ?: url?.let { parseVideoId(it) }
        if (vid.isNullOrBlank()) { respond(ex, 400, """{"error":"need videoId or youtube url"}"""); return }

        scope.launch {
            try {
                val q = YouTube.queue(listOf(vid), null).getOrThrow()
                val first = q.firstOrNull()
                val metadata: MediaMetadata? = first?.toMediaMetadata()
                withContext(Dispatchers.Main) {
                    if (addToQueue) {
                        val item = metadata?.toMediaItem() ?: return@withContext
                        service.addToQueue(listOf(item))
                    } else {
                        service.playQueue(
                            YouTubeQueue(WatchEndpoint(videoId = vid), metadata),
                            playWhenReady = true,
                        )
                    }
                }
                respond(ex, 200, """{"ok":true,"queued":$addToQueue,"videoId":"$vid"}""")
            } catch (t: Throwable) {
                Timber.tag(TAG).e(t, "play/queue failed for $vid")
                respond(ex, 500, """{"error":"${t.message ?: "resolve failed"}"}""")
            }
        }
    }

    private fun handleSeek(ex: HttpExchange) {
        val body = ex.requestBody.readBytes().toString(StandardCharsets.UTF_8)
        val pos = extractJsonNumber(body, "position") ?: run {
            respond(ex, 400, """{"error":"need position (seconds)"}"""); return
        }
        service.player.let { p -> p.seekTo((pos * 1000).toLong()) }
        respond(ex, 200, """{"ok":true,"position":$pos}""")
    }

    private fun handleVolume(ex: HttpExchange) {
        val body = ex.requestBody.readBytes().toString(StandardCharsets.UTF_8)
        val vol = extractJsonNumber(body, "volume") ?: run {
            respond(ex, 400, """{"error":"need volume (0.0..1.0)"}"""); return
        }
        player.volume = vol.toFloat().coerceIn(0f, 1f)
        respond(ex, 200, """{"ok":true,"volume":$vol}""")
    }

    // ── Apurit ──────────────────────────────────────────────────────────────

    private fun parseVideoId(url: String): String? {
        val patterns = listOf(
            Regex("[?&]v=([A-Za-z0-9_-]{11})"),
            Regex("youtu\\.be/([A-Za-z0-9_-]{11})"),
            Regex("/shorts/([A-Za-z0-9_-]{11})"),
            Regex("/embed/([A-Za-z0-9_-]{11})"),
        )
        return patterns.firstNotNullOfOrNull { it.find(url)?.groupValues?.get(1) }
    }

    private fun extractJsonString(body: String, key: String): String? =
        Regex("\"$key\"\\s*:\\s*\"([^\"]*)\"").find(body)?.groupValues?.get(1)

    private fun extractJsonNumber(body: String, key: String): Double? =
        Regex("\"$key\"\\s*:\\s*(-?[0-9.]+)").find(body)?.groupValues?.get(1)?.toDoubleOrNull()

    private fun respond(ex: HttpExchange, code: Int, body: String) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        ex.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
        ex.sendResponseHeaders(code, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    fun destroy() { scope.cancel() }
}
