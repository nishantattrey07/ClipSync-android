package com.nishantattrey.clipsync.core.sync.engine

import com.nishantattrey.clipsync.core.sync.model.SyncFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncFailureMappingTest {
    @Test
    fun `temporary transport failures remain retryable`() {
        listOf(
            SyncFailure.Offline,
            SyncFailure.TimedOut,
            SyncFailure.TemporarilyUnavailable,
        ).forEach { failure ->
            val result = failure.toSynchronizeResult()
            assertTrue(result is SynchronizeResult.Retrying)
            assertSame(failure, (result as SynchronizeResult.Retrying).failure)
        }
    }

    @Test
    fun `unauthorized response becomes safe actionable error`() {
        val result = SyncFailure.Unauthorized.toSynchronizeResult()

        assertEquals(
            SynchronizeResult.ActionableError("Supabase rejected the public API credentials."),
            result,
        )
    }

    @Test
    fun `backend and protocol failures preserve only their safe reason`() {
        val rejected = SyncFailure.Rejected("Supabase rejected the request (404).")
        val invalid = SyncFailure.InvalidRemoteData("Malformed Supabase response.")

        assertEquals(SynchronizeResult.ActionableError(rejected.safeReason), rejected.toSynchronizeResult())
        assertEquals(SynchronizeResult.ActionableError(invalid.safeReason), invalid.toSynchronizeResult())
    }
}
