package com.example.musictage.data.qq

import java.util.Locale

/**
 * QRC 明文 / LRC 文本 -> 标准 LRC。
 *
 * 对应 LDDC 的两处逻辑：
 *  - `LDDC/core/parser/qrc.py`  从 `<Lyric_1 LyricType="1" LyricContent="..."/>` 里抠正文并解析
 *  - `LDDC/core/parser/qrc.py::qrc_str_parse`  的降级链：QRC -> LRC -> 纯文本
 */
object QrcParser {
    private val QRC_DOC_PATTERN =
        Regex("""<Lyric_1\s+LyricType="1"\s+LyricContent="(.*?)"/>""", RegexOption.DOT_MATCHES_ALL)
    private val LINE_PATTERN = Regex("""^\[(\d+),(\d+)](.*)$""")
    /** 逐字时间戳，形如 (1234,567) —— QRC 用圆括号，酷狗 KRC 用尖括号 */
    private val WORD_PATTERN = Regex("""\((\d+),(\d+)\)""")
    private val TAG_PATTERN = Regex("""^\[(\w+):([^\]]*)]$""")

    /**
     * 把 QQ 音乐返回的歌词文本转成标准 LRC。
     *
     * 依次尝试 QRC -> LRC -> 纯文本，任何一步成功即返回。
     */
    fun toLrc(raw: String): String {
        if (raw.isBlank()) return ""

        val qrcDoc = QRC_DOC_PATTERN.find(raw)
        if (qrcDoc != null) {
            val content = qrcDoc.groupValues[1]
            if (content.isNotBlank()) {
                val parsed = parseQrcContent(content)
                if (parsed.isNotBlank()) return parsed
            }
        }

        // 降级：当普通 LRC 处理（含时间戳的文本原样保留即可）
        if (raw.contains('[') && raw.contains(']')) {
            val cleaned = stripWordStamps(raw)
            if (cleaned.isNotBlank()) return cleaned
        }

        // 再降级：纯文本（无时间轴）
        return raw.lines().joinToString("\n") { it.trim() }.trim()
    }

    /** 解析 QRC 的 LyricContent 正文 */
    private fun parseQrcContent(content: String): String {
        val sb = StringBuilder()
        for (rawLine in content.split('\n')) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue

            val m = LINE_PATTERN.find(line)
            if (m != null) {
                val startMs = m.groupValues[1].toLongOrNull() ?: continue
                val body = stripWordStamps(m.groupValues[3]).trim()
                // QRC 里的纯时间戳空行（只有 (start,dur) 而无文字）在转 LRC 时丢弃
                if (body.isNotEmpty()) {
                    sb.append(formatMs(startMs)).append(body).append('\n')
                }
                continue
            }

            val tag = TAG_PATTERN.find(line)
            if (tag != null) {
                // 保留 [ti:]/[ar:]/[al:]/[by:] 等元信息；[language:] 是 QRC 专有字段，丢弃
                val key = tag.groupValues[1]
                if (key != "language") {
                    sb.append(line).append('\n')
                }
            }
        }
        return sb.toString().trim()
    }

    /** 去掉逐字时间戳 `(1234,567)` */
    private fun stripWordStamps(text: String): String = WORD_PATTERN.replace(text, "")

    private fun formatMs(ms: Long): String {
        val totalSeconds = ms / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        val millis = ms % 1000
        return String.format(Locale.US, "[%02d:%02d.%03d]", minutes, seconds, millis)
    }
}
