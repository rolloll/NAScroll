package com.feelyeon.nasviewer

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.pdf.PdfRenderer
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.view.View
import android.widget.SeekBar
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.feelyeon.nasviewer.databinding.ActivityPdfReaderBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

class PdfReaderActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_PATH = "extra_path"
        private const val EXTRA_NAME = "extra_name"
        private const val EXTRA_SIZE = "extra_size"
        private const val EXTRA_MTIME = "extra_mtime"
        // Ridibooks-style tap zones: left third = previous page, right third = next page,
        // middle third = toggle the bars.
        private const val TAP_ZONE_EDGE = 0.3f

        fun start(context: Context, path: String, name: String, size: Long = -1, mtime: Long = -1) {
            val intent = Intent(context, PdfReaderActivity::class.java)
            intent.putExtra(EXTRA_PATH, path)
            intent.putExtra(EXTRA_NAME, name)
            intent.putExtra(EXTRA_SIZE, size)
            intent.putExtra(EXTRA_MTIME, mtime)
            context.startActivity(intent)
        }
    }

    private lateinit var binding: ActivityPdfReaderBinding
    private lateinit var filePath: String
    private lateinit var db: AnnotationDb
    private var nasSize = -1L
    private var nasMtime = -1L
    private var pfd: ParcelFileDescriptor? = null
    private var renderer: PdfRenderer? = null
    private var barsVisible = true
    private var userIsSeeking = false
    // false = 가로 넘김 (ViewPager2, one full page per swipe/tap-zone — the original behavior);
    // true = 세로 스크롤 (continuous vertical RecyclerView, pages stacked back-to-back like the
    // webtoon reader). Both share the same PdfPageAdapter/bitmap cache — only the hosting
    // widget and how "current page" is read/set differ, via currentPage()/goToPage() below.
    private var continuousMode = false
    private var scrollLayoutManager: LinearLayoutManager? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPdfReaderBinding.inflate(layoutInflater)
        setContentView(binding.root)

        filePath = intent.getStringExtra(EXTRA_PATH) ?: ""
        val name = intent.getStringExtra(EXTRA_NAME) ?: filePath
        nasSize = intent.getLongExtra(EXTRA_SIZE, -1)
        nasMtime = intent.getLongExtra(EXTRA_MTIME, -1)
        db = AnnotationDb.get(this)

        binding.titleText.text = name
        binding.backBtn.setOnClickListener { finish() }
        binding.bookmarkBtn.setOnClickListener { showBookmarkDialog() }
        binding.scrollModeBtn.setOnClickListener { showScrollModeDialog() }

        // Registered once, up front, on both (persistent) widgets rather than re-registering
        // every time the adapter is swapped in switchMode() — re-registering on every toggle
        // would stack duplicate callbacks on whichever widget survives across toggles. Each
        // callback no-ops unless its own mode is the currently active one.
        binding.readerPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                if (continuousMode) return
                if (!userIsSeeking) updateProgressUi()
                updateBookmarkIcon()
            }
        })
        binding.readerScrollList.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                if (!continuousMode) return
                if (!userIsSeeking) updateProgressUi()
                updateBookmarkIcon()
            }
        })

        setupSeekBar()
        load()
    }

    private fun load() {
        binding.statusMsg.text = "불러오는 중..."
        binding.statusMsg.visibility = View.VISIBLE
        lifecycleScope.launch {
            try {
                val file = withContext(Dispatchers.IO) { downloadToCache() }
                val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                val pdfRenderer = PdfRenderer(descriptor)
                pfd = descriptor
                renderer = pdfRenderer

                continuousMode = Prefs.pdfContinuousScroll(this@PdfReaderActivity)
                val adapter = buildAdapter(pdfRenderer)
                binding.statusMsg.visibility = View.GONE

                val savedPage = withContext(Dispatchers.IO) { db.getProgress(filePath) }?.toIntOrNull() ?: 0
                val startPage = if (savedPage in 0 until pdfRenderer.pageCount) savedPage else 0
                setupReaderView(adapter, startPage)
            } catch (e: Exception) {
                binding.statusMsg.text = e.message ?: "PDF를 여는 데 실패했습니다."
                binding.statusMsg.visibility = View.VISIBLE
            }
        }
    }

    private suspend fun buildAdapter(pdfRenderer: PdfRenderer): PdfPageAdapter {
        val targetWidth = PdfPageAdapter.targetWidthFor(this@PdfReaderActivity)
        val pageHeights = if (continuousMode) {
            withContext(Dispatchers.IO) { PdfPageAdapter.computePageHeights(pdfRenderer, targetWidth) }
        } else null
        return PdfPageAdapter(
            context = this@PdfReaderActivity,
            scope = lifecycleScope,
            renderer = pdfRenderer,
            pageHeights = pageHeights,
            onPageTapped = { xFraction -> handleTapZone(xFraction) }
        )
    }

    private fun setupReaderView(adapter: PdfPageAdapter, restorePage: Int) {
        if (continuousMode) {
            binding.readerPager.visibility = View.GONE
            binding.readerScrollList.visibility = View.VISIBLE
            if (scrollLayoutManager == null) {
                scrollLayoutManager = LinearLayoutManager(this)
                binding.readerScrollList.layoutManager = scrollLayoutManager
            }
            binding.readerScrollList.adapter = adapter
            binding.readerScrollList.scrollToPosition(restorePage)
        } else {
            binding.readerScrollList.visibility = View.GONE
            binding.readerPager.visibility = View.VISIBLE
            binding.readerPager.adapter = adapter
            binding.readerPager.setCurrentItem(restorePage, false)
        }
        updateProgressUi()
        updateBookmarkIcon()
    }

    private fun showScrollModeDialog() {
        val options = listOf("가로 넘김 (페이지)", "세로 스크롤")
        AlertDialog.Builder(this)
            .setTitle("읽기 방식")
            .setSingleChoiceItems(options.toTypedArray(), if (continuousMode) 1 else 0) { dialog, which ->
                dialog.dismiss()
                switchMode(which == 1)
            }
            .show()
    }

    private fun switchMode(newContinuous: Boolean) {
        if (newContinuous == continuousMode) return
        val pdfRenderer = renderer ?: return
        val page = currentPage()
        Prefs.setPdfContinuousScroll(this, newContinuous)
        continuousMode = newContinuous
        lifecycleScope.launch {
            val adapter = buildAdapter(pdfRenderer)
            setupReaderView(adapter, page)
        }
    }

    private fun currentPage(): Int =
        if (continuousMode) (scrollLayoutManager?.findFirstVisibleItemPosition() ?: 0).coerceAtLeast(0)
        else binding.readerPager.currentItem

    private fun goToPage(page: Int, smooth: Boolean) {
        if (continuousMode) {
            if (smooth) binding.readerScrollList.smoothScrollToPosition(page)
            else binding.readerScrollList.scrollToPosition(page)
        } else {
            binding.readerPager.setCurrentItem(page, smooth)
        }
    }

    private suspend fun downloadToCache(): File {
        val key = "pdf_" + CacheStaleness.keyFor(filePath)
        val cached = File(cacheDir, "$key.pdf")
        val metaFile = File(cacheDir, "$key.meta")
        if (!cached.exists() || !CacheStaleness.isFresh(metaFile, nasSize, nasMtime)) {
            SynologyApi.downloadToFile(this@PdfReaderActivity, filePath, cached)
            CacheStaleness.writeMeta(metaFile, nasSize, nasMtime)
        }
        return cached
    }

    private fun handleTapZone(xFraction: Float) {
        val count = renderer?.pageCount ?: return
        // Left/right tap-zone page-turning is a horizontal-paging concept — in continuous
        // vertical scroll there's no "previous/next page" gesture distinct from just
        // scrolling, so every tap there simply toggles the bars (matches the EPUB/webtoon
        // continuous readers).
        if (continuousMode) {
            toggleBars()
            return
        }
        when {
            xFraction < TAP_ZONE_EDGE -> {
                val prev = currentPage() - 1
                if (prev >= 0) goToPage(prev, true)
            }
            xFraction > 1f - TAP_ZONE_EDGE -> {
                val next = currentPage() + 1
                if (next < count) goToPage(next, true)
            }
            else -> toggleBars()
        }
    }

    private fun toggleBars() {
        barsVisible = !barsVisible
        val v = if (barsVisible) View.VISIBLE else View.GONE
        binding.readerTopBar.visibility = v
        binding.readerBottomBar.visibility = v
    }

    private fun setupSeekBar() {
        binding.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                val count = renderer?.pageCount ?: return
                if (fromUser) {
                    val page = ((progress / 1000.0) * (count - 1)).toInt().coerceIn(0, count - 1)
                    goToPage(page, false)
                    binding.progressLabel.text = "${page + 1} / $count 페이지"
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {
                userIsSeeking = true
            }

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                userIsSeeking = false
            }
        })
    }

    private fun updateProgressUi() {
        val count = renderer?.pageCount ?: return
        if (count == 0) return
        val current = currentPage()
        binding.progressLabel.text = "${current + 1} / $count 페이지"
        binding.seekBar.progress = ((current.toDouble() / (count - 1).coerceAtLeast(1)) * 1000).toInt().coerceIn(0, 1000)
    }

    private fun updateBookmarkIcon() {
        val page = currentPage()
        lifecycleScope.launch {
            val bookmarked = withContext(Dispatchers.IO) {
                db.bookmarksFor(filePath).any { it.position == page.toString() }
            }
            if (page != currentPage()) return@launch // page moved on again while we were reading
            binding.bookmarkBtn.setTextColor(
                ContextCompat.getColor(this@PdfReaderActivity, if (bookmarked) R.color.accent2 else R.color.text_primary)
            )
        }
    }

    private fun showBookmarkDialog() {
        lifecycleScope.launch {
            val bookmarks = withContext(Dispatchers.IO) { db.bookmarksFor(filePath) }
            val dateFmt = SimpleDateFormat("MM/dd HH:mm", Locale.getDefault())
            val labels = mutableListOf("+ 현재 페이지에 책갈피 추가")
            bookmarks.forEach { labels.add("${it.label}  (${dateFmt.format(it.createdAt)})") }

            AlertDialog.Builder(this@PdfReaderActivity)
                .setTitle("책갈피")
                .setItems(labels.toTypedArray()) { _, index ->
                    if (index == 0) {
                        addBookmarkAtCurrentPage()
                    } else {
                        val bm = bookmarks[index - 1]
                        val page = bm.position.toIntOrNull() ?: 0
                        goToPage(page, false)
                    }
                }
                .setNegativeButton("닫기", null)
                .show()
        }
    }

    private fun addBookmarkAtCurrentPage() {
        val page = currentPage()
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { db.addBookmark(filePath, page.toString(), "페이지 ${page + 1}") }
            updateBookmarkIcon()
        }
    }

    override fun onPause() {
        super.onPause()
        if (renderer != null) {
            db.saveProgress(filePath, currentPage().toString())
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        renderer?.close()
        pfd?.close()
    }
}
