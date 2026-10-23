package com.projecthub.clearcut.core.storage

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

/**
 * Optional local persistence for projects, scan history and user-created
 * indexes. Never required for database-free analysis.
 */
@Entity(tableName = "projects")
data class ProjectEntity(
    @PrimaryKey val id: String,
    val name: String,
    val sourceUri: String,
    val sourceName: String,
    val durationUs: Long,
    val hasAudio: Boolean,
    val hasVideo: Boolean,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    val scanReportJson: String? = null,
    val edlJson: String? = null,
    val exportHistoryJson: String? = null,
    val status: String = "READY",
)

@Entity(tableName = "scans")
data class ScanHistoryEntity(
    @PrimaryKey val id: String,
    val projectId: String,
    val mediaName: String,
    val scannedAtEpochMs: Long,
    val durationUs: Long,
    val findingCount: Int,
    val coverageFraction: Float,
    val reportJson: String,
)

@Dao
interface ProjectDao {
    @Query("SELECT * FROM projects ORDER BY updatedAtEpochMs DESC")
    fun observeAll(): Flow<List<ProjectEntity>>

    @Query("SELECT * FROM projects WHERE id = :id")
    suspend fun getById(id: String): ProjectEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(project: ProjectEntity)

    @Query("DELETE FROM projects WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM projects")
    suspend fun deleteAll()
}

@Dao
interface ScanHistoryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(scan: ScanHistoryEntity)

    @Query("SELECT * FROM scans ORDER BY scannedAtEpochMs DESC")
    fun observeAll(): Flow<List<ScanHistoryEntity>>

    @Query("DELETE FROM scans")
    suspend fun clearAll()
}

@Database(
    entities = [ProjectEntity::class, ScanHistoryEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class ClearCutDatabase : RoomDatabase() {
    abstract fun projectDao(): ProjectDao
    abstract fun scanHistoryDao(): ScanHistoryDao

    companion object {
        @Volatile
        private var instance: ClearCutDatabase? = null

        fun get(context: Context): ClearCutDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    ClearCutDatabase::class.java,
                    "clearcut.db",
                ).fallbackToDestructiveMigration().build().also { instance = it }
            }
    }
}