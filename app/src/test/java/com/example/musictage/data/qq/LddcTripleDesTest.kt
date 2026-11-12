package com.example.musictage.data.qq

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * LDDC 版三重 DES 的移植正确性测试。
 *
 * 测试向量由 LDDC 的 Python 实现（`LDDC/core/decryptor/tripledes.py`）真实运行得出，
 * 种子固定，可复现。若本测试通过，说明 Kotlin 移植与 Python 原版逐位一致，
 * QRC 解密链路即打通。
 *
 * 运行方式：Android Studio 右键本文件 -> Run，或
 * ```
 * ./gradlew :app:testDebugUnitTest --tests "*LddcTripleDesTest*"
 * ```
 */
class LddcTripleDesTest {

    private val qrcKey = "!@#)(*$%123ZXC!@!@#)(NHL".toByteArray(Charsets.UTF_8)

    /** 对应 LDDC 的 DECRYPT = 0 */
    private val decrypt = 0

    private fun hex(s: String): ByteArray {
        require(s.length % 2 == 0)
        return ByteArray(s.length / 2) { i ->
            ((Character.digit(s[i * 2], 16) shl 4) or Character.digit(s[i * 2 + 1], 16)).toByte()
        }
    }

    private fun decryptBlocks(cipherHex: String): String {
        val schedule = LddcTripleDes.tripleDesKeySetup(qrcKey, decrypt)
        val cipher = hex(cipherHex)
        val out = ByteArray(cipher.size)
        var offset = 0
        while (offset < cipher.size) {
            val plain = LddcTripleDes.tripleDesCrypt(cipher.copyOfRange(offset, offset + 8), schedule)
            System.arraycopy(plain, 0, out, offset, 8)
            offset += 8
        }
        return out.joinToString("") { "%02x".format(it) }
    }

    /** 密钥必须是 24 字节，否则 3DES 调度会读越界 */
    @Test
    fun qrcKeyLengthIsValid() {
        assertEquals(24, qrcKey.size)
    }

    /**
     * 完整的 3DES 解密向量。
     * 期望值来自 LDDC 的 tripledes_key_setup + tripledes_crypt。
     */
    @Test
    fun tripleDesDecryptMatchesLddc() {
        assertEquals(
            "3f706d43d53196a8",
            decryptBlocks("0011223344556677"),
        )
        assertEquals(
            "c2b8a64d111972e7b6190672c1156c59",
            decryptBlocks("ffeeddccbbaa99887766554433221100"),
        )
        assertEquals(
            "5a92c2297192224e00a3e9bf66da4be2",
            decryptBlocks("0123456789abcdeffedcba9876543210"),
        )
    }

    /**
     * inverse_permutation 的字节序向量。
     *
     * 这一组专门用来抓"字节顺序写反"——之前手工重构该函数时，
     * 2000 组随机输入与原版全部不一致，就是栽在这里。
     */
    @Test
    fun inversePermutationByteOrder() {
        // s0=0xF7697FB9 s1=0xC735DF5E -> 1feecefdc9de756f
        assertArrayEquals(
            hex("1feecefdc9de756f"),
            inversePermutation(0xF7697FB9L, 0xC735DF5EL),
        )
        // s0=0x70D3DA1F s1=0x1DE9EA66 -> ad831fb13c7e6ad5
        assertArrayEquals(
            hex("ad831fb13c7e6ad5"),
            inversePermutation(0x70D3DA1FL, 0x1DE9EA66L),
        )
        // s0=0x01EAF614 s1=0x17346B45 -> 18a79cca141e3ca5
        assertArrayEquals(
            hex("18a79cca141e3ca5"),
            inversePermutation(0x01EAF614L, 0x17346B45L),
        )
    }

    /**
     * 反射调用 private 的 inversePermutation。
     *
     * 说明：该函数被刻意保留为 private（外部不应直接使用）。
     * 若你的 Kotlin 版本不支持反射访问 private 成员，可以把它改成 internal 再跑本测试。
     */
    private fun inversePermutation(s0: Long, s1: Long): ByteArray {
        val method = LddcTripleDes::class.java.getDeclaredMethod(
            "inversePermutation",
            Long::class.javaPrimitiveType,
            Long::class.javaPrimitiveType,
        )
        method.isAccessible = true
        return method.invoke(LddcTripleDes, s0, s1) as ByteArray
    }
}
