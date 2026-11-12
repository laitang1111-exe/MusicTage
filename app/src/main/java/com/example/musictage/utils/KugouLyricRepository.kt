package com.example.musictage.utils

import android.util.Log
import com.example.musictage.data.kg.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

object KugouLyricRepository {
    private const val TAG = "KgRepo"

    /** 时长容差：±4 秒（与 LDDC 一致） */
    private const val DURATION_TOLERANCE_MS = 4000L

    /** 最低匹配分：低于此分不下载歌词（LDDC auto_fetch 默认阈值区间 55~60） */
    private const val MIN_SCORE = 55.0

    /** 每个搜索模式最多实际发起多少次歌词下载 */
    private const val MAX_DOWNLOAD_ATTEMPTS = 3

    /** 一次匹配尝试：一个搜索关键词 + 候选筛选时的参考时长 */
    private data class MatchAttempt(
        val label: String,
        val keyword: String,
        val refDurationMs: Long,
    ) {
        override fun toString(): String = "$label(keyword=\"$keyword\", refDur=$refDurationMs)"
    }

    /**
     * 在线匹配歌词。
     *
     * 修复要点（对照 LDDC auto_fetch 的策略）：
     *  1. 不再盲取搜索结果第一条，而是对全部结果按时长 + 歌名 + 歌手打分，取最高分；
     *  2. 时长容差 ±4 秒作为硬过滤；
     *  3. 歌名+歌手综合分低于 [MIN_SCORE] 的一律淘汰，避免"信息正确却匹配到别的歌"；
     *  4. 「歌手 - 歌名」召回为空时，自动降级为纯歌名再搜一轮；
     *  5. 拿到候选后依然按时长过滤、官方优先排序，并最多实际下载 3 条。
     *
     * @param songName 歌名
     * @param artistName 歌手名
     * @param durationMs 本地音频时长（毫秒）；<= 0 表示未知，此时不启用时长过滤
     */
    suspend fun fetchLyric(songName: String, artistName: String, durationMs: Long): String? = withContext(Dispatchers.IO) {
        val localTitle = songName.trim()
        val localArtist = artistName.trim()
        // 修复：MediaStore 在标签缺失时会给出 "<unknown>"，FLAC 无 ARTIST 时还可能为空串。
        // 这些都必须视为"没有歌手"，否则会拼出 "<unknown> - 歌名" 这种必然跑偏的关键词。
        val artistUsable = !LyricsMatcher.isUselessArtist(localArtist)
        val durationKnown = durationMs > 0

        Log.d(TAG, "fetchLyric: title=[$localTitle], artist=[$localArtist], durationMs=$durationMs " +
                "(artistUsable=$artistUsable, durationKnown=$durationKnown)")

        if (localTitle.isEmpty()) {
            Log.w(TAG, "本地歌名为空，无法进行可靠搜索（LDDC 在此情况下会降级用文件名）")
        }
        if (localTitle.contains(REPLACEMENT_CHAR) || localArtist.contains(REPLACEMENT_CHAR)) {
            Log.w(TAG, "输入含替换字符 U+FFFD，说明标签编码已损坏，匹配质量会下降")
        }

        // 修复：FLAC 缺少 TITLE 标签时，MediaStore 会把整个文件名当标题，
        // 得到 "芹澤優 - コスモルミナ" 这种"歌手 - 歌名"形态的串。
        // 这种串既会干扰搜索，也会被 normalizeTags 误当作标签剥掉，因此额外拆出纯歌名。
        val titleLooksLikeBlob = LyricsMatcher.looksLikeArtistTitleBlob(localTitle)
        val salvagedTitle: String? = if (titleLooksLikeBlob) {
            localTitle.substringAfterLast(" - ").trim().ifEmpty { null }
        } else {
            null
        }
        if (titleLooksLikeBlob) {
            Log.w(TAG, "歌名疑似「整个文件名被当作标题」: [$localTitle]，尝试拆分出纯歌名=[$salvagedTitle]")
        }

        // 搜索关键词变体：从最精确到最宽松
        val attempts = mutableListOf<MatchAttempt>()
        if (artistUsable && localTitle.isNotEmpty()) {
            attempts += MatchAttempt(
                label = "artist-title",
                keyword = "$localArtist - $localTitle",
                refDurationMs = durationMs,
            )
        }
        if (localTitle.isNotEmpty()) {
            attempts += MatchAttempt(
                label = "title-only",
                keyword = localTitle,
                refDurationMs = 0L,   // 召回的未必是同一版本，时长不作为硬过滤
            )
        }
        // 只有歌名是"文件名糊串"时才用拆出来的部分，且放在最后，避免污染正常歌名
        if (salvagedTitle != null && salvagedTitle != localTitle) {
            attempts += MatchAttempt(
                label = "salvaged-title",
                keyword = salvagedTitle,
                refDurationMs = 0L,
            )
        }
        if (attempts.isEmpty()) {
            Log.w(TAG, "没有任何可用的搜索关键词")
            return@withContext null
        }

        var lastError: Exception? = null

        for (attempt in attempts) {
            Log.d(TAG, "Search attempt [$attempt]")
            try {
                val songs = KgApi.searchSong(attempt.keyword)
                if (songs.isEmpty()) {
                    Log.d(TAG, "[${attempt.label}] 搜索无结果，尝试下一种关键词")
                    continue
                }

                // 打分排序，取最高分（不再盲取 songs[0]）
                val best = pickBestSong(songs, attempt, localTitle, localArtist)
                if (best == null) {
                    Log.w(TAG, "[${attempt.label}] 没有通过时长与分数校验的歌曲，尝试下一种关键词")
                    continue
                }
                val (bestScore, bestSong) = best
                Log.d(TAG, "[${attempt.label}] 选定歌曲: id=${bestSong.id}, title=[${bestSong.title}], artist=[${bestSong.artistText}], score=$bestScore")

                val lyric = tryFetchLyricsFor(bestSong, attempt.refDurationMs, bestScore)
                if (lyric != null) {
                    return@withContext lyric
                }
                Log.w(TAG, "[${attempt.label}] 该歌曲的所有候选都取不到歌词，尝试下一种关键词")
            } catch (e: IOException) {
                // 网络层错误不当作"没歌词"，记录下来后继续尝试下一种关键词
                lastError = e
                Log.e(TAG, "[${attempt.label}] 网络错误", e)
            } catch (e: LyricsNotFoundError) {
                lastError = e
                Log.i(TAG, "[${attempt.label}] 业务上判定为无歌词: ${e.message}")
            } catch (e: Exception) {
                lastError = e
                Log.e(TAG, "[${attempt.label}] 未预期错误", e)
            }
        }

        Log.i(TAG, "所有搜索关键词均已尝试完毕，未获得歌词。lastError=${lastError?.message}")
        null
    }

    /**
     * 取候选 -> 过滤排序 -> 下载解密。
     *
     * @param refDurationMs 用于候选时长过滤的参考时长；<= 0 表示不启用（例如纯歌名搜索召回的版本可能不同）
     * @return 成功则返回 LRC 文本；本条歌曲无可用歌词则返回 null
     */
    /**
     * 对一次搜索的全部结果打分排序，返回最高分的歌曲；没有合格结果时返回 null。
     */
    private fun pickBestSong(
        songs: List<KgSong>,
        attempt: MatchAttempt,
        localTitle: String,
        localArtist: String,
    ): Pair<Double, KgSong>? {
        val scored = mutableListOf<Pair<Double, KgSong>>()
        for (song in songs) {
            val diff = Math.abs(attempt.refDurationMs - song.durationMs)
            val durationOk = attempt.refDurationMs <= 0L
                    || LyricsMatcher.isDurationMatch(attempt.refDurationMs, song.durationMs, DURATION_TOLERANCE_MS)

            val score = LyricsMatcher.calculateScore(localTitle, localArtist, song.title, song.artistText)

            Log.d(
                TAG,
                "[${attempt.label}] candidate id=${song.id} title=[${song.title}] artist=[${song.artistText}] " +
                        "dur=${song.durationMs} (diff=${diff}ms, durationOk=$durationOk) score=$score",
            )

            if (!durationOk) {
                Log.d(TAG, "[${attempt.label}] 因时长超出 ±${DURATION_TOLERANCE_MS}ms 被丢弃: id=${song.id}")
                continue
            }
            if (score < MIN_SCORE) {
                Log.d(TAG, "[${attempt.label}] 因分数低于 $MIN_SCORE 被丢弃: id=${song.id} score=$score")
                continue
            }
            scored += score to song
        }

        if (scored.isEmpty()) return null

        // 分数降序；同分时选时长更接近本地的
        // 注意：thenBy 的键必须是 Comparable，Long 不是，因此转成 Int
        scored.sortWith(
            compareByDescending<Pair<Double, KgSong>> { it.first }
                .thenBy { Math.abs(attempt.refDurationMs - it.second.durationMs).toInt() },
        )
        return scored.first()
    }

    /**
     * 取候选 -> 过滤排序 -> 下载解密。
     *
     * @param refDurationMs 用于候选时长过滤的参考时长；<= 0 表示不启用（例如纯歌名搜索召回的版本可能不同）
     * @return 成功则返回 LRC 文本；本条歌曲无可用歌词则返回 null
     */
    private suspend fun tryFetchLyricsFor(song: KgSong, refDurationMs: Long, matchScore: Double): String? {
        val candidates = KgApi.getLyricCandidates(song)

        Log.d(TAG, "Step 2 candidates: count=${candidates.size} (song id=${song.id}, matchScore=$matchScore)")
        candidates.forEachIndexed { index, can ->
            Log.d(
                TAG,
                "  candidate#$index id=${can.lyricId}, score=${can.score}, from=${can.productFrom}, " +
                        "dur=${can.durationMs}, singer=[${can.singer}], song=[${can.songName}], nickname=[${can.nickname}]",
            )
        }

        if (candidates.isEmpty()) {
            return null
        }

        // 修复：剔除"纯音乐/伴奏"类占位歌词。
        // 实测酷狗会返回 singer="酷狗音乐"/song="酷狗音乐"/language="纯音乐" 的 UGC 记录，
        // 对有人声的歌曲而言这是错误匹配，会导致用户"下载到纯音乐歌词"。
        val nonPlaceholder = candidates.filterNot {
            LyricsMatcher.isPlaceholderCandidate(it.singer, it.songName, it.nickname)
        }
        if (nonPlaceholder.size < candidates.size) {
            Log.w(TAG, "已剔除 ${candidates.size - nonPlaceholder.size} 条纯音乐/占位候选")
        }
        if (nonPlaceholder.isEmpty()) {
            Log.w(TAG, "全部候选都是纯音乐/占位类，拒绝使用")
            return null
        }

        // 时长过滤：仅当参考时长已知时启用
        val filtered = if (refDurationMs > 0) {
            nonPlaceholder.filter { Math.abs(it.durationMs - refDurationMs) <= DURATION_TOLERANCE_MS }
        } else {
            nonPlaceholder
        }

        if (filtered.isEmpty()) {
            Log.w(TAG, "全部 ${nonPlaceholder.size} 条候选都因时长不符被过滤（参考时长=${refDurationMs}ms）")
            return null
        }
        if (filtered.size < nonPlaceholder.size) {
            Log.d(TAG, "时长过滤：${nonPlaceholder.size} -> ${filtered.size} 条候选")
        }

        // 排序：官方优先（大小写不敏感）> 服务端 score 降序 > 时长差升序
        val sorted = filtered.sortedWith { a, b ->
            val aIsUgc = a.productFrom.equals("ugc", ignoreCase = true)
            val bIsUgc = b.productFrom.equals("ugc", ignoreCase = true)
            when {
                !aIsUgc && bIsUgc -> -1
                aIsUgc && !bIsUgc -> 1
                else -> {
                    val scoreDiff = b.score - a.score
                    if (scoreDiff != 0) {
                        scoreDiff
                    } else {
                        val aDiff = Math.abs(a.durationMs - refDurationMs)
                        val bDiff = Math.abs(b.durationMs - refDurationMs)
                        aDiff.compareTo(bDiff)
                    }
                }
            }
        }

        var downloadCount = 0
        for (can in sorted) {
            if (downloadCount >= MAX_DOWNLOAD_ATTEMPTS) break
            downloadCount++
            try {
                Log.d(
                    TAG,
                    "下载尝试 $downloadCount/$MAX_DOWNLOAD_ATTEMPTS: id=${can.lyricId}, from=${can.productFrom}, " +
                            "kugouScore=${can.score}, dur=${can.durationMs}",
                )

                val resp = KgApi.downloadLyric(can)
                val plainText = if (resp.contentType == 2) {
                    // 纯文本歌词：Base64 直接解码
                    String(android.util.Base64.decode(resp.content, android.util.Base64.DEFAULT), Charsets.UTF_8)
                } else {
                    // KRC 密文：解密后转 LRC
                    val krcPlain = KrcDecoder.decrypt(resp.content)
                    if (krcPlain.isEmpty()) {
                        throw LyricsNotFoundError("KRC 解密结果为空")
                    }
                    KrcParser.krcToLrc(krcPlain)
                }

                if (plainText.isNotEmpty()) {
                    Log.d(TAG, "歌词获取成功: candidate id=${can.lyricId}, 长度=${plainText.length}")
                    Log.d(TAG, "歌词预览: " + plainText.take(100).replace("\n", " | "))
                    return plainText
                }
                Log.w(TAG, "候选 ${can.lyricId} 解密后内容为空")
            } catch (e: LyricsNotFoundError) {
                Log.w(TAG, "候选 ${can.lyricId} 业务失败，尝试下一条: ${e.message}")
            } catch (e: IOException) {
                // 网络错误向上抛，由 fetchLyric 决定是否换关键词重试
                Log.e(TAG, "下载歌词时网络错误", e)
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "候选 ${can.lyricId} 处理失败: ${e.message}")
            }
        }

        Log.w(TAG, "已尝试 $downloadCount 条候选，均未取得歌词")
        return null
    }

    private const val REPLACEMENT_CHAR = '\uFFFD'

    /** 批量匹配用的候选：带分数和时长是否对得上 */
    data class Candidate(
        val song: KgSong,
        val score: Double,
        val durationOk: Boolean,
    )

    /** 一首歌的歌词文本：原文 +（可选）译文轨道 */
    data class LyricTexts(
        val original: String,
        val withTranslation: String? = null,
    )

    /**
     * 搜索并按分数排好序的一批候选。
     *
     * 关键词变体和打分都复用 [fetchLyric] 里那套逻辑，这里只把
     * 「候选 + 分数 + 时长是否吻合」摊开给批量匹配挑。
     */
    suspend fun listSortedSongs(
        songName: String,
        artistName: String,
        durationMs: Long,
    ): List<Candidate> = withContext(Dispatchers.IO) {
        val title = songName.trim()
        val artist = artistName.trim()
        val keyword = if (!LyricsMatcher.isUselessArtist(artist)) "$artist $title" else title
        val songs = try {
            KgApi.searchSong(keyword)
        } catch (e: Exception) {
            emptyList()
        }
        songs.map { s ->
            val score = LyricsMatcher.calculateScore(title, artist, s.title, s.artistText)
            val ok = durationMs <= 0L ||
                LyricsMatcher.isDurationMatch(durationMs, s.durationMs)
            Candidate(s, score, ok)
        }.sortedByDescending { it.score }
    }

    /** 取某个候选的歌词文本，拿不到返回 null */
    suspend fun fetchLyricForChosen(candidate: Candidate, durationMs: Long): LyricTexts? {
        val text = try {
            fetchLyric(candidate.song.title, candidate.song.artistText, durationMs)
        } catch (e: Exception) {
            null
        }
        if (text.isNullOrBlank()) return null
        return LyricTexts(original = text)
    }

}
