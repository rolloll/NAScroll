package com.feelyeon.nasviewer

import java.io.File
import java.security.MessageDigest

/**
 * Shared cache-key + staleness-check helper for the on-device EPUB/PDF caches. A plain
 * 32-bit filePath.hashCode() key can collide, and neither reader previously noticed when
 * the NAS file changed under the same path (e.g. a batch cover-image swap) — this pairs a
 * collision-safe SHA-256 key with a small sidecar file recording the NAS size/mtime seen
 * at download time, so a changed file is detected and re-downloaded instead of silently
 * serving stale cached bytes forever.
 */
object CacheStaleness {
    fun keyFor(path: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(path.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    // size/mtime of -1 means "unknown" (caller couldn't get NAS metadata for this file) —
    // in that case staleness can't be detected, so an existing cache is trusted rather
    // than forcing a redownload on every open.
    fun isFresh(metaFile: File, size: Long, mtime: Long): Boolean {
        if (size < 0 || mtime < 0) return true
        if (!metaFile.exists()) return false
        return metaFile.readText() == "$size:$mtime"
    }

    fun writeMeta(metaFile: File, size: Long, mtime: Long) {
        metaFile.writeText("$size:$mtime")
    }
}
