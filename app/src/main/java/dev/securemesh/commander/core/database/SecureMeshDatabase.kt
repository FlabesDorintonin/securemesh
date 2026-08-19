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
    ],
    version = 3,
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

        fun create(context: Context): SecureMeshDatabase =
            Room.databaseBuilder<SecureMeshDatabase>(context, "securemesh_commander.db")
                .setDriver(BundledSQLiteDriver())
                .setQueryCoroutineContext(Dispatchers.IO)
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .build()
    }
}
