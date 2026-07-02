package com.nishantattrey.clipsync.core.protocol.validation

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.format.ResolverStyle
import java.time.temporal.ChronoField

object ServerTimestampCodec {
    private val parser = DateTimeFormatterBuilder()
        .append(DateTimeFormatter.ISO_LOCAL_DATE)
        .appendLiteral('T')
        .appendPattern("HH:mm:ss")
        .optionalStart()
        .appendFraction(ChronoField.NANO_OF_SECOND, 0, 6, true)
        .optionalEnd()
        .appendOffsetId()
        .toFormatter()
        .withResolverStyle(ResolverStyle.STRICT)

    private val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'")
        .withZone(ZoneOffset.UTC)

    fun parseMicroseconds(value: String): Long {
        val instant = Instant.from(parser.parse(value))
        return Math.addExact(
            Math.multiplyExact(instant.epochSecond, 1_000_000L),
            (instant.nano / 1_000).toLong(),
        )
    }

    fun formatMicroseconds(value: Long): String {
        val seconds = Math.floorDiv(value, 1_000_000)
        val micros = Math.floorMod(value, 1_000_000)
        return formatter.format(Instant.ofEpochSecond(seconds, micros * 1_000L))
    }
}
