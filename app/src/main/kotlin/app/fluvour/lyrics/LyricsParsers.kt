package app.fluvour.lyrics

import app.fluvour.media.SubLine
import app.fluvour.media.SubWord
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.Inflater

/** Parsers for the lyric formats the providers return. Pure Kotlin so they can be tested on a computer. */
object LyricsParsers {
    private val LINE_TAG = Regex("""^\s*\[(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?]""")
    private val WORD_TAG = Regex("""<(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?>""")

    private fun ms(m: String, s: String, frac: String): Long {
        val f = when (frac.length) { 0 -> 0L; 1 -> frac.toLong() * 100; 2 -> frac.toLong() * 10; else -> frac.take(3).toLong() }
        return m.toLong() * 60_000 + s.toLong() * 1000 + f
    }

    /** LRC, plain or enhanced (`<mm:ss.xx>` word stamps). Lines with several leading stamps are repeated. */
    fun parseLrc(text: String): List<SubLine> {
        val rows = ArrayList<Triple<Long, String, List<SubWord>>>()
        for (raw in text.lines()) {
            var rest = raw.trim()
            val starts = ArrayList<Long>()
            while (true) {
                val m = LINE_TAG.find(rest) ?: break
                starts += ms(m.groupValues[1], m.groupValues[2], m.groupValues[3])
                rest = rest.substring(m.range.last + 1).trimStart()
            }
            if (starts.isEmpty() || rest.isBlank()) continue
            val words = ArrayList<SubWord>()
            var plain = rest
            if (WORD_TAG.containsMatchIn(rest)) {
                val tg = WORD_TAG.findAll(rest).toList()
                val sb = StringBuilder()
                val head = rest.substring(0, tg[0].range.first)
                if (head.isNotBlank()) words += SubWord(starts[0], head)
                sb.append(head)
                for ((i, m) in tg.withIndex()) {
                    val end = if (i + 1 < tg.size) tg[i + 1].range.first else rest.length
                    val piece = rest.substring(m.range.last + 1, end)
                    if (piece.isBlank()) { sb.append(piece); continue }
                    words += SubWord(ms(m.groupValues[1], m.groupValues[2], m.groupValues[3]), piece)
                    sb.append(piece)
                }
                plain = sb.toString()
            }
            val text1 = plain.replace(Regex("\\s+"), " ").trim()
            if (text1.isEmpty()) continue
            val w = if (words.size > 1) words else emptyList()
            for (s in starts) rows += Triple(s, text1, if (starts.size == 1) w else emptyList())
        }
        rows.sortBy { it.first }
        return finish(rows)
    }

    private fun finish(rows: List<Triple<Long, String, List<SubWord>>>): List<SubLine> {
        val out = ArrayList<SubLine>(rows.size)
        for ((i, r) in rows.withIndex()) {
            val next = if (i + 1 < rows.size) rows[i + 1].first else r.first + 5000
            val end = minOf(next, r.first + 12_000).coerceAtLeast(r.first + 500)
            out += SubLine(r.first, end, r.second, r.third)
        }
        return out
    }

    /** True when at least [ratio] of the lines carry word times. */
    fun wordSynced(lines: List<SubLine>, ratio: Double = 0.6): Boolean =
        lines.isNotEmpty() && lines.count { it.words.size > 1 }.toDouble() / lines.size >= ratio

    // Kugou KRC ---------------------------------------------------------------------------------------------------------------------
    private val KRC_KEY = intArrayOf(0x40, 0x47, 0x61, 0x77, 0x5e, 0x32, 0x74, 0x47, 0x51, 0x36, 0x31, 0x2d, 0xce, 0xd2, 0x6e, 0x69)

    /** Base64 content from Kugou's download call to the KRC text. */
    fun krcDecode(base64: String): String? = try {
        val raw = Base64.getMimeDecoder().decode(base64)
        if (raw.size <= 4) null else {
            val body = ByteArray(raw.size - 4) { i -> (raw[i + 4].toInt() xor KRC_KEY[i % 16]).toByte() }
            val inf = Inflater()
            inf.setInput(body)
            val bos = ByteArrayOutputStream()
            val buf = ByteArray(8192)
            while (!inf.finished()) {
                val n = inf.inflate(buf)
                if (n == 0 && (inf.needsInput() || inf.needsDictionary())) break
                bos.write(buf, 0, n)
            }
            inf.end()
            String(bos.toByteArray(), Charsets.UTF_8).removePrefix("﻿")
        }
    } catch (_: Exception) { null }

    private val KRC_LINE = Regex("""^\[(\d+),(\d+)](.*)$""")
    private val KRC_WORD = Regex("""<(-?\d+),(\d+),\d+>""")
    private val KRC_CREDIT = Regex("""(?i)^(作词|作曲|编曲|词|曲|制作|混音|演唱|歌手|原唱|录音|吉他|贝斯|鼓|和声|监制|出品|发行|OP|SP|ISRC|lyrics?\s*by|composed?\s*by|written\s*by|arranged?\s*by|produced?\s*by|music\s*by)\s*[:：]""")

    fun parseKrc(text: String): List<SubLine> {
        val rows = ArrayList<Triple<Long, String, List<SubWord>>>()
        var globalOffset = 0L
        for (raw in text.lines()) {
            val line = raw.trim()
            if (line.startsWith("[offset:")) { globalOffset = line.removePrefix("[offset:").substringBefore(']').trim().toLongOrNull() ?: 0L; continue }
            val m = KRC_LINE.find(line) ?: continue
            val start = m.groupValues[1].toLong() + globalOffset
            val body = m.groupValues[3]
            val tags = KRC_WORD.findAll(body).toList()
            val words = ArrayList<SubWord>()
            val sb = StringBuilder()
            if (tags.isEmpty()) sb.append(body) else {
                for ((i, t) in tags.withIndex()) {
                    val end = if (i + 1 < tags.size) tags[i + 1].range.first else body.length
                    val piece = body.substring(t.range.last + 1, end)
                    sb.append(piece)
                    if (piece.isNotBlank()) words += SubWord(start + t.groupValues[1].toLong(), piece)
                }
            }
            val txt = sb.toString().replace(Regex("\\s+"), " ").trim()
            if (txt.isEmpty() || KRC_CREDIT.containsMatchIn(txt)) continue
            rows += Triple(start, txt, if (words.size > 1) words else emptyList())
        }
        rows.sortBy { it.first }
        return finish(rows)
    }
}
