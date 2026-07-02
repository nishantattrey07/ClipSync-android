package com.nishantattrey.clipsync.core.protocol.encoding

import java.util.Base64

object WireEncoding {
    private val lowercaseHex = Regex("[0-9a-f]+")
    private const val STANDARD_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    fun standardBase64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    fun decodeStandardBase64(value: String): ByteArray {
        validateCanonicalStandardBase64(value)
        return try {
            Base64.getDecoder().decode(value)
        } catch (error: IllegalArgumentException) {
            throw ProtocolEncodingException("Malformed standard Base64.", error)
        }
    }

    fun validateCanonicalStandardBase64(value: String) {
        if (value.length % 4 != 0) malformedBase64()
        val padding = when {
            value.endsWith("==") -> 2
            value.endsWith('=') -> 1
            else -> 0
        }
        if (padding > 2) malformedBase64()
        val contentLength = value.length - padding
        for (index in 0 until contentLength) {
            if (value[index] !in STANDARD_ALPHABET) malformedBase64()
        }
        for (index in contentLength until value.length) {
            if (value[index] != '=') malformedBase64()
        }
        if (padding > 0 && contentLength == 0) malformedBase64()

        val finalSextet = if (contentLength == 0) 0 else STANDARD_ALPHABET.indexOf(value[contentLength - 1])
        if ((padding == 1 && finalSextet and 0b11 != 0) ||
            (padding == 2 && finalSextet and 0b1111 != 0)
        ) {
            malformedBase64()
        }
    }

    fun standardBase64DecodedByteCount(value: String): Int {
        validateCanonicalStandardBase64(value)
        val padding = when {
            value.endsWith("==") -> 2
            value.endsWith('=') -> 1
            else -> 0
        }
        return Math.subtractExact(Math.multiplyExact(value.length / 4, 3), padding)
    }

    fun base64UrlWithoutPadding(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    fun decodeBase64UrlWithoutPadding(value: String): ByteArray {
        if ('=' in value) throw ProtocolEncodingException("Base64URL padding is not permitted.")
        val decoded = try {
            Base64.getUrlDecoder().decode(value)
        } catch (error: IllegalArgumentException) {
            throw ProtocolEncodingException("Malformed unpadded Base64URL.", error)
        }
        if (base64UrlWithoutPadding(decoded) != value) {
            throw ProtocolEncodingException("Malformed unpadded Base64URL.")
        }
        return decoded
    }

    fun lowercaseHex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    fun decodeLowercaseHex(value: String): ByteArray {
        if (value.length % 2 != 0 || !lowercaseHex.matches(value)) {
            throw ProtocolEncodingException("Malformed lowercase hexadecimal value.")
        }
        return ByteArray(value.length / 2) { index ->
            value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }

    private fun malformedBase64(): Nothing =
        throw ProtocolEncodingException("Malformed standard Base64.")
}

class ProtocolEncodingException(message: String, cause: Throwable? = null) :
    IllegalArgumentException(message, cause)
