package com.feelyeon.nasviewer

import android.content.Context
import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.load.resource.bitmap.DownsampleStrategy
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import com.feelyeon.nasviewer.databinding.ItemReaderPageBinding

class ReaderAdapter(
    private val context: Context,
    private val images: List<FileItem>,
    private val onImageError: (position: Int) -> Unit
) : RecyclerView.Adapter<ReaderAdapter.PageVH>() {

    // Target width = screen width, for crisp on-screen rendering (the ImageView's
    // wrap_content height otherwise leaves Glide guessing a target size, which
    // previously caused it to decode a tiny thumbnail-resolution bitmap and stretch
    // it). Height is capped, not left original: webtoon "cut" files can be tens of
    // thousands of pixels tall, and decoding one at full original resolution can
    // produce a bitmap past Android's hard ~100MB Canvas draw limit, crashing the
    // app. AT_MOST shrinks both dimensions together only if the height cap would
    // otherwise be exceeded, so normal-length pages still decode at full screen width.
    private val targetWidth = context.resources.displayMetrics.widthPixels
    private val maxHeight = 12000

    override fun getItemCount(): Int = images.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageVH {
        val inflater = LayoutInflater.from(parent.context)
        val binding = ItemReaderPageBinding.inflate(inflater, parent, false)
        binding.pageImage.fitMode = ZoomableImageView.FitMode.FIT_WIDTH_AUTO_HEIGHT
        return PageVH(binding)
    }

    override fun onBindViewHolder(holder: PageVH, position: Int) {
        val item = images[position]
        holder.binding.pageStatus.text = "${position + 1} / ${images.size}"
        holder.binding.pageStatus.visibility = View.VISIBLE
        Glide.with(context)
            .load(SynologyApi.downloadUrl(context, item.path))
            .downsample(DownsampleStrategy.AT_MOST)
            .override(targetWidth, maxHeight)
            .listener(object : RequestListener<Drawable> {
                override fun onLoadFailed(
                    e: GlideException?,
                    model: Any?,
                    target: Target<Drawable>,
                    isFirstResource: Boolean
                ): Boolean {
                    onImageError(position)
                    return false
                }

                override fun onResourceReady(
                    resource: Drawable,
                    model: Any,
                    target: Target<Drawable>?,
                    dataSource: DataSource,
                    isFirstResource: Boolean
                ): Boolean {
                    holder.binding.pageStatus.visibility = View.GONE
                    return false
                }
            })
            .into(holder.binding.pageImage)
    }

    class PageVH(val binding: ItemReaderPageBinding) : RecyclerView.ViewHolder(binding.root)
}
