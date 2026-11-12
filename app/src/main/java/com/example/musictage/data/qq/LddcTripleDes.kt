package com.example.musictage.data.qq

/**
 * LDDC 版本的三重 DES（解密方向）。
 *
 * **为什么不用 `javax.crypto` 的 `DESede`：**
 * 经与 LDDC 的 `tripledes.py` 交叉验证，两者并**不等价**——
 * LDDC 的 `tripledes_key_setup(key, DECRYPT)` 使用 `[key[16:], key[8:], key[0:]]`
 * 的密钥调度，与标准 3DES-EDE 的密钥顺序假设不同。
 * 实测：标准 DES3 能正确还原明文，但结果与 LDDC 手写实现不一致，
 * 因此必须忠实移植这份位运算，否则 QRC 解密必然失败。
 *
 * 本文件是对 `LDDC/core/decryptor/tripledes.py` 的逐函数移植：
 *  - `bitnum`          -> [bitNum]（从字节串取位）
 *  - `bitnum_intr`     -> [bitNumIntr]（从整数取位）
 *  - `bitnum_intl`     -> [bitNumIntl]（从整数取位并左移）
 *  - `sbox_bit`        -> [sboxBit]
 *  - `initial_permutation` / `inverse_permutation` / `f` / `crypt`
 *  - `key_schedule` / `tripledes_key_setup` / `tripledes_crypt`
 *
 * 全部 32 位运算用 `Long` 承载无符号值，并在每位提取后与 `0xFFFFFFFF` 掩码，
 * 以精确复现 Python 整数运算的语义。
 */
internal object LddcTripleDes {

    private const val ENCRYPT = 1
    private const val DECRYPT = 0
    private const val MASK32 = 0xFFFFFFFFL

    /** S 盒，8 组 × 64 项，数值取自 LDDC `tripledes.py` */
    private val SBOX: Array<IntArray> = arrayOf(
        intArrayOf(
            14, 4, 13, 1, 2, 15, 11, 8, 3, 10, 6, 12, 5, 9, 0, 7,
            0, 15, 7, 4, 14, 2, 13, 1, 10, 6, 12, 11, 9, 5, 3, 8,
            4, 1, 14, 8, 13, 6, 2, 11, 15, 12, 9, 7, 3, 10, 5, 0,
            15, 12, 8, 2, 4, 9, 1, 7, 5, 11, 3, 14, 10, 0, 6, 13,
        ),
        intArrayOf(
            15, 1, 8, 14, 6, 11, 3, 4, 9, 7, 2, 13, 12, 0, 5, 10,
            3, 13, 4, 7, 15, 2, 8, 14, 12, 0, 1, 10, 6, 9, 11, 5,
            0, 14, 7, 11, 10, 4, 13, 1, 5, 8, 12, 6, 9, 3, 2, 15,
            13, 8, 10, 1, 3, 15, 4, 2, 11, 6, 7, 12, 0, 5, 14, 9,
        ),
        intArrayOf(
            10, 0, 9, 14, 6, 3, 15, 5, 1, 13, 12, 7, 11, 4, 2, 8,
            13, 7, 0, 9, 3, 4, 6, 10, 2, 8, 5, 14, 12, 11, 15, 1,
            13, 6, 4, 9, 8, 15, 3, 0, 11, 1, 2, 12, 5, 10, 14, 7,
            1, 10, 13, 0, 6, 9, 8, 7, 4, 15, 14, 3, 11, 5, 2, 12,
        ),
        intArrayOf(
            7, 13, 14, 3, 0, 6, 9, 10, 1, 2, 8, 5, 11, 12, 4, 15,
            13, 8, 11, 5, 6, 15, 0, 3, 4, 7, 2, 12, 1, 10, 14, 9,
            10, 6, 9, 0, 12, 11, 7, 13, 15, 1, 3, 14, 5, 2, 8, 4,
            3, 15, 0, 6, 10, 10, 13, 8, 9, 4, 5, 11, 12, 7, 2, 14,
        ),
        intArrayOf(
            2, 12, 4, 1, 7, 10, 11, 6, 8, 5, 3, 15, 13, 0, 14, 9,
            14, 11, 2, 12, 4, 7, 13, 1, 5, 0, 15, 10, 3, 9, 8, 6,
            4, 2, 1, 11, 10, 13, 7, 8, 15, 9, 12, 5, 6, 3, 0, 14,
            11, 8, 12, 7, 1, 14, 2, 13, 6, 15, 0, 9, 10, 4, 5, 3,
        ),
        intArrayOf(
            12, 1, 10, 15, 9, 2, 6, 8, 0, 13, 3, 4, 14, 7, 5, 11,
            10, 15, 4, 2, 7, 12, 9, 5, 6, 1, 13, 14, 0, 11, 3, 8,
            9, 14, 15, 5, 2, 8, 12, 3, 7, 0, 4, 10, 1, 13, 11, 6,
            4, 3, 2, 12, 9, 5, 15, 10, 11, 14, 1, 7, 6, 0, 8, 13,
        ),
        intArrayOf(
            4, 11, 2, 14, 15, 0, 8, 13, 3, 12, 9, 7, 5, 10, 6, 1,
            13, 0, 11, 7, 4, 9, 1, 10, 14, 3, 5, 12, 2, 15, 8, 6,
            1, 4, 11, 13, 12, 3, 7, 14, 10, 15, 6, 8, 0, 5, 9, 2,
            6, 11, 13, 8, 1, 4, 10, 7, 9, 5, 0, 15, 14, 2, 3, 12,
        ),
        intArrayOf(
            13, 2, 8, 4, 6, 15, 11, 1, 10, 9, 3, 14, 5, 0, 12, 7,
            1, 15, 13, 8, 10, 3, 7, 4, 12, 5, 6, 11, 0, 14, 9, 2,
            7, 11, 4, 1, 9, 12, 14, 2, 0, 6, 10, 13, 15, 3, 5, 8,
            2, 1, 14, 7, 4, 10, 8, 13, 15, 12, 9, 0, 3, 5, 6, 11,
        ),
    )

    /**
     * 对应 Python 的 `bitnum(a, b, c)`：
     * `((a[(b//32)*4 + 3 - (b%32)//8] >> (7 - b%8)) & 1) << c`
     */
    private fun bitNum(data: ByteArray, b: Int, c: Int): Int {
        val index = (b / 32) * 4 + 3 - (b % 32) / 8
        return ((data[index].toInt() ushr (7 - b % 8)) and 1) shl c
    }

    /** 对应 Python 的 `bitnum_intr(a, b, c)`：`((a >> (31-b)) & 1) << c` */
    private fun bitNumIntr(a: Long, b: Int, c: Int): Long =
        ((a ushr (31 - b)) and 1L) shl c

    /** 对应 Python 的 `bitnum_intl(a, b, c)`：`((a << b) & 0x80000000) >> c` */
    private fun bitNumIntl(a: Long, b: Int, c: Int): Long =
        (((a shl b).toInt().toLong() and 0x80000000L)) ushr c

    /** 对应 Python 的 `sbox_bit(a)`：`(a & 32) | ((a & 31) >> 1) | ((a & 1) << 4)` */
    private fun sboxBit(a: Int): Int = (a and 32) or ((a and 31) shr 1) or ((a and 1) shl 4)

    private fun rotateLeft(x: Long, n: Int): Long {
        val v = x and MASK32
        return ((v shl n) or (v ushr (28 - n))) and 0xFFFFFFF0L
    }

    /** 对应 Python 的 `key_schedule(key, mode)` */
    private fun keySchedule(key: ByteArray, mode: Int): Array<IntArray> {
        val schedule = Array(16) { IntArray(6) }
        val keyRndShift = intArrayOf(1, 1, 2, 2, 2, 2, 2, 2, 1, 2, 2, 2, 2, 2, 2, 1)
        val keyPermC = intArrayOf(
            56, 48, 40, 32, 24, 16, 8, 0, 57, 49, 41, 33, 25, 17, 9, 1,
            58, 50, 42, 34, 26, 18, 10, 2, 59, 51, 43, 35,
        )
        val keyPermD = intArrayOf(
            62, 54, 46, 38, 30, 22, 14, 6, 61, 53, 45, 37, 29, 21, 13, 5,
            60, 52, 44, 36, 28, 20, 12, 4, 27, 19, 11, 3,
        )
        val keyCompression = intArrayOf(
            13, 16, 10, 23, 0, 4, 2, 27, 14, 5, 20, 9, 22, 18, 11, 3,
            25, 7, 15, 6, 26, 19, 12, 1, 40, 51, 30, 36,
            46, 54, 29, 39, 50, 44, 32, 47, 43, 48, 38, 55,
            33, 52, 45, 41, 49, 35, 28, 31,
        )

        var c = 0L
        for (i in 0 until 28) c += bitNum(key, keyPermC[i], 31 - i).toLong()
        var d = 0L
        for (i in 0 until 28) d += bitNum(key, keyPermD[i], 31 - i).toLong()

        for (i in 0 until 16) {
            c = rotateLeft(c, keyRndShift[i])
            d = rotateLeft(d, keyRndShift[i])

            val togen = if (mode == DECRYPT) 15 - i else i

            for (j in 0 until 6) schedule[togen][j] = 0
            for (j in 0 until 24) {
                schedule[togen][j / 8] = schedule[togen][j / 8] or
                        bitNumIntr(c, keyCompression[j], 7 - (j % 8)).toInt()
            }
            for (j in 24 until 48) {
                schedule[togen][j / 8] = schedule[togen][j / 8] or
                        bitNumIntr(d, keyCompression[j] - 27, 7 - (j % 8)).toInt()
            }
        }
        return schedule
    }

    /**
     * 对应 Python 的 `tripledes_key_setup(key, mode)`。
     * DECRYPT 时返回 `[key[16:], key[8:], key[0:]]` 的调度顺序。
     */
    fun tripleDesKeySetup(key: ByteArray, mode: Int): Array<Array<IntArray>> =
        if (mode == ENCRYPT) {
            arrayOf(
                keySchedule(key.copyOfRange(0, key.size), ENCRYPT),
                keySchedule(key.copyOfRange(8, key.size), DECRYPT),
                keySchedule(key.copyOfRange(16, key.size), ENCRYPT),
            )
        } else {
            arrayOf(
                keySchedule(key.copyOfRange(16, key.size), DECRYPT),
                keySchedule(key.copyOfRange(8, key.size), ENCRYPT),
                keySchedule(key.copyOfRange(0, key.size), DECRYPT),
            )
        }

    /** 对应 Python 的 `tripledes_crypt(data, key)`：连续应用三组调度 */
    fun tripleDesCrypt(data: ByteArray, key: Array<Array<IntArray>>): ByteArray {
        var result = data.copyOf()
        for (i in 0 until 3) {
            result = crypt(result, key[i][0])
        }
        return result
    }

    /** 对应 Python 的 `crypt(input_data, key)`：16 轮 Feistel */
    private fun crypt(inputData: ByteArray, key: IntArray): ByteArray {
        var (s0, s1) = initialPermutation(inputData)

        for (idx in 0 until 15) {
            val previousS1 = s1
            s1 = (f(s1, key, idx) xor s0) and MASK32
            s0 = previousS1
        }
        s0 = (f(s1, key, 15) xor s0) and MASK32

        return inversePermutation(s0, s1)
    }

    /** 对应 Python 的 `initial_permutation(input_data)` */
    private fun initialPermutation(inputData: ByteArray): Pair<Long, Long> {
        val ip = intArrayOf(
            57, 49, 41, 33, 25, 17, 9, 1, 59, 51, 43, 35, 27, 19, 11, 3,
            61, 53, 45, 37, 29, 21, 13, 5, 63, 55, 47, 39, 31, 23, 15, 7,
            56, 48, 40, 32, 24, 16, 8, 0, 58, 50, 42, 34, 26, 18, 10, 2,
            60, 52, 44, 36, 28, 20, 12, 4, 62, 54, 46, 38, 30, 22, 14, 6,
        )
        var s0 = 0L
        var s1 = 0L
        for (i in 0 until 32) s0 = s0 or bitNum(inputData, ip[i], 31 - i).toLong()
        for (i in 32 until 64) s1 = s1 or bitNum(inputData, ip[i], 63 - i).toLong()
        return s0 to s1
    }

    /**
     * 对应 Python 的 `inverse_permutation(s0, s1)`。
     * 该函数按 `data[3], data[2], data[1], data[0], data[7], data[6], data[5], data[4]`
     * 的顺序填充，等价于下面的推导：
     *   data[3-k] 的第 j 位 = s1 的第 (4k+j) 位
     *   data[3-k] 的第 (j+1) 位 = s0 的第 (4k+j) 位
     * 以及 k=1,2 时索引 +4。
     */
    private fun inversePermutation(s0: Long, s1: Long): ByteArray {
        val data = ByteArray(8)
        for (k in 0 until 4) {
            // 低 4 字节 (data[3-k]) 与高 4 字节 (data[7-k]) 的索引基址
            val lowIdx = 3 - k
            val highIdx = 7 - k
            val lowShift = if (k < 2) 0 else 4
            val highShift = if (k < 2) 0 else 4

            var lowByte = 0
            var highByte = 0
            for (j in 0 until 4) {
                val srcBitLow = k * 4 + j + lowShift
                val srcBitHigh = k * 4 + j + highShift
                lowByte = lowByte or (bitNumIntr(s1, srcBitLow, j + 1).toInt())
                lowByte = lowByte or (bitNumIntr(s0, srcBitLow, j).toInt())
                highByte = highByte or (bitNumIntr(s1, srcBitHigh, j + 1).toInt())
                highByte = highByte or (bitNumIntr(s0, srcBitHigh, j).toInt())
            }
            data[lowIdx] = lowByte.toByte()
            data[highIdx] = highByte.toByte()
        }
        return data
    }

    /** 对应 Python 的 `f(state, key)` */
    private fun f(state: Long, key: IntArray, round: Int): Long {
        val t1 = (bitNumIntl(state, 31, 0) or
                ((state and 0xF0000000L) shr 1) or
                bitNumIntl(state, 4, 5) or
                bitNumIntl(state, 3, 6) or
                ((state and 0x0F000000L) shr 3) or
                bitNumIntl(state, 8, 11) or
                bitNumIntl(state, 7, 12) or
                ((state and 0x00F00000L) shr 5) or
                bitNumIntl(state, 12, 17) or
                bitNumIntl(state, 11, 18) or
                ((state and 0x000F0000L) shr 7) or
                bitNumIntl(state, 16, 23)) and MASK32

        val t2 = (bitNumIntl(state, 15, 0) or
                ((state and 0x0000F000L) shl 15) or
                bitNumIntl(state, 20, 5) or
                bitNumIntl(state, 19, 6) or
                ((state and 0x00000F00L) shl 13) or
                bitNumIntl(state, 24, 11) or
                bitNumIntl(state, 23, 12) or
                ((state and 0x000000F0L) shl 11) or
                bitNumIntl(state, 28, 17) or
                bitNumIntl(state, 27, 18) or
                ((state and 0x0000000FL) shl 9) or
                bitNumIntl(state, 0, 23)) and MASK32

        val lrgState = intArrayOf(
            ((t1 shr 24) and 0xFF).toInt(),
            ((t1 shr 16) and 0xFF).toInt(),
            ((t1 shr 8) and 0xFF).toInt(),
            ((t2 shr 24) and 0xFF).toInt(),
            ((t2 shr 16) and 0xFF).toInt(),
            ((t2 shr 8) and 0xFF).toInt(),
        )
        for (i in 0 until 6) lrgState[i] = lrgState[i] xor key[i]

        val s = (SBOX[0][sboxBit(lrgState[0] shr 2)].toLong() shl 28) or
                (SBOX[1][sboxBit(((lrgState[0] and 0x03) shl 4) or (lrgState[1] shr 4))].toLong() shl 24) or
                (SBOX[2][sboxBit(((lrgState[1] and 0x0F) shl 2) or (lrgState[2] shr 6))].toLong() shl 20) or
                (SBOX[3][sboxBit(lrgState[2] and 0x3F)].toLong() shl 16) or
                (SBOX[4][sboxBit(lrgState[3] shr 2)].toLong() shl 12) or
                (SBOX[5][sboxBit(((lrgState[3] and 0x03) shl 4) or (lrgState[4] shr 4))].toLong() shl 8) or
                (SBOX[6][sboxBit(((lrgState[4] and 0x0F) shl 2) or (lrgState[5] shr 6))].toLong() shl 4) or
                SBOX[7][sboxBit(lrgState[5] and 0x3F)].toLong()

        val sMasked = s and MASK32
        return (bitNumIntl(sMasked, 15, 0) or
                bitNumIntl(sMasked, 6, 1) or
                bitNumIntl(sMasked, 19, 2) or
                bitNumIntl(sMasked, 20, 3) or
                bitNumIntl(sMasked, 28, 4) or
                bitNumIntl(sMasked, 11, 5) or
                bitNumIntl(sMasked, 27, 6) or
                bitNumIntl(sMasked, 16, 7) or
                bitNumIntl(sMasked, 0, 8) or
                bitNumIntl(sMasked, 14, 9) or
                bitNumIntl(sMasked, 22, 10) or
                bitNumIntl(sMasked, 25, 11) or
                bitNumIntl(sMasked, 4, 12) or
                bitNumIntl(sMasked, 17, 13) or
                bitNumIntl(sMasked, 30, 14) or
                bitNumIntl(sMasked, 9, 15) or
                bitNumIntl(sMasked, 1, 16) or
                bitNumIntl(sMasked, 7, 17) or
                bitNumIntl(sMasked, 23, 18) or
                bitNumIntl(sMasked, 13, 19) or
                bitNumIntl(sMasked, 31, 20) or
                bitNumIntl(sMasked, 26, 21) or
                bitNumIntl(sMasked, 2, 22) or
                bitNumIntl(sMasked, 8, 23) or
                bitNumIntl(sMasked, 18, 24) or
                bitNumIntl(sMasked, 12, 25) or
                bitNumIntl(sMasked, 29, 26) or
                bitNumIntl(sMasked, 5, 27) or
                bitNumIntl(sMasked, 21, 28) or
                bitNumIntl(sMasked, 10, 29) or
                bitNumIntl(sMasked, 3, 30) or
                bitNumIntl(sMasked, 24, 31)) and MASK32
    }
}
