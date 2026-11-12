package com.example.musictage.data.kg

import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.Inflater

/**
 * ★ 本地 KRC 文件读取 喵~
 *
 * 背景：酷狗下载目录里每首歌会带三个文件
 * ```
 *   陈慧娴 - 千千阕歌.mp3
 *   陈慧娴 - 千千阕歌.lrc   ← 逐行（普通 LRC）
 *   陈慧娴 - 千千阕歌.krc   ← ★ 逐字（加密的 KRC）
 * ```
 * 以前扫描器只认 `.lrc`，`.krc` 完全没被利用 ✗
 * 这个文件补上「读本地 .krc」这条路 ✓
 *
 * ## KRC 文件格式
 * ```
 * 偏移 0    "krc1"（4 字节魔数）
 * 偏移 4    加密体：每字节 XOR 固定 16 字节密钥
 *           → zlib 解压 → UTF-8 明文
 * ```
 *
 * ## 明文结构
 * ```
 * [id:$0191A4E4]
 * [ar:歌手][ti:标题][offset:297]
 * [language:BASE64]                        ← 翻译轨道（Base64 的 JSON）
 * [15430,1866]<0,799,0>徐<799,479,0>徐<1278,179,0>回<1457,409,0>望
 *  ↑行起点 ↑行时长   ↑字内偏移 ↑字时长
 * ```
 *
 * 逐字标记 `<字内偏移ms,字时长ms,0>` —— 这就是卡拉OK 高亮要用的数据 ✓
 */
object KrcLocalReader {

    private const val TAG = "KrcLocalReader"

    /**
     * ★ KRC 解密密钥（16 字节，固定）
     *
     * 出处：酷狗 PC 端 KRC 解密算法，LDDC 的 `core/parser/krc.py` 里也是这个 ✓
     */
    private val KRC_KEY = intArrayOf(
        0x40, 0x47, 0x61, 0x77, 0x5E, 0x32, 0x74, 0x47,
        0x51, 0x36, 0x31, 0x2D, 0xCE, 0xD2, 0x6E, 0x69,
    )

    /** 魔法头 */
    private val MAGIC = byteArrayOf(0x6B, 0x72, 0x63, 0x31)   // "krc1"

    // ══════════════════════════════════════════════════════════
    //  数据模型
    // ══════════════════════════════════════════════════════════

    /** ★ 一个字（用于逐字高亮） */
    data class KrcWord(
        /** 相对本行起点的偏移（毫秒） */
        val offsetMs: Int,
        /** 这个字唱多久（毫秒） */
        val durationMs: Int,
        /** 字本身 */
        val text: String,
    ) {
        /** 相对本行起点的结束位置（毫秒） */
        val endMs: Int get() = offsetMs + durationMs
    }

    /** ★ 一行（含逐字） */
    data class KrcLine(
        /** 行起点（绝对毫秒） */
        val startMs: Long,
        /** 行时长（毫秒） */
        val durationMs: Int,
        /** 整行文本（去掉逐字标记后的） */
        val text: String,
        /** ★ 逐字时间轴；没有逐字标记时为空表 */
        val words: List<KrcWord>,
    ) {
        val endMs: Long get() = startMs + durationMs
    }

    /** ★ 一个 KRC 文件的完整解析结果 */
    data class KrcDoc(
        /** 元信息，例如 "ar" -> "陈慧娴" */
        val meta: Map<String, String>,
        /** 歌词行 */
        val lines: List<KrcLine>,
        /** 翻译：行起点ms -> 译文 */
        val translations: Map<Long, String>,
    ) {
        val hasWords: Boolean get() = lines.any { it.words.isNotEmpty() }
        val hasTranslation: Boolean get() = translations.isNotEmpty()
    }

    // ══════════════════════════════════════════════════════════
    //  一、解密
    // ══════════════════════════════════════════════════════════

    /**
     * ★ 把 .krc 文件的字节解密 + 解压成明文
     *
     * @return 明文；失败返回 null
     */
    fun decode(raw: ByteArray): String? {
        return try {
            // ① 去掉 "krc1" 魔数（没有也照样试）
            val body = if (raw.size > 4 && raw[0] == MAGIC[0] && raw[1] == MAGIC[1] &&
                raw[2] == MAGIC[2] && raw[3] == MAGIC[3]
            ) raw.copyOfRange(4, raw.size) else raw

            // ② XOR 解密
            val dec = ByteArray(body.size)
            for (i in body.indices) {
                dec[i] = (body[i].toInt() xor KRC_KEY[i % KRC_KEY.size]).toByte()
            }

            // ③ zlib 解压（先按标准 zlib，失败再按 raw deflate 试一次）
            val text = inflate(dec, nowrap = false) ?: inflate(dec, nowrap = true)
            if (text == null) {
                Log.w(TAG, "zlib 解压失败（两种模式都试过了）")
                return null
            }
            text
        } catch (e: Throwable) {
            Log.w(TAG, "解码失败: ${e.message}")
            null
        }
    }

    private fun inflate(data: ByteArray, nowrap: Boolean): String? {
        val inf = Inflater(nowrap)
        return try {
            inf.setInput(data)
            val out = ByteArrayOutputStream(data.size * 4)
            val buf = ByteArray(8192)
            while (!inf.finished()) {
                val n = inf.inflate(buf)
                if (n == 0) {
                    if (inf.needsInput() || inf.needsDictionary()) break
                } else {
                    out.write(buf, 0, n)
                }
            }
            val bytes = out.toByteArray()
            if (bytes.isEmpty()) null else String(bytes, Charsets.UTF_8)
        } catch (e: Throwable) {
            null
        } finally {
            inf.end()
        }
    }

    // ══════════════════════════════════════════════════════════
    //  二、解析明文
    // ══════════════════════════════════════════════════════════

    /** `[起始ms,时长ms]` */
    private val TIME_LINE = Regex("^\\[(\\d+),(\\d+)]")

    /** `<字偏移,字时长,0>` */
    private val WORD_TAG = Regex("<(\\d+),(\\d+),(\\d+)>")

    /** `[key:value]` 元信息 */
    private val META_TAG = Regex("^\\[([a-zA-Z]+):([^]]*)]")

    /**
     * ★ 解析 KRC 明文（保留逐字信息）
     */
    fun parsePlain(plain: String): KrcDoc {
        val meta = LinkedHashMap<String, String>()
        val lines = ArrayList<KrcLine>()
        var languageTag: String? = null

        for (rawLine in plain.split("\n")) {
            val t = rawLine.trim()
            if (t.isEmpty()) continue

            // ── 时间行？ ──
            val tm = TIME_LINE.find(t)
            if (tm != null) {
                val startMs = tm.groupValues[1].toLongOrNull() ?: continue
                val durMs = tm.groupValues[2].toIntOrNull() ?: 0
                val rest = t.substring(tm.range.last + 1)

                // 切出「字 + 它的标记」
                val words = ArrayList<KrcWord>()
                val sb = StringBuilder()
                var cursor = 0
                for (m in WORD_TAG.findAll(rest)) {
                    // 标记【前面】的文字归属上一个标记
                    if (m.range.first > cursor) {
                        val chunk = rest.substring(cursor, m.range.first)
                        if (chunk.isNotEmpty()) {
                            if (words.isEmpty()) {
                                // 行首没有标记的文字 —— 当作一个从 0 开始的字
                                words.add(KrcWord(0, 0, chunk))
                            } else {
                                val last = words.removeAt(words.size - 1)
                                words.add(last.copy(text = last.text + chunk))
                            }
                            sb.append(chunk)
                        }
                    }
                    val off = m.groupValues[1].toIntOrNull() ?: 0
                    val dur = m.groupValues[2].toIntOrNull() ?: 0
                    words.add(KrcWord(off, dur, ""))
                    cursor = m.range.last + 1
                }
                // 尾巴
                if (cursor < rest.length) {
                    val tail = rest.substring(cursor)
                    if (tail.isNotEmpty()) {
                        if (words.isEmpty()) words.add(KrcWord(0, 0, tail))
                        else {
                            val last = words.removeAt(words.size - 1)
                            words.add(last.copy(text = last.text + tail))
                        }
                        sb.append(tail)
                    }
                }

                val text = sb.toString()
                if (text.isNotBlank()) {
                    lines.add(
                        KrcLine(
                            startMs = startMs,
                            durationMs = durMs,
                            text = text,
                            words = words.filter { it.text.isNotEmpty() },
                        )
                    )
                }
                continue
            }

            // ── 元信息 ──
            val mm = META_TAG.find(t)
            if (mm != null) {
                val k = mm.groupValues[1].lowercase()
                val v = mm.groupValues[2]
                if (k == "language") languageTag = v else if (v.isNotBlank()) meta[k] = v
            }
        }

        val translations = parseTranslationTrack(languageTag, lines.map { it.startMs })
        return KrcDoc(meta = meta, lines = lines, translations = translations)
    }

    /**
     * ★ 翻译轨道：[language:BASE64] → JSON
     *
     * ```json
     * {"content":[{"type":1,"lyricContent":[["译文行1"],["译文行2"]]}]}
     * ```
     * type=0 是罗马音（本项目不用），type=1 是翻译 ✓
     */
    private fun parseTranslationTrack(b64: String?, originalTimes: List<Long>): Map<Long, String> {
        if (b64.isNullOrBlank()) return emptyMap()
        return try {
            val json = String(android.util.Base64.decode(b64, android.util.Base64.DEFAULT), Charsets.UTF_8)
            val root = org.json.JSONObject(json)
            val arr = root.optJSONArray("content") ?: return emptyMap()
            var picked: org.json.JSONArray? = null
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                if (o.optInt("type", -1) == 1) { picked = o.optJSONArray("lyricContent"); break }
            }
            if (picked == null) return emptyMap()
            val map = LinkedHashMap<Long, String>()
            for (i in 0 until minOf(picked.length(), originalTimes.size)) {
                val line = picked.optJSONArray(i) ?: continue
                val s = buildString {
                    for (j in 0 until line.length()) append(line.optString(j))
                }.trim()
                if (s.isNotEmpty()) map[originalTimes[i]] = s
            }
            map
        } catch (e: Throwable) {
            Log.w(TAG, "翻译轨道解析失败: ${e.message}")
            emptyMap()
        }
    }

    // ══════════════════════════════════════════════════════════
    //  三、对外入口
    // ══════════════════════════════════════════════════════════

    /** ★ 读一个 .krc 文件 */
    fun read(file: File): KrcDoc? {
        if (!file.exists() || !file.isFile) return null
        return try {
            val raw = file.readBytes()
            val plain = decode(raw) ?: return null
            parsePlain(plain)
        } catch (e: Throwable) {
            Log.w(TAG, "读 ${file.name} 失败: ${e.message}")
            null
        }
    }

    /**
     * ★★ 把 KrcDoc 拍平成 LRC 文本（逐行）
     *
     * 用于喂给现有的 [com.example.musictage.utils.LrcParser]，
     * 这样不用改播放链路就能让 .krc 直接当歌词用 ✓
     */
    fun toLrcText(doc: KrcDoc, withTranslation: Boolean = true): String {
        val sb = StringBuilder()
        doc.meta.forEach { (k, v) -> sb.append('[').append(k).append(':').append(v).append("]\n") }
        for (line in doc.lines) {
            sb.append(formatMs(line.startMs)).append(line.text)
            if (withTranslation) {
                doc.translations[line.startMs]?.let { sb.append(" | ").append(it) }
            }
            sb.append('\n')
        }
        return sb.toString()
    }

    private fun formatMs(ms: Long): String {
        val m = ms / 60000
        val s = (ms % 60000) / 1000
        val cs = (ms % 1000) / 10
        return String.format(java.util.Locale.US, "[%02d:%02d.%02d]", m, s, cs)
    }
}
