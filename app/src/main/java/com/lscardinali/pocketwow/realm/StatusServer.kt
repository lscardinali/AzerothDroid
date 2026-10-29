package com.lscardinali.pocketwow.realm

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

/**
 * Minimal HTTP server for diagnostics. Binds to all interfaces so it is reachable from
 * the LAN as well as from other apps on the device (127.0.0.1).
 *
 * [routes] maps a path to a provider of (content type, body).
 */
class StatusServer(
    private val port: Int,
    private val routes: Map<String, () -> Pair<String, String>>,
) {
    private var serverSocket: ServerSocket? = null
    private val workers = Executors.newFixedThreadPool(4)

    fun start() {
        val socket = ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(InetAddress.getByName("0.0.0.0"), port))
        }
        serverSocket = socket
        Thread({ acceptLoop(socket) }, "status-server").apply { isDaemon = true }.start()
        Log.i(TAG, "Listening on 0.0.0.0:$port")
    }

    fun stop() {
        serverSocket?.close()
        serverSocket = null
        workers.shutdownNow()
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (!socket.isClosed) {
            val client = try {
                socket.accept()
            } catch (_: Exception) {
                break
            }
            workers.execute { handle(client) }
        }
    }

    private fun handle(client: Socket) = client.use {
        it.soTimeout = 5_000
        val requestLine = it.getInputStream().bufferedReader().readLine().orEmpty()
        val path = requestLine.split(' ').getOrNull(1).orEmpty()
        val route = routes[path]
        val code = if (route != null) "200 OK" else "404 Not Found"
        val (contentType, body) = route?.invoke()
            ?: (JSON to JSONObject().put("error", "not found").put("paths", JSONArray(routes.keys)).toString())
        val bytes = body.toByteArray()
        val header = "HTTP/1.1 $code\r\n" +
            "Content-Type: $contentType\r\n" +
            "Content-Length: ${bytes.size}\r\n" +
            "Connection: close\r\n\r\n"
        it.getOutputStream().apply {
            write(header.toByteArray())
            write(bytes)
            flush()
        }
    }

    companion object {
        private const val TAG = "StatusServer"
        const val JSON = "application/json"
        const val TEXT = "text/plain; charset=utf-8"

        fun addressesJson(): JSONArray = JSONArray().apply {
            SystemStats.lanAddresses().forEach { (nic, ip) ->
                put(JSONObject().put("interface", nic).put("ip", ip))
            }
        }
    }
}
