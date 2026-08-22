package party.qwer.iris

import android.app.RemoteInput
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import party.qwer.iris.Replier.Companion.SendMessageRequest
import java.io.File

// SendMsg : ye-seola/go-kdb

class Replier {
    companion object {
        // #1 fix: CONFLATED keeps only the latest queued item, so a burst of /reply
        // calls silently dropped all but the last message. UNLIMITED preserves every send.
        private val messageChannel = Channel<SendMessageRequest>(Channel.UNLIMITED)
        private val coroutineScope = CoroutineScope(Dispatchers.IO)
        private var messageSenderJob: Job? = null
        private val mutex = Mutex()

        // #4: set from Main once KakaoDB is constructed. Used to confirm a text
        // message actually landed in the chat DB before considering the send done.
        @Volatile
        var kakaoDb: KakaoDB? = null

        private const val SEND_MAX_ATTEMPTS = 3
        private const val SEND_VERIFY_TIMEOUT_MS = 2000L
        private const val SEND_VERIFY_POLL_MS = 250L

        init {
            startMessageSender()
        }

        fun startMessageSender() {
            coroutineScope.launch {
                if (messageSenderJob?.isActive == true) {
                    messageSenderJob?.cancelAndJoin()
                }
                messageSenderJob = launch {
                    for (request in messageChannel) {
                        try {
                            mutex.withLock {
                                request.send()
                                delay(Configurable.messageSendRate)
                            }
                        } catch (e: Exception) {
                            System.err.println("Error sending message from channel: $e")
                            e.printStackTrace()
                        }
                    }
                }
            }
        }

        fun restartMessageSender() {
            startMessageSender()
        }

        private fun sendMessageInternal(
            referer: String,
            chatId: Long,
            msg: String,
            threadId: Long?
        ) {
            // #3 fix: KakaoTalk rotates NotificationReferer (re-login / push re-register /
            // app update). Re-read it fresh per send instead of using the value cached at
            // startup; fall back to the passed-in referer if the read fails.
            val freshReferer = runCatching { RefererProvider.getReferer() }.getOrDefault(referer)

            val intent = Intent().apply {
                component = ComponentName(
                    "com.kakao.talk", "com.kakao.talk.notification.NotificationActionService"
                )
                putExtra("noti_referer", freshReferer)
                putExtra("chat_id", chatId)

                putExtra("is_chat_thread_notification", threadId != null)
                if (threadId != null) {
                    putExtra("thread_id", threadId)
                }

                action = "com.kakao.talk.notification.REPLY_MESSAGE"

                val results = Bundle().apply {
                    putCharSequence("reply_message", msg)
                }

                val remoteInput = RemoteInput.Builder("reply_message").build()
                RemoteInput.addResultsToIntent(arrayOf(remoteInput), this, results)
            }

            AndroidHiddenApi.startService(intent)
        }

        suspend fun sendMessage(
            referer: String,
            chatId: Long,
            msg: String,
            threadId: Long?
        ): Boolean {
            val completion = CompletableDeferred<Boolean>()
            messageChannel.send(SendMessageRequest {
                val delivered = runCatching {
                    sendTextWithVerify(referer, chatId, msg, threadId)
                }.getOrElse { error ->
                    System.err.println("[SEND] unexpected failure: $error")
                    false
                }
                completion.complete(delivered)
            })
            return completion.await()
        }

        private suspend fun sendTextWithVerify(
            referer: String,
            chatId: Long,
            msg: String,
            threadId: Long?
        ): Boolean {
            val db = kakaoDb
            if (db == null) {
                // DB not wired yet (shouldn't happen after startup): best-effort send.
                sendMessageInternal(referer, chatId, msg, threadId)
                return true
            }

            val baseline = runCatching { db.latestLogId() }.getOrDefault(0L)

            for (attempt in 1..SEND_MAX_ATTEMPTS) {
                try {
                    sendMessageInternal(referer, chatId, msg, threadId)
                } catch (e: Throwable) {
                    // The actual delivery call threw — this is the case we most need to
                    // root-cause. Dump the full exception chain + environment, then retry.
                    SendDiag.logSendFailure(attempt, chatId, threadId, msg, referer, e)
                    if (attempt < SEND_MAX_ATTEMPTS) {
                        delay(SEND_VERIFY_POLL_MS)
                        continue
                    }
                    System.err.println(
                        "[SEND] FAILED (threw) after $SEND_MAX_ATTEMPTS attempts chatId=$chatId msg=\"${msg.take(30)}\""
                    )
                    return false
                }

                var waited = 0L
                while (waited < SEND_VERIFY_TIMEOUT_MS) {
                    delay(SEND_VERIFY_POLL_MS)
                    waited += SEND_VERIFY_POLL_MS
                    val confirmed = runCatching {
                        db.verifyBotMessage(chatId, msg, baseline)
                    }.getOrDefault(false)
                    if (confirmed) {
                        if (attempt > 1) {
                            System.err.println("[SEND] recovered on attempt $attempt chatId=$chatId")
                        }
                        return true
                    }
                }

                // Sent without throwing but never landed in the DB — log environment too,
                // since this is the silent-failure variant.
                System.err.println(
                    "[SEND] not confirmed attempt=$attempt/$SEND_MAX_ATTEMPTS chatId=$chatId " +
                        "uptime=${SendDiag.uptime()} kakao=${SendDiag.kakaoProcInfo()} ams=${AndroidHiddenApi.binderStatus()}"
                )
            }

            System.err.println(
                "[SEND] FAILED (unconfirmed) after $SEND_MAX_ATTEMPTS attempts chatId=$chatId msg=\"${msg.take(30)}\""
            )
            return false
        }


        fun sendPhoto(room: Long, base64ImageDataString: String) {
            coroutineScope.launch {
                messageChannel.send(SendMessageRequest {
                    sendPhotoInternal(
                        room, base64ImageDataString
                    )
                })
            }
        }

        fun sendMultiplePhotos(room: Long, base64ImageDataStrings: List<String>) {
            coroutineScope.launch {
                messageChannel.send(SendMessageRequest {
                    sendMultiplePhotosInternal(
                        room, base64ImageDataStrings
                    )
                })
            }
        }

        private fun sendPhotoInternal(room: Long, base64ImageDataString: String) {
            sendMultiplePhotosInternal(room, listOf(base64ImageDataString))
        }

        private fun sendMultiplePhotosInternal(room: Long, base64ImageDataStrings: List<String>) {
            val picDir = File(IMAGE_DIR_PATH).apply {
                if (!exists()) {
                    mkdirs()
                }
            }

            val uris = base64ImageDataStrings.mapIndexed { idx, base64ImageDataString ->
                val decodedImage = Base64.decode(base64ImageDataString, Base64.DEFAULT)
                val timestamp = System.currentTimeMillis().toString()

                val imageFile = File(picDir, "${timestamp}_${idx}.png").apply {
                    writeBytes(decodedImage)
                }

                val imageUri = Uri.fromFile(imageFile)
                mediaScan(imageUri)
                imageUri
            }

            if (uris.isEmpty()) {
                System.err.println("No image URIs created, cannot send multiple photos.")
                return
            }

            val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                setPackage("com.kakao.talk")
                type = "image/*"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                putExtra("key_id", room)
                putExtra("key_type", 1)
                putExtra("key_from_direct_share", true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }

            try {
                AndroidHiddenApi.startActivity(intent)
            } catch (e: Exception) {
                System.err.println("Error starting activity for sending multiple photos: $e")
                throw e
            }
        }


        internal fun interface SendMessageRequest {
            suspend fun send()
        }

        private fun mediaScan(uri: Uri) {
            val mediaScanIntent = Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE).apply {
                data = uri
            }
            AndroidHiddenApi.broadcastIntent(mediaScanIntent)
        }
    }
}