package com.example.musictage.utils

/**
 * 音频软限幅：混响处理器和均衡处理器共用喵~
 *
 * 两个处理器都是"算完再交给系统" ✓
 * 干声叠残响、或者均衡里某一段被大幅提升（原版"磁性"在 250Hz 上给了 +18dB ✗）时，
 * 样本很容易冲过 ±1 ✗ 硬切会"咔"一声 ✓ 所以统一在这里平滑压回来 ✓
 *
 * 曲线形状：膝点以下增益正好是 1（完全不动原声 ✓），到 ±1 处斜率降到 0（平滑接住 ✓）✓
 *
 * 它是 **internal** 的：只服务于这个模块里的处理器，不想让界面层误用 ✓
 */
internal object AudioSoftClip {

    /** 膝点：0.7 以下完全不动 ✓ */
    private const val KNEE = 0.7f

    /** 把任意样本压进 ±1，同时尽量保持小信号不变 ✓ */
    fun apply(x: Float): Float {
        val magnitude = if (x < 0f) -x else x
        if (magnitude <= KNEE) return x
        val range = 1f - KNEE
        val over = magnitude - KNEE
        val t = if (over >= range) 1f else over / range
        // 1 - (1 - t)² 形状：在膝点处斜率为 1（接得上 ✓），到 1.0 处斜率为 0（压得住 ✓）
        val shaped = KNEE + range * (1f - (1f - t) * (1f - t))
        return if (x < 0f) -shaped else shaped
    }
}
