package com.example.musictage

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage

/**
 * 播放器主界面（全屏）。
 *
 * 视觉上是一层由封面放大模糊得到的弥散背景，加一层黑色遮罩，再叠正常内容：
 * 顶栏、大圆角封面、歌曲信息、进度条、播放控制 喵~
 *
 * 界面只读取 [PlayerViewModel] 暴露的状态，所有播放控制仍由 ViewModel 统一转发给
 * MediaController，避免这里出现第二份播放状态 喵~
 */
@Composable
fun PlayerScreen(
    playerViewModel: PlayerViewModel,
    onBackClick: () -> Unit
) {
    val title by playerViewModel.currentSongTitle.collectAsState()
    val artist by playerViewModel.currentArtist.collectAsState()
    val artwork by playerViewModel.currentArtworkUri.collectAsState()
    val isPlaying by playerViewModel.isPlaying.collectAsState()
    val currentPosition by playerViewModel.currentPosition.collectAsState()
    val duration by playerViewModel.duration.collectAsState()

    // 没有封面时统一回退到系统默认图，避免 AsyncImage 收到 null 后留下空洞 喵~
    val artworkModel: Any = artwork ?: android.R.drawable.ic_menu_gallery

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // 1. 幻彩弥散背景
        AsyncImage(
            model = artworkModel,
            contentDescription = null,
            modifier = Modifier
                .fillMaxSize()
                .blur(60.dp),
            contentScale = ContentScale.Crop
        )

        // 遮罩层
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.45f))
        )

        // 2. 沉浸式布局内容
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            PlayerTopBar(onBackClick)

            Spacer(modifier = Modifier.weight(0.1f))

            // 3. 核心视觉组件：大圆角封面
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(0.5f),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.72f)
                        .aspectRatio(1f)
                        .shadow(
                            elevation = 20.dp,
                            shape = RoundedCornerShape(32.dp),
                            ambientColor = Color.Black.copy(alpha = 0.5f),
                            spotColor = Color.Black
                        )
                        .clip(RoundedCornerShape(32.dp))
                        .background(Color.DarkGray)
                ) {
                    AsyncImage(
                        model = artworkModel,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )

                    // 音质标签
                    Row(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        QualityTag("Hi-Res")
                        QualityTag("FLAC | 48kHz | 24bit")
                    }
                }
            }

            Spacer(modifier = Modifier.weight(0.05f))

            // 4. 歌曲信息与操作
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 28.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = title,
                            color = Color.White,
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = artist,
                            color = Color.White.copy(alpha = 0.6f),
                            fontSize = 16.sp,
                            maxLines = 1
                        )
                    }
                    Icon(
                        imageVector = Icons.Outlined.FavoriteBorder,
                        contentDescription = "Favorite",
                        tint = Color.White.copy(alpha = 0.5f),
                        modifier = Modifier.size(28.dp)
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))

                // 功能图标行
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(24.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { }, modifier = Modifier.size(24.dp)) {
                        Icon(Icons.Outlined.Download, null, tint = Color.White.copy(alpha = 0.6f))
                    }
                    IconButton(onClick = { }, modifier = Modifier.size(24.dp)) {
                        Icon(Icons.Outlined.Timer, null, tint = Color.White.copy(alpha = 0.6f))
                    }
                    IconButton(onClick = { }, modifier = Modifier.size(24.dp)) {
                        Icon(Icons.Outlined.MoreVert, null, tint = Color.White.copy(alpha = 0.6f))
                    }
                }

                Spacer(modifier = Modifier.height(32.dp))

                // 进度条。
                // 注意 seekTo 接收的是 0..1 的比例而不是毫秒，所以滑块本身也跑比例，
                // 拖动时只做一次换算，避免把毫秒当成比例传进去导致进度瞬间跳到结尾 喵~
                Column(modifier = Modifier.fillMaxWidth()) {
                    val progress = if (duration > 0L) {
                        (currentPosition.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
                    } else {
                        0f
                    }
                    Slider(
                        value = progress,
                        onValueChange = { playerViewModel.seekTo(it) },
                        valueRange = 0f..1f,
                        colors = SliderDefaults.colors(
                            thumbColor = Color.White,
                            activeTrackColor = Color.White,
                            inactiveTrackColor = Color.White.copy(alpha = 0.3f)
                        ),
                        modifier = Modifier.height(4.dp)
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(formatPlayerTime(currentPosition), color = Color.White.copy(alpha = 0.5f), fontSize = 12.sp)
                        Text(formatPlayerTime(duration), color = Color.White.copy(alpha = 0.5f), fontSize = 12.sp)
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // 控制按钮
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { }, modifier = Modifier.size(24.dp)) {
                        Icon(Icons.Default.Shuffle, null, tint = Color.White.copy(alpha = 0.5f))
                    }
                    IconButton(onClick = { playerViewModel.skipPrevious() }, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Default.SkipPrevious, null, tint = Color.White, modifier = Modifier.size(32.dp))
                    }
                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .clip(CircleShape)
                            .background(Color.White)
                            .clickable { playerViewModel.playPause() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = null,
                            tint = Color.Black,
                            modifier = Modifier.size(36.dp)
                        )
                    }
                    IconButton(onClick = { playerViewModel.skipNext() }, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Default.SkipNext, null, tint = Color.White, modifier = Modifier.size(32.dp))
                    }
                    IconButton(onClick = { }, modifier = Modifier.size(24.dp)) {
                        Icon(Icons.Default.List, null, tint = Color.White.copy(alpha = 0.5f))
                    }
                }
            }
            Spacer(modifier = Modifier.height(40.dp))
        }
    }
}

/** 把毫秒格式化成 mm:ss；负数与非法值一律按 0 处理 喵~ */
private fun formatPlayerTime(ms: Long): String {
    val safeMs = ms.coerceAtLeast(0L)
    val totalSeconds = safeMs / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%02d:%02d".format(minutes, seconds)
}

@Composable
fun PlayerTopBar(onBackClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBackClick) {
            Icon(
                imageVector = Icons.Default.KeyboardArrowDown,
                contentDescription = "Close",
                tint = Color.White,
                modifier = Modifier.size(32.dp)
            )
        }

        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "歌曲",
                    color = Color.White,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Box(
                    modifier = Modifier
                        .width(18.dp)
                        .height(2.dp)
                        .background(Color.White, RoundedCornerShape(1.dp))
                )
            }
            Spacer(modifier = Modifier.width(28.dp))
            Text(
                text = "歌词",
                color = Color.White.copy(alpha = 0.5f),
                fontSize = 17.sp
            )
        }

        Spacer(modifier = Modifier.size(48.dp))
    }
}

@Composable
fun QualityTag(text: String) {
    Surface(
        color = Color.Black.copy(alpha = 0.4f),
        shape = RoundedCornerShape(4.dp)
    ) {
        Text(
            text = text,
            color = Color.White.copy(alpha = 0.8f),
            fontSize = 9.sp,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            fontWeight = FontWeight.Medium
        )
    }
}
