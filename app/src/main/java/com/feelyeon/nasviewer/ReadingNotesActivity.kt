package com.feelyeon.nasviewer

import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.feelyeon.nasviewer.databinding.ActivityReadingNotesBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ReadingNotesActivity : AppCompatActivity() {
    private lateinit var binding: ActivityReadingNotesBinding
    private val adapter = ReadingNotesAdapter { note -> confirmDelete(note) }
    private var selectedTab = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityReadingNotesBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.notesRecycler.layoutManager = LinearLayoutManager(this)
        binding.notesRecycler.adapter = adapter
        binding.backBtn.setOnClickListener { finish() }
        binding.allTab.setOnClickListener { selectTab(0) }
        binding.highlightTab.setOnClickListener { selectTab(1) }
        binding.bookmarkTab.setOnClickListener { selectTab(2) }
        binding.root.post { selectTab(0) }
    }

    override fun onResume() {
        super.onResume()
        if (::binding.isInitialized) selectTab(selectedTab)
    }

    private fun selectTab(tab: Int) {
        selectedTab = tab
        val active = ContextCompat.getColor(this, R.color.text_primary)
        val inactive = ContextCompat.getColor(this, R.color.text_muted)
        listOf(binding.allTab, binding.highlightTab, binding.bookmarkTab).forEachIndexed { index, view ->
            view.setTextColor(if (index == tab) active else inactive)
        }
        binding.tabIndicator.post {
            val width = binding.root.width / 3
            val params = binding.tabIndicator.layoutParams as LinearLayout.LayoutParams
            params.width = width
            params.leftMargin = width * tab
            binding.tabIndicator.layoutParams = params
        }
        lifecycleScope.launch {
            val db = AnnotationDb.get(this@ReadingNotesActivity)
            val notes = withContext(Dispatchers.IO) {
                val bookmarks = db.allBookmarks().map { ReadingNote.BookmarkNote(it) }
                val highlights = db.allHighlights().map { ReadingNote.HighlightNote(it) }
                when (tab) {
                    1 -> highlights
                    2 -> bookmarks
                    else -> (bookmarks + highlights).sortedByDescending { it.createdAt }
                }
            }
            if (selectedTab != tab) return@launch // a newer tab switch already superseded this load
            adapter.submit(notes)
            binding.emptyText.visibility = if (notes.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    private fun confirmDelete(note: ReadingNote) {
        val label = when (note) {
            is ReadingNote.BookmarkNote -> "책갈피"
            is ReadingNote.HighlightNote -> "하이라이트"
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("$label 삭제")
            .setMessage("이 독서 노트를 삭제하시겠습니까?")
            .setNegativeButton("취소", null)
            .setPositiveButton("삭제") { _, _ ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        when (note) {
                            is ReadingNote.BookmarkNote -> AnnotationDb.get(this@ReadingNotesActivity).deleteBookmark(note.bookmark.id)
                            is ReadingNote.HighlightNote -> AnnotationDb.get(this@ReadingNotesActivity).deleteHighlight(note.highlight.id)
                        }
                    }
                    selectTab(selectedTab)
                }
            }
            .show()
    }
}
