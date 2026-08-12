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
    version = 2,
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

        fun create(context: Context): SecureMeshDatabase =
            Room.databaseBuilder<SecureMeshDatabase>(context, "securemesh_commander.db")
                .setDriver(BundledSQLiteDriver())
                .setQueryCoroutineContext(Dispatchers.IO)
                .addMigrations(MIGRATION_1_2)
                .build()
    }
}
