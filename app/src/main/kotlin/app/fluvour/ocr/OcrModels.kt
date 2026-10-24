package app.fluvour.ocr

import kotlin.math.max

/** A rectangle in screen pixels. */
data class OcrBox(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    fun contains(x: Int, y: Int) = x in left..right && y in top..bottom
    fun shift(dx: Int, dy: Int) = OcrBox(left + dx, top + dy, right + dx, bottom + dy)
    /** Distance from a point to the box, 0 when inside. */
    fun distanceTo(x: Int, y: Int): Int = max(max(left - x, 0), x - right) + max(max(top - y, 0), y - bottom)
    val area: Long get() = width.toLong() * height
}

/** A smaller part of a line (a word, or a few characters) when the engine reports it. */
data class OcrPiece(val text: String, val box: OcrBox)

/** One recognised line, or one column of vertical text. */
data class OcrLine(val text: String, val box: OcrBox, val pieces: List<OcrPiece> = emptyList())

/**
 * A block is one speech bubble, caption or paragraph: the unit you tap.
 * `text` is its lines joined (no space for Japanese, Chinese and Thai); `starts[i]` is where `lines[i]` begins inside `text`.
 */
data class OcrBlock(val lines: List<OcrLine>, val box: OcrBox, val text: String, val starts: List<Int>) {
    companion object {
        fun of(lines: List<OcrLine>, joiner: String): OcrBlock? {
            val kept = lines.filter { it.text.isNotBlank() }
            if (kept.isEmpty()) return null
            val sb = StringBuilder()
            val starts = ArrayList<Int>()
            kept.forEachIndexed { i, l -> if (i > 0) sb.append(joiner); starts += sb.length; sb.append(l.text) }
            val box = OcrBox(kept.minOf { it.box.left }, kept.minOf { it.box.top }, kept.maxOf { it.box.right }, kept.maxOf { it.box.bottom })
            return OcrBlock(kept, box, sb.toString(), starts)
        }
    }
}

/** Everything read from one capture. `engine` says who read it (for the cache and for debugging). */
data class OcrPage(val blocks: List<OcrBlock>, val engine: String, val width: Int, val height: Int)

/** Pure geometry for turning a finger position into a block and a character position. No Android imports. */
object OcrGeometry {
    /** Languages written without spaces between words: their lines are joined with nothing. */
    fun joiner(lang: String): String = if (lang == "ja" || lang == "zh" || lang == "th") "" else " "

    /** The smallest block containing the point, else the nearest block within `slop` pixels. */
    fun blockAt(page: OcrPage, x: Int, y: Int, slop: Int = 28): OcrBlock? {
        page.blocks.filter { it.box.contains(x, y) }.minByOrNull { it.box.area }?.let { return it }
        return page.blocks.map { it to it.box.distanceTo(x, y) }.filter { it.second <= slop }.minByOrNull { it.second }?.first
    }

    /** Character position inside `block.text` nearest to the point. */
    fun offsetAt(block: OcrBlock, x: Int, y: Int): Int {
        if (block.text.isEmpty()) return 0
        val i = block.lines.indices.minByOrNull { block.lines[it].box.distanceTo(x, y) } ?: return 0
        return (block.starts[i] + offsetInLine(block.lines[i], x, y)).coerceIn(0, block.text.length - 1)
    }

    fun offsetInLine(line: OcrLine, x: Int, y: Int): Int {
        if (line.text.isEmpty()) return 0
        var cursor = 0
        for (p in line.pieces) {                       // pieces that can be placed in the line text, in order
            val at = line.text.indexOf(p.text, cursor)
            if (at < 0) continue
            if (p.box.contains(x, y)) return at + within(p.text.length, p.box, x, y)
            cursor = at + p.text.length
        }
        return within(line.text.length, line.box, x, y)
    }

    /** Position along the box's long side: top to bottom for tall boxes (vertical text), left to right otherwise. */
    private fun within(len: Int, box: OcrBox, x: Int, y: Int): Int {
        val vertical = len > 1 && box.height > box.width * 1.3
        val t = if (vertical) (y - box.top).toDouble() / max(1, box.height) else (x - box.left).toDouble() / max(1, box.width)
        return (t.coerceIn(0.0, 0.999) * len).toInt()
    }

    /** The page moved by (dx, dy): used when only a cropped area was read. */
    fun shift(page: OcrPage, dx: Int, dy: Int): OcrPage = if (dx == 0 && dy == 0) page else page.copy(blocks = page.blocks.map { b ->
        b.copy(box = b.box.shift(dx, dy), lines = b.lines.map { l ->
            l.copy(box = l.box.shift(dx, dy), pieces = l.pieces.map { p -> p.copy(box = p.box.shift(dx, dy)) })
        })
    })
}
