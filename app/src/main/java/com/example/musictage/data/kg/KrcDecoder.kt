package com.example.musictage.data.kg

import android.util.Base64
import java.io.ByteArrayOutputStream
import java.util.zip.Inflater

object KrcDecoder {
    // 严格按规范密钥：0x40 0x47 0x61 0x77 0x5E 0x32 0x74 0x47 0x51 0x36 0x31 0x2D 0xCE 0xD2 0x6E 0x69
    private val KEY = byteArrayOf(
        0x40, 0x47, 0x61, 0x77, 0x5E, 0x32, 0x74, 0x47, 0x51, 0x36, 0x31, 0x2D,
        0xCE.toByte(), 0xD2.toByte(),
        0x6E, 0x69
    )

    fun decrypt(contentBase64: String): String {
        if (contentBase64.isEmpty()) return ""

        val raw = Base64.decode(contentBase64, Base64.DEFAULT)
        if (raw.size <= 4) return ""

        // 丢掉 krc18 魔法头并 XOR
        val xored = ByteArray(raw.size - 4)
        for (i in 4 until raw.size) {
            xored[i - 4] = (raw[i].toInt() xor KEY[(i - 4) % KEY.size].toInt()).toByte()
        }

        val inflater = Inflater()
        try {
            inflater.setInput(xored)
            // 关键：Android 的 Inflater 必须先 prepare()，
            // 否则 inflate() 会抛 IllegalStateException("invalid state")。

            val out = ByteArrayOutputStream()
            val buf = ByteArray(8192)
            while (!inflater.finished()) {
                val n = inflater.inflate(buf)
                if (n == 0) {
                    // 既没有产出也没有结束，说明输入数据不完整或已损坏，避免死循环
                    if (inflater.needsInput() || inflater.needsDictionary()) break
                }
                out.write(buf, 0, n)
            }
            return out.toString("UTF-8")
        } finally {
            inflater.end()
        }
    }
}
