package com.scanner.overlay.scanner

class FrameWindow(private val windowMs: Long) {
    private data class Entry(val code: String, val timestamp: Long)
    private val buffer = ArrayDeque<Entry>()

    fun add(code: String, now: Long, requiredMatches: Int = 2): String? {
        prune(now)
        buffer.addLast(Entry(code, now))
        if (buffer.size < requiredMatches) return null
        val first = buffer.first().code
        if (buffer.count { it.code == first } >= requiredMatches) return first
        return null
    }

    fun bestCanonical(): String? {
        if (buffer.isEmpty()) return null
        return buffer.groupingBy { it.code }.eachCount()
            .maxByOrNull { it.value }?.key
    }

    fun clear() = buffer.clear()

    fun isEmpty(): Boolean = buffer.isEmpty()

    fun size(): Int = buffer.size

    private fun prune(now: Long) {
        val cutoff = now - windowMs
        while (buffer.isNotEmpty() && buffer.first().timestamp < cutoff) {
            buffer.removeFirst()
        }
    }
}
