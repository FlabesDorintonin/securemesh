package dev.securemesh.commander.core.database

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.PrimaryKey

@Entity(tableName = "events")
data class EventEntity(
    @PrimaryKey val id: String,
    val timestampEpochMs: Long,
    val category: String,
    val title: String,
    val details: String,
    val nodeId: String?,
)

@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey val id: String,
    val origin: String,
    val destination: String,
    val payload: String,
    val createdAtEpochMs: Long,
    val state: String,
    /** Legacy column now stores observed hop path only; it is never treated as an authoritative route. */
    val route: String,
    val hops: Int,
    val retries: Int,
    val deliveredAtEpochMs: Long?,
    val failureReason: String?,
)

@Entity(tableName = "known_nodes")
data class KnownNodeEntity(
    @PrimaryKey val id: String,
    val name: String,
    val role: String,
    val firmwareVersion: String,
    val protocolVersion: Int,
    val lastSeenEpochMs: Long,
)

@Entity(tableName = "field_tests")
data class FieldTestEntity(
    @PrimaryKey val id: String,
    val source: String,
    val target: String,
    val mode: String,
    val packetCount: Int,
    val intervalMs: Long,
    val payloadBytes: Int,
    val startedAtEpochMs: Long,
    val finishedAtEpochMs: Long?,
    val sent: Int,
    /** -1 means end-to-end result unavailable in this firmware profile. */
    val received: Int,
    /** -1 means end-to-end result unavailable in this firmware profile. */
    val lost: Int,
    val retries: Int,
    val route: String,
)

@Entity(tableName = "position_history")
data class PositionEntity(
    @PrimaryKey val key: String,
    val nodeId: String,
    val latitude: Double,
    val longitude: Double,
    val timestampEpochMs: Long,
    /** -1 means unknown. */
    val satellites: Int,
    val hdop: Double?,
    val speedMps: Double?,
    val valid: Boolean,
)

/**
 * Table/column names remain compatible with the v1 development database, but identity semantics changed:
 * the primary key is now SecureMesh nodeId, never BLE MAC. BLE address is intentionally not trusted identity.
 */
@Entity(tableName = "trusted_commanders")
data class TrustedDeviceEntity(
    @PrimaryKey @ColumnInfo(name = "address") val nodeId: String,
    val displayName: String?,
    val trustedAtEpochMs: Long,
    val protocolVersion: Int?,
)
