package com.example.musictage.utils

import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer

/**
 * 干湿分流处理器（「同时播放」的核心）喵~
 *
 * ## 放在链子的【最前面】
 *
 * ```
 * 解码器 → 【本处理器】→ 均衡 → 混响 → 主 AudioTrack（湿声，带音效）
 *              └──────→ 干声 AudioTrack（DryOutput，完全旁通音效）
 * ```
 *
 * 必须在 eq 之前 ✓ 否则拿到的是处理过的声音 ✗ 那就不叫干声了 ✓
 *
 * ## 同步精度：0ms（样本级）✓✓
 *
 * 两路吃的是**同一批 PCM 采样** ✓ 不靠 seek 纠偏 ✗ 不做时钟对齐 ✗
 * 所以不存在漂移 ✓ 只剩两个输出设备之间恒定的几毫秒驱动延迟 ✓
 * 这就是"精确到 10ms 左右"这个要求**唯一靠谱的解法** ✓
 * （两个独立 ExoPlayer 是做不到的 ✗ 详见 DryOutput 文件头的说明 ✓）
 *
 * ## 重写签名照抄 ParametricEqProcessor / HallReverbProcessor 喵~
 * 不凭记忆写 ✗（那是上个项目的教训 ✓）本文件的 override 集合与他们完全一致 ✓
 */
class DualOutputProcessor(private var volume: Float = 0.5f) : BaseAudioProcessor() {

    companion object {
        private const val TAG = "DualOutputProcessor"
    }

    private val dry = DryOutput(volume)

    /** 「同时播放」开关 ✓ false 时本处理器只是原样搬砖 ✓ 一分钱开销都不多花 ✓ */
    @Volatile
    var enabled: Boolean = false
        private set

    private var sampleRate = 0
    private var channels = 0

    /** 开关干声路 ✓ 由 PlaybackService.setDualPlay 调用 ✓ */
    fun setEnabled(on: Boolean) {
        if (on == enabled) return
        enabled = on
        if (on) {
            if (sampleRate > 0) {
                dry.open(sampleRate, channels, 2)
                dry.play()
                Log.i(TAG, "干声路开启：${sampleRate}Hz ${channels}ch，音量 $volume")
            } else {
                // 格式还没确定 ✓ 等 onConfigure 那一趟自己开 ✓
                Log.i(TAG, "干声路已置为开启，等音频格式确定后自动建立")
            }
        } else {
            dry.release()
            Log.i(TAG, "干声路关闭")
        }
    }

    fun setVolume(v: Float) {
        volume = v.coerceIn(0f, 1f)
        dry.setVolume(volume)
    }

    /** 音乐暂停时把干声也停掉 ✓（主路由播放器自己停 ✓ 这里只管我们这条 ✗） */
    fun pauseDry() = dry.pause()

    fun resumeDry() = dry.play()

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT ||
            inputAudioFormat.sampleRate <= 0 ||
            inputAudioFormat.channelCount <= 0
        ) {
            // 与他们两个处理器一致的处理：格式不支持就不生效 ✓ 绝不抛异常 ✗
            Log.w(
                TAG,
                "格式不支持，干声路不生效：encoding=${inputAudioFormat.encoding} " +
                        "sampleRate=${inputAudioFormat.sampleRate} channels=${inputAudioFormat.channelCount}"
            )
            return inputAudioFormat
        }
        sampleRate = inputAudioFormat.sampleRate
        channels = inputAudioFormat.channelCount
        if (enabled) {
            dry.open(sampleRate, channels, 2)
            dry.play()
        }
        return inputAudioFormat
    }

    override fun onFlush() {
        // 主播放器 seek 了 ✓ 干声缓冲里还留着"上一段"的采样 ✗ 必须清掉 ✓
        dry.flush()
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        // ★ 顺序很关键 喵：
        //   1) 先把当前这批采样【复制一份】灌给干声路 ✓
        //      DryOutput.feed 内部只读 duplicate ✓ 不动原 buffer 的 position ✓
        //   2) 再把原 buffer 原样搬进输出 ✓ 交给 eq / reverb 继续处理 ✓
        //   顺序反了的话 ✗ 干声路就会少拿到数据（position 已经被推进了）✗
        if (enabled && remaining > 0) {
            dry.feed(inputBuffer, sampleRate, channels, 2)
        }
        val output = replaceOutputBuffer(remaining)
        output.put(inputBuffer)
        output.flip()
    }
}
