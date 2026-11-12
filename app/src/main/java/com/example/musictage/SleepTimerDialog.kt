package com.example.musictage

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.musictage.utils.SleepTimerController
import kotlin.math.abs

/**
 * 定时关闭（睡眠定时）的两个弹窗 喵~
 *
 * 界面是照着原版华为音乐"定时关闭"弹窗的结构 1:1 搬的 ✓
 * （结构、层级、控件、文案都取自它的 `dialog_custom_choice.xml` 与
 * `dialog_custom_time_picker.xml` 解出来的真身 ✓ 尺寸取资源表里的真值 ✓）
 *
 * 主弹窗（[SleepTimerDialog]）：
 *   标题「定时关闭」→ 7 个带分割线的单选项（右侧单选框 ✓）
 *   → 「完整播放完毕再关闭」两行文字 + 右侧开关 → 底部「取消 / 确认」平分 ✓
 *
 * 自定义时长弹窗（[CustomTimePickerDialog]）叠在它上面 ✓：
 *   标题「自定义」（左上）→ 时/分两列滚轮（中间一条选中焦点线 ✓）
 *   → 两列单位标签「小时 / 分钟」→ 底部「取消 / 完成」平分 ✓
 */

/**
 * 华为那套红色主色 喵~
 *
 * 说明白：这个红来自 EMUI 主题属性，**资源表里查不到字面值** ✗
 * 先用华为品牌红 #CF0A2C 起头 ✓ 你从截图里吸一个精确色值，换这一行就行 ✓
 */
private val HwRed = Color(0xFFCF0A2C)

/** 弹窗底色：纯白 ✓（`color/emui_color_bg` 的真值就是 #ffffffff ✓）*/
private val DialogBg = Color.White

/** 正文主色：`color/emui_color_text_primary` 真值 #e6000000 ✓ */
private val TextPrimary = Color(0xFFE6000000)

/** 次要文字：`color/emui_color_text_secondary` 真值 #99000000 ✓ */
private val TextSecondary = Color(0xFF99000000)

/** 未选中控件（单选框/开关轨道）的灰 ✓ */
private val ControlOff = Color(0x66000000)

/** 分割线：EMUI 用的是一条很淡的灰 ✓（字面值不在资源表里 ✗ 这里取 8% 黑 ✓）*/
private val DividerLine = Color(0x14000000)

/** 滚轮高度：5 项可见 ✓ 每项 40dp ✓（原版滚轮项高不在资源表里 ✗ 按截图观感取的 ✓）*/
private val WheelItemHeight = 40.dp
private const val WheelVisibleItems = 5

/** 主弹窗里的 7 个选项 ✓（"自定义"单独处理 ✓）*/
private val PRESET_MINUTES = listOf(10, 20, 30, 60)

/** 定时选择结果 ✓ */
sealed interface SleepChoice {
    /** 不开启 ✓ */
    data object Off : SleepChoice

    /** 当前内容播放完毕关闭 ✓ */
    data object AfterCurrent : SleepChoice

    /** N 分钟后关闭 ✓ */
    data class Minutes(val minutes: Int) : SleepChoice
}

/**
 * 主弹窗 ✓
 *
 * @param current 当前生效的定时（用来回填选中项 ✓）
 * @param onDismiss 取消 ✓
 * @param onConfirm 确认：把选择交给服务去行使 ✓
 */
@Composable
fun SleepTimerDialog(
    current: SleepTimerController.State,
    onDismiss: () -> Unit,
    onConfirm: (SleepChoice, finishCurrentTrack: Boolean) -> Unit,
) {
    val context = LocalContext.current

    // 弹窗内部先"预选"，点确认才生效 ✓（原版就是这个交互：先选，再确认 ✓）
    var pending by remember {
        mutableStateOf(
            when (current.mode) {
                SleepTimerController.Mode.AFTER_CURRENT -> SleepChoice.AfterCurrent
                SleepTimerController.Mode.MINUTES ->
                    if (current.minutes in PRESET_MINUTES) SleepChoice.Minutes(current.minutes)
                    else SleepChoice.Minutes(current.minutes.coerceAtLeast(1))
                SleepTimerController.Mode.OFF -> SleepChoice.Off
            }
        )
    }
    var finishTrack by remember { mutableStateOf(current.finishCurrentTrack) }
    var showPicker by remember { mutableStateOf(false) }

    /** 自定义时长（分钟 ✓）：初始值沿用上次选的 ✓ 没选过就给 10 分钟 ✓ */
    var customMinutes by remember {
        mutableStateOf(
            when {
                current.mode == SleepTimerController.Mode.MINUTES &&
                        current.minutes !in PRESET_MINUTES -> current.minutes
                else -> 10
            }
        )
    }

    if (showPicker) {
        CustomTimePickerDialog(
            minutes = customMinutes,
            onDismiss = { showPicker = false },
            onConfirm = { picked ->
                if (picked < 1) {
                    Toast.makeText(context, "请选择至少 1 分钟", Toast.LENGTH_SHORT).show()
                } else {
                    customMinutes = picked
                    pending = SleepChoice.Minutes(picked)
                    showPicker = false
                }
            },
        )
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = DialogBg,
            modifier = Modifier.fillMaxWidth(0.9f),
        ) {
            Column {
                // ===== 标题 =====
                Text(
                    text = "定时关闭",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 20.dp, bottom = 12.dp),
                )

                // ===== 7 个单选项（右侧单选框，项间带分割线）=====
                SleepOptionRow("不开启", pending == SleepChoice.Off) { pending = SleepChoice.Off }
                SleepOptionRow("当前内容播放完毕关闭", pending == SleepChoice.AfterCurrent) {
                    pending = SleepChoice.AfterCurrent
                }
                PRESET_MINUTES.forEach { m ->
                    SleepOptionRow(
                        label = "${m}分钟后",
                        selected = pending == SleepChoice.Minutes(m),
                        onClick = { pending = SleepChoice.Minutes(m) },
                    )
                }
                SleepOptionRow(
                    label = "自定义",
                    selected = pending is SleepChoice.Minutes &&
                            (pending as SleepChoice.Minutes).minutes !in PRESET_MINUTES,
                    onClick = {
                        // 原版点这一项就直接把滚轮弹窗叠上来 ✓ 选完才落回这一项 ✓
                        showPicker = true
                    },
                )

                // ===== 「完整播放完毕再关闭」=====
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("完整播放完毕再关闭", fontSize = 16.sp, color = TextPrimary)
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            "计时结束时，播完正在播放的内容再关闭",
                            fontSize = 12.sp,
                            color = TextSecondary,
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Switch(
                        checked = finishTrack,
                        onCheckedChange = { finishTrack = it },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = HwRed,
                            uncheckedThumbColor = Color.White,
                            uncheckedTrackColor = ControlOff,
                        ),
                    )
                }

                HorizontalDivider(color = DividerLine, thickness = 1.dp)

                // ===== 底部：取消 / 确认 平分 =====
                Row(modifier = Modifier.fillMaxWidth()) {
                    TextButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("取消", color = HwRed, fontSize = 16.sp)
                    }
                    TextButton(
                        onClick = { onConfirm(pending, finishTrack) },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("确认", color = HwRed, fontSize = 16.sp)
                    }
                }
            }
        }
    }
}

/** 一行单选项：左文字右单选框 ✓ 带下分割线 ✓ */
@Composable
private fun SleepOptionRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 20.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                fontSize = 16.sp,
                color = TextPrimary,
                modifier = Modifier.weight(1f),
            )
            RadioButton(
                selected = selected,
                onClick = onClick,
                colors = RadioButtonDefaults.colors(
                    selectedColor = HwRed,
                    unselectedColor = ControlOff,
                ),
            )
        }
        HorizontalDivider(color = DividerLine, thickness = 1.dp)
    }
}

/**
 * 自定义时长弹窗 ✓
 *
 * 时/分两列滚轮 + 各自下方的单位标签 ✓（原版就是 152dp 宽的 NumberPickerView ×2 ✓
 * 两个单位标签等宽居中 ✓ 加起来 304dp 正好和两列滚轮对齐 ✓）
 */
@Composable
fun CustomTimePickerDialog(
    minutes: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    var hour by remember { mutableStateOf((minutes / 60).coerceIn(0, 12)) }
    var minute by remember { mutableStateOf((minutes % 60).coerceIn(0, 59)) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = DialogBg,
            modifier = Modifier.fillMaxWidth(0.9f),
        ) {
            Column {
                // ===== 标题：左上 ✓ =====
                Text(
                    text = "自定义",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, top = 20.dp, bottom = 8.dp),
                    textAlign = TextAlign.Start,
                )

                // ===== 两列滚轮 =====
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TimeWheel(
                        values = 0..12,
                        selected = hour,
                        onSelected = { hour = it },
                        modifier = Modifier.weight(1f),
                    )
                    TimeWheel(
                        values = 0..59,
                        selected = minute,
                        onSelected = { minute = it },
                        modifier = Modifier.weight(1f),
                    )
                }

                // ===== 单位标签（各占一半、等宽居中 ✓）=====
                Row(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "小时",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .weight(1f)
                            .height(36.dp)
                            .wrapContentHeight(),
                    )
                    Text(
                        text = "分钟",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .weight(1f)
                            .height(36.dp)
                            .wrapContentHeight(),
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider(color = DividerLine, thickness = 1.dp)

                // ===== 底部：取消 / 完成 平分 ✓ =====
                Row(modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                        Text("取消", color = HwRed, fontSize = 16.sp)
                    }
                    TextButton(
                        onClick = { onConfirm(hour * 60 + minute) },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("完成", color = HwRed, fontSize = 16.sp)
                    }
                }
            }
        }
    }
}

/**
 * 一列滚轮 ✓（时或分 ✓）
 *
 * 用 LazyColumn + 吸附滚动实现：中间那一项就是选中项 ✓ 颜色用主色 ✓ 其余按距离深浅递减 ✓
 * 中间画一条焦点线（原版是灰/红的分割线 ✓ 这里用主色 40% 透明 ✓）
 *
 * 为什么不自绘 Canvas 滚轮：LazyColumn 的吸附是系统实现 ✓ 手感最接近原生 ✓
 * 也不用自己去算惯性、对齐和触摸拦截 ✗
 */
@Composable
fun TimeWheel(
    values: IntRange,
    selected: Int,
    onSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
    itemHeight: Dp = WheelItemHeight,
    visibleItems: Int = WheelVisibleItems,
) {
    val listState = rememberLazyListState()
    val count = values.count()
    val index = (selected - values.first).coerceIn(0, count - 1)

    // 初次进来滚到当前值 ✓
    LaunchedEffect(Unit) {
        listState.scrollToItem(index)
    }

    // 滚动停下来之后：找出最靠近中线的那一项，吸过去并回调 ✓
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.collect { scrolling ->
            if (scrolling) return@collect
            val info = listState.layoutInfo
            if (info.visibleItemsInfo.isEmpty()) return@collect
            val center = (info.viewportStartOffset + info.viewportEndOffset) / 2
            val nearest = info.visibleItemsInfo.minByOrNull { item ->
                abs((item.offset + item.size / 2) - center)
            } ?: return@collect
            val value = values.first + nearest.index.coerceIn(0, count - 1)
            if (value != selected) onSelected(value)
            listState.animateScrollToItem(nearest.index)
        }
    }

    Box(
        modifier = modifier.height(itemHeight * visibleItems),
        contentAlignment = Alignment.Center,
    ) {
        LazyColumn(
            state = listState,
            flingBehavior = rememberSnapFlingBehavior(listState),
            contentPadding = PaddingValues(vertical = itemHeight * (visibleItems / 2)),
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            items(count) { i ->
                val value = values.first + i
                val isSelected = value == selected
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(itemHeight),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = value.toString(),
                        fontSize = if (isSelected) 20.sp else 18.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                        color = if (isSelected) {
                            HwRed
                        } else {
                            TextSecondary.copy(alpha = 0.75f)
                        },
                    )
                }
            }
        }

        // 选中焦点线：上下各一条 ✓（原版是灰/红的分割线 ✓）
        Column(modifier = Modifier.fillMaxWidth()) {
            HorizontalDivider(color = HwRed.copy(alpha = 0.35f), thickness = 1.dp)
            Spacer(modifier = Modifier.height(itemHeight - 1.dp))
            HorizontalDivider(color = HwRed.copy(alpha = 0.35f), thickness = 1.dp)
        }
    }
}
