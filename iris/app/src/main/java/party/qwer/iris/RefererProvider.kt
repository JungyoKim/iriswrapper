package party.qwer.iris

import java.io.File

// #3: single source of truth for KakaoTalk's NotificationReferer.
// Reads it from shared_prefs and re-reads only when the file changes (mtime),
// so every send uses the current referer without hitting the filesystem each time.
object RefererProvider {
    private val prefsFile: File by lazy {
        File("${PathUtils.getAppPath()}shared_prefs/KakaoTalk.hw.perferences.xml")
    }
    private val regex = Regex("""<string name="NotificationReferer">(.*?)</string>""")

    @Volatile
    private var cached: String? = null

    @Volatile
    private var cachedMtime: Long = 0L

    @Synchronized
    fun getReferer(): String {
        val mtime = prefsFile.lastModified()
        val current = cached
        if (current != null && mtime == cachedMtime) {
            return current
        }

        val data = prefsFile.bufferedReader().use { it.readText() }
        val referer = regex.find(data)?.groups?.get(1)?.value
            ?: throw Exception("failed to extract referer from data")

        cached = referer
        cachedMtime = mtime
        return referer
    }
}
