package com.example.musictage.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SongDao {
    @Query("SELECT * FROM songs WHERE isBlacklisted = 0 ORDER BY title ASC")
    fun getAllSongs(): Flow<List<SongEntity>>

    /**
     * 一次性取回与 [getAllSongs] 完全同序的歌曲列表。
     *
     * 播放列表需要的是一个立刻返回的快照（要整批塞给播放器），而不是持续观察的 Flow，
     * 所以单独开一个挂起版本。排序必须和列表界面一致，否则点击第 N 首会播成别的歌 喵~
     */
    @Query("SELECT * FROM songs WHERE isBlacklisted = 0 ORDER BY title ASC")
    suspend fun getAllSongsOnce(): List<SongEntity>

    @Query("SELECT * FROM songs WHERE isBlacklisted = 0 ORDER BY scanTimestamp DESC")
    suspend fun getSongsForNotification(): List<SongEntity>

    @Query("SELECT * FROM songs WHERE id = :songId LIMIT 1")
    suspend fun getSongById(songId: Long): SongEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSongs(songs: List<SongEntity>)

    @Query("UPDATE songs SET isBlacklisted = 1 WHERE id = :songId")
    suspend fun blacklistSong(songId: Long)

    @Query("UPDATE songs SET title = :title, artist = :artist WHERE id = :songId")
    suspend fun updateSongMetadata(songId: Long, title: String, artist: String)

    @Query("UPDATE songs SET lrcPath = :lrcPath WHERE id = :songId")
    suspend fun updateLrcPath(songId: Long, lrcPath: String?)

    /**
     * 写入单首歌的歌词偏移毫秒数（正数推迟、负数提前、0 不偏移）喵~
     */
    @Query("UPDATE songs SET lyricOffsetMs = :offsetMs WHERE id = :songId")
    suspend fun updateLyricOffset(songId: Long, offsetMs: Long)

    @Query("SELECT id FROM songs")
    suspend fun getAllIds(): List<Long>

    @Query("DELETE FROM songs WHERE id = :songId")
    suspend fun deleteSong(songId: Long)

    @Query("UPDATE songs SET coverPath = :path WHERE id = :songId")
    suspend fun updateCoverPath(songId: Long, path: String?)

    @Query("DELETE FROM songs")
    suspend fun clearAll()
}
