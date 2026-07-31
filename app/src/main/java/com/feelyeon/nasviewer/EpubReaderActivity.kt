package com.feelyeon.nasviewer

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.ColorUtils
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import com.feelyeon.nasviewer.databinding.ActivityEpubReaderBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

class EpubReaderActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_PATH = "extra_path"
        private const val EXTRA_NAME = "extra_name"
        private const val EXTRA_SIZE = "extra_size"
        private const val EXTRA_MTIME = "extra_mtime"
        private const val MIN_ZOOM = 80
        private const val MAX_ZOOM = 200
        private const val SCROLL_POLL_MS = 400L
        private val ZOOM_LEVELS = (MIN_ZOOM..MAX_ZOOM step 10).toList()

        fun start(context: Context, path: String, name: String, size: Long = -1, mtime: Long = -1) {
            val intent = Intent(context, EpubReaderActivity::class.java)
            intent.putExtra(EXTRA_PATH, path)
            intent.putExtra(EXTRA_NAME, name)
            intent.putExtra(EXTRA_SIZE, size)
            intent.putExtra(EXTRA_MTIME, mtime)
            context.startActivity(intent)
        }
    }

    private lateinit var binding: ActivityEpubReaderBinding
    private lateinit var filePath: String
    private lateinit var db: AnnotationDb
    private var nasSize = -1L
    private var nasMtime = -1L
    private var book: EpubBook? = null
    private var currentIndex = 0
    private var chapterPageStarts: IntArray = IntArray(0)
    private var totalPages = 0
    private var pendingScrollFraction: Double? = null
    private var barsVisible = true
    private var userIsSeeking = false
    private val pollHandler = Handler(Looper.getMainLooper())
    private var withinChapterFraction = 0.0

    private val pollRunnable = object : Runnable {
        override fun run() {
            if (!userIsSeeking) refreshScrollFraction()
            pollHandler.postDelayed(this, SCROLL_POLL_MS)
        }
    }

    // Highlights are stored per chapter file (not per whole book) since a DOM
    // position only makes sense within a single chapter's HTML.
    private fun highlightKey(chapterIndex: Int): String {
        val chapter = book?.chapters?.getOrNull(chapterIndex) ?: return filePath
        return "$filePath#${chapter.relativePath}"
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEpubReaderBinding.inflate(layoutInflater)
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
                currentZoom = { Prefs.epubTextZoomPct(this) },
                onZoomChange = { value -> adjustZoom(value - Prefs.epubTextZoomPct(this)) }
            ) { applyReaderAppearance() }
        }
        binding.viewerSettingBtn.setOnClickListener {
            ReaderAppearanceSettings.showTouchSettings(this) { applyReaderAppearance() }
        }
        binding.highlightApplyBtn.setOnClickListener { highlightCurrentSelection() }
        binding.highlightBtn.setOnClickListener { showHighlightsDialog() }
        binding.prevChBtn.setOnClickListener { goChapter(currentIndex - 1) }
        binding.nextChBtn.setOnClickListener { goChapter(currentIndex + 1) }
        binding.tocBtn.setOnClickListener { openToc() }
        binding.fontDownBtn.setOnClickListener { adjustZoom(-10) }
        binding.fontUpBtn.setOnClickListener { adjustZoom(10) }

        binding.webView.settings.javaScriptEnabled = true
        binding.webView.settings.allowFileAccess = true
        binding.webView.settings.textZoom = Prefs.epubTextZoomPct(this)
        binding.webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String?) {
                applyReaderAppearance()
                reapplyHighlights()
                pendingScrollFraction?.let { fraction ->
                    scrollToFractionSnapped(fraction)
                    pendingScrollFraction = null
                }
                binding.statusMsg.visibility = View.GONE
                updateProgressLabel()
            }
        }

        setupTapToggle()
        setupSeekBar()
        loadBook()
    }

    private fun applyReaderAppearance() {
        val appearance = ReaderAppearanceSettings.current(this)
        // CSS px inside the WebView already behaves like Android dp (no viewport scaling is
        // set up here), so these must NOT be multiplied by displayMetrics.density the way a
        // native View's pixel APIs would need — that multiplication (left over from copying
        // the same appearance values into TextReaderActivity, which does draw through native
        // Views) was inflating both well past their intended dp size on higher-density
        // devices, e.g. 6dp becoming 15 CSS px, ballooning line-height far past what the
        // appearance setting asked for. paragraphSpacingDp below was already applied raw,
        // without this multiplication — margin/spacing now match that.
        val margin = appearance.marginDp
        val spacing = appearance.lineSpacingDp
        val backgroundColor = Color.parseColor(appearance.backgroundColor)
        binding.root.setBackgroundColor(backgroundColor)
        binding.webView.setBackgroundColor(backgroundColor)
        binding.statusMsg.setTextColor(Color.parseColor(appearance.textColor))
        val attrs = window.attributes
        attrs.screenBrightness = appearance.brightness
        window.attributes = attrs
        // The WebView must never be visible through a transparent or contrast-enforced
        // gesture-navigation bar. Besides looking like a clipped line at the page bottom,
        // that made text outside the reader's usable viewport appear to belong to the page.
        window.statusBarColor = backgroundColor
        window.navigationBarColor = backgroundColor
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.navigationBarDividerColor = backgroundColor
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }
        val lightSystemBars = ColorUtils.calculateLuminance(backgroundColor) > 0.5
        WindowCompat.getInsetsController(window, binding.root).apply {
            isAppearanceLightStatusBars = lightSystemBars
            isAppearanceLightNavigationBars = lightSystemBars
        }
        val alignment = if (appearance.justify) "justify" else "left"
        val scrollEnabled = !Prefs.tapZonePaging(this)
        val highlightColor = if (appearance.highlightsVisible) "#5BA4FF" else "transparent"
        // Many of these EPUBs (this book included — verified against its extracted chapter
        // XHTML) write paragraph breaks as the author's own blank line, i.e. a spacer
        // <p><br/></p> between real paragraphs, rather than relying on CSS margins at all.
        // Forcing margin-bottom on every <p> — spacer included — stacks our margin on top of
        // that already-blank line only where a spacer happens to be present, while paragraph
        // pairs with no spacer get just our margin — the same book then reads with wildly
        // uneven gaps depending on which convention a given paragraph pair happens to use.
        // Zeroing the margin on spacer-only paragraphs (their own line-height already reads
        // as one blank line) leaves our margin as the sole, consistent gap between paragraphs
        // that don't have an explicit spacer, and lets the author's own spacer read as exactly
        // one blank line rather than a blank line plus our margin on top.
        //
        // line-height on `html,body` only ever set the line-height OF the html/body elements
        // themselves — it does not reach descendants that carry their own line-height rule,
        // because CSS inheritance only applies when nothing more specific already cascaded a
        // value onto that element. This EPUB's own stylesheet sets `p{line-height:1em}` (no
        // !important) directly on <p> — a rule that DOES match every paragraph, unlike our
        // `html,body` selector — so it wins outright, !important on the non-matching parent
        // rule notwithstanding. Every single-line paragraph looked fine regardless (line-height
        // only affects the gap between a block's OWN wrapped lines), but any paragraph long
        // enough to wrap read with the book's cramped 1em spacing between its own lines while
        // paragraph-to-paragraph gaps (margin-bottom, which the book never sets) used our
        // value — exactly the "some gaps huge, some tiny" pattern reported. `body *` matches
        // <p> directly too, so adding line-height there (with !important) now wins on equal
        // footing against the book's own rule. text-align has the identical exposure — this
        // same stylesheet also sets `p{text-align:justify}` directly — so the user's alignment
        // choice belongs in `body *` too, not just `html,body`.
        // padding was only ever set left/right — the WebView has no other top/bottom inset of
        // its own, so without an explicit padding-top the text started flush against the very
        // top edge while the sides carried a visible margin. Top/bottom read noticeably
        // tighter than most ebook readers even once symmetric, though — apps like Ridibooks
        // give the vertical edges more breathing room than the horizontal gutter, not the
        // same amount. verticalMargin (2x the side margin) matches that proportion while
        // staying tied to the user's margin setting rather than a hardcoded constant that
        // wouldn't track it. zeroing the default html/body margin (which some EPUB
        // stylesheets adjust in ways that only show up on some devices) keeps this exact.
        val verticalMargin = margin * 2
        val css = "html,body{background:${appearance.backgroundColor} !important;color:${appearance.textColor} !important;margin:0 !important;padding:${verticalMargin}px ${margin}px !important;line-height:calc(1.45em + ${spacing}px) !important;text-align:$alignment !important;font-family:${appearance.fontFamily} !important;touch-action:${if (scrollEnabled) "auto" else "none"} !important;}body *{color:inherit !important;font-family:inherit !important;line-height:calc(1.45em + ${spacing}px) !important;text-align:$alignment !important;}img{max-width:100% !important;height:auto !important;}p{margin:0 0 ${appearance.paragraphSpacingDp}px 0 !important;}p:empty,p:has(>br:only-child){margin:0 !important;}.nascope-hl{background-color:$highlightColor !important;}"
        val script = """
            (function(){
                var s = document.getElementById('nascope-reader-style');
                if (!s) { s = document.createElement('style'); s.id = 'nascope-reader-style'; document.head.appendChild(s); }
                s.textContent = ${JSONObject.quote(css)};
                // Reapplying appearance (font/margin/spacing/zoom, or a rotation) can change
                // where every line falls, so any cached boxes from before are no longer valid —
                // this runs on every applyReaderAppearance() call, which invalidates it here.
                window.__nascopeLineBoxesCache = null;
                // Top+bottom (document space) of every rendered line box — used to page-turn
                // and restore position without ever slicing a line across the viewport edge.
                // Bottom matters as much as top: a line's own box can be noticeably TALLER
                // than the line-to-line advance (this reader's line-height is intentionally
                // tighter than the font's natural ascent+descent box, so consecutive boxes
                // overlap by several px) — a page-turn algorithm that only looks at tops can
                // include a line whose top fits on this page but whose bottom actually spills
                // past the viewport edge, rendering with mangled batchim (a Korean syllable's
                // final consonant) or worse, sliced off entirely below the fold.
                //
                // Walking every text node's getClientRects() is O(chapter length) — cheap once,
                // but pageForward/pageBackward used to call this fresh on every single tap, so
                // a long chapter re-walked its entire DOM on every page turn. Cached here and
                // invalidated only by whatever can actually move a line: this function running
                // again (font/margin/spacing/zoom/rotation), or __nascopeInvalidateLineBoxes()
                // called explicitly after a native-side change the WebView itself can't signal
                // (textZoom, set outside this script). Scrolling alone never invalidates it.
                window.__nascopeScrollEnabled = ${if (scrollEnabled) "true" else "false"};
                window.__nascopeInvalidateLineBoxes = function() {
                    window.__nascopeLineBoxesCache = null;
                };
                window.__nascopeLineBoxes = function() {
                    if (window.__nascopeLineBoxesCache) return window.__nascopeLineBoxesCache;
                    var walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT, null, false);
                    var boxes = [];
                    var node;
                    while (node = walker.nextNode()) {
                        if (!node.nodeValue || !/\S/.test(node.nodeValue)) continue;
                        var range = document.createRange();
                        range.selectNodeContents(node);
                        var rects = range.getClientRects();
                        for (var i = 0; i < rects.length; i++) {
                            var r = rects[i];
                            // Whitespace-only fragments can have a height but no painted
                            // pixels. Treating those fragments as lines lets a page start on
                            // an empty indentation line and can make the following sentence
                            // appear to be skipped. Also, inline markup commonly returns more
                            // than one rect for the same visual line; merge those rects below.
                            if (r.height > 0 && r.width > 0) {
                                // Keep the WebView's fractional layout coordinates. Rounding
                                // the first line's top before scrolling can land it a fraction
                                // of a pixel inside the top mask, which makes that whole line
                                // disappear on some Android WebView versions.
                                boxes.push({top: r.top + window.scrollY, bottom: r.bottom + window.scrollY});
                            }
                        }
                    }
                    boxes.sort(function(a, b){ return a.top - b.top || a.bottom - b.bottom; });
                    var lines = [];
                    boxes.forEach(function(box) {
                        var last = lines[lines.length - 1];
                        // getClientRects() may differ by a fraction of a CSS pixel because of subpixel
                        // font metrics. Keep one entry per painted visual line and retain the
                        // largest bottom edge so a tall inline/ruby fragment is not clipped.
                        if (last && Math.abs(last.top - box.top) <= 1) {
                            last.bottom = Math.max(last.bottom, box.bottom);
                        } else {
                            lines.push({top: box.top, bottom: box.bottom});
                        }
                    });
                    window.__nascopeLineBoxesCache = lines;
                    return lines;
                };
                // The deliberate blank strip at each page edge, in px. Matches the CSS
                // padding-top/bottom (verticalMargin) so a mid-chapter page reads with the
                // same margin as the very first/last page of a chapter (where the real CSS
                // padding shows through instead of a mask).
                window.__nascopePageMargin = function() {
                    return ${verticalMargin};
                };
                function nascopeMask(id) {
                    var mask = document.getElementById(id);
                    if (!mask) {
                        mask = document.createElement('div');
                        mask.id = id;
                        mask.style.position = 'fixed';
                        mask.style.left = '0';
                        mask.style.right = '0';
                        mask.style.zIndex = '2147483647';
                        mask.style.pointerEvents = 'none';
                        document.body.appendChild(mask);
                    }
                    mask.style.backgroundColor = ${JSONObject.quote(appearance.backgroundColor)};
                    return mask;
                }
                // pageForward/pageBackward/scrollToFractionSnapped land a page's first line
                // __nascopePageMargin px below the viewport top rather than flush against
                // it — otherwise only the very first page of a chapter (showing the real CSS
                // padding-top) had a visible top margin, while every later page (reached by
                // paging) landed flush against the viewport top with none. This mask paints
                // over whatever content technically scrolled into that reserved strip so it
                // reads as blank space instead of a sliver of the previous line. Hidden at the
                // true top of the chapter, where the real CSS padding already provides the
                // same blank space. pageForward/pageBackward now choose their landing line by
                // its actual box bounds (see __nascopeLineBoxes), so in the normal tap-to-turn
                // path no line should ever straddle this boundary — but scrollToFractionSnapped
                // (restoring an arbitrary saved fraction) picks its anchor by top alone.
                // The page targets below always use the precise fractional line coordinate
                // and round the scroll position down, leaving the first line at or below the
                // fixed margin. The mask must stay fixed: expanding it from a caret probe can
                // mistake that first line for a clipped prior line and hide it completely.
                window.__nascopeApplyTopMask = function() {
                    var mask = nascopeMask('nascope-top-mask');
                    if (window.scrollY <= 2) { mask.style.display = 'none'; return; }
                    var margin = window.__nascopePageMargin();
                    mask.style.display = 'block';
                    mask.style.top = '0';
                    mask.style.height = margin + 'px';
                };
                // Mirrors the top mask at the bottom edge — deliberate minimum height equal
                // to the top margin, extended upward if a line is found actually straddling
                // it, hidden at the true end of the chapter (padding-bottom covers it there).
                window.__nascopeApplyBottomMask = function() {
                    var mask = nascopeMask('nascope-bottom-mask');
                    var viewportH = window.innerHeight;
                    var viewportW = window.innerWidth;
                    var scrollRoot = document.scrollingElement || document.documentElement;
                    var docH = scrollRoot.scrollHeight;
                    if (window.scrollY + viewportH >= docH - 1) {
                        mask.style.display = 'none';
                        return;
                    }
                    var margin = window.__nascopePageMargin();
                    var maskTop = viewportH - margin;
                    if (document.caretRangeFromPoint) {
                        var probeY = maskTop - 1;
                        for (var x = 8; x < viewportW; x += 16) {
                            var range = document.caretRangeFromPoint(x, probeY);
                            var node = range && range.startContainer;
                            if (!node || node.nodeType !== 3) continue;
                            var r = document.createRange();
                            r.selectNodeContents(node);
                            var rects = r.getClientRects();
                            for (var k = 0; k < rects.length; k++) {
                                var rc = rects[k];
                                if (rc.top < maskTop && rc.bottom > maskTop) {
                                    if (rc.top < maskTop) maskTop = rc.top;
                                }
                            }
                        }
                    }
                    mask.style.display = 'block';
                    mask.style.top = maskTop + 'px';
                    mask.style.height = (viewportH - maskTop) + 'px';
                };
                if (!window.__nascopeMaskListenerAdded) {
                    window.__nascopeMaskListenerAdded = true;
                    var applyBoth = function(){ window.__nascopeApplyTopMask(); window.__nascopeApplyBottomMask(); };
                    window.addEventListener('scroll', applyBoth, {passive: true});
                    // A resize here is almost always the reader bars toggling visibility
                    // (height-only, doesn't rewrap text), but multi-window/foldable resizing
                    // can change width too — invalidate defensively so a width change that
                    // reflows text can't leave stale line boxes behind.
                    window.addEventListener('resize', function(){ window.__nascopeInvalidateLineBoxes(); applyBoth(); });
                }
                if (!window.__nascopeScrollGuardAdded) {
                    window.__nascopeScrollGuardAdded = true;
                    var guardScroll = function(e) {
                        if (!window.__nascopeScrollEnabled) e.preventDefault();
                    };
                    document.addEventListener('touchmove', guardScroll, {passive: false});
                    document.addEventListener('wheel', guardScroll, {passive: false});
                }
                window.__nascopeApplyTopMask();
                window.__nascopeApplyBottomMask();
            })();
        """.trimIndent()
        binding.webView.evaluateJavascript(script, null)
    }
    private fun loadBook() {
        binding.statusMsg.text = "불러오는 중..."
        binding.statusMsg.visibility = View.VISIBLE
        lifecycleScope.launch {
            try {
                val (loaded, saved) = withContext(Dispatchers.IO) {
                    val parsed = downloadAndParse()
                    computePageMap(parsed)
                    parsed to db.getProgress(filePath)
                }
                book = loaded
                val (startIndex, startFraction) = parsePosition(saved)
                currentIndex = startIndex.coerceIn(0, loaded.chapters.size - 1)
                pendingScrollFraction = startFraction
                openChapter(currentIndex)
            } catch (e: Exception) {
                binding.statusMsg.text = e.message ?: "EPUB을 여는 데 실패했습니다."
                binding.statusMsg.visibility = View.VISIBLE
            }
        }
    }

    private suspend fun downloadAndParse(): EpubBook {
        val key = "epub_" + CacheStaleness.keyFor(filePath)
        val cachedZip = File(cacheDir, "$key.epub")
        val extractDir = File(cacheDir, key)
        val metaFile = File(cacheDir, "$key.meta")
        if (!cachedZip.exists() || !CacheStaleness.isFresh(metaFile, nasSize, nasMtime)) {
            extractDir.deleteRecursively()
            SynologyApi.downloadToFile(this@EpubReaderActivity, filePath, cachedZip)
            CacheStaleness.writeMeta(metaFile, nasSize, nasMtime)
        }
        return EpubParser.parse(cachedZip, extractDir)
    }

    private fun parsePosition(saved: String?): Pair<Int, Double?> {
        if (saved == null) return 0 to null
        val parts = saved.split(":")
        val idx = parts.getOrNull(0)?.toIntOrNull() ?: 0
        val frac = parts.getOrNull(1)?.toDoubleOrNull()
        return idx to frac
    }

    private fun goChapter(newIndex: Int) {
        val total = book?.chapters?.size ?: return
        if (newIndex < 0) {
            Toast.makeText(this, "이전 챕터가 없습니다.", Toast.LENGTH_SHORT).show()
            return
        }
        if (newIndex >= total) {
            Toast.makeText(this, "다음 챕터가 없습니다.", Toast.LENGTH_SHORT).show()
            return
        }
        saveCurrentProgress {
            currentIndex = newIndex
            openChapter(newIndex)
        }
    }

    private fun openChapter(index: Int) {
        val chapter = book?.chapters?.getOrNull(index) ?: return
        binding.titleText.text = chapter.title
        binding.statusMsg.visibility = View.VISIBLE
        binding.statusMsg.text = "불러오는 중..."
        binding.webView.loadUrl("file://${chapter.absoluteFile.absolutePath}")
    }

    private val tocLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val index = result.data?.getIntExtra(TocActivity.EXTRA_SELECTED, -1) ?: -1
        if (result.resultCode == RESULT_OK && index in 0 until (book?.chapters?.size ?: 0) && index != currentIndex) {
            saveCurrentProgress {
                currentIndex = index
                pendingScrollFraction = 0.0
                openChapter(currentIndex)
            }
        }
    }

    private fun openToc() {
        val chapters = book?.chapters ?: return
        val overallPct = (((currentIndex + withinChapterFraction) / chapters.size) * 100).toInt().coerceIn(0, 100)
        tocLauncher.launch(
            TocActivity.createIntent(
                this,
                titles = chapters.map { it.title }.toTypedArray(),
                pages = if (chapterPageStarts.size == chapters.size) chapterPageStarts else IntArray(chapters.size) { it + 1 },
                currentIndex = currentIndex,
                totalPages = totalPages.coerceAtLeast(1),
                progressPct = overallPct
            )
        )
    }

    // Rough per-chapter "page" numbers so the TOC reads like a physical book (Ridibooks-style),
    // even though EPUB reflow has no fixed pagination — approximated from stripped-tag character count.
    private fun computePageMap(loaded: EpubBook) {
        val charsPerPage = 700
        var running = 1
        val starts = IntArray(loaded.chapters.size)
        loaded.chapters.forEachIndexed { i, chapter ->
            starts[i] = running
            val text = try {
                chapter.absoluteFile.readText().replace(Regex("<[^>]*>"), " ")
            } catch (e: Exception) {
                ""
            }
            val pages = kotlin.math.ceil(text.length / charsPerPage.toDouble()).toInt().coerceAtLeast(1)
            running += pages
        }
        chapterPageStarts = starts
        totalPages = running - 1
    }

    private fun setupTapToggle() {
        val gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                val width = binding.webView.width
                val height = binding.webView.height
                if (width <= 0 || height <= 0) {
                    toggleBars()
                    return false
                }
                val action = TapZone.resolve(
                    Prefs.tapZonePaging(this@EpubReaderActivity),
                    Prefs.tapZoneVertical(this@EpubReaderActivity),
                    e.x / width,
                    e.y / height
                )
                when (action) {
                    TapZone.Action.BACKWARD -> pageBackward()
                    TapZone.Action.FORWARD -> pageForward()
                    TapZone.Action.TOGGLE -> toggleBars()
                }
                return false
            }
        })
        binding.webView.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            // Page-edge modes use taps for navigation, so consume drag movement before
            // WebView can turn it into a native scroll. The explicit Scroll mode leaves
            // every gesture available to WebView for normal continuous reading.
            Prefs.tapZonePaging(this@EpubReaderActivity) &&
                (event.actionMasked == MotionEvent.ACTION_MOVE ||
                    event.actionMasked == MotionEvent.ACTION_CANCEL)
        }
    }

    // Paging within a chapter snaps to a line boundary (via window.__nascopeLineBoxes, defined
    // in applyReaderAppearance) instead of a raw pixel scroll — otherwise a line of text lands
    // split across the viewport edge, showing sliced characters at the top or bottom of the
    // "page". Hitting the end (or start) spills seamlessly into the next (or previous)
    // chapter, like Ridibooks' continuous page-turn — otherwise the reader would dead-end at
    // every chapter boundary.
    //
    // This used to advance by only ~92% of a screen (deliberately re-showing the last ~8%) so
    // a line sitting right at the boundary wouldn't get sliced — but that meant every page
    // turn re-displayed whatever text had been at the bottom of the previous page, which reads
    // as the same sentence repeating. This also used to pick the next page's start by
    // comparing only line TOPS against a fixed reserved margin — but a line's own box can be
    // taller than that margin (see the comment on __nascopeLineBoxes), so a line could have
    // its top counted as "still fits on this page" while its bottom actually spilled past the
    // viewport edge, sliced off. Now every line still on the CURRENT page must have its
    // bottom fit entirely within the reserved content area — the first line that doesn't fit
    // becomes the next page's start, so nothing can ever straddle the boundary regardless of
    // how tall an individual line renders.
    //
    // The landing target is __nascopePageMargin px ABOVE that line's actual top, not on it —
    // otherwise only the chapter's real first page (CSS padding-top) had a top margin, while
    // every page reached by paging landed flush against the viewport top with none — so both
    // edges of every page carry an equal, deliberate margin.
    private fun pageForward() {
        val js = """
            (function(){
                var viewportH = window.innerHeight;
                var scrollRoot = document.scrollingElement || document.documentElement;
                var max = scrollRoot.scrollHeight - viewportH;
                if (max <= 0 || window.scrollY >= max - 2) return "END";
                var margin = window.__nascopePageMargin ? window.__nascopePageMargin() : 0;
                var contentTop = window.scrollY + margin;
                var contentBottom = window.scrollY + viewportH - margin;
                var boxes = (window.__nascopeLineBoxes ? window.__nascopeLineBoxes() : []);
                var lastVisible = -1;
                for (var i = 0; i < boxes.length; i++) {
                    if (boxes[i].top < contentTop) continue;
                    if (boxes[i].bottom <= contentBottom) { lastVisible = i; } else { break; }
                }
                // There is no later line when the current page already contains the end of
                // the chapter. Jumping to max - margin here leaves the same page in place on
                // the next tap, so report the edge and let the activity open the next chapter.
                if (lastVisible < 0 || !boxes[lastVisible + 1]) return "END";
                var next = boxes[lastVisible + 1].top;
                // scrollTo only accepts integer CSS pixels. Floor rather than round so the
                // first line is never placed even a fraction above the fixed top mask.
                window.scrollTo(0, Math.min(Math.max(Math.floor(next - margin), 0), max));
                return "OK";
            })();
        """.trimIndent()
        binding.webView.evaluateJavascript(js) { result ->
            if (result?.trim('"') == "END") goChapterEdge(currentIndex + 1, atStart = true)
        }
    }

    // Without an explicit stack of forward page-start offsets, a backward step can't always
    // land on the exact spot forward navigation would have chosen — so this errs toward
    // landing slightly earlier (a small re-shown gap at most) rather than any chance of
    // landing later and clipping into content pageForward already established as "the next
    // page". Finds the current page's first line, then walks backward accumulating whole
    // lines while they still fit within one page's content budget — the same fits-entirely
    // guarantee pageForward uses, applied in reverse — so a backward step reads with the same
    // margin and never straddles a line the way top-only comparison used to.
    private fun pageBackward() {
        val js = """
            (function(){
                if (window.scrollY <= 2) return "END";
                var viewportH = window.innerHeight;
                var margin = window.__nascopePageMargin ? window.__nascopePageMargin() : 0;
                var currentTop = window.scrollY + margin;
                var boxes = (window.__nascopeLineBoxes ? window.__nascopeLineBoxes() : []);
                var curIdx = -1;
                for (var i = 0; i < boxes.length; i++) {
                    if (boxes[i].top >= currentTop - 1) { curIdx = i; break; }
                }
                if (curIdx <= 0) { window.scrollTo(0, 0); return "OK"; }
                var endIdx = curIdx - 1;
                var startIdx = endIdx;
                var budget = viewportH - 2 * margin;
                while (startIdx > 0 && (boxes[endIdx].bottom - boxes[startIdx - 1].top) <= budget) {
                    startIdx--;
                }
                window.scrollTo(0, Math.max(Math.floor(boxes[startIdx].top - margin), 0));
                return "OK";
            })();
        """.trimIndent()
        binding.webView.evaluateJavascript(js) { result ->
            if (result?.trim('"') == "END") goChapterEdge(currentIndex - 1, atStart = false)
        }
    }

    private fun goChapterEdge(newIndex: Int, atStart: Boolean) {
        val total = book?.chapters?.size ?: return
        if (newIndex < 0 || newIndex >= total) return
        saveCurrentProgress {
            currentIndex = newIndex
            pendingScrollFraction = if (atStart) 0.0 else 1.0
            openChapter(newIndex)
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
                if (fromUser) {
                    withinChapterFraction = progress / 1000.0
                    scrollToFraction(withinChapterFraction)
                    updateProgressLabel()
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {
                userIsSeeking = true
            }

            // Live dragging uses the cheap unsnapped scrollToFraction — snapping every drag
            // tick would recompute line boxes on every pixel of finger movement. Once the
            // finger lifts, snap the final resting spot to a clean line boundary the same way
            // pageForward/pageBackward do, so a seek doesn't leave the reader mid-line or with
            // a line straddling the top/bottom mask.
            override fun onStopTrackingTouch(seekBar: SeekBar) {
                userIsSeeking = false
                scrollToFractionSnapped(withinChapterFraction)
            }
        })
    }

    private fun refreshScrollFraction() {
        val js = """
            (function(){
                var scrollRoot = document.scrollingElement || document.documentElement;
                var max = scrollRoot.scrollHeight - window.innerHeight;
                if (max <= 0) return "0";
                return String(window.scrollY / max);
            })();
        """.trimIndent()
        binding.webView.evaluateJavascript(js) { result ->
            val fraction = result?.trim('"')?.toDoubleOrNull() ?: 0.0
            withinChapterFraction = fraction
            binding.seekBar.progress = (fraction * 1000).toInt().coerceIn(0, 1000)
            updateProgressLabel()
        }
    }

    private fun updateProgressLabel() {
        val total = book?.chapters?.size ?: 0
        if (total == 0) return
        val overallPct = (((currentIndex + withinChapterFraction) / total) * 100).toInt().coerceIn(0, 100)
        binding.progressLabel.text = "${currentIndex + 1} / ${total}장 · $overallPct%"
    }

    private fun scrollToFraction(fraction: Double) {
        val js = """
            (function(){
                var scrollRoot = document.scrollingElement || document.documentElement;
                var max = scrollRoot.scrollHeight - window.innerHeight;
                if (max > 0) window.scrollTo(0, Math.round(max * $fraction));
            })();
        """.trimIndent()
        binding.webView.evaluateJavascript(js, null)
    }

    // Same as scrollToFraction, but snapped to a line boundary — used when reopening a
    // chapter at a saved position, so resuming never drops the reader mid-line. Landing
    // exactly at the document end (fraction 1.0, from paging backward into the previous
    // chapter) is always already safe — the last line can't be "cut" by a viewport edge
    // that coincides with the document's own end — so that case skips snapping entirely.
    // Reapplies the same top-margin offset as pageForward/pageBackward (clamped to 0 at the
    // true chapter start) so a resumed mid-chapter position reads with the same top margin
    // paging would have given it, instead of landing flush against the viewport top.
    private fun scrollToFractionSnapped(fraction: Double) {
        val js = """
            (function(){
                var scrollRoot = document.scrollingElement || document.documentElement;
                var max = scrollRoot.scrollHeight - window.innerHeight;
                if (max <= 0) return;
                var target = Math.round(max * $fraction);
                if (target >= max) { window.scrollTo(0, max); return; }
                var boxes = (window.__nascopeLineBoxes ? window.__nascopeLineBoxes() : []);
                var snapped = 0;
                for (var i = 0; i < boxes.length; i++) {
                    if (boxes[i].top <= target) snapped = boxes[i].top; else break;
                }
                var margin = window.__nascopePageMargin ? window.__nascopePageMargin() : 0;
                window.scrollTo(0, Math.max(Math.min(Math.floor(snapped - margin), max), 0));
            })();
        """.trimIndent()
        binding.webView.evaluateJavascript(js, null)
    }

    private fun adjustZoom(delta: Int) {
        val next = (Prefs.epubTextZoomPct(this) + delta).coerceIn(MIN_ZOOM, MAX_ZOOM)
        Prefs.setEpubTextZoomPct(this, next)
        binding.webView.settings.textZoom = next
        // textZoom is a native WebView setting the injected script has no way to observe —
        // unlike applyReaderAppearance (which re-runs its own script and clears the cache
        // itself) or a DOM resize (which fires its own event), this reflow happens with no
        // signal the page can react to, so the cached line boxes must be invalidated here.
        binding.webView.evaluateJavascript("window.__nascopeInvalidateLineBoxes && window.__nascopeInvalidateLineBoxes();", null)
    }

    private fun saveCurrentProgress(after: () -> Unit) {
        val js = """
            (function(){
                var scrollRoot = document.scrollingElement || document.documentElement;
                var max = scrollRoot.scrollHeight - window.innerHeight;
                if (max <= 0) return "0";
                return String(window.scrollY / max);
            })();
        """.trimIndent()
        binding.webView.evaluateJavascript(js) { result ->
            val fraction = result?.trim('"')?.toDoubleOrNull() ?: 0.0
            db.saveProgress(filePath, "$currentIndex:$fraction")
            after()
        }
    }

    override fun onResume() {
        super.onResume()
        pollHandler.post(pollRunnable)
    }

    override fun onPause() {
        super.onPause()
        pollHandler.removeCallbacks(pollRunnable)
        if (book != null) saveCurrentProgress {}
    }

    private fun reapplyHighlights() {
        val key = highlightKey(currentIndex)
        val loadedForIndex = currentIndex
        lifecycleScope.launch {
            val highlights = withContext(Dispatchers.IO) { db.highlightsFor(key) }
            if (loadedForIndex != currentIndex || highlights.isEmpty()) return@launch
            applyHighlightsJs(highlights)
        }
    }

    // Highlights created after this update carry a nodeIndex (which text node, in document
    // order, the highlight sits in) + a within-node start/end offset — precise even when the
    // same phrase repeats in a chapter. Highlights from before this column existed have no
    // nodeIndex, so they still fall back to the old first-match-by-snippet-text search.
    private fun applyHighlightsJs(highlights: List<Highlight>) {
        val (precise, legacy) = highlights.partition { it.nodeIndex != null }
        val preciseJson = JSONArray(precise.map { h ->
            JSONObject().apply {
                put("nodeIndex", h.nodeIndex)
                put("start", h.startOffset)
                put("end", h.endOffset)
            }
        }).toString()
        val legacyJson = JSONArray(legacy.map { it.snippet }).toString()
        val js = """
            (function(precise, legacySnippets){
                var walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT, null, false);
                var nodes = [];
                var n;
                while (n = walker.nextNode()) nodes.push(n);
                function applyMark(range) {
                    try {
                        var mark = document.createElement('mark');
                        mark.className = 'nascope-hl';
                        range.surroundContents(mark);
                    } catch(e) {}
                }
                // Applying a highlight splits its text node (surroundContents), so a second
                // highlight on the SAME node would see a stale/truncated node if processed in
                // start-ascending order. Processing highest-offset-first within each node keeps
                // the original node reference valid for the earlier (still-unprocessed) ranges.
                precise.sort(function(a, b){ return a.nodeIndex - b.nodeIndex || b.start - a.start; });
                precise.forEach(function(h){
                    var target = nodes[h.nodeIndex];
                    if (!target || target.parentNode.tagName === 'MARK') return;
                    var maxLen = target.nodeValue.length;
                    var start = Math.min(h.start, maxLen);
                    var end = Math.min(h.end, maxLen);
                    if (end <= start) return;
                    var range = document.createRange();
                    range.setStart(target, start);
                    range.setEnd(target, end);
                    applyMark(range);
                });
                legacySnippets.forEach(function(text){
                    if (!text) return;
                    for (var i = 0; i < nodes.length; i++) {
                        var node = nodes[i];
                        if (node.parentNode.tagName === 'MARK') continue;
                        var idx = node.nodeValue.indexOf(text);
                        if (idx >= 0) {
                            var range = document.createRange();
                            range.setStart(node, idx);
                            range.setEnd(node, idx + text.length);
                            applyMark(range);
                            break;
                        }
                    }
                });
            })($preciseJson, $legacyJson);
        """.trimIndent()
        binding.webView.evaluateJavascript(js, null)
    }

    private fun highlightCurrentSelection() {
        val js = """
            (function(){
                var sel = window.getSelection();
                if (!sel || sel.rangeCount === 0) return "";
                var text = sel.toString();
                if (!text) return "";
                try {
                    var range = sel.getRangeAt(0);
                    var container = range.startContainer;
                    if (container.nodeType !== 3) return "__FAILED__";
                    var walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT, null, false);
                    var idx = -1, node, i = 0;
                    while (node = walker.nextNode()) { if (node === container) { idx = i; break; } i++; }
                    if (idx < 0) return "__FAILED__";
                    var startOffset = range.startOffset;
                    var mark = document.createElement('mark');
                    mark.className = 'nascope-hl';
                    range.surroundContents(mark);
                    sel.removeAllRanges();
                    return JSON.stringify({nodeIndex: idx, start: startOffset, end: startOffset + text.length, text: text});
                } catch (e) {
                    return "__FAILED__";
                }
            })();
        """.trimIndent()
        binding.webView.evaluateJavascript(js) { result ->
            val payload = try {
                (org.json.JSONTokener(result ?: "\"\"").nextValue() as? String) ?: ""
            } catch (e: Exception) {
                ""
            }
            when {
                payload == "__FAILED__" -> Toast.makeText(
                    this, "이 영역은 하이라이트할 수 없습니다. 문단 경계를 넘지 않게 선택해주세요.", Toast.LENGTH_LONG
                ).show()
                payload.isBlank() -> Toast.makeText(this, "먼저 텍스트를 길게 눌러 선택해주세요.", Toast.LENGTH_SHORT).show()
                else -> {
                    val parsed = try { JSONObject(payload) } catch (e: Exception) { null }
                    if (parsed == null) {
                        Toast.makeText(this, "하이라이트 저장에 실패했습니다.", Toast.LENGTH_SHORT).show()
                    } else {
                        val nodeIndex = parsed.getInt("nodeIndex")
                        val start = parsed.getInt("start")
                        val end = parsed.getInt("end")
                        val text = parsed.getString("text")
                        val key = highlightKey(currentIndex)
                        lifecycleScope.launch {
                            withContext(Dispatchers.IO) { db.addHighlight(key, start, end, text.take(200), nodeIndex) }
                            Toast.makeText(this@EpubReaderActivity, "하이라이트가 추가되었습니다.", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }
    }

    // Combines bookmarks and highlights (from every chapter) into one list (Ridibooks'
    // "독서노트"), sorted by chapter order, so it doubles as an annotated table of contents.
    private fun showReadingNotesDialog() {
        val chapters = book?.chapters ?: return
        val dateFmt = SimpleDateFormat("MM/dd HH:mm", Locale.getDefault())
        data class Note(val chapterIndex: Int, val label: String, val jump: () -> Unit)

        lifecycleScope.launch {
            val (bookmarks, highlights) = withContext(Dispatchers.IO) {
                db.bookmarksFor(filePath) to db.highlightsForBookPrefix(filePath)
            }
            val bookmarkNotes = bookmarks.map { bm ->
                val (idx, frac) = parsePosition(bm.position)
                Note(idx, "🔖 ${bm.label}  (${dateFmt.format(bm.createdAt)})") {
                    saveCurrentProgress {
                        currentIndex = idx.coerceIn(0, chapters.size - 1)
                        pendingScrollFraction = frac
                        openChapter(currentIndex)
                    }
                }
            }
            val highlightNotes = highlights.mapNotNull { hl ->
                val idx = chapters.indexOfFirst { "$filePath#${it.relativePath}" == hl.filePath }
                if (idx < 0) null else Note(idx, "🖍 ${hl.snippet}") {
                    saveCurrentProgress {
                        currentIndex = idx
                        pendingScrollFraction = 0.0
                        openChapter(currentIndex)
                    }
                }
            }
            val notes = (bookmarkNotes + highlightNotes).sortedBy { it.chapterIndex }

            val labels = mutableListOf("+ 현재 위치에 책갈피 추가")
            labels.addAll(notes.map { it.label })

            AlertDialog.Builder(this@EpubReaderActivity)
                .setTitle("독서노트")
                .setItems(labels.toTypedArray()) { _, index ->
                    if (index == 0) addBookmarkAtCurrentPosition() else notes[index - 1].jump()
                }
                .setNegativeButton("닫기", null)
                .show()
        }
    }

    private fun showHighlightsDialog() {
        val chapters = book?.chapters ?: return
        val dateFmt = SimpleDateFormat("MM/dd HH:mm", Locale.getDefault())
        lifecycleScope.launch {
            val highlights = withContext(Dispatchers.IO) {
                db.highlightsForBookPrefix(filePath)
            }
            if (highlights.isEmpty()) {
                AlertDialog.Builder(this@EpubReaderActivity)
                    .setTitle("하이라이트")
                    .setMessage("이 파일에 저장된 하이라이트가 없습니다.\n텍스트를 길게 눌러 선택한 뒤 하이라이트를 추가하세요.")
                    .setPositiveButton("확인", null)
                    .show()
                return@launch
            }
            val notes = highlights.mapNotNull { highlight ->
                val chapterIndex = chapters.indexOfFirst {
                    "$filePath#${it.relativePath}" == highlight.filePath
                }
                if (chapterIndex < 0) null else {
                    val chapterTitle = chapters[chapterIndex].title
                    "${highlight.snippet}\n$chapterTitle · ${dateFmt.format(highlight.createdAt)}" to chapterIndex
                }
            }
            AlertDialog.Builder(this@EpubReaderActivity)
                .setTitle("하이라이트 (${notes.size})")
                .setItems(notes.map { it.first }.toTypedArray()) { _, which ->
                    val chapterIndex = notes[which].second
                    saveCurrentProgress {
                        currentIndex = chapterIndex
                        pendingScrollFraction = 0.0
                        openChapter(currentIndex)
                    }
                }
                .setNegativeButton("닫기", null)
                .show()
        }
    }

    private fun addBookmarkAtCurrentPosition() {
        saveCurrentProgress {
            val chapterTitle = book?.chapters?.getOrNull(currentIndex)?.title ?: "챕터 ${currentIndex + 1}"
            lifecycleScope.launch {
                withContext(Dispatchers.IO) {
                    val saved = db.getProgress(filePath)
                    db.addBookmark(filePath, saved ?: "$currentIndex:0.0", chapterTitle)
                }
                Toast.makeText(this@EpubReaderActivity, "책갈피가 추가되었습니다.", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
