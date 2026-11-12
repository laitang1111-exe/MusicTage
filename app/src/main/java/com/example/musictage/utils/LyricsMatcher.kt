package com.example.musictage.utils

import android.util.Log
import java.util.Locale

/**
 * 歌词精准匹配与打分筛选工具类
 */
object LyricsMatcher {
    private const val TAG = "LyricsMatcher"

    /**
     * 全角 -> 半角符号映射表。
     *
     * 修复：原实现用两个等长字符串按索引替换，
     * 但 "（）［］【】：？，！——" 与 "()[]():?,!--" 长度并不相等（15 vs 13），
     * 一旦走到下标 13 就会抛 StringIndexOutOfBoundsException。
     * 改为成对表逐项替换，并补齐常见全角符号。
     */
    private val SYMBOL_MAP: Map<Char, Char> = mapOf(
        '\uFF08' to '(',  // （
        '\uFF09' to ')',  // ）
        '\uFF3B' to '[',  // ［
        '\uFF3D' to ']',  // ］
        '\u3010' to '[',  // 【
        '\u3011' to ']',  // 】
        '\uFF1A' to ':',  // ：
        '\uFF1F' to '?',  // ？
        '\uFF0C' to ',',  // ，
        '\uFF01' to '!',  // ！
        '\uFF0F' to '/',  // ／
        '\uFF06' to '&',  // ＆
        '\uFF0B' to '+',  // ＋
        '\uFF1D' to '=',  // ＝
        '\uFF5E' to '~',  // ～
        '\u301C' to '~',  // 〜
        '\uFF1B' to ';',  // ；
        '\uFF0E' to '.',  // ．
        '\u3002' to '.',  // 。
        '\u3001' to ',',  // 、
        '\uFF5C' to '|',  // ｜
        '\uFF0A' to '*',  // ＊
        '\uFF20' to '@',  // ＠
        '\uFF03' to '#',  // ＃
        '\uFF04' to '$',  // ＄
        '\uFF05' to '%',  // ％
        '\uFF3C' to '\\', // ＼
        '\uFF1C' to '<',  // ＜
        '\uFF1E' to '>',  // ＞
        '\uFF5B' to '{',  // ｛
        '\uFF5D' to '}',  // ｝
        '\uFF0D' to '-',  // －
        '\u2014' to '-',  // —
        '\u2015' to '-',  // ―
        '\u2026' to '.',  // …
    )

    /**
     * 符号归一化：全角转半角，清理首尾及连续空白
     */
    fun normalizeSymbols(text: String): String {
        val sb = StringBuilder(text.length)
        for (ch in text) {
            sb.append(SYMBOL_MAP[ch] ?: ch)
        }
        // 合并连续的空白字符并去掉首尾空白
        return sb.toString().trim().replace(Regex("\\s+"), " ")
    }

    /**
     * 标签归一化：把版本类修饰统一成规范标签，并去掉无意义修饰。
     *
     * 修复两个会压低真实匹配分的缺陷：
     *  1. 原实现会移除所有括号内容。像「はなれない距離 (离不开的距离)」这种括号内
     *     是有效信息的标题会被截断；而且括号内不含标签关键词时，括号本身仍被无条件删除。
     *     现在：括号内含有效信息则保留，仅当括号内是"独立标签词"（如 (Live)）时才剥离。
     *  2. 原实现把 "[-~].*?[-~]" 也一并删除，会误伤歌名里的感叹号/连字符等合法字符。
     *     现在只处理成对包裹的形式。
     */
    fun normalizeTags(text: String): String {
        val normalized = normalizeSymbols(text).lowercase(Locale.getDefault())
        val pattern = Regex("[\\(\\[\\-\\~](.*?)[\\)\\]\\-\\~]")
        val tags = mutableListOf<String>()
        val removableRanges = mutableListOf<IntRange>()

        pattern.findAll(normalized).forEach { m ->
            val content = m.groupValues[1].trim()
            val tag = when {
                content.contains("ver") || content.contains("版本") -> "ver"
                content.contains("inst") || content.contains("伴奏") || content.contains("instrumental") -> "inst"
                content.contains("mix") || content.contains("混音") -> "mix"
                content.contains("edit") -> "edit"
                content.contains("live") || content.contains("现场") -> "live"
                else -> null
            }
            if (tag != null) {
                tags.add(tag)
                removableRanges.add(m.range)
            } else if (content.isNotEmpty() && content.length <= 4) {
                // 短修饰词（如 (铃声)、[tv]）视为纯标签，剥离
                removableRanges.add(m.range)
            }
            // 其余情况（括号内是有效信息）保留原文，避免截断真实歌名
        }

        var coreText = normalized
        for (r in removableRanges.sortedByDescending { it.first }) {
            coreText = coreText.removeRange(r)
        }
        coreText = coreText.replace(Regex("\\s+"), " ").trim()

        if (tags.isNotEmpty()) {
            coreText = "$coreText ${tags.distinct().joinToString(" ")}".trim()
        }
        return coreText
    }

    /**
     * 2. 时长过滤函数：正负误差超过 thresholdMs（默认 4 秒）丢弃
     */
    fun isDurationMatch(localDurationMs: Long, resultDurationMs: Long, thresholdMs: Long = 4000): Boolean {
        if (localDurationMs <= 0 || resultDurationMs <= 0) return true // 缺少时长参数则不做过滤
        return Math.abs(localDurationMs - resultDurationMs) <= thresholdMs
    }

    /**
     * 3. 计算综合得分（及格线 55 分）
     *
     * 标题相似度同时看「归一化后的标题」与「仅小写去空白的原始标题」两种形式并取较高者，
     * 避免归一化过程本身把真实匹配的分数压低（例如 "Daddy ! Daddy ! Do !" 这类标题）。
     */
    fun calculateScore(localTitle: String, localArtist: String, resultTitle: String, resultArtist: String): Double {
        val cleanLocalTitle = normalizeTags(localTitle)
        val cleanResultTitle = normalizeTags(resultTitle)
        val cleanLocalArtist = normalizeSymbols(localArtist).lowercase(Locale.getDefault())
        val cleanResultArtist = normalizeSymbols(resultArtist).lowercase(Locale.getDefault())

        // 两种标题归一化口径取较高分
        val rawLocalTitle = normalizeSymbols(localTitle).lowercase(Locale.getDefault())
        val rawResultTitle = normalizeSymbols(resultTitle).lowercase(Locale.getDefault())

        val normalizedTitleScore = similarity(cleanLocalTitle, cleanResultTitle) * 100
        val rawTitleScore = similarity(rawLocalTitle, rawResultTitle) * 100
        var titleScore = Math.max(normalizedTitleScore, rawTitleScore)

        // 标签一致（如都带 inst / 都不带）时给 30 分保底加分
        val localHasInst = cleanLocalTitle.contains("inst")
        val resultHasInst = cleanResultTitle.contains("inst")
        if (localHasInst == resultHasInst) {
            titleScore = (titleScore + 30).coerceAtMost(100.0)
        }

        val artistScore = if (cleanLocalArtist.isEmpty() || cleanResultArtist.isEmpty()) {
            0.0   // 任一侧缺歌手时不给歌手分，最终由标题分主导
        } else {
            calculateArtistScore(cleanLocalArtist, cleanResultArtist)
        }

        // 综合得分：标题 0.5 + 歌手 0.5；歌手缺失时退化为标题分
        var finalScore = if (artistScore <= 0.0) {
            titleScore
        } else {
            (titleScore * 0.5) + (artistScore * 0.5)
        }

        // 惩罚：标题相似度过低时扣 35 分
        if (titleScore < 30) {
            finalScore -= 35
        }

        val result = finalScore.coerceAtLeast(0.0)

        Log.d(TAG, "Matching: [$localTitle - $localArtist] vs [$resultTitle - $resultArtist]")
        Log.d(
            TAG,
            "Scores: titleNorm=$normalizedTitleScore, titleRaw=$rawTitleScore, " +
                    "titleUsed=$titleScore, artist=$artistScore, final=$result",
        )
        return result
    }

    /** 统一的相似度入口（当前为编辑距离归一化，后续可替换为更贴近 LDDC 的 SequenceMatcher 实现） */
    private fun similarity(a: String, b: String): Double = LevenshteinDistance(a, b)

    /**
     * 计算歌手的最大匹配得分，支持多歌手拆分
     */
    private fun calculateArtistScore(localArtist: String, resultArtist: String): Double {
        if (localArtist == resultArtist) return 100.0
        
        val delimiters = Regex("[/、,\\s+]")
        val localArtists = localArtist.split(delimiters).filter { it.isNotEmpty() }
        val resultArtists = resultArtist.split(delimiters).filter { it.isNotEmpty() }

        var maxScore = 0.0
        for (la in localArtists) {
            for (ra in resultArtists) {
                val score = LevenshteinDistance(la, ra) * 100
                if (score > maxScore) {
                    maxScore = score
                }
            }
        }
        return maxScore
    }

    /**
     * 经典的编辑距离算法：用于计算字符串相似度归一化值 (0.0 到 1.0)
     */
    private fun LevenshteinDistance(s1: String, s2: String): Double {
        if (s1 == s2) return 1.0
        if (s1.isEmpty()) return 0.0
        if (s2.isEmpty()) return 0.0

        val d = Array(s1.length + 1) { IntArray(s2.length + 1) }
        for (i in 0..s1.length) d[i][0] = i
        for (j in 0..s2.length) d[0][j] = j

        for (i in 1..s1.length) {
            for (j in 1..s2.length) {
                val cost = if (s1[i - 1] == s2[j - 1]) 0 else 1
                d[i][j] = Math.min(
                    Math.min(d[i - 1][j] + 1, d[i][j - 1] + 1),
                    d[i - 1][j - 1] + cost
                )
            }
        }
        val maxLength = Math.max(s1.length, s2.length)
        return (maxLength - d[s1.length][s2.length]).toDouble() / maxLength
    }

/** 这些歌手名等于「没有歌手」，拿去做搜索关键词只会跑偏 */
    private val USELESS_ARTISTS = setOf(
        "<unknown>", "unknown", "未知", "未知歌手", "未知艺术家",
        "various artists", "群星", "va", "n/a", "-", "null",
    )

    /** 歌手名是不是没有信息量 */
    fun isUselessArtist(artist: String): Boolean {
        val a = artist.trim().lowercase()
        if (a.isEmpty()) return true
        return a in USELESS_ARTISTS
    }

    /**
     * 歌名看起来是「歌手 - 歌名」整串文件名。
     *
     * 老歌的 ID3 经常没分开，标题里同时塞了歌手和歌名，
     * 这种串拿去搜索会被 normalizeTags 误当作标签剥掉，所以要能识别。
     */
    fun looksLikeArtistTitleBlob(title: String): Boolean {
        val t = title.trim()
        if (t.isEmpty()) return false
        val sep = listOf(" - ", " – ", " — ", "-", "－")
        for (s in sep) {
            if (!t.contains(s)) continue
            val parts = t.split(s).map { it.trim() }.filter { it.isNotEmpty() }
            if (parts.size >= 2 && parts[0].length in 1..24 && parts[1].length in 1..60) return true
        }
        return false
    }

    /** 候选是不是占位条目（无演唱者 / 歌名是占位词） */
    fun isPlaceholderCandidate(singer: String?, songName: String?, nickname: String?): Boolean {
        val s = (singer ?: "").trim()
        val n = (songName ?: nickname ?: "").trim()
        if (n.isEmpty()) return true
        if (n.contains("伴奏") && s.isEmpty()) return true
        val bad = listOf("纯音乐", "无", "暂无", "待补充", "unknown", "请添加")
        return bad.any { n.lowercase() == it }
    }
}
