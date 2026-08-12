package dev.securemesh.commander.core.database

import androidx.room3.*
import kotlinx.coroutines.flow.Flow

@Dao
interface SecureMeshDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertEvents(items: List<EventEntity>)
    @Query("SELECT * FROM events ORDER BY timestampEpochMs DESC LIMIT :limit")
    fun observeEvents(limit: Int = 1000): Flow<List<EventEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMessages(items: List<MessageEntity>)
    @Query("SELECT * FROM messages ORDER BY createdAtEpochMs DESC LIMIT :limit")
    fun observeMessages(limit: Int = 1000): Flow<List<MessageEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertKnownNodes(items: List<KnownNodeEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertFieldTest(item: FieldTestEntity)
    @Query("SELECT * FROM field_tests ORDER BY startedAtEpochMs DESC LIMIT :limit")
    fun observeFieldTests(limit: Int = 100): Flow<List<FieldTestEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPositions(items: List<PositionEntity>)
    @Query("SELECT * FROM position_history WHERE (:nodeId IS NULL OR nodeId = :nodeId) ORDER BY timestampEpochMs DESC LIMIT :limit")
    fun observePositions(nodeId: String?, limit: Int = 2000): Flow<List<PositionEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTrustedDevice(item: TrustedDeviceEntity)
    @Query("SELECT * FROM trusted_devices ORDER BY trustedAtEpochMs DESC LIMIT 1")
    suspend fun latestTrustedDevice(): TrustedDeviceEntity?
    @Query("SELECT * FROM trusted_devices WHERE nodeId = :nodeId LIMIT 1")
    suspend fun trustedDevice(nodeId: String): TrustedDeviceEntity?
    @Query("DELETE FROM trusted_devices")
    suspend fun clearTrustedDevices()

    @Query("DELETE FROM events WHERE timestampEpochMs < :cutoff") suspend fun deleteEventsBefore(cutoff: Long)
    @Query("DELETE FROM position_history WHERE timestampEpochMs < :cutoff") suspend fun deletePositionsBefore(cutoff: Long)
    @Query("DELETE FROM events") suspend fun clearEvents()
    @Query("DELETE FROM messages") suspend fun clearMessages()
    @Query("DELETE FROM known_nodes") suspend fun clearKnownNodes()
    @Query("DELETE FROM field_tests") suspend fun clearFieldTests()
    @Query("DELETE FROM position_history") suspend fun clearPositions()
}
