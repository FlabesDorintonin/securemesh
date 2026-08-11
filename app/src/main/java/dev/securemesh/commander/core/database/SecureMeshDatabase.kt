package dev.securemesh.commander.core.database

import android.content.Context
import androidx.room3.Database
import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
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
    version = 1,
    exportSchema = true,
)
abstract class SecureMeshDatabase : RoomDatabase() {
    abstract fun dao(): SecureMeshDao

    companion object {
        fun create(context: Context): SecureMeshDatabase =
            Room.databaseBuilder<SecureMeshDatabase>(context, "securemesh_commander.db")
                .setDriver(BundledSQLiteDriver())
                .setQueryCoroutineContext(Dispatchers.IO)
                .build()
    }
}
