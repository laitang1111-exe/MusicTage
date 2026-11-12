/*
 * ══════════════════════════════════════════════════════════════════════════
 * ★★★ ChilledWindows 特效 —— 数据与状态机 喵~
 *
 * 王桑 2026-10-16 需求：
 *     播放器内，固定布局，在播放器的界面底层播放视频，
 *     用原作者设定的 150 多个反转代码进行反转，
 *     反转完毕后应该会有个缩小，然后就剩底层播放的视频画面，
 *     播放完毕后会恢复原样。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 数据来源（全部从 ChilledWindows.exe 里原样挖出来的，一个数都没改）
 *
 *     .ctor 的 IL 里：
 *         ldtoken 0x04000020 → RuntimeHelpers.InitializeArray → flipTimes  (96)
 *         ldtoken 0x04000021 → RuntimeHelpers.InitializeArray → flipTimes1 (14)
 *         ldtoken 0x04000022 → RuntimeHelpers.InitializeArray → flipTimes2 (40)
 *     三个 token 指向 FieldRVA 表（文件偏移 0x1394，每行 RVA(4)+Field(2)）
 *     三个 RVA：0x4340CC / 0x43424C / 0x434284
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 原版的驱动方式（从 Dt_Tick 的 IL 反汇编读出来的）
 *
 *     frameIndex = (int)Math.Floor(mediaElement.Position.TotalMilliseconds / 33.33333)
 *     ▲ 注意：时钟是【视频播放位置】✗ 不是定时器计数 ✓
 *       所以帧率对齐问题根本不存在 —— 屏幕 60/90/120Hz 都无所谓 ✓
 *
 *     每次 Tick：
 *         if (frameIndex == 438) refreshFirstFlips  = false
 *         if (frameIndex == 585) refreshSecondFlips = false
 *         if (frameIndex == 622) { 起 4 个收尾动画 }
 *
 *         if (refreshFirstFlips && flipTimes[i0] <= frameIndex) { i0++; 翻上层 }
 *         if (refreshSecondFlips) {
 *             if (flipTimes1[i1] <= frameIndex) { i1++; 翻下层 }
 *             if (flipTimes2[i2] <= frameIndex) { i2++; 翻中层 }
 *         }
 *
 *     翻转动作：ScaleX 在 +1.0 和 -1.0 之间取反（水平镜像）
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 时间轴（帧号 ÷ 30 = 秒）
 *
 *     0.00 ~  4.20 秒   静止
 *     4.20 ~ 14.57 秒   上层高速闪（90 个点，间隔约 117ms）
 *     14.60 秒          上层停（frameIndex 438）
 *     14.67 ~ 19.50 秒  中层（34 个点，约 147ms）+ 下层（8 个点，约 623ms）
 *     19.50 秒          中下层停（frameIndex 585）
 *     20.73 秒          收尾动画开始（平移 + 缩小，各 500ms）
 *     22.17 秒          某开关置 true
 *     42.00 秒          显示文字 + 视频继续
 * ══════════════════════════════════════════════════════════════════════════
 */
package com.example.musictage

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** 原版每帧的毫秒数（Dt_Tick 的 IL_0014 那个常量） */
const val CHILLED_FRAME_MS = 33.33333

/** 用视频位置算帧号 —— 和原版一模一样的公式，一个字没改 ✓ */
fun chilledFrameIndex(positionMs: Long): Int =
    Math.floor(positionMs / CHILLED_FRAME_MS).toInt()

// ══════════════════════════════════════════════════════════════════════════
// 三个时间点数组（96 + 14 + 40 = 150 个，正是王桑说的「150 多个反转代码」）
//
// ★ 末尾的 0 是原版的【数组长度填充】✗ 不是有效时间点
//   原版靠阶段终点（438 / 585）先把 refreshXxxFlips 置 false，
//   所以填充值永远读不到 ✓ 这里照搬同样的做法 ✓
// ══════════════════════════════════════════════════════════════════════════

/** 上层用（原版 flipTimes，96 个，有效 90 个，126~437） */
val CHILLED_TIMES_TOP = intArrayOf(
    126, 129, 133, 136, 140, 143, 147, 150, 155, 158, 162, 165,
    169, 172, 176, 179, 184, 187, 191, 194, 198, 201, 205, 208,
    213, 216, 220, 223, 227, 230, 234, 237, 242, 245, 249, 252,
    256, 259, 263, 266, 271, 274, 278, 281, 285, 286, 288, 292,
    295, 297, 300, 303, 307, 310, 314, 317, 321, 324, 329, 332,
    336, 339, 343, 346, 350, 353, 355, 358, 361, 365, 368, 372,
    375, 379, 382, 387, 390, 394, 397, 401, 404, 408, 411, 416,
    419, 423, 426, 430, 433, 437, 0, 0, 0, 0, 0, 0,
)

/** 下层用（原版 flipTimes1，14 个，有效 8 个，454~585） */
val CHILLED_TIMES_BOTTOM = intArrayOf(
    454, 476, 497, 512, 531, 548, 569, 585, 0, 0, 0, 0, 0, 0,
)

/** 中层用（原版 flipTimes2，40 个，有效 34 个，440~585） */
val CHILLED_TIMES_MID = intArrayOf(
    440, 443, 449, 454, 456, 460, 468, 476, 481, 486, 497, 501,
    504, 506, 512, 514, 518, 522, 527, 531, 533, 536, 540, 548,
    552, 557, 561, 563, 569, 572, 576, 580, 582, 585, 0, 0,
    0, 0, 0, 0,
)

// ══════════════════════════════════════════════════════════════════════════
// 阶段终点（原版 Dt_Tick 里的 ldc.i4 常量）
// ══════════════════════════════════════════════════════════════════════════
/** 上层停 */
const val CHILLED_STOP_TOP = 438
/** 中下层停 */
const val CHILLED_STOP_MID = 585
/** 收尾动画开始 */
const val CHILLED_SHRINK_AT = 622
/** 某开关置 true（原版给 label 加内容） */
const val CHILLED_MARK_AT = 665
/** 显示文字 + 视频继续 */
const val CHILLED_END_AT = 1260

// ══════════════════════════════════════════════════════════════════════════
// 收尾动画的参数（原版 IL_0099~01BE 的四个 DoubleAnimation）
// ══════════════════════════════════════════════════════════════════════════
/** 时长 500ms */
const val CHILLED_SHRINK_MS = 500

/** 位移系数：X = 屏宽 × 这个值（原版 0.13817330210772832 × 3.0） */
const val CHILLED_TX_FACTOR = 0.13817330210772832f * 3.0f

/** 位移系数：Y = 屏高 × 这个值（原版 0.3541666666666667 × 3.0） */
const val CHILLED_TY_FACTOR = 0.3541666666666667f * 3.0f

/** 缩到多少（原版 1.0 → 0.3） */
const val CHILLED_SHRINK_TO = 0.3f

/**
 * ★★★ ChilledWindows 特效的状态机 喵~
 *
 * 用法：
 *     val fx = remember { ChilledState() }
 *     // 每帧喂一次视频位置（-1 表示视频还没准备好）
 *     fx.step(videoPositionMs)
 *     // 读三个翻转位去设置 graphicsLayer
 *     if (fx.flipTop) ... else ...
 *
 * 全部字段都是 Compose 的 mutableStateOf，改了会自动重组 ✓
 */
class ChilledState {
    /** 特效是否在跑 */
    var running by mutableStateOf(false)
        private set

    /** 三层各自的翻转位（true = 已镜像，scaleX = -1） */
    var flipTop by mutableStateOf(false)
        private set
    var flipMid by mutableStateOf(false)
        private set
    var flipBottom by mutableStateOf(false)
        private set

    /** 三层各自的数组索引 */
    var idxTop by mutableIntStateOf(0)
        private set
    var idxMid by mutableIntStateOf(0)
        private set
    var idxBottom by mutableIntStateOf(0)
        private set

    /** 当前帧号（给界面显示 / 调试用） */
    var frameIndex by mutableIntStateOf(0)
        private set

    /** 两个阶段的开关（对应原版的 refreshFirstFlips / refreshSecondFlips） */
    var refreshTop by mutableStateOf(false)
        private set
    var refreshMidBottom by mutableStateOf(false)
        private set

    /** 收尾动画的进度：0 = 没开始，1 = 已经缩完 */
    var shrinkProgress by mutableFloatStateOf(0f)
        private set

    /** 收尾动画是否已经走完（走完就该只留底层视频了） */
    var shrunk by mutableStateOf(false)
        private set

    /** 界面那一层是否已经完全透明（收尾之后再等一会儿） */
    var uiHidden by mutableStateOf(false)
        private set

    /** 视频播完了 */
    var finished by mutableStateOf(false)
        private set

    private var lastFrame = -1
    private var shrinkStartFrame = -1

    /** 开始演出 */
    fun start() {
        running = true
        flipTop = false
        flipMid = false
        flipBottom = false
        idxTop = 0
        idxMid = 0
        idxBottom = 0
        frameIndex = 0
        refreshTop = true
        refreshMidBottom = true
        shrinkProgress = 0f
        shrunk = false
        uiHidden = false
        finished = false
        lastFrame = -1
        shrinkStartFrame = -1
    }

    /** 收工（用户长按退出 / 视频播完） */
    fun stop() {
        running = false
        flipTop = false
        flipMid = false
        flipBottom = false
        refreshTop = false
        refreshMidBottom = false
        shrinkProgress = 0f
        shrunk = false
        uiHidden = false
    }

    /**
     * 每帧喂一次视频位置 喵~
     *
     * @param positionMs 视频当前播放位置（毫秒）；传 -1 表示还没准备好
     */
    fun step(positionMs: Long) {
        if (!running || positionMs < 0) return
        val f = chilledFrameIndex(positionMs)
        frameIndex = f
        if (f == lastFrame) return          // ★ 同一帧不重复处理 ✓
        lastFrame = f

        // ── 阶段切换（对应原版的三个 if）──
        if (f >= CHILLED_STOP_TOP) refreshTop = false
        if (f >= CHILLED_STOP_MID) refreshMidBottom = false
        if (f >= CHILLED_SHRINK_AT && shrinkStartFrame < 0) {
            shrinkStartFrame = f
        }
        // 收尾动画进度：500ms ≈ 15 帧
        if (shrinkStartFrame >= 0) {
            val passed = (f - shrinkStartFrame) * CHILLED_FRAME_MS
            shrinkProgress = (passed / CHILLED_SHRINK_MS).toFloat().coerceIn(0f, 1f)
            if (shrinkProgress >= 1f) {
                shrunk = true
                // 缩完之后再等 10 帧，把界面那一层藏掉，只留底层视频
                if (f >= CHILLED_SHRINK_AT + 25) uiHidden = true
            }
        }
        if (f >= CHILLED_END_AT) {
            // 原版这里是显示文字 + 视频继续播；这里就保持视频继续 ✓
        }

        // ── 上层（原版 flipTimes / fFlipTrans）──
        // ★ 用 while 而不是 if —— 设备卡一下跳过几个时间点时也不会漏 ✓
        if (refreshTop) {
            while (idxTop < CHILLED_TIMES_TOP.size &&
                CHILLED_TIMES_TOP[idxTop] in 1..f
            ) {
                idxTop++
                flipTop = !flipTop
            }
        }
        // ── 中下层（原版 refreshSecondFlips 块里的两组）──
        if (refreshMidBottom) {
            while (idxMid < CHILLED_TIMES_MID.size &&
                CHILLED_TIMES_MID[idxMid] in 1..f
            ) {
                idxMid++
                flipMid = !flipMid
            }
            while (idxBottom < CHILLED_TIMES_BOTTOM.size &&
                CHILLED_TIMES_BOTTOM[idxBottom] in 1..f
            ) {
                idxBottom++
                flipBottom = !flipBottom
            }
        }
    }

    /** 视频播完 */
    fun onVideoEnded() {
        finished = true
    }
}
