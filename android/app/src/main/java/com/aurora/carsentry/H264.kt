package com.aurora.carsentry

import java.io.ByteArrayOutputStream

/**
 * H.264 Annex-B 工具集。
 *
 * 四种采集源最终都要收敛到同一种东西：**Annex-B 的访问单元（Access Unit）**。
 * Camera2 / UVC 走 MediaCodec 编码后天然是 Annex-B；RTSP 和裸流需要自己把
 * RTP 包 / 字节流重新拼成 Annex-B。所以解析逻辑集中在这里，各源只调用。
 */
object H264Util {

    const val NAL_SLICE = 1
    const val NAL_IDR = 5
    const val NAL_SPS = 7
    const val NAL_PPS = 8
    const val NAL_AUD = 9

    private const val ZERO: Byte = 0x00
    private const val ONE: Byte = 0x01

    // ---------------------------------------------------------------------
    // AVCC ↔ Annex-B
    // ---------------------------------------------------------------------

    /**
     * MediaCodec 在多数设备上输出 Annex-B（00 00 00 01 起始码），
     * 但也有实现输出 AVCC（4 字节长度前缀）。TS 只吃 Annex-B，统一转一次。
     */
    fun toAnnexB(data: ByteArray): ByteArray {
        if (data.size < 5) return data
        return if (isAnnexB(data)) data else avccToAnnexB(data)
    }

    fun isAnnexB(data: ByteArray): Boolean {
        val limit = minOf(data.size - 3, 16)
        for (i in 0..limit) {
            if (data[i] == ZERO && data[i + 1] == ZERO && data[i + 2] == ONE) return true
        }
        return false
    }

    private fun avccToAnnexB(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(data.size + 16)
        var pos = 0
        while (pos + 4 <= data.size) {
            val length = ((data[pos].toInt() and 0xFF) shl 24) or
                ((data[pos + 1].toInt() and 0xFF) shl 16) or
                ((data[pos + 2].toInt() and 0xFF) shl 8) or
                (data[pos + 3].toInt() and 0xFF)
            pos += 4
            if (length <= 0 || pos + length > data.size) break
            out.write(0x00)
            out.write(0x00)
            out.write(0x00)
            out.write(0x01)
            out.write(data, pos, length)
            pos += length
        }
        return if (out.size() == 0) data else out.toByteArray()
    }

    // ---------------------------------------------------------------------
    // NAL 单元切分（针对一段已经完整的 buffer）
    // ---------------------------------------------------------------------

    /** NAL 载荷在 buffer 里的位置，含 1 字节 NAL header。 */
    data class Nal(val start: Int, val len: Int) {
        val end: Int get() = start + len
    }

    fun splitNalUnits(buf: ByteArray, offset: Int = 0, length: Int = buf.size - offset): List<Nal> {
        // 同时记「起始码位置」和「NAL 起点」，前者用来算上一个 NAL 的结尾
        val codeStarts = ArrayList<Int>()
        val nalStarts = ArrayList<Int>()
        var i = offset
        val limit = offset + length
        while (i + 2 < limit) {
            if (buf[i] == ZERO && buf[i + 1] == ZERO) {
                if (buf[i + 2] == ONE) {
                    codeStarts.add(i); nalStarts.add(i + 3); i += 3; continue
                }
                if (i + 3 < limit && buf[i + 2] == ZERO && buf[i + 3] == ONE) {
                    codeStarts.add(i); nalStarts.add(i + 4); i += 4; continue
                }
            }
            i++
        }

        val result = ArrayList<Nal>(nalStarts.size)
        for (k in nalStarts.indices) {
            val start = nalStarts[k]
            var end = if (k + 1 < codeStarts.size) codeStarts[k + 1] else limit
            // 起始码前的 0x00 属于分隔符，不是 NAL 内容
            while (end > start && buf[end - 1] == ZERO) end--
            if (end > start) result.add(Nal(start, end - start))
        }
        return result
    }

    fun nalType(buf: ByteArray, nal: Nal): Int =
        if (nal.len <= 0) -1 else buf[nal.start].toInt() and 0x1F

    /** slice header 的第一个 ue(v)。等于 0 表示这是一帧的起始 slice。 */
    fun firstMbInSlice(buf: ByteArray, nal: Nal): Int {
        if (nal.len < 2) return -1
        val reader = BitReader(buf, nal.start + 1, nal.end)
        return reader.readUe()
    }

    fun containsIdr(buf: ByteArray, nal: Nal): Boolean = nalType(buf, nal) == NAL_IDR

    // ---------------------------------------------------------------------
    // 位读取（只用来解 ue(v)，够用就行）
    // ---------------------------------------------------------------------

    private class BitReader(private val data: ByteArray, private var byteIndex: Int, private val end: Int) {

        private var bitIndex = 0

        private fun readBit(): Int {
            if (byteIndex >= end) return -1
            val value = (data[byteIndex].toInt() shr (7 - bitIndex)) and 1
            bitIndex++
            if (bitIndex == 8) {
                bitIndex = 0
                byteIndex++
            }
            return value
        }

        fun readUe(): Int {
            var zeros = 0
            while (true) {
                val bit = readBit()
                if (bit < 0) return -1
                if (bit == 1) break
                zeros++
                if (zeros > 31) return -1
            }
            var value = 0
            for (i in 0 until zeros) {
                val bit = readBit()
                if (bit < 0) return -1
                value = (value shl 1) or bit
            }
            // 防溢出：first_mb_in_slice 不可能超过 2^24
            return ((1 shl zeros) - 1) + value
        }
    }

    // ---------------------------------------------------------------------
    // 从裸字节流里切 NAL（TCP / 文件 / 厂商 socket 用）
    // ---------------------------------------------------------------------

    /**
     * 增量式 Annex-B 解析器：喂任意长度的 chunk，吐出完整的 NAL。
     *
     * 边界判断交给「下一个起始码」——只有在看到下一个起始码时，
     * 才能确认上一个 NAL 已经收全。所以最后一个 NAL 会留在缓冲区里等下一批数据。
     */
    class StreamReader(private val onNal: (ByteArray, Int, Int) -> Unit) {

        private var buf = ByteArray(1 shl 16)
        private var length = 0

        fun feed(chunk: ByteArray, chunkLength: Int) {
            if (chunkLength <= 0) return
            ensure(length + chunkLength)
            System.arraycopy(chunk, 0, buf, length, chunkLength)
            length += chunkLength

            val codeStarts = ArrayList<Int>()
            val nalStarts = ArrayList<Int>()
            var i = 0
            while (i + 2 < length) {
                if (buf[i] == ZERO && buf[i + 1] == ZERO) {
                    if (buf[i + 2] == ONE) {
                        codeStarts.add(i); nalStarts.add(i + 3); i += 3; continue
                    }
                    if (i + 3 < length && buf[i + 2] == ZERO && buf[i + 3] == ONE) {
                        codeStarts.add(i); nalStarts.add(i + 4); i += 4; continue
                    }
                }
                i++
            }

            if (nalStarts.size < 2) {
                // 只有起始码没有下一个起点，什么都还不能确定；但要防止缓冲区爆掉
                if (length > 8 * 1024 * 1024) {
                    val keep = if (nalStarts.isEmpty()) 0 else nalStarts.last()
                    compactFrom(keep)
                }
                return
            }

            for (k in 0 until nalStarts.size - 1) {
                val start = nalStarts[k]
                var end = codeStarts[k + 1]
                while (end > start && buf[end - 1] == ZERO) end--
                if (end > start) onNal(buf, start, end - start)
            }

            compactFrom(nalStarts.last())
        }

        /** 流结束时把最后一个 NAL 也吐出去 */
        fun flush() {
            if (length <= 0) return
            val nals = splitNalUnits(buf, 0, length)
            nals.forEach { onNal(buf, it.start, it.len) }
            length = 0
        }

        private fun compactFrom(keepStart: Int) {
            val keep = length - keepStart
            if (keep <= 0) {
                length = 0
                return
            }
            System.arraycopy(buf, keepStart, buf, 0, keep)
            length = keep
        }

        private fun ensure(capacity: Int) {
            if (capacity <= buf.size) return
            var size = buf.size
            while (size < capacity) size = size shl 1
            buf = buf.copyOf(size)
        }
    }

    // ---------------------------------------------------------------------
    // 把 NAL 拼成访问单元
    // ---------------------------------------------------------------------

    /**
     * 访问单元组装器。
     *
     * TS 里的一个 PES = 一个访问单元（一帧）。RTP / 裸流拿到的是一个个 NAL，
     * 需要按 H.264 规范判断帧边界：
     *   · 遇到 VCL NAL（1~5），且上一批里已经有 VCL，且 first_mb_in_slice == 0 → 新帧
     *   · 遇到 AUD（9）→ 之前的攒起来的内容就是完整一帧
     */
    class AccessUnitAssembler(private val onAccessUnit: (ByteArray, Boolean) -> Unit) {

        private val buffer = ByteArrayOutputStream(96 * 1024)
        private var sawVcl = false
        private var sawIdr = false

        fun push(nal: ByteArray, offset: Int, count: Int) {
            if (count <= 0) return
            val type = nal[offset].toInt() and 0x1F

            if (type == NAL_AUD && buffer.size() > 0) {
                flush()
            }

            val vcl = type in 1..5
            if (vcl && sawVcl) {
                val single = H264Util.Nal(offset, count)
                if (H264Util.firstMbInSlice(nal, single) == 0) flush()
            }

            buffer.write(0x00)
            buffer.write(0x00)
            buffer.write(0x00)
            buffer.write(0x01)
            buffer.write(nal, offset, count)

            if (vcl) sawVcl = true
            if (type == NAL_IDR) sawIdr = true
        }

        fun pushNal(nal: ByteArray) {
            push(nal, 0, nal.size)
        }

        fun flush() {
            if (buffer.size() > 0) {
                val data = buffer.toByteArray()
                val key = sawIdr
                buffer.reset()
                sawVcl = false
                sawIdr = false
                onAccessUnit(data, key)
            }
        }
    }
}
