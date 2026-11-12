package com.example.musictage.data.qq

import android.util.Log
import com.example.musictage.data.kg.LyricsNotFoundError
import com.example.musictage.utils.LyricsMatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/** 一次歌词获取的结果，附带匹配信息，便于界面给出可读的反馈 */
data class QqLyricResult(
    /** 可直接落盘的 LRC 文本 */
    val lrcText: String,
    /** 命中的歌曲信息 */
    val matchedTitle: String,
    val matchedArtist: String,
    /** 命中歌曲的时长（毫秒）；0 表示未知 */
    val matchedDurationMs: Long,
    /** 本地时长与命中歌曲时长的差值（毫秒） */
    val durationDiffMs: Long,
    /** 本地—结果匹配分 */
    val matchScore: Double,
    /** 搜索时使用的关键词 */
    val usedKeyword: String,
)

/**
 * QQ 音乐歌词仓库。
 *
 * 与酷狗的 [com.example.musictage.utils.KugouLyricRepository] 保持同样的策略：
 *  1. 搜索关键词按精确度生成多个变体，逐个尝试；
 *  2. 对搜索结果用 [LyricsMatcher] 打分，取最高分且超过阈值的；
 *  3. ±4 秒时长硬过滤（关键词信息不足时放宽到 ±15 秒）；
 *  4. 取到歌词后**再校验一遍时长**，因为 QQ 音乐也可能返回时长不符的歌词。
 *
 * 与酷狗的差异：QQ 音乐没有"歌词候选列表"，一次请求即拿到该歌曲的全部歌词，
 * 因此不存在候选筛选与递补，只有"这首歌本身选错了"这一种失败模式。
 */
object QqLyricRepository {
    private const val TAG = "QqRepo"

    /** 常规时长容差：±4 秒 */
    private const val DURATION_TOLERANCE_MS = 4000L

    /** 关键词信息不足时放宽到 ±15 秒 */
    private const val DURATION_TOLERANCE_UNSURE_MS = 15000L

    /** 最低匹配分 */
    private const val MIN_SCORE = 55.0

    private data class MatchAttempt(
        val label: String,
        val keyword: String,
        val scoreTitle: String,
        val authorshipUnsure: Boolean = false,
    ) {
        override fun toString(): String =
            "$label(keyword=\"$keyword\", scoreTitle=\"$scoreTitle\", unsure=$authorshipUnsure)"
    }

    /**
     * 在线匹配 QQ 音乐歌词。
     *
     * @param songName 歌名
     * @param artistName 歌手名
     * @param durationMs 本地音频时长（毫秒）；<= 0 表示未知，此时不做时长过滤
     * @return 成功返回 [QqLyricResult]；未找到返回 null
     */
    suspend fun fetchLyric(
        songName: String,
        artistName: String,
        durationMs: Long,
    ): QqLyricResult? = withContext(Dispatchers.IO) {
        val localTitle = songName.trim()
        val localArtist = artistName.trim()
        val artistUsable = !LyricsMatcher.isUselessArtist(localArtist)

        Log.d(
            TAG,
            "fetchLyric: title=[$localTitle], artist=[$localArtist], durationMs=$durationMs " +
                    "(artistUsable=$artistUsable)",
        )

        if (localTitle.isEmpty()) {
            Log.w(TAG, "本地歌名为空，无法进行可靠搜索")
            return@withContext null
        }

        // 歌名看起来是整串文件名时，把各部分都作为候选，不假设哪一半是歌名
        val titleLooksLikeBlob = LyricsMatcher.looksLikeArtistTitleBlob(localTitle)
        val blobParts: List<String> = if (titleLooksLikeBlob) {
            localTitle.split(Regex("\\s+-\\s+"))
                .map { it.trim() }
                .filter { it.isNotEmpty() && it != localTitle }
                .distinct()
        } else {
            emptyList()
        }
        if (titleLooksLikeBlob) {
            Log.w(TAG, "歌名疑似「整个文件名被当作标题」: [$localTitle]，候选关键词: $blobParts")
        }

        val attempts = mutableListOf<MatchAttempt>()
        if (artistUsable) {
            attempts += MatchAttempt(
                label = "artist-title",
                keyword = "$localArtist - $localTitle",
                scoreTitle = localTitle,
            )
        }
        attempts += MatchAttempt(
            label = "title-only",
            keyword = localTitle,
            scoreTitle = localTitle,
        )
        for (part in blobParts) {
            attempts += MatchAttempt(
                label = "blob-part",
                keyword = part,
                scoreTitle = part,
                authorshipUnsure = true,
            )
        }

        var lastError: Exception? = null

        for (attempt in attempts) {
            Log.d(TAG, "搜索尝试 [$attempt]")
            try {
                val songs = QqApi.searchSong(attempt.keyword)
                if (songs.isEmpty()) {
                    Log.d(TAG, "[${attempt.label}] 搜索无结果")
                    continue
                }

                val best = pickBestSong(songs, attempt, localArtist, durationMs)
                if (best == null) {
                    Log.w(TAG, "[${attempt.label}] 没有通过时长与分数校验的歌曲")
                    continue
                }
                val (score, song) = best
                Log.d(
                    TAG,
                    "[${attempt.label}] 选定歌曲: id=${song.id}, title=[${song.title}], " +
                            "artist=[${song.artistText}], album=[${song.album}], dur=${song.durationMs}, score=$score",
                )

                val result = tryFetchLyricsFor(song, durationMs, attempt, score)
                if (result != null) {
                    return@withContext result
                }
                Log.w(TAG, "[${attempt.label}] 该歌曲取不到有效歌词")
            } catch (e: LyricsNotFoundError) {
                lastError = e
                Log.i(TAG, "[${attempt.label}] 业务上判定为无歌词: ${e.message}")
            } catch (e: IOException) {
                lastError = e
                Log.e(TAG, "[${attempt.label}] 网络错误", e)
            } catch (e: Exception) {
                lastError = e
                Log.e(TAG, "[${attempt.label}] 未预期错误", e)
            }
        }

        Log.i(TAG, "所有关键词均尝试完毕，未获得歌词。lastError=${lastError?.message}")
        null
    }

    /** 对一次搜索的全部结果打分排序，返回最高分的歌曲 */
    private fun pickBestSong(
        songs: List<QqSong>,
        attempt: MatchAttempt,
        localArtist: String,
        localDurationMs: Long,
    ): Pair<Double, QqSong>? {
        val scored = mutableListOf<Pair<Double, QqSong>>()
        for (song in songs) {
            val durationOk = isDurationAcceptable(localDurationMs, song.durationMs, attempt.authorshipUnsure)
            val score = LyricsMatcher.calculateScore(attempt.scoreTitle, localArtist, song.title, song.artistText)

            Log.d(
                TAG,
                "[${attempt.label}] 候选 id=${song.id} title=[${song.title}] artist=[${song.artistText}] " +
                        "album=[${song.album}] dur=${song.durationMs} " +
                        "(与本地差=${Math.abs(localDurationMs - song.durationMs)}ms, durationOk=$durationOk) " +
                        "score=$score (假设歌名=[${attempt.scoreTitle}])",
            )

            if (!durationOk) {
                val tol = if (attempt.authorshipUnsure) DURATION_TOLERANCE_UNSURE_MS else DURATION_TOLERANCE_MS
                Log.d(TAG, "[${attempt.label}] 因时长超出 ±${tol}ms 被丢弃: id=${song.id}")
                continue
            }
            if (score < MIN_SCORE) {
                Log.d(TAG, "[${attempt.label}] 因分数低于 $MIN_SCORE 被丢弃: id=${song.id} score=$score")
                continue
            }
            scored += score to song
        }

        if (scored.isEmpty()) return null
        scored.sortWith(
            compareByDescending<Pair<Double, QqSong>> { it.first }
                .thenBy { Math.abs(localDurationMs - it.second.durationMs).toInt() },
        )
        return scored.first()
    }

    /**
     * 取歌词并做最终校验。
     *
     * QQ 音乐一次返回原文/翻译/罗马音，这里把三者按 LRC 时间轴合并输出
     * （与 LDDC 的 `converter/share.py::get_lyrics_lines` 思路一致：同一时间点的译文紧跟原文）。
     */
    private suspend fun tryFetchLyricsFor(
        song: QqSong,
        localDurationMs: Long,
        attempt: MatchAttempt,
        matchScore: Double,
    ): QqLyricResult? {
        val resp = QqApi.getLyric(song)

        // 原文：qrc_t != "0" 表示 QRC 密文，否则是明文
        val origStamp = if (resp.qrcStamp.isNotEmpty() && resp.qrcStamp != "0") resp.qrcStamp else resp.lrcStamp
        val origRaw = resp.qrc
        if (origRaw.isBlank()) {
            Log.w(TAG, "响应里没有原文歌词 (songID=${song.id})")
            return null
        }

        val origLrc = if (origStamp != "0" && origStamp.isNotEmpty()) {
            Log.d(TAG, "原文为 QRC 密文 (stamp=$origStamp)，开始解密")
            val plain = QrcDecoder.decryptCloud(origRaw)
            if (plain.isEmpty()) {
                Log.e(TAG, "QRC 解密失败或结果为空")
                return null
            }
            QrcParser.toLrc(plain)
        } else {
            Log.d(TAG, "原文为明文 (stamp=$origStamp)，直接解析")
            QrcParser.toLrc(origRaw)
        }

        if (origLrc.isBlank()) {
            Log.w(TAG, "解析后原文为空")
            return null
        }

        // 最终时长校验：用歌词最后一行时间戳与本地时长比对，防止下载到时长不符的内容
        val lastStamp = lastTimestampMs(origLrc)
        if (localDurationMs > 0 && lastStamp > 0) {
            val tolerance = if (attempt.authorshipUnsure) DURATION_TOLERANCE_UNSURE_MS else DURATION_TOLERANCE_MS
            // 歌词结尾通常比音频结尾早几秒，所以只校验"歌词不该明显超出音频时长"
            if (lastStamp - localDurationMs > tolerance) {
                Log.w(
                    TAG,
                    "歌词末行时间戳 ${lastStamp}ms 明显超出本地时长 ${localDurationMs}ms（容差 ${tolerance}ms），" +
                            "拒绝使用",
                )
                return null
            }
            Log.d(TAG, "歌词末行时间戳 ${lastStamp}ms 与本地时长 ${localDurationMs}ms 相符")
        }

        val merged = mergeTranslations(origLrc, resp, attempt)

        return QqLyricResult(
            lrcText = merged,
            matchedTitle = song.title,
            matchedArtist = song.artistText,
            matchedDurationMs = song.durationMs,
            durationDiffMs = if (localDurationMs > 0) Math.abs(localDurationMs - song.durationMs) else 0L,
            matchScore = matchScore,
            usedKeyword = attempt.keyword,
        )
    }

    /**
     * 把翻译/罗马音按时间戳插到原文行后面。
     * 对应 LDDC 里"原文 + 译文同时间点合并"的处理。
     */
    private fun mergeTranslations(origLrc: String, resp: QqLyricResponse, attempt: MatchAttempt): String {
        val extra = mutableListOf<Pair<String, String>>()   // timestamp -> text

        val tsStamp = resp.transStamp
        if (resp.translation.isNotBlank() && tsStamp != "0") {
            val tsPlain = if (tsStamp.isNotEmpty()) {
                QrcDecoder.decryptCloud(resp.translation).ifEmpty { resp.translation }
            } else {
                resp.translation
            }
            parseTimestampedLines(QrcParser.toLrc(tsPlain)).forEach { extra += it }
        }

        val romaStamp = resp.romaStamp
        if (resp.romanization.isNotBlank() && romaStamp != "0") {
            val romaPlain = if (romaStamp.isNotEmpty()) {
                QrcDecoder.decryptCloud(resp.romanization).ifEmpty { resp.romanization }
            } else {
                resp.romanization
            }
            parseTimestampedLines(QrcParser.toLrc(romaPlain)).forEach { extra += it }
        }

        if (extra.isEmpty()) return origLrc

        val byStamp = extra.groupBy({ it.first }, { it.second })
        val sb = StringBuilder()
        val tagLines = mutableListOf<String>()
        for (line in origLrc.lines()) {
            val m = TIMESTAMP_LINE.find(line)
            if (m == null) {
                if (line.isNotBlank()) tagLines.add(line)
                continue
            }
            sb.append(line).append('\n')
            val stamp = m.groupValues[1]
            byStamp[stamp]?.forEach { sb.append('[').append(stamp).append(']').append(it).append('\n') }
        }

        val header = if (tagLines.isNotEmpty()) tagLines.joinToString("\n") + "\n" else ""
        Log.d(TAG, "已合并 ${extra.size} 行翻译/罗马音 (${attempt.label})")
        return header + sb.toString()
    }

    private val TIMESTAMP_LINE = Regex("""^\[(\d{2}:\d{2}\.\d{3})]""")
    private val TIMESTAMP_ANY = Regex("""\[(\d{2}:\d{2}\.\d{3})]""")

    private fun parseTimestampedLines(lrc: String): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        for (line in lrc.lines()) {
            val m = TIMESTAMP_LINE.find(line) ?: continue
            val text = line.substring(m.range.last + 1).trim()
            if (text.isNotEmpty()) out += m.groupValues[1] to text
        }
        return out
    }

    /** 取 LRC 中最后一个时间戳（毫秒），用于和本地时长比对 */
    private fun lastTimestampMs(lrc: String): Long {
        var last = 0L
        for (m in TIMESTAMP_ANY.findAll(lrc)) {
            val ms = stampToMs(m.groupValues[1]) ?: continue
            if (ms > last) last = ms
        }
        return last
    }

    private fun stampToMs(stamp: String): Long? {
        val parts = stamp.split(':', '.')
        if (parts.size != 3) return null
        val min = parts[0].toLongOrNull() ?: return null
        val sec = parts[1].toLongOrNull() ?: return null
        val ms = parts[2].toLongOrNull() ?: return null
        return (min * 60 + sec) * 1000 + ms
    }

    /** 时长是否可接受；基准始终是本地时长 */
    private fun isDurationAcceptable(
        localDurationMs: Long,
        candidateDurationMs: Long,
        unsure: Boolean,
    ): Boolean {
        if (localDurationMs <= 0) return true
        if (candidateDurationMs <= 0) return true
        val tolerance = if (unsure) DURATION_TOLERANCE_UNSURE_MS else DURATION_TOLERANCE_MS
        return Math.abs(localDurationMs - candidateDurationMs) <= tolerance
    }
}
