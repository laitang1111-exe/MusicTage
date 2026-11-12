package com.example.musictage.utils

import kotlin.random.Random

/**
 * 「连续通知」测试功能。
 *
 * 用途是压测通知系统：开启后，每发一条歌词通知都有一定概率**再连发 100 条同样的通知**，
 * 用来复现"通知一多会怎样"这类问题 喵~
 *
 * 状态刻意只放在内存里、**不持久化**：这是测试开关，重启就该回到关闭，
 * 免得哪天忘了关把自己刷屏 喵~
 *
 * 开关方式（都在「音乐管理」的搜索框里操作）：
 * - 输入 `连续通知` 后点搜索 → 开启（概率回到默认 75%）
 * - 输入 `关闭` 后点搜索 → 关闭
 * - 已开启时输入数字 → 改概率，支持 `75` / `75%` / `75％` / `0.75` 这些写法 喵~
 */
object NotificationBurstTest {

    /** 开启关键字 喵~ */
    private const val ENABLE_KEYWORD = "连续通知"

    /** 关闭关键字 喵~ */
    private const val DISABLE_KEYWORD = "关闭"

    /** 每次触发连发多少条 喵~ */
    const val BURST_COUNT = 100

    /** 默认触发概率：75% 喵~ */
    const val DEFAULT_PROBABILITY = 0.75f

    /**
     * 连发用的通知 ID 起始值。
     *
     * 用**负数**是为了和别的通知彻底错开：歌词通知的 ID 取自歌词行时间戳（非负），
     * 扫描通知是 100/101+，前台服务通知由系统管——负数这段没人用 喵~
     */
    const val BURST_ID_BASE = -1

    /** 每条之间的间隔：连发 100 条约 2.5 秒，够快，又不会把主线程堵死 喵~ */
    const val BURST_INTERVAL_MS = 25L

    /** 当前是否开启；重启即回到 false 喵~ */
    @Volatile
    var enabled: Boolean = false
        private set

    /** 当前触发概率（0.01 ~ 1.0）喵~ */
    @Volatile
    var probability: Float = DEFAULT_PROBABILITY
        private set

    /** 这一条歌词通知要不要触发连发 喵~ */
    fun shouldBurst(): Boolean = enabled && Random.nextFloat() < probability

    /** 搜索框里认出来的命令 喵~ */
    sealed class Command {
        /** 开启（并把概率恢复成默认值） */
        data object Enable : Command()

        /** 关闭 */
        data object Disable : Command()

        /** 改概率 */
        data class SetProbability(val value: Float) : Command()
    }

    /**
     * 解析搜索框里的输入。
     *
     * @param currentlyEnabled 当前是否已开启——数字**只有在该功能已开启时**才当命令处理，
     *        否则"输入 123 搜索"这种正常用法会被误吃 喵~
     * @return null 表示"这不是命令，按普通搜索处理" 喵~
     */
    fun parseCommand(input: String, currentlyEnabled: Boolean): Command? {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return null
        if (trimmed == ENABLE_KEYWORD) return Command.Enable
        if (trimmed == DISABLE_KEYWORD) return Command.Disable
        if (!currentlyEnabled) return null

        val ratio = parsePercentage(trimmed) ?: return null
        return Command.SetProbability(ratio)
    }

    /** 执行命令并返回给用户看的提示文案 喵~ */
    fun apply(command: Command): String = when (command) {
        is Command.Enable -> {
            enabled = true
            probability = DEFAULT_PROBABILITY
            "连续通知：已开启（概率 ${probabilityText()}）。" +
                    "已开启时输入数字可改概率，输入「关闭」停止"
        }

        is Command.Disable -> {
            enabled = false
            "连续通知：已关闭"
        }

        is Command.SetProbability -> {
            probability = command.value
            "连续通知：概率已改为 ${probabilityText()}"
        }
    }

    /** 概率文案，例如 "75%" 喵~ */
    fun probabilityText(): String = "${(probability * 100).toInt()}%"

    /**
     * 解析概率写法。
     *
     * 认这些：`0.75`（小数比例）、`75`、`75%`、`75％`（全角百分号）、`７５％`（全角数字）；
     * 空白随便夹。判断规则：带百分号、或者数值大于 1，就按"百分之几"理解 喵~
     */
    private fun parsePercentage(raw: String): Float? {
        val builder = StringBuilder()
        var hasPercentSign = false

        for (ch in raw) {
            when {
                // 全角数字要先判，因为 Char.isDigit() 对它们也返回 true 喵~
                ch in '０'..'９' -> builder.append('0' + (ch - '０'))
                ch in '0'..'9' -> builder.append(ch)
                ch == '.' -> builder.append('.')
                // 半角与全角百分号都认（这就是"大小写/全半角适配"那件事）喵~
                ch == '%' || ch == '％' -> hasPercentSign = true
                ch.isWhitespace() -> Unit
                else -> return null
            }
        }

        if (builder.isEmpty()) return null
        val value = builder.toString().toFloatOrNull() ?: return null
        if (value <= 0f) return null

        val ratio = if (value <= 1f && !hasPercentSign) value else value / 100f
        // 夹在 1% ~ 100% 之间：填 0 或 999 都不至于让功能失效 喵~
        return ratio.coerceIn(0.01f, 1f)
    }
}
