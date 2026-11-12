package com.example.musictage

/**
 * 播放模式。
 *
 * 原本定义在 PlayerViewModel.kt 里，历史里捞回来的那个版本没有它，
 * 这里单独成文件补上，取值与顺序按反编译出来的枚举来（ordinal 会写进 preferences，
 * 所以顺序不能改）：
 *   0 SEQUENTIAL 顺序播放
 *   1 SHUFFLE    随机播放
 *   2 REPEAT_ONE 单曲循环
 *   3 PLAY_ONCE  单次播放
 */
enum class PlaybackMode(val label: String) {
    SEQUENTIAL("顺序播放"),
    SHUFFLE("随机播放"),
    REPEAT_ONE("单曲循环"),
    PLAY_ONCE("单次播放"),
}
