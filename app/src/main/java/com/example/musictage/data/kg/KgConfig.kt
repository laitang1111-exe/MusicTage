package com.example.musictage.data.kg

/**
 * 酷狗配置中心：SALT、appid、clientver、UA 模板
 */
object KgConfig {
    const val SALT = "LnT6xpN3khm36zse0QzvmgTZ3waWdRSA"
    const val APP_ID = "3116"
    const val CLIENT_VER = "11070"
    
    // UA 模板：1070-11070-201 必须与 appid/clientver 配套
    const val UA_SEARCH = "Android14-1070-11070-201-0-SearchSong-wifi"
    const val UA_LYRIC = "Android14-1070-11070-201-0-Lyric-wifi"

    const val URL_SEARCH = "http://complexsearch.kugou.com/v2/search/song"
    const val URL_LYRIC_SEARCH = "https://lyrics.kugou.com/v1/search"
    const val URL_LYRIC_DOWNLOAD = "http://lyrics.kugou.com/download"
    
    // 旧接口（备选降级路径）
    const val URL_SEARCH_OLD = "http://mobiles.kugou.com/api/v3/search/song"
}
