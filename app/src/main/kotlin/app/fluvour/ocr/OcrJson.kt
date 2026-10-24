package app.fluvour.ocr

import org.json.JSONArray
import org.json.JSONObject

/** Saving a page as JSON (the OCR cache) and reading Gemini's answer. */
object OcrJson {
    private fun box(b: OcrBox) = JSONArray().put(b.left).put(b.top).put(b.right).put(b.bottom)
    private fun box(a: JSONArray) = OcrBox(a.getInt(0), a.getInt(1), a.getInt(2), a.getInt(3))

    fun toJson(p: OcrPage): String = JSONObject().put("engine", p.engine).put("w", p.width).put("h", p.height).put("blocks", JSONArray().also { bs ->
        for (b in p.blocks) bs.put(JSONObject().put("box", box(b.box)).put("text", b.text).put("starts", JSONArray(b.starts))
            .put("lines", JSONArray().also { ls ->
                for (l in b.lines) ls.put(JSONObject().put("t", l.text).put("b", box(l.box)).put("p", JSONArray().also { ps ->
                    for (pc in l.pieces) ps.put(JSONObject().put("t", pc.text).put("b", box(pc.box)))
                }))
            }))
    }).toString()

    fun parse(json: String): OcrPage {
        val o = JSONObject(json)
        val blocks = o.getJSONArray("blocks").let { bs ->
            (0 until bs.length()).map { i ->
                val b = bs.getJSONObject(i)
                val ls = b.getJSONArray("lines")
                val lines = (0 until ls.length()).map { j ->
                    val l = ls.getJSONObject(j)
                    val ps = l.getJSONArray("p")
                    OcrLine(l.getString("t"), box(l.getJSONArray("b")), (0 until ps.length()).map { k ->
                        ps.getJSONObject(k).let { OcrPiece(it.getString("t"), box(it.getJSONArray("b"))) }
                    })
                }
                val st = b.getJSONArray("starts")
                OcrBlock(lines, box(b.getJSONArray("box")), b.getString("text"), (0 until st.length()).map { st.getInt(it) })
            }
        }
        return OcrPage(blocks, o.getString("engine"), o.getInt("w"), o.getInt("h"))
    }

    /**
     * Gemini's answer: {"blocks":[{"text":"...","box_2d":[ymin,xmin,ymax,xmax]}]} with coordinates 0..1000.
     * Tolerates a markdown fence around the JSON. Returns null when it cannot be read at all.
     */
    fun fromGemini(answer: String, width: Int, height: Int): List<OcrBlock>? = try {
        val clean = answer.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val arr = if (clean.startsWith("[")) JSONArray(clean) else JSONObject(clean).getJSONArray("blocks")
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.getJSONObject(i)
            val text = o.optString("text").trim()
            val b = o.optJSONArray("box_2d")
            if (text.isEmpty() || b == null || b.length() < 4) return@mapNotNull null
            fun px(v: Double, size: Int) = (v.coerceIn(0.0, 1000.0) / 1000.0 * size).toInt()
            val box = OcrBox(px(b.getDouble(1), width), px(b.getDouble(0), height), px(b.getDouble(3), width), px(b.getDouble(2), height))
            OcrBlock.of(listOf(OcrLine(text, box)), "")
        }
    } catch (e: Exception) { null }
}
