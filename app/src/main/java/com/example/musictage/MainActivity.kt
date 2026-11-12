package com.example.musictage

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import android.util.Log
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.musictage.data.AppDatabase
import com.example.musictage.data.SongEntity
import com.example.musictage.utils.AppPrefs
import com.example.musictage.utils.LaunchState
import com.example.musictage.utils.MediaLibraryScanner
import com.example.musictage.ui.theme.MusicTageTheme
import kotlinx.coroutines.*
import java.io.File
import java.util.Locale
import kotlin.random.Random

/**
 * 扫描汇总通知的 ID。
 *
 * 注意它放在**文件顶层**而不是 companion 里：底下的 [scanAndNotifyLocalSongs] 是顶层函数，
 * 而 companion 里的 `private` 成员对顶层函数是不可见的（Kotlin 的可见性规则），
 * 放在这里上面下面都能用 喵~
 */
private const val SCAN_SUMMARY_NOTIFICATION_ID = 100

class MainActivity : ComponentActivity() {
    companion object {
        private var hasScanned = false
        private var scanJob: Job? = null // 用于取消扫描任务
        const val EXTRA_SONG_ID = "EXTRA_SONG_ID"
        const val EXTRA_SONG_TITLE = "EXTRA_SONG_TITLE"
        const val EXTRA_SONG_ARTIST = "EXTRA_SONG_ARTIST"

        /** 扫描时发出去的歌曲通知 ID，用于精确清理 喵~ */
        private val postedSongNotificationIds = mutableListOf<Int>()

        // 停止扫描
        fun stopScanning() {
            scanJob?.cancel()
            scanJob = null
        }

        /** 记下一条刚发出的歌曲通知，方便之后精确撤掉 喵~ */
        fun rememberSongNotification(notificationId: Int) {
            postedSongNotificationIds.add(notificationId)
        }

        /**
         * 只清掉 App 自己发的「歌曲信息」通知。
         *
         * 这里以前是 `notificationManager.cancelAll()`——那是把 App 的所有通知一锅端，
         * 连歌词通知和 Media3 的前台服务通知都会一起清掉；而前台服务通知被清掉，
         * 在部分 ROM 上会连累服务本身（服务一停，歌词通知自然也就没了）。
         * 现在只撤我们自己发的那一批 喵~
         */
        fun cancelSongNotifications(context: Context) {
            val notificationManager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            postedSongNotificationIds.forEach { notificationManager.cancel(it) }
            postedSongNotificationIds.clear()
            notificationManager.cancel(SCAN_SUMMARY_NOTIFICATION_ID)
        }
    }

    private lateinit var viewModel: PlayerViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // ★ 启动去向（照原版 10-17 的逻辑，2026-11-12 补回）喵~
        //   · 从歌曲通知点进来 → 记下意图，正常显示播放界面
        //   · 已经跑过冷启动 / 从通知点过歌 → 直接进「音乐管理」
        val launchedBySongNotification = intent?.getLongExtra(EXTRA_SONG_ID, -1L) != -1L
        if (launchedBySongNotification) {
            LaunchState.fromSongNotification = true
        }
        if (!launchedBySongNotification && LaunchState.shouldOpenManage()) {
            startActivity(
                Intent(this, DatabaseManagementActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            )
            finish()
            return
        }
        LaunchState.sawColdStart = true

        createNotificationChannel(this)

        // 检查启动 Intent：如果是桌面正常启动（未带特例 Notification Extra 且 ACTION_MAIN），且满足条件，跳转到 DatabaseManagementActivity
        val isNotificationLaunch = intent?.hasExtra(EXTRA_SONG_ID) == true
        if (!isNotificationLaunch && intent?.action == Intent.ACTION_MAIN && intent?.hasCategory(Intent.CATEGORY_LAUNCHER) == true) {
            val dbIntent = Intent(this, DatabaseManagementActivity::class.java)
            startActivity(dbIntent)
            finish()
            return
        }

        enableEdgeToEdge()
        setContent {
            viewModel = viewModel()
            MusicTageTheme {
                PlayerHomeScreen(viewModel)
            }
        }

        if (checkAllPermissions(this) && !hasScanned) {
            hasScanned = true
            scanJob = scanAndNotifyLocalSongs(this)
            val intent = Intent(this, PlaybackService::class.java)
            startService(intent)
        }

        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent) // 关键：更新 Activity 的 Intent
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        // 冷启动的第一次 onResume 只记账；之后每次回到前台，满足条件就进音乐管理 ✓
        if (!LaunchState.coldStartResumeSeen) {
            LaunchState.coldStartResumeSeen = true
        } else {
            val i = intent
            if ((i == null || i.getLongExtra(EXTRA_SONG_ID, -1L) == -1L) &&
                LaunchState.shouldOpenManage()
            ) {
                startActivity(
                    Intent(this, DatabaseManagementActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                )
                finish()
                return
            }
        }
    }

    private fun handleIntent(intent: Intent?) {
        intent?.let {
            val songId = it.getLongExtra(EXTRA_SONG_ID, -1L)
            val title = it.getStringExtra(EXTRA_SONG_TITLE) ?: ""
            val artist = it.getStringExtra(EXTRA_SONG_ARTIST) ?: ""
            
            if (songId != -1L) {
                // 点击通知后：清掉我们自己发的那批歌曲通知并停止后续发送
                // （以前这里是 cancelAll，会把歌词通知和前台服务通知一起清掉）喵~
                cancelSongNotifications(this)
                stopScanning()

                // 确保在主线程调用
                lifecycleScope.launch {
                    // 1. 等待一小会儿确保 MediaController 连接成功并触发播放
                    delay(500)
                    viewModel.playSong(songId, title, artist)
                    
                    // 2. 播放动作触发后，立即执行伪退回动作（模拟点击通知后的闪退）
                    delay(100)
                    moveTaskToBack(true)
                }
            }
        }
    }
}

// 检查所有必要权限
fun checkAllPermissions(context: Context): Boolean {
    val basicPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        listOf(Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.READ_MEDIA_AUDIO)
    } else {
        listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }
    
    val basicGranted = basicPermissions.all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    // 检查“所有文件访问权限” (Android 11+)
    val manageStorageGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Environment.isExternalStorageManager()
    } else {
        true
    }

    return basicGranted && manageStorageGranted
}

// 创建通知渠道
fun createNotificationChannel(context: Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val name = "测试通知渠道"
        val descriptionText = "用于发送测试通知"
        val importance = NotificationManager.IMPORTANCE_DEFAULT
        val channel = NotificationChannel("TEST_CHANNEL_ID", name, importance).apply {
            description = descriptionText
            setSound(null, null)
        }
        val notificationManager: NotificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(channel)
    }
}

// 读取本地音频文件并为每首歌发送通知
fun scanAndNotifyLocalSongs(context: Context): Job {
    val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    val channelId = "TEST_CHANNEL_ID"
    val groupKey = "MUSIC_LIST_GROUP"
    val database = AppDatabase.getDatabase(context)

    return CoroutineScope(Dispatchers.IO).launch {
        // 整段扫描包在 try/catch 里：它是在 App 启动后**自动**跑的，
        // 以前任何一次意外（MediaStore 返回 null、数据库写入失败、通知异常）
        // 都会直接崩进程，表现就是"打开 App 什么都没做就闪退" 喵~
        try {
        // 扫描逻辑统一收在 MediaLibraryScanner：媒体库常规查询 + 优先目录整目录找文件。
        // 以前这里和「音乐管理」里各抄了一份，改一处忘一处 喵~
        val songEntities = MediaLibraryScanner.scan(
            context = context,
            priorityDirs = AppPrefs.getPriorityScanDirs(context),
        )

        // 保存到数据库 (使用 IGNORE 策略，确保已重命名的信息不会被覆盖)
        if (songEntities.isNotEmpty()) {
            database.songDao().insertSongs(songEntities)
        }

        // 从数据库读取最新数据（包含重命名后的信息，且排除已拉黑歌曲）
        val dbSongs = database.songDao().getSongsForNotification()

        if (!isActive) return@launch // 检查协程是否已被取消

        withContext(Dispatchers.Main) {
            if (dbSongs.isEmpty()) return@withContext

            val summaryNotification = NotificationCompat.Builder(context, channelId)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("发现本地歌曲")
                .setContentText("已检测到 ${dbSongs.size} 首歌曲")
                .setGroup(groupKey)
                .setGroupSummary(true)
                .setAutoCancel(true)
                .build()
            notificationManager.notify(SCAN_SUMMARY_NOTIFICATION_ID, summaryNotification)

            dbSongs.shuffled().forEach { song ->
                if (!isActive) return@forEach // 每次发送前检查取消状态

                val intent = Intent(context, MainActivity::class.java).apply {
                    putExtra(MainActivity.EXTRA_SONG_ID, song.id)
                    putExtra(MainActivity.EXTRA_SONG_TITLE, song.title)
                    putExtra(MainActivity.EXTRA_SONG_ARTIST, song.artist)
                    flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
                
                val pendingIntent = PendingIntent.getActivity(
                    context,
                    song.id.hashCode(),
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )

                val notification = NotificationCompat.Builder(context, channelId)
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle(song.title)
                    .setContentText(song.artist)
                    .setGroup(groupKey)
                    .setPriority(NotificationCompat.PRIORITY_LOW)
                    .setAutoCancel(true)
                    .setContentIntent(pendingIntent)
                    .build()

                val notificationId = 101 + song.id.hashCode()
                notificationManager.notify(notificationId, notification)
                // 记下来，之后「清空歌曲通知」只撤这一批，不动歌词通知与前台服务通知 喵~
                MainActivity.rememberSongNotification(notificationId)
                delay(Random.nextLong(300, 5001))
            }
        }
        } catch (e: CancellationException) {
            // 用户进了「音乐管理」界面会主动取消扫描，这不是错误，原样抛出让协程正常结束 喵~
            throw e
        } catch (e: Throwable) {
            // 扫描失败不是致命错误：记一笔日志就算了，绝不能让它把 App 带走 喵~
            Log.e("MusicTage", "扫描本地歌曲失败", e)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerHomeScreen(viewModel: PlayerViewModel = viewModel()) {
    // 保持对逻辑状态的订阅
    val isPlaying by viewModel.isPlaying.collectAsState()
    val currentSongTitle by viewModel.currentSongTitle.collectAsState()
    val currentPosition by viewModel.currentPosition.collectAsState()
    val duration by viewModel.duration.collectAsState()
    
    val context = LocalContext.current
    
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
                // 基本权限通过但缺失所有文件访问权限，引导去设置
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.parse("package:${context.packageName}")
                }
                context.startActivity(intent)
            } else {
                scanAndNotifyLocalSongs(context)
                val intent = Intent(context, PlaybackService::class.java)
                context.startService(intent)
            }
        }
    }

    LaunchedEffect(Unit) {
        val basicPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            listOf(Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.READ_MEDIA_AUDIO)
        } else {
            listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

        val basicGranted = basicPermissions.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }

        if (!basicGranted) {
            launcher.launch(basicPermissions.toTypedArray())
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            // 如果基本权限已过但缺失“所有文件访问权限”，跳转到系统设置页
            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                data = Uri.parse("package:${context.packageName}")
            }
            context.startActivity(intent)
        } else {
            // 如果权限已通过，进入界面后延迟极短时间（0.1秒）执行伪退回动作
            delay(100)
            (context as? ComponentActivity)?.moveTaskToBack(true)
        }
    }

    // 彻底清除所有控件，只保留一个全屏背景
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    )
}


@Preview(showBackground = true)
@Composable
fun PlayerPreview() {
    MusicTageTheme {
        PlayerHomeScreen()
    }
}
