package com.nishantattrey.clipsync.core.sync.image

import android.content.Context
import com.nishantattrey.clipsync.core.protocol.ProtocolV1
import com.nishantattrey.clipsync.core.protocol.validation.ProtocolValueValidator
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

interface PreparedImageStore {
    suspend fun write(itemId: String, exactEncryptedBytes: ByteArray, variant: String? = null): String
    suspend fun read(fileName: String): ByteArray
    suspend fun delete(fileName: String): Boolean
}

class AndroidPreparedImageStore(
    context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : PreparedImageStore {
    private val root = File(context.noBackupFilesDir, "prepared-images-v1").apply { mkdirs() }

    override suspend fun write(
        itemId: String,
        exactEncryptedBytes: ByteArray,
        variant: String?,
    ): String = withContext(ioDispatcher) {
        ProtocolValueValidator.requireUuid(itemId, "item")
        require(variant == null || variant == "upload") { "Invalid prepared image variant." }
        require(exactEncryptedBytes.size <= ProtocolV1.MAX_STORAGE_OBJECT_BYTES) { "Encrypted image exceeds the V1 limit." }
        val name = buildString {
            append(itemId.lowercase())
            if (variant != null) append('.').append(variant)
            append(".prepared")
        }
        val target = safeFile(name)
        val temporary = safeFile("$name.tmp")
        FileOutputStream(temporary).use { stream ->
            stream.write(exactEncryptedBytes)
            stream.fd.sync()
        }
        check(temporary.renameTo(target)) { "Prepared image could not be persisted atomically." }
        name
    }

    override suspend fun read(fileName: String): ByteArray = withContext(ioDispatcher) {
        val file = safeFile(fileName)
        file.inputStream().use { BoundedImageReader.read(it, ProtocolV1.MAX_STORAGE_OBJECT_BYTES) }
    }

    override suspend fun delete(fileName: String): Boolean = withContext(ioDispatcher) { safeFile(fileName).delete() }

    private fun safeFile(name: String): File {
        require(NAME.matches(name)) { "Invalid prepared image file name." }
        val file = File(root, name)
        require(file.parentFile?.canonicalFile == root.canonicalFile && !file.isDirectory) { "Invalid prepared image path." }
        return file
    }

    private companion object {
        val NAME = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}(?:[.]upload)?[.]prepared(?:[.]tmp)?")
    }
}
