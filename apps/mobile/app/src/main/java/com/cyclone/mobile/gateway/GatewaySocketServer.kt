package com.cyclone.mobile.gateway

import android.net.LocalServerSocket
import android.net.LocalSocket
import java.io.Closeable
import java.io.IOException
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

internal class GatewaySocketServer(
    private val onLine: (String) -> String,
) : Closeable {
    private val running = AtomicBoolean(false)
    private val clients = Collections.synchronizedSet(linkedSetOf<LocalSocket>())
    private val acceptExecutor = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "cyclone-gateway-accept") }
    private val clientExecutor = Executors.newCachedThreadPool { runnable -> Thread(runnable, "cyclone-gateway-client") }
    @Volatile private var server: LocalServerSocket? = null
    @Volatile var lastClientConnectedAt: Long? = null
        private set

    fun start() {
        if (!running.compareAndSet(false, true)) return
        try {
            server = LocalServerSocket(GatewayProtocol.SOCKET_NAME)
        } catch (error: IOException) {
            running.set(false)
            throw error
        }
        acceptExecutor.execute(::acceptLoop)
    }

    fun isRunning(): Boolean = running.get()
    fun connectedClients(): Int = clients.size

    fun disconnectClients() {
        val copy = synchronized(clients) { clients.toList() }
        copy.forEach { runCatching { it.close() } }
        clients.clear()
    }

    private fun acceptLoop() {
        while (running.get()) {
            val socket = try {
                server?.accept() ?: break
            } catch (_: IOException) {
                if (!running.get()) break else continue
            }
            clients += socket
            lastClientConnectedAt = System.currentTimeMillis()
            clientExecutor.execute { handle(socket) }
        }
    }

    private fun handle(socket: LocalSocket) {
        try {
            val input = socket.inputStream
            val output = socket.outputStream.bufferedWriter(Charsets.UTF_8)
            while (running.get() && !socket.isClosed) {
                val line = try {
                    GatewayLineReader.readUtf8Line(input)
                } catch (error: GatewayProtocolException) {
                    output.write(GatewayProtocol.error(error.requestId, error.code, error.message).toString())
                    output.newLine()
                    output.flush()
                    continue
                } ?: break
                val response = try {
                    onLine(line)
                } catch (error: Exception) {
                    GatewayProtocol.error("", "INTERNAL_ERROR", error.message ?: "Gateway request failed").toString()
                }
                output.write(response)
                output.newLine()
                output.flush()
            }
        } catch (_: IOException) {
            // ADB forwards disappear abruptly on USB disconnect; that is a normal lifecycle event.
        } finally {
            clients -= socket
            runCatching { socket.close() }
        }
    }

    override fun close() {
        if (!running.compareAndSet(true, false)) return
        disconnectClients()
        runCatching { server?.close() }
        server = null
        acceptExecutor.shutdownNow()
        clientExecutor.shutdownNow()
    }
}
