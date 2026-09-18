package dev.tokitoki.jetbrains.tracking

/**
 * Lines a human typed, per file, waiting for the next heartbeat to carry them.
 *
 * The server splits line churn by who produced it: an agent's file_edit
 * reports its own diff, and everything on an IDE heartbeat counts as a person
 * at a keyboard. So the one thing this must get right is to count only what a
 * person typed. The IDE reports every edit the same way — a keystroke, a
 * paste, a completion, a formatter, an agent rewriting the file on disk — and
 * the shape of the change is the only tell:
 *
 *   typed:  one change that inserts at most one character (plus the
 *           whitespace auto-indent adds after Enter) or deletes a range
 *   bulk:   anything else
 *
 * Bulk changes are dropped, not misfiled. The rule is WakaTime's.
 *
 * Counted per change rather than as a line-count delta so additions and
 * removals survive separately: typing a line then deleting another is
 * +1/-1, not 0.
 */
class LineChanges {
    data class Delta(val added: Int, val removed: Int)

    private val pending = HashMap<String, Delta>()

    /** Records one document change against its file; a bulk change counts
     * for nothing. `inserted` and `removed` are the fragments the IDE
     * reported. */
    @Synchronized
    fun record(entity: String, inserted: CharSequence, removed: CharSequence) {
        if (!isTyped(inserted)) return
        val added = inserted.count { it == '\n' }
        val removedLines = removed.count { it == '\n' }
        if (added == 0 && removedLines == 0) return
        val current = pending[entity] ?: Delta(0, 0)
        pending[entity] = Delta(current.added + added, current.removed + removedLines)
    }

    /** Hands over a file's lines and forgets them: each line rides exactly
     * one heartbeat. */
    @Synchronized
    fun take(entity: String): Delta = pending.remove(entity) ?: Delta(0, 0)

    @Synchronized
    fun has(entity: String): Boolean = pending.containsKey(entity)

    @Synchronized
    fun clear() = pending.clear()

    companion object {
        fun isTyped(inserted: CharSequence): Boolean = inserted.isEmpty() || inserted.trim().length <= 1
    }
}
