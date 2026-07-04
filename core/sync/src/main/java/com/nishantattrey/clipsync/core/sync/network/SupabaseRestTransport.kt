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
import com.nishantattrey.clipsync.core.sync.model.StorageUploadResult
import com.nishantattrey.clipsync.core.protocol.ProtocolV1
import com.nishantattrey.clipsync.core.protocol.validation.StoragePathValidator
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.accept
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.request.get
import io.ktor.client.request.put
import io.ktor.client.statement.bodyAsChannel
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
import io.ktor.utils.io.readAvailable
import java.io.ByteArrayOutputStream

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

    override suspend fun uploadEncryptedImage(path: String, exactBytes: ByteArray): TransportResult<StorageUploadResult> {
        StoragePathValidator.validate(path)
        require(exactBytes.size <= ProtocolV1.MAX_STORAGE_OBJECT_BYTES) { "Encrypted image exceeds the V1 limit." }
        return networkResult {
            val response = client.put("${endpoint.supabaseUrl}/storage/v1/object/${ProtocolV1.IMAGE_BUCKET}/$path") {
                header("apikey", endpoint.publishableKey)
                header("Authorization", "Bearer ${endpoint.publishableKey}")
                header("x-upsert", "false")
                header("If-None-Match", "*")
                contentType(ContentType.Application.OctetStream)
                setBody(exactBytes)
            }
            when {
                response.status.value in 200..299 -> StorageUploadResult.UPLOADED
                response.status.value == 409 -> when (val existing = downloadEncryptedImage(path)) {
                    is TransportResult.Success -> try {
                        if (existing.value.contentEquals(exactBytes)) StorageUploadResult.IDENTICAL_EXISTING
                        else StorageUploadResult.COLLISION
                    } finally { existing.value.fill(0) }
                    is TransportResult.Failure -> throw StorageReadException(existing.failure)
                }
                else -> throw HttpStatusException(response.status)
            }
        }
    }

    override suspend fun downloadEncryptedImage(path: String): TransportResult<ByteArray> {
        StoragePathValidator.validate(path)
        return networkResult {
            val response = client.get("${endpoint.supabaseUrl}/storage/v1/object/${ProtocolV1.IMAGE_BUCKET}/$path") {
                header("apikey", endpoint.publishableKey)
                header("Authorization", "Bearer ${endpoint.publishableKey}")
            }
            if (response.status.value !in 200..299) throw HttpStatusException(response.status)
            val channel = response.bodyAsChannel()
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(32 * 1024)
            try {
                var total = 0
                while (!channel.isClosedForRead) {
                    val count = channel.readAvailable(buffer)
                    if (count <= 0) continue
                    total += count
                    if (total > ProtocolV1.MAX_STORAGE_OBJECT_BYTES) throw IllegalArgumentException("Encrypted image exceeds the V1 limit.")
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            } finally { buffer.fill(0) }
        }
    }

    private suspend fun <T> networkResult(block: suspend () -> T): TransportResult<T> = try {
        withTimeout(timeoutMillis) { TransportResult.Success(block()) }
    } catch (_: TimeoutCancellationException) {
        TransportResult.Failure(SyncFailure.TimedOut)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: StorageReadException) {
        TransportResult.Failure(error.failure)
    } catch (error: HttpStatusException) {
        TransportResult.Failure(classifyStatus(error.status))
    } catch (_: IOException) {
        TransportResult.Failure(SyncFailure.Offline)
    } catch (_: IllegalArgumentException) {
        TransportResult.Failure(SyncFailure.InvalidRemoteData("Invalid encrypted image object."))
    }

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
                json(Json { ignoreUnknownKeys = true; explicitNulls = true })
            }
        }
    }
}

private class HttpStatusException(val status: HttpStatusCode) : Exception()
private class StorageReadException(val failure: SyncFailure) : Exception()

@Serializable
private data class ChannelParameters(@SerialName("p_channel_id") val channelId: String)
