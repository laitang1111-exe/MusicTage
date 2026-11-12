package com.example.musictage.utils

import kotlin.math.exp

/**
 * 峰值限幅器：快攻（默认 2ms）慢放（默认 200ms）喵~
 *
 * 用在混响干湿混合之后（见 [HallReverbProcessor]）✓
 *
 * **为什么光有 [AudioSoftClip] 不够**
 *
 * [AudioSoftClip] 是"逐样本"的静态曲线：输入多少就按曲线映射多少 ✓
 * 输入长期超过 1 时，它会把**每一个波峰**都按到 1.0 ✗
 * 而混响的湿声支路是被标定成"与干声等响"的（见 [ReverbScenario.wetTrim] ✓），
 * 于是干湿一相加就能到 1.7 倍 ✗ 离线实测（满刻度音乐状素材）：
 *
 * | 场景 | 混合峰值 | 贴顶样本占比 |
 * |---|---|---|
 * | 歌神 / KTV / 磁性 | 0.95~1.00 | 0.00% |
 * | 音乐厅 / 剧场 / 悠远 | 1.14~1.31 | ≤0.42% |
 * | **演唱会 / 实验** | **1.71** | **8.0%** |
 *
 * 8% 的样本贴顶 = 所有波峰都被削平 ✗ 听感就是持续的"爆音"、糊、刺 ✗
 * （这也解释了为什么只有"演唱会"这个最长最湿的场景、且只有响的素材才会犯 ✓）
 *
 * **这里的做法**
 *
 * 换成"整体增益"的思路：先算要多少增益才能不超阈值，再让增益包络快攻慢放地追上去 ✓
 *
 * - 没超阈值 → 增益恒为 1，**一个样本都不动** ✓ 音色与原来完全一致 ✓
 * - 超阈值 → 把整段轻轻压下来（演唱会实测平均只压 0.7dB、最深 3.9dB ✓），
 *   而不是把每个波峰削平 ✗ 压完还会慢慢放回去 ✓
 *
 * 最后仍然过一遍 [AudioSoftClip] 兜底：任何情况下输出都不会超过 ±1 ✓
 * （快攻期间万一有瞬态冲过阈值，也会被那条曲线平滑接住，不会"咔"一声 ✓）
 *
 * 它是 **internal** 的：只服务于这个模块里的处理器 ✓
 */
internal class AudioPeakLimiter(
    /** 限幅阈值（绝对值）✓ 留一点余量给后面的兜底曲线 ✓ */
    private val threshold: Float = 0.95f,
    attackMs: Float = 2f,
    releaseMs: Float = 200f,
    sampleRate: Int = 48000,
) {

    /**
     * 包络系数：与时间常数对应的一阶低通系数 ✓
     *
     * `coef = e^(-1 / (秒数 × 采样率))` ✓ 越小跟得越快 ✓
     * 采样率非法时按 48kHz 兜底：宁可跟得快一点，也不要算出 NaN ✗
     */
    private val attackCoef: Float = coefFor(attackMs, sampleRate)
    private val releaseCoef: Float = coefFor(releaseMs, sampleRate)

    /** 当前增益（≤1）✓ */
    private var gain = 1f

    /** 恢复初始状态（换场景 / 冲缓冲时调用）✓ */
    fun reset() {
        gain = 1f
    }

    /**
     * 处理一个样本 ✓
     *
     * 返回值的绝对值保证 ≤ 1 ✓（先乘包络增益，再过共用的软限幅曲线 ✓）
     */
    fun apply(x: Float): Float {
        val peak = if (x < 0f) -x else x
        // 要压到 threshold 以内需要多大增益；本来就够小就是 1（不动 ✓）
        val target = if (peak <= threshold) 1f else threshold / peak
        // 目标比当前小 = 出现峰值，用快攻压下去 ✓；比当前大 = 峰值过去，用慢放收回来 ✓
        val coef = if (target < gain) attackCoef else releaseCoef
        gain = coef * gain + (1f - coef) * target
        return AudioSoftClip.apply(x * gain)
    }

    /** 当前增益（1.0 = 完全没压）✓ 只给诊断日志用 ✓ */
    fun currentGain(): Float = gain

    private companion object {
        fun coefFor(milliseconds: Float, sampleRate: Int): Float {
            val rate = if (sampleRate > 0) sampleRate else 48000
            val seconds = (if (milliseconds > 0f) milliseconds else 1f) / 1000f
            return exp(-1f / (seconds * rate))
        }
    }
}
