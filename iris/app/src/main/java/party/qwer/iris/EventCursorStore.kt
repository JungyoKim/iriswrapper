package party.qwer.iris

import java.io.File

class EventCursorStore(
    private val file: File = File(
        System.getenv("IRIS_EVENT_CURSOR_PATH") ?: "/data/local/tmp/iris-event-cursor"
    )
) {
    @Synchronized
    fun load(): Long? = runCatching {
        file.readText().trim().toLongOrNull()
    }.getOrNull()

    @Synchronized
    fun save(cursor: Long): Boolean {
        val directory = file.parentFile ?: return false
        if (!directory.exists() && !directory.mkdirs()) {
            System.err.println("[EVENT] unable to create cursor directory: $directory")
            return false
        }

        val temporary = File(directory, ".${file.name}.tmp")
        return runCatching {
            temporary.writeText(cursor.toString())
            if (!temporary.renameTo(file)) {
                temporary.copyTo(file, overwrite = true)
                temporary.delete()
            }
            true
        }.getOrElse {
            System.err.println("[EVENT] unable to persist cursor=$cursor: $it")
            false
        }
    }
}
