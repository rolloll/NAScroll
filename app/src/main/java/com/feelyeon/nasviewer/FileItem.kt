package com.feelyeon.nasviewer

data class FileItem(
    val name: String,
    val path: String,
    val isDir: Boolean,
    // -1 means "unknown" (e.g. synthesized locally rather than read from the NAS listing).
    // Used to detect a stale on-device cache when the NAS file changes under the same path.
    val size: Long = -1,
    val mtime: Long = -1
)
