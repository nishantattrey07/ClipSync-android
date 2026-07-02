package com.nishantattrey.clipsync.core.protocol.validation

import java.util.UUID

object StoragePathValidator {
    private val pathPattern = Regex(
        "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}[.]enc",
    )

    fun make(itemId: UUID): String = "${itemId.toString().uppercase()}.enc"

    fun validate(path: String): UUID {
        require(pathPattern.matches(path)) { "Invalid encrypted image path." }
        return try {
            UUID.fromString(path.dropLast(4))
        } catch (error: IllegalArgumentException) {
            throw IllegalArgumentException("Invalid encrypted image path.", error)
        }
    }
}
