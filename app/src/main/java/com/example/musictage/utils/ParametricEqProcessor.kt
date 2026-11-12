package com.example.musictage.utils

import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 一段均衡的类型，对应原版预设 JSON 里的 `eqType` 喵~
 *
 * 这个映射不是猜的，是从两份预设反推出来的 ✓
 * - 原版那把 `原声`（YuanSheng）是**基准直通**：12 段增益全是 20 ✓ 干湿 0 ✓ 音量 100 ✓
 *   所以 **增益 20 = 0dB** ✓
 * - 原版 `留声机`（LiuShengji）要的是"老唱机"那种窄带音色：
 *   它把 3.5k/4k/6k/8k 四段设成 **eqType 2** 且增益压到最低（砍高频 ✓），
 *   又把 150/250/500/800/1000Hz 六段设成 **eqType 1** 且增益压到最低（砍低频 ✓）
 *   合起来正好是一条带通 ✓ 只有"低架 + 高架"这个解释说得通 ✓
 * - 其余预设里 type 0 全用在中频段、并且带着正负增益 ✓ = 峰值滤波 ✓
 */
enum class EqFilterType {
    /** eqType = 0：峰值滤波（中频段微调 ✓） */
    PEAKING,

    /** eqType = 1：低架滤波（例如"磁性"砍掉 111Hz 以下 ✓） */
    LOW_SHELF,

    /** eqType = 2：高架滤波（例如"留声机"砍掉 3.5kHz 以上 ✓） */
    HIGH_SHELF,
}

/**
 * 一段参数均衡 ✓
 *
 * @param frequencyHz 中心频率 / 架式滤波的拐点频率 ✓
 * @param gainDb 增益（dB）；**已经换算成最终 dB** ✓
 *   换算规则见 [ReverbScenario] 顶部：JSON 里的 20 = 0dB，且 1 个单位 = **0.5dB** ✓
 * @param q Q 值；原版 JSON 里 1414 就是 1.414 ✓
 */
class EqBand(
    val type: EqFilterType,
    val frequencyHz: Float,
    val gainDb: Float,
    val q: Float,
)

/**
 * 参数均衡处理器：每个场景一条最多 12 段的双二阶滤波器链 喵~
 *
 * 它和 [HallReverbProcessor] 跑在同一条音频管线里（Media3 的 `AudioProcessor` ✓），
 * 所以同样**不碰系统音效** ✓ 音量、路由照旧全交给系统 ✓
 * 顺序上排在混响**前面**（见 PlaybackService 里的处理器数组 ✓）：
 * 先塑形、再叠残响 ✓ 这样均衡抬起来的峰值会经过混响那条软限幅 ✓ 不会硬削 ✗
 *
 * ## 自动 headroom（很重要 ✓）
 *
 * 原版每个预设都带一层 **DRC**（动态范围控制 ✓ JSON 里的 `drcGain` ✓），
 * 所以它敢把曲线拉到 +20dB 还能听 ✓ 而 DRC 只有一个数字、拐点/压缩比/时序全无文档 ✗
 * 我们没搬它 ✓ 那就必须自己解决头寸问题：
 *
 * 实测"磁性"那条曲线峰值 **+22.6dB**（单位换算后 +11.7dB ✓），
 * 直接把峰值顶到满刻度的好几倍 ✗ → 软限幅会**每一个峰值都在压** ✓
 * 听感就是"一直在使劲压 + 谐波失真" = 用户说的"全损音质" ✗
 *
 * 所以这里在滤波**前**加一级前置衰减：先把整条曲线在可听频段的**最大提升量**算出来
 * （见 [maxBoostDb] ✓），再按它反相衰减 ✓
 * 效果是：**曲线的形状（也就是音色）一点没变** ✓ 但峰值回落到 0dB 附近 ✓
 * 限幅器只在真正的过峰处轻轻接一下，不再整天压着 ✓
 *
 * 代价要说清楚：像"磁性"这种大提升的曲线，整体响度会明显低于"歌神"这种平曲线 ✓
 * 原版是靠每个预设的 `volume` 做**场景间响度对齐**的 ✓（磁性 89、歌神 141 ✓）
 * 那个我们暂时没接 ✓ 觉得切场景音量跳，就是这一层没补上 ✓
 *
 * ## 其它
 *
 * 系数用的是 RBJ（Audio EQ Cookbook）那套经典公式 ✓
 * 运算用转置直接 II 型（每段每声道只要两个状态变量 ✓），
 * 全程没有内存分配、没有除法 ✓ 可以安全跑在播放器的音频线程上 ✓
 */
class ParametricEqProcessor : BaseAudioProcessor() {

    companion object {
        /**
         * 日志 tag 沿用混响处理器那个 ✓
         *
         * 故意的：整条音效链路只筛 `HallReverb` 一个词就能看全 ✓
         * 分两个 tag 反而要筛两次，排查时更容易漏 ✓
         */
        private val TAG = HallReverbProcessor.TAG

        /** 增益小于这个值就当"没用这一段"，直接跳过（省 CPU ✓） */
        private const val MIN_GAIN_DB = 0.05f

        /** 频率下限保护：低于这个频率的频点没有意义，夹住免得算出怪系数 ✗ */
        private const val MIN_FREQUENCY_HZ = 10f

        /** 头寸评估用的频段范围与网格密度（和 Python 预演脚本保持一致 ✓） */
        private const val EVAL_MIN_HZ = 20f
        private const val EVAL_MAX_HZ = 20000f
        private const val EVAL_POINTS = 480
    }

    /**
     * 一组双二阶滤波器：系数 + 每个声道各自的状态 ✓
     *
     * 系数只跟采样率有关 ✓ 状态跟信号有关 ✓ 所以换采样率要整组重建 ✓
     */
    private class Biquad(
        val b0: Float,
        val b1: Float,
        val b2: Float,
        val a1: Float,
        val a2: Float,
        channels: Int,
    ) {
        /** 转置直接 II 型的两个状态：s1/s2[声道] ✓ */
        val s1 = FloatArray(channels)
        val s2 = FloatArray(channels)

        fun reset() {
            s1.fill(0f)
            s2.fill(0f)
        }

        /** 处理一个样本（就地更新状态 ✓）✓ */
        fun process(x: Float, ch: Int): Float {
            val y = b0 * x + s1[ch]
            s1[ch] = b1 * x - a1 * y + s2[ch]
            s2[ch] = b2 * x - a2 * y
            return y
        }
    }

    /**
     * 一条算好的曲线：滤波器组 + 前置衰减 ✓
     *
     * 做成不可变对象整块换 ✓ 音频线程取一份本地引用再跑 ✓ 不会读到改到一半的状态 ✓
     */
    private class EqCurve(
        val filters: Array<Biquad>,
        /** 前置衰减（线性增益）：把曲线抬起来的峰值拉回 0dB 附近 ✓ */
        val preGain: Float,
        /** 该曲线的最大提升量（dB），只用来打日志和排查 ✓ */
        val maxBoostDb: Float,
    )

    /** 界面线程写进来的目标曲线；null = 关掉（原样透传 ✓）✓ */
    @Volatile
    private var wantedBands: List<EqBand>? = null

    /** 音频线程当前生效的曲线；null = 透传 ✓ */
    private var activeCurve: EqCurve? = null

    /** 当前生效曲线对应哪一份（用来判断要不要重建 ✓）✓ */
    private var activeBands: List<EqBand>? = null

    /** 曲线是否已经按当前采样率建好 ✓ */
    private var curveReady = false

    private var sampleRate = 0
    private var channels = 0
    private var dspReady = false

    /** 「均衡开始生效」这条日志是否打过（每次换曲线重置 ✓）✓ */
    @Volatile
    private var startedLogged = false

    /**
     * 设置均衡曲线；传 null 或空列表表示关掉（原样透传 ✓）✓
     *
     * 可以在播放中途随手切 ✓ 真正的系数换装放在音频线程（见 [refreshCurve]）✓
     */
    fun setBands(bands: List<EqBand>?) {
        val wanted = bands?.takeIf { it.isNotEmpty() }
        if (wantedBands == wanted) return
        wantedBands = wanted
        startedLogged = false
        // 换曲线要把滤波器状态清掉，否则旧曲线残留的振铃会带过来 ✗
        activeCurve?.filters?.forEach { it.reset() }
    }

    /**
     * 把 [wantedBands] 变成音频线程要用的曲线（**只在音频线程调用**）✓
     *
     * 需要重建的两种情况：曲线换了 ✓、采样率变了 ✓
     */
    private fun refreshCurve() {
        val wanted = wantedBands
        if (curveReady && activeBands == wanted) return

        activeBands = wanted
        val filters = wanted?.mapNotNull { band ->
            // 增益几乎为 0 的段直接跳过：它对声音没有影响，白白吃 CPU ✗
            if (abs(band.gainDb) < MIN_GAIN_DB) return@mapNotNull null
            buildBiquad(band)
        }?.toTypedArray()

        activeCurve = if (filters == null || filters.isEmpty()) {
            null
        } else {
            // 自动 headroom：按曲线的最大提升量做前置衰减 ✓
            val boost = maxBoostDb(filters, wanted)
            EqCurve(filters, 10f.pow(-boost / 20f), boost)
        }
        curveReady = true
        activeCurve?.filters?.forEach { it.reset() }

        activeCurve?.let {
            Log.i(
                TAG,
                "均衡曲线就绪：${it.filters.size} 段，峰值 +${it.maxBoostDb}dB " +
                        "→ 前置衰减 ${20f * log10(it.preGain)}dB ✓"
            )
        }
    }

    /** 按 RBJ 公式算一段双二阶的系数 ✓ */
    private fun buildBiquad(band: EqBand): Biquad? {
        val nyquist = sampleRate / 2f
        val freq = band.frequencyHz.coerceIn(MIN_FREQUENCY_HZ, nyquist * 0.98f)
        val q = band.q.coerceAtLeast(0.1f)

        val a = 10f.pow(band.gainDb / 40f)          // 幅度因子 A = 10^(dB/40) ✓
        val w0 = 2f * PI.toFloat() * freq / sampleRate
        val cosW = cos(w0)
        val sinW = sin(w0)
        val alpha = sinW / (2f * q)

        val b0: Float
        val b1: Float
        val b2: Float
        val a0: Float
        val a1: Float
        val a2: Float
        when (band.type) {
            EqFilterType.PEAKING -> {
                b0 = 1f + alpha * a
                b1 = -2f * cosW
                b2 = 1f - alpha * a
                a0 = 1f + alpha / a
                a1 = -2f * cosW
                a2 = 1f - alpha / a
            }

            EqFilterType.LOW_SHELF -> {
                val sqrtA = sqrt(a)
                val twoSqrtAlpha = 2f * sqrtA * alpha
                b0 = a * ((a + 1f) - (a - 1f) * cosW + twoSqrtAlpha)
                b1 = 2f * a * ((a - 1f) - (a + 1f) * cosW)
                b2 = a * ((a + 1f) - (a - 1f) * cosW - twoSqrtAlpha)
                a0 = (a + 1f) + (a - 1f) * cosW + twoSqrtAlpha
                a1 = -2f * ((a - 1f) + (a + 1f) * cosW)
                a2 = (a + 1f) + (a - 1f) * cosW - twoSqrtAlpha
            }

            EqFilterType.HIGH_SHELF -> {
                val sqrtA = sqrt(a)
                val twoSqrtAlpha = 2f * sqrtA * alpha
                b0 = a * ((a + 1f) + (a - 1f) * cosW + twoSqrtAlpha)
                b1 = -2f * a * ((a - 1f) + (a + 1f) * cosW)
                b2 = a * ((a + 1f) + (a - 1f) * cosW - twoSqrtAlpha)
                a0 = (a + 1f) - (a - 1f) * cosW + twoSqrtAlpha
                a1 = 2f * ((a - 1f) - (a + 1f) * cosW)
                a2 = (a + 1f) - (a - 1f) * cosW - twoSqrtAlpha
            }
        }

        if (a0 == 0f) return null
        // 系数一旦算出 NaN/Inf，这一段的整条滤波器都会吐垃圾 ✗ 直接放弃这一段 ✓
        val inv = 1f / a0
        val biquad = Biquad(b0 * inv, b1 * inv, b2 * inv, a1 * inv, a2 * inv, channels)
        if (!biquad.b0.isFinite() || !biquad.b1.isFinite() || !biquad.b2.isFinite() ||
            !biquad.a1.isFinite() || !biquad.a2.isFinite()
        ) {
            Log.w(TAG, "均衡系数异常，已跳过这一段：${band.type} ${band.frequencyHz}Hz ${band.gainDb}dB")
            return null
        }
        return biquad
    }

    /**
     * 估算这条曲线在可听频段上的**最大提升量**（dB）✓
     *
     * 做法：在 20Hz~20kHz 上取对数等分的网格，**再补上每一段的中心频率**
     * （免得高 Q 的窄峰刚好落在两个网格点之间被漏掉 ✗），
     * 逐点算出整条链的幅度响应，取最大值 ✓
     *
     * 只算幅度平方、不开根号：比大小和转 dB 都用得上它 ✓ 省一次 sqrt ✓
     *
     * 这段逻辑和 `_eq_headroom.py` 的预演脚本是同一套，两边算出来的数应当一致 ✓
     */
    private fun maxBoostDb(filters: Array<Biquad>, bands: List<EqBand>): Float {
        val ratio = EVAL_MAX_HZ / EVAL_MIN_HZ
        val points = ArrayList<Float>(EVAL_POINTS + bands.size)
        for (i in 0 until EVAL_POINTS) {
            points.add(EVAL_MIN_HZ * ratio.pow(i.toFloat() / (EVAL_POINTS - 1)))
        }
        bands.forEach { points.add(it.frequencyHz.coerceIn(EVAL_MIN_HZ, EVAL_MAX_HZ)) }

        var best = 1f
        for (freq in points) {
            val w = 2.0 * PI * freq / sampleRate
            val cos1 = cos(w).toFloat()
            val sin1 = sin(w).toFloat()
            val cos2 = cos(2.0 * w).toFloat()
            val sin2 = sin(2.0 * w).toFloat()

            var mag2 = 1f
            for (biquad in filters) {
                val numRe = biquad.b0 + biquad.b1 * cos1 + biquad.b2 * cos2
                val numIm = -(biquad.b1 * sin1 + biquad.b2 * sin2)
                val denRe = 1f + biquad.a1 * cos1 + biquad.a2 * cos2
                val denIm = -(biquad.a1 * sin1 + biquad.a2 * sin2)
                val den = denRe * denRe + denIm * denIm
                if (den <= 1e-12f) continue
                mag2 *= (numRe * numRe + numIm * numIm) / den
            }
            if (mag2 > best) best = mag2
        }
        return if (best > 1f) 10f * log10(best) else 0f
    }

    /** 只处理 16bit PCM；其它编码一律原样放行 ✓ */
    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT ||
            inputAudioFormat.sampleRate <= 0 ||
            inputAudioFormat.channelCount <= 0
        ) {
            dspReady = false
            Log.w(
                TAG,
                "均衡：音频格式不支持，将不生效：encoding=${inputAudioFormat.encoding} " +
                        "sampleRate=${inputAudioFormat.sampleRate} channels=${inputAudioFormat.channelCount}"
            )
            return inputAudioFormat
        }
        sampleRate = inputAudioFormat.sampleRate
        channels = inputAudioFormat.channelCount
        dspReady = true
        // 采样率可能变了：系数与 headroom 都要按新采样率重算 ✓
        curveReady = false
        refreshCurve()
        return inputAudioFormat
    }

    /** 切歌 / seek 时播放器会冲缓冲：把滤波器状态清掉 ✓ */
    override fun onFlush() {
        activeCurve?.filters?.forEach { it.reset() }
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining <= 0) return

        val output = replaceOutputBuffer(remaining)
        val frameBytes = channels * 2

        if (!dspReady || channels <= 0 || remaining % frameBytes != 0) {
            output.put(inputBuffer)
            output.flip()
            return
        }

        refreshCurve()
        val curve = activeCurve
        if (curve == null) {
            output.put(inputBuffer)
            output.flip()
            return
        }

        if (!startedLogged) {
            startedLogged = true
            Log.i(TAG, "均衡开始生效：${curve.filters.size} 段 ✓")
        }

        inputBuffer.order(ByteOrder.LITTLE_ENDIAN)
        output.order(ByteOrder.LITTLE_ENDIAN)

        val preGain = curve.preGain
        val filters = curve.filters
        val frames = remaining / frameBytes
        for (f in 0 until frames) {
            for (ch in 0 until channels) {
                val dry = inputBuffer.getShort() / 32768f
                // 先衰减（自动 headroom ✓）再走滤波器：音色不变，峰值不越界 ✓
                var y = dry * preGain
                for (i in 0 until filters.size) {
                    y = filters[i].process(y, ch)
                }
                // 均衡把某些频段抬起来之后仍可能冲过 ±1，统一走那道软限幅 ✓
                val limited = AudioSoftClip.apply(y)
                if (limited.isFinite()) {
                    output.putShort(toPcm16(limited))
                } else {
                    // 兜底：非有限值绝不流到 AudioTrack ✗
                    output.putShort(0)
                    filters.forEach { it.reset() }
                }
            }
        }
        output.flip()
    }

    /** Float 转成 16bit PCM 样本；非有限值按静音处理（roundToInt(NaN) 会抛异常 ✗）✓ */
    private fun toPcm16(x: Float): Short {
        if (!x.isFinite()) return 0
        val scaled = (x * 32767f).roundToInt()
        return scaled.coerceIn(-32768, 32767).toShort()
    }
}
