package com.example.musictage

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.example.musictage.utils.AppPrefs

/**
 * 设置弹窗：播放界面顶部的「设置」文字，和「音乐管理」标题长按，打开的都是这一个 喵~
 *
 * 以前这段代码长在 [DatabaseManagementActivity] 里，只有那一处能打开 ✓
 * 现在抽成公用 composable，两个入口共用同一份实现 ✓
 * 免得以后加设置项时改一处忘一处 ✗
 *
 * 状态全都在 [AppPrefs] 里有持久化副本 ✓ 弹窗每次打开时重新读一遍 ✓
 * 所以哪一边改完，另一边下次打开看到的都是新值 ✓
 */
@Composable
fun SettingsDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current

    // 歌词通知开关：默认开启，状态持久化在 AppPrefs 中，与实际播放逻辑共享同一份值 ✓
    var lyricNotificationEnabled by remember {
        mutableStateOf(AppPrefs.isLyricNotificationEnabled(context))
    }

    /** 优先扫描目录（可增删），以及“添加目录”输入框的内容 喵~ */
    var priorityDirs by remember { mutableStateOf(AppPrefs.getPriorityScanDirs(context)) }
    var newPriorityDir by remember { mutableStateOf("") }


    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            // 保留 verticalScroll：下面的「优先扫描目录」是个会长高的列表，
            // 小屏上不滚动的话下半截会被直接截掉（点都点不到 ✗）✓
            //（「音效设置」已经搬到播放器的音乐图标里，那边是独立弹窗 ✓）
            Column(
                modifier = Modifier
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = "设置",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )

                Spacer(modifier = Modifier.height(10.dp))

                // ★ 像素微动（防 OLED 烧屏）
                var pixelShiftOn by remember {
                    mutableStateOf(AppPrefs.isPixelShiftEnabled(context))
                }
                SettingsSwitchRow(
                    title = "像素微动",
                    subtitle = "每 5 秒把整个界面平移 0~3dp，防止 OLED 烧屏（默认开启）",
                    checked = pixelShiftOn,
                    onCheckedChange = { enabled ->
                        pixelShiftOn = enabled
                        AppPrefs.setPixelShiftEnabled(context, enabled)
                    },
                )

                Spacer(modifier = Modifier.height(10.dp))

                Spacer(modifier = Modifier.height(10.dp))

                // ★ 横屏布局反转（每 60 秒封面↔信息互换）
                var landscapeSwapOn by remember {
                    mutableStateOf(AppPrefs.isLandscapeSwapEnabled(context))
                }
                SettingsSwitchRow(
                    title = "横屏布局反转",
                    subtitle = "横屏时每 60 秒把封面和信息互换一次（默认开启）",
                    checked = landscapeSwapOn,
                    onCheckedChange = { enabled ->
                        landscapeSwapOn = enabled
                        AppPrefs.setLandscapeSwapEnabled(context, enabled)
                    },
                )

                // ★ 彩蛋（ChilledWindows）
                var chilledEggOn by remember {
                    mutableStateOf(AppPrefs.isChilledEggEnabled(context))
                }
                SettingsSwitchRow(
                    title = "彩蛋",
                    subtitle = "在播放器横屏时长按空白处触发（默认开启）",
                    checked = chilledEggOn,
                    onCheckedChange = { enabled ->
                        chilledEggOn = enabled
                        AppPrefs.setChilledEggEnabled(context, enabled)
                    },
                )

                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(8.dp))

                // 歌词通知：把每次变化同时落盘，并由 PlayerViewModel 立即响应
                SettingsSwitchRow(
                    title = "歌词通知",
                    subtitle = "播放时在当前歌词行变化时推送系统通知",
                    checked = lyricNotificationEnabled,
                    onCheckedChange = { enabled ->
                        lyricNotificationEnabled = enabled
                        // 只写持久化设置就够了：歌词通知循环住在 PlaybackService 里，
                        // 它每一拍都会读这个开关——关掉时会把已展示的通知收掉，
                        // 重新打开时下一拍就自动恢复，不需要界面再去通知谁 喵~
                        AppPrefs.setLyricNotificationEnabled(context, enabled)
                    },
                )

                Spacer(modifier = Modifier.height(10.dp))

                // ★ 新增（用户需求）：歌词通知【关闭翻译】
                //   开 = 通知里只有原文，不带译文
                //   关 = 照旧带译文（默认）
                //   和下棋那个「歌词通知」是独立的两件事 ✓ 所以不复用同一个状态 ✗
                //   落盘即可：发通知的是 PlaybackService，它每次都读这个值 ✓
                var lyricNoTranslation by remember {
                    mutableStateOf(AppPrefs.isLyricNotificationNoTranslation(context))
                }
                SettingsSwitchRow(
                    title = "歌词通知关闭翻译",
                    subtitle = "打开后，歌词通知里只显示原文，不再显示译文",
                    checked = lyricNoTranslation,
                    onCheckedChange = { enabled ->
                        lyricNoTranslation = enabled
                        AppPrefs.setLyricNotificationNoTranslation(context, enabled)
                    },
                )

                Spacer(modifier = Modifier.height(10.dp))

                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(8.dp))

                // ★ 新增（用户需求 ✓）：歌曲列表序号开关
                //   开启后「音乐管理」列表每条左边显示「1、2、3、…」✓
                //   歌名一行 ✓ 歌手换行在下一行（与原来一致 ✓）序号只占最左边一列 ✗ 不改动原有排版 ✓
                //   状态持久化在 AppPrefs ✓ 列表重组时读它 ✓ 改完立刻生效 ✓
                var showSongIndex by remember {
                    mutableStateOf(AppPrefs.isShowSongIndex(context))
                }
                SettingsSwitchRow(
                    title = "显示歌曲序号",
                    subtitle = "在歌曲列表左边显示「1、2、3、…」序号",
                    checked = showSongIndex,
                    onCheckedChange = { enabled ->
                        showSongIndex = enabled
                        // 落盘即可 ✓ 列表界面读的就是这个值 ✓ 无需再通知谁 ✓
                        AppPrefs.setShowSongIndex(context, enabled)
                    },
                )

                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(8.dp))

                // ===== 优先扫描目录 =====
                // 这些目录会被**整目录递归找文件**，不再依赖媒体库那条查询，
                // 专门用来救"媒体库里有、但常规扫描就是捞不出来"的歌 喵~
                Text(
                    text = "优先扫描目录",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "这些目录（含子目录）里的音频一定会被扫进来，不受 30 秒时长与媒体库收录状态影响。" +
                            "填相对路径即可，例如 Download/kgmusic；下次扫描生效。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(modifier = Modifier.height(6.dp))

                priorityDirs.forEach { dir ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = dir,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        TextButton(
                            onClick = {
                                val updated = priorityDirs - dir
                                priorityDirs = updated
                                AppPrefs.setPriorityScanDirs(context, updated.toSet())
                            }
                        ) {
                            Text("移除", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = newPriorityDir,
                        onValueChange = { newPriorityDir = it },
                        label = { Text("添加目录") },
                        placeholder = { Text("例如 Download/kgmusic") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        enabled = newPriorityDir.isNotBlank(),
                        onClick = {
                            val dir = newPriorityDir.trim().trim('/', ' ')
                            if (dir.isNotEmpty()) {
                                val updated = (priorityDirs + dir).distinct().sorted()
                                priorityDirs = updated
                                AppPrefs.setPriorityScanDirs(context, updated.toSet())
                                newPriorityDir = ""
                            }
                        }
                    ) {
                        Text("添加")
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss) { Text("关闭") }
                }
            }
        }
    }
}

/** 带标题与说明文字的一行开关 */
@Composable
internal fun SettingsSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.secondary,
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
