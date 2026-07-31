package com.feelyeon.nasviewer

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetDialog

data class ReaderAppearance(
    val backgroundColor: String,
    val textColor: String,
    val marginDp: Int,
    val lineSpacingDp: Int,
    val justify: Boolean,
    val fontFamily: String,
    val paragraphSpacingDp: Int,
    val highlightsVisible: Boolean,
    val brightness: Float
)

private data class ThemeOption(val index: Int, val label: String, val bg: String, val text: String)

object ReaderAppearanceSettings {
    private const val NARROW = "좁게"
    private const val NORMAL = "보통"
    private const val WIDE = "넓게"
    private const val PARAGRAPH_SPACING_DEFAULT = 12

    // CSS/Typeface generic families. Android maps these to system-installed fonts
    // (Noto Sans/Serif CJK on most devices) — there is no bundled "KoPub 바탕체" font file,
    // so this offers the closest honest equivalent without shipping a font asset.
    private val FONTS = listOf("sans-serif" to "기본체", "serif" to "명조체", "monospace" to "고정폭")

    // Display order for the swatch row (white/paper/gray/dark/green) is independent of the
    // stored `index` — index 2 ("어둡게") predates this list and existing installs may already
    // have it saved, so new themes are appended after it rather than renumbering.
    private val THEMES = listOf(
        ThemeOption(0, "밝게", "#FFFDF8", "#202124"),
        ThemeOption(1, "종이색", "#F3EBDD", "#4A4035"),
        ThemeOption(3, "그레이", "#B0B3BA", "#1B1D22"),
        ThemeOption(2, "어둡게", "#14161F", "#EEF0F6"),
        ThemeOption(4, "그린", "#0F2A22", "#BFE3D0")
    )

    private val LINE_SPACING_LEVELS = listOf(0, 2, 4, 6, 8, 10, 12, 14, 16)
    private val PARAGRAPH_SPACING_LEVELS = listOf(0, 4, 8, 12, 16, 20, 24, 28, 32)
    private val MARGIN_LEVELS = listOf(8, 12, 16, 20, 24, 28, 32, 36, 40)

    fun current(context: Context): ReaderAppearance {
        val theme = THEMES.firstOrNull { it.index == Prefs.readerTheme(context) } ?: THEMES[0]
        return ReaderAppearance(
            backgroundColor = theme.bg,
            textColor = theme.text,
            marginDp = Prefs.readerMarginDp(context),
            lineSpacingDp = Prefs.readerLineSpacingDp(context),
            justify = Prefs.readerJustify(context),
            fontFamily = Prefs.readerFontFamily(context),
            paragraphSpacingDp = Prefs.readerParagraphSpacingDp(context),
            highlightsVisible = Prefs.highlightsVisible(context),
            brightness = Prefs.readerBrightness(context)
        )
    }

    // Single unified "보기 설정" panel (theme swatches + brightness + inline steppers/toggles),
    // replacing the old drill-down list of sub-dialogs. Shared by EpubReaderActivity and
    // TextReaderActivity, which differ in how "글자 크기" is actually applied (WebView.textZoom
    // vs a native TextView size that also needs page rebuilding) — callers supply that behavior
    // via zoomLevels/currentZoom/onZoomChange rather than this object touching either reader
    // directly. showReadingMode is TXT-only (EPUB has no separate paged/scroll mode to pick).
    fun show(
        context: Context,
        zoomLevels: List<Int>,
        currentZoom: () -> Int,
        onZoomChange: (Int) -> Unit,
        showReadingMode: Boolean = false,
        onChanged: () -> Unit
    ) {
        val dialog = BottomSheetDialog(context)
        val view = LayoutInflater.from(context).inflate(R.layout.sheet_reader_appearance, null)
        dialog.setContentView(view)

        setupThemeSwatches(context, view, onChanged)
        setupBrightness(context, view)

        val fontValueText = view.findViewById<TextView>(R.id.fontValueText)
        fun refreshFont() {
            val label = FONTS.firstOrNull { it.first == Prefs.readerFontFamily(context) }?.second ?: FONTS[0].second
            fontValueText.text = "$label ▾"
        }
        refreshFont()
        view.findViewById<View>(R.id.fontRow).setOnClickListener {
            val idx = FONTS.indexOfFirst { it.first == Prefs.readerFontFamily(context) }.coerceAtLeast(0)
            showSingleChoice(context, "글꼴", FONTS.map { it.second }, idx, dismissOnSelect = true) { which ->
                Prefs.setReaderFontFamily(context, FONTS[which].first)
                refreshFont()
                onChanged()
            }
        }

        setupStepper(
            view, R.id.fontSizeMinusBtn, R.id.fontSizePlusBtn, R.id.fontSizeValueText,
            zoomLevels, currentZoom, { onZoomChange(it); onChanged() }
        )
        setupStepper(
            view, R.id.lineSpacingMinusBtn, R.id.lineSpacingPlusBtn, R.id.lineSpacingValueText,
            LINE_SPACING_LEVELS,
            { Prefs.readerLineSpacingDp(context) },
            { Prefs.setReaderLineSpacingDp(context, it); onChanged() }
        )
        val refreshParagraphSpacing = setupStepper(
            view, R.id.paragraphSpacingMinusBtn, R.id.paragraphSpacingPlusBtn, R.id.paragraphSpacingValueText,
            PARAGRAPH_SPACING_LEVELS,
            { Prefs.readerParagraphSpacingDp(context) },
            { Prefs.setReaderParagraphSpacingDp(context, it); onChanged() }
        )
        view.findViewById<View>(R.id.paragraphSpacingResetBtn).setOnClickListener {
            Prefs.setReaderParagraphSpacingDp(context, PARAGRAPH_SPACING_DEFAULT)
            refreshParagraphSpacing()
            onChanged()
        }
        setupStepper(
            view, R.id.marginMinusBtn, R.id.marginPlusBtn, R.id.marginValueText,
            MARGIN_LEVELS,
            { Prefs.readerMarginDp(context) },
            { Prefs.setReaderMarginDp(context, it); onChanged() }
        )

        val alignValueText = view.findViewById<TextView>(R.id.alignValueText)
        fun refreshAlign() {
            alignValueText.text = (if (Prefs.readerJustify(context)) "양쪽 정렬" else "왼쪽 정렬") + " ▾"
        }
        refreshAlign()
        view.findViewById<View>(R.id.alignRow).setOnClickListener {
            val idx = if (Prefs.readerJustify(context)) 0 else 1
            showSingleChoice(context, "문단 정렬", listOf("양쪽 정렬", "왼쪽 정렬"), idx, dismissOnSelect = true) { which ->
                Prefs.setReaderJustify(context, which == 0)
                refreshAlign()
                onChanged()
            }
        }

        val readingModeRow = view.findViewById<View>(R.id.readingModeRow)
        if (showReadingMode) {
            readingModeRow.visibility = View.VISIBLE
            val readingModeValueText = view.findViewById<TextView>(R.id.readingModeValueText)
            fun refreshReadingMode() {
                readingModeValueText.text = (if (Prefs.textPagedMode(context)) "페이지" else "스크롤") + " ▾"
            }
            refreshReadingMode()
            readingModeRow.setOnClickListener {
                val idx = if (Prefs.textPagedMode(context)) 1 else 0
                showSingleChoice(context, "읽기 방식", listOf("스크롤 보기", "페이지 보기"), idx, dismissOnSelect = true) { which ->
                    Prefs.setTextPagedMode(context, which == 1)
                    refreshReadingMode()
                    onChanged()
                }
            }
        }

        val highlightSwitch = view.findViewById<Switch>(R.id.highlightSwitch)
        highlightSwitch.isChecked = Prefs.highlightsVisible(context)
        highlightSwitch.setOnCheckedChangeListener { _, checked ->
            Prefs.setHighlightsVisible(context, checked)
            onChanged()
        }

        dialog.show()
    }

    private fun setupThemeSwatches(context: Context, root: View, onChanged: () -> Unit) {
        val row = root.findViewById<LinearLayout>(R.id.themeSwatchRow)
        val rings = mutableMapOf<Int, View>()
        fun refreshSelection() {
            val active = Prefs.readerTheme(context)
            rings.forEach { (index, ring) -> ring.isSelected = index == active }
        }
        THEMES.forEach { theme ->
            val swatch = LayoutInflater.from(context).inflate(R.layout.item_theme_swatch, row, false) as FrameLayout
            val ring = swatch.findViewById<View>(R.id.swatchRing)
            val dot = swatch.findViewById<View>(R.id.swatchDot)
            (dot.background.mutate() as GradientDrawable).setColor(android.graphics.Color.parseColor(theme.bg))
            rings[theme.index] = ring
            swatch.setOnClickListener {
                Prefs.setReaderTheme(context, theme.index)
                refreshSelection()
                onChanged()
            }
            row.addView(swatch)
        }
        refreshSelection()
    }

    private fun setupBrightness(context: Context, root: View) {
        val activity = context as? Activity
        val seekBar = root.findViewById<SeekBar>(R.id.brightnessSeekBar)
        val resetBtn = root.findViewById<View>(R.id.brightnessResetBtn)
        seekBar.max = 100

        fun systemBrightnessPct(): Int {
            val sys = try {
                Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
            } catch (e: Exception) {
                180
            }
            return (sys * 100 / 255).coerceIn(1, 100)
        }

        fun applyBrightness(value: Float) {
            activity?.let {
                val attrs = it.window.attributes
                attrs.screenBrightness = value
                it.window.attributes = attrs
            }
        }

        val current = Prefs.readerBrightness(context)
        seekBar.progress = if (current < 0f) systemBrightnessPct() else (current * 100).toInt().coerceIn(1, 100)

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val value = progress.coerceAtLeast(1) / 100f
                Prefs.setReaderBrightness(context, value)
                applyBrightness(value)
            }
            override fun onStartTrackingTouch(bar: SeekBar) {}
            override fun onStopTrackingTouch(bar: SeekBar) {}
        })

        resetBtn.setOnClickListener {
            Prefs.setReaderBrightness(context, -1f)
            applyBrightness(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE)
            seekBar.progress = systemBrightnessPct()
        }
    }

    // Wires a −/value/+ row to a fixed list of allowed values (levels), showing the 1-based
    // position in that list rather than the raw dp/pct number — matches how Ridibooks' own
    // size controls read as a small step count instead of a physical unit. Returns a refresh
    // closure so callers with an extra control that changes the same value (e.g. a reset
    // button) can re-sync the label after using it.
    private fun setupStepper(
        root: View,
        minusId: Int,
        plusId: Int,
        valueId: Int,
        levels: List<Int>,
        get: () -> Int,
        set: (Int) -> Unit
    ): () -> Unit {
        val minusBtn = root.findViewById<View>(minusId)
        val plusBtn = root.findViewById<View>(plusId)
        val valueText = root.findViewById<TextView>(valueId)

        fun closestIndex(): Int {
            val exact = levels.indexOf(get())
            if (exact >= 0) return exact
            return levels.indices.minByOrNull { kotlin.math.abs(levels[it] - get()) } ?: 0
        }
        fun refresh() {
            valueText.text = (closestIndex() + 1).toString()
        }
        minusBtn.setOnClickListener {
            val next = (closestIndex() - 1).coerceAtLeast(0)
            set(levels[next])
            refresh()
        }
        plusBtn.setOnClickListener {
            val next = (closestIndex() + 1).coerceAtMost(levels.lastIndex)
            set(levels[next])
            refresh()
        }
        refresh()
        return ::refresh
    }

    // Entry point for the reader's separate "⚙" button — was a row nested inside "보기 설정"
    // (show() above), split out to its own top-level menu to match Ridibooks having 보기
    // 설정/뷰어 설정 as two independent buttons instead of one leading into the other.
    fun showTouchSettings(context: Context, onChanged: () -> Unit) {
        val checked = when {
            !Prefs.tapZonePaging(context) -> 0
            Prefs.tapZoneVertical(context) -> 2
            else -> 1
        }
        showSingleChoice(
            context,
            "뷰어 설정",
            listOf(
                "탭으로 페이지 넘기기 사용 안 함",
                "좌/우 터치 영역으로 이전/다음 페이지 이동",
                "상/하 터치 영역으로 이전/다음 페이지 이동"
            ),
            checked,
            dismissOnSelect = false
        ) { which ->
            when (which) {
                0 -> Prefs.setTapZonePaging(context, false)
                1 -> { Prefs.setTapZonePaging(context, true); Prefs.setTapZoneVertical(context, false) }
                else -> { Prefs.setTapZonePaging(context, true); Prefs.setTapZoneVertical(context, true) }
            }
            onChanged()
        }
    }

    // Matches the sw600dp resource-qualifier threshold Android itself uses to call a device
    // a "tablet" (roughly 8"+ at typical density) — same signal, read at runtime since this
    // is a plain Kotlin object rather than a value pulled from resources.
    private fun isTablet(context: Context): Boolean =
        context.resources.configuration.smallestScreenWidthDp >= 600

    // A single-choice (radio) list. dismissOnSelect=true matches picking one closing the list;
    // false keeps it open with a manual close, for 뷰어 설정 where seeing the radio move
    // without the sheet vanishing makes the choice easier to confirm.
    private fun showSingleChoice(
        context: Context,
        title: String,
        items: List<String>,
        checked: Int,
        dismissOnSelect: Boolean,
        onSelect: (Int) -> Unit
    ) {
        if (isTablet(context)) {
            val builder = AlertDialog.Builder(context)
                .setTitle(title)
                .setSingleChoiceItems(items.toTypedArray(), checked) { dialog, which ->
                    onSelect(which)
                    if (dismissOnSelect) dialog.dismiss()
                }
            if (!dismissOnSelect) builder.setPositiveButton("닫기", null)
            builder.show()
        } else {
            showBottomSheetSingleChoice(context, title, items, checked, dismissOnSelect, onSelect)
        }
    }

    private fun inflateSheet(context: Context, title: String): Pair<View, LinearLayout> {
        val view = LayoutInflater.from(context).inflate(R.layout.sheet_settings_list, null)
        view.findViewById<TextView>(R.id.sheetTitle).text = title
        return view to view.findViewById(R.id.sheetItemContainer)
    }

    private fun showBottomSheetSingleChoice(
        context: Context,
        title: String,
        items: List<String>,
        checked: Int,
        dismissOnSelect: Boolean,
        onSelect: (Int) -> Unit
    ) {
        val dialog = BottomSheetDialog(context)
        val (view, container) = inflateSheet(context, title)
        val radios = mutableListOf<RadioButton>()
        items.forEachIndexed { index, label ->
            val row = LayoutInflater.from(context).inflate(R.layout.sheet_settings_radio_item, container, false)
            val radio = row.findViewById<RadioButton>(R.id.itemRadio)
            row.findViewById<TextView>(R.id.itemText).text = label
            radio.isChecked = index == checked
            radios += radio
            row.setOnClickListener {
                radios.forEachIndexed { i, r -> r.isChecked = i == index }
                onSelect(index)
                if (dismissOnSelect) dialog.dismiss()
            }
            container.addView(row)
        }
        dialog.setContentView(view)
        dialog.show()
    }
}
