package com.feelyeon.nasviewer

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.feelyeon.nasviewer.databinding.ItemTocBinding

class TocAdapter(
    private val titles: List<String>,
    private val pages: List<Int>,
    private val currentIndex: Int,
    private val onClick: (Int) -> Unit
) : RecyclerView.Adapter<TocAdapter.VH>() {

    inner class VH(val binding: ItemTocBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemTocBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val binding = holder.binding
        binding.pageText.text = pages.getOrNull(position)?.toString() ?: ""
        binding.titleText.text = titles[position]
        val isCurrent = position == currentIndex
        val context = binding.root.context
        val color = ContextCompat.getColor(context, if (isCurrent) R.color.accent2 else R.color.text_primary)
        binding.titleText.setTextColor(color)
        binding.pageText.setTextColor(
            ContextCompat.getColor(context, if (isCurrent) R.color.accent2 else R.color.text_muted)
        )
        binding.root.setOnClickListener { onClick(position) }
    }

    override fun getItemCount(): Int = titles.size
}
