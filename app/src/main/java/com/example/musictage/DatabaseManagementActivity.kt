package com.example.musictage

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.musictage.data.AppDatabase
import com.example.musictage.data.SongEntity
import com.example.musictage.ui.theme.MusicTageTheme
import kotlinx.coroutines.launch

/**
 * 音乐管理界面（2026-11-12 重写，对齐 10-17 原版外观）喵~
 *
 * 原版长这样：
 *   · 顶部居中标题「音乐管理（共 N 首）」，左音符图标，右搜索图标
 *   · 每行是 Material3 [ListItem]：
 *       上标 = 序号「1、」
 *       标题 = 歌名
 *       副标题 = 歌手（空则 <unknown>）
 *       右侧 = 时长，**有歌词绿色 / 没歌词红色**
 *   · 长按弹出菜单：匹配歌词 / 歌词偏移 / 路径定位 / 重命名 / 删除
 *
 * 时长配色是从原版反编译里读出来的常量：
 *   有歌词 0xFF4CAF50（绿 500）
 *   没歌词 0xFFF44336（红 500）
 */
class DatabaseManagementActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dao = AppDatabase.getDatabase(this).songDao()

        setContent {
            MusicTageTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    DatabaseManagementScreen(dao = dao)
                }
            }
        }
    }
}

private val LYRIC_COLOR = Color(0xFF4CAF50)
private val NO_LYRIC_COLOR = Color(0xFFF44336)

@Composable
fun DatabaseManagementScreen(dao: com.example.musictage.data.SongDao) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val songs by dao.getAllSongs().collectAsStateWithLifecycle(initialValue = emptyList())

    var searchOpen by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }

    var songToDelete by remember { mutableStateOf<SongEntity?>(null) }
    var songToRename by remember { mutableStateOf<SongEntity?>(null) }
    var songToOffset by remember { mutableStateOf<SongEntity?>(null) }

    val shown = remember(query, songs) {
        if (query.isBlank()) {
            songs
        } else {
            songs.filter {
                it.title.contains(query, true) || it.artist.contains(query, true)
            }
        }
    }

    Scaffold(
        topBar = {
            if (searchOpen) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = { Text("搜索歌曲、歌手...") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = {
                        searchOpen = false
                        query = ""
                    }) {
                        Icon(Icons.Filled.Close, contentDescription = "关闭搜索")
                    }
                }
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Filled.MusicNote,
                        contentDescription = null,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                    Text(
                        text = "音乐管理（共 ${songs.size} 首）",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { searchOpen = true }) {
                        Icon(Icons.Filled.Search, contentDescription = "搜索")
                    }
                }
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            itemsIndexed(shown, key = { _, s -> s.id }) { index, song ->
                SongRow(
                    song = song,
                    index = index + 1,
                    onClick = { playFromList(context, song) },
                    onMatchLyric = { toast(context, "匹配歌词：下一步实现") },
                    onLyricOffset = { songToOffset = song },
                    onLocatePath = { toast(context, song.filePath) },
                    onRename = { songToRename = song },
                    onDelete = { songToDelete = song },
                )
                HorizontalDivider(
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp),
                    thickness = 0.6.dp,
                )
            }
        }
    }

    songToDelete?.let { song ->
        AlertDialog(
            onDismissRequest = { songToDelete = null },
            title = { Text("确认删除") },
            text = { Text("将从数据库移除：${song.title}") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { dao.deleteSong(song.id) }
                    songToDelete = null
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { songToDelete = null }) { Text("取消") }
            },
        )
    }

    songToRename?.let { song ->
        var title by remember(song.id) { mutableStateOf(song.title) }
        var artist by remember(song.id) { mutableStateOf(song.artist) }
        AlertDialog(
            onDismissRequest = { songToRename = null },
            title = { Text("重命名") },
            text = {
                Column {
                    OutlinedTextField(
                        value = title,
                        onValueChange = { title = it },
                        label = { Text("歌曲名称") },
                        singleLine = true,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = artist,
                        onValueChange = { artist = it },
                        label = { Text("歌手名称") },
                        singleLine = true,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { dao.updateSongMetadata(song.id, title.trim(), artist.trim()) }
                    songToRename = null
                }) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { songToRename = null }) { Text("取消") }
            },
        )
    }

    songToOffset?.let { song ->
        var offset by remember(song.id) { mutableStateOf(song.lyricOffsetMs) }
        AlertDialog(
            onDismissRequest = { songToOffset = null },
            title = { Text("歌词偏移") },
            text = {
                Column {
                    Text("当前：$offset 毫秒（正数推迟、负数提前）")
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { offset -= 50 }) { Text("− 50 毫秒") }
                        TextButton(onClick = { offset += 50 }) { Text("+ 50 毫秒") }
                        TextButton(onClick = { offset = 0 }) { Text("重置") }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { dao.updateLyricOffset(song.id, offset) }
                    songToOffset = null
                }) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { songToOffset = null }) { Text("取消") }
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SongRow(
    song: SongEntity,
    index: Int,
    onClick: () -> Unit,
    onMatchLyric: () -> Unit,
    onLyricOffset: () -> Unit,
    onLocatePath: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }

    Box {
        ListItem(
            modifier = Modifier.combinedClickable(
                onClick = onClick,
                onLongClick = { menuOpen = true },
            ),
            overlineContent = {
                Text(
                    text = "$index、",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            headlineContent = {
                Text(
                    text = song.title,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            supportingContent = {
                Text(
                    text = song.artist.ifBlank { "<unknown>" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            trailingContent = {
                Text(
                    text = formatTime(song.duration),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (song.lrcPath != null) LYRIC_COLOR else NO_LYRIC_COLOR,
                )
            },
        )

        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(text = { Text("匹配歌词") }, onClick = {
                menuOpen = false
                onMatchLyric()
            })
            DropdownMenuItem(text = { Text("歌词偏移") }, onClick = {
                menuOpen = false
                onLyricOffset()
            })
            DropdownMenuItem(text = { Text("路径定位") }, onClick = {
                menuOpen = false
                onLocatePath()
            })
            DropdownMenuItem(text = { Text("重命名") }, onClick = {
                menuOpen = false
                onRename()
            })
            DropdownMenuItem(text = { Text("删除") }, onClick = {
                menuOpen = false
                onDelete()
            })
        }
    }
}

/** 毫秒 → mm:ss 喵~ */
private fun formatTime(durationMs: Long): String {
    val totalSeconds = (durationMs / 1000).coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%02d:%02d".format(minutes, seconds)
}

/** 点列表里的歌 = 交给 MainActivity 的既有入口去播（它会顺手伪退回）喵~ */
private fun playFromList(context: android.content.Context, song: SongEntity) {
    val intent = Intent(context, MainActivity::class.java).apply {
        putExtra(MainActivity.EXTRA_SONG_ID, song.id)
        putExtra(MainActivity.EXTRA_SONG_TITLE, song.title)
        putExtra(MainActivity.EXTRA_SONG_ARTIST, song.artist)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(intent)
}

private fun toast(context: android.content.Context, text: String) {
    Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
}
