package com.nishantattrey.clipsync.core.local.persistence

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("DROP INDEX IF EXISTS index_local_clipboard_items_fingerprint")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_local_clipboard_items_fingerprint ON local_clipboard_items(fingerprint)")
        db.execSQL("ALTER TABLE local_clipboard_items ADD COLUMN cloud_sync_state TEXT NOT NULL DEFAULT 'local'")
        db.execSQL("CREATE TABLE IF NOT EXISTS outbound_text_queue (item_id TEXT NOT NULL, channel_id TEXT NOT NULL, device_id TEXT NOT NULL, ciphertext TEXT NOT NULL, payload_version INTEGER NOT NULL, created_at_epoch_millis INTEGER NOT NULL, state TEXT NOT NULL, attempt_count INTEGER NOT NULL, next_attempt_at_epoch_millis INTEGER NOT NULL, last_failure TEXT, PRIMARY KEY(item_id))")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_outbound_text_queue_state_next_attempt_at_epoch_millis_created_at_epoch_millis_item_id ON outbound_text_queue(state, next_attempt_at_epoch_millis, created_at_epoch_millis, item_id)")
        db.execSQL("CREATE TABLE IF NOT EXISTS inbound_text_journal (item_id TEXT NOT NULL, channel_id TEXT NOT NULL, device_id TEXT NOT NULL, kind TEXT NOT NULL, payload_version INTEGER NOT NULL, ciphertext TEXT NOT NULL, created_at TEXT NOT NULL, created_at_microseconds INTEGER NOT NULL, state TEXT NOT NULL, failure_class TEXT, PRIMARY KEY(item_id))")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_inbound_text_journal_channel_id_created_at_microseconds_item_id ON inbound_text_journal(channel_id, created_at_microseconds, item_id)")
        db.execSQL("CREATE TABLE IF NOT EXISTS sync_cursors (channel_id TEXT NOT NULL, created_at_microseconds INTEGER NOT NULL, item_id TEXT NOT NULL, PRIMARY KEY(channel_id))")
        db.execSQL("CREATE TABLE IF NOT EXISTS device_directory (channel_id TEXT NOT NULL, device_id TEXT NOT NULL, profile_ciphertext TEXT NOT NULL, profile_version INTEGER NOT NULL, created_at TEXT NOT NULL, updated_at TEXT NOT NULL, PRIMARY KEY(channel_id, device_id))")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_device_directory_channel_id_updated_at ON device_directory(channel_id, updated_at)")
    }
}

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE inbound_text_journal RENAME TO inbound_journal_v2")
        db.execSQL("CREATE TABLE IF NOT EXISTS inbound_text_journal (item_id TEXT NOT NULL, channel_id TEXT NOT NULL, device_id TEXT NOT NULL, kind TEXT NOT NULL, payload_version INTEGER NOT NULL, ciphertext TEXT, image_path TEXT, thumbnail_ciphertext TEXT, mime_type TEXT, created_at TEXT NOT NULL, created_at_microseconds INTEGER NOT NULL, state TEXT NOT NULL, failure_class TEXT, PRIMARY KEY(item_id))")
        db.execSQL("INSERT INTO inbound_text_journal (item_id, channel_id, device_id, kind, payload_version, ciphertext, created_at, created_at_microseconds, state, failure_class) SELECT item_id, channel_id, device_id, kind, payload_version, ciphertext, created_at, created_at_microseconds, state, failure_class FROM inbound_journal_v2")
        db.execSQL("DROP TABLE inbound_journal_v2")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_inbound_text_journal_channel_id_created_at_microseconds_item_id ON inbound_text_journal(channel_id, created_at_microseconds, item_id)")
        db.execSQL("CREATE TABLE IF NOT EXISTS local_images (item_id TEXT NOT NULL, encrypted_file_name TEXT NOT NULL, mime_type TEXT NOT NULL, width INTEGER NOT NULL, height INTEGER NOT NULL, created_at_epoch_millis INTEGER NOT NULL, capture_source TEXT NOT NULL, sender_device_id TEXT, cloud_sync_state TEXT NOT NULL, is_bookmarked INTEGER NOT NULL DEFAULT 0, fingerprint BLOB NOT NULL, display_name TEXT, PRIMARY KEY(item_id))")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_local_images_created_at_epoch_millis_item_id ON local_images(created_at_epoch_millis, item_id)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_local_images_fingerprint ON local_images(fingerprint)")
        db.execSQL("CREATE TABLE IF NOT EXISTS outbound_image_queue (item_id TEXT NOT NULL, channel_id TEXT NOT NULL, device_id TEXT NOT NULL, prepared_file_name TEXT NOT NULL, image_path TEXT NOT NULL, thumbnail_ciphertext TEXT, mime_type TEXT NOT NULL, width INTEGER NOT NULL, height INTEGER NOT NULL, encrypted_byte_count INTEGER NOT NULL, created_at_epoch_millis INTEGER NOT NULL, state TEXT NOT NULL, attempt_count INTEGER NOT NULL, next_attempt_at_epoch_millis INTEGER NOT NULL, last_failure TEXT, PRIMARY KEY(item_id))")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_outbound_image_queue_state_next_attempt_at_epoch_millis_created_at_epoch_millis_item_id ON outbound_image_queue(state, next_attempt_at_epoch_millis, created_at_epoch_millis, item_id)")
    }
}
