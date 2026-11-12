package com.example.musictage.utils

import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive

/**
 * 「定时关闭」（睡眠定时）的引擎 喵~
 *
 * 为什么放在**服务**这一侧而不是界面：定时器必须比界面活得久 ✓
 * 用户按灭屏幕、退出 App、甚至把界面划掉，到点了照样要停 ✓✓
 * 界面只是读写这里的状态 ✓（[PlaybackService.sleepState] ✓）
 *
 * 三种模式，和原版华为音乐的选项一一对应 ✓
 * - [Mode.OFF]：不开启 ✓
 * - [Mode.AFTER_CURRENT]：当前内容播放完毕关闭 ✓（不倒数，等这一首自己走完 ✓）
 * - [Mode.MINUTES]：N 分钟后关闭 ✓（含自定义时长 ✓）
 *
 * 另外还有一个**独立**的开关 [State.finishCurrentTrack]：计时结束时，
 * 是立刻停，还是把正在唱的这首放完再停 ✓（原版那个「完整播放完毕再关闭」✓）
 *
 * 时间基准用 [SystemClock.elapsedRealtime]：它包含深睡时间 ✓
 * 用 `System.currentTimeMillis()` 的话，系统时间被改一下定时就废了 ✗
 */
class SleepTimerController(
    private val scope: CoroutineScope,
    /** 到点了：参数是"要不要等这首放完"✓ 具体怎么停由服务决定（淡出、暂停 ✓）*/
    private val onExpired: (waitForTrackEnd: Boolean) -> Unit,
) {

    enum class Mode {
        /** 不开启 ✓ */
        OFF,

        /** 当前内容播放完毕关闭 ✓ */
        AFTER_CURRENT,

        /** N 分钟后关闭 ✓ */
        MINUTES,
    }

    /**
     * 对外可见的定时状态 ✓
     *
     * - [endsAtMillis]：到期时刻（[SystemClock.elapsedRealtime] 基准 ✓），0 表示没有在倒数 ✓
     * - [remainingMillis]：还剩多少毫秒 ✓ 界面直接拿它显示 ✓
     * - [waitingTrackEnd]：已到点但正在等这首放完 ✓ 界面据此显示"等本曲结束" ✓
     */
    data class State(
        val mode: Mode = Mode.OFF,
        val minutes: Int = 0,
        val endsAtMillis: Long = 0L,
        val remainingMillis: Long = 0L,
        val finishCurrentTrack: Boolean = true,
        val waitingTrackEnd: Boolean = false,
    ) {
        /** 有没有正在生效的定时（含"等本曲结束"）✓ */
        val isActive: Boolean get() = mode != Mode.OFF
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var job: Job? = null

    /** 上次选过的分钟数（界面用它回填"自定义"那一项 ✓）*/
    private var lastMinutes: Int = 0

    /**
     * N 分钟后关闭 ✓
     *
     * @param minutes 分钟数，至少 1 ✓（0 或负数按"不开启"处理 ✓，和原版 1~120 分钟的下限一致 ✓）
     * @param finishCurrentTrack 到点后是否等这首放完 ✓
     */
    fun startMinutes(minutes: Int, finishCurrentTrack: Boolean) {
        job?.cancel()
        if (minutes <= 0) {
            _state.value = State(finishCurrentTrack = finishCurrentTrack)
            return
        }
        lastMinutes = minutes
        val endsAt = SystemClock.elapsedRealtime() + minutes * 60_000L
        _state.value = State(
            mode = Mode.MINUTES,
            minutes = minutes,
            endsAtMillis = endsAt,
            remainingMillis = minutes * 60_000L,
            finishCurrentTrack = finishCurrentTrack,
        )
        job = scope.launchTicker(endsAt)
    }

    /** 当前内容播放完毕关闭 ✓（不倒数 ✓）*/
    fun startAfterCurrent(finishCurrentTrack: Boolean = true) {
        job?.cancel()
        _state.value = State(
            mode = Mode.AFTER_CURRENT,
            finishCurrentTrack = finishCurrentTrack,
        )
    }

    /** 取消定时 ✓ */
    fun cancel() {
        job?.cancel()
        job = null
        _state.value = State(finishCurrentTrack = _state.value.finishCurrentTrack)
    }

    /**
     * 播放器换歌时调用 ✓
     *
     * 两种情况会在这里收尾：
     * 1. 「当前内容播放完毕关闭」——上一首放完了 ✓ 现在就该停 ✓
     * 2. N 分钟模式但到点时选择了"等这首放完"✓（[State.waitingTrackEnd] ✓）
     *
     * @return true 表示"现在该停了"✓ 由服务执行淡出+暂停 ✓
     */
    fun onTrackAdvanced(): Boolean {
        val s = _state.value
        return when {
            s.mode == Mode.AFTER_CURRENT -> {
                _state.value = State(finishCurrentTrack = s.finishCurrentTrack)
                true
            }
            s.waitingTrackEnd -> {
                _state.value = State(finishCurrentTrack = s.finishCurrentTrack)
                true
            }
            else -> false
        }
    }

    /** 界面想知道"上次自定义选了多少分钟"✓ */
    fun lastChosenMinutes(): Int = lastMinutes

    /** 每秒更新一次剩余时间；到点就交付给 [onExpired] ✓ */
    private fun CoroutineScope.launchTicker(endsAt: Long): Job = launch {
        while (isActive) {
            val left = endsAt - SystemClock.elapsedRealtime()
            val s = _state.value
            if (left <= 0L) {
                if (s.finishCurrentTrack) {
                    // 还要把这首放完：状态切到"等本曲结束"✓ 真正的停由 onTrackAdvanced 触发 ✓
                    _state.value = s.copy(endsAtMillis = 0L, remainingMillis = 0L, waitingTrackEnd = true)
                    onExpired(true)
                } else {
                    _state.value = s.copy(endsAtMillis = 0L, remainingMillis = 0L)
                    onExpired(false)
                }
                return@launch
            }
            _state.value = s.copy(remainingMillis = left)
            delay(TICK_MS)
        }
    }

    private companion object {
        /** 刷新间隔：只用来更新界面上的"还剩多少"✓ 到点判断本身不依赖它 ✓ */
        const val TICK_MS = 1000L
    }
}
