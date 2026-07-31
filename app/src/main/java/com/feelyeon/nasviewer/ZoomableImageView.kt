package com.feelyeon.nasviewer

import android.content.Context
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.appcompat.widget.AppCompatImageView

/**
 * An ImageView that supports pinch-to-zoom and pan, while still letting a parent
 * RecyclerView (vertical webtoon scroll) or ViewPager2 (horizontal PDF paging) handle
 * the gesture normally when the image isn't zoomed in. The trick: grab the gesture on
 * ACTION_DOWN, but hand it back to the parent (requestDisallowInterceptTouchEvent(false))
 * the moment a single-finger move is seen while not zoomed — the parent then intercepts
 * on the next event and takes over scrolling/paging as if we were never here.
 */
class ZoomableImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : AppCompatImageView(context, attrs) {

    enum class FitMode {
        // PDF pages: view bounds are fixed (one page = one screen), fit the image
        // entirely inside them like classic ScaleType.FIT_CENTER.
        FIT_CENTER_FIXED_BOUNDS,
        // Webtoon cuts: view width is fixed but height is wrap_content — fit to width
        // and grow the view's height to match, like the old adjustViewBounds+fitCenter.
        FIT_WIDTH_AUTO_HEIGHT
    }

    companion object {
        private const val MIN_SCALE = 1f
        private const val MAX_SCALE = 4f
        private const val DOUBLE_TAP_SCALE = 2.5f
        private const val ZOOM_EPSILON = 0.01f
    }

    var fitMode: FitMode = FitMode.FIT_CENTER_FIXED_BOUNDS
    // Reports the tap's x position as a 0f..1f fraction of the view's width, so callers
    // can split the view into left/center/right zones (tap-to-page like Ridibooks) without
    // this view needing to know anything about paging.
    var singleTapListener: ((Float) -> Unit)? = null

    private val baseMatrix = Matrix()
    private val drawMatrix = Matrix()
    private var extraScale = 1f
    private var lastX = 0f
    private var lastY = 0f

    init {
        scaleType = ScaleType.MATRIX
    }

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val newScale = (extraScale * detector.scaleFactor).coerceIn(MIN_SCALE, MAX_SCALE)
            val factor = newScale / extraScale
            extraScale = newScale
            drawMatrix.postScale(factor, factor, detector.focusX, detector.focusY)
            constrainTranslation()
            imageMatrix = drawMatrix
            return true
        }
    })

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (extraScale > MIN_SCALE + ZOOM_EPSILON) {
                resetZoomOnly()
            } else {
                val factor = DOUBLE_TAP_SCALE / extraScale
                extraScale = DOUBLE_TAP_SCALE
                drawMatrix.postScale(factor, factor, e.x, e.y)
                constrainTranslation()
                imageMatrix = drawMatrix
            }
            return true
        }

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            if (width > 0) singleTapListener?.invoke(e.x / width)
            return true
        }
    })

    override fun setImageDrawable(drawable: Drawable?) {
        super.setImageDrawable(drawable)
        applyBaseFit()
    }

    override fun setImageBitmap(bm: android.graphics.Bitmap?) {
        super.setImageBitmap(bm)
        applyBaseFit()
    }

    private fun applyBaseFit() {
        extraScale = 1f
        baseMatrix.reset()
        drawMatrix.reset()
        val d = drawable
        if (d == null || width == 0 || height == 0 || d.intrinsicWidth <= 0 || d.intrinsicHeight <= 0) {
            imageMatrix = drawMatrix
            return
        }
        val iw = d.intrinsicWidth.toFloat()
        val ih = d.intrinsicHeight.toFloat()

        when (fitMode) {
            FitMode.FIT_WIDTH_AUTO_HEIGHT -> {
                val scale = width / iw
                val targetHeight = (ih * scale).toInt().coerceAtLeast(1)
                if (layoutParams.height != targetHeight) {
                    layoutParams = layoutParams.also { it.height = targetHeight }
                }
                baseMatrix.setScale(scale, scale)
            }
            FitMode.FIT_CENTER_FIXED_BOUNDS -> {
                val scale = minOf(width / iw, height / ih)
                val dx = (width - iw * scale) / 2f
                val dy = (height - ih * scale) / 2f
                baseMatrix.setScale(scale, scale)
                baseMatrix.postTranslate(dx, dy)
            }
        }
        drawMatrix.set(baseMatrix)
        imageMatrix = drawMatrix
    }

    private fun resetZoomOnly() {
        extraScale = 1f
        drawMatrix.set(baseMatrix)
        imageMatrix = drawMatrix
    }

    private fun constrainTranslation() {
        val d = drawable ?: return
        val bounds = RectF(0f, 0f, d.intrinsicWidth.toFloat(), d.intrinsicHeight.toFloat())
        drawMatrix.mapRect(bounds)

        val dx: Float
        val dy: Float
        dx = if (bounds.width() <= width) {
            width / 2f - bounds.centerX()
        } else if (bounds.left > 0) {
            -bounds.left
        } else if (bounds.right < width) {
            width - bounds.right
        } else 0f

        dy = if (bounds.height() <= height) {
            height / 2f - bounds.centerY()
        } else if (bounds.top > 0) {
            -bounds.top
        } else if (bounds.bottom < height) {
            height - bounds.bottom
        } else 0f

        if (dx != 0f || dy != 0f) drawMatrix.postTranslate(dx, dy)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w != oldw || h != oldh) applyBaseFit()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
        val zoomed = extraScale > MIN_SCALE + ZOOM_EPSILON

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.x
                lastY = event.y
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount == 1 && !scaleDetector.isInProgress) {
                    if (zoomed) {
                        val dx = event.x - lastX
                        val dy = event.y - lastY
                        drawMatrix.postTranslate(dx, dy)
                        constrainTranslation()
                        imageMatrix = drawMatrix
                        lastX = event.x
                        lastY = event.y
                    } else {
                        parent?.requestDisallowInterceptTouchEvent(false)
                    }
                } else {
                    lastX = event.x
                    lastY = event.y
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        return true
    }
}
