package com.feelyeon.nasviewer

import android.content.Context
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.feelyeon.nasviewer.databinding.ItemFolderBinding
import com.feelyeon.nasviewer.databinding.ItemReadAllBinding
import com.feelyeon.nasviewer.databinding.ItemThumbBinding

sealed class BrowserRow {
    data class ReadAll(val count: Int) : BrowserRow()
    data class Folder(val item: FileItem) : BrowserRow()
    data class Doc(val item: FileItem) : BrowserRow()
    data class Thumb(val item: FileItem, val index: Int) : BrowserRow()
}

class BrowserAdapter(
    private val context: Context,
    private val onFolderClick: (FileItem) -> Unit,
    private val onDocClick: (FileItem) -> Unit,
    private val onReadAllClick: () -> Unit,
    private val onThumbClick: (Int) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        const val TYPE_READ_ALL = 0
        const val TYPE_FOLDER = 1
        const val TYPE_THUMB = 2
        const val TYPE_DOC = 3
    }

    private val rows = mutableListOf<BrowserRow>()

    fun submit(newRows: List<BrowserRow>) {
        val diff = DiffUtil.calculateDiff(RowDiffCallback(rows, newRows))
        rows.clear()
        rows.addAll(newRows)
        diff.dispatchUpdatesTo(this)
    }

    // Item identity is the row kind plus the FileItem path (ReadAll has no path, but
    // there is at most one such row per list). Content equality falls out of the data
    // classes' generated equals(), which already compares FileItem.size/mtime, so a
    // stale thumbnail or renamed doc is still detected as changed.
    private class RowDiffCallback(
        private val old: List<BrowserRow>,
        private val new: List<BrowserRow>
    ) : DiffUtil.Callback() {
        override fun getOldListSize() = old.size
        override fun getNewListSize() = new.size

        private fun key(row: BrowserRow): String = when (row) {
            is BrowserRow.ReadAll -> "readall"
            is BrowserRow.Folder -> "folder:${row.item.path}"
            is BrowserRow.Doc -> "doc:${row.item.path}"
            is BrowserRow.Thumb -> "thumb:${row.item.path}"
        }

        override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int) =
            key(old[oldItemPosition]) == key(new[newItemPosition])

        override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int) =
            old[oldItemPosition] == new[newItemPosition]
    }

    override fun getItemViewType(position: Int): Int = when (rows[position]) {
        is BrowserRow.ReadAll -> TYPE_READ_ALL
        is BrowserRow.Folder -> TYPE_FOLDER
        is BrowserRow.Doc -> TYPE_DOC
        is BrowserRow.Thumb -> TYPE_THUMB
    }

    override fun getItemCount(): Int = rows.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_READ_ALL -> ReadAllVH(ItemReadAllBinding.inflate(inflater, parent, false))
            TYPE_FOLDER -> FolderVH(ItemFolderBinding.inflate(inflater, parent, false))
            TYPE_DOC -> DocVH(ItemFolderBinding.inflate(inflater, parent, false))
            else -> ThumbVH(ItemThumbBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is BrowserRow.ReadAll -> (holder as ReadAllVH).bind(row)
            is BrowserRow.Folder -> (holder as FolderVH).bind(row)
            is BrowserRow.Doc -> (holder as DocVH).bind(row)
            is BrowserRow.Thumb -> (holder as ThumbVH).bind(row)
        }
    }

    inner class ReadAllVH(val binding: ItemReadAllBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(row: BrowserRow.ReadAll) {
            binding.readAllBtn.text = "▶ 전체 보기 (${row.count}장)"
            binding.readAllBtn.setOnClickListener { onReadAllClick() }
        }
    }

    inner class FolderVH(val binding: ItemFolderBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(row: BrowserRow.Folder) {
            binding.iconText.text = "📁"
            binding.nameText.text = row.item.name
            binding.nameText.textSize = 15f * Prefs.uiTextScale(context)
            binding.root.setOnClickListener { onFolderClick(row.item) }
        }
    }

    inner class DocVH(val binding: ItemFolderBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(row: BrowserRow.Doc) {
            binding.iconText.text = when {
                row.item.isEpub() -> "📖"
                row.item.isPdf() -> "📕"
                else -> "📄"
            }
            binding.nameText.text = row.item.name
            binding.nameText.textSize = 15f * Prefs.uiTextScale(context)
            binding.root.setOnClickListener { onDocClick(row.item) }
        }
    }

    inner class ThumbVH(val binding: ItemThumbBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(row: BrowserRow.Thumb) {
            binding.idxText.text = (row.index + 1).toString()
            Glide.with(context).load(SynologyApi.thumbUrl(context, row.item.path)).into(binding.thumbImage)
            binding.root.setOnClickListener { onThumbClick(row.index) }
        }
    }
}
