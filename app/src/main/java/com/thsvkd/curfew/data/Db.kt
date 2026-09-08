package com.thsvkd.curfew.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Ignore
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import com.thsvkd.curfew.collect.DeviceState
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "usage_bucket")
data class UsageBucket(
    @PrimaryKey val bucketStartMs: Long,
    val usedSeconds: Int,
)

@Entity(tableName = "collector_state")
data class CollectorState(
    @PrimaryKey val id: Int = 0,
    val lastCursorMs: Long,
    val screenOn: Boolean,
    val unlocked: Boolean,
) {
    @Ignore
    constructor(id: Int, lastCursorMs: Long, state: DeviceState) :
        this(id, lastCursorMs, state.screenOn, state.unlocked)
}

@Entity(tableName = "coverage_gap")
data class CoverageGap(
    @PrimaryKey val startMs: Long,
    val endMs: Long,
)

@Dao
interface CurfewDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertBucketIfAbsent(bucket: UsageBucket): Long

    @Query(
        "UPDATE usage_bucket SET usedSeconds = MIN(600, usedSeconds + :seconds) " +
            "WHERE bucketStartMs = :bucketStartMs"
    )
    suspend fun bumpBucket(bucketStartMs: Long, seconds: Int)

    /** 같은 칸에 다시 담기면 더한다. 칸 하나가 10분을 넘길 수는 없으므로 600초에서 자른다. */
    @Transaction
    suspend fun addUsage(bucketStartMs: Long, seconds: Int) {
        val inserted = insertBucketIfAbsent(UsageBucket(bucketStartMs, seconds.coerceIn(0, 600)))
        if (inserted == -1L) bumpBucket(bucketStartMs, seconds)
    }

    @Query(
        "SELECT * FROM usage_bucket WHERE bucketStartMs >= :fromMs AND bucketStartMs < :toMs " +
            "ORDER BY bucketStartMs"
    )
    fun bucketsBetween(fromMs: Long, toMs: Long): Flow<List<UsageBucket>>

    @Query("SELECT * FROM usage_bucket ORDER BY bucketStartMs")
    suspend fun allBuckets(): List<UsageBucket>

    @Query("SELECT * FROM coverage_gap WHERE startMs < :toMs AND endMs > :fromMs ORDER BY startMs")
    fun gapsOverlapping(fromMs: Long, toMs: Long): Flow<List<CoverageGap>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addGap(gap: CoverageGap)

    @Query("SELECT * FROM collector_state WHERE id = 0")
    suspend fun state(): CollectorState?

    @Query("SELECT * FROM collector_state WHERE id = 0")
    fun stateFlow(): Flow<CollectorState?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveState(state: CollectorState)

    @Query("DELETE FROM usage_bucket")
    suspend fun clearBuckets()

    @Query("DELETE FROM coverage_gap")
    suspend fun clearGaps()

    /**
     * 기록을 지운 뒤에는 지금 이전을 통째로 공백으로 표시한다. 지웠다는 사실과 그때 실제로
     * 핸드폰을 안 썼다는 사실을 뒤섞지 않기 위해서다.
     */
    @Transaction
    suspend fun clearAll(nowMs: Long) {
        clearBuckets()
        clearGaps()
        addGap(CoverageGap(0L, nowMs))
        saveState(CollectorState(0, nowMs, screenOn = false, unlocked = false))
    }
}

@Database(
    entities = [UsageBucket::class, CollectorState::class, CoverageGap::class],
    version = 1,
    exportSchema = false,
)
abstract class CurfewDb : RoomDatabase() {

    abstract fun dao(): CurfewDao

    companion object {
        @Volatile
        private var instance: CurfewDb? = null

        fun get(context: Context): CurfewDb = instance ?: synchronized(this) {
            instance ?: Room
                .databaseBuilder(context.applicationContext, CurfewDb::class.java, "curfew.db")
                .build()
                .also { instance = it }
        }
    }
}
