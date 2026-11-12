package com.example.musictage.utils

import java.io.File
import java.util.regex.Pattern

/**
 * LRC 歌词数据类
 */
data class LrcLine(
    val timeMs: Long,
    val originalText: String,
    val translatedText: String? = null
)

object LrcParser {

    // 匹配时间标签的正则表达式，支持 [mm:ss.SS] 或 [mm:ss.SSS]
    private val timePattern = Pattern.compile("\\[(\\d{2}):(\\d{2})\\.(\\d{2,3})\\]")

    /**
     * 解析 LRC 文本内容
     */
    fun parse(lrcContent: String): List<LrcLine> {
        val lines = lrcContent.split("\n")
        val tempMap = mutableMapOf<Long, MutableList<String>>()

        for (line in lines) {
            val trimLine = line.trim()
            if (trimLine.isEmpty()) continue

            val matcher = timePattern.matcher(trimLine)
            if (matcher.find()) {
                val min = matcher.group(1)?.toLong() ?: 0L
                val sec = matcher.group(2)?.toLong() ?: 0L
                val msStr = matcher.group(3) ?: "00"
                
                // 将毫秒部分标准化（如果是两位数 SS，则需乘以 10 变为 SSS）
                val ms = if (msStr.length == 2) msStr.toLong() * 10 else msStr.toLong()
                val totalTimeMs = (min * 60 + sec) * 1000 + ms
                
                val text = trimLine.substring(matcher.end()).trim()
                if (text.isNotEmpty()) {
                    tempMap.getOrPut(totalTimeMs) { mutableListOf() }.add(text)
                }
            }
        }

        // 将 Map 转换为按时间排序的 LrcLine 列表
        return tempMap.entries
            .map { (time, texts) ->
                LrcLine(
                    timeMs = time,
                    originalText = texts.getOrNull(0) ?: "",
                    translatedText = texts.getOrNull(1)
                )
            }
            .sortedBy { it.timeMs }
    }

    /**
     * 从文件中解析 LRC
     */
    fun parse(file: File): List<LrcLine> {
        return if (file.exists()) parse(file.readText()) else emptyList()
    }

    /**
     * 根据当前播放时间获取对应的歌词行（使用二分查找提高效率）
     */
    fun getLineAt(currentPositionMs: Long, lrcLines: List<LrcLine>): LrcLine? {
        if (lrcLines.isEmpty()) return null
        
        var low = 0
        var high = lrcLines.size - 1

        // 如果当前时间早于第一行
        if (currentPositionMs < lrcLines[0].timeMs) return null

        while (low <= high) {
            val mid = (low + high) / 2
            val midTime = lrcLines[mid].timeMs

            when {
                midTime == currentPositionMs -> return lrcLines[mid]
                midTime < currentPositionMs -> {
                    // 检查是否是最后一行，或者下一行的时间是否已经超过当前时间
                    if (mid == lrcLines.size - 1 || lrcLines[mid + 1].timeMs > currentPositionMs) {
                        return lrcLines[mid]
                    }
                    low = mid + 1
                }
                else -> high = mid - 1
            }
        }
        return null
    }

    /**
     * 把整份歌词的时间戳平移 [offsetMs] 毫秒。
     *
     * 单曲的「歌词偏移」存在数据库里，播放时用它整体挪一下，
     * 这样不用改 lrc 文件本身。负数表示歌词提前显示。
     */
    fun applyOffset(lines: List<LrcLine>, offsetMs: Long): List<LrcLine> {
        if (offsetMs == 0L) return lines
        return lines.map { it.copy(timeMs = it.timeMs + offsetMs) }
    }
}

/**
 * 调用示例：
 * 
 * val lrcContent = """
 * [00:10.00]Hello world
 * [00:10.00]你好，世界
 * [00:15.50]Enjoy the music
 * [00:15.50]享受音乐吧
 * """.trimIndent()
 * 
 * // 1. 解析
 * val lyrics = LrcParser.parse(lrcContent)
 * 
 * // 2. 匹配（在播放器的进度回调中调用）
 * val currentLyric = LrcParser.getLineAt(12000L, lyrics) 
 * // 结果：LrcLine(timeMs=10000, originalText="Hello world", translatedText="你好，世界")
 */

    
