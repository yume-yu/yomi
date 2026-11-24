package com.yumeyu.yomi

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.text.format.DateFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream

/**
 * エピソードの音声を 1 つの Ogg Opus のファイルにして、端末の「ダウンロード」に置く。
 *
 * 段落ごとのファイルをそのままつなぐ（Ogg の連結）と、長さが正しく出ない・最初の段落で止まる
 * プレイヤーがあるので、Opus のパケットを取り出して 1 本のストリームに詰め直す（再エンコードはしない）。
 */
object Export {
    /** 保存したファイルの名前を返す */
    suspend fun saveToDownloads(context: Context, episode: Episode): String = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT < 29) throw IOException("Android 10 以降でのみ保存できます")
        if (!episode.isComplete) throw IOException("音声がまだできあがっていません")
        val store = EpisodeStore.get(context)
        val chunks = episode.chunks.indices.map { store.chunkFile(episode.id, it).readBytes() }

        val name = fileName(episode)
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "audio/ogg")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("ダウンロードにファイルを作れませんでした")
        try {
            resolver.openOutputStream(uri)?.use { out -> OggOpus.concat(chunks, episode.title, out) }
                ?: throw IOException("ファイルを開けませんでした")
            resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
        } catch (e: Throwable) {
            resolver.delete(uri, null, null)
            throw e
        }
        name
    }

    private fun fileName(episode: Episode): String {
        val date = DateFormat.format("yyyyMMdd-HHmm", episode.createdAt)
        val title = episode.title.replace(Regex("""[\\/:*?"<>|\s]+"""), "_").trim('_').take(30)
        return "Yomi_${date}_$title.ogg"
    }
}

/** Ogg Opus を、パケットの単位で扱う最小限の読み書き */
object OggOpus {
    /** 1 本の論理ストリームだけが入った Ogg から、パケットを順に取り出す */
    fun readPackets(bytes: ByteArray): List<ByteArray> {
        val packets = mutableListOf<ByteArray>()
        val partial = ByteArrayOutputStream()
        var pos = 0
        while (pos + 27 <= bytes.size) {
            if (bytes[pos] != 'O'.code.toByte() || bytes[pos + 1] != 'g'.code.toByte() ||
                bytes[pos + 2] != 'g'.code.toByte() || bytes[pos + 3] != 'S'.code.toByte()
            ) throw IOException("Ogg のページが壊れています（$pos バイト目）")
            val segments = bytes[pos + 26].toInt() and 0xFF
            var body = pos + 27 + segments
            for (i in 0 until segments) {
                val len = bytes[pos + 27 + i].toInt() and 0xFF
                if (body + len > bytes.size) throw IOException("Ogg のページが途中で切れています")
                partial.write(bytes, body, len)
                body += len
                if (len < 255) {
                    packets += partial.toByteArray()
                    partial.reset()
                }
            }
            pos = body
        }
        return packets
    }

    /** Opus のパケット 1 つで出てくるサンプル数（48 kHz） */
    fun samplesOf(packet: ByteArray): Int {
        if (packet.isEmpty()) return 0
        val toc = packet[0].toInt() and 0xFF
        val config = toc shr 3
        val frameSize = when {
            config < 12 -> intArrayOf(480, 960, 1920, 2880)[config % 4]
            config < 16 -> intArrayOf(480, 960)[config % 2]
            else -> intArrayOf(120, 240, 480, 960)[config % 4]
        }
        val frames = when (toc and 3) {
            0 -> 1
            1, 2 -> 2
            else -> if (packet.size > 1) packet[1].toInt() and 0x3F else 0
        }
        return frameSize * frames
    }

    /**
     * いくつかの Ogg Opus をつないで、1 本のストリームとして書き出す。
     * 頭のヘッダー（OpusHead）は最初のものを使い、それぞれのヘッダーとタグは捨てる。
     */
    fun concat(files: List<ByteArray>, title: String, out: OutputStream) {
        require(files.isNotEmpty()) { "つなぐものがありません" }
        var head: ByteArray? = null
        val audio = mutableListOf<ByteArray>()
        for ((i, file) in files.withIndex()) {
            val packets = readPackets(file)
            if (packets.size < 2 || !packets[0].startsWith("OpusHead") || !packets[1].startsWith("OpusTags")) {
                throw IOException("${i + 1} 番目の段落が Ogg Opus ではありません")
            }
            val h = packets[0]
            if (head == null) head = h
            else if (h[9] != head[9]) throw IOException("段落によってチャンネル数が違うので、つなげません")
            audio += packets.drop(2)
        }

        val writer = PageWriter(out, serial = (System.nanoTime() and 0x7FFFFFFF).toInt())
        writer.page(listOf(head!!), granule = 0, bos = true)
        writer.page(listOf(tags(title)), granule = 0)

        var granule = 0L
        val page = mutableListOf<ByteArray>()
        var segments = 0
        for ((i, packet) in audio.withIndex()) {
            val need = packet.size / 255 + 1
            if (need > 255) throw IOException("大きすぎるパケットがあります")
            if (segments + need > 255 || page.size >= 50) {
                writer.page(page, granule)
                page.clear()
                segments = 0
            }
            page += packet
            segments += need
            granule += samplesOf(packet)
            if (i == audio.lastIndex) writer.page(page, granule, eos = true)
        }
        if (audio.isEmpty()) writer.page(emptyList(), 0, eos = true)
    }

    private fun tags(title: String): ByteArray {
        val o = ByteArrayOutputStream()
        fun le32(v: Int) = o.write(byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte()))
        fun str(s: String) = s.toByteArray(Charsets.UTF_8).let { le32(it.size); o.write(it) }
        o.write("OpusTags".toByteArray(Charsets.US_ASCII))
        str("Yomi")
        le32(2)
        str("TITLE=$title")
        str("ARTIST=Yomi")
        return o.toByteArray()
    }

    private fun ByteArray.startsWith(magic: String): Boolean =
        size >= magic.length && magic.indices.all { this[it] == magic[it].code.toByte() }

    private class PageWriter(private val out: OutputStream, private val serial: Int) {
        private var sequence = 0

        fun page(packets: List<ByteArray>, granule: Long, bos: Boolean = false, eos: Boolean = false) {
            val lacing = ByteArrayOutputStream()
            val body = ByteArrayOutputStream()
            for (p in packets) {
                var left = p.size
                while (left >= 255) { lacing.write(255); left -= 255 }
                lacing.write(left)
                body.write(p)
            }
            val segs = lacing.toByteArray()
            val header = ByteArray(27)
            "OggS".forEachIndexed { i, c -> header[i] = c.code.toByte() }
            header[4] = 0
            header[5] = ((if (bos) 2 else 0) or (if (eos) 4 else 0)).toByte()
            for (i in 0 until 8) header[6 + i] = (granule shr (8 * i)).toByte()
            for (i in 0 until 4) header[14 + i] = (serial shr (8 * i)).toByte()
            for (i in 0 until 4) header[18 + i] = (sequence shr (8 * i)).toByte()
            header[26] = segs.size.toByte()
            val page = header + segs + body.toByteArray()
            val crc = crc(page)
            for (i in 0 until 4) page[22 + i] = (crc shr (8 * i)).toByte()
            out.write(page)
            sequence++
        }
    }

    private val CRC_TABLE = IntArray(256) { n ->
        var r = n shl 24
        repeat(8) { r = if (r and 0x80000000.toInt() != 0) (r shl 1) xor 0x04C11DB7 else r shl 1 }
        r
    }

    private fun crc(data: ByteArray): Int {
        var crc = 0
        for (b in data) crc = (crc shl 8) xor CRC_TABLE[((crc ushr 24) xor (b.toInt() and 0xFF)) and 0xFF]
        return crc
    }
}
