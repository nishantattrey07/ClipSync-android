package com.nishantattrey.clipsync.core.protocol.validation

import java.util.UUID

object UuidOrdering : Comparator<UUID> {
    override fun compare(left: UUID, right: UUID): Int {
        val most = java.lang.Long.compareUnsigned(left.mostSignificantBits, right.mostSignificantBits)
        return if (most != 0) most else {
            java.lang.Long.compareUnsigned(left.leastSignificantBits, right.leastSignificantBits)
        }
    }
}
