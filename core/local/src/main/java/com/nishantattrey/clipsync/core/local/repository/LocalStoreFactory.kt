package com.nishantattrey.clipsync.core.local.repository

import android.content.Context
import androidx.room.Room
import com.nishantattrey.clipsync.core.local.crypto.AesGcmLocalPayloadCipher
import com.nishantattrey.clipsync.core.local.crypto.AndroidKeystoreLocalKeyMaterial
import com.nishantattrey.clipsync.core.local.crypto.HmacSha256LocalFingerprint
import com.nishantattrey.clipsync.core.local.persistence.ClipSyncDatabase

class LocalStore internal constructor(
    val repository: LocalClipboardRepository,
    val recovery: LocalRecoveryCoordinator,
)

fun createLocalStore(context: Context): LocalStore {
    val database = Room.databaseBuilder(context, ClipSyncDatabase::class.java, "clipsync-local-v1.db").build()
    val keys = AndroidKeystoreLocalKeyMaterial()
    val cipher = AesGcmLocalPayloadCipher(keys)
    val fingerprint = HmacSha256LocalFingerprint(keys)
    return LocalStore(
        repository = RoomLocalClipboardRepository(
            database.localClipboardDao(),
            cipher,
            fingerprint,
        ),
        recovery = LocalRecoveryCoordinator(database.localClipboardDao(), keys, cipher, fingerprint),
    )
}
