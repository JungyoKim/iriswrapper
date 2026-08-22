package party.qwer.iris

import android.database.Cursor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.security.MessageDigest
import java.util.LinkedList
import java.util.concurrent.Executors
import kotlin.collections.set

class ObserverHelper(
    private val db: KakaoDB,
    private val eventStream: IrisEventStream,
) {
    private val eventCursorStore = EventCursorStore()
    private val persistedCursor = eventCursorStore.load()

    @Volatile
    private var lastLogId: Long = persistedCursor ?: 0L

    @Volatile
    private var cursorInitialized = persistedCursor != null

    private var databaseCursorChecked = false

    init {
        if (cursorInitialized) {
            eventStream.advanceCursor(lastLogId)
        }
    }

    private val lastDecryptedLogs = LinkedList<Map<String, String?>>()
    private val httpRequestExecutor = Executors.newFixedThreadPool(8)
    private val okHttpClient = OkHttpClient()

    val currentCursor: Long
        get() = lastLogId

    val isCursorInitialized: Boolean
        get() = cursorInitialized

    fun checkChange(kakaoDb: KakaoDB) {
        if (!cursorInitialized) {
            val baseline = getLastLogIdFromDB()
            markProcessed(baseline)
            println("Initial lastLogId: $lastLogId")
            return
        }

        if (!databaseCursorChecked) {
            databaseCursorChecked = true
            val latestLogId = getLastLogIdFromDB()
            if (latestLogId < lastLogId) {
                System.err.println("[EVENT] KakaoTalk database cursor reset: $lastLogId -> $latestLogId")
                markProcessed(latestLogId)
                return
            }
        }

        if (getNewLogCountFromDB() == 0) return

        println("Detected new log(s). Processing...")
        kakaoDb.connection.rawQuery(
            "SELECT * FROM chat_logs WHERE _id > ? ORDER BY _id ASC",
            arrayOf(lastLogId.toString())
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val currentLogId = cursor.getLong(cursor.getColumnIndexOrThrow("_id"))
                if (currentLogId <= lastLogId) continue

                try {
                    val event = buildEvent(cursor, storeInDashboard = true)
                    if (event != null) {
                        if (Configurable.webServerEndpoint.isNotEmpty()) {
                            httpRequestExecutor.execute {
                                sendPostRequest(event.payload)
                            }
                        }
                        eventStream.publish(event)
                    }
                    markProcessed(currentLogId)
                } catch (e: Exception) {
                    System.err.println("[EVENT] processing failed logId=$currentLogId: $e")
                    return
                }
            }
        }
    }

    fun replayEventsAfter(afterLogId: Long): List<IrisEvent> {
        if (afterLogId < 0L) return emptyList()

        val replayThrough = lastLogId
        if (afterLogId >= replayThrough) return emptyList()

        val events = ArrayList<IrisEvent>()
        db.connection.rawQuery(
            "SELECT * FROM chat_logs WHERE _id > ? AND _id <= ? ORDER BY _id ASC",
            arrayOf(afterLogId.toString(), replayThrough.toString())
        ).use { cursor ->
            while (cursor.moveToNext()) {
                buildEvent(cursor, storeInDashboard = false)?.let(events::add)
            }
        }
        return events
    }

    private fun markProcessed(logId: Long) {
        check(eventCursorStore.save(logId)) {
            "[EVENT] unable to persist source cursor=$logId"
        }
        lastLogId = logId
        cursorInitialized = true
        eventStream.advanceCursor(logId)
    }

    private fun buildEvent(cursor: Cursor, storeInDashboard: Boolean): IrisEvent? {
        val columnNames = cursor.columnNames
        val currentLogId = cursor.getLong(cursor.getColumnIndexOrThrow("_id"))
        val v = JSONObject(cursor.getString(cursor.getColumnIndexOrThrow("v")))
        val enc = v.optInt("enc", 0)
        val origin = v.optString("origin")

        if (origin == "SYNCMSG" || origin == "MCHATLOGS") return null

        val chatId = cursor.getLong(cursor.getColumnIndexOrThrow("chat_id"))
        val userId = cursor.getLong(cursor.getColumnIndexOrThrow("user_id"))
        var message = cursor.getString(cursor.getColumnIndexOrThrow("message")) ?: ""
        var attachment = cursor.getString(cursor.getColumnIndexOrThrow("attachment")) ?: "{}"
        val messageType = cursor.getString(cursor.getColumnIndexOrThrow("type")).orEmpty()
        val threadId = cursor.getColumnIndex("thread_id").takeIf { it >= 0 }?.let(cursor::getString)

        var supplement = cursor.getColumnIndex("supplement")
            .takeIf { it >= 0 }
            ?.let(cursor::getString)
            ?: "{}"
        if (supplement.isNotEmpty() && supplement != "{}") {
            supplement = runCatching {
                KakaoDecrypt.decrypt(enc, supplement, userId)
            }.getOrDefault(supplement)
        }

        if (message.isNotEmpty() && message != "{}") {
            message = runCatching {
                KakaoDecrypt.decrypt(enc, message, userId)
            }.getOrElse {
                println("failed to decrypt message: $it")
                message
            }
        }

        if ((message.contains("선물") && messageType == "71") || attachment == "null") {
            attachment = "{}"
        } else if (attachment.isNotEmpty() && attachment != "{}") {
            attachment = runCatching {
                KakaoDecrypt.decrypt(enc, attachment, userId)
            }.getOrElse {
                println("failed to decrypt attachment: $it")
                attachment
            }
        }

        if (storeInDashboard) {
            storeDecryptedLog(cursor, message)
        }

        val raw = mutableMapOf<String, String?>()
        val attachmentData = getStringJsonToMap(attachment)
        attachmentData["src_isThread"] = false
        val supplementData = getStringJsonToMap(supplement)

        for ((index, columnName) in columnNames.withIndex()) {
            when (columnName) {
                "message" -> raw[columnName] = message
                "attachment" -> raw[columnName] = attachment
                "supplement" -> raw[columnName] = supplement
                else -> raw[columnName] = cursor.getString(index)
            }
        }

        val supplementThreadId = supplementData["threadId"]?.toString().orEmpty()
        val sourceLogId = attachmentData["src_logId"]?.toString().orEmpty()
        if (
            threadId.isNullOrEmpty() &&
            supplementThreadId.isNotEmpty() &&
            sourceLogId.isEmpty() &&
            messageType == "1"
        ) {
            attachmentData["src_logId"] = supplementThreadId
            attachmentData["src_isThread"] = true
        } else if (!threadId.isNullOrEmpty() && messageType == "1") {
            threadId.toLongOrNull()?.let {
                attachmentData["src_logId"] = it
                attachmentData["src_isThread"] = true
            }
        }
        raw["attachment"] = JSONObject(attachmentData).toString()

        val chatInfo = db.getChatInfo(chatId, userId)
        var roomName = chatInfo[0]
        var senderName = chatInfo[1]

        if (senderName.isNullOrEmpty()) {
            runCatching {
                val rawKey = "person_${chatId}:${userId}"
                val digest = MessageDigest.getInstance("SHA-256")
                    .digest(rawKey.toByteArray())
                    .joinToString("") { "%02x".format(it) }
                NamesDB.getName(digest)
            }.getOrNull()?.let { fallbackInfo ->
                senderName = fallbackInfo.first
                if (roomName.isNullOrEmpty()) {
                    roomName = fallbackInfo.second
                }
            }
        }

        val payload = JSONObject(
            mapOf(
                "msg" to message,
                "room" to roomName,
                "sender" to senderName,
                "json" to raw
            )
        ).toString()

        return IrisEvent(currentLogId, payload)
    }

    private fun getLastLogIdFromDB(): Long {
        val lastLog = db.logToDict(0)
        return lastLog["_id"]?.toLongOrNull() ?: 0
    }

    private fun getStringJsonToMap(data: String?): MutableMap<String, Any?> {
        if (data.isNullOrEmpty()) return HashMap()
        try {
            val object_ = JSONObject(data)
            val map: MutableMap<String, Any?> = HashMap()

            val keys: MutableIterator<String> = object_.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val value: Any? = object_.get(key)
                map[key] = value
            }

            return map
        } catch (e: Exception) {
            println("failed to parse JSON to map: $e")
            return HashMap()
        }
    }

    private fun getNewLogCountFromDB(): Int {
        val res = db.executeQuery(
            "select 1 as found from chat_logs where _id > ? limit 1",
            arrayOf(lastLogId.toString())
        )
        return res.size
    }

    @Synchronized
    private fun storeDecryptedLog(cursor: Cursor, decryptedMessage: String?) {
        val logEntry: MutableMap<String, String?> = HashMap()
        logEntry["_id"] = cursor.getString(cursor.getColumnIndexOrThrow("_id"))
        logEntry["chat_id"] = cursor.getString(cursor.getColumnIndexOrThrow("chat_id"))
        logEntry["user_id"] = cursor.getString(cursor.getColumnIndexOrThrow("user_id"))
        logEntry["message"] = decryptedMessage
        logEntry["created_at"] = cursor.getString(cursor.getColumnIndexOrThrow("created_at"))

        lastDecryptedLogs.addFirst(logEntry)
        if (lastDecryptedLogs.size > MAX_LOGS_STORED) {
            lastDecryptedLogs.removeLast()
        }
    }

    private fun sendPostRequest(jsonData: String) {
        val url = Configurable.webServerEndpoint
        println("Sending HTTP POST request to: $url")
        println("JSON Data being sent: $jsonData")

        val mediaType = "application/json; charset=utf-8".toMediaType()
        val requestBody = jsonData.toRequestBody(mediaType)

        val request = Request.Builder()
            .url(url)
            .post(requestBody)
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .build()

        try {
            okHttpClient.newCall(request).execute().use { response ->
                val responseCode = response.code
                println("HTTP Response Code: $responseCode")

                if (response.isSuccessful) {
                    val responseBody = response.body?.string()
                    println("HTTP Response Body: $responseBody")
                } else {
                    System.err.println("HTTP Error Response: $responseCode - ${response.message}")
                }
            }
        } catch (e: IOException) {
            System.err.println("Error sending POST request: " + e.message)
        }
    }

    val lastChatLogs: List<Map<String, String?>>
        get() = lastDecryptedLogs

    companion object {
        private const val MAX_LOGS_STORED = 50
    }
}