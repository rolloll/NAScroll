package com.feelyeon.nasviewer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.util.LruCache
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.feelyeon.nasviewer.databinding.ItemPdfPageBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class PdfPageAdapter(
    private val context: Context,
    private val scope: CoroutineScope,
    private val renderer: PdfRenderer,
    // Per-page pixel heights for the continuous-scroll RecyclerView, precomputed by the
    // caller on a background thread (see computePageHeights below) — each item must report
    // its natural (aspect-ratio-correct) height up front so the list lays out and scrolls
    // smoothly before that page's bitmap has actually finished rendering. Null means the
    // paged ViewPager2 mode, where every page just fills the full screen (XML match_parent)
    // regardless of its own aspect ratio.
    private val pageHeights: IntArray? = null,
    private val onPageTapped: (Float) -> Unit
) : RecyclerView.Adapter<PdfPageAdapter.PageVH>() {

    companion object {
        private const val CACHE_BUDGET_KB = 48 * 1024

        fun targetWidthFor(context: Context): Int =
            context.resources.displayMetrics.widthPixels.coerceAtMost(1600)

        // Cheap — openPage()/close() just reads page dimensions, no bitmap decode — but still
        // called from a background thread by convention, since this touches PdfRenderer.
        fun computePageHeights(renderer: PdfRenderer, targetWidth: Int): IntArray =
            IntArray(renderer.pageCount) { index ->
                val page = renderer.openPage(index)
                try {
                    (targetWidth.toFloat() / page.width * page.height).toInt().coerceAtLeast(1)
                } finally {
                    page.close()
                }
            }
    }

    private val renderMutex = Mutex()
    // Bounded by memory (KB), not entry count — a 1600px-wide A4 page is ~14MB as
    // ARGB_8888, so a count-based cap of "12 pages" could still balloon to ~170MB.
    private val bitmapCache = object : LruCache<Int, Bitmap>(CACHE_BUDGET_KB) {
        override fun sizeOf(key: Int, value: Bitmap): Int = (value.allocationByteCount / 1024).coerceAtLeast(1)
    }
    private val targetWidth = targetWidthFor(context)

    override fun getItemCount(): Int = renderer.pageCount

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageVH {
        val inflater = LayoutInflater.from(parent.context)
        val binding = ItemPdfPageBinding.inflate(inflater, parent, false)
        binding.pageImage.singleTapListener = onPageTapped
        return PageVH(binding)
    }

    override fun onBindViewHolder(holder: PageVH, position: Int) {
        pageHeights?.let { heights ->
            val lp = holder.itemView.layoutParams
            val height = heights[position]
            if (lp.height != height) {
                lp.height = height
                holder.itemView.layoutParams = lp
            }
        }
        val cached = bitmapCache.get(position)
        if (cached != null) {
            holder.binding.pageImage.setImageBitmap(cached)
            return
        }
        holder.binding.pageImage.setImageDrawable(null)
        holder.job?.cancel()
        holder.job = scope.launch {
            val bitmap = renderPage(position)
            if (holder.bindingAdapterPosition == position) {
                bitmapCache.put(position, bitmap)
                holder.binding.pageImage.setImageBitmap(bitmap)
            }
        }
    }

    override fun onViewRecycled(holder: PageVH) {
        super.onViewRecycled(holder)
        holder.job?.cancel()
        holder.job = null
    }

    private suspend fun renderPage(index: Int): Bitmap = withContext(Dispatchers.IO) {
        renderMutex.withLock {
            val page = renderer.openPage(index)
            try {
                val scale = targetWidth.toFloat() / page.width
                val height = (page.height * scale).toInt().coerceAtLeast(1)
                val bitmap = Bitmap.createBitmap(targetWidth, height, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                bitmap
            } finally {
                page.close()
            }
        }
    }

    class PageVH(val binding: ItemPdfPageBinding) : RecyclerView.ViewHolder(binding.root) {
        var job: Job? = null
    }
}
