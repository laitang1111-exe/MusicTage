package com.example.musictage.data.kg

import android.util.Base64
import android.util.Log
import org.json.JSONObject
import java.util.Locale

/**
 * KRC 歌词解析。
 *
 * KRC 明文的结构（酷狗 `/download` 接口解密后得到）：
 * ```
 * [ti:歌名][ar:歌手][al:专辑][by:作者]     ← 元信息
 * [language:BASE64]                        ← 语言轨道：Base64 编码的 JSON
 * [行起始ms,行时长ms]歌词内容               ← 歌词行
 *   ...行内还有 <字起始,字时长,0>逐字 标记
 * ```
 *
 * [language] 标签的内容是 Base64 的 JSON，形如：
 * ```json
 * {"content":[
 *   {"type":0, "lyricContent":[...]},   // 罗马音（本项目不使用）
 *   {"type":1, "lyricContent":[...]}    // 翻译
 * ]}
 * ```
 *
 * 注意：**翻译是酷狗随 KRC 一起下发的，不是本地机器翻译**——
 * 下载接口的参数里没有任何"请求翻译"的开关，语言轨道是打包在歌词文件里的。
 * 详见 LDDC 的 `core/parser/krc.py`（type=0 罗马音、type=1 翻译）。
 */
object KrcParser {
    private const val TAG = "KrcParser"

    private val TIME_LINE_PATTERN = Regex("^\\[(\\d+),(\\d+)]")
    private val WORD_TAG_PATTERN = Regex("<\\d+,\\d+,\\d+>")
    private val LANGUAGE_TAG_PATTERN = Regex("^\\[language:([^\\]]*)]")

    /**
     * 解析结果。
     *
     * @param lrc 原文 LRC 文本（可直接落盘）
     * @param translations 翻译行，按时间戳索引（毫秒 -> 译文）
     */
    data class Result(
        val lrc: String,
        val translations: Map<Long, String>,
    ) {
        /** 是否含有翻译轨道 */
        val hasTranslation: Boolean get() = translations.isNotEmpty()
    }

    /**
     * 把 KRC 明文解析为「原文 + 翻译」。
     *
     * 不再返回单纯的字符串，而是带上翻译时间轴，
     * 以便调用方按用户选择决定输出纯原文还是原文+译文。
     */
    fun parse(krc: String): Result {
        val sb = StringBuilder()
        val originalTimes = mutableListOf<Long>()
        var languageTag: String? = null

        for (rawLine in krc.split("\n")) {
            val trim = rawLine.trim()
            if (trim.isEmpty()) continue

            // 语言轨道：单独取出，不写进 LRC
            val langMatch = LANGUAGE_TAG_PATTERN.find(trim)
            if (langMatch != null) {
                languageTag = langMatch.groupValues[1]
                continue
            }

            val timeMatch = TIME_LINE_PATTERN.find(trim)
            if (timeMatch != null) {
                val startMs = timeMatch.groupValues[1].toLongOrNull() ?: continue
                // 移除逐字标签 <字起始,字时长,0>
                val text = trim.substring(timeMatch.range.last + 1).replace(WORD_TAG_PATTERN, "")
                if (text.isNotBlank()) {
                    sb.append(formatMs(startMs)).append(text).append('\n')
                    // 记录原文行顺序，用于与翻译逐行对齐
                    originalTimes.add(startMs)
                }
            } else if (trim.startsWith("[")) {
                // 保留其它元信息标签（[ti:] [ar:] [al:] [by:] 等）
                sb.append(trim).append('\n')
            }
        }

        val translations = parseTranslationTrack(languageTag, originalTimes)
        return Result(lrc = sb.toString(), translations = translations)
    }

    /**
     * 解析 [language] 轨道里的翻译（type=1）。
     *
     * 与原文的对应方式：KRC 里 `lyricContent` 的每一项**按顺序**对应原文行，
     * 因此这里按行序 zip，而不是按时间戳匹配（翻译的时间戳未必与原文完全一致）。
     *
     * 罗马音（type=0）按需求忽略。
     */
    private fun parseTranslationTrack(
        languageBase64: String?,
        originalTimes: List<Long>,
    ): Map<Long, String> {
        if (languageBase64.isNullOrBlank() || originalTimes.isEmpty()) return emptyMap()

        return try {
            val json = JSONObject(String(Base64.decode(languageBase64, Base64.DEFAULT), Charsets.UTF_8))
            val content = json.optJSONArray("content") ?: return emptyMap()

            for (i in 0 until content.length()) {
                val track = content.optJSONObject(i) ?: continue
                if (track.optInt("type") != 1) continue   // 只要翻译，跳过罗马音

                val lyricContent = track.optJSONArray("lyricContent") ?: continue
                val result = LinkedHashMap<Long, String>()
                for (idx in originalTimes.indices) {
                    if (idx >= lyricContent.length()) break
                    val translated = extractLineText(lyricContent.opt(idx))
                    if (!translated.isNullOrBlank()) {
                        result[originalTimes[idx]] = translated
                    }
                }
                Log.d(TAG, "解析到翻译 ${result.size} 行（原文 ${originalTimes.size} 行）")
                return result
            }
            emptyMap()
        } catch (e: Exception) {
            // language 轨道解析失败不影响原文——原文照常可用
            Log.w(TAG, "解析 [language] 翻译轨道失败: ${e.message}")
            emptyMap()
        }
    }

    /**
     * 从 `lyricContent[i]` 取出整行译文。
     *
     * 该位置上可能是：
     *  - 字符串（整行译文）
     *  - 数组，如 `["译文"]` 或 `["", "译文"]`（逐字时首项为空）
     *  - null / 空数组（该行无译文）
     */
    private fun extractLineText(item: Any?): String? = when (item) {
        null -> null
        is String -> item
        is org.json.JSONArray -> run {
            var found: String? = null
            for (k in 0 until item.length()) {
                val text = item.optString(k)
                if (text.isNotBlank()) {
                    found = text
                    break
                }
            }
            found
        }
        else -> null
    }

    /**
     * 把翻译按时间戳插到原文行之后。
     *
     * 输出示例：
     * ```
     * [00:12.345]原文
     * [00:12.345]译文
     * [00:15.678]原文
     * ```
     *
     * @param originalLrc [parse] 返回的原文 LRC
     * @param translations 时间戳（毫秒）-> 译文
     */
    fun mergeTranslation(originalLrc: String, translations: Map<Long, String>): String {
        if (translations.isEmpty()) return originalLrc

        val sb = StringBuilder()
        for (line in originalLrc.split("\n")) {
            sb.append(line).append('\n')
            val startMs = timestampToMs(line) ?: continue
            val translated = translations[startMs] ?: continue
            // 复用原文行的行首时间戳，保证两行同时间点
            val timeEnd = line.indexOf(']')
            if (timeEnd > 0) {
                sb.append(line, 0, timeEnd + 1).append(translated).append('\n')
            }
        }
        return sb.toString()
    }

    /** 从 `[mm:ss.mmm]xxx` 取出毫秒；非歌词行返回 null */
    private fun timestampToMs(line: String): Long? {
        if (!line.startsWith("[")) return null
        val end = line.indexOf(']')
        if (end <= 1) return null
        val stamp = line.substring(1, end)
        val parts = stamp.split(':', '.')
        if (parts.size != 3) return null
        val min = parts[0].toLongOrNull() ?: return null
        val sec = parts[1].toLongOrNull() ?: return null
        val ms = parts[2].toLongOrNull() ?: return null
        return (min * 60 + sec) * 1000 + ms
    }

    private fun formatMs(ms: Long): String {
        val totalSec = ms / 1000
        val min = totalSec / 60
        val sec = totalSec % 60
        val rem = ms % 1000
        return String.format(Locale.US, "[%02d:%02d.%03d]", min, sec, rem)
    }

    /**
     * 把酷狗 KRC 解出来的 JSON 转成标准 LRC 文本。
     *
     * KRC 结构大致是：
     *   {"content":[{"type":0,"offset":12345,
     *                "lyricContent":[["逐字"],["译文"]]}, ...]}
     * 这里只取每行的起始时间（offset，毫秒）和「第一组文字」拼成整行，
     * 逐字时间轴丢掉 —— 因为落盘的目标是普通 .lrc。
     * 解析失败时原样返回，至少不丢内容。
     */
    fun krcToLrc(krcPlain: String): String {
        return try {
            val root = org.json.JSONObject(krcPlain)
            val arr = root.optJSONArray("content") ?: return krcPlain
            val sb = StringBuilder()
            for (i in 0 until arr.length()) {
                val line = arr.optJSONObject(i) ?: continue
                val offset = line.optLong("offset", -1L)
                val parts = line.optJSONArray("lyricContent") ?: continue
                if (parts.length() == 0) continue
                val words = parts.optJSONArray(0) ?: continue
                val text = buildString {
                    for (k in 0 until words.length()) append(words.optString(k))
                }
                if (text.isBlank()) continue
                if (offset >= 0) sb.append(formatLrcTime(offset))
                sb.append(text).append('\n')
            }
            if (sb.isEmpty()) krcPlain else sb.toString()
        } catch (e: Exception) {
            krcPlain
        }
    }

    /** 毫秒 → [mm:ss.xx] */
    private fun formatLrcTime(ms: Long): String {
        val total = if (ms < 0) 0 else ms
        val m = total / 60000
        val s = (total % 60000) / 1000
        val cs = (total % 1000) / 10
        return String.format("[%02d:%02d.%02d]", m, s, cs)
    }

}
