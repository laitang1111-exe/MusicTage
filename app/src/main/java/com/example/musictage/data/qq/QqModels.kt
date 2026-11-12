package com.example.musictage.data.qq

/**
 * QQ 音乐（音乐馆）搜索返回的歌曲条目。
 *
 * 字段与 LDDC 的 `SongInfo` 对齐，方便与酷狗源共用匹配/打分逻辑：
 *  - [id]        对应 qm.py 里的 `info["id"]`，下载歌词时作为 `songID`
 *  - [title]     对应 `info["title"]`，下载歌词时 Base64 后作为 `songName`
 *  - [artists]   对应 `info["singer"][].name`
 *  - [album]     对应 `info["album"]["name"]`，下载歌词时 Base64 后作为 `albumName`
 *  - [durationMs] 对应 `info["interval"] * 1000`（接口返回的是秒）
 */
data class QqSong(
    val id: String,
    val mid: String,
    val title: String,
    val subtitle: String,
    val artists: List<String>,
    /** 顿号连接的歌手名，用于日志与关键词构造 */
    val artistText: String,
    /** 斜杠连接的歌手名，歌词接口的 singerName 需要它（与 LDDC 的 Artist.__str__ 一致） */
    val artistSlash: String,
    val album: String,
    val durationMs: Long,
)

/**
 * QQ 音乐歌词接口的返回。
 *
 * 与酷狗不同，QQ 音乐没有"歌词候选列表"这一层：
 * 一次 GetPlayLyricInfo 就把该歌曲的原文/翻译/罗马音全部取回。
 *
 * @param qrc          原文歌词（可能是 QRC 密文，也可能是明文 LRC）
 * @param qrcStamp     原文对应的版本号；等于 "0" 表示明文，否则需 QRC 解密
 * @param lrcStamp     纯 LRC 版本号，qrcStamp 为 0 时用它判断
 * @param translation  翻译歌词
 * @param transStamp   翻译对应的版本号
 * @param romanization 罗马音歌词
 * @param romaStamp    罗马音对应的版本号
 */
data class QqLyricResponse(
    val qrc: String,
    val qrcStamp: String,
    val lrcStamp: String,
    val translation: String,
    val transStamp: String,
    val romanization: String,
    val romaStamp: String,
)
