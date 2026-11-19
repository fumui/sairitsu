package my.fumui.sairitsu.gameplay

import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** The bytes written for a cache entry do not match the SHA-1 the server advertised. */
class HashMismatch(val key: String, val expected: String, val actual: String) :
    IOException("Hash mismatch for $key: expected $expected, got $actual")

fun sha1Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-1").digest(bytes).toHex()

fun sha1Hex(text: String): String = sha1Hex(text.toByteArray(Charsets.UTF_8))

private fun ByteArray.toHex(): String {
    val digits = "0123456789abcdef"
    val out = StringBuilder(size * 2)
    for (b in this) {
        out.append(digits[(b.toInt() shr 4) and 0xF]).append(digits[b.toInt() and 0xF])
    }
    return out.toString()
}

private fun File.sha1Hex(): String {
    val digest = MessageDigest.getInstance("SHA-1")
    inputStream().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            digest.update(buffer, 0, n)
        }
    }
    return digest.digest().toHex()
}

/**
 * Content-addressed file cache with LRU eviction by last-modified time. An entry exists only after
 * it was fully written and, when a hash was given, verified: writes go to `key.tmp` and are renamed.
 */
class ResourceCache(private val root: File, private val maxBytes: Long = 2L shl 30) {
    init {
        root.mkdirs()
        root.listFiles { f -> f.name.endsWith(TMP) }?.forEach { it.delete() }
    }

    /** The cached file for [key], marked as used just now, or null. */
    @Synchronized
    fun file(key: String): File? {
        val f = entry(key)
        if (!f.isFile) return null
        f.setLastModified(System.currentTimeMillis())
        return f
    }

    /**
     * Writes [key] through [write], verifies SHA-1 against [expectedSha1] when it is not null and
     * stores it. On any failure nothing is left behind.
     */
    fun put(key: String, expectedSha1: String?, write: (OutputStream) -> Unit): File {
        val dest = entry(key)
        val tmp = File.createTempFile("t-$key.", TMP, root)
        try {
            tmp.outputStream().use { write(it) }
            if (expectedSha1 != null) {
                val actual = tmp.sha1Hex()
                if (!actual.equals(expectedSha1, ignoreCase = true)) {
                    throw HashMismatch(key, expectedSha1.lowercase(), actual)
                }
            }
            try {
                Files.move(tmp.toPath(), dest.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(tmp.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            dest.setLastModified(System.currentTimeMillis())
        } catch (t: Throwable) {
            tmp.delete()
            throw t
        }
        return dest
    }

    /**
     * Deletes least recently used entries not in [pinned] until the unpinned entries fit in `maxBytes`. Pinned entries
     * (the level being loaded, downloaded levels) never count against the budget, so a large library does not squeeze
     * out the cache of streamed levels.
     */
    @Synchronized
    fun evict(pinned: Set<String>) {
        val files = entries().filter { it.name !in pinned }
        var total = files.sumOf { it.length() }
        for (f in files.sortedBy { it.lastModified() }) {
            if (total <= maxBytes) break
            val size = f.length()
            if (f.delete()) total -= size
        }
    }

    /** Where [key] is (or would be) stored; no I/O, so the UI may call it per row. */
    fun pathOf(key: String): File = entry(key)

    /** Whether [key] is cached; unlike [file] it does not mark the entry as used. */
    @Synchronized
    fun has(key: String): Boolean = entry(key).isFile

    /** The keys of every entry. */
    @Synchronized
    fun keys(): Set<String> = entries().mapTo(HashSet()) { it.name }

    /** Deletes the entries for [keys]; missing keys are ignored. */
    @Synchronized
    fun delete(keys: Set<String>) {
        for (key in keys) entry(key).delete()
    }

    /** The total size of the entries for [keys] that exist. */
    @Synchronized
    fun sizeOf(keys: Set<String>): Long = keys.sumOf { key -> entry(key).let { if (it.isFile) it.length() else 0L } }

    /** The total size of every entry. */
    @Synchronized
    fun totalBytes(): Long = entries().sumOf { it.length() }

    private fun entries(): Array<File> = root.listFiles { f -> f.isFile && !f.name.endsWith(TMP) } ?: emptyArray()

    private fun entry(key: String): File {
        require(KEY.matches(key)) { "Bad cache key: $key" }
        return File(root, key)
    }

    private companion object {
        const val TMP = ".tmp"
        val KEY = Regex("[A-Za-z0-9_-]+")
    }
}
