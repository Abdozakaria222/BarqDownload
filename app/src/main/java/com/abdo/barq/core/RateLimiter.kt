package com.abdo.barq.core

/**
 * Token bucket shared by all connections of a download (or by all downloads for the global limiter).
 * Changing [bytesPerSecond] applies at once, even while downloading. 0 = no limit.
 */
class RateLimiter(bytesPerSecond: Long = 0) {
    @Volatile var bytesPerSecond: Long = bytesPerSecond
        set(v) { field = v; synchronized(lock) { tokens = 0.0; last = System.nanoTime() } }

    private val lock = Object()
    private var tokens = 0.0
    private var last = System.nanoTime()

    /** Blocks until [n] bytes may pass. [cancelled] is polled while waiting. */
    fun acquire(n: Int, cancelled: () -> Boolean = { false }) {
        var need = n.toDouble()
        while (true) {
            val rate = bytesPerSecond
            if (rate <= 0) return
            var waitNs: Long
            synchronized(lock) {
                val now = System.nanoTime()
                tokens = minOf(rate.toDouble(), tokens + (now - last) / 1e9 * rate)  // at most one second of burst
                last = now
                if (tokens >= need) {
                    tokens -= need
                    return
                }
                need -= tokens
                tokens = 0.0
                waitNs = ((need / rate) * 1e9).toLong().coerceIn(1_000_000L, 250_000_000L)
            }
            if (cancelled()) return
            try { Thread.sleep(waitNs / 1_000_000, (waitNs % 1_000_000).toInt()) } catch (e: InterruptedException) { return }
        }
    }
}

/** "Download no more than X MB every Y hours". */
class Quota {
    @Volatile var limitBytes: Long = 0
    @Volatile var periodMs: Long = 0
    private var windowStart = System.currentTimeMillis()
    private var used = 0L
    private val lock = Object()

    /** Returns how long to wait (ms) before more bytes may be downloaded; 0 = go on. */
    fun add(n: Int): Long {
        if (limitBytes <= 0 || periodMs <= 0) return 0
        synchronized(lock) {
            val now = System.currentTimeMillis()
            if (now - windowStart >= periodMs) { windowStart = now; used = 0 }
            used += n
            return if (used >= limitBytes) windowStart + periodMs - now else 0
        }
    }

    fun waitIfNeeded(n: Int, cancelled: () -> Boolean) {
        var wait = add(n)
        while (wait > 0 && !cancelled()) {
            try { Thread.sleep(minOf(wait, 1000)) } catch (e: InterruptedException) { return }
            wait = add(0)
        }
    }
}
