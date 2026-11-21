package app.fluvour.path

/**
 * A small JSON reader (pure Kotlin, so the lesson check runs on a plain JVM). Objects become Map, arrays List, numbers Double,
 * plus String, Boolean and null. [parse] returns null for anything that is not valid JSON. [extract] cuts the first object out of a
 * model reply that may wrap it in a code fence or add words around it.
 */
object MiniJson {
    fun extract(text: String): String? {
        val a = text.indexOf('{'); val b = text.lastIndexOf('}')
        return if (a >= 0 && b > a) text.substring(a, b + 1) else null
    }

    fun parse(text: String): Any? = strict(text) ?: strict(dropTrailingCommas(text))

    private fun strict(text: String): Any? = try {
        val r = Reader(text); val v = r.value(); r.ws(); if (r.i != text.length) null else v
    } catch (e: Exception) { null }

    /** Models sometimes leave a comma before a closing bracket or brace. Removes those, outside of strings only. */
    fun dropTrailingCommas(text: String): String {
        val sb = StringBuilder(); var inStr = false; var esc = false
        for ((i, c) in text.withIndex()) {
            if (inStr) { sb.append(c); if (esc) esc = false else if (c == '\\') esc = true else if (c == '"') inStr = false; continue }
            if (c == '"') { inStr = true; sb.append(c); continue }
            if (c == ',') {
                var j = i + 1
                while (j < text.length && text[j].isWhitespace()) j++
                if (j < text.length && (text[j] == '}' || text[j] == ']')) continue
            }
            sb.append(c)
        }
        return sb.toString()
    }

    /** True when the reply starts a JSON object but never closes it (the model ran out of room). */
    fun looksCutOff(text: String): Boolean {
        var depth = 0; var inStr = false; var esc = false; var started = false
        for (c in text) {
            if (inStr) { if (esc) esc = false else if (c == '\\') esc = true else if (c == '"') inStr = false; continue }
            when (c) { '"' -> inStr = true; '{', '[' -> { depth++; started = true }; '}', ']' -> depth-- }
        }
        return started && depth > 0
    }

    private class Reader(val s: String) {
        var i = 0
        fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }
        fun value(): Any? {
            ws()
            return when (val c = s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> lit("true", true)
                'f' -> lit("false", false)
                'n' -> lit("null", null)
                else -> if (c == '-' || c.isDigit()) num() else throw IllegalStateException("bad value")
            }
        }
        fun lit(w: String, v: Any?): Any? { if (!s.startsWith(w, i)) throw IllegalStateException("bad literal"); i += w.length; return v }
        fun num(): Double { val st = i; while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++; return s.substring(st, i).toDouble() }
        fun str(): String {
            i++
            val sb = StringBuilder()
            while (true) {
                val c = s[i++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> when (val e = s[i++]) {
                        'n' -> sb.append('\n'); 't' -> sb.append('\t'); 'r' -> sb.append('\r'); 'b' -> sb.append('\b'); 'f' -> sb.append('\u000C')
                        'u' -> { sb.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                        else -> sb.append(e)
                    }
                    else -> sb.append(c)
                }
            }
        }
        fun arr(): List<Any?> {
            i++; val out = ArrayList<Any?>(); ws()
            if (s[i] == ']') { i++; return out }
            while (true) {
                out.add(value()); ws()
                when (s[i++]) { ',' -> continue; ']' -> return out; else -> throw IllegalStateException("bad array") }
            }
        }
        fun obj(): Map<String, Any?> {
            i++; val out = LinkedHashMap<String, Any?>(); ws()
            if (s[i] == '}') { i++; return out }
            while (true) {
                ws(); val k = str(); ws()
                if (s[i++] != ':') throw IllegalStateException("bad object")
                out[k] = value(); ws()
                when (s[i++]) { ',' -> continue; '}' -> return out; else -> throw IllegalStateException("bad object") }
            }
        }
    }
}
