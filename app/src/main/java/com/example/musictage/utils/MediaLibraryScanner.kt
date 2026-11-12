package com.example.musictage.utils

import android.content.Context
import android.media.MediaMetadataRetriever
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.example.musictage.data.SongEntity
import java.io.File
import java.util.Locale

/**
 * 本地歌曲扫描。
 *
 * 结果由两部分合并而成，靠歌曲 ID 天然去重 喵~
 *
 * 1. **常规部分**：媒体库里 `DURATION >= 30 秒` 的歌（原来的行为，一条 SQL 搞定）。
 * 2. **优先目录部分**：用户点名的目录**整目录递归找文件**，不再依赖媒体库的查询结果。
 *
 * 为什么非要第二部分：实测有些目录（例如酷狗下载目录）里的歌，媒体库**明明有**
 * （系统音乐 App 能扫到），但按我们那条 `DURATION >= 30000` 的查询就是捞不出来——
 * 典型的如媒体库里这首歌的 `duration` 是 0/NULL，而 SQL 里 `NULL >= 30000` 不成立，
 * 于是整行被静默过滤掉。直接去目录里找文件就绕开了这一切 喵~
 *
 * 找到文件之后仍然**优先按路径回媒体库认领 ID**（这样播放、封面、歌词全走原来的路）；
 * 实在认领不到（没收录、pending 之类）才用一个**负数合成 ID**，播放时退回 `file://` 喵~
 */
object MediaLibraryScanner {

    private const val TAG = "MediaLibraryScanner"

    /** 常规扫描的时长下限：短于这个长度的大多是提示音、铃声、片段 喵~ */
    const val MIN_DURATION_MS = 30_000L

    /** 优先目录递归的最大深度，防止有人把根目录整个加进来 喵~ */
    private const val MAX_DEPTH = 8

    /** 认识的音频扩展名（含常见无损/冷门容器）喵~ */
    private val AUDIO_EXTENSIONS = setOf(
        "mp3", "flac", "m4a", "mp4", "m4b", "aac", "wav", "wave", "ogg", "oga", "opus",
        "ape", "wma", "aif", "aiff", "alac", "dsf", "dff", "wv", "tak", "tta", "amr", "3gp",
    )

    /** 媒体库里的一行 喵~ */
    private data class MediaRow(
        val id: Long,
        val title: String,
        val artist: String,
        val durationMs: Long,
        val path: String,
    )

    /**
     * 扫一遍本地歌曲。
     *
     * @param priorityDirs 优先目录，可写相对共享存储根目录的路径（如 `Download/kgmusic`），
     *        也可写绝对路径；不存在或读不了的会被安静跳过 喵~
     */
    fun scan(context: Context, priorityDirs: List<String>): List<SongEntity> {
        val result = LinkedHashMap<Long, SongEntity>()
        val rowByPath = HashMap<String, MediaRow>()

        // ---------- 1. 常规：媒体库里时长合格的那些 ----------
        try {
            queryMediaStore(
                context,
                selection = "${MediaStore.Audio.Media.DURATION} >= ?",
                selectionArgs = arrayOf(MIN_DURATION_MS.toString()),
            ).forEach { row ->
                rowByPath[row.path] = row
                result[row.id] = row.toSongEntity(context)
            }
        } catch (e: Throwable) {
            // 常规查询失败不该让整次扫描失败，优先目录那半边照样要走完 喵~
            Log.e(TAG, "媒体库常规查询失败", e)
        }

        // ---------- 2. 优先目录：整目录找文件 ----------
        for (dir in priorityDirs) {
            val root = resolveDir(context, dir) ?: continue
            if (!root.isDirectory) {
                Log.w(TAG, "优先目录不存在或不是目录：$dir")
                continue
            }

            // 先把媒体库里落在这个目录下的行捞出来（这条查询不带时长条件），
            // 供下面按路径认领 ID 用 喵~
            try {
                queryMediaStore(
                    context,
                    selection = "${MediaStore.Audio.Media.DATA} LIKE ?",
                    selectionArgs = arrayOf("${root.absolutePath}/%"),
                ).forEach { row -> rowByPath[row.path] = row }
            } catch (e: Throwable) {
                Log.e(TAG, "优先目录的媒体库查询失败：$dir", e)
            }

            walkAudioFiles(root).forEach { file ->
                val path = file.absolutePath
                val row = rowByPath[path]
                if (row != null) {
                    // 认领到了：沿用媒体库的 ID（播放/封面/歌词全走原路）。
                    // 时长以文件里读到的为准——库里是 0 的时候正是它被漏掉的原因 喵~
                    val duration = row.durationMs.takeIf { it > 0 } ?: readDurationMs(file)
                    if (isTooShort(duration)) return@forEach
                    result[row.id] = SongEntity(
                        id = row.id,
                        title = row.title.ifBlank { file.nameWithoutExtension },
                        artist = row.artist,
                        duration = duration,
                        filePath = path,
                        lrcPath = findLyricPath(file),
                    )
                } else {
                    // 媒体库看不见它：用负数合成 ID 占位，播放时退回 file:// 喵~
                    val duration = readDurationMs(file)
                    if (isTooShort(duration)) return@forEach
                    val id = syntheticId(path)
                    result[id] = SongEntity(
                        id = id,
                        title = file.nameWithoutExtension,
                        artist = "",
                        duration = duration,
                        filePath = path,
                        lrcPath = findLyricPath(file),
                    )
                }
            }
        }

        Log.i(TAG, "扫描完成：共 ${result.size} 首（优先目录 ${priorityDirs.size} 个）")
        return result.values.toList()
    }

    /**
     * 时长为 0（读不出来）时放行——宁可多收一首，也不要再出现"明明有歌却扫不到" 喵~
     */
    private fun isTooShort(durationMs: Long): Boolean = durationMs in 1 until MIN_DURATION_MS

    private fun MediaRow.toSongEntity(context: Context): SongEntity = SongEntity(
        id = id,
        title = title,
        artist = artist,
        duration = durationMs,
        filePath = path,
        lrcPath = findLyricPath(File(path)),
    )

    /** 同目录同名的 `.lrc`（本 App 保存歌词用的就是这个名字）喵~ */
    private fun findLyricPath(audioFile: File): String? {
        val lrc = File(audioFile.parentFile, audioFile.nameWithoutExtension + ".lrc")
        return if (lrc.exists()) lrc.absolutePath else null
    }

    private fun queryMediaStore(
        context: Context,
        selection: String?,
        selectionArgs: Array<String>?,
    ): List<MediaRow> {
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.DATA,
        )
        val rows = mutableListOf<MediaRow>()
        context.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            null,
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val dataCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)

            while (cursor.moveToNext()) {
                // MediaStore 这几列都允许为 null（尤其 ARTIST / DATA），
                // 以前直接把平台类型塞给非空参数，一个 null 就是 NPE 崩进程 喵~
                val path = cursor.getString(dataCol)
                if (path.isNullOrEmpty()) continue
                rows.add(
                    MediaRow(
                        id = cursor.getLong(idCol),
                        title = cursor.getString(titleCol) ?: "",
                        artist = cursor.getString(artistCol) ?: "",
                        durationMs = cursor.getLong(durationCol),
                        path = path,
                    )
                )
            }
        }
        return rows
    }

    /**
     * 把优先目录解析成绝对路径。
     *
     * 相对路径按共享存储根目录（`/storage/emulated/0`）理解，
     * 这样设置里填 `Download/kgmusic` 比填一长串绝对路径友好 喵~
     */
    @Suppress("DEPRECATION")
    private fun resolveDir(context: Context, dir: String): File? {
        val trimmed = dir.trim().trim('/', ' ')
        if (trimmed.isEmpty()) return null
        val file = File(trimmed)
        return if (file.isAbsolute) file else File(Environment.getExternalStorageDirectory(), trimmed)
    }

    /** 递归找音频文件：跳过隐藏目录/文件（`.nomedia` 也在此列，但优先目录本来就无视它）喵~ */
    private fun walkAudioFiles(root: File, depth: Int = 0): List<File> {
        val out = mutableListOf<File>()
        if (depth > MAX_DEPTH) return out
        val children = root.listFiles() ?: return out
        for (child in children) {
            val name = child.name
            if (name.startsWith(".")) continue
            if (child.isDirectory) {
                out += walkAudioFiles(child, depth + 1)
            } else if (name.substringAfterLast('.', "").lowercase(Locale.US) in AUDIO_EXTENSIONS) {
                out += child
            }
        }
        return out
    }

    /** 从文件里读真实时长（媒体库里是 0 时补上），失败返回 0 喵~ */
    private fun readDurationMs(file: File): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } catch (e: Throwable) {
            // 连 OutOfMemoryError 一起兜住：读不出时长不算错，不能因此崩掉 喵~
            0L
        } finally {
            runCatching { retriever.release() }
        }
    }

    /**
     * 给"媒体库认领不到"的文件生成一个稳定的负数 ID。
     *
     * 用 FNV-1a 64 位哈希（不是 `hashCode()`），冲突概率极低；
     * 取负是为了和媒体库的正 ID 区分开——播放时靠符号决定走 content:// 还是 file:// 喵~
     */
    private fun syntheticId(path: String): Long {
        var hash = -0x340d631b7bdddcdbL // FNV offset basis
        for (byte in path.lowercase(Locale.US).toByteArray()) {
            hash = hash xor (byte.toLong() and 0xFF)
            hash *= 0x100000001b3L // FNV prime
        }
        return if (hash > 0) -hash else if (hash == 0L) -1L else hash
    }
}
