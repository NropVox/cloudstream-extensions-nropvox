package com.nropvox

import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Plays flixcloud playlists for the player over loopback. Every playlist there (master and media) is XOR-masked, which
 * the site's own hls.js undoes; the app's player can't, and also rejects `data:` URIs (Cronet: ERR_UNKNOWN_URL_SCHEME).
 * So the master is served from memory and the media playlists are fetched, unmasked and served on demand.
 * Segments of the HD-2 style hosts are wrapped as images (see [ReanimeParser.unwrapSegment]) and go through here too;
 * AES-128 segments and keys come straight from flixcloud, with the link's Referer.
 */
object ReanimeLocalServer {
    private const val MAX_SESSIONS = 32

    class Session(val id: String, val pk: ByteArray, val base: String) {
        /** URL the player should use for a flixcloud playlist. */
        fun proxy(remoteUrl: String): String = "$base/reanime/$id/proxy?u=" + URLEncoder.encode(remoteUrl, "UTF-8")

        /** URL of a segment the server unwraps before the player sees it. */
        fun segment(remoteUrl: String): String = "$base/reanime/$id/seg?u=" + URLEncoder.encode(remoteUrl, "UTF-8")
    }

    private val sessions = object : LinkedHashMap<String, Pair<Session, MutableMap<String, ByteArray>>>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<Session, MutableMap<String, ByteArray>>>?) =
            size > MAX_SESSIONS
    }
    private val counter = AtomicInteger()
    private var server: ServerSocket? = null

    @Synchronized
    fun session(pk: ByteArray): Session {
        val port = (server ?: start()).localPort
        val id = System.nanoTime().toString(36) + counter.incrementAndGet()
        val session = Session(id, pk, "http://127.0.0.1:$port")
        synchronized(sessions) { sessions[id] = session to HashMap() }
        return session
    }

    /** Serves [playlist] (already rewritten) and returns its URL. */
    fun publish(session: Session, playlist: String): String {
        synchronized(sessions) { sessions[session.id]?.second?.put("master.m3u8", playlist.toByteArray()) }
        return "${session.base}/reanime/${session.id}/master.m3u8"
    }

    private fun start(): ServerSocket {
        val socket = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        server = socket
        thread(isDaemon = true, name = "reanime-local") {
            while (!socket.isClosed) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                thread(isDaemon = true) { runCatching { handle(client) } }
            }
        }
        return socket
    }

    private fun handle(client: Socket) = client.use {
        it.soTimeout = 15_000
        val input = it.getInputStream().bufferedReader(Charsets.ISO_8859_1)
        val request = input.readLine() ?: return
        while (true) if (input.readLine().isNullOrEmpty()) break
        val parts = request.split(' ')
        val method = parts.getOrNull(0)
        val target = parts.getOrNull(1).orEmpty()
        val reply = if (method == "GET" || method == "HEAD") runCatching { respond(target) }.getOrNull() else null
        val out = it.getOutputStream()
        if (reply == null) {
            out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
        } else {
            out.write(
                ("HTTP/1.1 200 OK\r\nContent-Type: ${reply.second}\r\nContent-Length: ${reply.first.size}\r\n" +
                    "Cache-Control: no-store\r\nConnection: close\r\n\r\n").toByteArray()
            )
            if (method == "GET") out.write(reply.first)
        }
        out.flush()
    }

    private const val PLAYLIST = "application/vnd.apple.mpegurl"

    private fun respond(target: String): Pair<ByteArray, String>? {
        val segments = target.substringBefore('?').split('/')
        if (segments.size < 4 || segments[1] != "reanime") return null
        val (session, files) = synchronized(sessions) { sessions[segments[2]] } ?: return null
        val name = segments[3]
        if (name != "proxy" && name != "seg") return synchronized(sessions) { files[name] }?.let { it to PLAYLIST }
        val remote = target.substringAfter("u=", "").substringBefore('&').let { URLDecoder.decode(it, "UTF-8") }
        if (!remote.startsWith("https://")) return null
        val bytes = download(remote) ?: return null
        if (name == "seg") return ReanimeParser.unwrapSegment(bytes) to "video/mp2t"
        val text = ReanimeParser.unmaskPlaylist(bytes.decodeToString(), session.pk)
        return ReanimeParser.absolutizeMedia(text, remote, session::segment).toByteArray() to PLAYLIST
    }

    private fun download(url: String): ByteArray? {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.setRequestProperty("Referer", ReanimeParser.FLIX_REFERER)
        connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/130.0 Mobile Safari/537.36")
        connection.connectTimeout = 10_000
        connection.readTimeout = 20_000
        return if (connection.responseCode == 200) connection.inputStream.use { it.readBytes() } else null
    }
}
