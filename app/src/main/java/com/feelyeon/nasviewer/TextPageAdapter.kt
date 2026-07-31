package com.feelyeon.nasviewer

import android.graphics.Color
import android.graphics.Typeface
import android.text.SpannableString
import android.text.style.BackgroundColorSpan
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class TextPageAdapter(
    private val pages: List<String>,
    private val textColor: Int,
    private val backgroundColor: Int,
    private val paddingPx: Int,
    private val textSizeSp: Float,
    private val lineSpacingPx: Float,
    private val typeface: Typeface,
    private val highlights: List<Highlight>,
    private val pageStarts: List<Int>,
    private val onTap: (Float, Float) -> Unit
) : RecyclerView.Adapter<TextPageAdapter.PageVH>() {

    override fun getItemCount(): Int = pages.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageVH {
        val text = TextView(parent.context).apply {
            layoutParams = ViewGroup.LayoutParams(-1, -1)
            setTextIsSelectable(true)
            gravity = android.view.Gravity.TOP or android.view.Gravity.START
        }
        return PageVH(text)
    }

    override fun onBindViewHolder(holder: PageVH, position: Int) {
        val pageStart = pageStarts[position]
        val pageEnd = pageStart + pages[position].length
        val pageText = SpannableString(pages[position])
        highlights.filter { it.endOffset > pageStart && it.startOffset < pageEnd }.forEach { h ->
            pageText.setSpan(
                BackgroundColorSpan(Color.parseColor("#665BA4FF")),
                (h.startOffset - pageStart).coerceAtLeast(0),
                (h.endOffset - pageStart).coerceAtMost(pageText.length),
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        holder.text.apply {
            setText(pageText)
            setTextColor(textColor)
            setBackgroundColor(backgroundColor)
            setPadding(paddingPx, paddingPx, paddingPx, paddingPx)
            textSize = textSizeSp
            this.typeface = typeface
            setLineSpacing(lineSpacingPx, 1f)
            setOnTouchListener { view, event ->
                if (event.action == android.view.MotionEvent.ACTION_UP) {
                    onTap(event.x / view.width.coerceAtLeast(1), event.y / view.height.coerceAtLeast(1))
                }
                false
            }
        }
    }

    class PageVH(val text: TextView) : RecyclerView.ViewHolder(text)
}