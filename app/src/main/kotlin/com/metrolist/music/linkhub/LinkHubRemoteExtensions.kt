/**
 * MetrolistLinkHub — Lisä-API playlisteille, albumeista ja mood-genreistä.
 */
package com.metrolist.music.linkhub

import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.WatchEndpoint
import com.metrolist.music.extensions.toMediaItem
import com.metrolist.music.models.toMediaMetadata
import com.metrolist.music.playback.queues.ListQueue
import com.metrolist.music.playback.queues.YouTubeQueue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.OutputStream

object LinkHubRemoteExtensions {

    private const val TAG = "LinkHubRemote"

    private fun jsonStr(v: String?): String =
        "\"" + (v ?: "").replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""

    // ── Julkisen soittolistan sisältö ────────────────────────────────────────

    fun getPlaylist(playlistId: String): String {
        return try {
            kotlinx.coroutines.runBlocking {
                withContext(Dispatchers.IO) {
                    kotlinx.coroutines.withTimeoutOrNull(25000L) {
                        YouTube.playlist(playlistId).getOrNull()
                    }
                }
            }?.let { page ->
                val songs = page.songs.take(100)
                val sb = StringBuilder("{")
                sb.append("\"playlist\":{")
                sb.append("\"id\":").append(jsonStr(page.playlist.id))
                sb.append(",\"title\":").append(jsonStr(page.playlist.title))
                sb.append(",\"author\":").append(jsonStr(page.playlist.author?.name))
                sb.append(",\"thumbnail\":").append(jsonStr(page.playlist.thumbnail))
                sb.append("}")
                sb.append(",\"songs\":[")
                songs.forEachIndexed { i, s ->
                    sb.append("{")
                    sb.append("\"videoId\":").append(jsonStr(s.id))
                    sb.append(",\"title\":").append(jsonStr(s.title))
                    sb.append(",\"artist\":").append(jsonStr(s.artists.firstOrNull()?.name))
                    sb.append(",\"thumbnail\":").append(jsonStr(s.thumbnail))
                    sb.append("}").append(if (i < songs.size - 1) "," else "")
                }
                sb.append("],\"total\":").append(page.songs.size)
                sb.append("}")
                sb.toString()
            } ?: """{"error":"playlistia ei loytynyt"}"""
        } catch (t: Throwable) {
            Timber.tag(TAG).e(t, "getPlaylist failed: %s", playlistId)
            """{"error":"${t.message ?: "virhe"}"}"""
        }
    }

    // ── Mood & Genres -kategoriat (selausta varten) ─────────────────────────

    fun getMoodAndGenres(): String {
        return try {
            kotlinx.coroutines.runBlocking {
                withContext(Dispatchers.IO) {
                    kotlinx.coroutines.withTimeoutOrNull(20000L) {
                        YouTube.moodAndGenres().getOrNull()
                    }
                }
            }?.let { moods ->
                val sb = StringBuilder("[")
                moods.forEachIndexed { gi, mg ->
                    // Jokainen mood on gridi kategorioita — flatten yhdeksi listaksi
                    mg.items.forEachIndexed { ii, item ->
                        sb.append("{")
                        sb.append("\"category\":").append(jsonStr(mg.title))
                        sb.append(",\"title\":").append(jsonStr(item.title))
                        sb.append(",\"browseId\":").append(jsonStr(item.endpoint.browseId))
                        sb.append(",\"params\":").append(jsonStr(item.endpoint.params))
                        sb.append("}")
                        // Ei trailing comma -tarkistusta gridien yli — lisää pilkku aina paitsi viimeinen
                        sb.append(",")
                    }
                }
                // poista viimeinen pilkku
                var out = sb.toString()
                if (out.endsWith(",")) out = out.dropLast(1)
                out + "]"
            } ?: "[]"
        } catch (t: Throwable) {
            Timber.tag(TAG).e(t, "moodAndGenres failed")
            "[]"
        }
    }

    // ── Mood/Genre → soittolistat (browse-sivun playlistit) ────────────────

    fun getMoodPlaylists(browseId: String, params: String?): String {
        return try {
            val result = kotlinx.coroutines.runBlocking {
                withContext(Dispatchers.IO) {
                    kotlinx.coroutines.withTimeoutOrNull(20000L) {
                        YouTube.browse(browseId, params).getOrNull()
                    }
                }
            }
            if (result == null) return "[]"
            // BrowseResult: sections joissa on sekoitus SongItem/PlaylistItem/AlbumItem
            val sb = StringBuilder("[")
            var any = false
            result.items.forEach { section ->
                section.items.forEach { item ->
                    if (item is com.metrolist.innertube.models.PlaylistItem) {
                        if (any) sb.append(",")
                        sb.append("{")
                        sb.append("\"id\":").append(jsonStr(item.id))
                        sb.append(",\"title\":").append(jsonStr(item.title))
                        sb.append(",\"author\":").append(jsonStr(item.author?.name))
                        sb.append(",\"thumbnail\":").append(jsonStr(item.thumbnail))
                        sb.append("}")
                        any = true
                    } else if (item is com.metrolist.innertube.models.AlbumItem) {
                        if (any) sb.append(",")
                        sb.append("{")
                        sb.append("\"browseId\":").append(jsonStr(item.id))
                        sb.append(",\"title\":").append(jsonStr(item.title))
                        sb.append(",\"author\":").append(jsonStr(item.artists?.firstOrNull()?.name))
                        sb.append(",\"thumbnail\":").append(jsonStr(item.thumbnail))
                        sb.append(",\"type\":\"album\"}")
                        any = true
                    }
                }
            }
            sb.append("]")
            sb.toString()
        } catch (t: Throwable) {
            Timber.tag(TAG).e(t, "moodPlaylists failed")
            "[]"
        }
    }

    // ── Soita soittolista (koko jono kerralla) ──────────────────────────────

    fun playPlaylist(
        service: com.metrolist.music.playback.MusicService?,
        playlistId: String,
        shuffle: Boolean,
        videoIdStart: String? = null,
    ): String {
        if (service == null) {
            return """{"error":"soitin offline - avaa MetrolistLinkHub"}"""
        }
        return try {
            val page = kotlinx.coroutines.runBlocking {
                withContext(Dispatchers.IO) {
                    kotlinx.coroutines.withTimeoutOrNull(30000L) {
                        YouTube.playlist(playlistId).getOrNull()
                    }
                }
            }
            if (page == null) return """{"error":"playlistia ei loytynyt"}"""
            val songs = page.songs.take(100)
            if (songs.isEmpty()) return """{"error":"soittolista tyhja"}"""

            val startIndex = videoIdStart?.let { vid ->
                songs.indexOfFirst { it.id == vid }.takeIf { it >= 0 } ?: 0
            } ?: 0

            val mediaItems = songs.map { it.toMediaMetadata().toMediaItem() }
            val list = if (shuffle) mediaItems.shuffled() else mediaItems

            kotlinx.coroutines.runBlocking {
                withContext(Dispatchers.Main) {
                    service.playQueue(
                        ListQueue(
                            title = page.playlist.title,
                            items = list,
                            startIndex = startIndex.coerceIn(0, (list.size - 1).coerceAtLeast(0)),
                        ),
                        playWhenReady = true,
                    )
                }
            }
            """{"ok":true,"played":${jsonStr(page.playlist.title)},"tracks":${list.size},"shuffle":$shuffle}"""
        } catch (t: Throwable) {
            Timber.tag(TAG).e(t, "playPlaylist failed: %s", playlistId)
            """{"error":"${t.message ?: "virhe"}"}"""
        }
    }

    // ── Albumin toisto ───────────────────────────────────────────────────────

    fun playAlbum(service: com.metrolist.music.playback.MusicService?, browseId: String, shuffle: Boolean): String {
        if (service == null) {
            return """{"error":"soitin offline - avaa MetrolistLinkHub"}"""
        }
        return try {
            val page = kotlinx.coroutines.runBlocking {
                withContext(Dispatchers.IO) {
                    kotlinx.coroutines.withTimeoutOrNull(30000L) {
                        YouTube.album(browseId).getOrNull()
                    }
                }
            }
            if (page == null) return """{"error":"albumia ei loytynyt"}"""
            val songs = page.songs.take(100)
            if (songs.isEmpty()) return """{"error":"albumi tyhja"}"""

            val mediaItems = songs.map { it.toMediaMetadata().toMediaItem() }
            val list = if (shuffle) mediaItems.shuffled() else mediaItems

            kotlinx.coroutines.runBlocking {
                withContext(Dispatchers.Main) {
                    service.playQueue(
                        ListQueue(title = page.album.title, items = list),
                        playWhenReady = true,
                    )
                }
            }
            """{"ok":true,"played":${jsonStr(page.album.title)},"tracks":${list.size},"shuffle":$shuffle}"""
        } catch (t: Throwable) {
            Timber.tag(TAG).e(t, "playAlbum failed: %s", browseId)
            """{"error":"${t.message ?: "virhe"}"}"""
        }
    }

    // ── Lisää kappale soittolistaan LinkHubin kirjastossa ──────────────────
    // (puoli-pysyvä: LinkHubin music_api.py hoitaa oman listan)

    fun queuePlaylist(service: com.metrolist.music.playback.MusicService?, playlistId: String, maxTracks: Int = 20): String {
        if (service == null) {
            return """{"error":"soitin offline - avaa MetrolistLinkHub"}"""
        }
        return try {
            val page = kotlinx.coroutines.runBlocking {
                withContext(Dispatchers.IO) {
                    kotlinx.coroutines.withTimeoutOrNull(30000L) {
                        YouTube.playlist(playlistId).getOrNull()
                    }
                }
            }
            if (page == null) return """{"error":"playlistia ei loytynyt"}"""
            val songs = page.songs.take(maxTracks)
            if (songs.isEmpty()) return """{"error":"soittolista tyhja"}"""

            val mediaItems = songs.map { it.toMediaMetadata().toMediaItem() }

            kotlinx.coroutines.runBlocking {
                withContext(Dispatchers.Main) {
                    service.addToQueue(mediaItems)
                }
            }
            """{"ok":true,"queued":${mediaItems.size},"from":${jsonStr(page.playlist.title)}}"""
        } catch (t: Throwable) {
            Timber.tag(TAG).e(t, "queuePlaylist failed: %s", playlistId)
            """{"error":"${t.message ?: "virhe"}"}"""
        }
    }
}
