package com.qoder.relay

import fi.iki.elonen.NanoHTTPD
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.InterruptedIOException
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class RelayCoreTest {

    /** SSE response: headers immediately, chunks streamed, socket closed at the end. */
    @Test fun sseStreamsAndCloses() {
        val srv = object : NanoHTTPD(0) {
            override fun serve(session: IHTTPSession): Response {
                val pipe = SsePipe()
                Thread {
                    Thread.sleep(300)
                    repeat(3) { pipe.pushData("{\"n\":$it}"); Thread.sleep(100) }
                    pipe.pushData("[DONE]"); pipe.finish()
                }.start()
                return SseResponse(pipe)
            }
        }
        srv.start(5000, false)
        try {
            val c = OkHttpClient.Builder().readTimeout(5, TimeUnit.SECONDS).build()
            val t0 = System.currentTimeMillis()
            c.newCall(Request.Builder().url("http://127.0.0.1:${srv.listeningPort}/").build()).execute().use { r ->
                val headersAt = System.currentTimeMillis() - t0
                assertTrue("headers must not wait for data ($headersAt ms)", headersAt < 250)
                assertEquals("close", r.header("Connection"))
                val body = r.body!!.string()
                assertEquals(4, body.split("\n\n").count { it.startsWith("data:") })
                assertTrue(body.contains("[DONE]"))
            }
        } finally { srv.stop() }
    }

    /** Raw slow server: delays headers by [hdrDelay], then streams body for [bodyMs]. */
    private fun slowServer(hdrDelay: Long, bodyMs: Long): ServerSocket {
        val ss = ServerSocket(0)
        Executors.newSingleThreadExecutor().execute {
            runCatching {
                ss.accept().use { s ->
                    val inp = s.getInputStream().bufferedReader()
                    while (inp.readLine()?.isNotEmpty() == true) { }
                    Thread.sleep(hdrDelay)
                    val o = s.getOutputStream()
                    o.write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nConnection: close\r\n\r\n".toByteArray()); o.flush()
                    val end = System.currentTimeMillis() + bodyMs
                    while (System.currentTimeMillis() < end) { o.write("data: x\n\n".toByteArray()); o.flush(); Thread.sleep(200) }
                }
            }
        }
        return ss
    }

    private val sched = Executors.newSingleThreadScheduledExecutor()
    private val client = OkHttpClient.Builder().readTimeout(10, TimeUnit.SECONDS).build()

    /** Same pattern as QoderClient.openChat. */
    private fun open(port: Int, headerTimeoutMs: Long): okhttp3.Response {
        val call = client.newCall(Request.Builder().url("http://127.0.0.1:$port/").build())
        val wd = sched.schedule({ call.cancel() }, headerTimeoutMs, TimeUnit.MILLISECONDS)
        try { return call.execute() }
        catch (e: Exception) { if (wd.isDone) throw InterruptedIOException("timeout"); throw e }
        finally { wd.cancel(false) }
    }

    @Test fun longStreamSurvivesHeaderTimeout() {
        val ss = slowServer(hdrDelay = 100, bodyMs = 2500)
        open(ss.localPort, headerTimeoutMs = 800).use { r ->
            val body = r.body!!.string()   // must read for 2.5 s, well past the 0.8 s header budget
            assertTrue(body.length > 50)
        }
    }

    @Test(expected = InterruptedIOException::class)
    fun stalledHeadersFailFast() {
        val ss = slowServer(hdrDelay = 5000, bodyMs = 0)
        open(ss.localPort, headerTimeoutMs = 600)
    }

    /** Documents the original bug: OkHttp's call timeout keeps running after clearTimeout(). */
    @Test fun oldPatternKilledStreams() {
        val ss = slowServer(hdrDelay = 100, bodyMs = 2500)
        val call = client.newCall(Request.Builder().url("http://127.0.0.1:${ss.localPort}/").build())
        call.timeout().timeout(800, TimeUnit.MILLISECONDS)
        val r = call.execute()
        call.timeout().clearTimeout()
        val failed = runCatching { r.body!!.string() }.isFailure
        r.close()
        assertTrue("old code cut the stream at the header timeout", failed)
    }
}
