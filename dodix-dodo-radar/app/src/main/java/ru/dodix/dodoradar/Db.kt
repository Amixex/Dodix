package ru.dodix.dodoradar

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "sources")
data class SourceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val url: String,
    val type: String,               // rss | tg | html
    val selector: String = "",      // CSS-селектор ссылок для html
    val requireGeo: Boolean = true, // true: брать только публикации с упоминанием города
    val enabled: Boolean = true,
    val lastStatus: String = "не проверялся",
    val lastCount: Int = 0,
    val lastCheckedAt: Long = 0,
    val lastError: String = "",
)

@Entity(tableName = "news", indices = [Index("analyzed")])
data class NewsEntity(
    @PrimaryKey val url: String,
    val sourceName: String,
    val title: String,
    val text: String,
    val publishedAt: Long,
    val fetchedAt: Long,
    val kwScore: Int,
    val analyzed: Boolean = false,
)

@Entity(tableName = "digests")
data class DigestEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val createdAt: Long,
    val newCount: Int,
    val candidateCount: Int,
    val note: String = "",
)

@Entity(tableName = "picks", indices = [Index("digestId")])
data class PickEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val digestId: Long,
    val url: String,
    val title: String,
    val idea: String,
    val reason: String,
    val score: Int,
)

@Entity(tableName = "logs")
data class LogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ts: Long,
    val level: String,
    val msg: String,
)

@Dao
interface RadarDao {
    @Query("SELECT * FROM sources ORDER BY id") fun sources(): Flow<List<SourceEntity>>
    @Query("SELECT * FROM sources WHERE enabled = 1") suspend fun enabledSources(): List<SourceEntity>
    @Query("SELECT COUNT(*) FROM sources") suspend fun sourceCount(): Int
    @Insert suspend fun insertSource(s: SourceEntity): Long
    @Update suspend fun updateSource(s: SourceEntity)
    @Delete suspend fun deleteSource(s: SourceEntity)

    @Query("SELECT COUNT(*) FROM news WHERE url = :url") suspend fun newsExists(url: String): Int
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertNews(n: NewsEntity): Long
    @Query("SELECT * FROM news WHERE analyzed = 0 AND publishedAt >= :since ORDER BY kwScore DESC, publishedAt DESC LIMIT :limit")
    suspend fun pending(since: Long, limit: Int): List<NewsEntity>
    @Query("UPDATE news SET analyzed = 1 WHERE url IN (:urls)") suspend fun markAnalyzed(urls: List<String>)
    @Query("DELETE FROM news WHERE fetchedAt < :before") suspend fun purgeNews(before: Long)

    @Insert suspend fun insertDigest(d: DigestEntity): Long
    @Insert suspend fun insertPicks(p: List<PickEntity>)
    @Query("SELECT * FROM digests ORDER BY id DESC LIMIT 1") fun latestDigest(): Flow<DigestEntity?>
    @Query("SELECT * FROM picks WHERE digestId = :id ORDER BY score DESC") fun picks(id: Long): Flow<List<PickEntity>>

    @Insert suspend fun insertLog(l: LogEntity)
    @Query("SELECT * FROM logs ORDER BY id DESC LIMIT 150") fun logs(): Flow<List<LogEntity>>
    @Query("DELETE FROM logs WHERE ts < :before") suspend fun purgeLogs(before: Long)
}

@Database(
    entities = [SourceEntity::class, NewsEntity::class, DigestEntity::class, PickEntity::class, LogEntity::class],
    version = 1, exportSchema = false,
)
abstract class AppDb : RoomDatabase() {
    abstract fun dao(): RadarDao

    companion object {
        @Volatile private var inst: AppDb? = null
        fun get(ctx: Context): AppDb = inst ?: synchronized(this) {
            inst ?: Room.databaseBuilder(ctx.applicationContext, AppDb::class.java, "radar.db").build().also { inst = it }
        }
    }
}

object Log {
    fun addBlocking(ctx: Context, msg: String) {
        android.util.Log.i("DodoRadar", msg)
        kotlinx.coroutines.runBlocking { add(ctx, "warn", msg) }
    }

    suspend fun add(ctx: Context, level: String, msg: String) {
        android.util.Log.i("DodoRadar", "[$level] $msg")
        runCatching { AppDb.get(ctx).dao().insertLog(LogEntity(ts = System.currentTimeMillis(), level = level, msg = msg)) }
    }
}
