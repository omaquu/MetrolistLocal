/**
 * MetrolistLinkHub — Remote Control v3 (Android-safe)
 *
 * v3-KORJAUS (25.10.): aiempi LinkHubRemoteControl käytti
 * com.sun.net.httpserver.HttpServer -luokkaa, joka EI OLE Android-runtime'ssa
 * (kääntyy SDK:n android.jar:n kanssa mutta NoClassDefFoundError puhelimella
 * → remote ei koskaan käynnistynyt, vaikka appi oli auki). Tämä versio käyttää
 * java.net.ServerSocketia, joka toimii kaikilla Android-versioilla.
 *
 * Arkkitehtuuri v3 (singleton):
 *  - LinkHubRemote.start(context)  — MainActivity käynnistää KERRAN;
 *    palvelin kuuntelee niin kauan kuin appiprosessi elää
 *  - LinkHubRemote.attach(service) — MusicService kiinnittää itsensä;
 *    player-komennot toimivat vain kun service on ylhäillä
 *  - /play ja /queue käynnistävät servicen jos se ei ole päällä
 *
 * Reitit (Bearer-token paitsi /health):
 *   GET  /health, /state, /search?q=
 *   POST /play, /queue, /toggle, /next, /previous, /seek, /volume, /stop
 */
package com.metrolist.music.linkhub

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.WatchEndpoint
import com.metrolist.music.extensions.toMediaItem
import com.metrolist.music.models.MediaMetadata
import com.metrolist.music.models.toMediaMetadata
import com.metrolist.music.playback.MusicService
import com.metrolist.music.playback.queues.YouTubeQueue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.BufferedInputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

object LinkHubRemote {

    const val PREFS = "linkhub_remote"
    const val KEY_TOKEN = "token"
    const val KEY_PORT = "port"
    const val DEFAULT_PORT = 8875
    private const val TAG = "LinkHubRemote"

    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var boundPort: Int = DEFAULT_PORT
    @Volatile private var service: MusicService? = null
    @Volatile private var appContext: Context? = null
    private val running = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val workers = Executors.newFixedThreadPool(4)

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

    fun currentPort(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_PORT, DEFAULT_PORT)

    /** Käynnistä palvelin (idempotentti). Kutsu MainActivity:stä. */
    @Synchronized
    fun start(context: Context) {
        if (running.get()) return
        appContext = context.applicationContext
        val port = currentPort(context)
        try {
            val ss = ServerSocket()
            ss.reuseAddress = true
            ss.bind(InetSocketAddress(port), 8)
            serverSocket = ss
            boundPort = port
            running.set(true)
            Thread({
                acceptLoop(ss)
            }, "linkhub-remote-accept").apply { isDaemon = true }.start()
            Timber.tag(TAG).i("LinkHub remote listening on :%d (ServerSocket v3)", port)
        } catch (t: Throwable) {
            Timber.tag(TAG).e(t, "Failed to bind LinkHub remote :%d", port)
            serverSocket = null
        }
    }

    /** MusicService kiinnittää itsensä onCreate:ssa. */
    fun attach(musicService: MusicService) {
        service = musicService
        Timber.tag(TAG).i("MusicService attached — player control online")
    }

    /** MusicService irrottaa onDestroy:ssa (palvelin jää päälle). */
    fun detach(musicService: MusicService) {
        if (service === musicService) {
            service = null
            Timber.tag(TAG).i("MusicService detached — player offline, server up")
        }
    }

    fun isRunning(): Boolean = running.get()

    fun stop() {
        running.set(false)
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
    }

    // ── Accept-loop ──────────────────────────────────────────────────────────

    private fun acceptLoop(ss: ServerSocket) {
        while (running.get()) {
            val client = try {
                ss.accept()
            } catch (e: Exception) {
                if (running.get()) continue else break
            }
            workers.execute { handleConnection(client) }
        }
    }

    // ── Yhteyskäsittely (minimi-HTTP) ────────────────────────────────────────

    private fun handleConnection(socket: Socket) {
        try {
            socket.soTimeout = 20000
            val input = BufferedInputStream(socket.getInputStream())
            val out = socket.getOutputStream()

            val requestLine = readLine(input) ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return
            val method = parts[0].uppercase()
            val rawPath = parts[1]

            val headers = HashMap<String, String>()
            while (true) {
                val line = readLine(input) ?: break
                if (line.isEmpty()) break
                val idx = line.indexOf(':')
                if (idx > 0) headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
            }

            val contentLen = headers["content-length"]?.toIntOrNull() ?: 0
            val body = if (contentLen > 0) {
                val buf = ByteArray(contentLen)
                var read = 0
                while (read < contentLen) {
                    val n = input.read(buf, read, contentLen - read)
                    if (n < 0) break
                    read += n
                }
                String(buf, 0, read, StandardCharsets.UTF_8)
            } else ""

            val pathOnly = rawPath.substringBefore('?').trimEnd('/')
            val ctx = appContext ?: return
            val token = getOrCreateToken(ctx)

            if (pathOnly == "/health") {
                respond(out, 200, """{"ok":true,"app":"MetrolistLinkHub","port":$boundPort}""")
                return
            }
            if (headers["authorization"] != "Bearer $token") {
                respond(out, 401, """{"error":"unauthorized"}""")
                return
            }

            when {
                method == "GET" && pathOnly == "/state" -> handleState(out)
                method == "GET" && pathOnly == "/search" -> handleSearch(out, rawPath)
                method == "POST" && pathOnly == "/play" -> handlePlay(out, body, addToQueue = false)
                method == "POST" && pathOnly == "/queue" -> handlePlay(out, body, addToQueue = true)
                method == "POST" && pathOnly == "/toggle" -> withPlayer(out) { p -> if (p.isPlaying) p.pause() else p.play() }
                method == "POST" && pathOnly == "/next" -> withPlayer(out) { p -> p.seekToNext() }
                method == "POST" && pathOnly == "/previous" -> withPlayer(out) { p -> p.seekToPrevious() }
                method == "POST" && pathOnly == "/seek" -> handleSeek(out, body)
                method == "POST" && pathOnly == "/volume" -> handleVolume(out, body)
                method == "POST" && pathOnly == "/stop" -> withPlayer(out) { p -> p.stop() }
                // ── Extensions: playlistit, albumit, toistomoodit ──
                method == "GET" && pathOnly == "/playlist" -> respond(out, 200,
                    LinkHubRemoteExtensions.getPlaylist(rawPath.substringAfter("id=", "").substringBefore('&'))
                )
                method == "POST" && pathOnly == "/playlist/play" -> {
                    val bodyMap = parseBodyMap(body)
                    respond(out, 200, LinkHubRemoteExtensions.playPlaylist(
                        service,
                        bodyMap["playlistId"] ?: "",
                        bodyMap["shuffle"] == "true",
                        bodyMap["videoId"],
                    ))
                }
                method == "POST" && pathOnly == "/playlist/queue" -> {
                    val bodyMap = parseBodyMap(body)
                    respond(out, 200, LinkHubRemoteExtensions.queuePlaylist(
                        service,
                        bodyMap["playlistId"] ?: "",
                        bodyMap["max"]?.toIntOrNull() ?: 20,
                    ))
                }
                method == "GET" && pathOnly == "/moods" -> respond(out, 200, LinkHubRemoteExtensions.getMoodAndGenres())
                method == "GET" && pathOnly == "/mood/playlists" -> respond(out, 200,
                    LinkHubRemoteExtensions.getMoodPlaylists(
                        rawPath.substringAfter("browseId=", "").substringBefore('&'),
                        rawPath.substringAfter("params=", "").substringBefore('&').ifBlank { null },
                    )
                )
                method == "POST" && pathOnly == "/album/play" -> {
                    val bodyMap = parseBodyMap(body)
                    respond(out, 200, LinkHubRemoteExtensions.playAlbum(
                        service,
                        bodyMap["browseId"] ?: "",
                        bodyMap["shuffle"] == "true",
                    ))
                }
                // Toisto-tilat
                method == "POST" && pathOnly == "/repeat" -> withPlayer(out) { p ->
                    // 0 = OFF, 1 = ONE, 2 = ALL (media3 RepeatMode)
                    val mode = when (extractStr(body, "mode") ?: body.trim()) {
                        "one" -> 1; "all" -> 2; "off" -> 0
                        else -> when (p.repeatMode) { 2 -> 0; 1 -> 2; else -> 1 } // cycle off→all→one
                    }
                    p.repeatMode = mode
                }
                method == "POST" && pathOnly == "/shuffle" -> withPlayer(out) { p ->
                    val on = extractStr(body, "on") ?: ""
                    p.shuffleModeEnabled = when (on) { "true" -> true; "false" -> false; else -> !p.shuffleModeEnabled }
                }
                else -> respond(out, 404, """{"error":"not found"}""")
            }
        } catch (t: Throwable) {
            Timber.tag(TAG).e(t, "connection handler failed")
            // Turvaverkko: älä KOSKAAN jätä yhteysripiä vastaamatta —
            // muuten LinkHub näyttää 'Remote end closed connection without response'
            try {
                respond(socket.getOutputStream(), 500, """{"error":"${t.message ?: "internal"}"}""")
            } catch (_: Exception) {}
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    private fun parseBodyMap(body: String): Map<String, String> {
        val map = HashMap<String, String>()
        Regex("\"(\\w+)\"\\s*:\\s*\"([^\"]*)\"").findAll(body).forEach { m ->
            map[m.groupValues[1]] = m.groupValues[2]
        }
        Regex("\"(\\w+)\"\\s*:\\s*(true|false)").findAll(body).forEach { m ->
            map[m.groupValues[1]] = m.groupValues[2]
        }
        return map
    }

    private fun readLine(input: BufferedInputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(b.toChar())
            if (sb.length > 16384) return sb.toString()
        }
    }

    private fun respond(out: OutputStream, code: Int, body: String) {
        try {
            val bytes = body.toByteArray(StandardCharsets.UTF_8)
            val reason = when (code) {
                200 -> "OK"; 400 -> "Bad Request"; 401 -> "Unauthorized"
                404 -> "Not Found"; 500 -> "Internal Server Error"; 503 -> "Service Unavailable"
                else -> "Error"
            }
            val head = "HTTP/1.1 $code $reason\r\n" +
                "Content-Type: application/json; charset=utf-8\r\n" +
                "Content-Length: ${bytes.size}\r\n" +
                "Connection: close\r\n\r\n"
            out.write(head.toByteArray(StandardCharsets.UTF_8))
            out.write(bytes)
            out.flush()
        } catch (t: Throwable) {
            Timber.tag(TAG).e(t, "respond failed")
        }
    }

    // ── Käsittelijät ────────────────────────────────────────────────────────

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Suorita player-operaatio MAIN-THREADILLÄ ja odota tulos (max 3 s).
     * ExoPlayer EI ole thread-safe — kaikki player-kutsutovat main-threadiltä,
     * muuten media3 heittää IllegalStateExceptionin ("Player is accessed on the
     * wrong thread") ja yhteys kuolee ilman vastausta.
     * Palauttaa null jos service/pelaaja ei saatavilla tai timeout.
     */
    private fun <T> onPlayer(block: (androidx.media3.exoplayer.ExoPlayer) -> T): T? {
        val s = service ?: return null
        val latch = CountDownLatch(1)
        val result = arrayOfNulls<Any?>(1)
        val error = arrayOfNulls<Throwable>(1)
        mainHandler.post {
            try {
                val p = s.player
                result[0] = block(p)
            } catch (t: Throwable) {
                error[0] = t
            } finally {
                latch.countDown()
            }
        }
        if (!latch.await(3, TimeUnit.SECONDS)) return null
        error[0]?.let { throw it }
        @Suppress("UNCHECKED_CAST")
        return result[0] as? T
    }

    private fun withPlayer(out: OutputStream, block: (androidx.media3.exoplayer.ExoPlayer) -> Unit) {
        val s = service
        if (s == null) {
            respond(out, 503, """{"error":"player offline - avaa MetrolistLinkHub"}""")
            return
        }
        try {
            onPlayer(block)
        } catch (t: Throwable) {
            Timber.tag(TAG).e(t, "player op failed")
        }
        respond(out, 200, """{"ok":true}""")
    }

    private fun handleState(out: OutputStream) {
        val s = service
        if (s == null) {
            respond(out, 200, """{"service":false}""")
            return
        }
        val stateJson: String? = try {
            onPlayer { p ->
                val sb = StringBuilder("{")
                sb.append("\"playing\":").append(p.isPlaying)
                sb.append(",\"position\":").append((p.currentPosition / 1000).toInt())
                sb.append(",\"duration\":").append((p.duration.coerceAtLeast(0) / 1000).toInt())
                sb.append(",\"currentIndex\":").append(p.currentMediaItemIndex)
                sb.append(",\"queueSize\":").append(p.mediaItemCount)
                sb.append(",\"volume\":").append(p.volume)
                sb.append(",\"queue\":[")
                val n = p.mediaItemCount.coerceAtMost(50)
                for (i in 0 until n) {
                    val mi = p.getMediaItemAt(i)
                    sb.append("{\"videoId\":").append(jsonStr(mi.mediaId))
                    sb.append(",\"title\":").append(jsonStr(mi.mediaMetadata.title?.toString() ?: ""))
                    sb.append(",\"artist\":").append(jsonStr(mi.mediaMetadata.artist?.toString() ?: ""))
                    sb.append("}").append(if (i < n - 1) "," else "")
                }
                sb.append("]")
                val cur = p.currentMediaItem
                if (cur != null) {
                    sb.append(",\"current\":{")
                    sb.append("\"videoId\":").append(jsonStr(cur.mediaId))
                    sb.append(",\"title\":").append(jsonStr(cur.mediaMetadata.title?.toString() ?: ""))
                    sb.append(",\"artist\":").append(jsonStr(cur.mediaMetadata.artist?.toString() ?: ""))
                    sb.append("}")
                }
                sb.append("}")
                sb.toString()
            }
        } catch (t: Throwable) {
            Timber.tag(TAG).e(t, "state read failed")
            null
        }
        if (stateJson != null) {
            respond(out, 200, stateJson)
        } else {
            respond(out, 200, """{"service":true,"player":false}""")
        }
    }

    private fun jsonStr(v: String?): String =
        "\"" + (v ?: "").replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""

    private fun handleSearch(out: OutputStream, rawPath: String) {
        val q = rawPath.substringAfter("q=", "")
            .substringBefore('&')
            .let { URLDecoder.decode(it, "UTF-8") }
        if (q.isBlank()) {
            respond(out, 400, """{"error":"missing q"}""")
            return
        }
        Timber.tag(TAG).i("search: %s", q)
        val results = try {
            kotlinx.coroutines.runBlocking {
                withContext(Dispatchers.IO) {
                    kotlinx.coroutines.withTimeoutOrNull(20000L) {
                        YouTube.search(q, YouTube.SearchFilter.FILTER_SONG).getOrNull()
                    }
                }
            }
        } catch (t: Throwable) {
            Timber.tag(TAG).e(t, "search exception: %s", q)
            null
        }
        val songs = results
            ?.items
            ?.filterIsInstance<com.metrolist.innertube.models.SongItem>()
            .orEmpty()
            .take(20)
        Timber.tag(TAG).i("search '%s' → %d osumaa", q, songs.size)
        val sb = StringBuilder("[")
        songs.forEachIndexed { i, s ->
            sb.append("{")
            sb.append("\"videoId\":").append(jsonStr(s.id))
            sb.append(",\"title\":").append(jsonStr(s.title))
            sb.append(",\"artist\":").append(jsonStr(s.artists.firstOrNull()?.name))
            sb.append(",\"album\":").append(jsonStr(s.album?.name))
            sb.append(",\"duration\":").append(s.duration ?: 0)
            sb.append(",\"thumbnail\":").append(jsonStr(s.thumbnail))
            sb.append("}").append(if (i < songs.size - 1) "," else "")
        }
        sb.append("]")
        respond(out, 200, sb.toString())
    }

    private fun handlePlay(out: OutputStream, body: String, addToQueue: Boolean) {
        val videoId = extractStr(body, "videoId") ?: extractStr(body, "url")?.let { parseVideoId(it) }
        if (videoId.isNullOrBlank()) {
            respond(out, 400, """{"error":"need videoId or youtube url"}""")
            return
        }
        Timber.tag(TAG).i("play/queue: %s (addToQueue=%s)", videoId, addToQueue)
        val metadata: MediaMetadata? = try {
            kotlinx.coroutines.runBlocking {
                withContext(Dispatchers.IO) {
                    kotlinx.coroutines.withTimeoutOrNull(20000L) {
                        YouTube.queue(listOf(videoId), null).getOrNull()?.firstOrNull()?.toMediaMetadata()
                    }
                }
            }
        } catch (t: Throwable) {
            Timber.tag(TAG).e(t, "metadata resolve failed: %s", videoId)
            null
        }
        try {
            val s = service
            if (s == null) {
                // Service ei päällä → käynnistä ja kerro että retry
                appContext?.let {
                    val intent = Intent(it, MusicService::class.java)
                    intent.action = "com.metrolist.music.linkhub.BOOTSTRAP_REMOTE"
                    try { it.startForegroundService(intent) } catch (t: Throwable) {
                        Timber.tag(TAG).e(t, "service start failed")
                    }
                }
                respond(out, 503, """{"error":"soitin kaynnistyy - yrita uudelleen 5 s paasta"}""")
                return
            }
            kotlinx.coroutines.runBlocking {
                withContext(Dispatchers.Main) {
                    if (addToQueue) {
                        val item = metadata?.toMediaItem() ?: return@withContext
                        s.addToQueue(listOf(item))
                    } else {
                        s.playQueue(YouTubeQueue(WatchEndpoint(videoId = videoId), metadata), playWhenReady = true)
                    }
                }
            }
            respond(out, 200, """{"ok":true,"queued":$addToQueue,"videoId":"$videoId"}""")
        } catch (t: Throwable) {
            Timber.tag(TAG).e(t, "play failed for %s", videoId)
            respond(out, 500, """{"error":"${t.message ?: "resolve failed"}"}""")
        }
    }

    private fun handleSeek(out: OutputStream, body: String) {
        val pos = extractNum(body, "position")
        if (pos == null) { respond(out, 400, """{"error":"need position (seconds)"}"""); return }
        withPlayer(out) { p -> p.seekTo((pos * 1000).toLong()) }
    }

    private fun handleVolume(out: OutputStream, body: String) {
        val vol = extractNum(body, "volume")
        if (vol == null) { respond(out, 400, """{"error":"need volume (0.0..1.0)"}"""); return }
        withPlayer(out) { p -> p.volume = vol.toFloat().coerceIn(0f, 1f) }
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

    private fun extractStr(body: String, key: String): String? =
        Regex("\"$key\"\\s*:\\s*\"([^\"]*)\"").find(body)?.groupValues?.get(1)

    private fun extractNum(body: String, key: String): Double? =
        Regex("\"$key\"\\s*:\\s*(-?[0-9.]+)").find(body)?.groupValues?.get(1)?.toDoubleOrNull()
}
