package com.nishantattrey.clipsync.core.sync.network

import com.nishantattrey.clipsync.core.protocol.model.ClipboardDeviceRecord
import com.nishantattrey.clipsync.core.protocol.model.ClipboardInsertResult
import com.nishantattrey.clipsync.core.protocol.model.ClipboardItemRecord
import com.nishantattrey.clipsync.core.protocol.model.FetchAfterParameters
import com.nishantattrey.clipsync.core.protocol.model.InsertClipboardItemParameters
import com.nishantattrey.clipsync.core.protocol.model.InsertItemResponse
import com.nishantattrey.clipsync.core.protocol.model.RegisterDeviceParameters
import com.nishantattrey.clipsync.core.sync.model.ClipboardCloudTransport
import com.nishantattrey.clipsync.core.sync.model.CloudEndpoint
import com.nishantattrey.clipsync.core.sync.model.SyncFailure
import com.nishantattrey.clipsync.core.sync.model.TransportResult
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.accept
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class SupabaseRestTransport(
    private val endpoint: CloudEndpoint,
    private val client: HttpClient = defaultHttpClient(),
    private val timeoutMillis: Long = 20_000,
) : ClipboardCloudTransport {
    override suspend fun registerDevice(parameters: RegisterDeviceParameters): TransportResult<Unit> =
        rpcWithoutResponse("register_clipboard_device", parameters)

    override suspend fun insertText(parameters: InsertClipboardItemParameters): TransportResult<ClipboardInsertResult> =
        rpc<InsertClipboardItemParameters, InsertItemResponse, ClipboardInsertResult>("insert_clipboard_item", parameters) { response ->
            ClipboardInsertResult.parse(response.status)
        }

    override suspend fun fetchAfter(parameters: FetchAfterParameters): TransportResult<List<ClipboardItemRecord>> =
        rpc<FetchAfterParameters, List<ClipboardItemRecord>, List<ClipboardItemRecord>>(
            "get_clipboard_items_after", parameters,
        ) { it }

    override suspend fun fetchDevices(channelId: String): TransportResult<List<ClipboardDeviceRecord>> =
        rpc<ChannelParameters, List<ClipboardDeviceRecord>, List<ClipboardDeviceRecord>>(
            "get_clipboard_devices", ChannelParameters(channelId),
        ) { it }

    private suspend inline fun <reified Request : Any> rpcWithoutResponse(
        function: String,
        request: Request,
    ): TransportResult<Unit> = executeRpc(function, request) { Unit }

    private suspend inline fun <reified Request : Any, reified Response, Result> rpc(
        function: String,
        request: Request,
        crossinline transform: (Response) -> Result,
    ): TransportResult<Result> = executeRpc(function, request) { response ->
        transform(response.body<Response>())
    }

    private suspend inline fun <reified Request : Any, Result> executeRpc(
        function: String,
        request: Request,
        crossinline transform: suspend (io.ktor.client.statement.HttpResponse) -> Result,
    ): TransportResult<Result> = try {
        withTimeout(timeoutMillis) {
            val response = client.post("${endpoint.supabaseUrl}/rest/v1/rpc/$function") {
                header("apikey", endpoint.publishableKey)
                header("Authorization", "Bearer ${endpoint.publishableKey}")
                contentType(ContentType.Application.Json)
                accept(ContentType.Application.Json)
                setBody(request)
            }
            if (response.status.value !in 200..299) {
                return@withTimeout TransportResult.Failure(classifyStatus(response.status))
            }
            TransportResult.Success(transform(response))
        }
    } catch (_: TimeoutCancellationException) {
        TransportResult.Failure(SyncFailure.TimedOut)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: HttpRequestTimeoutException) {
        TransportResult.Failure(SyncFailure.TimedOut)
    } catch (_: IOException) {
        TransportResult.Failure(SyncFailure.Offline)
    } catch (_: kotlinx.serialization.SerializationException) {
        TransportResult.Failure(SyncFailure.InvalidRemoteData("Malformed Supabase response."))
    } catch (_: IllegalArgumentException) {
        TransportResult.Failure(SyncFailure.InvalidRemoteData("Invalid Supabase response."))
    }

    private fun classifyStatus(status: HttpStatusCode): SyncFailure = when (status.value) {
        401, 403 -> SyncFailure.Unauthorized
        408, 429 -> SyncFailure.TemporarilyUnavailable
        in 500..599 -> SyncFailure.TemporarilyUnavailable
        else -> SyncFailure.Rejected("Supabase rejected the request (${status.value}).")
    }

    companion object {
        fun defaultHttpClient(): HttpClient = HttpClient(OkHttp) {
            expectSuccess = false
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = false; explicitNulls = true })
            }
        }
    }
}

@Serializable
private data class ChannelParameters(@SerialName("p_channel_id") val channelId: String)
