package party.qwer.iris

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.KotlinxWebsocketSerializationConverter
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.json.JSONObject
import party.qwer.iris.model.AotResponse
import party.qwer.iris.model.ApiResponse
import party.qwer.iris.model.CommonErrorResponse
import party.qwer.iris.model.ConfigRequest
import party.qwer.iris.model.ConfigResponse
import party.qwer.iris.model.DashboardStatusResponse
import party.qwer.iris.model.DecryptRequest
import party.qwer.iris.model.DecryptResponse
import party.qwer.iris.model.QueryRequest
import party.qwer.iris.model.QueryResponse
import party.qwer.iris.model.ReplyRequest
import party.qwer.iris.model.ReplyType


class IrisServer(
    private val kakaoDB: KakaoDB,
    private val dbObserver: DBObserver,
    private val observerHelper: ObserverHelper,
    private val notificationReferer: String,
    private val eventStream: IrisEventStream
) {
    private val eventAckStore = EventAckStore()
    fun startServer() {
        embeddedServer(Netty, port = Configurable.botSocketPort) {
            install(WebSockets) {
                contentConverter = KotlinxWebsocketSerializationConverter(Json)
            }

            install(ContentNegotiation) {
                json()
            }

            install(StatusPages) {
                exception<Throwable> { call, cause ->
                    call.respond(
                        HttpStatusCode.InternalServerError, CommonErrorResponse(
                            message = cause.message ?: "unknown error"
                        )
                    )
                }
            }

            routing {
                route("/dashboard") {
                    get {
                        val html = PageRenderer.renderDashboard()
                        call.respondText(html, ContentType.Text.Html)
                    }

                    get("status") {
                        call.respond(
                            DashboardStatusResponse(
                                isObserving = dbObserver.isPollingThreadAlive,
                                statusMessage = if (dbObserver.isPollingThreadAlive) {
                                    "Observing database"
                                } else {
                                    "Not observing database"
                                },
                                lastLogs = observerHelper.lastChatLogs
                            )
                        )
                    }
                }

                route("/config") {
                    get {
                        call.respond(
                            ConfigResponse(
                                bot_name = Configurable.botName,
                                bot_http_port = Configurable.botSocketPort,
                                web_server_endpoint = Configurable.webServerEndpoint,
                                db_polling_rate = Configurable.dbPollingRate,
                                message_send_rate = Configurable.messageSendRate,
                                bot_id = Configurable.botId,
                            )
                        )
                    }

                    post("{name}") {
                        val name = call.parameters["name"]
                        val req = call.receive<ConfigRequest>()

                        when (name) {
                            "endpoint" -> {
                                var value = req.endpoint
                                if (value == null) {
                                    value = ""
                                }
                                Configurable.webServerEndpoint = value
                            }

                            "botname" -> {
                                val value = req.botname
                                if (value.isNullOrBlank()) {
                                    throw Exception("missing or empty value")
                                }
                                Configurable.botName = value
                            }

                            "dbrate" -> {
                                val value = req.rate ?: throw Exception("missing or invalid value")

                                Configurable.dbPollingRate = value
                            }

                            "sendrate" -> {
                                val value = req.rate ?: throw Exception("missing or invalid value")

                                Configurable.messageSendRate = value
                            }

                            "botport" -> {
                                val value = req.port ?: throw Exception("missing or invalid value")

                                if (value < 1 || value > 65535) {
                                    throw Exception("Invalid port number. Port must be between 1 and 65535.")
                                }

                                Configurable.botSocketPort = value
                            }

                            else -> {
                                throw Exception("Unknown config $name")
                            }
                        }

                        call.respond(ApiResponse(success = true, message = "success"))
                    }
                }

                get("/aot") {
                    val aotToken = AuthProvider.getToken()

                    call.respond(
                        AotResponse(
                            success = true,
                            aot = Json.parseToJsonElement(aotToken.toString()).jsonObject
                        )
                    )
                }

                post("/reply") {
                    val replyRequest = call.receive<ReplyRequest>()
                    val roomId = replyRequest.room.toLong()
                    val threadId = replyRequest.threadId?.toLong()

                    val delivered = when (replyRequest.type) {
                        ReplyType.TEXT -> Replier.sendMessage(
                            notificationReferer,
                            roomId,
                            replyRequest.data.jsonPrimitive.content,
                            threadId
                        )

                        ReplyType.IMAGE -> {
                            Replier.sendPhoto(roomId, replyRequest.data.jsonPrimitive.content)
                            true
                        }

                        ReplyType.IMAGE_MULTIPLE -> {
                            Replier.sendMultiplePhotos(
                                roomId,
                                replyRequest.data.jsonArray.map { it.jsonPrimitive.content }
                            )
                            true
                        }
                    }

                    if (delivered) {
                        call.respond(ApiResponse(success = true, message = "success"))
                    } else {
                        call.respond(
                            HttpStatusCode.ServiceUnavailable,
                            ApiResponse(success = false, message = "delivery verification failed")
                        )
                    }
                }

                post("/query") {
                    val queryRequest = call.receive<QueryRequest>()

                    try {
                        val rows = kakaoDB.executeQuery(
                            queryRequest.query,
                            (queryRequest.bind?.map { it.content } ?: listOf()).toTypedArray())

                        call.respond(QueryResponse(data = rows.map {
                            KakaoDB.decryptRow(it)
                        }))
                    } catch (e: Exception) {
                        throw Exception("Query 오류: query=${queryRequest.query}, err=${e.message}")
                    }
                }

                get("/event-cursor") {
                    call.respond(mapOf("cursor" to observerHelper.currentCursor))
                }

                post("/decrypt") {
                    val decryptRequest = call.receive<DecryptRequest>()
                    val plaintext = KakaoDecrypt.decrypt(
                        decryptRequest.enc,
                        decryptRequest.b64_ciphertext,
                        decryptRequest.user_id ?: Configurable.botId
                    )

                    call.respond(DecryptResponse(plain_text = plaintext))
                }

                webSocket("/ws") {
                    val clientId = call.request.queryParameters["client"]
                    val streamCursor = eventStream.currentCursor()
                    if (!clientId.isNullOrBlank() && !observerHelper.isCursorInitialized) {
                        close(
                            CloseReason(
                                CloseReason.Codes.TRY_AGAIN_LATER,
                                "event cursor is initializing"
                            )
                        )
                        return@webSocket
                    }
                    val afterLogId = if (clientId.isNullOrBlank()) {
                        call.request.queryParameters["after"]?.toLongOrNull()
                            ?: streamCursor
                    } else {
                        eventAckStore.cursorFor(clientId, streamCursor)
                    }
                    val subscription = eventStream.subscribe(
                        afterLogId,
                        observerHelper::replayEventsAfter
                    )
                    val sender = launch {
                        for (event in subscription.replay) {
                            send(event.payload)
                        }
                        for (event in subscription.live) {
                            send(event.payload)
                        }
                        close(
                            CloseReason(
                                CloseReason.Codes.TRY_AGAIN_LATER,
                                "event subscriber must resume"
                            )
                        )
                    }

                    try {
                        for (frame in incoming) {
                            if (frame is Frame.Text && !clientId.isNullOrBlank()) {
                                acknowledge(clientId, frame.readText())
                            }
                        }
                    } finally {
                        eventStream.unsubscribe(subscription.live)
                        sender.cancelAndJoin()
                    }
                }
            }
        }.start(wait = true)
    }

    private fun acknowledge(clientId: String, payload: String) {
        val acknowledgement = runCatching { JSONObject(payload) }.getOrNull() ?: return
        if (acknowledgement.optString("type") != "ack") return

        val cursor = acknowledgement.optString("cursor").toLongOrNull() ?: return
        if (!eventAckStore.acknowledge(clientId, cursor, eventStream.currentCursor())) {
            System.err.println("[EVENT] rejected acknowledgement client=$clientId cursor=$cursor")
        }
    }
}
