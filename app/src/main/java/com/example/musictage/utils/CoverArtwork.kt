package com.example.musictage.utils

import android.media.MediaMetadataRetriever
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit

/**
 * 把封面写进歌曲文件 喵~
 *
 * 支持两种（就是用户库里主要的两种 ✓）：
 * - **MP3**：往 ID3v2 标签里插一个 `APIC` 帧 ✓
 * - **FLAC**：加一个 `PICTURE` 元数据块 ✓
 *
 * 三条铁律（都是用户明确要求的 ✓）：
 * 1. **已经有封面就跳过** ✓ 不覆盖原有数据 喵~
 * 2. **先写临时文件再原子替换** ✓ 中途出错绝不留下半个坏文件 喵~
 * 3. **写不进去也不硬来** ✓ 返回 [EmbedResult] 让上层决定要不要退化成外挂图 喵~
 *
 * 另外两个刻意的取舍 喵~
 * - **不解析已有帧**：新 APIC 帧直接插在原有帧之前，原有帧（含 padding 与音频）逐字节照抄 ✓
 *   这样不需要实现完整的 ID3 解析器，也就不会因为解析出错而毁掉用户的标签 喵~
 * - **扩展头 / 去同步标记的标签不碰** ✓ 那两种情况插入位置会算错 ✗ 直接判失败、走外挂图更安全 喵~
 */
object CoverArtwork {

    private const val TAG = "CoverArtwork"

    /** 小于这个字节数的"图片"多半是错误页或占位图 ✗ 直接不要 喵~ */
    private const val MIN_COVER_BYTES = 1024

    /** 一次替换的结果 ✓ */
    enum class EmbedResult {
        /** 写进去了 ✓ */
        EMBEDDED,

        /** 文件里本来就有封面，按约定跳过 ✓ */
        ALREADY_HAS_ONE,

        /** 格式不支持（不是 MP3/FLAC ✓）*/
        UNSUPPORTED_FORMAT,

        /** 写失败了（权限、损坏、异常 ✓）→ 上层可以退化成外挂图 喵~ */
        FAILED,
    }

    // ---------------------------------------------------------------- 下载

    /**
     * 下载封面图 ✓ 返回 (字节, MIME) ✓ 失败返回 null 喵~
     *
     * 单独建一个短超时的 client ✓ 不借用歌词接口那个（那边超时更长 ✓ 下载图片没必要等那么久 喵~）
     */
    suspend fun download(url: String): Pair<ByteArray, String>? = withContext(Dispatchers.IO) {
        if (url.isBlank()) return@withContext null
        runCatching {
            val client = OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .build()
            client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "封面下载失败：http ${resp.code} $url")
                    return@use null
                }
                val bytes = resp.body?.bytes()
                if (bytes == null || bytes.size < MIN_COVER_BYTES) {
                    Log.w(TAG, "封面数据太小，丢弃：${bytes?.size ?: 0} 字节")
                    return@use null
                }
                val mime = resp.body?.contentType()?.toString()?.substringBefore(';')?.trim()
                    ?.takeIf { it.startsWith("image/") }
                    ?: sniffMime(bytes)
                bytes to mime
            }
        }.getOrNull()
    }

    /** 服务端没给 MIME 时按文件头猜 ✓ 猜不出就按 JPEG（最常见 ✓）喵~ */
    private fun sniffMime(bytes: ByteArray): String = when {
        bytes.size >= 8 && bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte() -> "image/png"
        bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() -> "image/jpeg"
        bytes.size >= 12 && String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" -> "image/webp"
        else -> "image/jpeg"
    }

    // ---------------------------------------------------------------- 检查

    /** 文件里是否已经有内嵌封面 ✓（只读标签，不动文件 ✓）喵~ */
    fun hasEmbeddedCover(file: File): Boolean {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            retriever.embeddedPicture != null
        } catch (t: Throwable) {
            // 解析不了就当"没有"✓ 后面写不进去也会退化成外挂图 ✓ 不会误判成"已有"而漏掉封面 喵~
            false
        } finally {
            runCatching { retriever.release() }
        }
    }

    // ---------------------------------------------------------------- 嵌入

    /** 按扩展名分派 ✓ 已有封面则跳过 ✓ 出错返回失败让上层兜底 喵~ */
    fun embed(file: File, bytes: ByteArray, mime: String): EmbedResult {
        if (!file.isFile) return EmbedResult.FAILED
        if (hasEmbeddedCover(file)) return EmbedResult.ALREADY_HAS_ONE
        return try {
            when (file.extension.lowercase()) {
                "mp3" -> if (embedId3(file, bytes, mime)) EmbedResult.EMBEDDED else EmbedResult.FAILED
                "flac" -> if (embedFlacPicture(file, bytes, mime)) EmbedResult.EMBEDDED else EmbedResult.FAILED
                else -> EmbedResult.UNSUPPORTED_FORMAT
            }
        } catch (t: Throwable) {
            Log.w(TAG, "写封面失败：${file.name}", t)
            EmbedResult.FAILED
        }
    }

    /**
     * 嵌入失败时的兜底：在歌曲旁边落一张外挂图 喵~
     *
     * 文件名用「歌名.cover.jpg」而不是固定的 cover.jpg ✓
     * 同一个文件夹里几十首歌共用 cover.jpg 会互相覆盖 ✗ 那还不如不存 喵~
     */
    fun saveSidecar(file: File, bytes: ByteArray): File? = runCatching {
        val out = File(file.parentFile, file.nameWithoutExtension + ".cover.jpg")
        out.writeBytes(bytes)
        out
    }.getOrNull()

    // ---------------------------------------------------------------- MP3 / ID3v2

    /**
     * 往 ID3v2 里插一个 APIC 帧 喵~
     *
     * 帧插在**原有帧之前** ✓ 原有帧与音频逐字节照抄 ✓
     * 标签体大小是两个版本都用的 synchsafe 整数 ✓ 而帧大小 v2.3 是普通大端、v2.4 才是 synchsafe ✗
     * 这两个规则不一样，写错的话播放器会读不出封面 喵~
     */
    private fun embedId3(file: File, cover: ByteArray, mime: String): Boolean {
        val head = ByteArray(10)
        RandomAccessFile(file, "r").use { raf ->
            if (raf.length() < 10) {
                // 短得连一个标签头都放不下：肯定没有 ID3 ✓ 直接按"新建标签"处理 喵~
                head.fill(0)
            } else {
                raf.readFully(head)
            }
        }
        val hasTag = head[0] == 'I'.code.toByte() &&
                head[1] == 'D'.code.toByte() &&
                head[2] == '3'.code.toByte()
        if (hasTag) {
            val flags = head[5].toInt() and 0xFF
            // 扩展头（0x40）/ 去同步（0x80）：插入位置会算错 ✗ 宁可不写 喵~
            if ((flags and 0x40) != 0 || (flags and 0x80) != 0) {
                Log.w(TAG, "ID3 带扩展头或去同步标记，跳过嵌入：${file.name}")
                return false
            }
        }
        val version = if (hasTag) (head[3].toInt() and 0xFF).coerceIn(3, 4) else 3
        val oldTagSize = if (hasTag) synchsafeToInt(head, 6) else 0
        val apic = buildApicFrame(cover, mime, version)
        val newTagBodySize = oldTagSize + apic.size

        val header = ByteArray(10)
        header[0] = 'I'.code.toByte()
        header[1] = 'D'.code.toByte()
        header[2] = '3'.code.toByte()
        header[3] = version.toByte()
        header[4] = 0
        header[5] = 0                 // 清掉所有 flags：去同步/扩展头都不保留 ✓
        writeSynchsafe(header, 6, newTagBodySize)

        val temp = File(file.parentFile, file.name + ".cover.tmp")
        FileOutputStream(temp).use { out ->
            out.write(header)
            out.write(apic)
            if (oldTagSize > 0) {
                RandomAccessFile(file, "r").use { raf ->
                    raf.seek(10)
                    copyRange(raf, out, oldTagSize.toLong())
                    copyRest(raf, out)
                }
            } else {
                FileInputStream(file).use { it.copyTo(out, COPY_BUFFER) }
            }
        }
        replaceFile(temp, file)
        return true
    }

    /** 组装 APIC 帧 ✓ 文本编码统一用 ISO-8859-1（MIME 与描述都是 ASCII ✓）喵~ */
    private fun buildApicFrame(cover: ByteArray, mime: String, version: Int): ByteArray {
        val mimeBytes = mime.ifBlank { "image/jpeg" }.toByteArray(Charsets.ISO_8859_1)
        val body = ByteArrayOutputStream()
        body.write(0x00)                       // 文本编码 ✓
        body.write(mimeBytes)
        body.write(0x00)                       // MIME 结束符 ✓
        body.write(0x03)                       // 图片类型：Cover (front) ✓
        body.write(0x00)                       // 空描述 ✓
        body.write(cover)
        val payload = body.toByteArray()

        val frame = ByteArray(10 + payload.size)
        frame[0] = 'A'.code.toByte()
        frame[1] = 'P'.code.toByte()
        frame[2] = 'I'.code.toByte()
        frame[3] = 'C'.code.toByte()
        if (version >= 4) {
            writeSynchsafe(frame, 4, payload.size)
        } else {
            writeInt32BE(frame, 4, payload.size)
        }
        // frame[8] / frame[9] 是帧标志，保持 0 ✓
        payload.copyInto(frame, 10)
        return frame
    }

    // ---------------------------------------------------------------- FLAC

    /**
     * 给 FLAC 加一个 PICTURE 元数据块 喵~
     *
     * FLAC 的结构是 "fLaC" + 一串元数据块 + 音频帧 ✓
     * 每个块头 4 字节：第 1 字节的最高位是"是否最后一块"✓ 低 7 位是块类型（6 = PICTURE ✓）
     * 所以做法是：把原最后一块的"最后"标记清掉 ✓ 再把新块接在后面并标为最后 ✓ 最后照抄音频 喵~
     */
    private fun embedFlacPicture(file: File, cover: ByteArray, mime: String): Boolean {
        class Block(val offset: Long, val total: Int, val type: Int, val isLast: Boolean)

        val blocks = mutableListOf<Block>()
        var audioStart = 0L
        RandomAccessFile(file, "r").use { raf ->
            if (raf.length() < 8) return false
            val magic = ByteArray(4)
            raf.readFully(magic)
            if (String(magic, Charsets.US_ASCII) != "fLaC") return false

            var pos = 4L
            while (true) {
                raf.seek(pos)
                val h = ByteArray(4)
                raf.readFully(h)
                val isLast = (h[0].toInt() and 0x80) != 0
                val type = h[0].toInt() and 0x7F
                if (type == 127) return false        // 非法块类型：文件不干净 ✗ 不碰 喵~
                val len = ((h[1].toInt() and 0xFF) shl 16) or
                        ((h[2].toInt() and 0xFF) shl 8) or
                        (h[3].toInt() and 0xFF)
                if (type == 6) return false          // 已经有 PICTURE：理论上前面已跳过 ✓ 这里再兜一层 喵~
                blocks += Block(pos, 4 + len, type, isLast)
                pos += 4 + len
                if (isLast) break
            }
            audioStart = pos
        }
        if (blocks.isEmpty()) return false

        val picture = buildFlacPictureBlock(cover, mime, isLast = true)
        val temp = File(file.parentFile, file.name + ".cover.tmp")
        FileOutputStream(temp).use { out ->
            out.write("fLaC".toByteArray(Charsets.US_ASCII))
            RandomAccessFile(file, "r").use { raf ->
                for (b in blocks) {
                    raf.seek(b.offset)
                    val data = ByteArray(b.total)
                    raf.readFully(data)
                    // 原最后一块要让出"最后"的位置 ✓ 否则元数据区就提前结束了 ✗
                    if (b.isLast) data[0] = (data[0].toInt() and 0x7F).toByte()
                    out.write(data)
                }
                // 新块必须在元数据区末尾、音频之前 ✓ 顺序错了整首歌就废了 喵~
                out.write(picture)
                raf.seek(audioStart)
                copyRest(raf, out)
            }
        }
        replaceFile(temp, file)
        return true
    }

    /** 组装一个 FLAC PICTURE 块 ✓ 宽高色深都填 0（= 未知 ✓ 这是规范允许的 ✓）喵~ */
    private fun buildFlacPictureBlock(cover: ByteArray, mime: String, isLast: Boolean): ByteArray {
        val mimeBytes = mime.ifBlank { "image/jpeg" }.toByteArray(Charsets.US_ASCII)
        val body = ByteArrayOutputStream()
        writeInt32BE(body, 3)                 // 图片类型：front cover ✓
        writeInt32BE(body, mimeBytes.size)
        body.write(mimeBytes)
        writeInt32BE(body, 0)                 // 描述长度 0 ✓
        writeInt32BE(body, 0)                 // 宽未知 ✓
        writeInt32BE(body, 0)                 // 高未知 ✓
        writeInt32BE(body, 0)                 // 色深未知 ✓
        writeInt32BE(body, 0)                 // 颜色数未知 ✓
        writeInt32BE(body, cover.size)
        body.write(cover)

        val payload = body.toByteArray()
        val block = ByteArray(4 + payload.size)
        block[0] = (((if (isLast) 0x80 else 0x00) or 6)).toByte()
        block[1] = ((payload.size shr 16) and 0xFF).toByte()
        block[2] = ((payload.size shr 8) and 0xFF).toByte()
        block[3] = (payload.size and 0xFF).toByte()
        payload.copyInto(block, 4)
        return block
    }

    // ---------------------------------------------------------------- 小工具

    private const val COPY_BUFFER = 64 * 1024

    /** 从当前位置精确抄 [count] 字节 喵~ */
    private fun copyRange(raf: RandomAccessFile, out: FileOutputStream, count: Long) {
        val buf = ByteArray(COPY_BUFFER)
        var left = count
        while (left > 0) {
            val n = raf.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (n <= 0) break
            out.write(buf, 0, n)
            left -= n
        }
    }

    /** 把剩下的内容（音频数据 ✓）全部抄完 喵~ */
    private fun copyRest(raf: RandomAccessFile, out: FileOutputStream) {
        val buf = ByteArray(COPY_BUFFER)
        var n = raf.read(buf)
        while (n > 0) {
            out.write(buf, 0, n)
            n = raf.read(buf)
        }
    }

    /**
     * 用临时文件原子替换原文件 喵~
     *
     * 先走 `Files.move(REPLACE_EXISTING)`（API 26+ 可用 ✓ 语义就是原子的覆盖 ✓）
     * 失败再退回 `renameTo` ✓ 两条都失败就把临时文件删掉并抛错，交由上层退化成外挂图 喵~
     */
    private fun replaceFile(temp: File, target: File) {
        try {
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            return
        } catch (t: Throwable) {
            Log.w(TAG, "Files.move 失败，退回 renameTo", t)
        }
        if (!temp.renameTo(target)) {
            temp.delete()
            throw IOException("无法替换原文件：${target.name}")
        }
    }

    /** ID3v2 的 synchsafe 整数（每字节只用低 7 位 ✓）读取 喵~ */
    private fun synchsafeToInt(b: ByteArray, offset: Int): Int =
        ((b[offset].toInt() and 0x7F) shl 21) or
                ((b[offset + 1].toInt() and 0x7F) shl 14) or
                ((b[offset + 2].toInt() and 0x7F) shl 7) or
                (b[offset + 3].toInt() and 0x7F)

    /** ID3v2 的 synchsafe 整数写入 喵~ */
    private fun writeSynchsafe(b: ByteArray, offset: Int, value: Int) {
        b[offset] = ((value shr 21) and 0x7F).toByte()
        b[offset + 1] = ((value shr 14) and 0x7F).toByte()
        b[offset + 2] = ((value shr 7) and 0x7F).toByte()
        b[offset + 3] = (value and 0x7F).toByte()
    }

    /** 普通大端 32 位写入（ID3v2.3 的帧大小、FLAC 的字段都用它 ✓）喵~ */
    private fun writeInt32BE(b: ByteArray, offset: Int, value: Int) {
        b[offset] = ((value shr 24) and 0xFF).toByte()
        b[offset + 1] = ((value shr 16) and 0xFF).toByte()
        b[offset + 2] = ((value shr 8) and 0xFF).toByte()
        b[offset + 3] = (value and 0xFF).toByte()
    }

    private fun writeInt32BE(out: ByteArrayOutputStream, value: Int) {
        out.write((value shr 24) and 0xFF)
        out.write((value shr 16) and 0xFF)
        out.write((value shr 8) and 0xFF)
        out.write(value and 0xFF)
    }
}
