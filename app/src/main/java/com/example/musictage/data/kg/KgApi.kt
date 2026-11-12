package com.example.musictage.data.kg

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

object KgApi {
    private const val TAG = "KgApi"
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private fun buildSignedUrl(baseUrl: String, params: MutableMap<String, Any?>): Pair<String, String> {
        val ms = System.currentTimeMillis()
        val mid = KgSign.md5Hex(ms.toString())
        val sec = ms / 1000

        params["appid"] = KgConfig.APP_ID
        params["clientver"] = KgConfig.CLIENT_VER
        params["clienttime"] = sec
        params["mid"] = mid
        params["uuid"] = "-"
        params["dfid"] = "-"

        val sig = KgSign.signature(params)
        val url = baseUrl.toHttpUrl().newBuilder().apply {
            params.forEach { (k, v) -> addQueryParameter(k, v.toString()) }
            addQueryParameter("signature", sig)
        }.build().toString()
        
        return url to mid
    }

    suspend fun searchSong(keyword: String, page: Int = 1): List<KgSong> = withContext(Dispatchers.IO) {
        val params = mutableMapOf<String, Any?>(
            "sorttype" to "0",
            "keyword" to keyword,
            "pagesize" to 20,
            "page" to page,
            "userid" to "0",
            "token" to "",
            "iscorrection" to "1",
            "platform" to "AndroidFilter"
        )
        val (url, mid) = buildSignedUrl(KgConfig.URL_SEARCH, params)
        
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", KgConfig.UA_SEARCH)
            .header("KG-Rec", "1")
            .header("KG-RC", "1")
            .header("KG-CLIENTTIMEMS", System.currentTimeMillis().toString())
            .header("mid", mid)
            .header("x-router", "complexsearch.kugou.com")
            .header("Connection", "Keep-Alive")
            .build()

        Log.d(TAG, "[Step 1] Req: $url | mid: $mid")

        client.newCall(request).execute().use { resp ->
            val body = resp.body?.string()
            Log.d(TAG, "[Step 1] Resp: http=${resp.code}, len=${body?.length ?: 0}")
            
            if (body.isNullOrEmpty()) {
                throw IOException("Search returned empty body, httpStatus=${resp.code}, url=$url")
            }
            
            val json = JSONObject(body)
            val err = json.optInt("error_code", -1)
            if (err != 0 && err != 200) {
                Log.e(TAG, "Search business error: $err - ${json.optString("error_msg")}")
                return@withContext emptyList()
            }

            val lists = json.optJSONObject("data")?.optJSONArray("lists") ?: return@withContext emptyList()
            val results = mutableListOf<KgSong>()
            for (i in 0 until lists.length()) {
                val item = lists.getJSONObject(i)
                val singerArr = item.optJSONArray("Singers")
                val artists = mutableListOf<String>()
                if (singerArr != null) {
                    for (j in 0 until singerArr.length()) {
                        artists.add(singerArr.getJSONObject(j).optString("name"))
                    }
                }
                val artText = artists.joinToString("、").ifEmpty { item.optString("SingerName") }

                results.add(KgSong(
                    id = item.optString("Audioid").ifEmpty { item.optString("ID") },
                    hash = item.optString("FileHash"),
                    title = item.optString("SongName"),
                    artists = artists,
                    artistText = artText,
                    album = item.optString("AlbumName"),
                    durationMs = item.optLong("Duration") * 1000,
                    subtitle = item.optString("Auxiliary")
                ))
            }
            results
        }
    }

    suspend fun getLyricCandidates(song: KgSong): List<KgLyricCandidate> = withContext(Dispatchers.IO) {
        val params = mutableMapOf<String, Any?>(
            "album_audio_id" to song.id,
            "duration" to song.durationMs,
            "hash" to song.hash,
            "keyword" to "${song.artistText} - ${song.title}",
            "lrctxt" to 1,
            "man" to "no"
        )
        val (url, mid) = buildSignedUrl(KgConfig.URL_LYRIC_SEARCH, params)

        Log.d(TAG, "[Step 2] Req: $url | mid: $mid")

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", KgConfig.UA_LYRIC)
            .header("KG-Rec", "1")
            .header("KG-RC", "1")
            .header("KG-CLIENTTIMEMS", System.currentTimeMillis().toString())
            .header("mid", mid)
            .header("Connection", "Keep-Alive")
            .build()

        client.newCall(request).execute().use { resp ->
            val body = resp.body?.string()
            Log.d(TAG, "[Step 2] Resp: http=${resp.code}, len=${body?.length ?: 0}, body=$body")
            
            if (body.isNullOrEmpty()) {
                throw IOException("returned empty body, httpStatus=${resp.code}, url=$url")
            }
            
            val json = JSONObject(body)
            // status 缺失时回退 errcode
            val status = if (json.has("status")) json.optInt("status", -1) else json.optInt("errcode", -1)
            
            if (status != 200) {
                throw LyricsNotFoundError("Business status $status: ${json.optString("errmsg").ifEmpty { json.optString("info") }}")
            }
            
            val arr = json.optJSONArray("candidates") ?: return@withContext emptyList()
            val list = mutableListOf<KgLyricCandidate>()
            for (i in 0 until arr.length()) {
                val item = arr.getJSONObject(i)
                val can = KgLyricCandidate(
                    lyricId = item.optString("id"),
                    accessKey = item.optString("accesskey"),
                    durationMs = item.optLong("duration"),
                    score = item.optInt("score"),
                    productFrom = item.optString("product_from"),
                    singer = item.optString("singer"),
                    songName = item.optString("song"),
                    nickname = item.optString("nickname"),
                    contentType = item.optInt("contenttype")
                )
                Log.d(TAG, "[Candidate $i] id=${can.lyricId}, score=${can.score}, from=${can.productFrom}, singer=${can.singer}, song=${can.songName}, dur=${can.durationMs}, type=${can.contentType}")
                list.add(can)
            }
            list
        }
    }

    suspend fun downloadLyric(can: KgLyricCandidate): KgLyricResponse = withContext(Dispatchers.IO) {
        val params = mutableMapOf<String, Any?>(
            "accesskey" to can.accessKey,
            "id" to can.lyricId,
            "charset" to "utf8",
            "client" to "mobi",
            "fmt" to "krc",
            "ver" to 1
        )
        val (url, mid) = buildSignedUrl(KgConfig.URL_LYRIC_DOWNLOAD, params)

        Log.d(TAG, "[Step 3] Req: $url")

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", KgConfig.UA_LYRIC)
            .header("KG-Rec", "1")
            .header("KG-RC", "1")
            .header("KG-CLIENTTIMEMS", System.currentTimeMillis().toString())
            .header("mid", mid)
            .header("Connection", "Keep-Alive")
            .build()

        client.newCall(request).execute().use { resp ->
            val body = resp.body?.string()
            Log.d(TAG, "[Step 3] Resp: http=${resp.code}, len=${body?.length ?: 0}")
            
            if (body.isNullOrEmpty()) {
                throw IOException("Download returned empty body, httpStatus=${resp.code}, url=$url")
            }
            
            val json = JSONObject(body)
            val status = if (json.has("status")) json.optInt("status", -1) else json.optInt("errcode", -1)
            
            if (status != 200) {
                throw LyricsNotFoundError("Download business failure: $status")
            }
            
            val content = json.optString("content")
            if (content.isNullOrEmpty()) {
                throw LyricsNotFoundError("Download response has no content")
            }

            KgLyricResponse(
                content = content,
                contentType = json.optInt("contenttype")
            )
        }
    }
}
