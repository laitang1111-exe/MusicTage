package com.example.musictage.data.qq

/**
 * QQ 音乐歌词接口「拿不到歌词」时统一的异常。
 *
 * 这个类原来定义在别处（历史里没随 QqApi.kt 一起捞回来），
 * 这里单独成文件补上，签名按调用点的用法：只带一个 message。
 */
class LyricsNotFoundForQq(message: String) : Exception(message)
