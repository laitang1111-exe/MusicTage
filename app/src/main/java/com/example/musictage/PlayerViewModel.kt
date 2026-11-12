package com.example.musictage

import android.app.Application
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.example.musictage.data.AppDatabase
import com.example.musictage.utils.AppPrefs
import com.example.musictage.utils.LrcLine
import com.example.musictage.utils.LrcParser
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

class PlayerViewModel(application: Application) : AndroidViewModel(application) {

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var mediaController: MediaController? = null

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying = _isPlaying.asStateFlow()

    private val _currentSongTitle = MutableStateFlow("未在播放")
    val currentSongTitle = _currentSongTitle.asStateFlow()

    private val _currentPosition = MutableStateFlow(0L)
    val currentPosition = _currentPosition.asStateFlow()

    private val _duration = MutableStateFlow(0L)
    val duration = _duration.asStateFlow()

    /**
     * 当前歌曲的歌手名。
     *
     * 界面（PlayerScreen）用它显示在歌名下面。历史里捞回来的 ViewModel 版本没有这一项，
     * 这里补上：由播放器回调里的 MediaMetadata 直接喂。
     */
    private val _currentArtist = MutableStateFlow("")
    val currentArtist = _currentArtist.asStateFlow()

    /**
     * 当前封面图的 URI（字符串）。
     *
     * 优先用歌曲同目录的旁挂图片（coverPath），没有就退回 MediaStore 的专辑封面。
     * 界面用 Coil 直接加载这个字符串即可，空串表示「没封面」。
     */
    private val _currentArtworkUri = MutableStateFlow("")
    val currentArtworkUri = _currentArtworkUri.asStateFlow()

    private var lyrics: List<LrcLine> = emptyList()
    private var lastNotifiedTimeMs: Long = -1L
    private var containsJapanese: Boolean = false // 全局判定：当前歌词文件是否包含日文
    private val activeLyricNotificationIds = mutableListOf<Int>() // 跟踪活动的歌词通知 ID

    /** 歌词通知使用的通知组；与 `sendLyricNotification` 里的 `setGroup` 保持一致 */
    private val lyricNotificationGroup = "LYRIC_GROUP"

    /**
     * 歌词通知的「组摘要」通知 ID。
     *
     * 歌词通知用 `setGroup(lyricNotificationGroup)` 归为一组，系统在组内多于一条时
     * 可能生成一条组摘要通知。取消组内成员后用这个 ID 一并取消摘要，避免残留。
     * 该值与其它通知 ID 不冲突（歌词通知 ID 取自歌词行时间戳，歌曲概览通知为 100/101+）。
     */
    private val lyricGroupSummaryId = 99

    /** 延迟发送新歌词通知前的等待毫秒数：给旧通知的取消留出时间 */
    private val lyricNotificationSwapDelayMs = 700L

    private var progressJob: Job? = null

    init {
        val sessionToken = SessionToken(application, ComponentName(application, PlaybackService::class.java))
        controllerFuture = MediaController.Builder(application, sessionToken).buildAsync()
        controllerFuture?.addListener({
            mediaController = controllerFuture?.get()
            setupPlayer()
        }, MoreExecutors.directExecutor())
    }

    private fun setupPlayer() {
        mediaController?.let { player ->
            _isPlaying.value = player.isPlaying
            _duration.value = player.duration.coerceAtLeast(0L)
            _currentSongTitle.value = player.currentMediaItem?.mediaMetadata?.title?.toString() ?: "未在播放"
            
            // 关键优化：如果进入界面时已经在播放，自动同步并加载歌词
            val currentMediaId = player.currentMediaItem?.mediaId
            if (currentMediaId != null) {
                loadLyrics(currentMediaId.toLong())
            }

            player.addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    _isPlaying.value = isPlaying
                    if (isPlaying) {
                        startProgressUpdate()
                    } else {
                        stopProgressUpdate()
                    }
                }

                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    _currentSongTitle.value = mediaItem?.mediaMetadata?.title?.toString() ?: "未知曲目"
                    _duration.value = player.duration.coerceAtLeast(0L)
                    // 切歌时重新加载新歌词
                    mediaItem?.mediaId?.let { loadLyrics(it.toLong()) }
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    _duration.value = player.duration.coerceAtLeast(0L)
                }
            })
            
            if (player.isPlaying) {
                startProgressUpdate()
            }
        }
    }

    private fun loadLyrics(songId: Long) {
        viewModelScope.launch {
            val db = AppDatabase.getDatabase(getApplication())
            val song = db.songDao().getSongById(songId)
            
            lyrics = if (song?.lrcPath != null) {
                LrcParser.parse(File(song.lrcPath))
            } else {
                emptyList()
            }
            
            // 重置状态，强制触发一次通知显示
            lastNotifiedTimeMs = -1L
            // 全局检测是否包含日文
            containsJapanese = lyrics.any { it.originalText.containsJapanese() || it.translatedText?.containsJapanese() == true }
        }
    }

    private fun startProgressUpdate() {
        stopProgressUpdate()
        progressJob = viewModelScope.launch {
            while (true) {
                val currentPos = mediaController?.currentPosition ?: 0L
                _currentPosition.value = currentPos
                checkAndSendLyricNotification(currentPos)
                delay(200) // 缩短间隔以更精准匹配歌词
            }
        }
    }

    private fun checkAndSendLyricNotification(currentPos: Long) {
        // 尊重「歌词通知」开关：关闭时不推送任何歌词通知。
        // 提前 350 毫秒匹配歌词行，以确保更完美的视觉同步感
        if (!AppPrefs.isLyricNotificationEnabled(getApplication())) {
            return
        }
        val line = LrcParser.getLineAt(currentPos + 350, lyrics)
        if (line != null && line.timeMs != lastNotifiedTimeMs) {
            lastNotifiedTimeMs = line.timeMs
            sendLyricNotification(line)
        }
    }

    private fun sendLyricNotification(line: LrcLine) {
        val context = getApplication<Application>()
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "TEST_CHANNEL_ID"

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

        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setGroup(lyricNotificationGroup) // 分组管理
            .setAutoCancel(true)
            .build()

        val notificationId = line.timeMs.toInt()

        // 取走上一批通知 ID，并立即把新 ID 纳入跟踪列表。
        // 立即纳入的原因：若用户在这 700ms 内关掉「歌词通知」开关，
        // clearLyricNotifications() 必须能取消掉这条刚发出的新通知。
        val idsToRemove = activeLyricNotificationIds.toList()
        activeLyricNotificationIds.clear()
        activeLyricNotificationIds.add(notificationId)

        // 发送新通知
        notificationManager.notify(notificationId, notification)

        // 间隔 lyricNotificationSwapDelayMs 后再删除旧通知。
        // 保留这个顺序是刻意的：新旧两条会短暂并存，形成上一句与当前句的视觉叠加，
        // 便于对照。不要改成"先删旧再发新"——那会丢失这个效果。
        viewModelScope.launch {
            delay(lyricNotificationSwapDelayMs)
            idsToRemove.forEach { clearLyricNotification(it) }
        }
    }

    /** 取消单条歌词通知（按通知 ID 移除，并清理通知组摘要） */
    private fun clearLyricNotification(notificationId: Int) {
        val notificationManager =
            getApplication<Application>().getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(notificationId)
        // 当前歌词通知使用 setGroup(lyricNotificationGroup)，取消组内成员后
        // 系统可能仍保留组摘要通知；这里按摘要 ID 一并取消，避免残留。
        notificationManager.cancel(lyricGroupSummaryId)
    }

    private fun String.containsJapanese(): Boolean {
        // 匹配平假名、片假名
        return this.any { it in '\u3040'..'\u309F' || it in '\u30A0'..'\u30FF' }
    }

    /**
     * 清除当前已展示的歌词通知，并重置「已通知到哪一行」的记录。
     *
     * 公开给界面层调用：关闭「歌词通知」开关时需要立即收起正在显示的通知，
     * 并让 [lastNotifiedTimeMs] 归位，以便重新打开时能立刻恢复显示。
     */
    fun clearLyricNotifications() {
        activeLyricNotificationIds.forEach { clearLyricNotification(it) }
        activeLyricNotificationIds.clear()
        lastNotifiedTimeMs = -1L
    }

    /**
     * 「歌词通知」开关变更后的回调。
     *
     * 关闭时立即清掉已展示的通知；重新打开时因为 [lastNotifiedTimeMs] 已被重置，
     * 播放循环会在下一个 200ms 周期自动恢复推送当前歌词行。
     */
    fun onLyricNotificationChanged(enabled: Boolean) {
        if (!enabled) {
            clearLyricNotifications()
        }
    }

    private fun stopProgressUpdate() {
        progressJob?.cancel()
    }

    fun playPause() {
        mediaController?.let {
            if (it.isPlaying) it.pause() else it.play()
        }
    }

    fun skipNext() {
        mediaController?.seekToNext()
        clearLyricNotifications()
    }

    fun skipPrevious() {
        mediaController?.seekToPrevious()
        clearLyricNotifications()
    }

    fun seekTo(position: Float) {
        val durationMs = _duration.value
        if (durationMs > 0) {
            mediaController?.seekTo((position * durationMs).toLong())
            // 进度拖动后重置记录
            lastNotifiedTimeMs = -1L
        }
    }

    fun playSong(songId: Long, title: String, artist: String) {
        viewModelScope.launch {
            clearLyricNotifications()
            loadLyrics(songId)

            val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, songId)
            val mediaItem = MediaItem.Builder()
                .setMediaId(songId.toString())
                .setUri(uri)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(title)
                        .setArtist(artist)
                        .build()
                )
                .build()

            mediaController?.let {
                it.setMediaItem(mediaItem)
                it.prepare()
                it.play()
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        controllerFuture?.let {
            MediaController.releaseFuture(it)
        }
    }
}
