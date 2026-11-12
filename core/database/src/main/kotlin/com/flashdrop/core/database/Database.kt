package com.flashdrop.core.database

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.Flow
import javax.inject.Singleton

/**
 * Non-suspending methods are called from the transfer engine's I/O threads (checkpoints are
 * written from blocking storage code); suspending and Flow methods serve repositories and UI.
 */
@Dao
interface TransferDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertSession(session: TransferSessionEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertFiles(files: List<TransferFileEntity>)

    @Query(
        "UPDATE transfer_sessions SET status = :status, transferredBytes = :transferred, message = :message, " +
            "averageSpeed = :averageSpeed, updatedAt = :now WHERE sessionId = :sessionId",
    )
    fun updateSession(sessionId: String, status: String, transferred: Long, message: String?, averageSpeed: Long, now: Long)

    @Query("SELECT * FROM transfer_sessions WHERE sessionId = :sessionId")
    fun findSession(sessionId: String): TransferSessionEntity?

    @Query("SELECT * FROM transfer_files WHERE sessionId = :sessionId ORDER BY position")
    fun filesOf(sessionId: String): List<TransferFileEntity>

    @Query("SELECT * FROM transfer_checkpoints WHERE sessionId = :sessionId")
    fun checkpointsOf(sessionId: String): List<TransferCheckpointEntity>

    @Query(
        "SELECT COUNT(*) FROM transfer_sessions WHERE direction = 'RECEIVE' AND status = 'Interrupted' " +
            "AND remoteFingerprint = :fingerprint AND updatedAt >= :notBefore",
    )
    fun countResumableFrom(fingerprint: String, notBefore: Long): Int

    @Query("SELECT * FROM transfer_sessions WHERE status NOT IN ('Completed', 'Rejected', 'Failed', 'Cancelled')")
    fun unfinishedSessions(): List<TransferSessionEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertCheckpoint(checkpoint: TransferCheckpointEntity)

    @Query("DELETE FROM transfer_checkpoints WHERE sessionId = :sessionId AND fileId = :fileId")
    fun deleteCheckpoint(sessionId: String, fileId: String)

    @Query("UPDATE transfer_files SET completedBytes = :bytes, status = :status WHERE sessionId = :sessionId AND fileId = :fileId")
    fun updateFileProgress(sessionId: String, fileId: String, bytes: Long, status: String)

    @Query(
        "UPDATE transfer_files SET temporaryUri = :temporaryUri, status = :status, completedBytes = :bytes " +
            "WHERE sessionId = :sessionId AND fileId = :fileId",
    )
    fun startFile(sessionId: String, fileId: String, temporaryUri: String, status: String, bytes: Long)

    @Query(
        "UPDATE transfer_files SET status = :status, completedBytes = :bytes, sha256 = :sha256, " +
            "destinationUri = :destinationUri, temporaryUri = NULL WHERE sessionId = :sessionId AND fileId = :fileId",
    )
    fun finishFile(sessionId: String, fileId: String, status: String, bytes: Long, sha256: String?, destinationUri: String?)

    @Query("SELECT * FROM transfer_sessions ORDER BY createdAt DESC")
    fun observeSessions(): Flow<List<TransferSessionEntity>>

    @Query("SELECT * FROM transfer_sessions WHERE sessionId = :sessionId")
    fun observeSession(sessionId: String): Flow<TransferSessionEntity?>

    @Query("SELECT * FROM transfer_files WHERE sessionId = :sessionId ORDER BY position")
    fun observeFiles(sessionId: String): Flow<List<TransferFileEntity>>

    @Query("DELETE FROM transfer_sessions WHERE sessionId = :sessionId")
    suspend fun deleteSession(sessionId: String)

    @Query("DELETE FROM transfer_sessions WHERE status IN ('Completed', 'Rejected', 'Failed', 'Cancelled')")
    suspend fun deleteFinishedSessions()
}

@Dao
interface TrustedDeviceDao {
    @Query("SELECT * FROM trusted_devices ORDER BY displayName")
    fun observeAll(): Flow<List<TrustedDeviceEntity>>

    @Query("SELECT * FROM trusted_devices WHERE deviceId = :deviceId")
    suspend fun find(deviceId: String): TrustedDeviceEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(device: TrustedDeviceEntity)

    @Query("UPDATE trusted_devices SET lastSeenAt = :now, displayName = :name WHERE deviceId = :deviceId")
    suspend fun touch(deviceId: String, name: String, now: Long)

    @Query("DELETE FROM trusted_devices WHERE deviceId = :deviceId")
    suspend fun delete(deviceId: String)
}

@Database(
    entities = [
        TransferSessionEntity::class,
        TransferFileEntity::class,
        TransferCheckpointEntity::class,
        TrustedDeviceEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class FlashDropDatabase : RoomDatabase() {
    abstract fun transferDao(): TransferDao
    abstract fun trustedDeviceDao(): TrustedDeviceDao

    /** Creates a session and its file rows atomically. */
    fun createSession(session: TransferSessionEntity, files: List<TransferFileEntity>) = runInTransaction {
        transferDao().upsertSession(session)
        transferDao().upsertFiles(files)
    }
}

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): FlashDropDatabase =
        // Schema changes must ship with a Migration; there is deliberately no destructive fallback.
        Room.databaseBuilder(context, FlashDropDatabase::class.java, "flashdrop.db").build()

    @Provides
    fun provideTransferDao(db: FlashDropDatabase): TransferDao = db.transferDao()

    @Provides
    fun provideTrustedDeviceDao(db: FlashDropDatabase): TrustedDeviceDao = db.trustedDeviceDao()
}
