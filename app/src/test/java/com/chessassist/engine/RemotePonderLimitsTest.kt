package com.chessassist.engine

import com.chessassist.BridgeSettings
import com.chessassist.PonderLimits
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RemotePonderLimitsTest {
    private val source = "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq - 0 1"
    private val target = "rnbqkbnr/pppp1ppp/8/4p3/4P3/8/PPPP1PPP/RNBQKBNR w KQkq - 0 2"

    @Test fun timeLimitFinalizesOnTheExistingPonderProtocol() {
        assertTrue(verifyFinalization(PonderLimits(250, 0)) >= 200)
    }

    @Test fun depthLimitFinalizesAsSoonAsThatIterationIsReady() {
        assertTrue(verifyFinalization(PonderLimits(10_000, 6)) < 3000)
    }

    private fun verifyFinalization(limits: PonderLimits): Long {
        val loopback = InetAddress.getByName("127.0.0.1")
        val oldHost = BridgeSettings.pcHost
        val oldPort = BridgeSettings.pcPort
        val client = RemoteEngineClient()
        try {
            return ServerSocket(0, 1, loopback).use { server ->
                server.soTimeout = 4000
                val check = FutureTask {
                    server.accept().use { connection ->
                        connection.soTimeout = 4000
                        val reader = connection.getInputStream().bufferedReader()
                        val writer = connection.getOutputStream().bufferedWriter()
                        fun receive() = JSONObject(reader.readLine())
                        fun send(reply: JSONObject) {
                            writer.write(reply.toString())
                            writer.newLine()
                            writer.flush()
                        }

                        assertEquals("info", receive().getString("t"))
                        send(JSONObject().put("name", "test").put("movetime", 9000))
                        assertEquals("ping", receive().getString("t"))
                        send(JSONObject().put("t", "pong"))
                        val request = receive()
                        val start = System.nanoTime()
                        assertEquals("ponder", request.getString("t"))
                        assertTrue(request.getBoolean("bounded"))
                        assertEquals(limits.movetimeMs, request.getLong("movetimeMs"))
                        assertEquals(limits.depth, request.getInt("depth"))
                        val reply = JSONObject().put("id", request.getInt("id")).put("fen", target)
                            .put("uci", "g1f3").put("depth", 6)
                        if (limits.depth > 0) send(reply.put("t", "ponder_info"))
                        assertEquals("ponderhit", receive().getString("t"))
                        val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
                        send(reply.put("t", "result"))
                        elapsed
                    }
                }
                Thread(check, "test-remote-ponder").apply { isDaemon = true; start() }
                BridgeSettings.pcHost = loopback.hostAddress!!
                BridgeSettings.pcPort = server.localPort
                client.start()
                val result = client.ponder(source, "e7e5", limits.movetimeMs, limits.depth, AtomicBoolean()) {}
                val elapsed = check.get(5, TimeUnit.SECONDS)
                assertEquals("g1f3", result?.uci)
                assertEquals(target, result?.positionFen)
                elapsed
            }
        } finally {
            client.stop()
            BridgeSettings.pcHost = oldHost
            BridgeSettings.pcPort = oldPort
        }
    }
}
