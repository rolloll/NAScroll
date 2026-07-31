package com.feelyeon.nasviewer

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.feelyeon.nasviewer.databinding.ActivityTocBinding

class TocActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_SELECTED = "extra_selected"
        private const val EXTRA_TITLES = "extra_titles"
        private const val EXTRA_PAGES = "extra_pages"
        private const val EXTRA_CURRENT = "extra_current"
        private const val EXTRA_TOTAL_PAGES = "extra_total_pages"
        private const val EXTRA_PROGRESS_PCT = "extra_progress_pct"

        fun createIntent(
            context: Context,
            titles: Array<String>,
            pages: IntArray,
            currentIndex: Int,
            totalPages: Int,
            progressPct: Int
        ): Intent {
            val intent = Intent(context, TocActivity::class.java)
            intent.putExtra(EXTRA_TITLES, titles)
            intent.putExtra(EXTRA_PAGES, pages)
            intent.putExtra(EXTRA_CURRENT, currentIndex)
            intent.putExtra(EXTRA_TOTAL_PAGES, totalPages)
            intent.putExtra(EXTRA_PROGRESS_PCT, progressPct)
            return intent
        }
    }

    private lateinit var binding: ActivityTocBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTocBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val titles = intent.getStringArrayExtra(EXTRA_TITLES) ?: emptyArray()
        val pages = intent.getIntArrayExtra(EXTRA_PAGES) ?: IntArray(0)
        val currentIndex = intent.getIntExtra(EXTRA_CURRENT, 0)
        val totalPages = intent.getIntExtra(EXTRA_TOTAL_PAGES, 1)
        val progressPct = intent.getIntExtra(EXTRA_PROGRESS_PCT, 0)

        binding.progressText.text = "${currentIndex + 1} / ${titles.size}장 · 약 $totalPages 페이지 · $progressPct%"
        binding.backBtn.setOnClickListener { finish() }
        binding.tocRecycler.layoutManager = LinearLayoutManager(this)
        binding.tocRecycler.adapter = TocAdapter(titles.toList(), pages.toList(), currentIndex) { index ->
            setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_SELECTED, index))
            finish()
        }
        binding.tocRecycler.scrollToPosition(currentIndex.coerceIn(0, (titles.size - 1).coerceAtLeast(0)))
    }
}
