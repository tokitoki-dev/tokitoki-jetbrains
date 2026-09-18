package dev.tokitoki.jetbrains.tracking

/** The interval every Tokitoki editor plugin uses. */
const val DEFAULT_THROTTLE_MS = 2 * 60 * 1000L

/**
 * Heartbeat throttling: writes always pass, and a changed entity or category
 * always passes. Otherwise one heartbeat per interval.
 *
 * Entity and category collapse into one key so "file changed" and "category
 * changed" are the same rule, not two special cases.
 */
class HeartbeatThrottler(private val intervalMs: Long = DEFAULT_THROTTLE_MS) {
    private var lastKey = ""
    private var lastSentAt = 0L

    init {
        require(intervalMs > 0) { "interval must be positive" }
    }

    @Synchronized
    fun shouldSend(entity: String, category: String, timeMs: Long, isWrite: Boolean): Boolean {
        val key = "$category|$entity"
        if (!isWrite && key == lastKey && timeMs - lastSentAt < intervalMs) {
            return false
        }
        lastKey = key
        lastSentAt = timeMs
        return true
    }
}
