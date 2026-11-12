package com.example.musictage.utils

import android.content.Context

/**
 * 标准纯音乐模板 喵~
 *
 * ## 来源
 *
 * 工作区根目录那份 `标准纯音乐模板.lrc` ✓ 已原样拷进 `assets/instrumental_template.lrc` ✓
 * 内容就一行：
 * ```
 * [00:01.580]纯音乐，请欣赏
 * ```
 *
 * ## 用途
 *
 * 在「匹配歌词」流程的最后一步（歌词确认弹窗 ✓ `LyricFlow.ConfirmingLyric`）里
 * 用户可以勾选「这是纯音乐」✓ 勾上后：
 *   1. 弹窗里那份**候选歌词预览整块隐藏** ✗（只看模板 ✓）
 *   2. 点「确认」时**不写搜到的那份歌词** ✗ 而是把本模板写进去当这首歌的歌词 ✓✓
 *
 * ## 设计要点
 *
 * - **不落盘、不记忆** ✓ 勾选状态是 Compose 的 `remember` 本地态 ✓ 重启即复位 ✓
 * - 读不到 assets 也**绝不抛异常** ✗ 直接给 [FALLBACK] ✓ 功能不会因为文件缺失而崩 ✓
 * - `context` 只在方法参数里用 ✓ 不持有 ✗ 不会内存泄漏 ✓
 */
object InstrumentalTemplate {

    /** assets 里的路径 ✓ 刻意用英文名 ✗ 避免中文资源名在部分工具链上的坑 ✓ */
    const val ASSET_PATH: String = "instrumental_template.lrc"

    /** 兜底文本 ✓ 与 assets 里那份内容一致 ✓ */
    const val FALLBACK: String = "[00:01.580]纯音乐，请欣赏"

    /**
     * 读出模板文本 喵~
     *
     * @return assets 里的原文 ✓ 读不到或为空就返回 [FALLBACK] ✓ 永不返回空串 ✗
     */
    fun text(context: Context): String =
        runCatching {
            context.assets.open(ASSET_PATH)
                .bufferedReader(Charsets.UTF_8)
                .use { it.readText() }
        }.getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: FALLBACK
}
