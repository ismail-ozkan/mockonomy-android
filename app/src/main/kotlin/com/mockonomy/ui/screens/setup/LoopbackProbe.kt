package com.mockonomy.ui.screens.setup

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import javax.inject.Inject

/**
 * The network side of the diagnostics. Split out as an interface so the diagnostics logic
 * can be tested without opening real sockets.
 */
interface LoopbackProbe {
    /** Whether the port is listening — a raw TCP connection, not HTTP. */
    suspend fun isPortOpen(port: Int): Boolean

    /** Sends a request to the given path; `null` if it cannot connect. */
    suspend fun statusCodeOf(port: Int, path: String): Int?
}

/**
 * `HttpURLConnection` is enough: adding an HTTP client dependency to the app for a single
 * loopback request is unnecessary.
 */
class AndroidLoopbackProbe @Inject constructor() : LoopbackProbe {

    override suspend fun isPortOpen(port: Int): Boolean = withContext(Dispatchers.IO) {
        try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(LOOPBACK, port), CONNECT_TIMEOUT_MS)
                true
            }
        } catch (error: IOException) {
            false
        }
    }

    override suspend fun statusCodeOf(port: Int, path: String): Int? = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            connection = (URL("http://$LOOPBACK:$port$path").openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                requestMethod = "GET"
            }
            connection.responseCode
        } catch (error: IOException) {
            null
        } finally {
            connection?.disconnect()
        }
    }

    private companion object {
        const val LOOPBACK = "127.0.0.1"
        const val CONNECT_TIMEOUT_MS = 1_500
        const val READ_TIMEOUT_MS = 3_000
    }
}
