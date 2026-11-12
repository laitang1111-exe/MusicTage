package com.example.musictage.utils

import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo

/**
 * 全局横屏锁
 *
 * 王桑 2026-10-25：
 *   「就不能改为 APP 内全局强制横屏吗？」
 *
 * 原来的做法只在 PlayerScreen 里挂了一个 DisposableEffect，
 * 而且 onDispose 会把方向重置成 UNSPECIFIED ——
 * 也就是说一离开播放界面就恢复竖屏，看起来就像「只有播放页横屏」。
 *
 * 现在改成这一套：
 *   一、开关打开后，本 APP 里所有 Activity 都锁 sensorLandscape
 *   二、开关关掉后恢复 UNSPECIFIED，交还给系统
 *   三、每个 Activity 的 onResume 都调一次，从别的应用切回来也不会掉
 *   四、改开关时立刻生效，不用重启
 *
 * 用 sensorLandscape 而不是 landscape，是为了让手机翻转 180° 时
 * 界面还能跟着转 —— 躺床上充电的时候正着反着都能看。
 */
object OrientationLock {

    /** 按当前设置把方向应用到这个 Activity 上 */
    fun apply(activity: Activity) {
        val target = if (AppPrefs.isForceLandscapeEnabled(activity)) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        // 相同值重复赋值不会触发重建，这里只是省掉一次多余的调用
        if (activity.requestedOrientation != target) {
            try {
                activity.requestedOrientation = target
            } catch (e: Exception) {
                // 极少数 ROM 在某些窗口状态下会抛，忽略即可
            }
        }
    }

    /**
     * 开关变更后立刻生效。
     *
     * 传进来的 context 可能是 Activity，也可能是别的，能拿到 Activity 就应用。
     */
    fun refresh(context: Context?) {
        (context as? Activity)?.let { apply(it) }
    }
}
