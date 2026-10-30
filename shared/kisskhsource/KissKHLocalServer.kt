package com.nropvox

import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import kotlin.concurrent.thread

/**
 * Serves kisskh's encrypted subtitles (`.txt`, `.txt1`) to the player as plain SRT over loopback. Their cue lines
 * are AES-encrypted and only the site's JS decrypts them; the app's player takes subtitles by URL only.
 */
object KissKHLocalServer {
    private var server: ServerSocket? = null

    /** Loopback URL that returns [remoteUrl] decrypted. */
    @Synchronized
    fun subtitleUrl(remoteUrl: String): String {
        val port = (server ?: start()).localPort
        return "http://127.0.0.1:$port/kisskh/sub.srt?u=" + URLEncoder.encode(remoteUrl, "UTF-8")
    }

    private fun start(): ServerSocket {
        val socket = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        server = socket
        thread(isDaemon = true, name = "kisskh-local") {
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
        val body = if (method == "GET" || method == "HEAD") runCatching { respond(target) }.getOrNull() else null
        val out = it.getOutputStream()
        if (body == null) {
            out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
        } else {
            out.write(
                ("HTTP/1.1 200 OK\r\nContent-Type: application/x-subrip; charset=utf-8\r\n" +
                    "Content-Length: ${body.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n").toByteArray()
            )
            if (method == "GET") out.write(body)
        }
        out.flush()
    }

    private fun respond(target: String): ByteArray? {
        if (!target.startsWith("/kisskh/sub.srt?")) return null
        val remote = target.substringAfter("u=", "").substringBefore('&').let { URLDecoder.decode(it, "UTF-8") }
        if (!remote.startsWith("https://")) return null
        val text = download(remote) ?: return null
        return KissKHParser.decryptSubtitle(text, KissKHParser.subtitleExtension(remote)).toByteArray()
    }

    private fun download(url: String): String? {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/130.0 Mobile Safari/537.36")
        connection.connectTimeout = 10_000
        connection.readTimeout = 20_000
        return if (connection.responseCode == 200) connection.inputStream.use { it.readBytes().decodeToString() } else null
    }
}
