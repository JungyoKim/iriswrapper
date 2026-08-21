// SendMsg : ye-seola/go-kdb
// Kakaodecrypt : jiru/kakaodecrypt
package party.qwer.iris

import kotlinx.coroutines.flow.MutableSharedFlow
import java.util.concurrent.TimeUnit

const val IMAGE_DIR_PATH: String = "/sdcard/Android/data/com.kakao.talk/files"

class Main {
    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            try {
                val wsEventFlow = MutableSharedFlow<String>()

                // #3: validate the referer is readable at startup; per-send reads go
                // through RefererProvider so a rotated referer is picked up automatically.
                val notificationReferer = RefererProvider.getReferer()

                Replier.startMessageSender()
                println("Message sender thread started")

                val kakaoDb = KakaoDB()
                // #4: give the sender DB access so it can confirm message delivery.
                Replier.kakaoDb = kakaoDb

                // Low-level send diagnostics: periodic environment snapshot so we can
                // correlate what drifts (kakao proc state / AMS binder / referer) before
                // the over-time send failure hits.
                SendDiag.startHeartbeat()
                val observerHelper = ObserverHelper(kakaoDb, wsEventFlow)

                val dbObserver = DBObserver(kakaoDb, observerHelper)
                dbObserver.startPolling()
                println("DBObserver started")

                val notificationPoller = NotificationPoller()
                notificationPoller.startPolling()
                println("Notification Poller started")

                val imageDeleter = ImageDeleter(IMAGE_DIR_PATH, TimeUnit.HOURS.toMillis(1))
                imageDeleter.startDeletion()
                println("ImageDeleter started, and will delete images older than 1 hour.")

                val irisServer = IrisServer(
                    kakaoDb, dbObserver, observerHelper, notificationReferer, wsEventFlow
                )
                irisServer.startServer()
                println("Iris Server started")

                kakaoDb.closeConnection()
            } catch (e: Exception) {
                System.err.println("Iris Error")
                e.printStackTrace()
            }
        }
    }
}

