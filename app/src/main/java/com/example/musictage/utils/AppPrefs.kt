package com.example.musictage.utils

import android.content.Context

/**
 * 应用级偏好设置的集中入口。
 *
 * 所有开关都存在 SharedPreferences 里，界面（设置弹窗）负责写，播放逻辑负责读，
 * 两边必须看到同一个值 —— 所以统一收在这里，不散落到界面层。
 *
 * 说明：会话历史里只捞回了早期的两函数版本（歌词通知开关），
 * 这里按反编译出来的 AppPrefs API 把其余设置补齐，
 * 键名按语义重新起，读写都走 commit() 保证立刻可见。
 */
object AppPrefs {

    private const val PREFS_NAME = "musictage_prefs"

    private const val K_LYRIC_NOTIFICATION = "lyric_notification_enabled"
    private const val K_LYRIC_NO_TRANSLATION = "lyric_notification_no_translation"
    private const val K_SLEEP_MINUTES = "sleep_minutes"
    private const val K_SLEEP_FINISH_TRACK = "sleep_finish_track"
    private const val K_SHOW_SONG_INDEX = "show_song_index"
    private const val K_PLAYBACK_MODE = "playback_mode_ordinal"
    private const val K_PRIORITY_DIRS = "priority_scan_dirs"
    private const val K_HALL_REVERB = "hall_reverb_enabled"
    private const val K_REVERB_SCENARIO = "reverb_scenario_id"
    private const val K_PIXEL_SHIFT = "pixel_shift_enabled"
    private const val K_CHILLED_EGG = "chilled_egg_enabled"
    private const val K_LANDSCAPE_SWAP = "landscape_swap_enabled"
    private const val K_FORCE_LANDSCAPE = "force_landscape_enabled"
    private const val K_NOTIFICATION_IDS = "song_notification_ids"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ---------- 歌词通知 ----------

    fun isLyricNotificationEnabled(context: Context): Boolean = try {
        prefs(context).getBoolean(K_LYRIC_NOTIFICATION, true)
    } catch (e: Exception) {
        true
    }

    fun setLyricNotificationEnabled(context: Context, enabled: Boolean): Boolean = try {
        prefs(context).edit().putBoolean(K_LYRIC_NOTIFICATION, enabled).commit()
    } catch (e: Exception) {
        false
    }

    fun isLyricNotificationNoTranslation(context: Context): Boolean = try {
        prefs(context).getBoolean(K_LYRIC_NO_TRANSLATION, false)
    } catch (e: Exception) {
        false
    }

    fun setLyricNotificationNoTranslation(context: Context, enabled: Boolean): Boolean = try {
        prefs(context).edit().putBoolean(K_LYRIC_NO_TRANSLATION, enabled).commit()
    } catch (e: Exception) {
        false
    }

    // ---------- 睡眠定时 ----------

    /** 定时分钟数，0 表示不启用 */
    fun getSleepMinutes(context: Context): Int = try {
        prefs(context).getInt(K_SLEEP_MINUTES, 0)
    } catch (e: Exception) {
        0
    }

    fun setSleepMinutes(context: Context, minutes: Int): Boolean = try {
        prefs(context).edit().putInt(K_SLEEP_MINUTES, minutes).commit()
    } catch (e: Exception) {
        false
    }

    /** 定时到了是「播完当前这首」还是「立刻停」 */
    fun isSleepFinishTrack(context: Context): Boolean = try {
        prefs(context).getBoolean(K_SLEEP_FINISH_TRACK, true)
    } catch (e: Exception) {
        true
    }

    fun setSleepFinishTrack(context: Context, enabled: Boolean): Boolean = try {
        prefs(context).edit().putBoolean(K_SLEEP_FINISH_TRACK, enabled).commit()
    } catch (e: Exception) {
        false
    }

    // ---------- 列表显示 ----------

    fun isShowSongIndex(context: Context): Boolean = try {
        prefs(context).getBoolean(K_SHOW_SONG_INDEX, false)
    } catch (e: Exception) {
        false
    }

    fun setShowSongIndex(context: Context, enabled: Boolean): Boolean = try {
        prefs(context).edit().putBoolean(K_SHOW_SONG_INDEX, enabled).commit()
    } catch (e: Exception) {
        false
    }

    // ---------- 播放模式 ----------

    fun getPlaybackModeOrdinal(context: Context): Int = try {
        prefs(context).getInt(K_PLAYBACK_MODE, 0)
    } catch (e: Exception) {
        0
    }

    fun setPlaybackModeOrdinal(context: Context, ordinal: Int): Boolean = try {
        prefs(context).edit().putInt(K_PLAYBACK_MODE, ordinal).commit()
    } catch (e: Exception) {
        false
    }

    // ---------- 扫描目录 ----------

    fun getPriorityScanDirs(context: Context): List<String> = try {
        prefs(context).getStringSet(K_PRIORITY_DIRS, emptySet())
            ?.sorted()
            ?: emptyList()
    } catch (e: Exception) {
        emptyList()
    }

    fun setPriorityScanDirs(context: Context, dirs: Set<String>): Boolean = try {
        prefs(context).edit().putStringSet(K_PRIORITY_DIRS, HashSet(dirs)).commit()
    } catch (e: Exception) {
        false
    }

    // ---------- 音效 ----------

    fun isHallReverbEnabled(context: Context): Boolean = try {
        prefs(context).getBoolean(K_HALL_REVERB, false)
    } catch (e: Exception) {
        false
    }

    fun setHallReverbEnabled(context: Context, enabled: Boolean): Boolean = try {
        prefs(context).edit().putBoolean(K_HALL_REVERB, enabled).commit()
    } catch (e: Exception) {
        false
    }

    fun getReverbScenarioId(context: Context): String = try {
        prefs(context).getString(K_REVERB_SCENARIO, "") ?: ""
    } catch (e: Exception) {
        ""
    }

    fun setReverbScenarioId(context: Context, id: String): Boolean = try {
        prefs(context).edit().putString(K_REVERB_SCENARIO, id).commit()
    } catch (e: Exception) {
        false
    }

    // ---------- 通知 id 记录 ----------

    fun addSongNotificationId(context: Context, id: Int): Boolean = try {
        val cur = prefs(context).getStringSet(K_NOTIFICATION_IDS, emptySet()) ?: emptySet()
        prefs(context).edit()
            .putStringSet(K_NOTIFICATION_IDS, HashSet(cur + id.toString()))
            .commit()
    } catch (e: Exception) {
        false
    }

    fun getSongNotificationIds(context: Context): Set<String> = try {
        prefs(context).getStringSet(K_NOTIFICATION_IDS, emptySet()) ?: emptySet()
    } catch (e: Exception) {
        emptySet()
    }

    fun clearSongNotificationIds(context: Context): Boolean = try {
        prefs(context).edit().remove(K_NOTIFICATION_IDS).commit()
    } catch (e: Exception) {
        false
    }

    // ---------- 界面微调 ----------

    fun isPixelShiftEnabled(context: Context): Boolean = try {
        prefs(context).getBoolean(K_PIXEL_SHIFT, false)
    } catch (e: Exception) {
        false
    }

    fun setPixelShiftEnabled(context: Context, enabled: Boolean): Boolean = try {
        prefs(context).edit().putBoolean(K_PIXEL_SHIFT, enabled).commit()
    } catch (e: Exception) {
        false
    }

    fun isChilledEggEnabled(context: Context): Boolean = try {
        prefs(context).getBoolean(K_CHILLED_EGG, true)
    } catch (e: Exception) {
        true
    }

    fun setChilledEggEnabled(context: Context, enabled: Boolean): Boolean = try {
        prefs(context).edit().putBoolean(K_CHILLED_EGG, enabled).commit()
    } catch (e: Exception) {
        false
    }

    fun isLandscapeSwapEnabled(context: Context): Boolean = try {
        prefs(context).getBoolean(K_LANDSCAPE_SWAP, false)
    } catch (e: Exception) {
        false
    }

    fun setLandscapeSwapEnabled(context: Context, enabled: Boolean): Boolean = try {
        prefs(context).edit().putBoolean(K_LANDSCAPE_SWAP, enabled).commit()
    } catch (e: Exception) {
        false
    }

    fun isForceLandscapeEnabled(context: Context): Boolean = try {
        prefs(context).getBoolean(K_FORCE_LANDSCAPE, false)
    } catch (e: Exception) {
        false
    }

    fun setForceLandscapeEnabled(context: Context, enabled: Boolean): Boolean = try {
        prefs(context).edit().putBoolean(K_FORCE_LANDSCAPE, enabled).commit()
    } catch (e: Exception) {
        false
    }
}
