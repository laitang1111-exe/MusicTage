package com.example.musictage.data.kg

/**
 * 业务预期内的“无歌词”异常
 */
class LyricsNotFoundError(message: String) : Exception(message)

data class KgSong(
    val id: String,
    val hash: String,
    val title: String,
    val artists: List<String>,
    val artistText: String, // 顿号连接的歌手名
    val album: String,
    val durationMs: Long,
    val subtitle: String = "",
    /** 封面地址（接口不一定给，可能为 null） */
    val imageUrl: String? = null,
)

data class KgLyricCandidate(
    val lyricId: String,
    val accessKey: String,
    val durationMs: Long,
    val score: Int,
    val productFrom: String,
    val singer: String,
    val songName: String,
    val nickname: String,
    val contentType: Int
)

data class KgLyricResponse(
    val content: String,
    val contentType: Int
)
