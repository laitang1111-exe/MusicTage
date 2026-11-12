package com.example.musictage.utils

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 干声输出（"同时播放"的第二路）喵~
 *
 * ## 为什么不是第二个 ExoPlayer ✗
 *
 * 要求是两路同步到 **10ms 左右** ✓ 而两个独立 ExoPlayer **做不到** ✗：
 *   - 各有各的解码线程、缓冲、时钟 ✗ `currentPosition` 本身粒度与抖动就有几十毫秒 ✗
 *   - 靠 seekTo 纠偏会断音爆音 ✗ 想压到 10ms 就得高频 seek ✗ → 连续咔哒声 ✗✗
 *
 * ## 正确做法：同一批 PCM 喂两路 ✓
 *
 * 解码后的 PCM 在**同一条链**上被复制一份 ✓ 一份继续走 均衡→混响→主输出（湿声 ✓）
 * 一份直接写进这里的第二个 AudioTrack（干声 ✗ 完全旁通音效 ✓）
 * 两路吃的是**同一批采样** ✓ → 同步误差 **0ms（样本级）** ✓✓
 * 只剩两个输出设备之间**恒定的几毫秒驱动延迟** ✓ 恒定 ✓ 不漂移 ✓
 *
 * ## 关键设计：阻塞写 ✓
 *
 * [feed] 用**阻塞式** AudioTrack.write ✗ 不是非阻塞 ✗
 * 这样干声路的消费速度会**自然锁住**上游的解码速度 ✓
 * 永远不需要 seek ✗ 也永远不会漂移 ✓✓ 这是本方案能稳在 10ms 内的根本原因 ✓
 *
 * ## 用法（接在服务里）
 *
 * ```
 * val dry = DryOutput(volume = 0.5f)
 * // 在音频处理器链的【最前面】调用（务必在 eq/reverb 之前 ✓ 否则拿到的就不是干声 ✗）
 * dry.feed(inputBuffer, format)      // inputBuffer 原样不动 ✓ 内部只读它的副本 ✓
 * dry.pause() / dry.play() / dry.flush() / dry.release()
 * ```
 *
 * @param volume 干声增益 ✓ 两路相加会过载 ✗ 默认 0.5 ✓
 */
class DryOutput(private var volume: Float = 0.5f) {

    companion object {
        private const val TAG = "DryOutput"
    }

    private var track: AudioTrack? = null
    private var sampleRate = 0
    private var channelCount = 0
    private var bytesPerSample = 2
    private var usable = false

    /** 干声音量 ✓ 运行时随时可调 ✓ */
    fun setVolume(v: Float) {
        volume = v.coerceIn(0f, 1f)
        track?.let { runCatching { it.setVolume(volume) } }
    }

    fun isOpen(): Boolean = track != null

    /**
     * 按 PCM 格式开（或重开）干声轨 喵~
     *
     * 采样率/声道数协商不上时会把 [usable] 置 false ✓ 调用方据此跳过 ✓ 不会崩 ✗
     */
    fun open(sampleRateHz: Int, channels: Int, bytesPerSampleIn: Int) {
        if (track != null && sampleRate == sampleRateHz &&
            channelCount == channels && bytesPerSample == bytesPerSampleIn
        ) {
            return   // 格式没变 ✓ 继续用 ✓
        }
        release()
        sampleRate = sampleRateHz
        channelCount = channels
        bytesPerSample = bytesPerSampleIn

        val channelMask = if (channels == 1) AudioFormat.CHANNEL_OUT_MONO
        else AudioFormat.CHANNEL_OUT_STEREO
        val encoding = when (bytesPerSample) {
            1 -> AudioFormat.ENCODING_PCM_8BIT
            4 -> AudioFormat.ENCODING_PCM_FLOAT
            else -> AudioFormat.ENCODING_PCM_16BIT
        }
        val minBuf = AudioTrack.getMinBufferSize(sampleRateHz, channelMask, encoding)
        if (minBuf <= 0) {
            Log.w(TAG, "干声轨参数不被支持：${sampleRateHz}Hz/${channels}ch/${bytesPerSample * 8}bit，已旁路")
            usable = false
            return
        }
        // 缓冲给到最小值的 2 倍 ✓ 够稳又不至于引入太大延迟 ✓
        val bufSize = minBuf * 2
        try {
            val t = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(encoding)
                        .setSampleRate(sampleRateHz)
                        .setChannelMask(channelMask)
                        .build()
                )
                .setBufferSizeInBytes(bufSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            if (t.state != AudioTrack.STATE_INITIALIZED) {
                Log.w(TAG, "干声轨初始化失败，已旁路")
                runCatching { t.release() }
                usable = false
                return
            }
            t.setVolume(volume)
            t.play()
            track = t
            usable = true
            Log.i(TAG, "干声轨已开启：${sampleRateHz}Hz ${channels}ch ${bytesPerSample * 8}bit，缓冲 $bufSize 字节，音量 $volume")
        } catch (t: Throwable) {
            Log.w(TAG, "干声轨创建异常：${t.javaClass.simpleName}，已旁路")
            usable = false
        }
    }

    /**
     * 灌一批 PCM 喵~
     *
     * ★ 只读 [src] 的**副本** ✓ 绝不改动 src 的 position/limit ✗
     *   所以上游可以继续把 src 交给 eq/reverb ✓ 两路互不干扰 ✓✓
     *
     * ★ 阻塞写 ✓ 上游解码速度会被干声轨的消费速度锁住 ✓ 因此永不漂移 ✓
     */
    fun feed(src: ByteBuffer, sampleRateHz: Int, channels: Int, bytesPerSampleIn: Int) {
        if (!usable && track == null) return
        open(sampleRateHz, channels, bytesPerSampleIn)
        val t = track ?: return
        if (!usable) return

        // 复制一份 ✓ 原 buffer 的 position 不动 ✓
        val dup = src.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val n = dup.remaining()
        if (n <= 0) return
        val bytes = ByteArray(n)
        dup.get(bytes)

        // 干声音量：16bit 定点自己乘 ✓ 省得每条轨都走一遍 mixer ✓
        if (bytesPerSample == 2 && volume != 1f) {
            var i = 0
            while (i + 1 < bytes.size) {
                val lo = bytes[i].toInt() and 0xFF
                val hi = bytes[i + 1].toInt()
                var s = ((hi shl 8) or lo).toShort().toInt()
                s = (s * volume).toInt().coerceIn(-32768, 32767)
                bytes[i] = (s and 0xFF).toByte()
                bytes[i + 1] = ((s shr 8) and 0xFF).toByte()
                i += 2
            }
            runCatching { t.write(bytes, 0, bytes.size) }
        } else {
            runCatching { t.write(bytes, 0, bytes.size) }
        }
    }

    fun play() {
        runCatching { track?.play() }
    }

    fun pause() {
        runCatching { track?.pause() }
    }

    /** 主播放器 seek 时同步清空干声缓冲 ✓ 否则旧数据会拖在后面 ✗ */
    fun flush() {
        runCatching { track?.pause(); track?.flush(); track?.play() }
    }

    fun release() {
        track?.let {
            runCatching { it.pause(); it.flush(); it.release() }
        }
        track = null
        usable = false
        Log.i(TAG, "干声轨已释放")
    }
}
