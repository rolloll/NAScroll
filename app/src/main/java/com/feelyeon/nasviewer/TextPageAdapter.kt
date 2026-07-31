package com.feelyeon.nasviewer

import android.graphics.Color
import android.graphics.Typeface
import android.text.SpannableString
import android.text.style.BackgroundColorSpan
import android.view.LayoutInflater
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
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
    private val onHighlightSelection: (Int, Int) -> Unit,
    private val onTap: (Float, Float) -> Unit
) : RecyclerView.Adapter<TextPageAdapter.PageVH>() {

    companion object {
        private const val HIGHLIGHT_MENU_ID = 1001
    }

    override fun getItemCount(): Int = pages.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageVH {
        val text = TextView(parent.context).apply {
            layoutParams = ViewGroup.LayoutParams(-1, -1)
            setTextIsSelectable(true)
            customSelectionActionModeCallback = object : ActionMode.Callback {
                override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
                    menu.add(0, HIGHLIGHT_MENU_ID, 0, "하이라이트")
                    return true
                }

                override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean = false

                override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
                    if (item.itemId != HIGHLIGHT_MENU_ID) return false
                    val start = selectionStart
                    val end = selectionEnd
                    if (start >= 0 && end > start) onHighlightSelection(start, end)
                    mode.finish()
                    return true
                }

                override fun onDestroyActionMode(mode: ActionMode) = Unit
            }
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
