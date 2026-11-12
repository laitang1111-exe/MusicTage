package com.example.musictage.utils

import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * 混响处理器：跑在**播放器自己的音频管线**里（Media3 的 `AudioProcessor`）喵~
 *
 * 为什么不用系统音效（`EnvironmentalReverb`）：
 * 实测目标机上只要给音频会话挂上 `AudioEffect`，媒体音量就彻底失控
 * ——恒定最大声、音量键完全没用 ✗ 那是系统音效链/厂商 DSP 的坑，不是参数写错 ✗
 * （AOSP 里也有过 "Audio Effects: fix problems in volume control" 这类修复 ✓）
 *
 * 现在改成在播放器内部处理 PCM 样本：音频的**音量与路由仍旧完全交给系统** ✓
 * 我们只是把样本算一算再交回去，所以音量键、绝对音量、蓝牙音量全都照常 ✓✓
 *
 * 结构是最经典的 Schroeder / Freeverb：预延迟 → 8 条并联梳状滤波器（带阻尼低通）
 * → 4 条串联全通滤波器 ✓
 *
 * 具体用哪套参数由 [ReverbScenario] 决定 ✓
 * 那些参数不是我随手填的，是从华为音乐那份 `effectResource.zip` 的脉冲响应里**实测**出来的
 * （RT60 用 Schroeder 反向积分外推 ✓、预延迟取 JSON 的 `preDelay` ✓、
 * 湿度取 `dryWetGain` ✓、湿声电平逐个场景标定过 ✓），细节见 [ReverbScenario] 的注释 喵~
 */
class HallReverbProcessor : BaseAudioProcessor() {

    companion object {
        /**
         * 日志 tag：排查音效问题**只筛这一个词**就够了 ✓
         *
         * 处理器自己的日志和 `PlaybackService` 里那条开关日志用的是同一个 tag，
         * 所以 Logcat 里搜 `HallReverb` 能把这条链路全捞出来 喵~
         */
        const val TAG = "HallReverb"

        /** 干声增益：小于 1 是为了给残响留 headroom，避免叠加后削顶 ✓ */
        private const val DRY_GAIN = 0.85f

        /**
         * 预延迟线的**最大**长度（毫秒）✓
         *
         * 各场景的预延迟不一样（磁性 6.5ms ~ 歌神 33.3ms ✓），
         * 按最大值一次分配、按场景切换活动长度 ✓ 这样换场景就不用重新分配内存 ✓
         * 40ms 是给 33.3ms 留的余量 ✓
         */
        private const val MAX_PRE_DELAY_MS = 40f

        /**
         * 8 条梳状滤波器的延迟长度。
         *
         * 用的是 Freeverb 的经典值（那是按 44.1kHz 定的），
         * 实际分配时会按 `sampleRate / 44100` 缩放，48kHz 下自动跟着变长 ✓
         */
        private val COMB_DELAYS = intArrayOf(1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617)

        /** 4 条全通滤波器的延迟长度（同样按 44.1kHz 定，用的时候缩放）✓ */
        private val ALLPASS_DELAYS = intArrayOf(556, 441, 341, 225)

        /** 右声道整体偏移这么多采样：让左右残响不相关，听感更宽 ✓ */
        private const val STEREO_SPREAD = 23

        /** 梳状滤波器里的阻尼系数：高频比低频衰得更快，跟真实厅堂一致 ✓ */
        private const val DAMPING = 0.28f

        /** 全通滤波器反馈系数 ✓ */
        private const val ALLPASS_FEEDBACK = 0.5f

        /** 软限幅的膝点：0.7 以下完全不动，往上平滑压到 ±1 ✓ */
        private const val CLIP_KNEE = 0.7f
    }

    /**
     * 一个场景运行需要的全部参数。
     *
     * 做成不可变对象是为了"整块换"：切场景时只换一个引用 ✓
     * 音频线程每次取一份本地引用再跑 ✓ 所以永远不会读到"改到一半"的状态 ✓
     */
    private class Profile(
        val scenario: ReverbScenario,
        /** 活动预延迟长度（采样数，至少 1）✓ */
        val preDelaySamples: Int,
        /** 每条梳状滤波器的反馈量（按该场景实测的 RT60 反推）✓ */
        val combFeedback: FloatArray,
        /** 湿声电平补偿（每个场景单独标定过）✓ */
        val wetTrim: Float,
    )

    /**
     * 界面线程写进来的"想要哪个场景"；null 表示关掉（一个样本都不动，原样透传 ✓）
     *
     * 只写这一个引用 ✓ 真正的换装（清延迟线、按当前采样率重算反馈）交给音频线程做（见 [refreshProfile]）✓
     * 在界面线程换装会跟正在跑的 DSP 抢同一份状态 ✗
     */
    @Volatile
    private var wantedScenario: ReverbScenario? = null

    /** 音频线程当前生效的场景 ✓ */
    private var activeScenario: ReverbScenario? = null

    /** 音频线程当前生效的参数；null = 透传 ✓ */
    private var activeProfile: Profile? = null

    /** [activeProfile] 是否已经按当前采样率建好 ✓ */
    private var profileReady = false

    /** 需要清零延迟线（换场景、冲缓冲时置位）✓ */
    @Volatile
    private var needsClear = true

    /** 当前格式能不能跑 DSP（只支持 16bit PCM 小端）✓ */
    private var dspReady = false

    /** 「混响开始生效」这条日志是否已经打过（每次换场景会重置）✓ */
    @Volatile
    private var dspStartedLogged = false

    /** 「出现非有限值」这条警告是否已经打过（避免刷屏）✓ */
    @Volatile
    private var nonFiniteLogged = false

    private var sampleRate = 0
    private var channels = 0

    /** 预延迟线实际分配的容量（采样数）✓ */
    private var preDelayCapacity = 1

    /** 预延迟线：每个声道一条 ✓ */
    private var preDelay = emptyArray<FloatArray>()
    private var preDelayIndex = IntArray(0)

    /** 梳状滤波器：combs[第几条][声道] ✓ */
    private var combs = emptyArray<Array<FloatArray>>()
    private var combIndex = emptyArray<IntArray>()

    /** 每条梳状滤波器的阻尼低通状态：combStore[第几条][声道] ✓ */
    private var combStore = emptyArray<FloatArray>()

    /** 全通滤波器：allpasses[第几条][声道] ✓ */
    private var allpasses = emptyArray<Array<FloatArray>>()
    private var allpassIndex = emptyArray<IntArray>()

    /**
     * 切换混响场景；传 null 表示关掉（原样透传）✓
     *
     * 可以在播放中途随手切 ✓ 处理器不用重建、音频管线也不用重配 ✓
     */
    fun setScenario(scenario: ReverbScenario?) {
        if (wantedScenario == scenario) return
        wantedScenario = scenario
        // 换场景/关掉都清一次延迟线：否则会把上一个场景的尾巴带过来（串味 ✗）✓
        needsClear = true
        // 下一次真正出声时重新报一条日志，方便在 Logcat 里看每一次切换 ✓
        dspStartedLogged = false
    }

    /**
     * 把 [wantedScenario] 换成音频线程要用的 [Profile]（**只在音频线程调用**）✓
     *
     * 需要重建的两种情况：场景换了 ✓、采样率变了（管线重新配置 ✓）
     */
    private fun refreshProfile() {
        val wanted = wantedScenario
        if (profileReady && activeScenario == wanted) return

        activeScenario = wanted
        activeProfile = wanted?.let { scenario ->
            val scale = sampleRate / 44100f
            Profile(
                scenario = scenario,
                preDelaySamples = (scenario.preDelayMs * sampleRate / 1000f).roundToInt()
                    .coerceIn(1, preDelayCapacity),
                combFeedback = FloatArray(COMB_DELAYS.size) { i ->
                    // 反馈量按 RT60 反推：g = 10^(-3 × 延迟秒数 / RT60) ✓
                    // 这样 8 条尾巴会一起衰减到 -60dB，正好落在该场景实测的 RT60 上 ✓
                    val delaySeconds = (COMB_DELAYS[i] * scale) / sampleRate
                    10f.pow(-3f * delaySeconds / scenario.rt60Seconds)
                },
                wetTrim = scenario.wetTrim,
            )
        }
        profileReady = true
        needsClear = true
    }

    /**
     * 只处理 16bit PCM；其它编码（float、大端）一律原样放行 ✓
     *
     * 返回的格式和输入一样：我们只改样本内容，不改采样率也不改声道数 ✓
     */
    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT ||
            inputAudioFormat.sampleRate <= 0 ||
            inputAudioFormat.channelCount <= 0
        ) {
            dspReady = false
            // 这条日志很关键：dspReady=false 就说明管线给的格式我们处理不了
            // （比如 float / 大端），混响会是"完全不生效"而不是"效果不对" ✗
            Log.w(
                TAG,
                "音频格式不支持，混响将不生效：encoding=${inputAudioFormat.encoding} " +
                        "sampleRate=${inputAudioFormat.sampleRate} channels=${inputAudioFormat.channelCount}"
            )
            return inputAudioFormat
        }
        sampleRate = inputAudioFormat.sampleRate
        channels = inputAudioFormat.channelCount
        allocate()
        dspReady = true
        dspStartedLogged = false
        // 采样率可能变了，梳状反馈量要按新采样率重算 ✓
        profileReady = false
        refreshProfile()
        Log.i(TAG, "音频管线已配置：${sampleRate}Hz ${channels}声道 encoding=16bit → DSP 可用 ✓")
        return inputAudioFormat
    }

    /** 切歌 / seek 时播放器会冲缓冲：把延迟线清掉，免得把上一首的尾巴带过来 ✓ */
    override fun onFlush() {
        needsClear = true
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining <= 0) return

        val output = replaceOutputBuffer(remaining)

        // 格式不支持、或者数据不是整帧：一个样本都不动，原样透传 ✓
        val frameBytes = channels * 2
        if (!dspReady || channels <= 0 || remaining % frameBytes != 0) {
            output.put(inputBuffer)
            output.flip()
            return
        }

        // 场景可能刚被换过：先把这个缓冲要用的参数准备好（这一步必须在音频线程做 ✓）
        refreshProfile()
        val profile = activeProfile
        if (profile == null) {
            // 音效关着：原样透传，一个样本都不改 ✓
            output.put(inputBuffer)
            output.flip()
            return
        }

        if (needsClear) {
            clearState()
            needsClear = false
        }

        // 只在每个开关周期里报一次：这条出现就代表混响**真的在出声**了 ✓
        if (!dspStartedLogged) {
            dspStartedLogged = true
            Log.i(
                TAG,
                "混响开始生效：${profile.scenario.displayName} " +
                        "RT60=${profile.scenario.rt60Seconds}s " +
                        "预延迟=${profile.scenario.preDelayMs}ms " +
                        "湿度=${profile.scenario.wetGain} ✓"
            )
        }

        // PCM 是小端，显式声明一下字节序，别依赖系统默认 ✓
        inputBuffer.order(ByteOrder.LITTLE_ENDIAN)
        output.order(ByteOrder.LITTLE_ENDIAN)

        val frames = remaining / frameBytes
        var nonFinite = false
        for (f in 0 until frames) {
            for (ch in 0 until channels) {
                val dry = inputBuffer.getShort() / 32768f
                // 混响支路先做本场景的电平补偿、再按干湿比混合，否则残响会盖过原声 ✗
                val wet = processSample(ch, dry, profile) * profile.wetTrim * profile.scenario.wetGain
                val mixed = dry * DRY_GAIN + wet
                if (mixed.isFinite()) {
                    output.putShort(toPcm16(softClip(mixed)))
                } else {
                    // DSP 万一算出非有限值，也绝不能让它流到 AudioTrack 去 ✗
                    // 这里静音这一个样本，下面再把所有延迟线清掉重来 ✓
                    nonFinite = true
                    output.putShort(0)
                }
            }
        }
        output.flip()

        if (nonFinite) {
            // 状态已经脏了：整体清零，让混响从干净状态重新开始，而不是一直吐 NaN ✗
            clearState()
            needsClear = false
            if (!nonFiniteLogged) {
                nonFiniteLogged = true
                Log.w(TAG, "DSP 出现非有限值：已静音该样本并重置混响状态（播放不中断）✗")
            }
        }
    }

    /** 按当前采样率/声道数分配延迟线 ✓ */
    private fun allocate() {
        val scale = sampleRate / 44100f

        // 预延迟按**最大**场景一次分配，各场景只是用前多少格不一样 ✓
        preDelayCapacity = max(1, (MAX_PRE_DELAY_MS * sampleRate / 1000f).roundToInt())
        preDelay = Array(channels) { FloatArray(preDelayCapacity) }
        preDelayIndex = IntArray(channels)

        combs = Array(COMB_DELAYS.size) { i ->
            Array(channels) { ch -> FloatArray(delayLength(COMB_DELAYS[i], scale, ch)) }
        }
        combIndex = Array(COMB_DELAYS.size) { IntArray(channels) }
        combStore = Array(COMB_DELAYS.size) { FloatArray(channels) }

        allpasses = Array(ALLPASS_DELAYS.size) { i ->
            Array(channels) { ch -> FloatArray(delayLength(ALLPASS_DELAYS[i], scale, ch)) }
        }
        allpassIndex = Array(ALLPASS_DELAYS.size) { IntArray(channels) }

        needsClear = true
    }

    /** 单条延迟线的长度：按采样率缩放，右声道再加一点偏移做出立体声宽度 ✓ */
    private fun delayLength(base: Int, scale: Float, channel: Int): Int {
        val scaled = (base * scale).roundToInt()
        val spread = if (channel == 1) STEREO_SPREAD else 0
        return max(1, scaled + spread)
    }

    /**
     * 一个样本的混响运算：预延迟 → 8 并联梳状 → 4 串联全通 ✓
     *
     * 全程只用加法/乘法和一个一阶低通，没有除法也没有内存分配，
     * 所以可以安全地跑在播放器的高优先级音频线程上 ✓
     */
    private fun processSample(ch: Int, input: Float, profile: Profile): Float {
        // 预延迟：声音先到、残响后到；长度由场景决定（磁性 6.5ms ~ 歌神 33.3ms ✓）
        val pre = preDelay[ch]
        val preActive = profile.preDelaySamples
        val preIndex = preDelayIndex[ch]
        val delayed = pre[preIndex]
        pre[preIndex] = input
        preDelayIndex[ch] = if (preIndex + 1 >= preActive) 0 else preIndex + 1

        var sum = 0f
        for (i in 0 until combs.size) {
            val line = combs[i][ch]
            val index = combIndex[i][ch]
            val sample = line[index]
            // 阻尼低通：高频能量在反馈里被磨掉，尾巴越往后越"暗" ✓
            combStore[i][ch] = sample * (1f - DAMPING) + combStore[i][ch] * DAMPING
            line[index] = delayed + combStore[i][ch] * profile.combFeedback[i]
            combIndex[i][ch] = if (index + 1 >= line.size) 0 else index + 1
            sum += sample
        }
        var value = sum * (1f / combs.size)

        // 全通滤波器：只打散相位、不改总能量，把残响"抹匀" ✓
        for (i in 0 until allpasses.size) {
            val line = allpasses[i][ch]
            val index = allpassIndex[i][ch]
            val buffered = line[index]
            // ⚠️ 这里必须用**原始输入**写回延迟线：Freeverb 的全通是
            //     buffer[i] = input + bufout × g
            // 如果先执行 `value = buffered - value`（输入被覆盖成输出）再写回，
            // 递推就变成 buffer[i] = bufout × (1 + g) - input ——每次往返放大 1.5 倍 ✗
            // 几百次之后冲到 Infinity，再一相减就是 NaN，
            // 最后 roundToInt(NaN) 抛异常把播放打断（实测就是播 1 秒左右断）✗
            val input = value
            value = buffered - input
            line[index] = input + buffered * ALLPASS_FEEDBACK
            allpassIndex[i][ch] = if (index + 1 >= line.size) 0 else index + 1
        }
        return value
    }

    /** 清空所有延迟线（只在音频线程里调用）✓ */
    private fun clearState() {
        preDelay.forEach { it.fill(0f) }
        preDelayIndex.fill(0)
        combs.forEach { perChannel -> perChannel.forEach { it.fill(0f) } }
        combIndex.forEach { it.fill(0) }
        combStore.forEach { it.fill(0f) }
        allpasses.forEach { perChannel -> perChannel.forEach { it.fill(0f) } }
        allpassIndex.forEach { it.fill(0) }
    }

    /**
     * 软限幅：干声叠残响有可能冲过 ±1，硬切会"咔"，这里在膝点以上平滑压回来 ✓
     *
     * 膝点以下增益正好是 1（不动原声），到 ±1 处斜率降到 0 ✓
     */
    private fun softClip(x: Float): Float {
        val magnitude = if (x < 0f) -x else x
        if (magnitude <= CLIP_KNEE) return x
        val range = 1f - CLIP_KNEE
        val over = magnitude - CLIP_KNEE
        val t = if (over >= range) 1f else over / range
        val shaped = CLIP_KNEE + range * (1f - (1f - t) * (1f - t))
        return if (x < 0f) -shaped else shaped
    }

    /**
     * Float 转成 16bit PCM 样本 ✓
     *
     * `roundToInt()` 遇到 NaN 会**直接抛异常**（`Cannot round NaN value`）✗
     * 而这个异常是在播放线程上抛的，会把整条播放打断（实测就是这么断的 ✗）
     * 所以这里是最后一道兜底：非有限值一律按静音处理，绝不让它抛出去 ✓
     */
    private fun toPcm16(x: Float): Short {
        if (!x.isFinite()) return 0
        val scaled = (x * 32767f).roundToInt()
        return scaled.coerceIn(-32768, 32767).toShort()
    }
}
