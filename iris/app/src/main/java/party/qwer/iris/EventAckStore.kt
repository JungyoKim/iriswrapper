package party.qwer.iris

import java.io.File

class EventAckStore(
    private val directory: File = File(
        System.getenv("IRIS_EVENT_ACK_DIRECTORY") ?: "/data/local/tmp/iris-event-acks"
    )
) {
    @Synchronized
    fun cursorFor(clientId: String, initialCursor: Long): Long {
        val store = EventCursorStore(cursorFile(clientId))
        val savedCursor = store.load()
        if (savedCursor != null) return savedCursor

        check(store.save(initialCursor)) {
            "[EVENT] unable to initialize acknowledgement cursor for $clientId"
        }
        return initialCursor
    }

    @Synchronized
    fun acknowledge(clientId: String, cursor: Long, highWaterMark: Long): Boolean {
        if (cursor < 0L || cursor > highWaterMark) return false

        val store = EventCursorStore(cursorFile(clientId))
        val previous = store.load() ?: 0L
        return if (cursor > previous) {
            store.save(cursor)
        } else {
            true
        }
    }

    private fun cursorFile(clientId: String): File {
        val safeClientId = clientId
            .replace(Regex("[^A-Za-z0-9_.-]"), "_")
            .take(64)
            .ifEmpty { "anonymous" }
        return File(directory, "$safeClientId.cursor")
    }
}
