package com.example.musictage.utils

import android.media.audiofx.EnvironmentalReverb
import android.util.Log

/**
 * 「音乐厅」混响音效（第一版：先只做一个场景，验证整条链路能不能工作）喵~
 *
 * 走的是 Android 的 `AudioEffect` 体系：把 [EnvironmentalReverb] 挂到播放器的
 * **audioSessionId** 上，播放中的音频就会实时经过它 ✓
 *
 * 参数不是我随手填的，是从华为音乐那条 `yinyueting.bin`（音乐厅脉冲响应）里**实测**出来的喵~
 * - 衰减时间 RT60 ≈ **2.57 秒**（用 Schroeder 反向积分在 -5dB→-25dB 段外推）
 * - 预延迟：JSON 里 `preDelay = 750` 帧 ÷ 48kHz ≈ **15.6 毫秒**
 * - 干湿比：JSON 里 `dryWetGain = 180`（上限 300）→ 偏湿，所以后段残响给得比较足
 * - 扩散/密度：实测 IR 是密集扩散型残响 → 拉满
 *
 * 参数范围都对着 `EnvironmentalReverb` 的源码注释核过了：
 * decayTime [100,20000]ms、reflectionsDelay [0,300]ms、reverbDelay [0,100]ms、
 * roomLevel [-9000,0]、reflectionsLevel [-9000,1000]、reverbLevel [-9000,2000] 毫贝、
 * diffusion/density [0,1000] 千分比、decayHFRatio 千分比 喵~
 *
 * 注意：这是**参数式混响**，只能逼近"像那么回事"，还原不了 IR 里早期反射的花纹；
 * 真想一模一样得走实时卷积那条路（见项目里的解包分析）喵~
 */
class HallReverbEffect {

    companion object {
        private const val TAG = "HallReverbEffect"

        // 注意类型：EnvironmentalReverb 的 setter 有的是 int、有的是 short，
        // 常量类型必须跟着 API 走，否则编译不过 喵~

        /** 衰减时间：实测 RT60 ≈ 2.57 秒，取整成 2500 毫秒 喵~ */
        private const val DECAY_TIME_MS = 2500

        /** 高频衰减比例（千分比，1000 = 与低频同速）：大厅高频掉得更快，取 700 喵~ */
        private const val DECAY_HF_RATIO: Short = 700

        /** 早期反射延迟：对应 JSON 的 preDelay ≈ 15.6ms 喵~ */
        private const val REFLECTIONS_DELAY_MS = 16

        /** 后段残响的起步延迟：大厅常规值 喵~ */
        private const val REVERB_DELAY_MS = 40

        /** 扩散与密度（千分比）拉满：实测 IR 是密集扩散型残响 喵~ */
        private const val DIFFUSION: Short = 1000
        private const val DENSITY: Short = 1000

        /** 各层电平（毫贝，0 = 最大，负得越多越小声）喵~ */
        private const val ROOM_LEVEL_MB: Short = -1200
        private const val REFLECTIONS_LEVEL_MB: Short = -1800
        private const val REVERB_LEVEL_MB: Short = -600
    }

    private var reverb: EnvironmentalReverb? = null
    private var attachedSessionId = 0

    /** 当前是否真的挂上了（设备不支持时会是 false）喵~ */
    val isActive: Boolean get() = runCatching { reverb?.hasControl() == true }.getOrDefault(false)

    /**
     * 按开关挂上/卸下混响。
     *
     * @param sessionId 播放器的音频会话 ID；为 0 表示还没拿到，直接不做事 喵~
     * @param enabled 设置里的开关；关掉时立刻释放，避免多个实例互相叠加 喵~
     */
    fun apply(sessionId: Int, enabled: Boolean) {
        if (!enabled) {
            release()
            return
        }
        if (sessionId == 0) return
        // 已经挂在同一条会话上了，不用重复创建 喵~
        if (reverb != null && attachedSessionId == sessionId) return

        release()

        // 构造器会抛 IllegalArgumentException / UnsupportedOperationException / RuntimeException，
        // 有些设备干脆没实现这个效果，所以创建过程要整个兜住 喵~
        val effect = runCatching { EnvironmentalReverb(0, sessionId) }.getOrElse { error ->
            Log.w(TAG, "这台设备挂不上 EnvironmentalReverb：${error.javaClass.simpleName} ${error.message}")
            return
        }

        // 逐个参数单独兜底：某些 ROM 会对个别参数抛异常，
        // 不能因为一个参数就整条效果都放弃 喵~
        runCatching {
            effect.setDecayTime(DECAY_TIME_MS)
            effect.setDecayHFRatio(DECAY_HF_RATIO)
            effect.setReflectionsDelay(REFLECTIONS_DELAY_MS)
            effect.setReverbDelay(REVERB_DELAY_MS)
            effect.setDiffusion(DIFFUSION)
            effect.setDensity(DENSITY)
            effect.setRoomLevel(ROOM_LEVEL_MB)
            effect.setReflectionsLevel(REFLECTIONS_LEVEL_MB)
            effect.setReverbLevel(REVERB_LEVEL_MB)
            // setEnabled 的返回值是 int（不是 void），Kotlin 不会把它合成属性，所以只能方法调用 喵~
            effect.setEnabled(true)
        }.onFailure { Log.w(TAG, "设置混响参数失败：${it.javaClass.simpleName} ${it.message}") }

        reverb = effect
        attachedSessionId = sessionId
        Log.i(
            TAG,
            "音乐厅混响已挂上：session=$sessionId decay=${DECAY_TIME_MS}ms " +
                    "hasControl=${runCatching { effect.hasControl() }.getOrDefault(false)}"
        )
    }

    /** 卸下音效并释放系统资源（服务销毁、或用户关掉开关时必须调用）喵~ */
    fun release() {
        reverb?.let { effect ->
            runCatching { effect.setEnabled(false) }
            runCatching { effect.release() }
        }
        if (reverb != null) {
            Log.i(TAG, "音乐厅混响已卸下 喵~")
        }
        reverb = null
        attachedSessionId = 0
    }
}
