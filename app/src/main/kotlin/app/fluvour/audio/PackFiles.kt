package app.fluvour.audio

import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.zip.ZipFile

/** File work for voice packs. Pure Java (no Android), so it is checked on a plain JVM in tools/voicetest. Used by [PackManager]. */
object PackFiles {
    fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Unpacks [zip] into [dest]. Refuses any entry that would land outside [dest] and stops past [maxBytes] of unpacked data
     * (a damaged or hostile zip). Returns how many files were written.
     */
    fun unzip(zip: File, dest: File, maxBytes: Long): Int {
        dest.mkdirs()
        val root = dest.canonicalFile
        var total = 0L
        var files = 0
        ZipFile(zip).use { z ->
            for (e in z.entries()) {
                val out = File(root, e.name).canonicalFile
                if (out.path != root.path && !out.path.startsWith(root.path + File.separator)) throw IOException("zip entry outside the folder: ${e.name}")
                if (e.isDirectory) { out.mkdirs(); continue }
                out.parentFile?.mkdirs()
                z.getInputStream(e).use { input ->
                    out.outputStream().use { o ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            total += n
                            if (total > maxBytes) throw IOException("zip unpacks to more than $maxBytes bytes")
                            o.write(buf, 0, n)
                        }
                    }
                }
                files++
            }
        }
        return files
    }

    /** How many bytes of a half-downloaded [part] file can be kept. A file longer than the pack is not ours: deleted, start again. */
    fun resumeOffset(part: File, size: Long): Long {
        if (!part.exists()) return 0
        val len = part.length()
        if (len > size) { part.delete(); return 0 }
        return len
    }

    /** First byte of a `Content-Range: bytes 100-199/200` header, or null when absent or odd. */
    fun contentRangeStart(header: String?): Long? {
        val m = Regex("""bytes\s+(\d+)-(\d+)/(\d+|\*)""").find(header ?: return null) ?: return null
        return m.groupValues[1].toLongOrNull()
    }

    fun percent(done: Long, total: Long): Int = if (total <= 0) 0 else (done * 100 / total).toInt().coerceIn(0, 100)
}
