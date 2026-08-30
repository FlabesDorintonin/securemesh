package dev.securemesh.commander.core.database

import android.content.Context
import androidx.room3.Database
import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import kotlinx.coroutines.Dispatchers

@Database(
    entities = [
        EventEntity::class,
        MessageEntity::class,
        KnownNodeEntity::class,
        FieldTestEntity::class,
        PositionEntity::class,
        TrustedDeviceEntity::class,
        ContactProfileEntity::class,
    ],
    version = 5,
    exportSchema = true,
)
abstract class SecureMeshDatabase : RoomDatabase() {
    abstract fun dao(): SecureMeshDao

    companion object {
        /**
         * v1 physically stored trust in `trusted_commanders(address PRIMARY KEY, ...)`.
         * Some installations can contain BLE MACs in that column; those rows must never become SecureMesh identity.
         * We preserve only clearly node-shaped 8-hex-character IDs, migrate all unrelated tables untouched,
         * and deliberately discard MAC-shaped legacy trust rather than reinterpret it.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override suspend fun migrate(connection: SQLiteConnection) {
                connection.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `trusted_devices` (
                        `nodeId` TEXT NOT NULL,
                        `displayName` TEXT,
                        `lastSeenBleAddress` TEXT,
                        `trustedAtEpochMs` INTEGER NOT NULL,
                        `firmwareVersion` TEXT,
                        `protocolVersion` INTEGER,
                        PRIMARY KEY(`nodeId`)
                    )
                    """.trimIndent()
                )
                connection.execSQL(
                    """
                    INSERT OR REPLACE INTO `trusted_devices`
                        (`nodeId`, `displayName`, `lastSeenBleAddress`, `trustedAtEpochMs`, `firmwareVersion`, `protocolVersion`)
                    SELECT `address`, `displayName`, NULL, `trustedAtEpochMs`, NULL, `protocolVersion`
                    FROM `trusted_commanders`
                    WHERE length(`address`) = 8 AND `address` NOT LIKE '%:%'
                    """.trimIndent()
                )
                connection.execSQL("DROP TABLE `trusted_commanders`")
            }
        }

        /** Message ids are firmware-local u32 values, so origin + id is the durable unique identity. */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override suspend fun migrate(connection: SQLiteConnection) {
                connection.execSQL(
                    """
                    CREATE TABLE `messages_new` (
                        `key` TEXT NOT NULL,
                        `id` TEXT NOT NULL,
                        `origin` TEXT NOT NULL,
                        `destination` TEXT NOT NULL,
                        `payload` TEXT NOT NULL,
                        `createdAtEpochMs` INTEGER NOT NULL,
                        `state` TEXT NOT NULL,
                        `route` TEXT NOT NULL,
                        `hops` INTEGER NOT NULL,
                        `retries` INTEGER NOT NULL,
                        `deliveredAtEpochMs` INTEGER,
                        `failureReason` TEXT,
                        PRIMARY KEY(`key`)
                    )
                    """.trimIndent()
                )
                connection.execSQL(
                    """
                    INSERT OR REPLACE INTO `messages_new`
                        (`key`,`id`,`origin`,`destination`,`payload`,`createdAtEpochMs`,`state`,`route`,`hops`,`retries`,`deliveredAtEpochMs`,`failureReason`)
                    SELECT `origin` || ':' || `id`, `id`, `origin`, `destination`, `payload`, `createdAtEpochMs`, `state`, `route`, `hops`, `retries`, `deliveredAtEpochMs`, `failureReason`
                    FROM `messages`
                    """.trimIndent()
                )
                connection.execSQL("DROP TABLE `messages`")
                connection.execSQL("ALTER TABLE `messages_new` RENAME TO `messages`")
            }
        }

        /** Contact aliases/notes are stored only as authenticated ciphertext. */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override suspend fun migrate(connection: SQLiteConnection) {
                connection.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `contact_profiles` (
                        `nodeId` TEXT NOT NULL,
                        `encryptedAlias` TEXT,
                        `encryptedNote` TEXT,
                        `notePinned` INTEGER NOT NULL,
                        `updatedAtEpochMs` INTEGER NOT NULL,
                        PRIMARY KEY(`nodeId`)
                    )
                    """.trimIndent()
                )
            }
        }

        /**
         * v0.9.0 stored GPS history as plaintext doubles. For the hardened vault we deliberately
         * discard that cache once and recreate the table with only encrypted position payloads.
         * Live mesh positions are unaffected; future history is encrypted before Room sees it.
         */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override suspend fun migrate(connection: SQLiteConnection) {
                connection.execSQL("DROP TABLE IF EXISTS `position_history`")
                connection.execSQL(
                    """
                    CREATE TABLE `position_history` (
                        `key` TEXT NOT NULL,
                        `nodeId` TEXT NOT NULL,
                        `timestampEpochMs` INTEGER NOT NULL,
                        `encryptedPayload` TEXT NOT NULL,
                        PRIMARY KEY(`key`)
                    )
                    """.trimIndent()
                )
            }
        }

        fun create(context: Context): SecureMeshDatabase =
            Room.databaseBuilder<SecureMeshDatabase>(context, "securemesh_commander.db")
                .setDriver(BundledSQLiteDriver())
                .setQueryCoroutineContext(Dispatchers.IO)
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                .addCallback(object : RoomDatabase.Callback() {
                    override suspend fun onOpen(connection: SQLiteConnection) {
                        // Reduce recoverability of deleted/overwritten sensitive cells inside SQLite pages.
                        connection.execSQL("PRAGMA secure_delete=ON")
                    }
                })
                .build()
    }
}
