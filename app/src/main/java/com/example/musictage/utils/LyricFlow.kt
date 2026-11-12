package com.example.musictage.utils

import com.example.musictage.data.SongEntity

/**
 * 歌词匹配与确认流程状态 喵~
 */
sealed class LyricFlow {
    /** 初始/未开始状态 */
    object Idle : LyricFlow()

    /** 正在搜索歌词 */
    data class Searching(val song: SongEntity) : LyricFlow()

    /**
     * 待确认歌词状态。
     *
     * @param song 当前针对的歌曲
     * @param lyricText 搜到的歌词内容
     * @param isInstrumental 是否被设为纯音乐
     */
    data class ConfirmingLyric(
        val song: SongEntity,
        val lyricText: String,
        val isInstrumental: Boolean = false,
        val offsetMs: Long = 0L
    ) : LyricFlow()

    /** 匹配完成 */
    object Success : LyricFlow()

    /** 匹配失败 */
    data class Error(val message: String) : LyricFlow()
}
