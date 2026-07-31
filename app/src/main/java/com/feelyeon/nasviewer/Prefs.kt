package com.feelyeon.nasviewer

import android.content.Context

/**
 * Plain SharedPreferences for account/password/paths. This is a personal single-user
 * app meant to run only on the owner's own device — storing the DSM password here
 * (same trade-off as the earlier web version's localStorage) is what makes
 * "log in once, stay logged in" possible without re-prompting every launch.
 */
object Prefs {
    private const val FILE = "nasviewer_prefs"
    private const val KEY_BASE_URL = "base_url"
    private const val KEY_ACCOUNT = "account"
    private const val KEY_PASSWORD = "password"
    private const val KEY_LAST_PATH = "last_path"
    private const val KEY_TEXT_FONT_SIZE = "text_font_size_sp"
    private const val KEY_EPUB_TEXT_ZOOM = "epub_text_zoom_pct"
    private const val KEY_READER_THEME = "reader_theme"
    private const val KEY_READER_MARGIN = "reader_margin_dp"
    private const val KEY_READER_LINE_SPACING = "reader_line_spacing_dp"
    private const val KEY_READER_JUSTIFY = "reader_justify"
    private const val KEY_TAP_ZONE_PAGING = "tap_zone_paging"
    private const val KEY_TAP_ZONE_VERTICAL = "tap_zone_vertical"
    private const val KEY_TEXT_PAGED_MODE = "text_paged_mode"
    private const val KEY_UI_TEXT_SCALE = "ui_text_scale"
    private const val KEY_READER_FONT_FAMILY = "reader_font_family"
    private const val KEY_READER_PARAGRAPH_SPACING = "reader_paragraph_spacing_dp"
    private const val KEY_HIGHLIGHTS_VISIBLE = "highlights_visible"
    private const val KEY_READER_BRIGHTNESS = "reader_brightness"
    private const val KEY_PDF_CONTINUOUS_SCROLL = "pdf_continuous_scroll"

    private fun sp(context: Context) =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun baseUrl(context: Context): String = sp(context).getString(KEY_BASE_URL, "") ?: ""
    fun setBaseUrl(context: Context, value: String) {
        sp(context).edit().putString(KEY_BASE_URL, value.trimEnd('/')).apply()
    }

    fun account(context: Context): String = sp(context).getString(KEY_ACCOUNT, "") ?: ""
    fun setAccount(context: Context, value: String) {
        sp(context).edit().putString(KEY_ACCOUNT, value).apply()
    }

    fun password(context: Context): String = sp(context).getString(KEY_PASSWORD, "") ?: ""
    fun setPassword(context: Context, value: String) {
        sp(context).edit().putString(KEY_PASSWORD, value).apply()
    }

    fun lastPath(context: Context): String = sp(context).getString(KEY_LAST_PATH, "") ?: ""
    fun setLastPath(context: Context, value: String) {
        sp(context).edit().putString(KEY_LAST_PATH, value).apply()
    }

    fun textFontSizeSp(context: Context): Int = sp(context).getInt(KEY_TEXT_FONT_SIZE, 16)
    fun setTextFontSizeSp(context: Context, value: Int) {
        sp(context).edit().putInt(KEY_TEXT_FONT_SIZE, value).apply()
    }

    fun epubTextZoomPct(context: Context): Int = sp(context).getInt(KEY_EPUB_TEXT_ZOOM, 100)
    fun setEpubTextZoomPct(context: Context, value: Int) {
        sp(context).edit().putInt(KEY_EPUB_TEXT_ZOOM, value).apply()
    }

    fun readerTheme(context: Context): Int = sp(context).getInt(KEY_READER_THEME, 0)
    fun setReaderTheme(context: Context, value: Int) {
        sp(context).edit().putInt(KEY_READER_THEME, value).apply()
    }

    fun readerMarginDp(context: Context): Int = sp(context).getInt(KEY_READER_MARGIN, 20)
    fun setReaderMarginDp(context: Context, value: Int) {
        sp(context).edit().putInt(KEY_READER_MARGIN, value).apply()
    }

    fun readerLineSpacingDp(context: Context): Int = sp(context).getInt(KEY_READER_LINE_SPACING, 6)
    fun setReaderLineSpacingDp(context: Context, value: Int) {
        sp(context).edit().putInt(KEY_READER_LINE_SPACING, value).apply()
    }

    fun readerJustify(context: Context): Boolean = sp(context).getBoolean(KEY_READER_JUSTIFY, true)
    fun setReaderJustify(context: Context, value: Boolean) {
        sp(context).edit().putBoolean(KEY_READER_JUSTIFY, value).apply()
    }

    fun tapZonePaging(context: Context): Boolean = sp(context).getBoolean(KEY_TAP_ZONE_PAGING, true)
    fun setTapZonePaging(context: Context, value: Boolean) {
        sp(context).edit().putBoolean(KEY_TAP_ZONE_PAGING, value).apply()
    }
    // false = left/right edges page (the original default); true = top/bottom edges page.
    fun tapZoneVertical(context: Context): Boolean = sp(context).getBoolean(KEY_TAP_ZONE_VERTICAL, false)
    fun setTapZoneVertical(context: Context, value: Boolean) {
        sp(context).edit().putBoolean(KEY_TAP_ZONE_VERTICAL, value).apply()
    }
    fun textPagedMode(context: Context): Boolean = sp(context).getBoolean(KEY_TEXT_PAGED_MODE, false)
    fun setTextPagedMode(context: Context, value: Boolean) {
        sp(context).edit().putBoolean(KEY_TEXT_PAGED_MODE, value).apply()
    }
    fun uiTextScale(context: Context): Float = sp(context).getFloat(KEY_UI_TEXT_SCALE, 1f)
    fun setUiTextScale(context: Context, value: Float) {
        sp(context).edit().putFloat(KEY_UI_TEXT_SCALE, value).apply()
    }

    fun readerFontFamily(context: Context): String =
        sp(context).getString(KEY_READER_FONT_FAMILY, "sans-serif") ?: "sans-serif"
    fun setReaderFontFamily(context: Context, value: String) {
        sp(context).edit().putString(KEY_READER_FONT_FAMILY, value).apply()
    }

    fun readerParagraphSpacingDp(context: Context): Int = sp(context).getInt(KEY_READER_PARAGRAPH_SPACING, 12)
    fun setReaderParagraphSpacingDp(context: Context, value: Int) {
        sp(context).edit().putInt(KEY_READER_PARAGRAPH_SPACING, value).apply()
    }

    fun highlightsVisible(context: Context): Boolean = sp(context).getBoolean(KEY_HIGHLIGHTS_VISIBLE, true)
    fun setHighlightsVisible(context: Context, value: Boolean) {
        sp(context).edit().putBoolean(KEY_HIGHLIGHTS_VISIBLE, value).apply()
    }

    // -1f means "follow the system brightness" (matches WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE),
    // otherwise a 0.01..1.0 fraction applied directly to the reader window.
    fun readerBrightness(context: Context): Float = sp(context).getFloat(KEY_READER_BRIGHTNESS, -1f)
    fun setReaderBrightness(context: Context, value: Float) {
        sp(context).edit().putFloat(KEY_READER_BRIGHTNESS, value).apply()
    }
    // false = existing default (가로 넘김, ViewPager2 page-by-page); true = 세로 스크롤(continuous).
    fun pdfContinuousScroll(context: Context): Boolean = sp(context).getBoolean(KEY_PDF_CONTINUOUS_SCROLL, false)
    fun setPdfContinuousScroll(context: Context, value: Boolean) {
        sp(context).edit().putBoolean(KEY_PDF_CONTINUOUS_SCROLL, value).apply()
    }

    fun hasAccount(context: Context): Boolean =
        account(context).isNotBlank() && password(context).isNotBlank() && baseUrl(context).isNotBlank()

    fun clear(context: Context) {
        sp(context).edit().clear().apply()
    }
}
