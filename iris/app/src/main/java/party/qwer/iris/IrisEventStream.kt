package party.qwer.iris

import kotlinx.coroutines.channels.Channel
import java.util.ArrayDeque

data class IrisEvent(
    val logId: Long,
    val payload: String,
)

data class IrisEventSubscription(
    val replay: List<IrisEvent>,
    val live: Channel<IrisEvent>,
)

class IrisEventStream(
    private val journalCapacity: Int = JOURNAL_CAPACITY,
) {
    private val lock = Any()
    private val journal = ArrayDeque<IrisEvent>()
    private val subscribers = mutableSetOf<Channel<IrisEvent>>()
    private var highWaterMark = 0L

    fun advanceCursor(logId: Long) {
        synchronized(lock) {
            if (logId > highWaterMark) {
                highWaterMark = logId
            }
        }
    }

    fun currentCursor(): Long = synchronized(lock) { highWaterMark }

    fun publish(event: IrisEvent) {
        synchronized(lock) {
            if (event.logId <= highWaterMark) return

            highWaterMark = event.logId
            journal.addLast(event)
            while (journal.size > journalCapacity) {
                journal.removeFirst()
            }

            val iterator = subscribers.iterator()
            while (iterator.hasNext()) {
                val subscriber = iterator.next()
                if (subscriber.trySend(event).isFailure) {
                    subscriber.close()
                    iterator.remove()
                    System.err.println("[EVENT] disconnected slow WebSocket subscriber")
                }
            }
        }
    }

    fun subscribe(
        afterLogId: Long,
        replayProvider: (Long) -> List<IrisEvent>,
    ): IrisEventSubscription {
        val subscriber = Channel<IrisEvent>(LIVE_BUFFER_CAPACITY)

        val replay = synchronized(lock) {
            val firstBufferedLogId = journal.firstOrNull()?.logId
            val requiresDatabaseReplay = firstBufferedLogId == null ||
                afterLogId < firstBufferedLogId - 1
            val events = if (requiresDatabaseReplay) {
                replayProvider(afterLogId)
            } else {
                journal.filter { it.logId > afterLogId }
            }
            subscribers.add(subscriber)
            events
        }

        return IrisEventSubscription(replay, subscriber)
    }

    fun unsubscribe(subscriber: Channel<IrisEvent>) {
        synchronized(lock) {
            subscribers.remove(subscriber)
            subscriber.close()
        }
    }

    companion object {
        private const val JOURNAL_CAPACITY = 4096
        private const val LIVE_BUFFER_CAPACITY = 1024
    }
}
