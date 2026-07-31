package com.feelyeon.nasviewer

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class Bookmark(
    val id: Long,
    val filePath: String,
    val position: String, // opaque location string; format depends on reader (text offset, or "spineIndex:scrollFraction" for epub)
    val label: String,
    val createdAt: Long
)

data class Highlight(
    val id: Long,
    val filePath: String,
    val startOffset: Int,
    val endOffset: Int,
    val snippet: String,
    val createdAt: Long,
    // Set only for EPUB highlights: the index (in document order) of the text node the
    // highlight anchors to, with startOffset/endOffset then meaning offsets *within that
    // node* rather than a whole-chapter position. Null for TXT highlights (whose
    // startOffset/endOffset are absolute character offsets into the file, as before) and
    // for older EPUB highlights created before this column existed.
    val nodeIndex: Int? = null
)

/**
 * All annotation data (bookmarks, highlights, last-read position) lives only in this
 * app-private SQLite database on the device. Nothing here is ever written back to the
 * NAS — SynologyApi only exposes read APIs (List/Thumb/Download), so there is no code
 * path that could touch the original file even by accident.
 */
class AnnotationDb private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "annotations.db", null, 5) {

    companion object {
        @Volatile
        private var instance: AnnotationDb? = null

        fun get(context: Context): AnnotationDb =
            instance ?: synchronized(this) {
                instance ?: AnnotationDb(context).also { instance = it }
            }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE bookmarks (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                file_path TEXT NOT NULL,
                position TEXT NOT NULL,
                label TEXT NOT NULL,
                created_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE highlights (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                file_path TEXT NOT NULL,
                start_offset INTEGER NOT NULL,
                end_offset INTEGER NOT NULL,
                snippet TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                node_index INTEGER
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE reading_progress (
                file_path TEXT PRIMARY KEY,
                position TEXT NOT NULL,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        createIndices(db)
    }

    private fun createIndices(db: SQLiteDatabase) {
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_bookmarks_file_path ON bookmarks(file_path, created_at)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_highlights_file_path ON highlights(file_path, start_offset)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS pdf_highlights (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    file_path TEXT NOT NULL,
                    page_index INTEGER NOT NULL,
                    rect_left REAL NOT NULL,
                    rect_top REAL NOT NULL,
                    rect_right REAL NOT NULL,
                    rect_bottom REAL NOT NULL,
                    created_at INTEGER NOT NULL
                )
                """.trimIndent()
            )
        }
        if (oldVersion < 3) {
            // PDF highlighting was replaced with page bookmarks (existing bookmarks table).
            db.execSQL("DROP TABLE IF EXISTS pdf_highlights")
        }
        if (oldVersion < 4) {
            createIndices(db)
        }
        if (oldVersion < 5) {
            db.execSQL("ALTER TABLE highlights ADD COLUMN node_index INTEGER")
        }
    }

    fun addBookmark(filePath: String, position: String, label: String): Long {
        val values = ContentValues().apply {
            put("file_path", filePath)
            put("position", position)
            put("label", label)
            put("created_at", System.currentTimeMillis())
        }
        return writableDatabase.insert("bookmarks", null, values)
    }

    fun bookmarksFor(filePath: String): List<Bookmark> {
        val result = mutableListOf<Bookmark>()
        readableDatabase.rawQuery(
            "SELECT id, file_path, position, label, created_at FROM bookmarks WHERE file_path = ? ORDER BY created_at DESC",
            arrayOf(filePath)
        ).use { c ->
            while (c.moveToNext()) {
                result.add(
                    Bookmark(
                        id = c.getLong(0),
                        filePath = c.getString(1),
                        position = c.getString(2),
                        label = c.getString(3),
                        createdAt = c.getLong(4)
                    )
                )
            }
        }
        return result
    }

    fun deleteBookmark(id: Long) {
        writableDatabase.delete("bookmarks", "id = ?", arrayOf(id.toString()))
    }

    fun addHighlight(filePath: String, startOffset: Int, endOffset: Int, snippet: String, nodeIndex: Int? = null): Long {
        val values = ContentValues().apply {
            put("file_path", filePath)
            put("start_offset", startOffset)
            put("end_offset", endOffset)
            put("snippet", snippet)
            put("created_at", System.currentTimeMillis())
            if (nodeIndex != null) put("node_index", nodeIndex) else putNull("node_index")
        }
        return writableDatabase.insert("highlights", null, values)
    }

    private fun Cursor.toHighlight(): Highlight {
        val nodeIndexColumn = getColumnIndexOrThrow("node_index")
        return Highlight(
            id = getLong(getColumnIndexOrThrow("id")),
            filePath = getString(getColumnIndexOrThrow("file_path")),
            startOffset = getInt(getColumnIndexOrThrow("start_offset")),
            endOffset = getInt(getColumnIndexOrThrow("end_offset")),
            snippet = getString(getColumnIndexOrThrow("snippet")),
            createdAt = getLong(getColumnIndexOrThrow("created_at")),
            nodeIndex = if (isNull(nodeIndexColumn)) null else getInt(nodeIndexColumn)
        )
    }

    fun highlightsFor(filePath: String): List<Highlight> {
        val result = mutableListOf<Highlight>()
        readableDatabase.rawQuery(
            "SELECT id, file_path, start_offset, end_offset, snippet, created_at, node_index FROM highlights WHERE file_path = ? ORDER BY start_offset ASC",
            arrayOf(filePath)
        ).use { c ->
            while (c.moveToNext()) {
                result.add(c.toHighlight())
            }
        }
        return result
    }

    fun deleteHighlight(id: Long) {
        writableDatabase.delete("highlights", "id = ?", arrayOf(id.toString()))
    }

    // EPUB highlights are stored per-chapter under "filePath#chapterFileName" keys (a DOM
    // position only makes sense within one chapter's HTML) — this pulls every highlight
    // across all of a book's chapters at once for a combined "reading notes" view.
    fun highlightsForBookPrefix(filePath: String): List<Highlight> {
        val result = mutableListOf<Highlight>()
        readableDatabase.rawQuery(
            "SELECT id, file_path, start_offset, end_offset, snippet, created_at, node_index FROM highlights " +
                "WHERE file_path = ? OR file_path LIKE ? ORDER BY created_at DESC",
            arrayOf(filePath, "$filePath#%")
        ).use { c ->
            while (c.moveToNext()) {
                result.add(c.toHighlight())
            }
        }
        return result
    }

    fun allBookmarks(): List<Bookmark> {
        val result = mutableListOf<Bookmark>()
        readableDatabase.rawQuery(
            "SELECT id, file_path, position, label, created_at FROM bookmarks ORDER BY created_at DESC",
            null
        ).use { c ->
            while (c.moveToNext()) {
                result.add(Bookmark(c.getLong(0), c.getString(1), c.getString(2), c.getString(3), c.getLong(4)))
            }
        }
        return result
    }

    fun allHighlights(): List<Highlight> {
        val result = mutableListOf<Highlight>()
        readableDatabase.rawQuery(
            "SELECT id, file_path, start_offset, end_offset, snippet, created_at, node_index FROM highlights ORDER BY created_at DESC",
            null
        ).use { c ->
            while (c.moveToNext()) {
                result.add(c.toHighlight())
            }
        }
        return result
    }
    fun saveProgress(filePath: String, position: String) {
        val values = ContentValues().apply {
            put("file_path", filePath)
            put("position", position)
            put("updated_at", System.currentTimeMillis())
        }
        writableDatabase.insertWithOnConflict("reading_progress", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun getProgress(filePath: String): String? {
        readableDatabase.rawQuery(
            "SELECT position FROM reading_progress WHERE file_path = ?",
            arrayOf(filePath)
        ).use { c ->
            return if (c.moveToFirst()) c.getString(0) else null
        }
    }
}
