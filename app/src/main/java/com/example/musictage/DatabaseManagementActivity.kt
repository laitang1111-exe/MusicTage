package com.example.musictage

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.example.musictage.data.AppDatabase
import com.example.musictage.data.SongEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 数据库管理界面（快捷方式进来的那个）。
 *
 * 说明：这个文件在会话历史里只剩片段，没能完整捞回来。
 * 这里按 SongDao 的接口重写了最小实现：
 *   · 顶部搜索框，边打边过滤（歌名 / 歌手 / 路径）
 *   · 列表显示歌曲，右侧删除
 *   · 底部刷新 / 清空整库
 * 功能比原版少（原版还有批量匹配歌词、改歌名等），但能编译、能用。
 */
class DatabaseManagementActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dao = AppDatabase.getDatabase(this).songDao()

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    DatabaseScreen(
                        loadAll = { dao.getAllSongsOnce() },
                        onDelete = { id -> dao.deleteSong(id) },
                        onClearAll = { dao.clearAll() },
                        scope = lifecycleScope,
                    )
                }
            }
        }
    }
}

@Composable
private fun DatabaseScreen(
    loadAll: suspend () -> List<SongEntity>,
    onDelete: suspend (Long) -> Unit,
    onClearAll: suspend () -> Unit,
    scope: CoroutineScope,
) {
    var query by remember { mutableStateOf("") }
    var songs by remember { mutableStateOf<List<SongEntity>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    val io = rememberCoroutineScope()

    if (!loaded) {
        loaded = true
        scope.launch { songs = loadAll() }
    }

    val shown = remember(query, songs) {
        if (query.isBlank()) songs
        else songs.filter {
            it.title.contains(query, true) ||
                it.artist.contains(query, true) ||
                it.filePath.contains(query, true)
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("数据库管理", style = MaterialTheme.typography.titleLarge)
        Text(
            "共 ${songs.size} 首，当前显示 ${shown.size} 首",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
        )

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("搜索歌名 / 歌手 / 路径") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(onClick = { io.launch { songs = loadAll(); query = "" } }) {
                Text("刷新")
            }
            Button(onClick = {
                io.launch {
                    onClearAll()
                    songs = emptyList()
                }
            }) {
                Text("清空整库")
            }
        }

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(shown, key = { it.id }) { s ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(s.title, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "${s.artist}　${s.filePath.substringAfterLast('/')}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Button(onClick = {
                        io.launch {
                            onDelete(s.id)
                            songs = loadAll()
                        }
                    }) {
                        Text("删除")
                    }
                }
            }
        }
    }
}
