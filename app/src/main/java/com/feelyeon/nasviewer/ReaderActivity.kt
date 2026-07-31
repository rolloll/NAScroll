package com.feelyeon.nasviewer

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.feelyeon.nasviewer.databinding.ActivityReaderBinding
import kotlinx.coroutines.launch

class ReaderActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_NAMES = "extra_names"
        private const val EXTRA_PATHS = "extra_paths"
        private const val EXTRA_START_INDEX = "extra_start_index"
        private const val EXTRA_CHAPTER_PATH = "extra_chapter_path"

        fun start(context: Context, images: List<FileItem>, startIndex: Int, chapterPath: String) {
            val intent = Intent(context, ReaderActivity::class.java)
            intent.putStringArrayListExtra(EXTRA_NAMES, ArrayList(images.map { it.name }))
            intent.putStringArrayListExtra(EXTRA_PATHS, ArrayList(images.map { it.path }))
            intent.putExtra(EXTRA_START_INDEX, startIndex)
            intent.putExtra(EXTRA_CHAPTER_PATH, chapterPath)
            context.startActivity(intent)
        }
    }

    private lateinit var binding: ActivityReaderBinding
    private lateinit var chapterPath: String
    private val retriedPositions = mutableSetOf<Int>()
    private var barsVisible = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityReaderBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val names = intent.getStringArrayListExtra(EXTRA_NAMES) ?: arrayListOf()
        val paths = intent.getStringArrayListExtra(EXTRA_PATHS) ?: arrayListOf()
        val startIndex = intent.getIntExtra(EXTRA_START_INDEX, 0)
        chapterPath = intent.getStringExtra(EXTRA_CHAPTER_PATH) ?: "/"

        val images = names.indices.map { FileItem(names[it], paths[it], isDir = false) }
        binding.readerTitle.text = chapterPath.trimEnd('/').substringAfterLast('/')

        val layoutManager = LinearLayoutManager(this)
        binding.readerRecycler.layoutManager = layoutManager
        binding.readerRecycler.adapter = ReaderAdapter(this, images) { position -> handleImageError(position) }
        if (startIndex > 0) {
            layoutManager.scrollToPositionWithOffset(startIndex, 0)
        }

        setupTapToggle()

        binding.readerBack.setOnClickListener { finish() }
    }

    private fun setupTapToggle() {
        val gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                toggleBars()
                return true
            }
        })
        binding.readerRecycler.addOnItemTouchListener(object : RecyclerView.OnItemTouchListener {
            override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
                gestureDetector.onTouchEvent(e)
                return false
            }
            override fun onTouchEvent(rv: RecyclerView, e: MotionEvent) {}
            override fun onRequestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {}
        })
    }

    private fun toggleBars() {
        barsVisible = !barsVisible
        binding.readerTopBar.visibility = if (barsVisible) View.VISIBLE else View.GONE
    }

    private fun handleImageError(position: Int) {
        if (position in retriedPositions) return
        retriedPositions.add(position)
        lifecycleScope.launch {
            val ok = SynologyApi.ensureLoggedIn(this@ReaderActivity, force = true)
            if (ok) binding.readerRecycler.adapter?.notifyItemChanged(position)
        }
    }
}
