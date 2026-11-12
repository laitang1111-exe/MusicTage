package com.example.musictage.data.qq

import android.util.Log
import java.io.ByteArrayOutputStream
import java.util.zip.Inflater
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * QQ 音乐 QRC 歌词解密。
 *
 * 对应 LDDC 的 `LDDC/core/decryptor/__init__.py::qrc_decrypt`（云端分支）。
 *
 * 链路：十六进制字符串 -> 3DES(EDE/ECB/NoPadding) 解密 -> zlib 解压 -> UTF-8 文本
 *
 * 说明：LDDC 里的 `tripledes.py` 是一份手写的 3DES 实现（240 行纯位运算），
 * 其 `tripledes_key_setup(key, DECRYPT)` 返回 [key[16:], key[8:], key[0:]]，
 * 正是标准 3DES-EDE 解密。因此这里直接用 JCE，无需移植位运算。
 */
object QrcDecoder {
    private const val TAG = "QrcDecoder"

    /**
     * QRC 固定密钥。
     *
     * 取自 LDDC：QRC_KEY = b"!@#)(*$%123ZXC!@!@#)(NHL"
     *
     * 注意：这个常量**必须写成字符串再由 UTF-8 编码成字节**，不能手搓 byteArray。
     * 密钥共 24 个字符（24 字节，正好是 3DES 要求的 3×8 字节），
     * 其中 `$`、`%`、`*`、`!`、`#`、`@`、`&`(无) 等都是 ASCII，
     * 但整串容易被数错位数，所以用字符串最安全。
     */
    private val QRC_KEY: ByteArray = "!@#)(*$%123ZXC!@!@#)(NHL".toByteArray(Charsets.UTF_8)

    init {
        // 3DES 要求密钥长度为 24 字节；这里做一次启动期自检，避免密钥被误改后静默失败
        check(QRC_KEY.size == 24) { "QRC 密钥长度必须为 24 字节，当前为 ${QRC_KEY.size}" }
    }

    /**
     * 解密云端 QRC。
     *
     * @param hexQrc 接口返回的十六进制字符串
     * @return 解密后的 QRC 明文；输入为空或格式非法时返回空串（由调用方决定降级或报错）
     */
    fun decryptCloud(hexQrc: String): String {
        if (hexQrc.isBlank()) return ""

        val encrypted = try {
            hexToBytes(hexQrc)
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "QRC 内容不是合法的十六进制字符串", e)
            return ""
        }
        // 以 8 字节为单位分组，不足一个分组的尾部按 LDDC 的行为一并交给 Cipher（NoPadding 会报错，故先截断）
        val usableLength = encrypted.size - encrypted.size % 8
        if (usableLength <= 0) {
            Log.e(TAG, "QRC 密文长度 ${encrypted.size} 不足一个 DES 分组")
            return ""
        }

        val decrypted = try {
            val cipher = Cipher.getInstance("DESede/ECB/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(QRC_KEY, "DESede"))
            cipher.doFinal(encrypted, 0, usableLength)
        } catch (e: Exception) {
            Log.e(TAG, "QRC 3DES 解密失败: ${e.message}", e)
            return ""
        }

        return try {
            inflate(decrypted)
        } catch (e: Exception) {
            Log.e(TAG, "QRC zlib 解压失败: ${e.message}", e)
            ""
        }
    }

    /** zlib 解压（带 zlib 头，使用 Inflater 默认构造） */
    private fun inflate(data: ByteArray): String {
        val inflater = Inflater()
        try {
            inflater.setInput(data)
            // Android 的 Inflater 必须先 prepare()，否则 inflate() 抛 IllegalStateException
            val out = ByteArrayOutputStream()
            val buf = ByteArray(8192)
            while (!inflater.finished()) {
                val n = inflater.inflate(buf)
                if (n == 0) {
                    if (inflater.needsInput() || inflater.needsDictionary()) break
                }
                out.write(buf, 0, n)
            }
            return out.toString("UTF-8")
        } finally {
            inflater.end()
        }
    }

    private fun hexToBytes(hex: String): ByteArray {
        val s = hex.trim()
        require(s.length % 2 == 0) { "十六进制字符串长度必须为偶数" }
        val out = ByteArray(s.length / 2)
        for (i in out.indices) {
            val hi = Character.digit(s[i * 2], 16)
            val lo = Character.digit(s[i * 2 + 1], 16)
            require(hi >= 0 && lo >= 0) { "包含非十六进制字符" }
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }
}
