package com.nishantattrey.clipsync.core.sync.network

import com.nishantattrey.clipsync.core.protocol.validation.ProtocolValueValidator
import com.nishantattrey.clipsync.core.sync.model.CloudEndpoint
import com.nishantattrey.clipsync.core.sync.model.RealtimeWakeSource
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.header
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import io.ktor.websocket.send
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Supabase Realtime is deliberately reduced to an INSERT wake signal; row data is never consumed here. */
class SupabaseRealtimeWakeSource(
    private val endpoint: CloudEndpoint,
    private val client: HttpClient = HttpClient(OkHttp) { install(WebSockets) },
    private val json: Json = Json,
) : RealtimeWakeSource {
    override suspend fun awaitWake(channelId: String) {
        ProtocolValueValidator.requireChannelId(channelId)
        val websocketUrl = endpoint.supabaseUrl
            .replaceFirst("https://", "wss://")
            .replaceFirst("http://", "ws://") +
            "/realtime/v1/websocket?apikey=${encode(endpoint.publishableKey)}&vsn=1.0.0"
        client.webSocket(websocketUrl, request = {
            header("Authorization", "Bearer ${endpoint.publishableKey}")
        }) {
            send(Frame.Text(joinMessage(channelId)))
            for (frame in incoming) {
                val text = (frame as? Frame.Text)?.readText() ?: continue
                val event = runCatching { json.parseToJsonElement(text).jsonObject["event"]?.jsonPrimitive?.content }
                    .getOrNull()
                if (event == "postgres_changes") return@webSocket
            }
        }
    }

    private fun joinMessage(channelId: String): String = json.encodeToString(
        kotlinx.serialization.json.JsonObject.serializer(),
        buildJsonObject {
            put("topic", "realtime:public:clipboard_items")
            put("event", "phx_join")
            put("ref", "1")
            put("join_ref", "1")
            put("payload", buildJsonObject {
                put("config", buildJsonObject {
                    put("broadcast", buildJsonObject { put("ack", false); put("self", false) })
                    put("presence", buildJsonObject { put("key", "") })
                    put("postgres_changes", buildJsonArray {
                        add(buildJsonObject {
                            put("event", "INSERT")
                            put("schema", "public")
                            put("table", "clipboard_items")
                            put("filter", "channel_id=eq.$channelId")
                        })
                    })
                })
                put("access_token", endpoint.publishableKey)
            })
        },
    )

    private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8.name())
}
