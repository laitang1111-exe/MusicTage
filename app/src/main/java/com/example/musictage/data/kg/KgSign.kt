package com.example.musictage.data.kg

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/**
 * 酷狗 1:1 复刻签名算法
 */
object KgSign {

    /**
     * 生成 32 位全小写 MD5
     */
    fun md5Hex(input: String): String =
        MessageDigest.getInstance("MD5")
            .digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    /**
     * 计算 signature
     * 规则：MD5( SALT + concat("key=value" 按 key 升序) + data + SALT )
     */
    fun signature(params: Map<String, Any?>, data: String = ""): String {
        // 按 Key 的字符串升序排列并紧凑拼接
        val body = params.entries
            .sortedBy { it.key }
            .joinToString("") { (k, v) -> "$k=${valueToString(v)}" }
        
        return md5Hex(KgConfig.SALT + body + data + KgConfig.SALT)
    }

    /**
     * 嵌套结构序列化转换
     */
    private fun valueToString(v: Any?): String = when (v) {
        null -> ""
        is Map<*, *> -> JSONObject(v).toString()
        is List<*> -> JSONArray(v).toString()
        else -> v.toString()
    }
}
