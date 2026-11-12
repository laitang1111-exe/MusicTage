package com.example.musictage

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.annotation.OptIn
import androidx.core.app.NotificationCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.example.musictage.data.AppDatabase
import com.example.musictage.utils.AppPrefs
import com.example.musictage.utils.LrcLine
import com.example.musictage.utils.LrcParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/**
 * 播放服务：播放器本体和歌词通知循环都住在这里。
 *
 * 为什么歌词通知必须放在服务里：界面层的 ViewModel 是**跟着 Activity 生死**的，
 * 而音乐是跟着服务的。以前通知循环住在 ViewModel 里，一旦承载它的那个 Activity 被销毁
 * （按返回键退出、被系统回收），就会出现"音乐还在放、歌词通知却再也不弹"的怪现象。
 * 搬进服务之后，只要还在播，通知就一直在 喵~
 *
 * 顺带解决第二个问题：以前 MainActivity 和「音乐管理」各自持有一个 ViewModel，
 * 两个 MediaController 会同时跑两条通知循环、互相撤掉对方刚发出来的通知。
 * 现在整个进程只有这一条循环 喵~
 */
class PlaybackService : MediaSessionService() {

    companion object {
        /** 歌词通知渠道：与 `MainActivity.createNotificationChannel` 里建的是同一个 喵~ */
        private const val LYRIC_CHANNEL_ID = "TEST_CHANNEL_ID"

        /** 歌词通知的分组；与 `sendLyricNotification` 里的 `setGroup` 保持一致 喵~ */
        private const val LYRIC_GROUP = "LYRIC_GROUP"

        /**
         * 歌词通知的「组摘要」通知 ID。
         *
         * 歌词通知用 `setGroup(LYRIC_GROUP)` 归为一组，系统在组内多于一条时可能生成
         * 一条组摘要通知。取消组内成员后用这个 ID 一并取消，避免残留。
         * 该值与其它通知 ID 不冲突（歌词通知 ID 取自歌词行时间戳，歌曲概览通知为 100/101+）喵~
         */
        private const val LYRIC_GROUP_SUMMARY_ID = 99

        /**
         * 延迟撤掉旧歌词通知前的等待毫秒数。
         *
         * 保留"先发新、隔一会儿再撤旧"这个顺序是刻意的：新旧两条会短暂并存，
         * 形成上一句与当前句的视觉叠加，便于对照。不要改成"先删旧再发新" 喵~
         */
        private const val NOTIFICATION_SWAP_DELAY_MS = 700L

        /** 歌词通知的提前量：提前这么多毫秒匹配歌词行，视觉同步更准 喵~ */
        private const val LYRIC_LOOKAHEAD_MS = 350L

        /** 进度轮询间隔 喵~ */
        private const val TICK_INTERVAL_MS = 200L

        /**
         * 当前活着的服务实例。
         *
         * 本进程内只会有一个 PlaybackService，界面要"让正在播的这首立刻重算歌词"时
         * 直接从这里调用，比再走一次 Intent / Binder 简单得多 喵~
         */
        @Volatile
        private var instance: PlaybackService? = null

        /** 进度条松手后：立刻按新位置补一条歌词通知，不用等下一个轮询周期 喵~ */
        fun refreshLyricNow() {
            instance?.notifyLyricNow()
        }

        /** 某首歌的歌词偏移改完之后，如果正好在放这首就重新解析一遍 喵~ */
        fun reloadLyrics(songId: Long) {
            instance?.onLyricOffsetChanged(songId)
        }
    }

    private var mediaSession: MediaSession? = null
    private lateinit var exoPlayer: ExoPlayer

    /**
     * 服务自己的协程作用域。
     *
     * 用 SupervisorJob + 内部逐个 try/catch，保证任何一次通知/读文件的意外
     * 都不会把整个服务连带 App 一起带走 喵~
     */
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var lyricJob: Job? = null

    private var lyrics: List<LrcLine> = emptyList()

    /** 当前歌词文件是否含日文（含日文时通知的主副标题要换个摆法）喵~ */
    private var containsJapanese = false

    private var lastNotifiedTimeMs = -1L

    /** 跟踪活动的歌词通知 ID，切歌或关开关时逐个撤掉 喵~ */
    private val activeLyricNotificationIds = mutableListOf<Int>()

    /** 当前播放歌曲的本地 ID（也是歌曲表主键）喵~ */
    private var currentSongId: Long? = null

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        instance = this

        // 通知渠道在这里也建一次：从「音乐管理」快捷方式进来时根本不经过 MainActivity
        // （shortcuts.xml 直接拉起 DatabaseManagementActivity），只有那边建渠道的话，
        // 这些通知会被系统静默丢弃 喵~
        createNotificationChannel(this)

        // 配置音频属性，支持音频焦点自动管理
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        exoPlayer = ExoPlayer.Builder(this)
            .setAudioAttributes(audioAttributes, false) 
            .setHandleAudioBecomingNoisy(true) 
            .setWakeMode(C.WAKE_MODE_LOCAL) // 保持 CPU 唤醒，解决播放中断问题
            .build()

        mediaSession = MediaSession.Builder(this, exoPlayer).build()

        exoPlayer.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                currentSongId = mediaItem?.mediaId?.toLongOrNull()
                // 切歌时先把上一首残留的歌词通知收掉，再按新歌重新解析 喵~
                clearLyricNotifications()
                loadLyricsForCurrentSong()
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) startLyricLoop() else stopLyricLoop()
            }
        })

        // 进程重启后可能已经在播（服务比 Activity 活得久），这里补上初始状态 喵~
        currentSongId = exoPlayer.currentMediaItem?.mediaId?.toLongOrNull()
        loadLyricsForCurrentSong()
        if (exoPlayer.isPlaying) {
            startLyricLoop()
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    override fun onDestroy() {
        // 服务要没了，把还挂着的歌词通知一并收掉，避免留下"上一句"挂在通知栏 喵~
        stopLyricLoop()
        clearLyricNotifications()
        serviceScope.cancel()

        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
        instance = null
        super.onDestroy()
    }

    // 禁用 aggressive stopSelf，防止 Activity 伪闪退导致服务误关
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
    }

    // ==================== 歌词通知 ====================

    /**
     * 启动歌词通知循环。
     *
     * 循环体整个包在 try/catch 里：通知、读文件这类事出问题只应该"这一拍没做成"，
     * 绝不能让异常逃出协程把服务（乃至 App）带走 喵~
     */
    private fun startLyricLoop() {
        stopLyricLoop()
        lyricJob = serviceScope.launch {
            while (true) {
                try {
                    if (AppPrefs.isLyricNotificationEnabled(this@PlaybackService)) {
                        checkAndSendLyricNotification(exoPlayer.currentPosition)
                    } else if (activeLyricNotificationIds.isNotEmpty()) {
                        // 开关被关掉：把已经显示的通知收掉，之后自然也就不再发了 喵~
                        clearLyricNotifications()
                    }
                } catch (e: Throwable) {
                    // 单拍失败就算了，下一拍继续 喵~
                }
                delay(TICK_INTERVAL_MS)
            }
        }
    }

    private fun stopLyricLoop() {
        lyricJob?.cancel()
        lyricJob = null
    }

    /**
     * 读取当前这首的歌词。
     *
     * 任何失败（数据库、文件被删、编码异常）都只当作"这首没有歌词"处理——
     * 这一段以前跑在界面的 ViewModel 里且完全没有保护，是"自动切歌时突然闪退"
     * 的主要来源，现在换成 runCatching 兜住 喵~
     */
    private fun loadLyricsForCurrentSong() {
        val songId = currentSongId ?: run {
            lyrics = emptyList()
            return
        }

        serviceScope.launch {
            val parsed = runCatching {
                val song = AppDatabase.getDatabase(applicationContext).songDao().getSongById(songId)
                val path = song?.lrcPath ?: return@runCatching emptyList()
                // 这首歌自己的偏移量在这里生效：整体平移歌词行的时间戳 喵~
                LrcParser.applyOffset(LrcParser.parse(File(path)), song.lyricOffsetMs)
            }.getOrDefault(emptyList())

            // 解析期间可能已经切歌了，那就别把上一首的歌词顶上去 喵~
            if (currentSongId != songId) return@launch

            lyrics = parsed
            lastNotifiedTimeMs = -1L
            containsJapanese = parsed.any {
                it.originalText.containsJapanese() || it.translatedText?.containsJapanese() == true
            }
        }
    }

    /** 歌词偏移改动后的即时生效：改的正好是当前这首才需要重新解析 喵~ */
    private fun onLyricOffsetChanged(songId: Long) {
        if (currentSongId == songId) {
            loadLyricsForCurrentSong()
        }
    }

    private fun checkAndSendLyricNotification(currentPos: Long) {
        val line = LrcParser.getLineAt(currentPos + LYRIC_LOOKAHEAD_MS, lyrics) ?: return
        if (line.timeMs == lastNotifiedTimeMs) {
            return
        }
        lastNotifiedTimeMs = line.timeMs
        sendLyricNotification(line)
    }

    /**
     * 立刻按当前进度补一条歌词通知，不管这一行之前是否已经推过。
     *
     * 拖动进度条松手后专用：松手时位置可能一下子跨过好几行歌词，而轮询最多还要
     * 等 200 毫秒才轮到，所以这里主动补一条，松手后歌词马上对上 喵~
     */
    private fun notifyLyricNow() {
        if (!AppPrefs.isLyricNotificationEnabled(this)) return
        try {
            val line = LrcParser.getLineAt(exoPlayer.currentPosition + LYRIC_LOOKAHEAD_MS, lyrics) ?: return
            lastNotifiedTimeMs = line.timeMs
            sendLyricNotification(line)
        } catch (e: Throwable) {
            // 补发失败无所谓，轮询会兜住 喵~
        }
    }

    private fun sendLyricNotification(line: LrcLine) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val title: String
        val text: String

        if (!containsJapanese) {
            // 全局未包含日文：直接放在主标题，副标题填占位符或译文
            title = line.originalText
            text = line.translatedText ?: " "
        } else {
            // 全局包含日文：执行之前的逻辑
            title = if (line.translatedText == null) "                                                      " else line.originalText
            text = line.translatedText ?: line.originalText
        }

        val notification = NotificationCompat.Builder(this, LYRIC_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setGroup(LYRIC_GROUP) // 分组管理
            .setAutoCancel(true)
            .build()

        val notificationId = line.timeMs.toInt()

        // 取走上一批通知 ID，并立即把新 ID 纳入跟踪列表。
        // 立即纳入的原因：撤销是延迟执行的，这期间如果服务被销毁，
        // clearLyricNotifications() 必须能取消掉这条刚发出的新通知。
        val idsToRemove = activeLyricNotificationIds.toList()
        activeLyricNotificationIds.clear()
        activeLyricNotificationIds.add(notificationId)

        // 发送新通知
        notificationManager.notify(notificationId, notification)

        // 间隔 NOTIFICATION_SWAP_DELAY_MS 后再删除旧通知。
        // 保留这个顺序是刻意的：新旧两条会短暂并存，形成上一句与当前句的视觉叠加，
        // 便于对照。不要改成"先删旧再发新"——那会丢失这个效果。
        serviceScope.launch {
            delay(NOTIFICATION_SWAP_DELAY_MS)
            idsToRemove.forEach { clearLyricNotification(it) }
        }
    }

    /** 取消单条歌词通知（按通知 ID 移除，并清理通知组摘要）喵~ */
    private fun clearLyricNotification(notificationId: Int) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(notificationId)
        // 当前歌词通知使用 setGroup(LYRIC_GROUP)，取消组内成员后
        // 系统可能仍保留组摘要通知；这里按摘要 ID 一并取消，避免残留。
        notificationManager.cancel(LYRIC_GROUP_SUMMARY_ID)
    }

    /** 清除当前已展示的歌词通知，并重置「已通知到哪一行」的记录 喵~ */
    private fun clearLyricNotifications() {
        activeLyricNotificationIds.forEach { clearLyricNotification(it) }
        activeLyricNotificationIds.clear()
        lastNotifiedTimeMs = -1L
    }

    private fun String.containsJapanese(): Boolean {
        // 匹配平假名、片假名
        return this.any { it in '\u3040'..'\u309F' || it in '\u30A0'..'\u30FF' }
    }
}
