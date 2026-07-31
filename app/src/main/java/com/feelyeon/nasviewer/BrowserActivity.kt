package com.feelyeon.nasviewer

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.feelyeon.nasviewer.databinding.ActivityBrowserBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class BrowserActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_PATH = "extra_path"
        private const val SPAN_COUNT = 3
    }

    private lateinit var binding: ActivityBrowserBinding
    private lateinit var adapter: BrowserAdapter
    private var currentPath: String = "/"
    private var currentItems: List<FileItem> = emptyList()
    private var loadJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBrowserBinding.inflate(layoutInflater)
        setContentView(binding.root)

        currentPath = intent.getStringExtra(EXTRA_PATH) ?: "/"
        Prefs.setLastPath(this, currentPath)
        applyUiTextScale()

        adapter = BrowserAdapter(
            context = this,
            onFolderClick = { item -> openFolder(item.path) },
            onDocClick = { item -> openDoc(item) },
            onReadAllClick = { openReaderFromImages(0) },
            onThumbClick = { index -> openReaderFromImages(index) }
        )

        val layoutManager = GridLayoutManager(this, SPAN_COUNT)
        layoutManager.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int {
                return if (adapter.getItemViewType(position) == BrowserAdapter.TYPE_THUMB) 1 else SPAN_COUNT
            }
        }
        binding.recyclerView.layoutManager = layoutManager
        binding.recyclerView.adapter = adapter

        binding.settingsBtn.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        binding.refreshBtn.setOnClickListener { load(currentPath) }
        binding.swipeRefresh.setOnRefreshListener { load(currentPath) }
        binding.readingNotesTab.setOnClickListener {
            startActivity(Intent(this, ReadingNotesActivity::class.java))
        }

        renderBreadcrumb()
        load(currentPath)
    }

    override fun onResume() {
        super.onResume()
        applyUiTextScale()
        renderBreadcrumb()
        if (!Prefs.hasAccount(this)) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
        }
    }

    private fun applyUiTextScale() {
        val scale = Prefs.uiTextScale(this)
        binding.titleText.textSize = 15f * scale
        binding.filesTab.textSize = 14f * scale
        binding.readingNotesTab.textSize = 14f * scale
        binding.statusMsg.textSize = 14f * scale
    }
    private fun openFolder(path: String) {
        val intent = Intent(this, BrowserActivity::class.java)
        intent.putExtra(EXTRA_PATH, path)
        startActivity(intent)
    }

    private fun openReaderFromImages(startIndex: Int) {
        val images = currentItems.filter { it.isImage() }.sortedWith(compareBy(NaturalOrder) { it.name })
        if (images.isEmpty()) return
        ReaderActivity.start(this, images, startIndex, currentPath)
    }

    private fun openDoc(item: FileItem) {
        when {
            item.isEpub() -> EpubReaderActivity.start(this, item.path, item.name, item.size, item.mtime)
            item.isPdf() -> PdfReaderActivity.start(this, item.path, item.name, item.size, item.mtime)
            else -> TextReaderActivity.start(this, item.path, item.name, item.size, item.mtime)
        }
    }

    // Full path from the actual NAS root ("/" = the shares list) is always shown —
    // there is no longer a fixed starting share, so every segment needs to be
    // tappable to let the user navigate back out to any ancestor folder or the
    // shares list itself. binding.breadcrumb is now a vertical container: crumbs are
    // packed into horizontal "row" LinearLayouts added on demand, wrapping to a new
    // row whenever the next crumb wouldn't fit — deep paths grow downward instead of
    // running off the right edge of the screen.
    private fun renderBreadcrumb() {
        binding.breadcrumb.removeAllViews()
        val segs = currentPath.trim('/').split("/").filter { it.isNotBlank() }
        val horizontalPadding = binding.breadcrumb.paddingStart + binding.breadcrumb.paddingEnd
        val availableWidth = resources.displayMetrics.widthPixels - horizontalPadding

        var row = newBreadcrumbRow()
        binding.breadcrumb.addView(row)
        var rowWidth = 0

        fun addToRow(view: View) {
            view.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
            val width = view.measuredWidth
            if (rowWidth + width > availableWidth && row.childCount > 0) {
                row = newBreadcrumbRow()
                binding.breadcrumb.addView(row)
                rowWidth = 0
            }
            row.addView(view)
            rowWidth += width
        }

        addToRow(makeCrumb("🏠", "/", isCurrent = segs.isEmpty()))
        var acc = ""
        segs.forEachIndexed { i, seg ->
            acc += "/$seg"
            addToRow(makeCrumbSeparator())
            addToRow(makeCrumb(seg, acc, isCurrent = i == segs.lastIndex))
        }
    }

    private fun newBreadcrumbRow(): LinearLayout {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = android.view.Gravity.CENTER_VERTICAL
        return row
    }

    private fun makeCrumbSeparator(): TextView {
        val tv = TextView(this)
        tv.text = "/"
        tv.textSize = 15f * Prefs.uiTextScale(this)
        tv.setPadding(6, 0, 6, 0)
        tv.setTextColor(ContextCompat.getColor(this, R.color.text_muted))
        return tv
    }

    private fun makeCrumb(label: String, path: String, isCurrent: Boolean): TextView {
        val tv = TextView(this)
        tv.text = label
        tv.textSize = 16f * Prefs.uiTextScale(this)
        tv.setPadding(6, 6, 6, 6)
        tv.setTextColor(ContextCompat.getColor(this, if (isCurrent) R.color.text_muted else R.color.accent2))
        if (!isCurrent) {
            tv.isClickable = true
            tv.isFocusable = true
            tv.setOnClickListener { openFolder(path) }
        }
        return tv
    }

    private fun load(path: String) {
        binding.statusMsg.visibility = View.GONE
        // Refresh button + pull-to-refresh + the initial load() all hit this with the same
        // path — tapping refresh again before the first request lands used to leave both
        // coroutines in flight with no ordering guarantee, so a slower first response could
        // land after and overwrite the newer one. Cancelling any prior in-flight load first
        // means only the most recently triggered request can ever update the screen.
        loadJob?.cancel()
        loadJob = lifecycleScope.launch {
            try {
                val items = SynologyApi.listFolder(this@BrowserActivity, path)
                currentItems = items
                binding.swipeRefresh.isRefreshing = false
                renderItems(items)
            } catch (e: Exception) {
                binding.swipeRefresh.isRefreshing = false
                binding.statusMsg.text = e.message ?: "오류가 발생했습니다."
                binding.statusMsg.visibility = View.VISIBLE
                adapter.submit(emptyList())
            }
        }
    }

    private fun renderItems(items: List<FileItem>) {
        val folders = items.filter { it.isDir }.sortedWith(compareBy(NaturalOrder) { it.name })
        val docs = items.filter { it.isEpub() || it.isTextDoc() || it.isPdf() }.sortedWith(compareBy(NaturalOrder) { it.name })
        val images = items.filter { it.isImage() }.sortedWith(compareBy(NaturalOrder) { it.name })

        if (folders.isEmpty() && docs.isEmpty() && images.isEmpty()) {
            binding.statusMsg.text = "이 폴더는 비어있거나 표시할 파일/폴더가 없습니다."
            binding.statusMsg.visibility = View.VISIBLE
            adapter.submit(emptyList())
            return
        }
        binding.statusMsg.visibility = View.GONE

        val rows = mutableListOf<BrowserRow>()
        if (images.isNotEmpty()) rows.add(BrowserRow.ReadAll(images.size))
        folders.forEach { rows.add(BrowserRow.Folder(it)) }
        docs.forEach { rows.add(BrowserRow.Doc(it)) }
        images.forEachIndexed { index, item -> rows.add(BrowserRow.Thumb(item, index)) }
        adapter.submit(rows)
    }
}
