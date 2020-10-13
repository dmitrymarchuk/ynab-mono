package com.github.smaugfm.mono

import com.github.smaugfm.events.Event
import io.ktor.application.*
import io.ktor.client.*
import io.ktor.client.features.*
import io.ktor.client.features.json.*
import io.ktor.client.features.json.serializer.*
import io.ktor.client.request.*
import io.ktor.features.*
import io.ktor.http.*
import io.ktor.request.*
import io.ktor.response.*
import io.ktor.routing.*
import io.ktor.serialization.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.net.URI
import kotlin.coroutines.CoroutineContext

class MonoApi(private val token: String) {
    init {
        require(token.isNotBlank())
    }

    private var previousStatementCallTimestamp = Long.MIN_VALUE / 2

    private val httpClient = HttpClient {
        defaultRequest {
            header("X-Token", token)
        }
        install(JsonFeature) {
            serializer = KotlinxSerializer()
        }
    }

    suspend fun fetchUserInfo(): MonoUserInfo {
        val infoString = httpClient.get<String>(url("personal/client-info"))
        return Json.decodeFromString(infoString)
    }

    suspend fun setWebHook(url: URI): MonoStatusResponse {
        require(url.toASCIIString() == url.toString())

        val json = defaultSerializer()
        val server = embeddedServer(Netty, port = url.port) {
            routing {
                get(url.path) {
                    call.response.status(HttpStatusCode.OK)
                    call.respondText("OK\n", ContentType.Text.Plain)
                    println("Webhook setup successful: $url")
                }
            }
        }
        server.start(wait = false)
        val statusString = httpClient.post<String>(url("personal/webhook")) {
            body = json.write(MonoWebHookRequest(url.toString()))
        }
        server.stop(100, 100)
        return Json.decodeFromString(statusString)
    }

    suspend fun fetchStatementItems(
        id: MonoAccountId,
        from: Instant,
        to: Instant = Clock.System.now(),
    ): List<MonoStatementItem> {
        val currentTime = System.currentTimeMillis()
        if (currentTime - previousStatementCallTimestamp < StatementCallRate) {
            delay(StatementCallRate - (currentTime - previousStatementCallTimestamp))
        }

        val itemsString =
            httpClient.get<String>(url("personal/statement/$id/${from.epochSeconds}/${to.epochSeconds}")).also {
                previousStatementCallTimestamp = System.currentTimeMillis()
            }

        return Json.decodeFromString(itemsString)
    }


    suspend fun fetchBankCurrency(): List<MonoCurrencyInfo> {
        val infoString = httpClient.get<String>(url("bank/currency"))
        return Json.decodeFromString(infoString)
    }

    companion object {
        private const val StatementCallRate = 60000
        private fun url(endpoint: String) = "https://api.monobank.ua/${endpoint}"

        fun startMonoWebhookServerAsync(
            context: CoroutineContext,
            webhook: URI,
            dispatch: suspend (event: Event) -> Unit,
        ): Job {
            val server = embeddedServer(Netty, port = webhook.port) {
                install(ContentNegotiation) {
                    json()
                }
                routing {
                    post(webhook.path) {
                        val response = call.receive<MonoWebhookResponse>();
                        call.response.status(HttpStatusCode.OK)
                        dispatch(Event.Mono.NewStatementReceived(response.data))
                    }
                }
            }
            return GlobalScope.launch(context) {
                server.start(wait = true).let { Unit }
            }
        }

        suspend fun Collection<MonoApi>.setupWebhook(webhook: URI) {
            this.forEach {
                it.setWebHook(webhook)
            }
        }
    }
}