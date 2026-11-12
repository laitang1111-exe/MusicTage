package com.example.musictage.utils

/**
 * 可选的混响场景 喵~
 *
 * 每个场景由**两部分**组成，都是从华为音乐那份 `effectResource.zip` 里**搬**过来的 ✓
 *
 * **一、混响** —— 把每个预设的脉冲响应（IR）读进来**实测**出参数，
 * 再交给 [HallReverbProcessor] 那套 Schroeder/Freeverb 结构去还原 ✓
 *
 * - [rt60Seconds]：在该场景对应的脉冲响应上实测
 *   （Schroeder 反向积分，取 -5dB→-25dB 段线性外推 ✓）
 *   注意：原版引擎会用 `impulseTruncate` **截断** IR（只取前 0.58~0.77 秒 ✓），
 *   截断后的尾巴明显比整条 IR 短 ✓ 所以这里量的是**截断窗口内**的衰减 ✓
 *   这样量出来的才是你实际听到的那条尾巴 ✓
 *   实测值：音乐厅 2.59s（不截断 ✓）、悠远 1.12s、歌神 1.34s、演唱会 2.96s、
 *   剧场 0.49s、KTV 0.49s、磁性 1.23s ✓
 * - [preDelayMs]：JSON 里的 `preDelay`（帧 @48kHz）换算成毫秒 ✓
 * - [wetGain]：JSON 里的 `dryWetGain`（0~300）÷ 400 ✓
 *   音乐厅 180 → 0.45 ✓（正好是本项目第一版用手调出来的湿度 ✓ 说明这个换算对得上 ✓）
 * - [wetTrim]：湿声支路的电平补偿 ✓
 *   梳状滤波器组的增益随 RT60 变化（剧场约 +6.7dB、演唱会约 +9.5dB ✗），
 *   不逐个补偿的话，切到"剧场"会突然变小声、切到"演唱会"又会糊掉 ✗
 *   所以每个场景都单独量了一次（Python 跑同一套结构 + float32 ✓），
 *   乘上去之后湿声正好回到 0dB ✓ 这样 [wetGain] 才真的等于"干湿比" ✓
 *
 * **二、均衡** —— 每个场景那 12 段参数均衡 ✓ 交给 [ParametricEqProcessor] ✓
 * 这部分比混响**忠实得多**：双二阶滤波器是数学上精确的 ✓ 不是逼近 ✓
 *
 * 参数换算（这个约定很关键，写清楚方便以后复查 ✓）：
 * - `eqGain` 的 **20 = 0dB** ✓ 由"原声"预设反推 ✓ 它 12 段全是 20 ✓ 而且它就是基准直通 ✓
 * - 增益单位取 **1 个单位 = 0.5dB** ✓ 也就是最终 dB = (JSON 值 − 20) × 0.5 ✓
 *   反推回 JSON 就是 `dB × 2 + 20` ✓
 *   为什么取 0.5 而不是 1：按 1dB 换算时"磁性"会到 **+18dB** ✓ 整条曲线峰值 **+22.6dB** ✗
 *   实测听感就是"全损音质"（软限幅整天压着 ✗）；按 0.5dB 换算它只有 **+9dB** ✓
 *   7 个场景的曲线也都落回正常音效预设的范围 ✓
 * - `eqQ` 的 1414 = 1.414 ✓
 * - `eqType` 的映射是 0=峰值 / 1=低架 / 2=高架 ✓
 *   证据是"留声机"预设：它把 3.5k~8k 四段设成 type 2 压到最低、把 150~1000Hz 六段设成 type 1
 *   压到最低 ✓ 合起来正好是"老唱机"那条带通 ✓ 别的解释都对不上 ✓
 *
 * 增益为 0dB 的段一律**不写进来**（原版拿它们占位 ✓）✓ 省 CPU 也更清楚 ✓
 *
 * 另外 [ParametricEqProcessor] 会按曲线的**最大提升量**自动补一级前置衰减 ✓
 * 所以这里给的 dB 值就是"形状" ✓ 不用担心把限幅器打爆 ✓
 * 代价是大提升的曲线（磁性 ✓）整体响度会低于平曲线（歌神 ✓）——
 * 原版靠每个预设的 `volume` 做场景间响度对齐 ✓ 那层我们暂时没接 ✓
 *
 * 刻意**没搬**的东西：
 * 1. 真·卷积（把 IR 原样卷进来 ✓）——要自己写 FFT + 分段卷积 ✗
 * 2. 原声/录音棚/留声机这 3 个场景：它们用的是引擎**内置**厅堂混响，
 *    资源包里**没有对应 IR** ✓ 所以做不出混响那部分 ✓
 *    （不过它们的均衡曲线是完整的 ✓ 哪天真要补，补上一条 IR 就行 ✓）
 * 3. `volume`（89~200）和 `drcGain`：前者是原版做场景间响度对齐的 ✓
 *    后者只有一个数字、拐点/压缩比/时序全都没有文档 ✗ 照搬只能"听着像" ✗
 *    这里改用统一的软限幅 + 自动 headroom 兜住峰值 ✓
 */
enum class ReverbScenario(
    /** 落盘用的稳定 id：一旦发布就不要再改，改了用户的选择会丢 ✓ */
    val id: String,
    /** 设置界面里显示的名字 ✓ */
    val displayName: String,
    /** 设置界面里的一句话说明 ✓ */
    val description: String,
    /** 实测 RT60（秒）✓ */
    val rt60Seconds: Float,
    /** 预延迟（毫秒）✓ */
    val preDelayMs: Float,
    /** 湿度（0~1）✓ */
    val wetGain: Float,
    /** 湿声电平补偿 ✓ */
    val wetTrim: Float,
    /** 该场景的均衡曲线（增益为 0dB 的段已经剔掉 ✓，单位见文件头 ✓）✓ */
    val eqBands: List<EqBand>,
) {
    /** 音乐厅：默认场景，也是第一版就调通的那个 ✓ */
    YINYUETING(
        id = "yinyueting",
        displayName = "音乐厅",
        description = "大厅残响（尾巴约 2.6 秒）+ 中频微凹、2.1kHz 与高频微抬的厅堂均衡",
        rt60Seconds = 2.59f,
        preDelayMs = 15.6f,
        wetGain = 0.45f,
        wetTrim = 0.3433f,
        eqBands = listOf(
            peak(2100f, +3f, 1.2f),
            peak(215f, -4.5f, 2.0f),
            peak(845f, -4.5f, 2.0f),
            highShelf(4103f, +3f, 1.414f),
        ),
    ),

    /** 悠远：低湿、稍长预延迟，尾巴被原版截到约 0.67 秒 ✓ */
    YOUYUAN(
        id = "youyuan",
        displayName = "悠远",
        description = "空旷悠远（尾巴约 1.1 秒）+ 砍掉 134Hz 以下、削弱中频染色的均衡",
        rt60Seconds = 1.12f,
        preDelayMs = 18.75f,
        wetGain = 0.35f,
        wetTrim = 0.4004f,
        eqBands = listOf(
            lowShelf(134f, -10f, 5.0f),
            peak(181f, -5f, 2.0f),
            peak(1323f, -4f, 2.9f),
            peak(867f, -4.5f, 2.0f),
            peak(7642f, -3f, 2.0f),
            highShelf(14234f, +2f, 1.414f),
        ),
    ),

    /** 歌神：湿度很低（60/300 ✓），主要是给声音加一点空间 ✓ */
    GESHEN(
        id = "geshen",
        displayName = "歌神",
        description = "人声向的淡混响（尾巴约 1.3 秒）+ 80Hz 以下压低、6.3kHz 收敛齿音的均衡",
        rt60Seconds = 1.34f,
        preDelayMs = 33.33f,
        wetGain = 0.15f,
        wetTrim = 0.3884f,
        eqBands = listOf(
            lowShelf(80f, -10f, 1.414f),
            peak(6341f, -5f, 5.2f),
            peak(139f, -4.5f, 3.3f),
            peak(215f, -4f, 4.5f),
            peak(825f, -4f, 2.8f),
        ),
    ),

    /** 演唱会：原版里最湿的一个（300/300 ✓），尾巴最长 ✓ */
    YANCHANGHUI(
        id = "yanchanghui",
        displayName = "演唱会",
        description = "现场感最强（湿度拉满、尾巴约 3.0 秒）+ 收敛低频与 6.3kHz 的现场均衡",
        rt60Seconds = 2.96f,
        preDelayMs = 0f,
        wetGain = 0.75f,
        wetTrim = 0.3343f,
        eqBands = listOf(
            peak(6341f, -5.5f, 5.2f),
            peak(146f, -7.5f, 3.3f),
            peak(825f, -3.5f, 2.8f),
        ),
    ),

    /** 剧场：IR 只有 0.3 秒 ✓ 是很短的厅堂 ✓ */
    JUCHANG(
        id = "juchang",
        displayName = "剧场",
        description = "短促的剧场反射（尾巴约 0.5 秒）+ 2~2.8kHz 提亮、4.1kHz 以上抬高频的均衡",
        rt60Seconds = 0.49f,
        preDelayMs = 30.21f,
        wetGain = 0.45f,
        wetTrim = 0.4621f,
        eqBands = listOf(
            peak(2800f, +3f, 2.0f),
            peak(2100f, +3f, 1.2f),
            peak(215f, -4.5f, 2.0f),
            peak(845f, -4.5f, 2.0f),
            highShelf(4103f, +3f, 1.414f),
        ),
    ),

    /** KTV：和剧场共用同一条 IR ✓ 只是湿度更低、预延迟更短 ✓ */
    KTV(
        id = "ktv",
        displayName = "KTV",
        description = "小房间短混响（尾巴约 0.5 秒）+ 经典的 2.9kHz / 465Hz 双向提升",
        rt60Seconds = 0.49f,
        preDelayMs = 25.63f,
        wetGain = 0.25f,
        wetTrim = 0.4618f,
        eqBands = listOf(
            peak(2896f, +6f, 2.0f),
            peak(465f, +3.5f, 2.0f),
        ),
    ),

    /** 磁性：预延迟最短（6.5ms ✓）、尾巴约 1.2 秒 ✓ */
    CIXING(
        id = "cixing",
        displayName = "磁性",
        description = "贴耳近场残响（尾巴约 1.2 秒）+ 250Hz 加厚、111Hz 以下全砍的暖声均衡",
        rt60Seconds = 1.23f,
        preDelayMs = 6.46f,
        wetGain = 0.175f,
        wetTrim = 0.3931f,
        eqBands = listOf(
            lowShelf(111f, -10f, 5.0f),
            peak(250f, +9f, 2.0f),
            peak(148f, -2.5f, 6.8f),
            peak(582f, +2.5f, 3.5f),
            peak(7005f, -2.5f, 4.1f),
            highShelf(16732f, +3f, 1.414f),
        ),
    ),
    ;

    companion object {
        /** 默认场景：保持"打开音效就是音乐厅"这个既有行为 ✓ */
        val DEFAULT: ReverbScenario = YINYUETING

        /**
         * 按 id 找场景；找不到（比如以后删过场景）就退回默认 ✓
         *
         * 这里刻意不抛异常：偏好数据坏了也不该让播放器起不来 ✗
         */
        fun byId(id: String?): ReverbScenario =
            entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}

/** 峰值滤波：写起来短一点，参数顺序 = 频率 / 增益dB / Q ✓ */
private fun peak(frequencyHz: Float, gainDb: Float, q: Float) =
    EqBand(EqFilterType.PEAKING, frequencyHz, gainDb, q)

/** 低架滤波 ✓ */
private fun lowShelf(frequencyHz: Float, gainDb: Float, q: Float) =
    EqBand(EqFilterType.LOW_SHELF, frequencyHz, gainDb, q)

/** 高架滤波 ✓ */
private fun highShelf(frequencyHz: Float, gainDb: Float, q: Float) =
    EqBand(EqFilterType.HIGH_SHELF, frequencyHz, gainDb, q)
