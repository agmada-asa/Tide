package com.agmada.tide.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Upsert
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "playlists")
data class PlaylistRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** "Album · Artist" or a playlist's owner, for imports. */
    val subtitle: String? = null,
    /** The Spotify link it came from, if any. */
    val source: String? = null,
    val coverUrl: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
)

/**
 * A playlist entry holds what the song is, not which file it was, so renaming, moving or
 * re-tagging a file doesn't drop it. [path] is only a hint for the first lookup.
 */
@Entity(tableName = "playlist_items", indices = [Index("playlistId")])
data class PlaylistItemRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val playlistId: Long,
    val position: Int,
    val title: String,
    val artist: String,
    val durationMs: Long,
    val path: String? = null,
)

@Entity(tableName = "song_edits")
data class SongEditRow(
    @PrimaryKey val path: String,
    val title: String?,
    val artist: String?,
    val album: String?,
    val albumArtist: String?,
    val track: Int? = null,
    val year: Int? = null,
) {
    fun toEdit() = SongEdit(title, artist, album, albumArtist, track, year)
}

@Dao
interface TideDao {
    @Query("SELECT * FROM playlists ORDER BY id = $LIKED DESC, createdAt DESC")
    fun playlists(): Flow<List<PlaylistRow>>

    @Query("SELECT * FROM playlist_items ORDER BY playlistId, position")
    fun items(): Flow<List<PlaylistItemRow>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertPlaylist(row: PlaylistRow): Long

    @Query("UPDATE playlists SET name = :name WHERE id = :id")
    suspend fun renamePlaylist(id: Long, name: String)

    @Query("DELETE FROM playlists WHERE id = :id")
    suspend fun deletePlaylistRow(id: Long)

    @Query("DELETE FROM playlist_items WHERE playlistId = :id")
    suspend fun clearItems(id: Long)

    @Insert
    suspend fun insertItems(rows: List<PlaylistItemRow>)

    @Query("DELETE FROM playlist_items WHERE id = :itemId")
    suspend fun deleteItem(itemId: Long)

    @Query("SELECT COALESCE(MAX(position), -1) + 1 FROM playlist_items WHERE playlistId = :id")
    suspend fun nextPosition(id: Long): Int

    @Query("SELECT * FROM playlist_items WHERE playlistId = :id ORDER BY position")
    suspend fun itemsOf(id: Long): List<PlaylistItemRow>

    @Transaction
    suspend fun deletePlaylist(id: Long) {
        clearItems(id)
        deletePlaylistRow(id)
    }

    @Transaction
    suspend fun replaceItems(id: Long, rows: List<PlaylistItemRow>) {
        clearItems(id)
        insertItems(rows.mapIndexed { i, row -> row.copy(id = 0, playlistId = id, position = i) })
    }

    @Query("SELECT * FROM song_edits")
    fun edits(): Flow<List<SongEditRow>>

    @Upsert
    suspend fun saveEdit(row: SongEditRow)

    @Query("DELETE FROM song_edits WHERE path = :path")
    suspend fun clearEdit(path: String)

    @Upsert
    suspend fun saveEdits(rows: List<SongEditRow>)

    @Query("DELETE FROM song_edits WHERE path IN (:paths)")
    suspend fun clearEdits(paths: List<String>)

    /** A file moved: its edit and any playlist hints follow it. */
    @Transaction
    suspend fun moved(from: String, to: String) {
        movePlaylistHints(from, to)
        moveEdit(from, to)
    }

    @Query("UPDATE playlist_items SET path = :to WHERE path = :from")
    suspend fun movePlaylistHints(from: String, to: String)

    @Query("UPDATE OR REPLACE song_edits SET path = :to WHERE path = :from")
    suspend fun moveEdit(from: String, to: String)

    companion object {
        /** Liked songs is playlist 1, made on first run. */
        const val LIKED = 1L
    }
}

@Database(entities = [PlaylistRow::class, PlaylistItemRow::class, SongEditRow::class], version = 2, exportSchema = false)
abstract class TideDb : RoomDatabase() {
    abstract fun dao(): TideDao

    companion object {
        @Volatile private var instance: TideDb? = null

        private val ADD_TRACK_AND_YEAR = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE song_edits ADD COLUMN track INTEGER")
                db.execSQL("ALTER TABLE song_edits ADD COLUMN year INTEGER")
            }
        }

        fun get(context: Context): TideDb = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, TideDb::class.java, "tide.db").addMigrations(ADD_TRACK_AND_YEAR).build().also { instance = it }
        }
    }
}
