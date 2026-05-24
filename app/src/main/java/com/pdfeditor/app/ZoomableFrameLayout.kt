package com.pdfeditor.app

import android.animation.ValueAnimator
import android.content.Context
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout

class ZoomableFrameLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private var currentScale = 1f
    private var currentTransX = 0f
    private var currentTransY = 0f
    private var lastFocusX = 0f
    private var lastFocusY = 0f
    private var isScaling = false

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            isScaling = true
            lastFocusX = detector.focusX
            lastFocusY = detector.focusY
            return true
        }

        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val oldScale = currentScale
            currentScale *= detector.scaleFactor
            currentScale = currentScale.coerceIn(1f, 6f)

            val focusX = detector.focusX
            val focusY = detector.focusY
            val ratio = currentScale / oldScale
            currentTransX = focusX - (focusX - currentTransX) * ratio
            currentTransY = focusY - (focusY - currentTransY) * ratio

            currentTransX += focusX - lastFocusX
            currentTransY += focusY - lastFocusY
            lastFocusX = focusX
            lastFocusY = focusY

            constrainPan()
            applyTransform()
            return true
        }

        override fun onScaleEnd(detector: ScaleGestureDetector) {
            isScaling = false
        }
    })

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (currentScale > 1.2f) {
                animateToScale(1f, e.x, e.y)
            } else {
                animateToScale(3f, e.x, e.y)
            }
            return true
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            if (currentScale > 1.05f && !isScaling) {
                currentTransX -= distanceX
                currentTransY -= distanceY
                constrainPan()
                applyTransform()
                return true
            }
            return false
        }
    })

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (ev.pointerCount >= 2) return true
        if (currentScale > 1.05f) return true
        gestureDetector.onTouchEvent(ev)
        return false
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
        return true
    }

    private fun constrainPan() {
        if (currentScale <= 1f) {
            currentTransX = 0f
            currentTransY = 0f
            return
        }
        val maxX = width * (currentScale - 1f) / 2f
        val maxY = height * (currentScale - 1f) / 2f
        currentTransX = currentTransX.coerceIn(-maxX, maxX)
        currentTransY = currentTransY.coerceIn(-maxY, maxY)
    }

    private fun applyTransform() {
        pivotX = 0f
        pivotY = 0f
        scaleX = currentScale
        scaleY = currentScale
        translationX = currentTransX
        translationY = currentTransY
        // Prevent flickering during transform
        if (currentScale != 1f) {
            setLayerType(LAYER_TYPE_HARDWARE, null)
        } else {
            setLayerType(LAYER_TYPE_NONE, null)
        }
    }

    private fun animateToScale(targetScale: Float, focusX: Float, focusY: Float) {
        val startScale = currentScale
        val startTransX = currentTransX
        val startTransY = currentTransY

        val endTransX: Float
        val endTransY: Float
        if (targetScale <= 1f) {
            endTransX = 0f
            endTransY = 0f
        } else {
            val ratio = targetScale / startScale
            endTransX = focusX - (focusX - startTransX) * ratio
            endTransY = focusY - (focusY - startTransY) * ratio
        }

        val animator = ValueAnimator.ofFloat(0f, 1f)
        animator.duration = 250
        animator.interpolator = DecelerateInterpolator()
        animator.addUpdateListener { anim ->
            val fraction = anim.animatedValue as Float
            currentScale = startScale + (targetScale - startScale) * fraction
            currentTransX = startTransX + (endTransX - startTransX) * fraction
            currentTransY = startTransY + (endTransY - startTransY) * fraction
            constrainPan()
            applyTransform()
        }
        animator.start()
    }

    fun resetZoom() {
        currentScale = 1f
        currentTransX = 0f
        currentTransY = 0f
        applyTransform()
    }

    fun isZoomed(): Boolean = currentScale > 1.05f
}
