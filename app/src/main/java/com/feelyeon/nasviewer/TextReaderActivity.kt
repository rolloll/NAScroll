package com.feelyeon.nasviewer

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.text.Layout
import android.text.SpannableString
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.text.style.BackgroundColorSpan
import android.util.TypedValue
import android.view.ActionMode
import android.view.GestureDetector
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.widget.ViewPager2
import com.feelyeon.nasviewer.databinding.ActivityTextReaderBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.charset.Charset
import java.text.SimpleDateFormat
import java.util.Locale

class TextReaderActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_PATH = "extra_path"
        private const val EXTRA_NAME = "extra_name"
        private const val EXTRA_SIZE = "extra_size"
        private const val EXTRA_MTIME = "extra_mtime"
        private const val HIGHLIGHT_MENU_ID = 1001
        private const val MIN_FONT_SP = 12
        private const val MAX_FONT_SP = 28
        private val ZOOM_LEVELS = (MIN_FONT_SP..MAX_FONT_SP step 2).toList()
        // Starting sample size for page splitting (see buildTextPages) — comfortably more
        // than one screen's worth of text for any normal font size, so the doubling loop
        // rarely needs more than one retry.
        private const val INITIAL_PAGE_CHUNK_CHARS = 4000

        fun start(context: Context, path: String, name: String, size: Long = -1, mtime: Long = -1) {
            val intent = Intent(context, TextReaderActivity::class.java)
            intent.putExtra(EXTRA_PATH, path)
            intent.putExtra(EXTRA_NAME, name)
            intent.putExtra(EXTRA_SIZE, size)
            intent.putExtra(EXTRA_MTIME, mtime)
            context.startActivity(intent)
        }
    }

    private lateinit var binding: ActivityTextReaderBinding
    private lateinit var filePath: String
    private lateinit var db: AnnotationDb
    private var nasSize = -1L
    private var nasMtime = -1L
    private var fullText: String = ""
    private var barsVisible = true
    private var userIsSeeking = false
    private var pageMode = false
    private var textPages: List<String> = emptyList()
    private var pageStarts: List<Int> = emptyList()
    private var pageAdapter: TextPageAdapter? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTextReaderBinding.inflate(layoutInflater)
        setContentView(binding.root)

        filePath = intent.getStringExtra(EXTRA_PATH) ?: ""
        val name = intent.getStringExtra(EXTRA_NAME) ?: filePath
        nasSize = intent.getLongExtra(EXTRA_SIZE, -1)
        nasMtime = intent.getLongExtra(EXTRA_MTIME, -1)
        db = AnnotationDb.get(this)

        binding.titleText.text = name
        binding.backBtn.setOnClickListener { finish() }
        binding.bookmarkBtn.setOnClickListener { showReadingNotesDialog() }
        binding.readerSettingBtn.setOnClickListener {
            ReaderAppearanceSettings.show(
                context = this,
                zoomLevels = ZOOM_LEVELS,
                currentZoom = { Prefs.textFontSizeSp(this) },
                onZoomChange = { value -> adjustFontSize(value - Prefs.textFontSizeSp(this)) },
                showReadingMode = true
            ) { applyReaderAppearance(); configureReadingMode() }
        }
        binding.viewerSettingBtn.setOnClickListener {
            ReaderAppearanceSettings.showTouchSettings(this) {
                // The viewer setting is the navigation choice for TXT as well: scroll keeps
                // the continuous ScrollView, while either page-edge option uses fixed pages.
                Prefs.setTextPagedMode(this, Prefs.tapZonePaging(this))
                applyReaderAppearance()
                configureReadingMode()
            }
        }
        binding.contentText.textSize = Prefs.textFontSizeSp(this).toFloat()
        binding.fontDownBtn.setOnClickListener { adjustFontSize(-2) }
        binding.fontUpBtn.setOnClickListener { adjustFontSize(2) }
        binding.pagePager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                if (position in pageStarts.indices) {
                    updatePageProgress(position)
                }
            }
        })
        applyReaderAppearance()
        pageMode = Prefs.textPagedMode(this)

        setupTapToggle()
        setupScrollSync()
        setupHighlightSelection()
        load()
    }

    private fun applyReaderAppearance() {
        val appearance = ReaderAppearanceSettings.current(this)
        val margin = (appearance.marginDp * resources.displayMetrics.density).toInt()
        binding.root.setBackgroundColor(Color.parseColor(appearance.backgroundColor))
        binding.contentText.setTextColor(Color.parseColor(appearance.textColor))
        binding.contentText.setPadding(margin, margin, margin, margin)
        binding.contentText.typeface = fontFamilyToTypeface(appearance.fontFamily)
        binding.contentText.setLineSpacing(
            appearance.lineSpacingDp * resources.displayMetrics.density,
            1f
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            binding.contentText.justificationMode = if (appearance.justify) {
                Layout.JUSTIFICATION_MODE_INTER_WORD
            } else {
                Layout.JUSTIFICATION_MODE_NONE
            }
        }
        binding.statusMsg.setTextColor(Color.parseColor(appearance.textColor))
        val attrs = window.attributes
        attrs.screenBrightness = appearance.brightness
        window.attributes = attrs
        if (fullText.isNotEmpty()) renderText()
    }

    // TXT files have no <p> markup, so there is no reliable "paragraph" boundary distinct
    // from line spacing here — unlike EpubReaderActivity, paragraph-spacing is not offered.
    private fun fontFamilyToTypeface(family: String): Typeface = when (family) {
        "serif" -> Typeface.SERIF
        "monospace" -> Typeface.MONOSPACE
        else -> Typeface.SANS_SERIF
    }
    private fun load() {
        binding.statusMsg.text = "불러오는 중..."
        binding.statusMsg.visibility = View.VISIBLE
        binding.contentText.visibility = View.GONE
        lifecycleScope.launch {
            try {
                // Streams to a cached file instead of SynologyApi.downloadBytes(), which
                // would hold the full HTTP response and its ByteArray copy in memory at
                // once (on top of the fullText String built from it just below). Also
                // means re-opening the same file skips the redownload while it's fresh.
                val key = "txt_" + CacheStaleness.keyFor(filePath)
                val cachedFile = File(cacheDir, "$key.txt")
                val metaFile = File(cacheDir, "$key.meta")
                if (!cachedFile.exists() || !CacheStaleness.isFresh(metaFile, nasSize, nasMtime)) {
                    SynologyApi.downloadToFile(this@TextReaderActivity, filePath, cachedFile)
                    CacheStaleness.writeMeta(metaFile, nasSize, nasMtime)
                }
                val bytes = withContext(Dispatchers.IO) { cachedFile.readBytes() }
                fullText = decodeText(bytes)
                binding.statusMsg.visibility = View.GONE
                binding.contentText.visibility = View.VISIBLE
                renderText()
                binding.pagePager.post { configureReadingMode(); restorePosition() }
            } catch (e: Exception) {
                binding.statusMsg.text = e.message ?: "파일을 불러오지 못했습니다."
                binding.statusMsg.visibility = View.VISIBLE
                binding.contentText.visibility = View.GONE
            }
        }
    }

    // Korean .txt files are sometimes legacy EUC-KR/CP949 rather than UTF-8. UTF-8
    // decoding of non-UTF-8 bytes reliably produces U+FFFD replacement characters,
    // which is used here as the signal to retry with EUC-KR.
    private fun decodeText(bytes: ByteArray): String {
        val utf8 = String(bytes, Charsets.UTF_8)
        return if (utf8.contains('�')) {
            try {
                String(bytes, Charset.forName("EUC-KR"))
            } catch (e: Exception) {
                utf8
            }
        } else {
            utf8
        }
    }

    private fun renderText() {
        val spannable = SpannableString(fullText)
        if (Prefs.highlightsVisible(this)) {
            for (h in db.highlightsFor(filePath)) {
                if (h.startOffset in 0..fullText.length && h.endOffset in h.startOffset..fullText.length) {
                    spannable.setSpan(
                        BackgroundColorSpan(Color.parseColor("#665BA4FF")),
                        h.startOffset, h.endOffset,
                        android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }
            }
        }
        binding.contentText.text = spannable
    }

    // Listener goes on contentText, not scrollView: a plain tap never reaches the
    // ScrollView's own touch handling because the selectable TextView child always
    // consumes ACTION_DOWN first (it has to, in case the user is starting a text
    // selection) — the parent's OnTouchListener would simply never fire.
    private fun configureReadingMode() {
        val requested = Prefs.textPagedMode(this)
        pageMode = requested
        binding.scrollView.visibility = if (requested) View.GONE else View.VISIBLE
        binding.pagePager.visibility = if (requested) View.VISIBLE else View.GONE
        if (!requested || fullText.isEmpty()) return
        if (binding.pagePager.width <= 0 || binding.pagePager.height <= 0) {
            binding.pagePager.post { configureReadingMode() }
            return
        }

        val anchorOffset = if (pageMode && pageStarts.isNotEmpty()) {
            pageStarts[binding.pagePager.currentItem.coerceIn(0, pageStarts.lastIndex)]
        } else {
            currentCharOffset()
        }
        buildTextPages()
        val appearance = ReaderAppearanceSettings.current(this)
        val density = resources.displayMetrics.density
        val textColor = Color.parseColor(appearance.textColor)
        val backgroundColor = Color.parseColor(appearance.backgroundColor)
        val pagesSnapshot = textPages
        val pageStartsSnapshot = pageStarts
        lifecycleScope.launch {
            val highlights = if (appearance.highlightsVisible) {
                withContext(Dispatchers.IO) { db.highlightsFor(filePath) }
            } else {
                emptyList()
            }
            pageAdapter = TextPageAdapter(
                pages = pagesSnapshot,
                textColor = textColor,
                backgroundColor = backgroundColor,
                paddingPx = (appearance.marginDp * density).toInt(),
                textSizeSp = Prefs.textFontSizeSp(this@TextReaderActivity).toFloat(),
                lineSpacingPx = appearance.lineSpacingDp * density,
                typeface = fontFamilyToTypeface(appearance.fontFamily),
                highlights = highlights,
                pageStarts = pageStartsSnapshot,
                onTap = { xFraction, yFraction ->
                    val action = TapZone.resolve(
                        Prefs.tapZonePaging(this@TextReaderActivity),
                        Prefs.tapZoneVertical(this@TextReaderActivity),
                        xFraction,
                        yFraction
                    )
                    when (action) {
                        TapZone.Action.BACKWARD -> pageBy(-1)
                        TapZone.Action.FORWARD -> pageBy(1)
                        TapZone.Action.TOGGLE -> toggleBars()
                    }
                }
            )
            binding.pagePager.adapter = pageAdapter
            val target = pageStartsSnapshot.indexOfLast { it <= anchorOffset }.coerceAtLeast(0)
            binding.pagePager.setCurrentItem(target, false)
            updatePageProgress(target)
        }
    }

    private fun buildTextPages() {
        val appearance = ReaderAppearanceSettings.current(this)
        val density = resources.displayMetrics.density
        val width = (binding.pagePager.width - appearance.marginDp * density * 2).toInt().coerceAtLeast(1)
        val chromeHeight = binding.readerTopBar.height + binding.readerBottomBar.height
        val height = (binding.pagePager.height - chromeHeight - appearance.marginDp * density * 2).toInt().coerceAtLeast(1)
        val paint = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
            // sp->px must use scaledDensity (honors the user's accessibility font-scale
            // setting), not density — the actual page TextView's `textSize = spValue`
            // resolves through scaledDensity, so using plain density here would size the
            // measurement layout differently from what's rendered whenever font-scale != 1,
            // letting the real (larger) text overflow the page and clip its last line.
            textSize = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP,
                Prefs.textFontSizeSp(this@TextReaderActivity).toFloat(),
                resources.displayMetrics
            )
            typeface = binding.contentText.typeface
        }
        val pagesOut = mutableListOf<String>()
        val startsOut = mutableListOf<Int>()
        var offset = 0
        // Lay out a bounded chunk starting at `offset` (not the whole remaining suffix) —
        // StaticLayout.Builder.obtain(fullText, start, end, ...) breaks lines over just that
        // range while still reporting absolute offsets into fullText, so no substring copy of
        // the shrinking remainder is needed. Without this, each iteration re-copied and
        // re-line-broke the entire remaining text just to find where one page's worth ends,
        // which is O(n^2) over a long document.
        while (offset < fullText.length) {
            var chunkChars = INITIAL_PAGE_CHUNK_CHARS
            var layout: StaticLayout
            var endLine: Int
            while (true) {
                val chunkEnd = (offset + chunkChars).coerceAtMost(fullText.length)
                layout = StaticLayout.Builder.obtain(fullText, offset, chunkEnd, paint, width)
                    .setLineSpacing(appearance.lineSpacingDp * density, 1f)
                    .setIncludePad(true)
                    .build()
                val wholeChunkFits = layout.getLineBottom(layout.lineCount - 1) <= height
                if (!wholeChunkFits || chunkEnd == fullText.length) {
                    endLine = layout.lineCount - 1
                    while (endLine > 0 && layout.getLineBottom(endLine) > height) endLine--
                    break
                }
                // The whole sampled chunk fit within one page and there's more text after it —
                // sample a bigger chunk so the real page break (further out) can be found.
                chunkChars *= 2
            }
            val length = if (endLine == 0 && layout.getLineBottom(0) > height) {
                1
            } else {
                (layout.getLineEnd(endLine) - offset).coerceAtLeast(1)
            }
            val pageEnd = (offset + length).coerceAtMost(fullText.length)
            pagesOut.add(fullText.substring(offset, pageEnd))
            startsOut.add(offset)
            offset = pageEnd
        }
        textPages = if (pagesOut.isEmpty()) listOf("") else pagesOut
        pageStarts = if (startsOut.isEmpty()) listOf(0) else startsOut
    }

    private fun updatePageProgress(position: Int) {
        val fraction = if (textPages.size <= 1) 0.0 else position.toDouble() / (textPages.size - 1)
        binding.seekBar.progress = (fraction * 1000).toInt().coerceIn(0, 1000)
        binding.progressLabel.text = "${position + 1} / ${textPages.size}"
    }
    private fun setupTapToggle() {
        val gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                val width = binding.contentText.width
                val height = binding.contentText.height
                if (width <= 0 || height <= 0) {
                    toggleBars()
                    return true
                }
                val action = TapZone.resolve(
                    Prefs.tapZonePaging(this@TextReaderActivity),
                    Prefs.tapZoneVertical(this@TextReaderActivity),
                    e.x / width,
                    e.y / height
                )
                when (action) {
                    TapZone.Action.BACKWARD -> pageBy(-1)
                    TapZone.Action.FORWARD -> pageBy(1)
                    TapZone.Action.TOGGLE -> toggleBars()
                }
                return true
            }
        })
        binding.contentText.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            false
        }
    }

    private fun pageBy(direction: Int) {
        if (pageMode) {
            val target = (binding.pagePager.currentItem + direction).coerceIn(0, textPages.lastIndex.coerceAtLeast(0))
            binding.pagePager.setCurrentItem(target, true)
        } else {
            val delta = (binding.scrollView.height * 0.85).toInt() * direction
            binding.scrollView.smoothScrollBy(0, delta)
        }
    }

    private fun toggleBars() {
        barsVisible = !barsVisible
        val v = if (barsVisible) View.VISIBLE else View.GONE
        binding.readerTopBar.visibility = v
        binding.readerBottomBar.visibility = v
    }

    private fun setupScrollSync() {
        binding.scrollView.viewTreeObserver.addOnScrollChangedListener {
            if (!userIsSeeking) updateProgressUi()
        }
        binding.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser && fullText.isNotEmpty()) {
                    val target = (fullText.length * (progress / 1000.0)).toInt()
                    if (pageMode) {
                        val page = pageStarts.indexOfLast { it <= target }.coerceAtLeast(0)
                        binding.pagePager.setCurrentItem(page, false)
                        updatePageProgress(page)
                    } else {
                        scrollToCharOffset(target)
                        binding.progressLabel.text = "${progress / 10}%"
                    }
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
        if (fullText.isEmpty()) return
        if (pageMode) {
            updatePageProgress(binding.pagePager.currentItem)
            return
        }
        val fraction = currentCharOffset().toDouble() / fullText.length
        binding.seekBar.progress = (fraction * 1000).toInt().coerceIn(0, 1000)
        binding.progressLabel.text = "${(fraction * 100).toInt()}%"
    }

    private fun adjustFontSize(delta: Int) {
        val current = Prefs.textFontSizeSp(this)
        val next = (current + delta).coerceIn(MIN_FONT_SP, MAX_FONT_SP)
        if (next == current) return
        val anchorOffset = currentCharOffset()
        Prefs.setTextFontSizeSp(this, next)
        binding.contentText.textSize = next.toFloat()
        if (pageMode) {
            binding.pagePager.post { configureReadingMode(); scrollToCharOffset(anchorOffset) }
        } else {
            binding.contentText.post { scrollToCharOffset(anchorOffset) }
        }
    }

    private fun setupHighlightSelection() {
        binding.contentText.setTextIsSelectable(true)
        binding.contentText.customSelectionActionModeCallback = object : ActionMode.Callback {
            override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
                menu.add(0, HIGHLIGHT_MENU_ID, 0, "하이라이트")
                return true
            }

            override fun onPrepareActionMode(mode: ActionMode, menu: Menu) = false

            override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
                if (item.itemId == HIGHLIGHT_MENU_ID) {
                    applyHighlightFromSelection()
                    mode.finish()
                    return true
                }
                return false
            }

            override fun onDestroyActionMode(mode: ActionMode) {}
        }
    }

    private fun applyHighlightFromSelection() {
        val start = binding.contentText.selectionStart
        val end = binding.contentText.selectionEnd
        if (start < 0 || end <= start) return
        val snippet = fullText.substring(start, end).take(40)
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { db.addHighlight(filePath, start, end, snippet) }
            renderText()
            Toast.makeText(this@TextReaderActivity, "하이라이트가 추가되었습니다.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun currentCharOffset(): Int {
        val layout = binding.contentText.layout ?: return 0
        val scrollY = binding.scrollView.scrollY
        val line = layout.getLineForVertical(scrollY)
        return layout.getLineStart(line)
    }

    private fun scrollToCharOffset(offset: Int) {
        binding.contentText.post {
            val layout = binding.contentText.layout ?: return@post
            val clamped = offset.coerceIn(0, fullText.length)
            val line = layout.getLineForOffset(clamped)
            val y = layout.getLineTop(line) + binding.contentText.paddingTop
            binding.scrollView.scrollTo(0, y)
        }
    }

    private fun restorePosition() {
        val saved = db.getProgress(filePath)?.toIntOrNull()
        if (saved != null) {
            if (pageMode && pageStarts.isNotEmpty()) {
                val page = pageStarts.indexOfLast { it <= saved }.coerceAtLeast(0)
                binding.pagePager.setCurrentItem(page, false)
            } else {
                scrollToCharOffset(saved)
            }
        }
        binding.contentText.post { updateProgressUi() }
    }

    override fun onPause() {
        super.onPause()
        if (fullText.isNotEmpty()) {
            val position = if (pageMode && pageStarts.isNotEmpty()) {
                pageStarts[binding.pagePager.currentItem]
            } else {
                currentCharOffset()
            }
            db.saveProgress(filePath, position.toString())
        }
    }

    // Combines bookmarks and highlights into one list (Ridibooks' "독서노트"), sorted by
    // where they fall in the text, so it doubles as a mini table of contents for jumping.
    private fun showReadingNotesDialog() {
        val dateFmt = SimpleDateFormat("MM/dd HH:mm", Locale.getDefault())
        data class Note(val offset: Int, val label: String, val jump: () -> Unit)

        lifecycleScope.launch {
            val (bookmarks, highlights) = withContext(Dispatchers.IO) {
                db.bookmarksFor(filePath) to db.highlightsFor(filePath)
            }
            val notes = (bookmarks.map { bm ->
                Note(bm.position.toIntOrNull() ?: 0, "🔖 ${bm.label}  (${dateFmt.format(bm.createdAt)})") {
                    scrollToCharOffset(bm.position.toIntOrNull() ?: 0)
                }
            } + highlights.map { hl ->
                Note(hl.startOffset, "🖍 ${hl.snippet}") { scrollToCharOffset(hl.startOffset) }
            }).sortedBy { it.offset }

            val labels = mutableListOf("+ 현재 위치에 책갈피 추가")
            labels.addAll(notes.map { it.label })

            AlertDialog.Builder(this@TextReaderActivity)
                .setTitle("독서노트")
                .setItems(labels.toTypedArray()) { _, index ->
                    if (index == 0) addBookmarkAtCurrentPosition() else notes[index - 1].jump()
                }
                .setNegativeButton("닫기", null)
                .show()
        }
    }

    private fun addBookmarkAtCurrentPosition() {
        val offset = currentCharOffset()
        val label = fullText.substring(offset, (offset + 20).coerceAtMost(fullText.length))
            .replace("\n", " ").trim().ifBlank { "책갈피" }
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { db.addBookmark(filePath, offset.toString(), label) }
            Toast.makeText(this@TextReaderActivity, "책갈피가 추가되었습니다.", Toast.LENGTH_SHORT).show()
        }
    }
}
