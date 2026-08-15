package com.shiyin.music.data.db

import android.content.Context
import androidx.room.ColumnInfo
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
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "saved_lyrics")
data class SavedLyricEntity(
    @PrimaryKey val mediaId: Long,
    val lyrics: String,
    val source: String,
    val offsetMs: Long,
    val savedAt: Long,
)

@Entity(tableName = "artist_alias")
data class ArtistAliasEntity(
    @PrimaryKey val fromName: String,
    val toName: String,
)

@Entity(tableName = "album_order")
data class AlbumOrderEntity(
    @PrimaryKey val albumKey: String,
    val orderedIds: String,
)

@Entity(tableName = "ignored_folder")
data class IgnoredFolderEntity(
    @PrimaryKey val path: String,
)

@Entity(tableName = "trashed_track")
data class TrashedTrackEntity(
    @PrimaryKey val mediaId: Long,
    val title: String,
    val artist: String,
    val folder: String,
    val sizeBytes: Long,
    val trashedAt: Long,
)

@Entity(tableName = "playlist")
data class PlaylistEntity(
    @PrimaryKey val id: String,
    val name: String,
    val sortIdx: Int,
    // v3.0: nullable. When present, this album's cover is used as the playlist
    // cover instead of the auto-generated 2x2 mosaic. Null = mosaic/auto.
    val coverAlbumId: Long? = null,
)

@Entity(tableName = "playlist_track", primaryKeys = ["playlistId", "mediaId"])
data class PlaylistTrackEntity(
    val playlistId: String,
    val mediaId: Long,
    val addedAt: Long,
)

// ── v2.0 artist schema ──────────────────────────────────────────────────

@Entity(tableName = "album_override")
data class AlbumOverrideEntity(
    @PrimaryKey val mediaId: Long,
    val albumName: String,
    val artistName: String,
)

// v4.3: album-level override — manual edits to an album's name/artist and an
// optional custom cover URI. Distinct from the track-level AlbumOverrideEntity
// (which re-parents a single NO_ALBUM track into an album). This one applies to
// the WHOLE album keyed by MediaStore albumId, so a user can fix scan errors
// the whole album shares (wrong artist, misspelled album name) and pin a true
// album cover so a standout single's embedded art stops overriding it.
// v9: `type` lets the user manually force the album's category (专辑/EP/单曲)
// to override the heuristic in AlbumClassifier — needed when only a few tracks
// of an album were downloaded (heuristic sees 2 tracks → 单曲, user knows it's
// really an 专辑). "" = keep auto-classification. Values: "Album"/"EP"/"Single".
@Entity(tableName = "album_info_override")
data class AlbumInfoOverrideEntity(
    @PrimaryKey val albumId: Long,
    val albumName: String = "",
    val artistName: String = "",
    val coverUri: String = "",
    val type: String = "",
    val updatedAt: Long = 0,
)

// v4.3: single-track manual edits — display-level title/artist/note overrides
// for songs whose scanned metadata is wrong or that the user wants renamed for
// their own organization. Applied per-mediaId in lib() at display time; the
// underlying file tags are never rewritten.
@Entity(tableName = "track_info_override")
data class TrackInfoOverrideEntity(
    @PrimaryKey val mediaId: Long,
    val title: String = "",
    val artist: String = "",
    val note: String = "",
    val hidden: Int = 0,
    val updatedAt: Long = 0,
)

// v4.3: track_album_move — 单曲迁移到正确专辑。Scan errors sometimes isolate a
// single from its real album (wrong albumId), leaving the album short a song.
// This override re-parents the track at display time: lib() swaps albumId so
// the track joins the target album's group/cover/key everywhere (album page,
// artist album rail, stats). Underlying file tags are never rewritten.
@Entity(tableName = "track_album_move")
data class TrackAlbumMoveEntity(
    @PrimaryKey val mediaId: Long,
    val albumId: Long,
    val updatedAt: Long = 0,
)

@Entity(
    tableName = "artist",
    indices = [Index(value = ["name"], unique = true)],
)
data class ArtistEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    @ColumnInfo(name = "name") val name: String,
    val aliases: String = "",
    val avatarUrl: String = "",
    val avatarSource: String = "",
    val updatedAt: Long = 0,
)

@Entity(tableName = "song_artist", primaryKeys = ["mediaId", "artistId"])
data class SongArtistEntity(
    val mediaId: Long,
    val artistId: Int,
)

@Entity(tableName = "play_count")
data class PlayCountEntity(
    @PrimaryKey val mediaId: Long,
    val count: Int = 0,
)

@Entity(tableName = "album_art_cache")
data class AlbumArtCacheEntity(
    @PrimaryKey val albumId: Long,
    val url: String = "",
    val source: String = "",
    val fetchedAt: Long = 0,
    // v3.0: extracted dominant colors (ARGB int) so the player/lyrics never
    // re-extract on every open. 0 means "not extracted yet".
    val bgArgb: Int = 0,
    val fgArgb: Int = 0,
    // v4: iTunes releaseDate ("1987-07-21T07:00:00Z") stored for sort-by-newest
    // on the artist's album list. Never promised to be the true original release
    // date — UI must NOT label it as the official release date.
    val releaseDate: String = "",
)

// ── v4: timestamped play events (for 最近播放 + 收听统计) ──────────────────
// play_count is only a running total with no timestamp; it can't answer
// "what did I play in the last 3 months" or "this week's top album/artist/track".
// play_event logs every counted play with its wall-clock time + track duration
// so the recent-plays feed and weekly aggregates share one source of truth.
// `completed` distinguishes fully-played songs (counted toward 收听统计) from
// skipped/partial plays (still surfaced by 最近播放, but excluded from stats).
@Entity(tableName = "play_event")
data class PlayEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val mediaId: Long,
    val playedAt: Long,    // epoch millis (System.currentTimeMillis)
    val durationSec: Int,  // track's total duration at play time, for weekly-sum aggregation
    val completed: Boolean = false,  // v5: true only when the track played to STATE_ENDED
)

// ── v4: "你的更新" — one-shot unread reminder of newly-scanned albums ───────
// On every scan, albums that are brand-new to the library go into this table.
// When the user opens an album's detail page (viewing it), its row is deleted,
// so "你的更新" only ever lists unseen new arrivals — exactly the unread-flag
// lifecycle the requirement describes. Decoupled from album/track tables so we
// don't dirty the core read paths with a viewed-state boolean.
@Entity(tableName = "new_album")
data class NewAlbumEntity(
    @PrimaryKey val albumId: Long,
    val firstSeenAt: Long,  // epoch millis, drives newest-first ordering
)

@Dao
interface ShiyinDao {
    // Lyrics
    @Query("SELECT * FROM saved_lyrics")
    fun lyricsFlow(): Flow<List<SavedLyricEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertLyric(e: SavedLyricEntity)

    @Query("DELETE FROM saved_lyrics WHERE mediaId = :mediaId")
    suspend fun deleteLyric(mediaId: Long)

    // Alias
    @Query("SELECT * FROM artist_alias")
    fun aliasFlow(): Flow<List<ArtistAliasEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAliases(list: List<ArtistAliasEntity>)

    @Query("DELETE FROM artist_alias WHERE fromName = :fromName")
    suspend fun deleteAlias(fromName: String)

    // Album order
    @Query("SELECT * FROM album_order")
    fun orderFlow(): Flow<List<AlbumOrderEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertOrder(e: AlbumOrderEntity)

    @Query("DELETE FROM album_order WHERE albumKey = :albumKey")
    suspend fun deleteOrder(albumKey: String)

    // Ignored folders
    @Query("SELECT * FROM ignored_folder")
    fun ignoredFlow(): Flow<List<IgnoredFolderEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addIgnored(e: IgnoredFolderEntity)

    @Query("DELETE FROM ignored_folder WHERE path = :path")
    suspend fun removeIgnored(path: String)

    // Trash mirror
    @Query("SELECT * FROM trashed_track ORDER BY trashedAt DESC")
    fun trashFlow(): Flow<List<TrashedTrackEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addTrashed(list: List<TrashedTrackEntity>)

    @Query("DELETE FROM trashed_track WHERE mediaId IN (:ids)")
    suspend fun removeTrashed(ids: List<Long>)

    @Query("DELETE FROM trashed_track")
    suspend fun clearTrashed()

    // Playlists
    @Query("SELECT * FROM playlist ORDER BY sortIdx")
    fun playlistsFlow(): Flow<List<PlaylistEntity>>

    @Query("SELECT * FROM playlist_track")
    fun playlistTracksFlow(): Flow<List<PlaylistTrackEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addToPlaylist(e: PlaylistTrackEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPlaylist(e: PlaylistEntity)

    @Query("DELETE FROM playlist_track WHERE playlistId = :playlistId AND mediaId = :mediaId")
    suspend fun removeFromPlaylist(playlistId: String, mediaId: Long)

    // ── v2.0 artist ──────────────────────────────────────────────────────
    @Query("SELECT * FROM artist")
    fun artistFlow(): Flow<List<ArtistEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertArtist(e: ArtistEntity)

    @Query("UPDATE artist SET avatarUrl = :url, avatarSource = :source, updatedAt = :now WHERE name = :name")
    suspend fun updateArtistAvatar(name: String, url: String, source: String, now: Long)

    @Query("SELECT * FROM artist WHERE name = :name")
    suspend fun artistByName(name: String): ArtistEntity?

    // song_artist
    @Query("SELECT * FROM song_artist WHERE mediaId = :mediaId")
    suspend fun artistsForTrack(mediaId: Long): List<SongArtistEntity>

    @Query("SELECT * FROM song_artist WHERE artistId = :artistId")
    fun tracksForArtist(artistId: Int): Flow<List<SongArtistEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSongArtist(e: SongArtistEntity)

    @Query("DELETE FROM song_artist WHERE mediaId = :mediaId")
    suspend fun clearSongArtist(mediaId: Long)

    // play_count
    @Query("SELECT * FROM play_count")
    fun playCountFlow(): Flow<List<PlayCountEntity>>

    @Query("SELECT count FROM play_count WHERE mediaId = :mediaId")
    suspend fun playCount(mediaId: Long): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPlayCount(e: PlayCountEntity)

    @Query("UPDATE play_count SET count = count + 1 WHERE mediaId = :mediaId")
    suspend fun incrementPlayCount(mediaId: Long)

    // album_override
    @Query("SELECT * FROM album_override")
    fun albumOverrideFlow(): Flow<List<AlbumOverrideEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAlbumOverride(e: AlbumOverrideEntity)

    @Query("DELETE FROM album_override WHERE mediaId = :mediaId")
    suspend fun deleteAlbumOverride(mediaId: Long)

    // v4.3: album_info_override — album-level manual edits (name/artist/cover).
    @Query("SELECT * FROM album_info_override")
    fun albumInfoOverrideFlow(): Flow<List<AlbumInfoOverrideEntity>>

    @Query("SELECT * FROM album_info_override WHERE albumId = :albumId")
    suspend fun albumInfoOverride(albumId: Long): AlbumInfoOverrideEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAlbumInfoOverride(e: AlbumInfoOverrideEntity)

    @Query("DELETE FROM album_info_override WHERE albumId = :albumId")
    suspend fun deleteAlbumInfoOverride(albumId: Long)

    // v4.3: track_info_override — single-track manual edits (title/artist/note).
    @Query("SELECT * FROM track_info_override")
    fun trackInfoOverrideFlow(): Flow<List<TrackInfoOverrideEntity>>

    @Query("SELECT * FROM track_info_override WHERE mediaId = :mediaId")
    suspend fun trackInfoOverride(mediaId: Long): TrackInfoOverrideEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTrackInfoOverride(e: TrackInfoOverrideEntity)

    // ── v4.3: track_album_move — 单曲迁移到正确专辑 ────────────────────────
    @Query("SELECT * FROM track_album_move")
    fun trackAlbumMoveFlow(): Flow<List<TrackAlbumMoveEntity>>

    @Query("SELECT * FROM track_album_move WHERE mediaId = :mediaId")
    suspend fun trackAlbumMove(mediaId: Long): TrackAlbumMoveEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTrackAlbumMove(e: TrackAlbumMoveEntity)

    @Query("DELETE FROM track_album_move WHERE mediaId = :mediaId")
    suspend fun deleteTrackAlbumMove(mediaId: Long)

    // ── v4.3: rematch support ──────────────────────────────────────────────
    // Drop persisted + in-memory art for an album so the next load re-fetches
    // with the edited album name/artist (iTunes match) or the pinned cover.
    @Query("DELETE FROM album_art_cache WHERE albumId = :albumId")
    suspend fun deleteArtCacheForAlbum(albumId: Long)

    // album_art_cache
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAlbumArtCache(e: AlbumArtCacheEntity)

    @Query("SELECT * FROM album_art_cache WHERE albumId = :albumId")
    suspend fun albumArtCache(albumId: Long): AlbumArtCacheEntity?

    // v4: store/refresh the iTunes releaseDate for an album without rewriting
    // url/source/colors. Used by the artist album list for sort-by-release-date.
    @Query("UPDATE album_art_cache SET releaseDate = :iso WHERE albumId = :albumId")
    suspend fun updateAlbumReleaseDate(albumId: Long, iso: String)

    // v3.0: bulk-load persisted dominant colors at startup so the player/lyrics
    // pages can tint from the album's mood without re-extracting on first paint.
    @Query("SELECT * FROM album_art_cache")
    suspend fun allArtCache(): List<AlbumArtCacheEntity>

    // v3.0: persist extracted dominant colors without rewriting url/source/fetchedAt.
    @Query("UPDATE album_art_cache SET bgArgb = :bg, fgArgb = :fg WHERE albumId = :albumId")
    suspend fun updateAlbumArtColors(albumId: Long, bg: Int, fg: Int)

    // ── v4: play events (最近播放 + 收听统计) ─────────────────────────────
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlayEvent(e: PlayEventEntity)

    /** Recent play events, newest first, capped so the 最近播放 feed never
     *  grows unbounded — the UI only shows the last 3 months anyway. */
    @Query("SELECT * FROM play_event ORDER BY playedAt DESC LIMIT :limit")
    suspend fun recentPlayEvents(limit: Int): List<PlayEventEntity>

    /** All play events within [from..to] (epoch millis, inclusive) for weekly aggregation. */
    @Query("SELECT * FROM play_event WHERE playedAt >= :from AND playedAt <= :to ORDER BY playedAt ASC")
    suspend fun playEventsBetween(from: Long, to: Long): List<PlayEventEntity>

    /** v5: only fully-completed plays within [from..to] — 收听统计 sums these so
     *  skipped/partial plays don't inflate weekly listening time or top-lists. */
    @Query("SELECT * FROM play_event WHERE playedAt >= :from AND playedAt <= :to AND completed = 1 ORDER BY playedAt ASC")
    suspend fun completedPlayEventsBetween(from: Long, to: Long): List<PlayEventEntity>

    /** v5: mark the most recent un-completed play of [mediaId] as completed.
     *  Called from onTrackCompleted when STATE_ENDED fires. Best-effort: if the
     *  event row was already trimmed, this is a no-op. */
    @Query("UPDATE play_event SET completed = 1 WHERE mediaId = :mediaId AND completed = 0 AND id = (SELECT id FROM play_event WHERE mediaId = :mediaId AND completed = 0 ORDER BY playedAt DESC LIMIT 1)")
    suspend fun markLatestPlayCompleted(mediaId: Long)

    /** Trim play events older than [cutoff] — the 最近播放 page only shows
     *  the last 3 months, so anything older is dead data and is physically
     *  deleted here (see requirement: cleanup is acceptable, no archive needed). */
    @Query("DELETE FROM play_event WHERE playedAt < :cutoff")
    suspend fun trimPlayEventsBefore(cutoff: Long)

    // ── v4: 你的更新 (new_album unread reminders) ──────────────────────────
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addNewAlbums(list: List<NewAlbumEntity>)

    /** All unseen new arrivals, newest first. Drives the 你的更新 page. */
    @Query("SELECT * FROM new_album ORDER BY firstSeenAt DESC")
    fun newAlbumsFlow(): Flow<List<NewAlbumEntity>>

    /** Mark an album as "viewed" — deletes its unread row. One-shot lifecycle. */
    @Query("DELETE FROM new_album WHERE albumId = :albumId")
    suspend fun markAlbumViewed(albumId: Long)

    @Query("SELECT albumId FROM new_album")
    suspend fun allNewAlbumIds(): List<Long>

    /** v5.2 Bug2: clear the "你的更新" residuals (one-shot wipe). Used by the
     *  in-page "清空历史" button to discard new_album rows that were seeded
     *  by earlier-version scans and didn't benefit from the v5.1/v5.2
     *  firstScanDone gate. */
    @Query("DELETE FROM new_album")
    suspend fun clearAllNewAlbums()

    // playlist cover (v3.0): set / clear the chosen album cover for a playlist.
    @Query("UPDATE playlist SET name = :name WHERE id = :id")
    suspend fun renamePlaylist(id: String, name: String)

    @Query("UPDATE playlist SET coverAlbumId = :albumId WHERE id = :id")
    suspend fun setPlaylistCover(id: String, albumId: Long?)

    @Query("DELETE FROM playlist_track WHERE playlistId = :id")
    suspend fun clearPlaylistTracks(id: String)

    @Query("DELETE FROM playlist WHERE id = :id")
    suspend fun deletePlaylist(id: String)
}

@Database(
    entities = [
        SavedLyricEntity::class, ArtistAliasEntity::class, AlbumOrderEntity::class,
        IgnoredFolderEntity::class, TrashedTrackEntity::class,
        PlaylistEntity::class, PlaylistTrackEntity::class,
        ArtistEntity::class, SongArtistEntity::class, PlayCountEntity::class,
        AlbumOverrideEntity::class, AlbumArtCacheEntity::class,
        // v4: timestamped play events + 你的更新 unread reminders
        PlayEventEntity::class, NewAlbumEntity::class,
        // v4.3: album-level manual edits (name/artist + custom cover)
        AlbumInfoOverrideEntity::class,
        // v4.3: single-track manual edits (title/artist/note)
        TrackInfoOverrideEntity::class,
        // v4.3: single-track re-parenting into its real album
        TrackAlbumMoveEntity::class,
    ],
    version = 10,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dao(): ShiyinDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS artist (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        name TEXT NOT NULL,
                        aliases TEXT NOT NULL,
                        avatarUrl TEXT NOT NULL,
                        avatarSource TEXT NOT NULL,
                        updatedAt INTEGER NOT NULL
                    )
                """)
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_artist_name ON artist(name)")
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS song_artist (
                        mediaId INTEGER NOT NULL,
                        artistId INTEGER NOT NULL,
                        PRIMARY KEY(mediaId, artistId)
                    )
                """)
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS play_count (
                        mediaId INTEGER NOT NULL,
                        count INTEGER NOT NULL,
                        PRIMARY KEY(mediaId)
                    )
                """)
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS album_override (
                        mediaId INTEGER NOT NULL,
                        albumName TEXT NOT NULL,
                        artistName TEXT NOT NULL,
                        PRIMARY KEY(mediaId)
                    )
                """)
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS album_art_cache (
                        albumId INTEGER NOT NULL,
                        url TEXT NOT NULL,
                        source TEXT NOT NULL,
                        fetchedAt INTEGER NOT NULL,
                        PRIMARY KEY(albumId)
                    )
                """)
            }
        }

        // v3.0: add dominant-color columns to album_art_cache (bgArgb/fgArgb)
        // and a nullable playlist cover column. All default to 0/NULL so old
        // rows read as "not yet extracted" / "auto mosaic".
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE album_art_cache ADD COLUMN bgArgb INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE album_art_cache ADD COLUMN fgArgb INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE playlist ADD COLUMN coverAlbumId INTEGER DEFAULT NULL")
            }
        }

        // v4: timestamped play events + 你的更新 (new_album) + releaseDate on album_art_cache.
        //   - play_event: the first time history ever has per-play timestamps;
        //     enables 最近播放 (last 3 months) and 收听统计 (weekly aggregates).
        //   - new_album: one-shot "newly scanned & not yet viewed" reminders.
        //   - album_art_cache.releaseDate: iTunes store-date for album sort
        //     (UI must NOT label it as the official release date).
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE album_art_cache ADD COLUMN releaseDate TEXT NOT NULL DEFAULT ''")
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS play_event (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        mediaId INTEGER NOT NULL,
                        playedAt INTEGER NOT NULL,
                        durationSec INTEGER NOT NULL
                    )
                """)
                db.execSQL("CREATE INDEX IF NOT EXISTS index_play_event_playedAt ON play_event(playedAt)")
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS new_album (
                        albumId INTEGER NOT NULL,
                        firstSeenAt INTEGER NOT NULL,
                        PRIMARY KEY(albumId)
                    )
                """)
            }
        }

        // v5: add `completed` column to play_event so 收听统计 can count only
        // fully-played songs while 最近播放 still surfaces partial/skipped plays.
        // Old rows default to completed = 0 (treated as incomplete); they remain
        // visible in 最近播放 but don't inflate past weekly stats. Acceptable
        // since v5 ships alongside the stats feature itself.
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE play_event ADD COLUMN completed INTEGER NOT NULL DEFAULT 0")
            }
        }

        // v6: album_info_override — album-level manual edits (name/artist/cover).
        // Decoupled from track-level album_override so re-parenting a single
        // track and fixing whole-album metadata are independent concerns.
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS album_info_override (
                        albumId INTEGER NOT NULL PRIMARY KEY,
                        albumName TEXT NOT NULL DEFAULT '',
                        artistName TEXT NOT NULL DEFAULT '',
                        coverUri TEXT NOT NULL DEFAULT '',
                        updatedAt INTEGER NOT NULL DEFAULT 0
                    )
                """)
            }
        }

        // v7: track_info_override — single-track display-level edits
        // (title/artist/note). Keyed by mediaId; empty columns mean "keep
        // the scanned value". Underlying file tags are never rewritten.
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS track_info_override (
                        mediaId INTEGER NOT NULL PRIMARY KEY,
                        title TEXT NOT NULL DEFAULT '',
                        artist TEXT NOT NULL DEFAULT '',
                        note TEXT NOT NULL DEFAULT '',
                        updatedAt INTEGER NOT NULL DEFAULT 0
                    )
                """)
            }
        }

        // v8: track_album_move — display-level re-parenting of a single into
        // its real album (fixes scan errors where one song got its own albumId).
        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS track_album_move (
                        mediaId INTEGER NOT NULL PRIMARY KEY,
                        albumId INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL DEFAULT 0
                    )
                """)
            }
        }

        // v9: album_info_override.type — manual override of an album's category
        // (专辑/EP/单曲). "" keeps the heuristic. Backfills as "" for existing
        // rows so they keep auto-classifying exactly as before.
        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE album_info_override ADD COLUMN type TEXT NOT NULL DEFAULT ''")
            }
        }

        // v5.2 隐藏曲:加 TrackInfoOverrideEntity.hidden INTEGER NOT NULL DEFAULT 0
        // 列。复用已有的 track_info_override 表(原本是 title/artist/note 的 per-track
        // 覆盖),hidden 跟它们同粒度——一TrackInfoOverride 行存一首曲的所有
        // 手工设置。DEFAULT 0 表示已存在的曲默认"未隐藏",行为不变。
        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE track_info_override ADD COLUMN hidden INTEGER NOT NULL DEFAULT 0")
            }
        }

        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "shiyin.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10)
                .fallbackToDestructiveMigration()
                .addCallback(object : Callback() {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL("INSERT INTO playlist (id, name, sortIdx) VALUES ('p3', '我最喜爱', 0)")
                    }
                })
                .build()
                .also { instance = it }
        }
    }
}