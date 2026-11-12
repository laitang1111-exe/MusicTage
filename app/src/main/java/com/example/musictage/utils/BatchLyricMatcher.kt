package com.example.musictage.utils

import android.content.Context
import android.util.Log
import com.example.musictage.data.AppDatabase
import com.example.musictage.data.SongEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

/**
 * ★★★ 批量匹配歌词 喵~
 *
 * 王桑需求：加个批量匹配歌词功能，默认下载封面，在设置里可以打开
 *
 * 它和「单首匹配」最大的区别：
 *     单首：搜索 → 把候选列出来 → 【用户自己挑】✓
 *     批量：搜索 → 【程序自动挑】→ 直接下载 ✗ 不能逐个问用户
 *
 * 所以这里必须有一道【防错闸】：
 *     一、只接受 durationOk 的候选（时长对不上的一律不要）
 *     二、分数低于 [MIN_SCORE] 的一律跳过
 *     三、宁可跳过，也不写一份错歌词进去 ✗
 *
 * 各零件都是现成的，本类只负责【串起来】：
 *     · KugouLyricRepository.listSortedSongs   搜索 + 打分 + 排序
 *     · KugouLyricRepository.fetchLyricForChosen  取歌词文本
 *     · CoverArtwork.download / embed          下封面 + 写进音频文件
 *     · SongDao.updateLrcPath / updateCoverPath   回写数据库
 */
object BatchLyricMatcher {

    private const val TAG = "BatchLyricMatcher"

    /**
     * ★ 自动选候选的最低分数
     *
     * 定在 0.55：酷狗对同名歌的常见干扰（翻唱、Live、DJ 版）通常落在 0.3~0.5，
     * 真正的原曲一般 0.7 往上。宁可漏掉几首让王桑手动配，也不要写错 ✗
     */
    private const val MIN_SCORE = 0.55

    /** ★ 每首之间歇一下：几百首连着打，接口会限流 喵~ */
    private const val DELAY_MS = 400L

    /** 进度快照，每处理一首回调一次 */
    data class Progress(
        val total: Int,
        val done: Int,
        val currentTitle: String,
        val ok: Int,
        val skipped: Int,
        val failed: Int,
    )

    /** 单首的处理结果 */
    data class Item(val title: String, val artist: String, val status: String)

    /** 整批的结果 */
    data class Result(
        val ok: Int,
        val skipped: Int,
        val failed: Int,
        val items: List<Item>,
    )

    /**
     * 跑一批。
     *
     * @param songs 要处理的歌（调用方已经筛过，一般是「没有歌词」的那些）
     * @param downloadCover 是否顺便下载封面（来自设置里的「下载封面」开关）
     * @param onProgress 每首回调一次，用来刷界面上的进度
     * @param isCancelled 每次循环问一次，用户点了「停止」就收工
     */
    suspend fun run(
        context: Context,
        songs: List<SongEntity>,
        downloadCover: Boolean,
        onProgress: (Progress) -> Unit,
        isCancelled: () -> Boolean = { false },
    ): Result = withContext(Dispatchers.IO) {
        val dao = AppDatabase.getDatabase(context).songDao()

        var ok = 0
        var skipped = 0
        var failed = 0
        var done = 0
        val items = mutableListOf<Item>()

        Log.i(TAG, "批量匹配开始：共 ${songs.size} 首，下载封面=$downloadCover")

        for (song in songs) {
            if (isCancelled()) {
                Log.i(TAG, "用户中止，已处理 $done 首")
                break
            }
            done++
            onProgress(Progress(songs.size, done, song.title, ok, skipped, failed))

            val audio = File(song.filePath)
            if (!audio.isFile) {
                failed++
                items += Item(song.title, song.artist, "文件不存在")
                continue
            }

            try {
                // ① 搜索：关键词变体 + 打分 + 排序都在里面做完了
                val candidates = KugouLyricRepository.listSortedSongs(
                    song.title,
                    song.artist,
                    song.duration,
                )

                // ② 自动选：只从【时长对得上】的里面挑【分数最高】的
                val best = candidates
                    .filter { it.durationOk }
                    .maxByOrNull { it.score }

                if (best == null) {
                    skipped++
                    items += Item(song.title, song.artist, "无匹配")
                    delay(DELAY_MS)
                    continue
                }
                if (best.score < MIN_SCORE) {
                    skipped++
                    items += Item(
                        song.title, song.artist,
                        "分数过低 " + String.format("%.2f", best.score),
                    )
                    delay(DELAY_MS)
                    continue
                }

                // ③ 取歌词文本（附带翻译轨道的话优先带上）
                val texts = KugouLyricRepository.fetchLyricForChosen(best, song.duration)
                if (texts == null) {
                    skipped++
                    items += Item(song.title, song.artist, "无歌词")
                    delay(DELAY_MS)
                    continue
                }

                // ④ 落盘：写在音频文件旁边，同名 .lrc
                val text = texts.withTranslation ?: texts.original
                if (text.isBlank()) {
                    skipped++
                    items += Item(song.title, song.artist, "歌词为空")
                    delay(DELAY_MS)
                    continue
                }
                val dest = File(audio.parentFile, audio.nameWithoutExtension + ".lrc")
                dest.writeText(text, Charsets.UTF_8)
                dao.updateLrcPath(song.id, dest.absolutePath)

                // ⑤ 封面：开关开着才做
                //    封面失败【不影响】歌词 —— 两件事互相独立 ✓
                var coverNote = ""
                if (downloadCover) {
                    val url = best.song.imageUrl
                    if (url.isNullOrBlank()) {
                        coverNote = "（无封面地址）"
                    } else {
                        try {
                            val pair = CoverArtwork.download(url)
                            if (pair == null) {
                                coverNote = "（封面下载失败）"
                            } else {
                                val res = CoverArtwork.embed(audio, pair.first, pair.second)
                                if (res == CoverArtwork.EmbedResult.EMBEDDED || res == CoverArtwork.EmbedResult.ALREADY_HAS_ONE) {
                                    // 内嵌成功，数据库里也记一份路径（列表页显示用）
                                    dao.updateCoverPath(song.id, audio.absolutePath)
                                } else {
                                    coverNote = "（封面嵌入失败）"
                                }
                            }
                        } catch (e: Exception) {
                            coverNote = "（封面异常）"
                            Log.w(TAG, "封面处理失败: ${song.title}", e)
                        }
                    }
                }

                ok++
                items += Item(song.title, song.artist, "成功$coverNote")
                Log.i(TAG, "成功: ${song.artist} - ${song.title}  分数=${best.score}  $coverNote")

            } catch (e: Exception) {
                failed++
                items += Item(song.title, song.artist, "失败 " + e.javaClass.simpleName)
                Log.w(TAG, "处理失败: ${song.title}", e)
            }

            delay(DELAY_MS)
        }

        onProgress(Progress(songs.size, done, "", ok, skipped, failed))
        Log.i(TAG, "批量匹配结束：成功 $ok，跳过 $skipped，失败 $failed")
        Result(ok, skipped, failed, items)
    }
}
