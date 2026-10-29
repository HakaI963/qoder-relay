package com.qoder.relay

import fi.iki.elonen.NanoHTTPD.Method
import fi.iki.elonen.NanoHTTPD.Response
import fi.iki.elonen.NanoHTTPD.Response.Status
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Thread-safe one-way byte pipe between an upstream pump (producer thread) and the HTTP writer
 * (NanoHTTPD thread). Every [push] is an atomic, already-framed SSE block, so heartbeats and data
 * chunks can never interleave inside a single `data:` line.
 */
class SsePipe {
    private val q = LinkedBlockingQueue<Any>()
    private val endMark = Any()

    /** Set when the client is gone (socket error / stream closed) so the producer can abort early. */
    @Volatile
    var aborted = false
        private set

    @Volatile
    var lastWrite = System.currentTimeMillis()
        private set

    val bytes: InputStream = object : InputStream() {
        private var cur: ByteArray? = null
        private var pos = 0

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) <= 0) -1 else one[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            while (true) {
                if (aborted) return -1
                val c = cur
                if (c != null && pos < c.size) {
                    val n = minOf(len, c.size - pos)
                    System.arraycopy(c, pos, b, off, n)
                    pos += n
                    return n
                }
                cur = null
                pos = 0
                val item = try {
                    q.poll(400, TimeUnit.MILLISECONDS)
                } catch (_: InterruptedException) {
                    return -1
                } ?: continue
                if (item === endMark) return -1
                cur = item as ByteArray
            }
        }

        override fun close() {
            aborted = true
            q.clear()
        }
    }

    /** @return false once the consumer is gone — the producer uses this to cancel upstream work. */
    fun push(s: String): Boolean {
        if (aborted) return false
        lastWrite = System.currentTimeMillis()
        return q.offer(s.toByteArray(StandardCharsets.UTF_8))
    }

    fun pushData(json: String): Boolean = push("data: $json\n\n")

    fun pushComment(text: String): Boolean = push(": $text\n\n")

    fun finish() {
        q.offer(endMark)
    }

    fun abort() {
        aborted = true
        q.clear()
    }
}

/**
 * Chunked response that flushes after **every** SSE block.
 *
 * NanoHTTPD's stock chunked writer buffers and only flushes at the end, and — far more importantly
 * — a stock relay response cannot be sent until the upstream answer exists. This class writes the
 * status line + headers immediately (which is what kills the "Timed out waiting for response
 * headers" client errors) and then pumps the pipe with an explicit flush per chunk.
 */
class SseResponse(
    private val pipe: SsePipe,
    headers: Map<String, String> = emptyMap()
) : Response(Status.OK, "text/event-stream", null, 0L) {

    private val extra = LinkedHashMap<String, String>().apply {
        put("Cache-Control", "no-cache, no-transform")
        put("X-Accel-Buffering", "no")
        put("Connection", "keep-alive")
        putAll(headers)
    }

    override fun send(outputStream: OutputStream) {
        try {
            val sb = StringBuilder()
            sb.append("HTTP/1.1 200 OK\r\n")
            sb.append("Content-Type: text/event-stream; charset=utf-8\r\n")
            for ((k, v) in extra) sb.append(k).append(": ").append(v).append("\r\n")
            sb.append("Transfer-Encoding: chunked\r\n\r\n")
            outputStream.write(sb.toString().toByteArray(StandardCharsets.US_ASCII))
            outputStream.flush()

            if (requestMethod == Method.HEAD) {
                outputStream.write("0\r\n\r\n".toByteArray(StandardCharsets.US_ASCII))
                outputStream.flush()
                return
            }

            val buf = ByteArray(16 * 1024)
            while (true) {
                val n = try {
                    pipe.bytes.read(buf)
                } catch (_: IOException) {
                    -1
                }
                if (n < 0) break
                if (n == 0) continue
                outputStream.write((Integer.toHexString(n) + "\r\n").toByteArray(StandardCharsets.US_ASCII))
                outputStream.write(buf, 0, n)
                outputStream.write("\r\n".toByteArray(StandardCharsets.US_ASCII))
                outputStream.flush()
            }
            outputStream.write("0\r\n\r\n".toByteArray(StandardCharsets.US_ASCII))
            outputStream.flush()
        } catch (_: IOException) {
            // client vanished mid-stream: mark the pipe aborted so the producer stops upstream
        } finally {
            pipe.abort()
            try { outputStream.flush() } catch (_: Exception) { }
        }
    }

    override fun close() {
        pipe.abort()
    }
}

/** Emits SSE comments while the pipe is idle, so proxies and client chunk-timeouts stay satisfied. */
class Heartbeat(
    private val pipe: SsePipe,
    private val idleMs: Long = 8_000,
    private val text: String = "relay keep-alive"
) {
    @Volatile
    private var running = false

    private var thread: Thread? = null

    fun start() {
        if (running) return
        running = true
        thread = Thread {
            while (running) {
                try {
                    Thread.sleep(idleMs)
                } catch (_: InterruptedException) {
                    break
                }
                if (!running) break
                if (System.currentTimeMillis() - pipe.lastWrite >= idleMs) {
                    if (!pipe.pushComment(text)) break
                }
            }
        }.apply { isDaemon = true; name = "relay-heartbeat"; start() }
    }

    fun stop() {
        running = false
        try { thread?.interrupt() } catch (_: Exception) { }
        thread = null
    }
}
