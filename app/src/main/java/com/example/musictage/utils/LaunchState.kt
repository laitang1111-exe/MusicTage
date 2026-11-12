package com.example.musictage.utils

/**
 * 启动去向的进程内状态 喵~
 *
 * ## 要解决的两件事
 *
 * 1. **冷启动的"伪闪退"之后，再点图标应直接进「音乐管理」** ✓
 *    冷启动时 App 会跑一次扫描然后自己退出（看起来像闪退 ✓）
 *    用户之后**再点一次**图标时 ✓ 不应该又跑一遍扫描 ✗ 而是直接进音乐管理 ✓
 *
 * 2. **从歌曲通知点过歌之后，再点图标也应进「音乐管理」** ✓
 *    用户已经用通知选过歌了 ✓ 说明他要的是管理/播放界面 ✓ 不是再扫一遍 ✗
 *
 * ## 为什么放在进程内、不落盘 喵~
 *
 * 这两件事都是"这一次使用过程中"的语义 ✓
 *   - 真的冷启动（进程被杀后重开 ✗）→ 应该正常走扫描 ✓ 所以标志不能持久化 ✓✓
 *   - 进程还活着时的第二次点击 → 直接进管理 ✓ 标志是内存态正好 ✓✓
 *
 * 同理 [sawColdStart] 不被重置：一个进程里只跑一次扫描 ✓
 */
object LaunchState {

    /** 本次进程是否已经跑过冷启动那套（扫描 + 伪闪退）✓ true 就说明"该进管理界面了" ✓ */
    @Volatile
    var sawColdStart: Boolean = false

    /** 用户是否从歌曲通知点过歌 ✓ 点过就说明意图是管理/播放 ✓ */
    @Volatile
    var fromSongNotification: Boolean = false

    /**
     * 冷启动后的第一次 onResume 已经来过了 喵~
     *
     * 原版逻辑：第一次 onResume 只记账，不跳转；从第二次起，如果满足条件就跳音乐管理 ✓
     * 这样"冷启动 → 伪闪退 → 再点图标"才落到管理界面，而不是每次 resume 都跳 ✗
     */
    @Volatile
    var coldStartResumeSeen: Boolean = false

    /**
     * 本次启动是否应该直接进「音乐管理」喵~
     *
     * 两个条件任一成立就进 ✓：
     *   - 这个进程已经跑过一次冷启动流程了 ✓（用户是"再点一次"的 ✓）
     *   - 用户刚从通知里点过歌 ✓
     */
    fun shouldOpenManage(): Boolean = sawColdStart || fromSongNotification
}
