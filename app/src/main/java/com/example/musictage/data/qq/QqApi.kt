package com.example.musictage.data.qq

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * QQ 音乐（音乐馆）接口客户端。
 *
 * 对应 LDDC 的 `LDDC/core/api/lyrics/qm.py`。
 *
 * 与酷狗源最大的结构差异：
 *  - **只有一个 URL**：`https://u.y.qq.com/cgi-bin/musicu.fcg`，
 *    所有功能靠 body 里的 `request.method` + `request.module` 区分；
 *  - **POST + JSON body**，URL 上没有任何 query 参数；
 *  - **没有签名**，身份伪装完全靠 body 里的 `comm` 段；
 *  - **需要先建立会话**（GetSession 拿 uid/sid/userip），之后所有请求都带上它。
 */
object QqApi {
    private const val TAG = "QqApi"

    private const val URL_MUSICU = "https://u.y.qq.com/cgi-bin/musicu.fcg"
    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    /** 搜索类型映射，与 qm.py 的 SEARCH_TYPE_MAPPING 一致 */
    private const val SEARCH_TYPE_SONG = 0
    private const val SEARCH_TYPE_ALBUM = 2
    private const val SEARCH_TYPE_SONGLIST = 3

    private const val PAGE_SIZE = 20

    /**
     * 会话公共参数，对应 qm.py 的 `self.comm` 初始值。
     * uid / sid / userip 三个字段在 GetSession 之后补充进来。
     */
    private val comm = JSONObject().apply {
        put("ct", 11)
        put("cv", "1003006")
        put("v", "1003006")
        put("os_ver", "15")
        put("phonetype", "24122RKC7C")   // 红米 K80 Pro
        put("rom", "Redmi/miro/miro:15/AE3A.240806.005/OS2.0.10" + Random.nextInt(0, 3).let { arrayOf("5", "4", "2")[it] } + ".0.VOMCNXM:user/release-keys")
        put("tmeAppID", "qqmusiclight")
        put("nettype", "NETWORK_WIFI")
        put("udid", "0")
    }

    private val sessionMutex = Mutex()
    private var sessionReady = false

    /** 建立会话（GetSession），幂等；失败会抛 IOException */
    private suspend fun ensureSession() {
        if (sessionReady) return
        sessionMutex.withLock {
            if (sessionReady) return
            val param = JSONObject().apply {
                put("caller", 0)
                put("uid", "0")
                put("vkey", 0)
            }
            val data = request("GetSession", "music.getSession.session", param)
            val session = data.optJSONObject("session")
            if (session == null) {
                throw IOException("QQ 音乐 GetSession 返回缺少 session 字段")
            }
            comm.put("uid", session.optString("uid"))
            comm.put("sid", session.optString("sid"))
            comm.put("userip", session.optString("userip"))
            sessionReady = true
            Log.d(TAG, "[GetSession] uid=${session.optString("uid")}, userip=${session.optString("userip")}")
        }
    }

    /**
     * 统一的请求发送。
     *
     * Body 结构（与 qm.py 完全一致）：
     * ```json
     * { "comm": {...}, "request": { "method": "...", "module": "...", "param": {...} } }
     * ```
     *
     * 注意两点：
     *  - 中文**不能转义**（qm.py 用了 `ensure_ascii=False`），否则服务端可能认不出来；
     *    `JSONObject.toString()` 本身就不转义非 ASCII，符合要求。
     *  - 响应里**顶层 code 和 request.code 都要判**，业务数据埋在 `request.data`。
     */
    private fun request(method: String, module: String, param: JSONObject): JSONObject {
        val requestNode = JSONObject().apply {
            put("method", method)
            put("module", module)
            put("param", param)
        }
        val payload = JSONObject().apply {
            put("comm", comm)
            put("request", requestNode)
        }

        val body = payload.toString().toRequestBody(JSON_MEDIA_TYPE)
        val httpRequest = Request.Builder()
            .url(URL_MUSICU)
            .header("cookie", "tmeLoginType=-1;")
            .header("User-Agent", "okhttp/3.14.9")
            .header("Accept-Encoding", "gzip")
            .post(body)
            .build()

        Log.d(TAG, "[$method] 发送请求 module=$module")

        client.newCall(httpRequest).execute().use { resp ->
            val text = resp.body?.string()
            if (text.isNullOrEmpty()) {
                throw IOException("QQ 音乐接口返回空 body, httpStatus=${resp.code}, method=$method")
            }
            val json = JSONObject(text)

            val topCode = json.optInt("code", -1)
            val reqNode = json.optJSONObject("request")
            val reqCode = reqNode?.optInt("code", -1) ?: -1
            if (topCode != 0 || reqCode != 0) {
                val code = if (topCode != 0) topCode else reqCode
                val msg = reqNode?.optString("msg").orEmpty().ifEmpty { json.optString("msg") }
                throw IOException("QQ 音乐接口错误: code=$code, msg=$msg, method=$method")
            }
            Log.d(TAG, "[$method] 响应正常, len=${text.length}")
            return reqNode?.optJSONObject("data")
                ?: throw IOException("QQ 音乐接口返回缺少 request.data, method=$method")
        }
    }

    /**
     * 搜索歌曲。
     *
     * 对应 qm.py 的 `search()`，这里只保留单曲搜索（专辑/歌单对歌词匹配无用）。
     *
     * @param keyword 搜索关键词，通常是 "歌手 - 歌名" 或纯歌名
     */
    suspend fun searchSong(keyword: String, page: Int = 1): List<QqSong> = withContext(Dispatchers.IO) {
        ensureSession()

        val param = JSONObject().apply {
            put("search_id", buildSearchId())
            put("remoteplace", "search.android.keyboard")
            put("query", keyword)
            put("search_type", SEARCH_TYPE_SONG)
            put("num_per_page", PAGE_SIZE)
            put("page_num", page)
            put("highlight", 0)
            put("nqc_flag", 0)
            put("page_id", 1)
            put("grp", 1)
        }

        Log.d(TAG, "[Step 1] 搜索 keyword=[$keyword], page=$page")

        val data = request("DoSearchForQQMusicLite", "music.search.SearchCgiService", param)
        val itemSong = data.optJSONObject("body")?.optJSONArray("item_song")
            ?: return@withContext emptyList()

        val results = mutableListOf<QqSong>()
        for (i in 0 until itemSong.length()) {
            val item = itemSong.optJSONObject(i) ?: continue
            val song = parseSong(item) ?: continue
            results.add(song)
        }
        Log.d(TAG, "[Step 1] 解析出 ${results.size} 条结果")
        results
    }

    /**
     * 获取歌词。
     *
     * 对应 qm.py 的 `get_lyrics()`。与酷狗不同，这里没有"候选列表"：
     * 一次请求就把原文/翻译/罗马音全部取回。
     *
     * 前置校验（与 qm.py 第 354 行一致）：`title`、`album`、`id`、`durationMs` **四者缺一不可**。
     */
    suspend fun getLyric(song: QqSong): QqLyricResponse = withContext(Dispatchers.IO) {
        if (song.title.isEmpty() || song.album.isEmpty() || song.id.isEmpty() || song.durationMs <= 0) {
            throw LyricsNotFoundForQq(
                "QQ 音乐歌词接口缺少必要参数: title=[${song.title}] album=[${song.album}] " +
                        "id=[${song.id}] durationMs=${song.durationMs}",
            )
        }
        ensureSession()

        val param = JSONObject().apply {
            // 歌名 / 专辑 / 歌手 三个字段必须 Base64 编码
            put("albumName", b64(song.album))
            put("songName", b64(song.title))
            put("singerName", b64(song.artistSlash))
            put("songID", song.id.toLongOrNull() ?: song.id.toIntOrNull() ?: 0L)
            put("interval", song.durationMs / 1000)   // 接口要秒级整数
            // 固定开关位，与 qm.py 一致
            put("crypt", 1)
            put("ct", 19)
            put("cv", 2111)
            put("type", 0)
            put("qrc", 1)
            put("qrc_t", 0)
            put("trans", 1)
            put("trans_t", 0)
            put("roma", 1)
            put("roma_t", 0)
            put("lrc_t", 0)
        }

        Log.d(
            TAG,
            "[Step 2] 请求歌词 songID=${song.id}, songName=[${song.title}], album=[${song.album}], " +
                    "singer=[${song.artistSlash}], interval=${song.durationMs / 1000}s",
        )

        val data = request("GetPlayLyricInfo", "music.musichallSong.PlayLyricInfo", param)

        val response = QqLyricResponse(
            qrc = data.optString("lyric"),
            qrcStamp = data.optString("qrc_t"),
            lrcStamp = data.optString("lrc_t"),
            translation = data.optString("trans"),
            transStamp = data.optString("trans_t"),
            romanization = data.optString("roma"),
            romaStamp = data.optString("roma_t"),
        )

        Log.d(
            TAG,
            "[Step 2] 响应: lyricLen=${response.qrc.length}, qrcT=${response.qrcStamp}, " +
                    "lrcT=${response.lrcStamp}, transLen=${response.translation.length}, " +
                    "romaLen=${response.romanization.length}",
        )
        response
    }

    /**
     * 从搜索结果条目解析出 [QqSong]。
     * 返回 null 表示该条目缺少必要字段，直接跳过。
     */
    private fun parseSong(item: JSONObject): QqSong? {
        val id = item.optString("id")
        val title = item.optString("title")
        if (id.isEmpty() || title.isEmpty()) return null

        val singers: JSONArray? = item.optJSONArray("singer")
        val artists = mutableListOf<String>()
        if (singers != null) {
            for (j in 0 until singers.length()) {
                val name = singers.optJSONObject(j)?.optString("name").orEmpty()
                if (name.isNotEmpty()) artists.add(name)
            }
        }

        val album = item.optJSONObject("album")?.optString("name").orEmpty()

        return QqSong(
            id = id,
            mid = item.optString("mid"),
            title = title,
            subtitle = item.optString("subtitle"),
            artists = artists,
            artistText = artists.joinToString("、"),
            artistSlash = artists.joinToString("/"),
            album = album,
            // 接口返回的 interval 单位是秒，转成毫秒与其他源统一
            durationMs = item.optLong("interval") * 1000,
        )
    }

    /**
     * 构造 search_id，与 qm.py 完全一致：
     * ```
     * random(1..20) * 18014398509481984
     *   + random(0..4194304) * 4294967296
     *   + (当前毫秒时间戳 % 86400000)
     * ```
     */
    private fun buildSearchId(): String {
        val a = Random.nextLong(1, 21) * 18014398509481984L
        val b = Random.nextLong(0, 4194305) * 4294967296L
        val c = System.currentTimeMillis() % 86400000L
        return (a + b + c).toString()
    }

    /** Base64 编码（UTF-8），对应 Python 的 b64encode(x.encode()).decode() */
    private fun b64(text: String): String =
        Base64.encodeToString(text.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
}
