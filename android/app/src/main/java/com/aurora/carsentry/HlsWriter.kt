package com.aurora.carsentry

import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

/**
 * 把 H.264 Annex-B 码流封装成 MPEG-TS 分片，并维护 HLS playlist。
 *
 * 三件事：
 *   1. TS 复用：PAT / PMT / PES（只做视频，不做音频）
 *   2. 切片：**只在 IDR 帧上切**，每个分片必须能独立解码，否则手机端花屏
 *   3. 直播 playlist 滚动窗口 + 每 60 秒滚一个「录像片段」出来
 *
 * 注意：这个类被编码线程独占调用，方法内部不加锁。
 */
class HlsWriter(
    private val cameraDir: File,
    private val cameraId: String,
    private val segmentSeconds: Double = 2.0,
    private val liveWindow: Int = 4,
    private val clipSegmentCount: Int = 30,
    private val onClipReady: ((clipId: String, startAtSec: Long, durationSec: Int, bytes: Long) -> Unit)? = null,
) {

    companion object {
        private const val TAG = "HlsWriter"
        private const val TS_PACKET = 188
        private const val PID_PMT = 0x1000
        private const val PID_VIDEO = 0x0100
        private const val CLIP_DIR = "rec"
    }

    private data class SegRef(val index: Int, val duration: Double, val bytes: Long, val startAtSec: Long)

    private val playlistFile = File(cameraDir, "index.m3u8")

    private var nextSegmentIndex = 0
    private var out: OutputStream? = null
    private var outFile: File? = null
    private var segmentBytes = 0L
    private var segmentStartUs = 0L
    private var segmentStartEpoch = 0L
    private var ccPat = 0
    private var ccPmt = 0
    private var ccVideo = 0

    private val liveQueue = ArrayDeque<SegRef>()

    // 正在拼的录像片段
    private val clipSegments = ArrayList<SegRef>()
    private var clipStartEpoch = 0L

    init {
        cameraDir.mkdirs()
    }

    // ---------------------------------------------------------------------
    // 对外入口
    // ---------------------------------------------------------------------

    /**
     * @param ptsUs      帧的显示时间（微秒），由 System.nanoTime 换算
     * @param epochSec   这一帧对应的墙钟秒，用来给录像片段定时间
     */
    fun writeSample(
        data: ByteArray,
        offset: Int,
        size: Int,
        ptsUs: Long,
        isKeyFrame: Boolean,
        epochSec: Long,
    ) {
        if (out == null) {
            openSegment(ptsUs, epochSec)
        }

        val elapsedUs = ptsUs - segmentStartUs
        if (isKeyFrame && segmentBytes > 0 && elapsedUs >= (segmentSeconds * 1_000_000).toLong()) {
            closeSegment()
            openSegment(ptsUs, epochSec)
        }

        segmentBytes += writePes(out!!, data, offset, size, ptsUs)
    }

    /** 推流结束时调，把最后一个分片收尾。 */
    fun finish() {
        closeSegment()
        out = null
    }

    // ---------------------------------------------------------------------
    // 分片
    // ---------------------------------------------------------------------

    private fun openSegment(ptsUs: Long, epochSec: Long) {
        val index = nextSegmentIndex++
        val file = File(cameraDir, "seg$index.ts")

        out = try {
            FileOutputStream(file).buffered(64 * 1024)
        } catch (e: Exception) {
            Log.e(TAG, "open segment failed: ${e.message}")
            return
        }
        outFile = file
        segmentBytes = 0
        segmentStartUs = ptsUs
        segmentStartEpoch = epochSec

        // 每个分片开头都写一遍 PAT/PMT，保证任意分片都能被独立解析
        ccPat = (ccPat + 1) and 0x0F
        ccPmt = (ccPmt + 1) and 0x0F
        writeSection(out!!, 0x0000, buildPat(), ccPat)
        writeSection(out!!, PID_PMT, buildPmt(), ccPmt)
    }

    private fun closeSegment() {
        val stream = out ?: return
        val file = outFile ?: return

        try {
            stream.flush()
            stream.close()
        } catch (e: Exception) {
            Log.w(TAG, "close segment: ${e.message}")
        }

        out = null
        outFile = null

        if (segmentBytes <= 0 || file.length() <= 0) {
            file.delete()
            return
        }

        val index = file.nameWithoutExtension.removePrefix("seg").toIntOrNull() ?: return
        val ref = SegRef(
            index = index,
            duration = segmentSeconds,
            bytes = file.length(),
            startAtSec = segmentStartEpoch,
        )

        liveQueue.addLast(ref)
        while (liveQueue.size > liveWindow) {
            liveQueue.removeFirst()
        }
        writeLivePlaylist()

        clipSegments.add(ref)
        if (clipSegments.size == 1) {
            clipStartEpoch = ref.startAtSec
        }
        if (clipSegments.size >= clipSegmentCount) {
            rollClip()
        }
    }

    private fun rollClip() {
        if (clipSegments.isEmpty()) return

        val first = clipSegments.first()
        val clipId = "$cameraId-${first.startAtSec}"
        val duration = (clipSegments.size * segmentSeconds).toInt()
        val bytes = clipSegments.sumOf { it.bytes }

        val dir = File(cameraDir.parentFile, "$CLIP_DIR/$clipId")
        dir.mkdirs()

        val sb = StringBuilder()
        sb.append("#EXTM3U\n")
        sb.append("#EXT-X-VERSION:3\n")
        sb.append("#EXT-X-TARGETDURATION:${(segmentSeconds + 1).toInt()}\n")
        sb.append("#EXT-X-PLAYLIST-TYPE:VOD\n")
        clipSegments.forEach { seg ->
            sb.append("#EXTINF:${formatDuration(seg.duration)},\n")
            // 相对路径指回 live 目录，不复制文件
            sb.append("../../live/$cameraId/seg${seg.index}.ts\n")
        }
        sb.append("#EXT-X-ENDLIST\n")

        val playlist = File(dir, "index.m3u8")
        playlist.writeText(sb.toString())

        clipSegments.clear()

        onClipReady?.invoke(clipId, first.startAtSec, duration, bytes)
        Log.i(TAG, "clip ready: $clipId, ${duration}s, $bytes bytes")
    }

    private fun writeLivePlaylist() {
        val sb = StringBuilder()
        sb.append("#EXTM3U\n")
        sb.append("#EXT-X-VERSION:3\n")
        sb.append("#EXT-X-TARGETDURATION:${(segmentSeconds + 1).toInt()}\n")
        sb.append("#EXT-X-MEDIA-SEQUENCE:${liveQueue.firstOrNull()?.index ?: 0}\n")
        liveQueue.forEach { seg ->
            sb.append("#EXTINF:${formatDuration(seg.duration)},\n")
            sb.append("seg${seg.index}.ts\n")
        }

        try {
            val tmp = File(cameraDir, "index.m3u8.tmp")
            tmp.writeText(sb.toString())
            if (!tmp.renameTo(playlistFile)) {
                playlistFile.writeText(sb.toString())
                tmp.delete()
            }
        } catch (e: Exception) {
            Log.w(TAG, "write playlist: ${e.message}")
        }
    }

    private fun formatDuration(value: Double): String = String.format(java.util.Locale.US, "%.3f", value)

    // ---------------------------------------------------------------------
    // TS 复用
    // ---------------------------------------------------------------------

    private fun writeSection(out: OutputStream, pid: Int, section: ByteArray, cc: Int) {
        val packet = ByteArray(TS_PACKET)
        packet[0] = 0x47
        packet[1] = (0x40 or ((pid shr 8) and 0x1F)).toByte() // PUSI = 1
        packet[2] = (pid and 0xFF).toByte()
        packet[3] = (0x10 or (cc and 0x0F)).toByte()          // payload only
        packet[4] = 0x00                                       // pointer_field
        System.arraycopy(section, 0, packet, 5, section.size)
        for (i in (5 + section.size) until TS_PACKET) {
            packet[i] = 0xFF.toByte()
        }
        out.write(packet)
    }

    /** 返回写入的字节数 */
    private fun writePes(out: OutputStream, data: ByteArray, offset: Int, size: Int, ptsUs: Long): Long {
        val pts = (ptsUs * 90L / 1000L) and 0x1FFFFFFFFL

        val pes = ByteArray(14 + size)
        pes[0] = 0x00
        pes[1] = 0x00
        pes[2] = 0x01
        pes[3] = 0xE0.toByte()
        pes[4] = 0x00
        pes[5] = 0x00                                     // PES_packet_length = 0（视频流允许）
        pes[6] = 0x80.toByte()                            // '10' + 只有 PTS
        pes[7] = 0x00
        pes[8] = 0x05                                     // PES_header_data_length
        pes[9] = (0x21 or (((pts shr 30) and 0x07).toInt() shl 1)).toByte()
        pes[10] = ((pts shr 22) and 0xFF).toByte()
        pes[11] = ((((pts shr 15) and 0x7F).toInt() shl 1) or 0x01).toByte()
        pes[12] = ((pts shr 7) and 0xFF).toByte()
        pes[13] = (((pts and 0x7F).toInt() shl 1) or 0x01).toByte()
        System.arraycopy(data, offset, pes, 14, size)

        var pos = 0
        var first = true
        var written = 0L

        while (pos < pes.size) {
            val remain = pes.size - pos
            val packet = ByteArray(TS_PACKET)
            packet[0] = 0x47
            packet[1] = ((if (first) 0x40 else 0x00) or ((PID_VIDEO shr 8) and 0x1F)).toByte()
            packet[2] = (PID_VIDEO and 0xFF).toByte()

            if (remain >= 184) {
                packet[3] = (0x10 or (ccVideo and 0x0F)).toByte() // payload only
                System.arraycopy(pes, pos, packet, 4, 184)
                pos += 184
            } else {
                // 最后一包不够 184，用 adaptation field 补齐
                packet[3] = (0x30 or (ccVideo and 0x0F)).toByte() // adaptation + payload
                val afl = 183 - remain
                packet[4] = afl.toByte()
                if (afl > 0) {
                    packet[5] = 0x00
                    for (i in 6 until (5 + afl)) {
                        packet[i] = 0xFF.toByte()
                    }
                }
                System.arraycopy(pes, pos, packet, 5 + afl, remain)
                pos = pes.size
            }

            ccVideo = (ccVideo + 1) and 0x0F
            first = false
            written += TS_PACKET
            out.write(packet)
        }

        return written
    }

    // ---------------------------------------------------------------------
    // PSI
    // ---------------------------------------------------------------------

    private fun buildPat(): ByteArray {
        val s = ByteArray(16)
        s[0] = 0x00
        s[1] = 0xB0.toByte()    // section_syntax_indicator=1 + section_length 高 5 位
        s[2] = 13
        s[3] = 0x00
        s[4] = 0x01             // transport_stream_id = 1
        s[5] = 0xC1.toByte()    // reserved + version 0 + current_next 1
        s[6] = 0x00
        s[7] = 0x00
        s[8] = 0x00
        s[9] = 0x01             // program_number = 1
        s[10] = (0xE0 or ((PID_PMT shr 8) and 0x1F)).toByte()
        s[11] = (PID_PMT and 0xFF).toByte()

        val crc = crc32Mpeg(s, 0, 12)
        s[12] = ((crc shr 24) and 0xFF).toByte()
        s[13] = ((crc shr 16) and 0xFF).toByte()
        s[14] = ((crc shr 8) and 0xFF).toByte()
        s[15] = (crc and 0xFF).toByte()
        return s
    }

    private fun buildPmt(): ByteArray {
        val s = ByteArray(21)
        s[0] = 0x02
        s[1] = 0xB0.toByte()
        s[2] = 18
        s[3] = 0x00
        s[4] = 0x01             // program_number = 1
        s[5] = 0xC1.toByte()
        s[6] = 0x00
        s[7] = 0x00
        s[8] = (0xE0 or ((PID_VIDEO shr 8) and 0x1F)).toByte()  // PCR_PID
        s[9] = (PID_VIDEO and 0xFF).toByte()
        s[10] = 0xF0.toByte()
        s[11] = 0x00            // program_info_length = 0
        s[12] = 0x1B            // stream_type = H.264
        s[13] = (0xE0 or ((PID_VIDEO shr 8) and 0x1F)).toByte()
        s[14] = (PID_VIDEO and 0xFF).toByte()
        s[15] = 0xF0.toByte()
        s[16] = 0x00            // ES_info_length = 0

        val crc = crc32Mpeg(s, 0, 17)
        s[17] = ((crc shr 24) and 0xFF).toByte()
        s[18] = ((crc shr 16) and 0xFF).toByte()
        s[19] = ((crc shr 8) and 0xFF).toByte()
        s[20] = (crc and 0xFF).toByte()
        return s
    }

    /** MPEG-2 CRC32（poly 0x04C11DB7，不取反） */
    private fun crc32Mpeg(data: ByteArray, from: Int, to: Int): Long {
        var crc = 0xFFFFFFFFL
        for (i in from until to) {
            crc = crc xor ((data[i].toLong() and 0xFF) shl 24)
            for (bit in 0 until 8) {
                crc = if ((crc and 0x80000000L) != 0L) {
                    ((crc shl 1) xor 0x04C11DB7L) and 0xFFFFFFFFL
                } else {
                    (crc shl 1) and 0xFFFFFFFFL
                }
            }
        }
        return crc and 0xFFFFFFFFL
    }
}
