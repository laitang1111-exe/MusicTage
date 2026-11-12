package com.example.musictage.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "songs")
data class SongEntity(
    @PrimaryKey val id: Long,
    val title: String,
    val artist: String,
    val duration: Long,
    val filePath: String,
    val lrcPath: String? = null,
    val isBlacklisted: Boolean = false,
    val scanTimestamp: Long = System.currentTimeMillis(),
    /**
     * 歌词偏移毫秒数，绑定在单首歌上。
     *
     * 正数表示歌词推迟显示（歌词比人声早到时就加大它），
     * 负数表示提前显示，0 表示不偏移 喵~
     */
    val lyricOffsetMs: Long = 0L,
    /** 旁挂封面图路径（和歌词一样放在歌曲同目录），列表页显示用 */
    val coverPath: String? = null,
)
