package com.feelyeon.nasviewer

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.feelyeon.nasviewer.databinding.ItemReadingNoteBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

sealed class ReadingNote {
    abstract val path: String
    abstract val createdAt: Long

    data class BookmarkNote(val bookmark: Bookmark) : ReadingNote() {
        override val path get() = bookmark.filePath
        override val createdAt get() = bookmark.createdAt
    }

    data class HighlightNote(val highlight: Highlight) : ReadingNote() {
        override val path get() = highlight.filePath.substringBefore('#')
        override val createdAt get() = highlight.createdAt
    }
}

class ReadingNotesAdapter(
    private val onDelete: (ReadingNote) -> Unit
) : RecyclerView.Adapter<ReadingNotesAdapter.NoteVH>() {
    private val items = mutableListOf<ReadingNote>()
    private val dateFormat = SimpleDateFormat("MM/dd HH:mm", Locale.getDefault())

    fun submit(notes: List<ReadingNote>) {
        val diff = DiffUtil.calculateDiff(NoteDiffCallback(items, notes))
        items.clear()
        items.addAll(notes)
        diff.dispatchUpdatesTo(this)
    }

    private class NoteDiffCallback(
        private val old: List<ReadingNote>,
        private val new: List<ReadingNote>
    ) : DiffUtil.Callback() {
        override fun getOldListSize() = old.size
        override fun getNewListSize() = new.size

        private fun key(note: ReadingNote): Pair<Boolean, Long> = when (note) {
            is ReadingNote.BookmarkNote -> true to note.bookmark.id
            is ReadingNote.HighlightNote -> false to note.highlight.id
        }

        override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int) =
            key(old[oldItemPosition]) == key(new[newItemPosition])

        override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int) =
            old[oldItemPosition] == new[newItemPosition]
    }

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): NoteVH {
        return NoteVH(ItemReadingNoteBinding.inflate(LayoutInflater.from(parent.context), parent, false))
    }

    override fun onBindViewHolder(holder: NoteVH, position: Int) {
        val item = items[position]
        when (item) {
            is ReadingNote.BookmarkNote -> {
                holder.binding.typeText.text = "책갈피  ·  ${dateFormat.format(Date(item.createdAt))}"
                holder.binding.contentText.text = item.bookmark.label
                holder.binding.pathText.text = item.path.trim('/').ifBlank { "/" }
            }
            is ReadingNote.HighlightNote -> {
                holder.binding.typeText.text = "하이라이트  ·  ${dateFormat.format(Date(item.createdAt))}"
                holder.binding.contentText.text = item.highlight.snippet
                holder.binding.pathText.text = item.path.trim('/').ifBlank { "/" }
            }
        }
        holder.binding.deleteBtn.setOnClickListener { onDelete(item) }
    }

    class NoteVH(val binding: ItemReadingNoteBinding) : RecyclerView.ViewHolder(binding.root)
}
