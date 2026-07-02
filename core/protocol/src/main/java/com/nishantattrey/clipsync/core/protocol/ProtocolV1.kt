package com.nishantattrey.clipsync.core.protocol

object ProtocolV1 {
    const val KEY_DERIVATION_VERSION = 1
    const val PAYLOAD_VERSION = 1
    const val PROFILE_VERSION = 1
    const val PROFILE_PROTOCOL_VERSION = 1

    const val ARGON2_VERSION = 19
    const val ARGON2_ITERATIONS = 3
    const val ARGON2_MEMORY_KIB = 65_536
    const val ARGON2_PARALLELISM = 1
    const val DERIVED_KEY_BYTES = 32
    const val ARGON2_SALT_BYTES = 16

    const val HKDF_SALT = "clipsync-v1"
    const val CHANNEL_INFO = "channel-id"
    const val ENCRYPTION_INFO = "encryption"
    const val HMAC_INFO = "content-hmac"

    const val AES_GCM_NONCE_BYTES = 12
    const val AES_GCM_TAG_BYTES = 16
    const val GENERATED_SECRET_BYTES = 32
    const val GENERATED_SECRET_CHARACTERS = 43

    const val MAX_DEVICE_NAME_CODE_POINTS = 80
    const val MAX_PROFILE_CIPHERTEXT_BYTES = 65_536
    const val MAX_TEXT_PLAINTEXT_BYTES = 5_000_000
    const val MAX_TEXT_CIPHERTEXT_BYTES = 6_700_000
    const val MAX_IMAGE_PLAINTEXT_BYTES = 50_000_000
    const val MAX_IMAGE_DIMENSION = 16_384
    const val MAX_DECODED_PIXELS = 40_000_000
    const val MAX_THUMBNAIL_DIMENSION = 256
    const val MAX_THUMBNAIL_CIPHERTEXT_BYTES = 2_000_000
    const val MAX_STORAGE_OBJECT_BYTES = 51_000_000
    const val PAGE_SIZE = 50
    const val SERVER_MAX_PAGE_SIZE = 100

    const val IMAGE_BUCKET = "clipboard-images"
    const val ENCRYPTED_OBJECT_CONTENT_TYPE = "application/octet-stream"
}
